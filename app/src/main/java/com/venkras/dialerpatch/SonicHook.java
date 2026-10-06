package com.venkras.dialerpatch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
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
                    StringBuilder sb = new StringBuilder();
                    boolean sonic = false;
                    for (Field f : param.thisObject.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        Object v = f.get(param.thisObject);
                        if (v instanceof Future && ((Future<?>) v).isDone()) {
                            try {
                                v = ((Future<?>) v).get();
                            } catch (Throwable t) {
                                v = "future-error";
                            }
                        }
                        String s = String.valueOf(v);
                        if (s.length() > 200) {
                            s = s.substring(0, 200);
                        }
                        if (s.contains("SONIC_KEY")) {
                            sonic = true;
                        }
                        sb.append(f.getName()).append('=').append(s).append(" | ");
                    }
                    if (sonic) {
                        XposedBridge.log("[SonicHook] play: " + sb);
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[SonicHook] log error: " + t);
                }
            }
        });
    }
}
