package com.svcntrl.util;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Lang {
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
            e.printStackTrace();
        }
    }

    public static MutableText translatable(String key, Object... args) {
        String pattern = TRANSLATIONS.getOrDefault(key, key);
        
        if (args.length == 0) {
            return Text.literal(pattern);
        }
        
        Object[] stringArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Text) {
                stringArgs[i] = ((Text) args[i]).getString(); 
            } else {
                stringArgs[i] = args[i];
            }
        }
        
        try {
            return Text.literal(String.format(pattern, stringArgs));
        } catch (Exception e) {
            return Text.literal(pattern);
        }
    }
}
