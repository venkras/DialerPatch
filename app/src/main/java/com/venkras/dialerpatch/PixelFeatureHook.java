package com.venkras.dialerpatch;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class PixelFeatureHook {

    // The -pixel2026 Dialer builds show "This device is not supported" without this feature.
    private static final String[] FEATURES = {
        "com.google.android.feature.PIXEL_2026_EXPERIENCE",
        "com.google.android.feature.PIXEL_2025_EXPERIENCE",
        "com.google.android.feature.PIXEL_2024_EXPERIENCE",
        "com.google.android.feature.PIXEL_EXPERIENCE",
        "com.google.android.apps.dialer.SUPPORTED",
    };

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", lpparam.classLoader,
                "hasSystemFeature", String.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isSpoofed((String) param.args[0])) {
                            param.setResult(true);
                        }
                    }
                });
            XposedBridge.log("[PixelFeatureHook] hooked hasSystemFeature");
        } catch (Throwable t) {
            XposedBridge.log("[PixelFeatureHook] error: " + t);
        }
    }

    private static boolean isSpoofed(String name) {
        if (name == null) {
            return false;
        }
        for (String f : FEATURES) {
            if (f.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
