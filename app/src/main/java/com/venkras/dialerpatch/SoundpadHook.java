package com.venkras.dialerpatch;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XCallback;

public class SoundpadHook {

    private static final String PLAYER_ANCHOR = "state change to blocking";
    private static final String PLAY_ANCHOR = "playAllowingMutedCall(%s)";

    private static volatile Object player;
    private static volatile Object icd;
    private static Method playMethod;
    private static Class<?> icdClass;
    private static long lastNotReadyLog = 0;
    private static volatile long lastCapture = 0;

    private static void log(String s) {
        XposedBridge.log("[Soundpad] " + s);
    }

    static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.appInfo == null || lpparam.appInfo.sourceDir == null || lpparam.appInfo.dataDir == null) {
            return;
        }
        final File dir = new File(lpparam.appInfo.dataDir, "files/custom_sounds");
        ClassLoader cl = lpparam.classLoader;
        try (DexKitBridge bridge = DexKitBridge.create(lpparam.appInfo.sourceDir)) {
            List<MethodData> play = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(PLAY_ANCHOR)));
            List<MethodData> anchor = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(PLAYER_ANCHOR)));
            if (play.size() != 1 || anchor.isEmpty()) {
                log("anchors not found: play=" + play.size() + " player=" + anchor.size());
                return;
            }
            playMethod = play.get(0).getMethodInstance(cl);
            playMethod.setAccessible(true);
            icdClass = playMethod.getDeclaringClass();
            log("play method params: " + playMethod.getParameterTypes().length
                + " types=" + java.util.Arrays.toString(playMethod.getParameterTypes())
                + " return=" + playMethod.getReturnType().getName());
            Class<?> playerClass = anchor.get(0).getMethodInstance(cl).getDeclaringClass();

            XC_MethodHook capture = new XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.thisObject != null) {
                        player = p.thisObject;
                        lastCapture = System.currentTimeMillis();
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject != null) {
                        player = p.thisObject;
                        lastCapture = System.currentTimeMillis();
                    }
                }
            };
            for (Constructor<?> k : playerClass.getDeclaredConstructors()) {
                XposedBridge.hookMethod(k, capture);
            }
            for (Method m : playerClass.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(m, capture);
                }
            }
            log("ready: icd=" + icdClass.getName() + "." + playMethod.getName()
                + " player=" + playerClass.getName());
        } catch (Throwable t) {
            log("install error: " + t);
            return;
        }
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(dir);
            }
        }, "DialerPatch-soundpad");
        th.setDaemon(true);
        th.start();
    }

    private static void loop(File dir) {
        File cmd = new File(dir, "cmd");
        while (true) {
            try {
                Thread.sleep(300);
                if (cmd.exists()) {
                    handle(dir, cmd);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                log("loop error: " + t);
            }
        }
    }

    private static boolean isFramework(Class<?> c) {
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("android.") || n.startsWith("javax.")
            || n.startsWith("kotlin.") || n.startsWith("dalvik.") || n.startsWith("j$.");
    }

    private static Object findIcd(Object p) throws Exception {
        for (Field pf : p.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(pf.getModifiers())) {
                continue;
            }
            pf.setAccessible(true);
            Object o = pf.get(p);
            if (o == null) {
                continue;
            }
            if (icdClass.isInstance(o)) {
                return o;
            }
            if (isFramework(o.getClass())) {
                continue;
            }
            for (Field vf : o.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(vf.getModifiers())) {
                    continue;
                }
                vf.setAccessible(true);
                Object v = vf.get(o);
                if (v != null && icdClass.isInstance(v)) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String dump(Object o) {
        if (o == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (Field fl : o.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(fl.getModifiers())) {
                    continue;
                }
                fl.setAccessible(true);
                Object v = fl.get(o);
                String s = String.valueOf(v);
                if (s.length() > 60) {
                    s = s.substring(0, 60);
                }
                sb.append(fl.getName()).append('=').append(s).append(' ');
            }
        } catch (Throwable t) {
            sb.append("dump error ").append(t);
        }
        return sb.toString();
    }

    private static void notReady(String why) {
        long now = System.currentTimeMillis();
        if (now - lastNotReadyLog > 5000) {
            lastNotReadyLog = now;
            log("not ready: " + why);
        }
    }

    private static void handle(File dir, File cmd) throws Exception {
        Object p = player;
        if (p == null) {
            notReady("player not captured yet");
            return;
        }
        Object ic = icd;
        if (ic == null) {
            ic = findIcd(p);
            if (ic == null) {
                notReady("icd instance not found");
                return;
            }
            icd = ic;
            log("icd instance found");
        }
        String name = null;
        try (BufferedReader r = new BufferedReader(new FileReader(cmd))) {
            name = r.readLine();
        } catch (Throwable ignored) {
        }
        long age = System.currentTimeMillis() - cmd.lastModified();
        if (!cmd.delete()) {
            log("cannot delete cmd file, skipping");
            Thread.sleep(3000);
            return;
        }
        if (age > 10000) {
            log("stale command ignored");
            return;
        }
        if (name == null) {
            return;
        }
        name = name.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("..")) {
            log("bad name: " + name);
            return;
        }
        File f = new File(dir, name);
        if (!f.exists()) {
            log("no such file: " + name);
            return;
        }
        log("seconds since player capture: " + ((System.currentTimeMillis() - lastCapture) / 1000));
        Object res = playMethod.invoke(ic, f.getAbsolutePath());
        log("play " + name + " -> " + (res == null ? "null" : res.getClass().getName()));
        if (res != null) {
            try {
                Method get = res.getClass().getMethod("get", long.class, java.util.concurrent.TimeUnit.class);
                Object val = get.invoke(res, 3000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                log("future result: " + (val == null ? "null" : val.getClass().getName()) + " " + dump(val));
            } catch (java.lang.reflect.InvocationTargetException ite) {
                log("future failed: " + ite.getCause());
            } catch (Throwable t) {
                log("future get not available: " + t);
            }
        }
    }
}
