package com.venkras.dialerpatch;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class InterruptHook {

    private static final String PLAY_ANCHOR = "playAudioForLocalOrRemoteInternal";
    private static final String PLAYER_ANCHOR = "state change to blocking";
    private static volatile boolean loggedTarget = false;

    static void install(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.appInfo == null || lpparam.appInfo.sourceDir == null || lpparam.appInfo.dataDir == null) {
            return;
        }
        final File modeFile = new File(lpparam.appInfo.dataDir, "files/custom_sounds/mode");
        try (DexKitBridge bridge = DexKitBridge.create(lpparam.appInfo.sourceDir)) {
            List<MethodData> play = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(PLAY_ANCHOR)));
            List<MethodData> anchor = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(PLAYER_ANCHOR)));
            if (play.size() != 1 || anchor.isEmpty()) {
                XposedBridge.log("[InterruptHook] anchors not found: play=" + play.size() + " player=" + anchor.size());
                return;
            }
            final Class<?> playerClass = anchor.get(0).getMethodInstance(lpparam.classLoader).getDeclaringClass();
            Method playMethod = play.get(0).getMethodInstance(lpparam.classLoader);
            XposedBridge.hookMethod(playMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!isInterrupt(modeFile)) {
                            return;
                        }
                        Object player = findPlayer(param.thisObject, playerClass);
                        if (player == null) {
                            return;
                        }
                        cancelCurrent(player);
                    } catch (Throwable t) {
                        XposedBridge.log("[InterruptHook] error: " + t);
                    }
                }
            });
            XposedBridge.log("[InterruptHook] hooked " + playMethod.getDeclaringClass().getName() + "." + playMethod.getName());
        } catch (Throwable t) {
            XposedBridge.log("[InterruptHook] install error: " + t);
        }
    }

    private static boolean isInterrupt(File f) {
        if (!f.exists()) {
            return false;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s = r.readLine();
            return s != null && "interrupt".equals(s.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isFramework(Class<?> c) {
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("android.") || n.startsWith("javax.")
            || n.startsWith("kotlin.") || n.startsWith("dalvik.") || n.startsWith("j$.");
    }

    private static Object findPlayer(Object self, Class<?> playerClass) throws Exception {
        for (Field f : self.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object v = f.get(self);
            if (v != null && playerClass.isInstance(v)) {
                return v;
            }
        }
        return null;
    }

    private static Method cancelOf(Class<?> c) {
        for (Class<?> i : c.getInterfaces()) {
            Method[] ms = i.getDeclaredMethods();
            if (ms.length != 4) {
                continue;
            }
            Method voidMethod = null;
            int nonVoid = 0;
            boolean ok = true;
            for (Method m : ms) {
                if (m.getParameterTypes().length != 0) {
                    ok = false;
                    break;
                }
                if (m.getReturnType() == void.class) {
                    voidMethod = m;
                } else {
                    nonVoid++;
                }
            }
            if (ok && voidMethod != null && nonVoid == 3) {
                return voidMethod;
            }
        }
        return null;
    }

    private static void cancelCurrent(Object player) throws Exception {
        List<Object[]> hits = new ArrayList<>();
        for (Field pf : player.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(pf.getModifiers())) {
                continue;
            }
            pf.setAccessible(true);
            Object o = pf.get(player);
            if (o == null || isFramework(o.getClass())) {
                continue;
            }
            for (Field vf : o.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(vf.getModifiers())) {
                    continue;
                }
                vf.setAccessible(true);
                Object v = vf.get(o);
                if (v == null) {
                    continue;
                }
                Method m = cancelOf(v.getClass());
                if (m != null) {
                    hits.add(new Object[] {v, m, pf.getName() + "." + vf.getName() + " (" + v.getClass().getName() + ")"});
                }
            }
        }
        if (hits.isEmpty()) {
            return;
        }
        if (hits.size() != 1) {
            if (!loggedTarget) {
                loggedTarget = true;
                XposedBridge.log("[InterruptHook] cancel target not unique: " + hits.size());
                for (Object[] h : hits) {
                    XposedBridge.log("[InterruptHook]   candidate " + h[2]);
                }
            }
            return;
        }
        Object[] h = hits.get(0);
        if (!loggedTarget) {
            loggedTarget = true;
            XposedBridge.log("[InterruptHook] cancel target: " + h[2]);
        }
        ((Method) h[1]).invoke(h[0]);
        XposedBridge.log("[InterruptHook] cancelled previous playback");
    }
}
