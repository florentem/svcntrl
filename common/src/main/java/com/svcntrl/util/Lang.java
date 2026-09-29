package com.svcntrl.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Lang {
    private static final Logger LOGGER = LoggerFactory.getLogger("svcntrl");
    private static final Map<String, String> TRANSLATIONS = new HashMap<>();

    static {
        loadLanguage("en_us");
        loadLanguage("ru_ru");
    }

    private static void loadLanguage(String langCode) {
        try {
            InputStream is = Lang.class.getResourceAsStream("/assets/svcntrl/lang/" + langCode + ".json");
            if (is != null) {
                JsonObject json = new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), JsonObject.class);
                for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
                    TRANSLATIONS.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception e) {
            LOGGER.error("[svcntrl] Failed to load language " + langCode, e);
        }
    }

    public static String get(String key, Object... args) {
        String pattern = TRANSLATIONS.getOrDefault(key, key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(pattern, args);
        } catch (Exception e) {
            return pattern;
        }
    }
}
