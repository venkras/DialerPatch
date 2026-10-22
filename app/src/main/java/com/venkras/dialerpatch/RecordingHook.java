package com.venkras.dialerpatch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RecordingHook {

    private static final String CAN_RECORD_ANCHOR = "Call recording is disabled in the current country";
    private static final String GEOFENCE_ANCHOR = "withinCallRecordingGeoFence";
    private static final String START_ANCHOR = "CallRecordingButtonController_startCallRecording_playStartingAudio";
    private static final String CALLBACK_ANCHOR = "failed to play starting audio";
    private static final String TAG_FIELD = "dialerpatch_start_audio";

    private static final ThreadLocal<List<Object>> createdCallbacks = new ThreadLocal<>();

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        String sourceDir = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        if (sourceDir == null) {
            return;
        }
        try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
            hookTrue(bridge, lpparam, CAN_RECORD_ANCHOR, "CanRecord");
            hookTrue(bridge, lpparam, GEOFENCE_ANCHOR, "Geofence");
            hookStartAudioFailure(bridge, lpparam);
        } catch (Throwable t) {
            XposedBridge.log("[RecordingHook] error: " + t);
        }
    }

    private static void hookTrue(DexKitBridge bridge, XC_LoadPackage.LoadPackageParam lpparam,
                                 String anchor, String label) {
        try {
            List<MethodData> found = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(anchor)
                )
            );
            if (found.size() != 1) {
                XposedBridge.log("[RecordingHook] " + label + ": expected 1 method, found " + found.size());
                return;
            }
            Method m = found.get(0).getMethodInstance(lpparam.classLoader);
            if (m.getParameterTypes().length != 0 || m.getReturnType() != boolean.class) {
                XposedBridge.log("[RecordingHook] " + label + ": unexpected signature " + m);
                return;
            }
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true));
            XposedBridge.log("[RecordingHook] " + label + ": hooked " + m.getDeclaringClass().getName() + "." + m.getName());
        } catch (Throwable t) {
            XposedBridge.log("[RecordingHook] " + label + " error: " + t);
        }
    }

    // Before recording starts, the Dialer plays a disclosure. Countries without a built-in file use
    // TTS, and when the TTS engine has no voice for the language (e.g. Russian on the OnePlus
    // engine) the disclosure fails and the Dialer cancels the recording. Treat that failure as a
    // finished disclosure so the recording starts anyway.
    private static void hookStartAudioFailure(DexKitBridge bridge, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            List<MethodData> starts = bridge.findMethod(
                FindMethod.create().matcher(MethodMatcher.create().usingStrings(START_ANCHOR)));
            List<ClassData> callbacks = bridge.findClass(
                FindClass.create().matcher(ClassMatcher.create().usingStrings(CALLBACK_ANCHOR)));
            if (starts.size() != 1 || callbacks.size() != 1) {
                XposedBridge.log("[RecordingHook] StartAudio: anchors not found: start=" + starts.size()
                    + " callback=" + callbacks.size());
                return;
            }
            Method start = starts.get(0).getMethodInstance(lpparam.classLoader);
            Class<?> callbackClass = callbacks.get(0).getInstance(lpparam.classLoader);

            Method onFailure = null;
            Method onSuccess = null;
            for (Method m : callbackClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == Throwable.class) {
                    onFailure = m;
                } else if (p.length == 1 && p[0] == Object.class && m.getReturnType() == void.class) {
                    onSuccess = m;
                }
            }
            Object completed = findCompleted(bridge, lpparam);
            if (onFailure == null || onSuccess == null || completed == null) {
                XposedBridge.log("[RecordingHook] StartAudio: callback shape not recognized");
                return;
            }

            // startCallRecording creates the callback for the disclosure result last; remember it.
            XposedBridge.hookMethod(start, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    createdCallbacks.set(new ArrayList<>());
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    List<Object> created = createdCallbacks.get();
                    createdCallbacks.remove();
                    if (created != null && !created.isEmpty()) {
                        XposedHelpers.setAdditionalInstanceField(created.get(created.size() - 1), TAG_FIELD, true);
                    }
                }
            });
            for (Constructor<?> c : callbackClass.getDeclaredConstructors()) {
                XposedBridge.hookMethod(c, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        List<Object> created = createdCallbacks.get();
                        if (created != null) {
                            created.add(param.thisObject);
                        }
                    }
                });
            }

            final Method success = onSuccess;
            final Object result = completed;
            XposedBridge.hookMethod(onFailure, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(param.thisObject, TAG_FIELD))) {
                        return;
                    }
                    XposedBridge.log("[RecordingHook] StartAudio: disclosure failed (" + param.args[0]
                        + "), starting recording anyway");
                    success.setAccessible(true);
                    success.invoke(param.thisObject, result);
                    param.setResult(null);
                }
            });
            XposedBridge.log("[RecordingHook] StartAudio: hooked " + callbackClass.getName());
        } catch (Throwable t) {
            XposedBridge.log("[RecordingHook] StartAudio error: " + t);
        }
    }

    // The disclosure result is an enum with COMPLETED, CANCELED, FAILED and MUTED.
    private static Object findCompleted(DexKitBridge bridge, XC_LoadPackage.LoadPackageParam lpparam) {
        List<ClassData> found = bridge.findClass(FindClass.create().matcher(
            ClassMatcher.create().superClass("java.lang.Enum")
                .usingStrings("COMPLETED", "CANCELED", "FAILED", "MUTED")));
        for (ClassData data : found) {
            try {
                Object[] values = data.getInstance(lpparam.classLoader).getEnumConstants();
                if (values == null || values.length != 4) {
                    continue;
                }
                for (Object v : values) {
                    if ("COMPLETED".equals(((Enum<?>) v).name())) {
                        return v;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
