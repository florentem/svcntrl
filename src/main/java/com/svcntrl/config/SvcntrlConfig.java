package com.svcntrl.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.svcntrl.SvcntrlMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SvcntrlConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("svcntrl.json");

    public String outlineParticle = "minecraft:flame";
    public int outlineFrequencyTicks = 15;
    public boolean allowPublicExport = false;
    public int maxRegionVolume = 100000000;
    public String[] raycastParticlePool = new String[]{
            "minecraft:end_rod", "minecraft:happy_villager", "minecraft:flame", "minecraft:soul_fire_flame",
            "minecraft:glow", "minecraft:wax_on", "minecraft:wax_off", "minecraft:nautilus",
            "minecraft:electric_spark", "minecraft:scrape", "minecraft:totem_of_undying", "minecraft:witch",
            "minecraft:cherry_leaves", "minecraft:soul", "minecraft:crimson_spore"
    };
    public boolean autoSaveOnBranchSwitch = true;
    public boolean autoSaveOnRestore = true;

    private static SvcntrlConfig instance = new SvcntrlConfig();

    public static SvcntrlConfig getInstance() {
        return instance;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                SvcntrlConfig loaded = GSON.fromJson(reader, SvcntrlConfig.class);
                if (loaded != null) {
                    // Validate and apply defaults for null fields
                    if (loaded.outlineParticle == null) loaded.outlineParticle = "minecraft:flame";
                    if (loaded.outlineFrequencyTicks <= 0) loaded.outlineFrequencyTicks = 15;
                    if (loaded.maxRegionVolume <= 0) loaded.maxRegionVolume = 100000000;
                    if (loaded.raycastParticlePool == null || loaded.raycastParticlePool.length == 0) {
                        loaded.raycastParticlePool = new SvcntrlConfig().raycastParticlePool;
                    }
                    instance = loaded;
                }
            } catch (Exception e) {
                SvcntrlMod.LOGGER.error("Failed to load svcntrl config", e);
            }
        }
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            SvcntrlMod.LOGGER.error("Failed to save svcntrl config", e);
        }
    }
}
