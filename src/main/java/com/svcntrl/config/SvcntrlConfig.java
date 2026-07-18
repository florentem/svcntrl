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
    private static Path CONFIG_PATH;
    static {
        try {
            CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("svcntrl.json");
        } catch (Throwable t) {
            CONFIG_PATH = Path.of("svcntrl.json");
        }
    }

    public String outlineParticle = "minecraft:flame";
    public int outlineFrequencyTicks = 15;
    public boolean allowPublicExport = false;
    public String customExportEndpoint = "";
    public long taskBudgetNs = 25_000_000L;
    public int maxRegionVolume = 5_000_000;
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

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                SvcntrlConfig loaded = GSON.fromJson(reader, SvcntrlConfig.class);
                if (loaded != null) {
                    // Validate and apply defaults for null fields
                    if (loaded.outlineParticle == null) loaded.outlineParticle = "minecraft:flame";
                    if (loaded.outlineFrequencyTicks <= 0) loaded.outlineFrequencyTicks = 15;
                    if (loaded.customExportEndpoint == null) loaded.customExportEndpoint = "";
                    
                    // Enforce hard limits to prevent Integer Overflow (max 100M blocks)
                    // 5.3: Fallback to the default of 5M (not 100M) when value is out-of-range
                    if (loaded.maxRegionVolume <= 0 || loaded.maxRegionVolume > 100_000_000) loaded.maxRegionVolume = 5_000_000;
                    
                    // Enforce hard limits for task budget to prevent server freeze (min 0.1ms, max 50ms)
                    if (loaded.taskBudgetNs < 100_000L) loaded.taskBudgetNs = 100_000L;
                    if (loaded.taskBudgetNs > 50_000_000L) loaded.taskBudgetNs = 50_000_000L;
                    
                    if (loaded.maxAutoSnapshots <= 0) loaded.maxAutoSnapshots = 10;
                    if (loaded.raycastParticlePool == null || loaded.raycastParticlePool.length == 0) {
                        loaded.raycastParticlePool = new SvcntrlConfig().raycastParticlePool;
                    }
                    // 5.2: Gson silently assigns false to boolean primitives when the JSON value is null.
                    // Detect this by comparing to the defaults from a fresh instance.
                    SvcntrlConfig defaults = new SvcntrlConfig();
                    if (!loaded.autoSaveOnBranchSwitch && defaults.autoSaveOnBranchSwitch) {
                        // Only warn — the user may have intentionally set it to false
                        SvcntrlMod.LOGGER.warn("[svcntrl] autoSaveOnBranchSwitch is false. If unintentional, check your config.");
                    }
                    if (!loaded.autoSaveOnBranchCreate && defaults.autoSaveOnBranchCreate) {
                        SvcntrlMod.LOGGER.warn("[svcntrl] autoSaveOnBranchCreate is false. If unintentional, check your config.");
                    }
                    if (!loaded.autoSaveOnRestore && defaults.autoSaveOnRestore) {
                        SvcntrlMod.LOGGER.warn("[svcntrl] autoSaveOnRestore is false. If unintentional, check your config.");
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
