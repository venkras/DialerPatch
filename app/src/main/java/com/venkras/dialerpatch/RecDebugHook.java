package com.venkras.dialerpatch;

import java.lang.reflect.Method;
import java.util.List;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
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
            if (++n >= 4) {
                break;
            }
        }
        return sb.toString();
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.appInfo == null || lpparam.appInfo.sourceDir == null) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        try (DexKitBridge bridge = DexKitBridge.create(lpparam.appInfo.sourceDir)) {
            List<MethodData> st = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings("StreamState(recordDownlink=")));
            if (st.size() != 1) {
                log("StreamState anchor count: " + st.size());
                return;
            }
            final Class<?> stateClass = st.get(0).getMethodInstance(cl).getDeclaringClass();
            log("StreamState class " + stateClass.getName());

            List<ClassData> rec = bridge.findClass(FindClass.create().matcher(
                ClassMatcher.create().usingStrings("com/android/dialer/audio/recorder/impl/RecorderImpl")));
            int hookedStart = 0;
            for (ClassData cd : rec) {
                Class<?> c = cd.getInstance(cl);
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 4 && p[0] == boolean.class && p[1] == boolean.class && p[2] == boolean.class) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam a) {
                                log("recorder start: downlink=" + a.args[0] + " uplink=" + a.args[1]
                                    + " playback=" + a.args[2] + " from " + caller());
                            }
                        });
                        hookedStart++;
                        log("hooked recorder start " + c.getName() + "." + m.getName());
                    }
                }
            }
            if (hookedStart == 0) {
                log("recorder start method not found, classes: " + rec.size());
            }

            List<ClassData> fac = bridge.findClass(FindClass.create().matcher(
                ClassMatcher.create().usingStrings("com/android/dialer/audio/recorder/impl/RecorderFactoryImpl")));
            int hookedState = 0;
            for (ClassData cd : fac) {
                Class<?> c = cd.getInstance(cl);
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length >= 2 && p[1] == stateClass) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam a) {
                                log("state set: " + a.args[1]);
                            }
                        });
                        hookedState++;
                        log("hooked state setter " + c.getName() + "." + m.getName());
                    }
                }
            }
            if (hookedState == 0) {
                log("state setter not found, classes: " + fac.size());
            }

            List<MethodData> pb = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings("playback detected")));
            for (MethodData md : pb) {
                Method m = md.getMethodInstance(cl);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam a) {
                        log("playback request: " + a.method.getDeclaringClass().getName()
                            + "." + a.method.getName());
                    }
                });
            }
            log("playback hooks: " + pb.size());
        } catch (Throwable t) {
            log("error: " + t);
        }
    }
}
