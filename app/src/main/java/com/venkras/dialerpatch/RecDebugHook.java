package com.venkras.dialerpatch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class RecDebugHook {

    private static final List<Object> factories = Collections.synchronizedList(new ArrayList<Object>());
    private static final Set<Class<?>> facClasses = new HashSet<>();
    private static final AtomicInteger counter = new AtomicInteger();

    private static void log(String s) {
        XposedBridge.log("[RecDebug] " + s);
    }

    private static void remember(Object o) {
        if (o != null && facClasses.contains(o.getClass())) {
            synchronized (factories) {
                for (Object f : factories) {
                    if (f == o) {
                        return;
                    }
                }
                factories.add(o);
            }
            log("factory instance seen: " + o.getClass().getName());
        }
    }

    private static void dumpFactories() {
        synchronized (factories) {
            if (factories.isEmpty()) {
                log("no factory instance seen");
                return;
            }
            for (Object fac : factories) {
                for (Field f : fac.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(fac);
                        if (v instanceof Map) {
                            Map<?, ?> m = (Map<?, ?>) v;
                            log("factory map " + f.getName() + " size=" + m.size());
                            int i = 0;
                            for (Map.Entry<?, ?> e : m.entrySet()) {
                                if (i++ >= 5) {
                                    break;
                                }
                                log("  " + e.getKey().getClass().getName() + " -> " + e.getValue());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static final XC_MethodHook CALL_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam a) {
            remember(a.thisObject);
            if (counter.incrementAndGet() <= 300) {
                log("call " + a.method.getDeclaringClass().getName() + "." + a.method.getName());
            }
        }
    };

    private static final XC_MethodHook CTOR_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam a) {
            remember(a.thisObject);
        }
    };

    private static int hookClass(Class<?> c) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isAbstract(m.getModifiers()) || Modifier.isNative(m.getModifiers())) {
                continue;
            }
            XposedBridge.hookMethod(m, CALL_HOOK);
            n++;
        }
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            XposedBridge.hookMethod(k, CTOR_HOOK);
        }
        return n;
    }

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.appInfo == null || lpparam.appInfo.sourceDir == null) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        try (DexKitBridge bridge = DexKitBridge.create(lpparam.appInfo.sourceDir)) {
            List<ClassData> fac = bridge.findClass(FindClass.create().matcher(
                ClassMatcher.create().usingStrings("com/android/dialer/audio/recorder/impl/RecorderFactoryImpl")));
            for (ClassData cd : fac) {
                Class<?> c = cd.getInstance(cl);
                facClasses.add(c);
                log("hooked factory " + c.getName() + " methods=" + hookClass(c));
            }
            List<ClassData> rec = bridge.findClass(FindClass.create().matcher(
                ClassMatcher.create().usingStrings("com/android/dialer/audio/recorder/impl/RecorderImpl")));
            for (ClassData cd : rec) {
                Class<?> c = cd.getInstance(cl);
                log("hooked recorder " + c.getName() + " methods=" + hookClass(c));
            }
            List<MethodData> pb = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings("playback detected")));
            for (MethodData md : pb) {
                Method m = md.getMethodInstance(cl);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam a) {
                        log("playback request");
                        dumpFactories();
                    }
                });
            }
            log("playback hooks: " + pb.size());
        } catch (Throwable t) {
            log("error: " + t);
        }
    }
}
