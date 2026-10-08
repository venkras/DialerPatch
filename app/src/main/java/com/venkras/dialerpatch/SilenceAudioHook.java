package com.venkras.dialerpatch;

import android.content.Context;
import android.net.Uri;

import java.io.FileDescriptor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SilenceAudioHook {

    private static final String TAG = "[SilenceAudioHook] ";
    private static final String FLAG = "mute_this_player";
    
    private static final String[] MARKERS = {
            "callrecordingprompt",
            "starting.wav",
            "ending.wav",
            "call_recording",
            "recording_disclosure",
    };

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookStringDataSource();
            hookUriDataSource();
            hookFdDataSource();
            hookStart();
            XposedBridge.log(TAG + "Hook installed successfully.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Error: " + t.getMessage());
        }
    }
    
    private static void hookStringDataSource() {
        XposedHelpers.findAndHookMethod(
                android.media.MediaPlayer.class,
                "setDataSource",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String path = (String) param.args[0];
                        if (isPrompt(path)) {
                            flag(param);
                            XposedBridge.log(TAG + "Flagged (String): " + path);
                        }
                    }
                });
    }

    private static void hookUriDataSource() {
        XposedHelpers.findAndHookMethod(
                android.media.MediaPlayer.class,
                "setDataSource",
                Context.class,
                Uri.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Uri uri = (Uri) param.args[1];         
                        if (uri != null && isPrompt(uri.toString())) {
                            flag(param);
                            XposedBridge.log(TAG + "Flagged (Uri): " + uri);
                        }
                    }
                });
    }

    private static void hookFdDataSource() {
        try {
            XposedHelpers.findAndHookMethod(
                    android.media.MediaPlayer.class,
                    "setDataSource",
                    FileDescriptor.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String resolved = resolveFd((FileDescriptor) param.args[0]);
                            if (resolved != null && isPrompt(resolved)) {
                                flag(param);
                                XposedBridge.log(TAG + "Flagged (FD→" + resolved + ")");
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "FD hook skipped: " + t.getMessage());
        }
    }

    private static void hookStart() {
        XposedHelpers.findAndHookMethod(
                android.media.MediaPlayer.class,
                "start",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object flag = XposedHelpers.getAdditionalInstanceField(
                                param.thisObject, FLAG);
                        if (Boolean.TRUE.equals(flag)) {
                            param.setResult(null);        
                            XposedHelpers.removeAdditionalInstanceField(
                                    param.thisObject, FLAG);
                            XposedBridge.log(TAG + "Suppressed MediaPlayer.start() (recording prompt)");
                        }
                    }
                });
    }


    private static void flag(XC_MethodHook.MethodHookParam param) {
        XposedHelpers.setAdditionalInstanceField(param.thisObject, FLAG, Boolean.TRUE);
    }

    private static boolean isPrompt(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        for (String m : MARKERS) {
            if (lower.contains(m)) return true;
        }
        return false;
    }

    private static String resolveFd(FileDescriptor fd) {
        if (fd == null) return null;
        try {
            java.lang.reflect.Field f = FileDescriptor.class.getDeclaredField("descriptor");
            f.setAccessible(true);
            int num = f.getInt(fd);
            java.io.File link = new java.io.File("/proc/self/fd/" + num);
            return link.getCanonicalPath();
        } catch (Throwable t) {
            return null;
        }
    }
}
