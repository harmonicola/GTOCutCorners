package com.gtocutcorners;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * GTO Cut Corners 配置文件 (config/gtocutcorners.json)
 *
 * 模式切换逻辑:
 * - oneTickMode=true  → 使用 GTOCutCorners 原生系统 (native C + JVMTI + Mixin + Watchdog)
 *                       所有配方强制 1 tick, durationMultiplier 和 durationFactor 被忽略
 * - oneTickMode=false → 使用 GTOFast 移植系统 (纯 Java 反射批处理 + Scanner)
 *                       根据 durationFactor 控制速度, 不加载 native 库
 */
public class GTOConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get("config", "gtocutcorners.json");

    // ======================== 模式开关 ========================

    /** true=原生1tick模式(默认), false=倍率模式(纯Java) */
    public boolean oneTickMode = true;

    /** 1tick模式下是否清空配方条件(always pass) */
    public boolean clearConditions = true;

    /** 1tick模式下是否修补原版熔炉配方 */
    public boolean patchVanilla = true;

    /** 1tick模式下是否修补GT配方 */
    public boolean patchGT = true;

    /**
     * 1tick模式下的倍率 (仅 oneTickMode=true 时生效)
     * 0.0 = 1-tick (配合 JVMTI overclock 补丁)
     * >0  = 乘以该值 (例如 0.5 = 半时长 = 2倍速)
     */
    public float durationMultiplier = 0.0f;

    /**
     * 倍率模式下的速度因子 (仅 oneTickMode=false 时生效)
     * 0.0  = 1-tick (所有配方 1 tick)
     * 0.25 = 25% 时长 = 4倍速
     * 0.5  = 50% 时长 = 2倍速
     * 1.0  = 原速 (不变)
     * 2.0  = 2倍时长 = 半速
     */
    public double durationFactor = 0.25;

    // ======================== 单例 ========================

    private static GTOConfig INSTANCE = new GTOConfig();

    public static GTOConfig getInstance() { return INSTANCE; }

    public static GTOConfig load() {
        GTOConfig cfg = new GTOConfig();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.exists(CONFIG_PATH)) {
                String raw = Files.readString(CONFIG_PATH);
                cfg = GSON.fromJson(raw, GTOConfig.class);
                if (cfg == null) cfg = new GTOConfig();
            } else {
                Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
            }
        } catch (Exception e) {
            System.err.println("[GTOConfig] load failed: " + e.getMessage());
        }
        INSTANCE = cfg;
        return cfg;
    }

    public static void reload() { load(); }

    /** 快捷判断: 是否原生1tick模式 */
    public static boolean isOneTickMode() {
        return INSTANCE != null && INSTANCE.oneTickMode;
    }
}
