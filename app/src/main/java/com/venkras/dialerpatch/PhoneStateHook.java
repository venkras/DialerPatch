package com.venkras.dialerpatch;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runs in system_server. On Pixel the Dialer is a privileged system app; elsewhere it is a
 * regular app and audioserver refuses it two permissions:
 * - MODIFY_PHONE_STATE: Audio Emoji reach the other person through the Telephony Tx device,
 *   otherwise playback fails with "modify phone state not allowed".
 * - CAPTURE_AUDIO_OUTPUT: call recording reads the VOICE_CALL source, otherwise it fails with
 *   "missing perms for source 4".
 * This reports both permissions to audioserver for the Dialer only; the Dialer does not get them
 * anywhere else.
 */
public class PhoneStateHook {

    private static final String DIALER = "com.google.android.dialer";
    private static final Set<String> PERMISSIONS = new HashSet<>(Arrays.asList(
        "android.permission.MODIFY_PHONE_STATE",
        "android.permission.CAPTURE_AUDIO_OUTPUT"
    ));
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
                            original.test(uid, perm) || (PERMISSIONS.contains(perm) && isDialer(self, uid));
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
