package com.svcntrl.paper;

import com.svcntrl.config.SvcntrlConfig;
import com.svcntrl.core.TaskScheduler;
import com.svcntrl.data.ProjectManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;

public class SvcntrlPlugin extends JavaPlugin {
    private static SvcntrlPlugin instance;

    public static SvcntrlPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("[svcntrl] Initializing Svcntrl Paper plugin...");

        Path dataFolder = getDataFolder().toPath();
        SvcntrlConfig.load(dataFolder.resolve("svcntrl.json"));
        ProjectManager.getInstance().loadProjects(dataFolder.resolve("svcntrl_data"));
        PaperPreviewManager.getInstance().init(this);

        PaperCommands commands = new PaperCommands();
        PluginCommand cmd = getCommand("svcntrl");
        if (cmd != null) {
            cmd.setExecutor(commands);
            cmd.setTabCompleter(commands);
        } else {
            try {
                java.lang.reflect.Method getCommandMap = getServer().getClass().getMethod("getCommandMap");
                org.bukkit.command.CommandMap commandMap = (org.bukkit.command.CommandMap) getCommandMap.invoke(getServer());
                org.bukkit.command.Command fallbackCmd = new org.bukkit.command.Command("svcntrl") {
                    {
                        setAliases(java.util.List.of("sc"));
                        setDescription("Server-side version control for Minecraft builds");
                        setUsage("/svcntrl");
                    }
                    @Override
                    public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                        return commands.onCommand(sender, this, label, args);
                    }
                    @Override
                    public java.util.List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) {
                        return commands.onTabComplete(sender, this, alias, args);
                    }
                    @Override
                    public boolean testPermissionSilent(org.bukkit.command.CommandSender target) {
                        return true;
                    }
                };
                commandMap.register("svcntrl", fallbackCmd);
            } catch (Exception e) {
                getLogger().severe("[svcntrl] Failed to register command in CommandMap: " + e.getMessage());
            }
        }

        getServer().getPluginManager().registerEvents(new PaperListener(), this);

        getServer().getScheduler().runTaskTimer(this, () -> {
            TaskScheduler.getInstance().tick();
            PaperUXManager.getInstance().tick(getServer());
        }, 1L, 1L);

        getLogger().info("[svcntrl] Loaded " + ProjectManager.getInstance().getProjectCount() + " project(s).");
    }

    @Override
    public void onDisable() {
        getLogger().info("[svcntrl] Disabling Svcntrl Paper plugin...");
        ProjectManager.getInstance().saveProjects();
        TaskScheduler.getInstance().clear();
        getLogger().info("[svcntrl] Project data saved.");
    }
}
