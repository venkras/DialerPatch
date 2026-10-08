package com.venkras.dialerpatch;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SilenceAudioHook {

    private static final String TAG = "[SilenceAudioHook] ";

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XC_MethodHook dataSourceHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    String dataSource = "";
                    if (param.args.length > 0 && param.args[0] != null) {
                        dataSource = param.args[0].toString().toLowerCase();
                    }

                    if (dataSource.contains("callrecordingprompt") || 
                        dataSource.contains("starting.wav") || 
                        dataSource.contains("ending.wav")) {
                        
                        XposedHelpers.setAdditionalInstanceField(param.thisObject, "mute_this_player", true);
                        XposedBridge.log(TAG + "Marked recording prompt audio for muting: " + dataSource);
                    }
                }
            };

            XposedHelpers.findAndHookMethod(MediaPlayer.class, "setDataSource", String.class, dataSourceHook);
            XposedHelpers.findAndHookMethod(MediaPlayer.class, "setDataSource", Context.class, Uri.class, dataSourceHook);

            XposedHelpers.findAndHookMethod(MediaPlayer.class, "start", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object flag = XposedHelpers.getAdditionalInstanceField(param.thisObject, "mute_this_player");
                    if (flag instanceof Boolean && (Boolean) flag) {
                        MediaPlayer player = (MediaPlayer) param.thisObject;
                        player.setVolume(0.0f, 0.0f);
                        XposedBridge.log(TAG + "Successfully silenced MediaPlayer.start()!");
                    }
                }
            });

            XposedBridge.log(TAG + "Hook installed successfully.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error: " + t.getMessage());
        }
    }
}
