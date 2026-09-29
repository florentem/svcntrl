package com.svcntrl.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SvcntrlConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("svcntrl");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath = Path.of("config/svcntrl.json");

    public String outlineParticle = "minecraft:flame";
    public int outlineFrequencyTicks = 15;
    public boolean allowPublicExport = false;
    public String customExportEndpoint = "";
    public long taskBudgetNs = 25_000_000L;
    public int maxRegionVolume = 2_000_000_000;
    public String[] raycastParticlePool = new String[]{
            "minecraft:end_rod", "minecraft:happy_villager", "minecraft:flame", "minecraft:soul_fire_flame",
            "minecraft:glow", "minecraft:wax_on", "minecraft:wax_off", "minecraft:nautilus",
            "minecraft:electric_spark", "minecraft:scrape", "minecraft:totem_of_undying", "minecraft:witch",
            "minecraft:cherry_leaves", "minecraft:soul", "minecraft:crimson_spore"
    };
    public boolean autoSaveOnBranchSwitch = true;
    public boolean autoSaveOnBranchCreate = true;
    public boolean autoSaveOnRestore = true;
    public int maxAutoSnapshots = 10;

    private static SvcntrlConfig instance = new SvcntrlConfig();

    public static SvcntrlConfig getInstance() {
        return instance;
    }

    public static void setConfigPath(Path path) {
        configPath = path;
    }

    public static Path getConfigPath() {
        return configPath;
    }

    public static void load(Path path) {
        setConfigPath(path);
        load();
    }

    public static void load() {
        if (configPath != null && Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                SvcntrlConfig loaded = GSON.fromJson(reader, SvcntrlConfig.class);
                if (loaded != null) {
                    if (loaded.outlineParticle == null) loaded.outlineParticle = "minecraft:flame";
                    if (loaded.outlineFrequencyTicks <= 0) loaded.outlineFrequencyTicks = 15;
                    if (loaded.customExportEndpoint == null) loaded.customExportEndpoint = "";
                    
                    if (loaded.maxRegionVolume <= 0 || loaded.maxRegionVolume > 2_000_000_000) loaded.maxRegionVolume = 2_000_000_000;
                    
                    if (loaded.taskBudgetNs < 100_000L) loaded.taskBudgetNs = 100_000L;
                    if (loaded.taskBudgetNs > 50_000_000L) loaded.taskBudgetNs = 50_000_000L;
                    
                    if (loaded.maxAutoSnapshots <= 0) loaded.maxAutoSnapshots = 10;
                    if (loaded.raycastParticlePool == null || loaded.raycastParticlePool.length == 0) {
                        loaded.raycastParticlePool = new SvcntrlConfig().raycastParticlePool;
                    }
                    instance = loaded;
                }
            } catch (Exception e) {
                LOGGER.error("[svcntrl] Failed to load config", e);
            }
        }
        save();
    }

    public static void save() {
        if (configPath == null) return;
        try {
            if (configPath.getParent() != null) {
                Files.createDirectories(configPath.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            LOGGER.error("[svcntrl] Failed to save config", e);
        }
    }
}
