package com.venkras.dialerpatch;

import android.media.MediaPlayer;
import android.speech.tts.TextToSpeech;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SilenceAudioHook {

    private static final String TAG = "[SilenceAudioHook] ";
    private static final Map<Object, Boolean> mutedPlayers = new WeakHashMap<>();

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookMediaPlayer();
            hookTextToSpeech();
            XposedBridge.log(TAG + "Installed successfully");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Installation error: " + t);
        }
    }

    private static void hookMediaPlayer() {
        try {
            XposedHelpers.findAndHookMethod(
                MediaPlayer.class,
                "setDataSource",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        String path = (String) param.args[0];
                        if (path != null && path.contains("callrecordingprompt")) {
                            mutedPlayers.put(param.thisObject, true);
                            XposedBridge.log(TAG + "Target prompt WAV detected: " + path);
                        }
                    }
                }
            );

            XposedHelpers.findAndHookMethod(
                MediaPlayer.class,
                "start",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (Boolean.TRUE.equals(mutedPlayers.get(param.thisObject))) {
                            MediaPlayer mp = (MediaPlayer) param.thisObject;
                            mp.setVolume(0f, 0f);
                            XposedBridge.log(TAG + "Muted MediaPlayer for recording prompt");
                        }
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "MediaPlayer hook error: " + t);
        }
    }

    private static void hookTextToSpeech() {
        try {
            XposedHelpers.findAndHookMethod(
                TextToSpeech.class,
                "speak",
                CharSequence.class,
                int.class,
                android.os.Bundle.class,
                String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        CharSequence text = (CharSequence) param.args[0];
                        if (text != null) {
                            String str = text.toString().toLowerCase();
                            if (str.contains("recording") || str.contains("запис")) {
                                XposedBridge.log(TAG + "Blocked TTS audio disclaimer: " + str);
                                return TextToSpeech.SUCCESS;
                            }
                        }
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + "TTS hook error: " + t);
        }
    }
}
