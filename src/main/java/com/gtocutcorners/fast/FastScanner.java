package com.gtocutcorners.fast;

import com.gtocutcorners.GTOConfig;
import com.gtocutcorners.GTOCutCorners;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runtime Scanner — ported from GTOFast's Scanner.java.
 * Only active when oneTickMode=false (multiplier mode).
 *
 * Every 1s walks all server-levels' loaded chunks, finds MetaMachine block-entities,
 * locates their RecipeLogic, and corrects duration if it was modified externally.
 */
public final class FastScanner {

    private static ScheduledExecutorService executor;
    private static int cycle;

    private FastScanner() {}

    public static synchronized void start(MinecraftServer server) {
        stop();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GTOFast-Scanner"); t.setDaemon(true); return t;
        });
        executor.scheduleWithFixedDelay(FastScanner::scan, 5, 1, TimeUnit.SECONDS);
        GTOCutCorners.jlog("[Fast] Scanner started (period=1s)");
    }

    public static synchronized void stop() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            executor = null;
            GTOCutCorners.jlog("[Fast] Scanner stopped");
        }
    }

    // -- main scan loop --

    private static void scan() {
        cycle++;
        try {
            Object server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            // Get 'levels' field (Map<ResourceKey, ServerLevel>)
            Field levelsField = null;
            for (Class<?> c = server.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try { levelsField = c.getDeclaredField("levels"); levelsField.setAccessible(true); break; }
                catch (NoSuchFieldException ignored) {}
            }
            if (levelsField == null) return;

            Map<?, ?> levelMap = (Map<?, ?>) levelsField.get(server);
            int totalBE = 0, totalMM = 0, totalRL = 0, corrected = 0;

            for (Object level : levelMap.values()) {
                Object cs = level.getClass().getMethod("getChunkSource").invoke(level);
                // ChunkMap.getChunks() is protected — use reflection
                Method getChunks = null;
                for (Class<?> cc = cs.getClass(); cc != null && cc != Object.class; cc = cc.getSuperclass()) {
                    try { getChunks = cc.getDeclaredMethod("getChunks"); getChunks.setAccessible(true); break; }
                    catch (NoSuchMethodException ignored) {}
                }
                if (getChunks == null) continue;

                for (Object chunk : (Iterable<?>) getChunks.invoke(cs)) {
                    Map<?, ?> beMap = (Map<?, ?>) chunk.getClass().getMethod("getBlockEntities").invoke(chunk);
                    if (beMap.isEmpty()) continue;

                    for (Object be : beMap.values()) {
                        totalBE++;
                        try {
                            Object mm = be.getClass().getMethod("getMetaMachine").invoke(be);
                            if (mm == null) continue;
                            totalMM++;

                            Object rl = null;
                            try { rl = mm.getClass().getMethod("getRecipeLogic").invoke(mm); } catch (Exception ignored) {}

                            if (rl != null) {
                                totalRL++;
                                int dur = FastUtils.getIntField(rl, "duration");
                                if (dur > 1) {
                                    int target = computeTarget(rl);
                                    if (target != dur) {
                                        FastUtils.setIntField(rl, "duration", target);
                                        corrected++;
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            if (cycle <= 3) {
                GTOCutCorners.jlog("[Fast] Scanner #" + cycle + ": BEs=" + totalBE
                    + " MMs=" + totalMM + " RLs=" + totalRL + " corrected=" + corrected);
            }
        } catch (Exception e) {
            if (cycle <= 3) GTOCutCorners.jlog("[Fast] Scanner err: " + e.getMessage());
        }
    }

    // -- target duration for Scanner correction (factor mode) --

    private static int computeTarget(Object recipeLogic) {
        double factor = GTOConfig.getInstance().durationFactor;
        if (factor == 1.0) return 1;
        if (factor <= 0.0) return 1;

        try {
            Object lastRecipe = recipeLogic.getClass().getMethod("getLastRecipe").invoke(recipeLogic);
            if (lastRecipe != null) {
                Object idObj = lastRecipe.getClass().getMethod("getId").invoke(lastRecipe);
                if (idObj != null) {
                    Integer orig = FastPatcher.originalDurations.get(idObj.toString());
                    if (orig != null) {
                        return Math.max(1, (int)(orig * factor));
                    }
                }
            }
        } catch (Exception ignored) {}
        return 1;
    }
}
