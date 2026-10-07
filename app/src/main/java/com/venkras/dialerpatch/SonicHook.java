package com.venkras.dialerpatch;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

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
                    File custom = new File(originalPath.substring(0, idx) + "/files/custom_sounds/" + emoji + ".ogg");
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
}
