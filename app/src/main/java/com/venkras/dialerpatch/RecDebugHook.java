package com.venkras.dialerpatch;

import java.lang.reflect.Constructor;
import java.util.List;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RecDebugHook {

    private static void log(String s) {
        XposedBridge.log("[RecDebug] " + s);
    }

    private static String caller() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("de.robv") || c.startsWith("java.lang.reflect")
                || c.startsWith("LSPHooker") || c.contains("RecDebugHook")) {
                continue;
            }
            sb.append(c).append('.').append(e.getMethodName()).append(" < ");
            if (++n >= 5) {
                break;
            }
        }
        return sb.toString();
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.appInfo == null || lpparam.appInfo.sourceDir == null) {
            return;
        }
        try (DexKitBridge bridge = DexKitBridge.create(lpparam.appInfo.sourceDir)) {
            List<MethodData> st = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings("StreamState(recordDownlink=")));
            if (st.size() == 1) {
                Class<?> c = st.get(0).getMethodInstance(lpparam.classLoader).getDeclaringClass();
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    XposedBridge.hookMethod(k, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            log("created " + p.thisObject + " from " + caller());
                        }
                    });
                }
                log("hooked StreamState " + c.getName());
            } else {
                log("StreamState anchor count: " + st.size());
            }

            List<MethodData> pb = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings("playback detected")));
            if (pb.size() >= 1 && pb.size() <= 3) {
                for (MethodData md : pb) {
                    java.lang.reflect.Method m = md.getMethodInstance(lpparam.classLoader);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            log("playback request: " + p.method.getDeclaringClass().getName()
                                + "." + p.method.getName());
                        }
                    });
                    log("hooked " + m.getDeclaringClass().getName() + "." + m.getName());
                }
            } else {
                log("playback anchor count: " + pb.size());
            }
        } catch (Throwable t) {
            log("error: " + t);
        }
    }
}
