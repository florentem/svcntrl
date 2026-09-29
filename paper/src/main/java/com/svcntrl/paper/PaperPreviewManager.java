package com.svcntrl.paper;

import com.svcntrl.core.TaskScheduler;
import com.svcntrl.data.BlockPos;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import com.svcntrl.util.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PaperPreviewManager {
    private static final PaperPreviewManager INSTANCE = new PaperPreviewManager();

    private final Map<UUID, Map<BlockPos, BlockData>> activePreviews = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> hiddenEntities = new ConcurrentHashMap<>();
    private final Map<UUID, String> previewingProjects = new ConcurrentHashMap<>();
    private final Map<UUID, BoundingBox> previewBoundingBoxes = new ConcurrentHashMap<>();
    private final Set<UUID> pendingPreviews = ConcurrentHashMap.newKeySet();

    private Plugin plugin;

    private PaperPreviewManager() {}

    public static PaperPreviewManager getInstance() {
        return INSTANCE;
    }

    public void init(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean hasPreview(UUID playerUuid) {
        return activePreviews.containsKey(playerUuid) || pendingPreviews.contains(playerUuid);
    }

    public boolean hasAnyPreviews() {
        return !activePreviews.isEmpty() || !pendingPreviews.isEmpty();
    }

    public Collection<String> getPreviewingProjects() {
        return previewingProjects.values();
    }

    public boolean isPreviewingProject(Project project) {
        String name = project.getName();
        return previewingProjects.values().stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    public BlockData getPreviewBlock(UUID playerUuid, BlockPos pos) {
        Map<BlockPos, BlockData> blocks = activePreviews.get(playerUuid);
        if (blocks != null) {
            return blocks.get(pos);
        }
        return null;
    }

    public void resendBlock(Player player, BlockPos pos) {
        BlockData data = getPreviewBlock(player.getUniqueId(), pos);
        if (data != null) {
            Location loc = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ());
            player.sendBlockChange(loc, data);
        }
    }

    public void startPreview(Player player, Project project, Map<BlockPos, BlockData> previewBlocks, String branchName, String category, int snapshotId) {
        if (hasPreview(player.getUniqueId())) {
            stopPreview(player);
        }

        UUID uuid = player.getUniqueId();
        activePreviews.put(uuid, previewBlocks);
        previewingProjects.put(uuid, project.getName());

        BoundingBox box = new BoundingBox(
                project.getMin().getX(), project.getMin().getY(), project.getMin().getZ(),
                project.getMax().getX() + 1.0, project.getMax().getY() + 1.0, project.getMax().getZ() + 1.0
        );
        previewBoundingBoxes.put(uuid, box);

        // Hide real entities inside bounding box
        Set<UUID> hidden = ConcurrentHashMap.newKeySet();
        hiddenEntities.put(uuid, hidden);

        World world = player.getWorld();
        for (Entity entity : world.getNearbyEntities(box)) {
            if (!(entity instanceof Player)) {
                player.hideEntity(plugin, entity);
                hidden.add(entity.getUniqueId());
            }
        }

        // Send preview blocks
        List<Map.Entry<BlockPos, BlockData>> entries = new ArrayList<>(previewBlocks.entrySet());
        TaskScheduler.getInstance().schedule(new TaskScheduler.TickTask() {
            private int index = 0;

            @Override
            public boolean tick(long maxTimeNs) {
                if (!player.isOnline() || !hasPreview(uuid)) return true;
                long start = System.nanoTime();
                while (index < entries.size()) {
                    Map.Entry<BlockPos, BlockData> entry = entries.get(index);
                    BlockPos p = entry.getKey();
                    Location loc = new Location(world, p.getX(), p.getY(), p.getZ());
                    player.sendBlockChange(loc, entry.getValue());
                    index++;
                    if ((index & 0x7F) == 0 && (System.nanoTime() - start) > maxTimeNs) {
                        return false;
                    }
                }
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.previewing", branchName, category, snapshotId), NamedTextColor.AQUA));
                return true;
            }
        });
    }

    public void stopPreview(Player player) {
        UUID uuid = player.getUniqueId();
        pendingPreviews.remove(uuid);
        Map<BlockPos, BlockData> blocks = activePreviews.remove(uuid);
        previewingProjects.remove(uuid);
        previewBoundingBoxes.remove(uuid);

        Set<UUID> hidden = hiddenEntities.remove(uuid);
        if (hidden != null) {
            for (UUID entityUuid : hidden) {
                Entity entity = Bukkit.getEntity(entityUuid);
                if (entity != null && entity.isValid()) {
                    player.showEntity(plugin, entity);
                }
            }
        }

        if (blocks != null && !blocks.isEmpty()) {
            World world = player.getWorld();
            List<BlockPos> list = new ArrayList<>(blocks.keySet());
            TaskScheduler.getInstance().schedule(new TaskScheduler.TickTask() {
                private int index = 0;

                @Override
                public boolean tick(long maxTimeNs) {
                    if (!player.isOnline()) return true;
                    long start = System.nanoTime();
                    while (index < list.size()) {
                        BlockPos p = list.get(index);
                        Location loc = new Location(world, p.getX(), p.getY(), p.getZ());
                        player.sendBlockChange(loc, world.getBlockData(loc));
                        index++;
                        if ((index & 0x7F) == 0 && (System.nanoTime() - start) > maxTimeNs) {
                            return false;
                        }
                    }
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.clearing_preview_100_0"), NamedTextColor.GREEN));
                    return true;
                }
            });
        }
    }
}
