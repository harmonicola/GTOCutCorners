package com.gtocutcorners.registry;

import com.gtocutcorners.GTOCutCorners;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defensive restoration of GTCEu registrate's one-shot client maps.
 *
 * <p>{@code com.gto.registrate.ClientEvent} nulls its handler maps after the
 * color/render events have fired. Any machine registered after that point (for
 * example via a late CommonSetup fallback) hits a NullPointerException inside
 * {@code BlockBuilder.color()}. This class re-creates the maps just before our
 * registration so the failure mode becomes a missing cosmetic color instead of a
 * crash. It only touches GTCEu registrate internals, not GTO protected code.</p>
 */
public final class ClientEventGuard {

    private static boolean warned = false;

    private ClientEventGuard() {
    }

    public static void ensureColorMaps() {
        try {
            Class<?> clazz = Class.forName("com.gto.registrate.ClientEvent");
            ensureField(clazz, "BLOCK_COLOR_HANDLERS");
            ensureField(clazz, "ITEM_COLOR_HANDLERS");
        } catch (Throwable t) {
            if (!warned) {
                GTOCutCorners.jlog("[ClientEventGuard] cannot ensure client maps: " + t);
                warned = true;
            }
        }
    }

    private static void ensureField(Class<?> clazz, String name) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        if (field.get(null) == null) {
            field.set(null, new ConcurrentHashMap<>());
            GTOCutCorners.jlog("[ClientEventGuard] restored null map: " + name);
        }
    }
}
