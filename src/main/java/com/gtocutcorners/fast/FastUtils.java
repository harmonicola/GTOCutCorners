package com.gtocutcorners.fast;

import java.lang.reflect.Field;

/**
 * Minimal reflection utilities — ported from GTOFast's Utils.java.
 * Used by FastPatcher and FastScanner in multiplier mode (oneTickMode=false).
 */
public final class FastUtils {

    private FastUtils() {}

    static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    static int getIntField(Object obj, String name) {
        Field f = findField(obj.getClass(), name);
        if (f != null) try { return f.getInt(obj); } catch (Exception ignored) {}
        return 0;
    }

    static Object getObjectField(Object obj, String name) {
        Field f = findField(obj.getClass(), name);
        if (f != null) try { return f.get(obj); } catch (Exception ignored) {}
        return null;
    }

    static void setIntField(Object obj, String name, int value) {
        Field f = findField(obj.getClass(), name);
        if (f != null) try { f.setInt(obj, value); } catch (Exception ignored) {}
    }

    static void setObjectField(Object obj, String name, Object value) {
        Field f = findField(obj.getClass(), name);
        if (f != null) try { f.set(obj, value); } catch (Exception ignored) {}
    }

    static sun.misc.Unsafe getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) { return null; }
    }
}
