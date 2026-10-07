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

public class NoLockHook {

    private static final String ANCHOR = "state change to blocking";

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        String sourceDir = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        if (sourceDir == null) {
            return;
        }
        try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
            List<MethodData> found = bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(ANCHOR)
                )
            );
            if (found.isEmpty()) {
                XposedBridge.log("[NoLockHook] anchor method not found");
                return;
            }
            Class<?> cls = found.get(0).getMethodInstance(lpparam.classLoader).getDeclaringClass();
            Method target = null;
            int count = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == boolean.class) {
                    target = m;
                    count++;
                }
            }
            if (count != 1) {
                XposedBridge.log("[NoLockHook] expected one busy-check method in " + cls.getName() + ", found " + count);
                return;
            }
            XposedBridge.hookMethod(target, XC_MethodReplacement.returnConstant(false));
            XposedBridge.log("[NoLockHook] hooked " + cls.getName() + "." + target.getName() + " -> never busy");
        } catch (Throwable t) {
            XposedBridge.log("[NoLockHook] error: " + t);
        }
    }
}
