package com.venkras.dialerpatch;

import java.util.Collection;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runs in system_server. Audio Emoji reach the other person through the Telephony Tx device,
 * and audioserver allows that only for apps holding MODIFY_PHONE_STATE. On Pixel the Dialer is a
 * privileged system app; elsewhere it is a regular app and remote playback fails with
 * "modify phone state not allowed". This reports the permission to audioserver for the Dialer
 * only; the Dialer does not get it anywhere else.
 */
public class PhoneStateHook {

    private static final String DIALER = "com.google.android.dialer";
    private static final String PERMISSION = "android.permission.MODIFY_PHONE_STATE";
    private static final String PROVIDER = "com.android.server.audio.AudioServerPermissionProvider";

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookConstructor(PROVIDER, lpparam.classLoader,
                Collection.class, BiPredicate.class, Supplier.class, new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam param) {
                        final Object self = param.thisObject;
                        final BiPredicate<Integer, String> original = (BiPredicate<Integer, String>) param.args[1];
                        param.args[1] = (BiPredicate<Integer, String>) (uid, perm) ->
                            original.test(uid, perm) || (PERMISSION.equals(perm) && isDialer(self, uid));
                    }
                });
            XposedBridge.log("[PhoneStateHook] hooked " + PROVIDER);
        } catch (Throwable t) {
            XposedBridge.log("[PhoneStateHook] error: " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isDialer(Object provider, Integer uid) {
        try {
            Map<Integer, ? extends Collection<String>> packages =
                (Map<Integer, ? extends Collection<String>>) XposedHelpers.getObjectField(provider, "mPackageMap");
            Collection<String> names = packages.get(uid % 100000);
            return names != null && names.contains(DIALER);
        } catch (Throwable t) {
            XposedBridge.log("[PhoneStateHook] lookup error: " + t);
            return false;
        }
    }
}
