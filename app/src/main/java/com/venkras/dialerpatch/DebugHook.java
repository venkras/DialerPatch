package com.venkras.dialerpatch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.Future;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.callbacks.XCallback;

public class DebugHook {

    private static final String ANCHOR = "state change to blocking";
    private static long last = 0;
    private static int seq = 0;

    static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        String sourceDir = lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null;
        if (sourceDir == null) {
            return;
        }
        try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
            List<MethodData> found = bridge.findMethod(
                FindMethod.create().matcher(MethodMatcher.create().usingStrings(ANCHOR)));
            if (found.isEmpty()) {
                XposedBridge.log("[DebugHook] anchor not found");
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
                XposedBridge.log("[DebugHook] busy-check not unique: " + count);
                return;
            }
            XposedBridge.hookMethod(target, new XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        dump(param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log("[DebugHook] dump error: " + t);
                    }
                }
            });
            XposedBridge.log("[DebugHook] hooked " + cls.getName() + "." + target.getName());
        } catch (Throwable t) {
            XposedBridge.log("[DebugHook] error: " + t);
        }
    }

    private static void dump(Object root) {
        long now = System.currentTimeMillis();
        if (now - last < 400) {
            return;
        }
        last = now;
        int n = ++seq;
        StringBuilder sb = new StringBuilder();
        dumpObj(sb, "player", root, 0, new IdentityHashMap<Object, Boolean>());
        for (String line : sb.toString().split("\n")) {
            XposedBridge.log("[DebugHook #" + n + "] " + line);
        }
    }

    private static boolean isFramework(String cn) {
        return cn.startsWith("java.") || cn.startsWith("android.") || cn.startsWith("javax.")
            || cn.startsWith("kotlin.") || cn.startsWith("dalvik.");
    }

    private static void dumpObj(StringBuilder sb, String name, Object o, int depth,
                                IdentityHashMap<Object, Boolean> seen) {
        StringBuilder ind = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            ind.append("  ");
        }
        if (sb.length() > 20000) {
            return;
        }
        if (o == null) {
            sb.append(ind).append(name).append(" = null\n");
            return;
        }
        String cn = o.getClass().getName();
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean || o instanceof Enum) {
            sb.append(ind).append(name).append(" = ").append(o).append(" (").append(cn).append(")\n");
            return;
        }
        if (seen.put(o, Boolean.TRUE) != null) {
            sb.append(ind).append(name).append(" -> seen ").append(cn).append("\n");
            return;
        }
        sb.append(ind).append(name).append(" : ").append(cn);
        if (o instanceof Collection) {
            sb.append(" size=").append(((Collection<?>) o).size());
        }
        if (o instanceof Future) {
            Future<?> f = (Future<?>) o;
            sb.append(" done=").append(f.isDone()).append(" cancelled=").append(f.isCancelled());
        }
        sb.append("\n");
        if (depth >= 3) {
            return;
        }
        if (o instanceof Collection) {
            int i = 0;
            for (Object e : (Collection<?>) o) {
                if (i++ >= 3) {
                    break;
                }
                dumpObj(sb, "[" + (i - 1) + "]", e, depth + 1, seen);
            }
            return;
        }
        if (isFramework(cn)) {
            return;
        }
        Class<?> c = o.getClass();
        while (c != null && c != Object.class && !isFramework(c.getName())) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    dumpObj(sb, f.getName(), f.get(o), depth + 1, seen);
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
    }
}
