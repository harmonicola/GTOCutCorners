package com.gtocutcorners;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GTOConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get("config", "gtocutcorners.json");

    public boolean oneTickMode = true;
    public boolean clearConditions = true;
    public boolean patchVanilla = true;
    public boolean patchGT = true;

    public static GTOConfig load() {
        GTOConfig cfg = new GTOConfig();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.exists(CONFIG_PATH)) {
                String raw = Files.readString(CONFIG_PATH);
                cfg = GSON.fromJson(raw, GTOConfig.class);
                if (cfg == null) {
                    cfg = new GTOConfig();
                }
            } else {
                Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
            }
        } catch (Exception e) {
            System.err.println("[GTOConfig] load failed: " + e.getMessage());
        }
        return cfg;
    }
}
