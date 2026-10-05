package com.venkras.dialerpatch;

import android.content.pm.PackageInfo;
import android.os.Build;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedInit implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TARGET_PACKAGE = "com.google.android.dialer";
    private static final String TARGET_METHOD = "a";
    private static final String TARGET_STRING = "com/android/dialer/sonic/impl/SonicEnabledFn";
    private static final String CACHE_FILE_NAME = "dialer_patch_class.cache";

    private static String MODULE_PATH = null;
    private static boolean dexkitLoaded = false;

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        MODULE_PATH = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        ClassLoader classLoader = lpparam.classLoader;
        Class<?> targetClass = null;

        long currentVersionCode = getAppVersionCode(lpparam);
        File cacheFile = getCacheFile(lpparam);

        // 1. Read from cache (only if versionCode is valid and cache file exists)
        if (currentVersionCode != -1 && cacheFile != null && cacheFile.exists()) {
            String cachedName = readCache(cacheFile, currentVersionCode);
            if (cachedName != null && !cachedName.isEmpty()) {
                targetClass = XposedHelpers.findClassIfExists(cachedName, classLoader);
                if (targetClass != null) {
                    XposedBridge.log("[DialerPatch] Loaded class from cache: " + cachedName);
                } else {
                    XposedBridge.log("[DialerPatch] Cached class not found in ClassLoader, invalidating cache.");
                }
            }
        }

        // 2. Search via DexKit if not found in cache
        if (targetClass == null) {
            XposedBridge.log("[DialerPatch] Class not found in cache. Starting DexKit search...");
            String foundClassName = findClassWithDexKit(lpparam);

            if (foundClassName != null) {
                targetClass = XposedHelpers.findClassIfExists(foundClassName, classLoader);
                if (targetClass != null) {
                    XposedBridge.log("[DialerPatch] DexKit successfully found class: " + foundClassName);
                    if (currentVersionCode != -1 && cacheFile != null) {
                        writeCache(cacheFile, currentVersionCode, foundClassName);
                    }
                }
            }
        }

        // 3. Apply hook
        if (targetClass != null) {
            try {
                java.lang.reflect.Method target = findTargetMethod(targetClass);
                if (target == null) {
                    XposedBridge.log("[DialerPatch] Target method not found in " + targetClass.getName());
                } else {
                    XposedBridge.hookMethod(target, XC_MethodReplacement.returnConstant(true));
                    XposedBridge.log("[DialerPatch] Successfully hooked method " + target.getName() + "() to return true");
                }
            } catch (Throwable t) {
                XposedBridge.log("[DialerPatch] Error hooking method " + TARGET_METHOD + ": " + t.getMessage());
            }
        } else {
            XposedBridge.log("[DialerPatch] Failed to find target class in " + TARGET_PACKAGE);
        }
    }

    private java.lang.reflect.Method findTargetMethod(Class<?> cls) {
        java.lang.reflect.Method fallback = null;
        int count = 0;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == boolean.class) {
                if (m.getName().equals(TARGET_METHOD)) {
                    return m;
                }
                fallback = m;
                count++;
            }
        }
        return count == 1 ? fallback : null;
    }

    private String findClassWithDexKit(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!loadDexKitNativeLibrary()) {
            XposedBridge.log("[DialerPatch] Failed to load DexKit native library");
            return null;
        }

        String sourceDir = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        if (sourceDir == null) {
            XposedBridge.log("[DialerPatch] App sourceDir is null");
            return null;
        }

        try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
            List<ClassData> results = bridge.findClass(
                FindClass.create()
                    .matcher(
                        ClassMatcher.create()
                            .usingStrings(TARGET_STRING)
                    )
            );

            if (!results.isEmpty()) {
                return results.get(0).getName();
            }
        } catch (Throwable t) {
            XposedBridge.log("[DialerPatch] DexKitBridge search error: " + t.getMessage());
        }

        return null;
    }

    private synchronized boolean loadDexKitNativeLibrary() {
        if (dexkitLoaded) {
            return true;
        }
        if (MODULE_PATH == null) {
            XposedBridge.log("[DialerPatch] MODULE_PATH is null");
            return false;
        }
        XposedBridge.log("[DialerPatch] MODULE_PATH=" + MODULE_PATH);
        File libDir = new File(new File(MODULE_PATH).getParentFile(), "lib");
        for (String abi : Build.SUPPORTED_ABIS) {
            String sub = abi.startsWith("arm64") ? "arm64" : (abi.startsWith("armeabi") ? "arm" : abi);
            File so = new File(libDir, sub + "/libdexkit.so");
            if (so.exists()) {
                try {
                    System.load(so.getAbsolutePath());
                    dexkitLoaded = true;
                    XposedBridge.log("[DialerPatch] Loaded " + so.getAbsolutePath());
                    return true;
                } catch (Throwable t) {
                    XposedBridge.log("[DialerPatch] System.load failed: " + t);
                }
            }
        }
        XposedBridge.log("[DialerPatch] libdexkit.so not found in " + libDir);
        return false;
    }

    private long getAppVersionCode(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Object currentActivityThread = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread"
            );
            Object systemContext = XposedHelpers.callMethod(currentActivityThread, "getSystemContext");
            Object packageManager = XposedHelpers.callMethod(systemContext, "getPackageManager");

            PackageInfo pInfo = (PackageInfo) XposedHelpers.callMethod(
                packageManager,
                "getPackageInfo",
                lpparam.packageName,
                0
            );

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (Throwable t) {
            XposedBridge.log("[DialerPatch] Failed to resolve versionCode: " + t.getMessage());
            return -1;
        }
    }

    private File getCacheFile(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam.appInfo == null || lpparam.appInfo.dataDir == null) {
                XposedBridge.log("[DialerPatch] appInfo.dataDir is null, unable to build cache path");
                return null;
            }
            String baseDir = lpparam.appInfo.deviceProtectedDataDir != null ? lpparam.appInfo.deviceProtectedDataDir : lpparam.appInfo.dataDir;
            File cacheDir = new File(baseDir, "cache");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            return new File(cacheDir, CACHE_FILE_NAME);
        } catch (Throwable t) {
            XposedBridge.log("[DialerPatch] Failed to get cache directory: " + t.getMessage());
            return null;
        }
    }

    private String readCache(File cacheFile, long currentVersionCode) {
        try (FileInputStream fis = new FileInputStream(cacheFile)) {
            byte[] data = new byte[(int) cacheFile.length()];
            int bytesRead = fis.read(data);
            if (bytesRead <= 0) return null;

            String content = new String(data, 0, bytesRead, StandardCharsets.UTF_8).trim();
            String[] parts = content.split(":", 2);
            if (parts.length == 2) {
                long cachedVersion = Long.parseLong(parts[0]);
                if (cachedVersion == currentVersionCode) {
                    return parts[1];
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void writeCache(File cacheFile, long currentVersionCode, String className) {
        try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
            String content = currentVersionCode + ":" + className;
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            XposedBridge.log("[DialerPatch] Cache updated for versionCode " + currentVersionCode + " with class " + className);
        } catch (Throwable t) {
            XposedBridge.log("[DialerPatch] Failed to write cache: " + t.getMessage());
        }
    }
}

