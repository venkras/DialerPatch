package com.venkras.dialerpatch;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SonicHook {

    private static final String PLAY_STRING = "playAudioForLocalOrRemoteInternal";
    private static final String KEY_PREFIX = "SONIC_KEY_";
    private static final String PATH_MARK = "datadownloadfile";
    private static final AtomicInteger TOKEN = new AtomicInteger();

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        String sourceDir = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        if (sourceDir == null) {
            return;
        }
        Method target = null;
        try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
            List<MethodData> found = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(PLAY_STRING)
                )
            );
            XposedBridge.log("[SonicHook] methods found: " + found.size());
            if (!found.isEmpty()) {
                target = found.get(0).getMethodInstance(lpparam.classLoader);
            }
        } catch (Throwable t) {
            XposedBridge.log("[SonicHook] search error: " + t);
        }
        if (target == null) {
            XposedBridge.log("[SonicHook] play method not found");
            return;
        }
        XposedBridge.log("[SonicHook] hooking " + target.getDeclaringClass().getName() + "." + target.getName());
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object self = param.thisObject;
                    String emoji = null;
                    Field pathField = null;
                    boolean pathIsFuture = false;
                    String originalPath = null;

                    for (Field f : self.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        Object raw = f.get(self);
                        Object v = raw;
                        boolean isFuture = false;
                        if (raw instanceof Future && ((Future<?>) raw).isDone()) {
                            try {
                                v = ((Future<?>) raw).get();
                                isFuture = true;
                            } catch (Throwable t) {
                                v = null;
                            }
                        }
                        String s = String.valueOf(v);
                        if (s.startsWith("Optional[" + KEY_PREFIX) && s.endsWith("]")) {
                            emoji = s.substring(9 + KEY_PREFIX.length(), s.length() - 1).toLowerCase();
                        } else if (v instanceof String && s.contains(PATH_MARK)) {
                            pathField = f;
                            pathIsFuture = isFuture;
                            originalPath = s;
                        }
                    }

                    if (emoji == null || pathField == null) {
                        return;
                    }

                    int idx = originalPath.indexOf("/files/");
                    if (idx < 0) {
                        return;
                    }
                    String base = originalPath.substring(0, idx) + "/files/custom_sounds/";

                    scheduleUnlock(self, base);

                    File custom = new File(base + emoji + ".ogg");
                    if (!custom.isFile() || !custom.canRead() || custom.length() == 0) {
                        XposedBridge.log("[SonicHook] " + emoji + ": no custom file, playing original");
                        return;
                    }

                    String newPath = custom.getAbsolutePath();
                    if (pathIsFuture) {
                        pathField.set(self, CompletableFuture.completedFuture(newPath));
                    } else {
                        pathField.set(self, newPath);
                    }
                    XposedBridge.log("[SonicHook] " + emoji + ": replaced with " + newPath);
                } catch (Throwable t) {
                    XposedBridge.log("[SonicHook] replace error: " + t);
                }
            }
        });
    }

    private static int readSeconds(File file) {
        try {
            if (!file.isFile() || !file.canRead()) {
                return 0;
            }
            byte[] data = new byte[(int) Math.min(file.length(), 16)];
            try (FileInputStream in = new FileInputStream(file)) {
                int n = in.read(data);
                if (n <= 0) {
                    return 0;
                }
                int sec = Integer.parseInt(new String(data, 0, n).trim());
                return Math.max(0, Math.min(sec, 30));
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Object firstStateValue(Class<?> type) throws Exception {
        if (type.isPrimitive() || type.isArray() || type.isEnum() || type.isInterface()) {
            return null;
        }
        String n = type.getName();
        if (n.startsWith("java.") || n.startsWith("android.") || n.startsWith("j$.")) {
            return null;
        }
        List<Object> values = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                f.setAccessible(true);
                values.add(f.get(null));
            }
        }
        return values.size() == 3 ? values.get(0) : null;
    }

    private static void scheduleUnlock(Object self, String base) {
        try {
            final int token = TOKEN.incrementAndGet();
            int seconds = readSeconds(new File(base + "lock_seconds"));
            if (seconds <= 0) {
                return;
            }
            Object player = null;
            Method setState = null;
            Object idle = null;
            for (Field f : self.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(self);
                if (v == null) {
                    continue;
                }
                String n = v.getClass().getName();
                if (n.startsWith("java.") || n.startsWith("android.") || n.startsWith("j$.")) {
                    continue;
                }
                for (Method m : v.getClass().getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 1 || m.getReturnType() != void.class) {
                        continue;
                    }
                    Object first = firstStateValue(p[0]);
                    if (first != null) {
                        player = v;
                        setState = m;
                        idle = first;
                        break;
                    }
                }
                if (player != null) {
                    break;
                }
            }
            if (player == null) {
                XposedBridge.log("[SonicHook] player state method not found, lock time not applied");
                return;
            }
            final Object fp = player;
            final Method fm = setState;
            final Object fi = idle;
            XposedBridge.log("[SonicHook] unlock scheduled in " + seconds + " s");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (TOKEN.get() != token) {
                    return;
                }
                try {
                    fm.setAccessible(true);
                    fm.invoke(fp, fi);
                    XposedBridge.log("[SonicHook] lock reset to idle");
                } catch (Throwable t) {
                    XposedBridge.log("[SonicHook] unlock error: " + t);
                }
            }, seconds * 1000L);
        } catch (Throwable t) {
            XposedBridge.log("[SonicHook] schedule error: " + t);
        }
    }
}
