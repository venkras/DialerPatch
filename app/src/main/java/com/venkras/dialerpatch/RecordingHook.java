package com.venkras.dialerpatch;

import java.lang.reflect.Method;
import java.util.List;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RecordingHook {

    private static final String CAN_RECORD_ANCHOR = "Call recording is disabled in the current country";
    private static final String GEOFENCE_ANCHOR = "withinCallRecordingGeoFence";

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        String sourceDir = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        if (sourceDir == null) {
            return;
        }
        try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
            hookTrue(bridge, lpparam, CAN_RECORD_ANCHOR, "CanRecord");
            hookTrue(bridge, lpparam, GEOFENCE_ANCHOR, "Geofence");
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
}
