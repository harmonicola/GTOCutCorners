package com.gtocutcorners.registry;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.gtocutcorners.GTOCutCorners;
import com.lowdragmc.lowdraglib.utils.LocalizationUtils;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GTO ignores third-party lang files, but ldlib's LanguageMixin consults
 * {@link LocalizationUtils}' dynamic-lang table before every
 * ClientLanguage#getOrDefault lookup. We therefore load our own lang JSON from
 * the jar at client setup and inject it into that table so gtocore-namespaced
 * machines (and our own items/tooltips) get translated at runtime.
 */
public final class GTOCLang {

    private static final Gson GSON = new Gson();

    private GTOCLang() {
    }

    public static void registerDynamic() {
        try {
            String code = Minecraft.getInstance().options.languageCode;
            String file = (code != null && code.startsWith("zh"))
                ? "/assets/gtocutcorners/lang/zh_cn.json"
                : "/assets/gtocutcorners/lang/en_us.json";
            try (InputStream in = GTOCLang.class.getResourceAsStream(file)) {
                if (in == null) {
                    return;
                }
                Map<String, String> map = GSON.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {
                    }.getType());
                if (map != null && !map.isEmpty()) {
                    LocalizationUtils.appendDynamicLang(map);
                    GTOCutCorners.jlog("[GTOCLang] injected " + map.size() + " dynamic lang entries (" + file + ")");
                }
            }
        } catch (Throwable t) {
            GTOCutCorners.jlog("[GTOCLang] dynamic lang registration failed: " + t);
        }
    }
}
