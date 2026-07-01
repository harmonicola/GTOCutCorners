package com.gtocutcorners;

import com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition;
import com.gtocutcorners.recipe.GTOCRecipes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

@Mod(GTOCutCorners.MODID)
public class GTOCutCorners {
    public static final String MODID = "gtocutcorners";

    // ======================== 配置 ========================
    private static GTOConfig config = new GTOConfig();

    // ======================== 日志系统 ========================
    private static FileWriter fw;
    private static FileWriter vfw;
    private static boolean vLogOk = false;

    static {
        try {
            fw = new FileWriter("gtocutcorners_java.log", true);
        } catch (Exception e) {
            System.err.println("[GTO] Cannot init log: " + e);
        }
        try {
            vfw = new FileWriter("gtocutcorners_vanilla_patch.log", false);
            vLogOk = true;
        } catch (Exception e) {
            System.err.println("[GTO] Cannot init vanilla log: " + e);
        }
    }

    public GTOCutCorners() {
        jlog("[GTO] ===== GTOCutCorners CONSTRUCTOR =====");
        jlog("[GTO] thread: " + Thread.currentThread().getName());
        config = GTOConfig.load();
        jlog("Config: oneTick=" + config.oneTickMode + " clearCond=" + config.clearConditions
            + " vanilla=" + config.patchVanilla + " gt=" + config.patchGT);
        MinecraftForge.EVENT_BUS.register(this);
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        jlog("[GTO] registering recipe lambda on mod event bus...");
        bus.addListener((FMLCommonSetupEvent e) -> {
            jlog("[GTO] ===== FMLCommonSetupEvent FIRED =====");
            jlog("[GTO] event: " + e);
            try {
                GTOCRecipes.register();
                jlog("[GTO] GTOCRecipes.register() OK");
            } catch (Exception ex) {
                jlog("[GTO] GTOCRecipes.register() THREW: " + ex);
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                jlog(sw.toString());
            }
        });
        jlog("[GTO] CONSTRUCTOR done");
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        new Thread(() -> loadAndPatch(level), "GTOCutCorners-Patcher").start();
    }

    // ---- 日志输出 ----
    public static synchronized void jlog(String msg) {
        String line = "[GTO] " + msg + "\n";
        System.out.print(line);
        if (fw == null) {
            try {
                fw = new FileWriter("gtocutcorners_java.log", true);
            } catch (Exception ignored) {
            }
        }
        if (fw != null) {
            try {
                fw.write(line);
                fw.flush();
            } catch (Exception ignored) {
            }
        }
    }

    /** 原版配方专项日志 */
    private static synchronized void vlog(String tag, String msg) {
        if (!vLogOk) {
            return;
        }
        String line = String.format("[%s] %s\n", tag, msg);
        try {
            vfw.write(line);
            vfw.flush();
        } catch (Exception ignored) {
        }
    }

    private static void vlog(String tag, String fmt, Object... args) {
        vlog(tag, String.format(fmt, args));
    }

    // ======================== JNI 声明 ========================
    private static native Collection<GTRecipeDefinition> getRecipeCollection();
    private static native int nativeSetIntField(Object obj, String fieldName, int newValue);
    private static native int nativeMassPatch(boolean clearConditions);
    private static native void nativeSetObjectField(Object obj, String fieldName, Object newValue);
    private static native void nativeStartWatchdog();
    private static native void nativeStopWatchdog();
    public static native void nativeRegisterRecipeLogic(Object logic);
    public static native void nativeUnregisterRecipeLogic(Object logic);
    private static native int nativeWatchdogTick();
    private static native void nativeInitJVMTI();
    public static native void nativeDumpRecipeLogics();


    // ======================== 字段自动发现 ========================
    private static Object unsafeObj;
    private static long cookingTimeOffset = -1;
    private static boolean unsafeOk = false;
    private static boolean unsafeTried = false;

    /** 反射找到的 cookingTime 字段 */
    private static Field cookingTimeField;
    /** 是否需要在运行时通过值匹配自动发现字段 */
    private static boolean needRuntimeDiscovery = true;

    /**
     * 第一阶段：尝试候选名。速度快，覆盖大多数情况。
     * 失败不致命——标记 needRuntimeDiscovery，后续用值匹配兜底。
     */
    private static void tryNameCandidates() {
        String[] candidates = {"cookingTime", "f_43750_", "cookingTime_", "field_43750"};
        Class<?> cls = AbstractCookingRecipe.class;
        while (cls != null && cls != Object.class) {
            for (String name : candidates) {
                try {
                    Field f = cls.getDeclaredField(name);
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        cookingTimeField = f;
                        needRuntimeDiscovery = false;
                        jlog("cookingTime field found via name: " + name);
                        vlog("FIELD", "name match: '%s' in %s", name, cls.getSimpleName());
                        return;
                    }
                } catch (NoSuchFieldException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        jlog("cookingTime field: all name candidates failed, will use runtime value-match");
        vlog("FIELD", "all name candidates failed, will use runtime discovery");
    }

    /**
     * 第二阶段：运行时值匹配。
     * 利用 AbstractCookingRecipe.getCookingTime() 公共方法确定字段身份。
     * 遍历类层级中所有 int 字段，找到值等于 getCookingTime() 的那一个。
     * 需要至少一条真实配方作为样本。
     */
    private static void discoverFieldByValue(AbstractCookingRecipe sample) {
        if (cookingTimeField != null || !needRuntimeDiscovery) {
            return;
        }

        int expected = sample.getCookingTime();
        jlog("discoverFieldByValue: sample=" + sample.getId() + " getCookingTime()=" + expected);
        vlog("FIELD", "value-match: sample=%s, getCookingTime()=%d", sample.getId(), expected);

        // 收集类层级中所有 int 字段
        List<Field> candidates = new ArrayList<>();
        Class<?> cls = sample.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    candidates.add(f);
                }
            }
            cls = cls.getSuperclass();
        }

        jlog("discoverFieldByValue: " + candidates.size() + " int fields in hierarchy");
        vlog("FIELD", "%d int fields in class hierarchy", candidates.size());

        if (candidates.isEmpty()) {
            jlog("FATAL: no int fields found at all");
            vlog("FIELD", "FATAL: zero int fields in hierarchy");
            return;
        }

        if (candidates.size() == 1) {
            cookingTimeField = candidates.get(0);
            needRuntimeDiscovery = false;
            jlog("discoverFieldByValue: only 1 int field -> must be cookingTime: " + cookingTimeField.getName());
            vlog("FIELD", "sole int field: '%s'", cookingTimeField.getName());
            return;
        }

        // 多个候选：值匹配
        for (Field f : candidates) {
            try {
                int val = f.getInt(sample);
                if (val == expected) {
                    cookingTimeField = f;
                    needRuntimeDiscovery = false;
                    jlog("discoverFieldByValue: MATCH '" + f.getName() + "' val=" + val + " == expected=" + expected);
                    vlog("FIELD", "value-match: '%s' val=%d == expected=%d", f.getName(), val, expected);
                    return;
                }
                vlog("FIELD", "  skip '%s' val=%d != expected=%d", f.getName(), val, expected);
            } catch (Exception e) {
                vlog("FIELD", "  skip '%s' read error: %s", f.getName(), e.getMessage());
            }
        }

        // 全不匹配？可能采样配方有问题，用第二条再试
        jlog("discoverFieldByValue: no exact match on sample, will retry with next recipe");
        vlog("FIELD", "no match on first sample, will retry");
    }

    /**
     * Unsafe 初始化。先走候选名，全失败则标记运行时发现。
     */
    private static void ensureUnsafe() {
        if (unsafeTried) {
            return;
        }
        unsafeTried = true;

        tryNameCandidates();

        // 如果候选名命中且字段可用，尝试 Unsafe
        if (cookingTimeField != null) {
            tryInitUnsafe();
        }
    }

    /** 在字段已定位后尝试初始化 Unsafe */
    private static void tryInitUnsafe() {
        if (unsafeOk || cookingTimeField == null) {
            return;
        }
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field uf = uc.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            unsafeObj = uf.get(null);
            Method ofo = uc.getMethod("objectFieldOffset", Field.class);
            cookingTimeOffset = (long) ofo.invoke(unsafeObj, cookingTimeField);
            unsafeOk = true;
            jlog("Unsafe OK, field=" + cookingTimeField.getName() + " offset=" + cookingTimeOffset);
            vlog("UNSAFE", "OK, field=%s offset=%d", cookingTimeField.getName(), cookingTimeOffset);
        } catch (Throwable t) {
            jlog("Unsafe unavailable: " + t);
            vlog("UNSAFE", "FAIL: %s", t.toString());
        }
    }

    /** 返回 true 表示实际写入了值 */
    private static boolean setCookingTime(Object recipe, int value) {
        // 运行时字段发现
        if (needRuntimeDiscovery && recipe instanceof AbstractCookingRecipe acr) {
            discoverFieldByValue(acr);
            if (cookingTimeField != null) {
                tryInitUnsafe();
            }
        }

        if (cookingTimeField == null) {
            return false;
        }
        try {
            if (unsafeOk && unsafeObj != null) {
                Method putInt = unsafeObj.getClass().getMethod("putInt", Object.class, long.class, int.class);
                putInt.invoke(unsafeObj, recipe, cookingTimeOffset, value);
            } else {
                cookingTimeField.setInt(recipe, value);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== GT 配方 patch ========================
    private static final Set<String> GENS = Set.of(
        "combustion","gas_turbine","steam_boiler","large_boiler",
        "rock_breaker","heat_exchanger","steam_turbine","plasma_generator"
    );

    private static int patchGT(Collection<GTRecipeDefinition> recipes) {
        jlog("patchGT: size=" + recipes.size());
        long start = System.currentTimeMillis(), lastLog = start;
        int count = 0, skipped = 0, errors = 0, total = 0;

        for (GTRecipeDefinition r : recipes) {
            total++;
            try {
                if (r.recipeType != null) {
                    String tn = r.recipeType.toString().toLowerCase();
                    boolean isGen = false;
                    for (String kw : GENS) {
                        if (tn.contains(kw)) {
                            isGen = true;
                            break;
                        }
                    }
                    if (isGen) {
                        skipped++;
                        continue;
                    }
                }
                int old = nativeSetIntField(r, "duration", 1);
                count++;
                if (count <= 10) {
                    jlog("  #" + count + " " + r.id + " " + old + "->1");
                }
            } catch (Exception e) {
                errors++;
                if (errors <= 10) {
                    jlog("  ERR " + r.id + ": " + e.getMessage());
                }
            }
            long now = System.currentTimeMillis();
            if (now - lastLog > 5000) {
                jlog("  progress " + total + "/" + recipes.size() + " p=" + count + " s=" + skipped);
                lastLog = now;
            }
        }
        jlog("patchGT done: " + count + " patched " + skipped + " skipped " + errors + " errors "
            + (System.currentTimeMillis() - start) + "ms");
        return count;
    }

    /** 遍历所有GT配方，清空conditions数组——无条件执行 */
    public static int bypassConditions(Collection<GTRecipeDefinition> recipes) {
        Object emptyArr = Array.newInstance(
            com.gregtechceu.gtceu.api.recipe.RecipeCondition.class, 0);
        int count = 0;
        for (GTRecipeDefinition r : recipes) {
            try {
                if (r.conditions != null && r.conditions.length > 0) {
                    nativeSetObjectField(r, "conditions", emptyArr);
                    count++;
                }
            } catch (Exception ignored) {
            }
        }
        jlog("bypassConditions: " + count + " recipes cleared");
        return count;
    }

    // ======================== 原版配方 patch ========================
    private static int vanillaCached = -1;

    private static int patchVanilla(ServerLevel level) {
        if (vanillaCached > 0) {
            jlog("patchVanilla: already patched " + vanillaCached + " (cache)");
            vlog("SKIP", "already patched %d, cache hit", vanillaCached);
            return vanillaCached;
        }
        jlog("patchVanilla START");
        vlog("START", "========================================");
        vlog("START", "level=%s", level.dimension().location());

        // 懒加载 Unsafe（不在 static 块，不炸类加载）
        ensureUnsafe();

        int count = 0, errs = 0, total = 0;
        long t0 = System.currentTimeMillis();

        try {
            // Step 1: RecipeManager
            RecipeManager rm = level.getRecipeManager();
            jlog("patchVanilla: RecipeManager=" + rm.getClass().getName());
            vlog("STEP1", "RecipeManager: %s", rm.getClass().getName());

            // Step 2: 获取配方
            Collection<?> all = rm.getRecipes();
            total = all.size();
            jlog("patchVanilla: total recipes=" + total);
            vlog("STEP2", "getRecipes() = %d entries", total);
            if (total == 0) {
                vlog("END", "no recipes, abort");
                return 0;
            }

            // Step 3: 遍历 patch
            vlog("STEP3", "unsafeOk=%b, offset=%d", unsafeOk, cookingTimeOffset);

            int cookingFound = 0;
            Map<String, Integer> byType = new LinkedHashMap<>();
            Set<String> sampledIds = new LinkedHashSet<>();

            for (Object r : all) {
                if (r instanceof AbstractCookingRecipe acr) {
                    cookingFound++;
                    String tn = r.getClass().getSimpleName();
                    byType.merge(tn, 1, Integer::sum);
                    if (sampledIds.size() < 20) sampledIds.add(acr.getId().toString());

                    try {
                        if (setCookingTime(acr, 1)) {
                            count++;
                        } else {
                            errs++;
                            if (errs <= 5) {
                                jlog("patchVanilla ERR[" + errs + "]: " + acr.getId() + " field write returned false");
                                vlog("ERR", "write fail %s: setCookingTime=false", acr.getId());
                            }
                        }
                    } catch (Exception e) {
                        errs++;
                        if (errs <= 5) {
                            jlog("patchVanilla ERR[" + errs + "]: " + acr.getId() + " " + e.getMessage());
                            vlog("ERR", "write fail %s: %s", acr.getId(), e.getMessage());
                        }
                    }
                }
            }

            // Step 4: 汇总
            long elapsed = System.currentTimeMillis() - t0;
            jlog("patchVanilla done: total=" + total + " cooking=" + cookingFound
                + " patched=" + count + " errors=" + errs + " time=" + elapsed + "ms");
            vlog("STATS", "total=%d cooking=%d patched=%d errors=%d time=%dms",
                total, cookingFound, count, errs, elapsed);
            vlog("STATS", "byType: %s", byType);
            vlog("SAMPLE", "first 20: %s", sampledIds);
            vlog("END", "========================================");

            vanillaCached = count;
        } catch (Exception e) {
            jlog("patchVanilla FATAL: " + e);
            vlog("FATAL", "%s", e.toString());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            vlog("FATAL", "stack: %s", sw.toString());
        }
        return count;
    }

    // ======================== Java-side RecipeLogic scanner ========================
    private static java.lang.reflect.Field rlDurationField;
    private static int g_scanCycle = 0;
    private static int rlPatchedTotal = 0;

    private static int scanRecipeLogics() {
        g_scanCycle++;
        jlog("SCAN cycle " + g_scanCycle + " START");
        try {
            Object server = Class.forName("net.minecraftforge.server.ServerLifecycleHooks")
                .getMethod("getCurrentServer").invoke(null);
            if (server == null) { jlog("SCAN: server is null"); return 0; }
            jlog("SCAN: server OK");

            int totalBE = 0, totalMM = 0, totalRL = 0;
            /* Get all loaded levels via 'levels' field */
            java.lang.reflect.Field levelsField = null;
            Class<?> cls = server.getClass();
            while (cls != null && levelsField == null) {
                try { levelsField = cls.getDeclaredField("levels"); levelsField.setAccessible(true); }
                catch (Exception e) { cls = cls.getSuperclass(); }
            }
            if (levelsField == null) {
                jlog("SCAN: FATAL - cannot find 'levels' field on server");
                return 0;
            }
            java.util.Map<?,?> levelMap = (java.util.Map<?,?>) levelsField.get(server);
            jlog("SCAN: found " + levelMap.size() + " levels");
            for (Object level : levelMap.values()) {
                String dimName = level.toString();
                Object cs = level.getClass().getMethod("getChunkSource").invoke(level);
                Object chunks = cs.getClass().getMethod("getChunks").invoke(cs);
                int chunkCount = 0;
                for (Object chunk : (Iterable<?>) chunks) { chunkCount++; }
                if (chunkCount > 0) jlog("SCAN: dim " + dimName + " chunks=" + chunkCount);

                for (Object chunk : (Iterable<?>) chunks) {
                    Object beMap = chunk.getClass().getMethod("getBlockEntities").invoke(chunk);
                    int beCount = ((java.util.Map<?,?>)beMap).size();
                    if (beCount == 0) continue;

                    for (Object be : ((java.util.Map<?,?>)beMap).values()) {
                        totalBE++;
                        String beCls = be.getClass().getSimpleName();
                        try {
                            Object mm = be.getClass().getMethod("getMetaMachine").invoke(be);
                            if (mm == null) continue;
                            totalMM++;
                            String mmCls = mm.getClass().getSimpleName();

                            Object rl = null;
                            try { rl = mm.getClass().getMethod("getRecipeLogic").invoke(mm); }
                            catch (Exception e) {}

                            if (rl != null) {
                                totalRL++;
                                String rlCls = rl.getClass().getSimpleName();
                                int dur = -1, prog = -1;
                                try {
                                    Class<?> clz = rl.getClass();
                                    while (clz != null && clz != Object.class) {
                                        try {
                                            java.lang.reflect.Field f = clz.getDeclaredField("duration");
                                            f.setAccessible(true);
                                            dur = f.getInt(rl);
                                            break;
                                        } catch (Exception e) { clz = clz.getSuperclass(); }
                                    }
                                    Class<?> clz2 = rl.getClass();
                                    while (clz2 != null && clz2 != Object.class) {
                                        try {
                                            java.lang.reflect.Field f = clz2.getDeclaredField("progress");
                                            f.setAccessible(true);
                                            prog = f.getInt(rl);
                                            break;
                                        } catch (Exception e) { clz2 = clz2.getSuperclass(); }
                                    }
                                } catch (Exception e) {}

                                                                if (mmCls.toLowerCase().contains("heater")) {
                                    jlog("DIAG HEATER: MM=" + mmCls + " RL=" + rlCls
                                        + " dur=" + dur + " prog=" + prog);
                                    try {
                                        Object rt = mm.getClass().getMethod("getRecipeType").invoke(mm);
                                        jlog("DIAG HEATER: RecipeType=" + rt);
                                        if (rt != null) {
                                            Object recipes = rt.getClass().getMethod("getRecipes").invoke(rt);
                                            if (recipes instanceof java.util.Map) {
                                                jlog("DIAG HEATER: recipes count=" + ((java.util.Map)recipes).size());
                                            }
                                        }
                                        Object lr = rl.getClass().getMethod("getLastRecipe").invoke(rl);
                                        jlog("DIAG HEATER: lastRecipe=" + lr);
                                    } catch (Exception ex) {
                                        jlog("DIAG HEATER err: " + ex.getMessage());
                                    }
                                }
                                jlog("TRACE BE=" + beCls + " MM=" + mmCls + " RL=" + rlCls
                                    + " dur=" + dur + " prog=" + prog
                                    + (dur > 1 ? " !!!" : ""));

                                if (dur > 1 && rlDurationField != null) {
                                    rlDurationField.setInt(rl, 1);
                                    jlog("TRACE patched duration -> 1");
                                    totalRL++;
                                }
                            } else {
                                if (g_scanCycle <= 3)
                                    jlog("TRACE BE=" + beCls + " MM=" + mmCls + " NO_RECIPE_LOGIC");
                            }
                        } catch (Exception e) {
                            if (g_scanCycle <= 3)
                                jlog("TRACE BE=" + beCls + " ERR: " + e.getMessage());
                        }
                    }
                }
            }
            jlog("SCAN cycle " + g_scanCycle + " DONE: BEs=" + totalBE + " MMs=" + totalMM + " RLs=" + totalRL);
            return totalRL;
        } catch (Exception e) {
            jlog("SCAN cycle " + g_scanCycle + " FATAL: " + e.getMessage());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            jlog(sw.toString());
            return 0;
        }
    }

    // ======================== 主流程 ========================
    private static void loadAndPatch(ServerLevel level) {
        jlog("=== START ===");
        vlog("SESSION", "=== new session, level=%s ===", level != null ? level.dimension().location() : "null");
        try {
            // DLL 加载
            String dll = "libgtocutcorners_native.dll";
            InputStream in = GTOCutCorners.class.getClassLoader().getResourceAsStream("native/" + dll);
            if (in == null) { jlog("DLL not found"); vlog("ERR", "DLL not found"); return; }
            Path tmp = Files.createTempDirectory("g"); Path dp = tmp.resolve(dll);
            Files.copy(in, dp, StandardCopyOption.REPLACE_EXISTING); in.close();
            System.load(dp.toAbsolutePath().toString());
            jlog("DLL loaded");
            // Quick verify MAX_PROGRESS was actually changed
            // Verify native MAX_PROGRESS patch actually took effect
            try {
                java.lang.reflect.Field mpf = Class.forName("com.gtocore.common.machine.trait.INFFluidDrillLogic").getDeclaredField("MAX_PROGRESS");
                mpf.setAccessible(true);
                java.lang.reflect.Field mods = java.lang.reflect.Field.class.getDeclaredField("modifiers");
                mods.setAccessible(true);
                mods.setInt(mpf, mpf.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                jlog("VERIFY MAX_PROGRESS=" + mpf.getInt(null) + " (expect 1)");
            } catch (Exception ex) { jlog("VERIFY err: " + ex.getMessage()); }

            // JVMTI bytecode injection for RecipeLogic.setupRecipe
            try {
                nativeInitJVMTI();
                jlog("JVMTI init done");
            } catch (Throwable t) {
                jlog("JVMTI init failed: " + t.getMessage());
            }

            // Start native watchdog thread (iterates RecipeLogic list, patches + logs)
            try {
                nativeStartWatchdog();
                jlog("Watchdog started");
            } catch (Throwable t) {
                jlog("Watchdog start failed: " + t.getMessage());
            }

            // Java timer for native watchdog tick (avoids cross-thread JNI attach issues)
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "GTOCutCorners-Scanner");
                t.setDaemon(true);
                return t;
            }).scheduleWithFixedDelay(() -> {
                try {
                    int r = scanRecipeLogics();
                    if (r > 0) jlog("Scanner patched " + r);
                } catch (Throwable t) {
                    jlog("Scanner error: " + t.getMessage());
                }
            }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);

            // Diagnostic: dump RecipeLogic state every 3 seconds
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "GTOCutCorners-Diag");
                t.setDaemon(true);
                return t;
            }).scheduleWithFixedDelay(() -> {
                try {
                    nativeDumpRecipeLogics();
                } catch (Throwable tt) {
                    jlog("Diag dump failed: " + tt.getMessage());
                }
            }, 5, 3, java.util.concurrent.TimeUnit.SECONDS);
            jlog("RecipeLogic scanner started");

            // 原版配方 patch
            int va = 0;
            if (config.patchVanilla && config.oneTickMode) {
                va = patchVanilla(level);
            } else {
                jlog("patchVanilla SKIP (config: vanilla=" + config.patchVanilla + " oneTick=" + config.oneTickMode + ")");
            }

            // GT 配方 patch
            int massResult = 0;
            if (config.patchGT) {
                                Collection<GTRecipeDefinition> recipes = getRecipeCollection();
                jlog("Got " + recipes.size() + " GT recipes");
                vlog("GT", "getRecipeCollection returned %d GT recipes", recipes.size());

                if (config.oneTickMode) {
                    massResult = nativeMassPatch(config.clearConditions);
                } else if (config.clearConditions) {
                    massResult = bypassConditions(recipes);
                }
                jlog("nativeMassPatch: " + massResult + " conditions replaced");
                vlog("GT", "nativeMassPatch result=%d", massResult);
            } else {
                jlog("GT patch SKIP (config: patchGT=false)");
            }

            jlog("=== DONE MassPatch=" + massResult + " Vanilla=" + va + " ===");
            vlog("SESSION", "=== done: GT=%d  Vanilla=%d ===", massResult, va);
        } catch (Throwable t) {
            jlog("FATAL: " + t);
            vlog("FATAL", "%s", t.toString());
            for (StackTraceElement s : t.getStackTrace()) { jlog("  " + s); vlog("FATAL", "  at %s", s); }
        }
    }
}
