package com.svcntrl.paper;

import com.svcntrl.core.TaskScheduler;
import com.svcntrl.data.BlockPos;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import com.svcntrl.nbt.NbtCompound;
import com.svcntrl.nbt.NbtIo;
import com.svcntrl.nbt.NbtList;
import com.svcntrl.util.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Consumer;

public class PaperAreaSerializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("svcntrl");

    public static void saveAreaAsync(Player player, World world, Project project, String branchName, String category, int snapshotId, Runnable onSuccess, Consumer<String> onError) {
        ProjectManager.getInstance().setProjectLocked(project, true);
        ProjectManager.runAsync(() -> {
            try {
                SaveTask task = new SaveTask(player, world, project, branchName, category, snapshotId, onSuccess, onError);
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("svcntrl"), () -> {
                    TaskScheduler.getInstance().schedule(task);
                });
            } catch (Throwable t) {
                ProjectManager.getInstance().setProjectLocked(project, false);
                if (onError != null) onError.accept("Error initializing save: " + t.getMessage());
            }
        });
    }

    public static boolean restoreArea(Player player, World world, Project project, String branchName, String category, int snapshotId, boolean excludeIntersections, Runnable onComplete, Runnable onFail) {
        Path filePath = ProjectManager.getInstance().getSnapshotPath(project, branchName, category, snapshotId);
        if (!Files.exists(filePath)) {
            LOGGER.error("[svcntrl] Snapshot file not found: {}", filePath);
            return false;
        }

        ProjectManager.getInstance().setProjectLocked(project, true);
        ProjectManager.runAsync(() -> {
            try {
                NbtCompound root = NbtIo.readCompressed(filePath);
                Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("svcntrl"), () -> {
                    try {
                        TaskScheduler.getInstance().schedule(new RestoreTask(player, world, project, root, onComplete, onFail).setExcludeIntersections(excludeIntersections));
                    } catch (Throwable t) {
                        LOGGER.error("[svcntrl] Failed to schedule restore", t);
                        ProjectManager.getInstance().setProjectLocked(project, false);
                        if (player != null && player.isOnline()) player.sendMessage(Component.text(Lang.get("svcntrl.msg.failed_to_schedule_restore"), NamedTextColor.RED));
                        if (onFail != null) onFail.run();
                    }
                });
            } catch (Throwable e) {
                LOGGER.error("[svcntrl] Failed to read snapshot file: {}", filePath, e);
                ProjectManager.getInstance().setProjectLocked(project, false);
                if (player != null && player.isOnline()) player.sendMessage(Component.text(Lang.get("svcntrl.msg.failed_to_read_snapshot_file"), NamedTextColor.RED));
                if (onFail != null) onFail.run();
            }
        });
        return true;
    }

    public static NbtCompound readSnapshot(Project project, String branchName, String category, int snapshotId) {
        Path filePath = ProjectManager.getInstance().getSnapshotPath(project, branchName, category, snapshotId);
        if (!Files.exists(filePath)) return null;
        try {
            return NbtIo.readCompressed(filePath);
        } catch (IOException e) {
            LOGGER.error("[svcntrl] Failed to read snapshot: {}", filePath, e);
            return null;
        }
    }

    private static class SaveTask implements TaskScheduler.TickTask {
        private final Player player;
        private final World world;
        private final Project project;
        private final String branchName;
        private final String category;
        private final int snapshotId;
        private final Runnable onSuccess;
        private final Consumer<String> onError;

        private final BlockPos min;
        private final BlockPos max;
        private final int width, height, length;
        private final NbtCompound root;
        private final int[] blockData;
        private final Map<String, Integer> statePaletteMap = new HashMap<>();
        private final List<NbtCompound> paletteList = new ArrayList<>();
        private final NbtList blockEntitiesList = new NbtList();

        private int cx, cy, cz;
        private boolean finished = false;
        private int processed = 0;
        private long lastMessageTime = 0;

        public SaveTask(Player player, World world, Project project, String branchName, String category, int snapshotId, Runnable onSuccess, Consumer<String> onError) {
            this.player = player;
            this.world = world;
            this.project = project;
            this.branchName = branchName;
            this.category = category;
            this.snapshotId = snapshotId;
            this.onSuccess = onSuccess;
            this.onError = onError;

            this.min = project.getMin();
            this.max = project.getMax();

            this.width = max.getX() - min.getX() + 1;
            this.height = max.getY() - min.getY() + 1;
            this.length = max.getZ() - min.getZ() + 1;

            this.blockData = new int[width * height * length];

            this.cx = min.getX();
            this.cy = min.getY();
            this.cz = min.getZ();

            this.root = new NbtCompound();
            root.putInt("Version", 2);
            root.putInt("MinX", min.getX());
            root.putInt("MinY", min.getY());
            root.putInt("MinZ", min.getZ());
            root.putInt("MaxX", max.getX());
            root.putInt("MaxY", max.getY());
            root.putInt("MaxZ", max.getZ());
        }

        private int getPaletteIndex(BlockData data) {
            String str = data.getAsString();
            Integer existing = statePaletteMap.get(str);
            if (existing != null) return existing;

            int newIndex = paletteList.size();
            statePaletteMap.put(str, newIndex);

            NbtCompound entry = new NbtCompound();
            entry.putString("BlockId", data.getMaterial().getKey().toString());
            entry.putString("BlockDataString", str);
            paletteList.add(entry);
            return newIndex;
        }

        @Override
        public boolean tick(long maxTimeNs) {
            if (finished) return true;
            long startTime = System.nanoTime();

            while (cz <= max.getZ()) {
                while (cy <= max.getY()) {
                    while (cx <= max.getX()) {
                        Block block = world.getBlockAt(cx, cy, cz);
                        BlockData data = block.getBlockData();

                        int rx = cx - min.getX();
                        int ry = cy - min.getY();
                        int rz = cz - min.getZ();
                        int index = rz * (width * height) + ry * width + rx;
                        blockData[index] = getPaletteIndex(data);

                        BlockState state = block.getState();
                        if (state instanceof Container container) {
                            NbtCompound beNbt = new NbtCompound();
                            beNbt.putInt("X", rx);
                            beNbt.putInt("Y", ry);
                            beNbt.putInt("Z", rz);
                            NbtCompound invData = new NbtCompound();
                            ItemStack[] contents = container.getInventory().getContents();
                            NbtList items = new NbtList();
                            for (int slot = 0; slot < contents.length; slot++) {
                                ItemStack it = contents[slot];
                                if (it != null && !it.getType().isAir()) {
                                    NbtCompound itemNbt = new NbtCompound();
                                    itemNbt.putInt("Slot", slot);
                                    itemNbt.putString("id", it.getType().getKey().toString());
                                    itemNbt.putInt("Count", it.getAmount());
                                    items.add(itemNbt);
                                }
                            }
                            invData.put("Items", items);
                            beNbt.put("Data", invData);
                            blockEntitiesList.add(beNbt);
                        }

                        processed++;
                        cx++;

                        if ((processed & 0xFF) == 0 && (System.nanoTime() - startTime) > maxTimeNs) {
                            if (player != null && player.isOnline()) {
                                long now = System.currentTimeMillis();
                                if (now - lastMessageTime > 50) {
                                    float pct = (float) processed / (width * (long) height * length) * 100f;
                                    player.sendActionBar(Component.text(Lang.get("svcntrl.msg.saving_blocks_progress", String.format(Locale.US, "%.1f", pct)), NamedTextColor.GREEN));
                                    lastMessageTime = now;
                                }
                            }
                            return false;
                        }
                    }
                    cx = min.getX();
                    cy++;
                }
                cy = min.getY();
                cz++;
            }

            finished = true;
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.text(Lang.get("svcntrl.msg.saving_100_0"), NamedTextColor.GREEN));
            }
            finishSave();
            return true;
        }

        @Override
        public void onCancel(Throwable t) {
            ProjectManager.getInstance().setProjectLocked(project, false);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.save_failed_cancelled"), NamedTextColor.RED));
            }
            if (onError != null) onError.accept(t.getMessage());
        }

        private void finishSave() {
            NbtList pList = new NbtList();
            for (NbtCompound c : paletteList) pList.add(c);
            root.put("Palette", pList);
            root.putIntArray("BlockData", blockData);
            root.put("BlockEntities", blockEntitiesList);

            BoundingBox box = new BoundingBox(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
            NbtList entities = new NbtList();
            for (Entity e : world.getNearbyEntities(box)) {
                if (e instanceof Player) continue;
                NbtCompound eNbt = new NbtCompound();
                eNbt.putString("id", e.getType().getKey().toString());
                eNbt.putDouble("svcntrl_RelX", e.getLocation().getX() - min.getX());
                eNbt.putDouble("svcntrl_RelY", e.getLocation().getY() - min.getY());
                eNbt.putDouble("svcntrl_RelZ", e.getLocation().getZ() - min.getZ());
                entities.add(eNbt);
            }
            root.put("Entities", entities);

            ProjectManager.runAsync(() -> {
                try {
                    Path filePath = ProjectManager.getInstance().getSnapshotPath(project, branchName, category, snapshotId);
                    Files.createDirectories(filePath.getParent());
                    Path tempPath = filePath.getParent().resolve(filePath.getFileName() + ".tmp");
                    NbtIo.writeCompressed(root, tempPath);
                    Files.move(tempPath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("[svcntrl] Saved V2 snapshot {} ({}) for project '{}'", snapshotId, category, project.getName());

                    Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("svcntrl"), () -> {
                        try {
                            if (onSuccess != null) onSuccess.run();
                        } finally {
                            ProjectManager.getInstance().setProjectLocked(project, false);
                        }
                    });
                } catch (Throwable t) {
                    LOGGER.error("[svcntrl] Error saving snapshot", t);
                    Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("svcntrl"), () -> {
                        try {
                            if (onError != null) onError.accept(t.getMessage());
                        } finally {
                            ProjectManager.getInstance().setProjectLocked(project, false);
                        }
                    });
                }
            });
        }
    }

    private static class RestoreTask implements TaskScheduler.TickTask {
        private final Player player;
        private final World world;
        private final Project project;
        private final BlockPos min;
        private final int width, height, length;
        private final int[] blockData;
        private final BlockData[] palette;
        private final NbtList blockEntitiesList;
        private final NbtList entities;
        private final Runnable onComplete;
        private final Runnable onFail;

        private List<Project> overlappingProjects = null;
        private int phase = -1; // -1 = clear entities, 0 = set blocks, 1 = block entities, 2 = entities
        private int cx, cy, cz;
        private int currentIndex = 0;
        private long lastMessageTime = 0;

        public RestoreTask(Player player, World world, Project project, NbtCompound root, Runnable onComplete, Runnable onFail) {
            this.player = player;
            this.world = world;
            this.project = project;
            this.min = project.getMin();
            this.onComplete = onComplete;
            this.onFail = onFail;

            this.width = root.getInt("MaxX", 0) - root.getInt("MinX", 0) + 1;
            this.height = root.getInt("MaxY", 0) - root.getInt("MinY", 0) + 1;
            this.length = root.getInt("MaxZ", 0) - root.getInt("MinZ", 0) + 1;

            this.blockData = root.getIntArray("BlockData");
            this.blockEntitiesList = root.getListOrEmpty("BlockEntities");
            this.entities = root.getListOrEmpty("Entities");

            NbtList pList = root.getListOrEmpty("Palette");
            this.palette = new BlockData[pList.size()];
            for (int i = 0; i < pList.size(); i++) {
                NbtCompound p = pList.getCompoundOrEmpty(i);
                String dataStr = p.getString("BlockDataString", "");
                if (!dataStr.isEmpty()) {
                    try {
                        palette[i] = Bukkit.createBlockData(dataStr);
                    } catch (Exception e) {
                        palette[i] = Bukkit.createBlockData(p.getString("BlockId", "minecraft:air"));
                    }
                } else {
                    palette[i] = Bukkit.createBlockData(p.getString("BlockId", "minecraft:air"));
                }
            }

            this.cx = min.getX();
            this.cy = min.getY();
            this.cz = min.getZ();
        }

        public RestoreTask setExcludeIntersections(boolean exclude) {
            if (exclude) {
                this.overlappingProjects = new ArrayList<>();
                for (Project p : ProjectManager.getInstance().getProjects()) {
                    if (p != project && p.getWorldId().equalsIgnoreCase(project.getWorldId()) && p.intersects(project)) {
                        overlappingProjects.add(p);
                    }
                }
            }
            return this;
        }

        @Override
        public boolean tick(long maxTimeNs) {
            long start = System.nanoTime();

            if (phase == -1) {
                BoundingBox box = new BoundingBox(min.getX(), min.getY(), min.getZ(), min.getX() + width, min.getY() + height, min.getZ() + length);
                for (Entity e : world.getNearbyEntities(box)) {
                    if (e instanceof Player) continue;
                    if (overlappingProjects != null) {
                        boolean skip = false;
                        for (Project p : overlappingProjects) {
                            if (p.contains(e.getLocation().getBlockX(), e.getLocation().getBlockY(), e.getLocation().getBlockZ())) {
                                skip = true;
                                break;
                            }
                        }
                        if (skip) continue;
                    }
                    e.remove();
                }
                phase = 0;
            }

            if (phase == 0) {
                int totalBlocks = blockData.length;
                while (cz < min.getZ() + length) {
                    while (cy < min.getY() + height) {
                        while (cx < min.getX() + width) {
                            if (overlappingProjects != null) {
                                boolean skip = false;
                                for (Project p : overlappingProjects) {
                                    if (p.contains(cx, cy, cz)) {
                                        skip = true;
                                        break;
                                    }
                                }
                                if (skip) {
                                    currentIndex++;
                                    cx++;
                                    continue;
                                }
                            }

                            int pIndex = blockData[currentIndex];
                            BlockData data = (pIndex >= 0 && pIndex < palette.length) ? palette[pIndex] : Bukkit.createBlockData("minecraft:air");
                            Block block = world.getBlockAt(cx, cy, cz);
                            if (!block.getBlockData().matches(data)) {
                                block.setBlockData(data, false);
                            }

                            currentIndex++;
                            cx++;

                            if ((currentIndex & 0x7F) == 0 && (System.nanoTime() - start) > maxTimeNs) {
                                if (player != null && player.isOnline()) {
                                    long now = System.currentTimeMillis();
                                    if (now - lastMessageTime > 50) {
                                        float pct = (float) currentIndex / totalBlocks * 100f;
                                        player.sendActionBar(Component.text(Lang.get("svcntrl.msg.restoring_blocks_progress", String.format(Locale.US, "%.1f", pct)), NamedTextColor.GREEN));
                                        lastMessageTime = now;
                                    }
                                }
                                return false;
                            }
                        }
                        cx = min.getX();
                        cy++;
                    }
                    cy = min.getY();
                    cz++;
                }
                phase = 1;
                currentIndex = 0;
            }

            if (phase == 1) {
                while (currentIndex < blockEntitiesList.size()) {
                    NbtCompound beNbt = blockEntitiesList.getCompoundOrEmpty(currentIndex);
                    int x = min.getX() + beNbt.getInt("X", 0);
                    int y = min.getY() + beNbt.getInt("Y", 0);
                    int z = min.getZ() + beNbt.getInt("Z", 0);

                    Block block = world.getBlockAt(x, y, z);
                    BlockState state = block.getState();
                    if (state instanceof Container container) {
                        NbtCompound invData = beNbt.getCompoundOrEmpty("Data");
                        NbtList items = invData.getListOrEmpty("Items");
                        container.getInventory().clear();
                        for (int i = 0; i < items.size(); i++) {
                            NbtCompound itNbt = items.getCompoundOrEmpty(i);
                            int slot = itNbt.getInt("Slot", 0);
                            String id = itNbt.getString("id", "minecraft:air").replace("minecraft:", "").toUpperCase(Locale.ROOT);
                            int count = itNbt.getInt("Count", 1);
                            org.bukkit.Material mat = org.bukkit.Material.getMaterial(id);
                            if (mat != null) {
                                container.getInventory().setItem(slot, new ItemStack(mat, count));
                            }
                        }
                        state.update(true, false);
                    }
                    currentIndex++;
                }
                phase = 2;
                currentIndex = 0;
            }

            if (phase == 2) {
                while (currentIndex < entities.size()) {
                    NbtCompound eNbt = entities.getCompoundOrEmpty(currentIndex);
                    double absX = min.getX() + eNbt.getDouble("svcntrl_RelX", 0.0);
                    double absY = min.getY() + eNbt.getDouble("svcntrl_RelY", 0.0);
                    double absZ = min.getZ() + eNbt.getDouble("svcntrl_RelZ", 0.0);

                    String id = eNbt.getString("id", "").replace("minecraft:", "").toUpperCase(Locale.ROOT);
                    try {
                        EntityType type = EntityType.valueOf(id);
                        world.spawnEntity(new Location(world, absX, absY, absZ), type);
                    } catch (Exception ignored) {}

                    currentIndex++;
                }

                ProjectManager.getInstance().setProjectLocked(project, false);
                if (player != null && player.isOnline()) {
                    player.sendActionBar(Component.text(Lang.get("svcntrl.msg.restore_completed"), NamedTextColor.GOLD));
                }
                if (onComplete != null) onComplete.run();
                return true;
            }

            return false;
        }

        @Override
        public void onCancel(Throwable t) {
            ProjectManager.getInstance().setProjectLocked(project, false);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.restore_failed_cancelled"), NamedTextColor.RED));
            }
            if (onFail != null) onFail.run();
        }
    }
}
