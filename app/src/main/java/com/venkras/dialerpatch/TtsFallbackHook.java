package com.venkras.dialerpatch;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Before call recording starts the Dialer synthesizes a disclosure with the default TTS engine
 * (except for the few countries with a built-in file). If the engine has no voice for the
 * language, the Dialer fails with "Language not available for TTS" and cancels the recording.
 * When that happens, report the language as available to the Dialer's TTS wrapper and write a
 * short silent WAV instead of synthesizing, for any language.
 */
public class TtsFallbackHook {

    private static final String TTS_WRAPPER_ANCHOR = "com/android/dialer/audio/audioinjector/fileprovider/BaseTtsInstance";
    private static final String AUDIO_DIR = "/audioinjector/";
    private static final String UNSUPPORTED = "dialerpatch_tts_unsupported";

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        String sourceDir = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        if (sourceDir == null) {
            return;
        }
        final String wrapper;
        try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
            List<ClassData> found = bridge.findClass(FindClass.create()
                .matcher(ClassMatcher.create().usingStrings(TTS_WRAPPER_ANCHOR)));
            if (found.size() != 1) {
                XposedBridge.log("[TtsFallbackHook] expected 1 TTS wrapper class, found " + found.size());
                return;
            }
            wrapper = found.get(0).getName();
        } catch (Throwable t) {
            XposedBridge.log("[TtsFallbackHook] search error: " + t);
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(TextToSpeech.class, "isLanguageAvailable", Locale.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof Integer) || (Integer) result >= TextToSpeech.LANG_AVAILABLE
                            || !calledFrom(wrapper)) {
                        return;
                    }
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, UNSUPPORTED, Boolean.TRUE);
                    XposedBridge.log("[TtsFallbackHook] " + param.args[0] + " not supported by TTS (" + result + "), using silence");
                    param.setResult(TextToSpeech.LANG_AVAILABLE);
                }
            });
            XposedHelpers.findAndHookMethod(TextToSpeech.class, "synthesizeToFile",
                CharSequence.class, Bundle.class, File.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        File file = (File) param.args[2];
                        if (file == null || !file.getAbsolutePath().contains(AUDIO_DIR)
                                || !Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(param.thisObject, UNSUPPORTED))) {
                            return;
                        }
                        try {
                            writeSilence(file);
                        } catch (Throwable t) {
                            XposedBridge.log("[TtsFallbackHook] write error: " + t);
                            return;
                        }
                        notifyDone(param.thisObject, (String) param.args[3]);
                        XposedBridge.log("[TtsFallbackHook] wrote silence to " + file);
                        param.setResult(TextToSpeech.SUCCESS);
                    }
                });
            XposedBridge.log("[TtsFallbackHook] hooked TTS for " + wrapper);
        } catch (Throwable t) {
            XposedBridge.log("[TtsFallbackHook] error: " + t);
        }
    }

    private static boolean calledFrom(String className) {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if (className.equals(e.getClassName())) {
                return true;
            }
        }
        return false;
    }

    // The Dialer waits for onDone of the utterance, as it would after real synthesis.
    private static void notifyDone(Object tts, final String utteranceId) {
        final UtteranceProgressListener listener =
            (UtteranceProgressListener) XposedHelpers.getObjectField(tts, "mUtteranceProgressListener");
        if (listener == null) {
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(50);
                listener.onStart(utteranceId);
                listener.onDone(utteranceId);
            } catch (Throwable t) {
                XposedBridge.log("[TtsFallbackHook] listener error: " + t);
            }
        }, "DialerPatchTts").start();
    }

    // 0.3 s of 16-bit mono PCM silence at 16 kHz.
    private static void writeSilence(File file) throws Exception {
        int rate = 16000;
        int dataLen = rate * 2 * 3 / 10;
        byte[] wav = new byte[44 + dataLen];
        putAscii(wav, 0, "RIFF");
        putInt(wav, 4, 36 + dataLen);
        putAscii(wav, 8, "WAVE");
        putAscii(wav, 12, "fmt ");
        putInt(wav, 16, 16);
        putShort(wav, 20, 1);
        putShort(wav, 22, 1);
        putInt(wav, 24, rate);
        putInt(wav, 28, rate * 2);
        putShort(wav, 32, 2);
        putShort(wav, 34, 16);
        putAscii(wav, 36, "data");
        putInt(wav, 40, dataLen);
        File dir = file.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(wav);
        }
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            b[off + i] = (byte) s.charAt(i);
        }
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
