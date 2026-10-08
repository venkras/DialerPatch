package com.venkras.dialerpatch;

import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.SoundPool;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SilenceAudioHook {

    private static final String TAG = "[SilenceAudioHook] ";

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookSoundPool();
            hookAudioTrack();
            hookMediaPlayer();
            XposedBridge.log(TAG + "Hooks installed successfully");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Installation error: " + t.getMessage());
        }
    }

    private static void hookSoundPool() {
        try {
            XposedHelpers.findAndHookMethod(
                SoundPool.class,
                "play",
                int.class, float.class, float.class, int.class, int.class, float.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isCallFromRecordingPrompt()) {
                            param.args[1] = 0.0f; // leftVolume
                            param.args[2] = 0.0f; // rightVolume
                            XposedBridge.log(TAG + "SoundPool.play volume set to 0");
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "SoundPool hook error: " + t.getMessage());
        }
    }

    private static void hookAudioTrack() {
        try {
            XposedHelpers.findAndHookMethod(
                AudioTrack.class,
                "play",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isCallFromRecordingPrompt()) {
                            AudioTrack track = (AudioTrack) param.thisObject;
                            track.setVolume(0.0f);
                            XposedBridge.log(TAG + "AudioTrack.play volume set to 0");
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "AudioTrack hook error: " + t.getMessage());
        }
    }

    private static void hookMediaPlayer() {
        try {
            XposedHelpers.findAndHookMethod(
                MediaPlayer.class,
                "start",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (isCallFromRecordingPrompt()) {
                            MediaPlayer player = (MediaPlayer) param.thisObject;
                            player.setVolume(0.0f, 0.0f);
                            XposedBridge.log(TAG + "MediaPlayer.start volume set to 0");
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "MediaPlayer hook error: " + t.getMessage());
        }
    }

    private static boolean isCallFromRecordingPrompt() {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement element : stackTrace) {
                String className = element.getClassName().toLowerCase();
                String methodName = element.getMethodName().toLowerCase();
                if (className.contains("callrecording") 
                        || className.contains("prompt") 
                        || methodName.contains("disclaimer") 
                        || methodName.contains("announcement")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
