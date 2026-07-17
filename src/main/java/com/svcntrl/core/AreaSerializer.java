package com.svcntrl.core;

import com.svcntrl.SvcntrlMod;
import com.svcntrl.config.SvcntrlConfig;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Core engine for saving and restoring entire areas of the world.
 * Handles blocks (with states), block entities (NBT data), and entities.
 *
 * This is the most critical module — everything else (commands, preview, restore)
 * depends on its correctness.
 */
public class AreaSerializer {

    public static void saveAreaAsync(net.minecraft.server.network.ServerPlayerEntity player, ServerWorld world, Project project, String branchName, String category, int snapshotId, Runnable onSuccess, java.util.function.Consumer<String> onError) {
        ProjectManager.getInstance().setProjectLocked(project, true);
        try {
            TaskScheduler.getInstance().schedule(new SaveTask(player, world, project, branchName, category, snapshotId, onSuccess, onError));
        } catch (Throwable t) {
            ProjectManager.getInstance().setProjectLocked(project, false);
            if (onError != null) onError.accept("Internal error during save task initialization: " + t.getMessage());
        }
    }

    /**
     * Restores an area from a previously saved NBT snapshot.
     * This is a destructive operation — it replaces everything in the area.
     *
     * Steps:
     * 1. Clear all existing entities in the area (except players).
     * 2. Overwrite all blocks with states from the snapshot (in time-sliced batches).
     * 3. Restore block entity data for the placed blocks.
     * 4. Spawn entities from the snapshot.
     */
    public static boolean restoreArea(net.minecraft.server.network.ServerPlayerEntity player, ServerWorld world, Project project, String branchName, String category, int snapshotId, boolean excludeIntersections) {
        Path filePath = ProjectManager.getInstance().getSnapshotPath(project, branchName, category, snapshotId);
        if (!Files.exists(filePath)) {
            SvcntrlMod.LOGGER.error("[svcntrl] Snapshot file not found: {}", filePath);
            return false;
        }

        ProjectManager.getInstance().setProjectLocked(project, true);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                NbtCompound root = NbtIo.readCompressed(filePath, NbtSizeTracker.ofUnlimitedBytes());
                world.getServer().execute(() -> {
                    try {
                        BlockPos min = project.getMin();
                        BlockPos max = project.getMax();

                        NbtList entities = root.getListOrEmpty("Entities");

                        TaskScheduler.getInstance().schedule(new RestoreTask(player, world, min, root, entities, project).setExcludeIntersections(excludeIntersections));
                        SvcntrlMod.LOGGER.info("[svcntrl] Scheduled restore task for project '{}' (snapshot {} {})", project.getName(), snapshotId, category);
                    } catch (Throwable t) {
                        SvcntrlMod.LOGGER.error("[svcntrl] Failed to schedule restore task", t);
                        ProjectManager.getInstance().setProjectLocked(project, false);
                        if (player != null) player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.failed_to_schedule_restore").formatted(net.minecraft.util.Formatting.RED));
                    }
                });
            } catch (Throwable e) {
                SvcntrlMod.LOGGER.error("[svcntrl] Failed to read snapshot file: {}", filePath, e);
                ProjectManager.getInstance().setProjectLocked(project, false);
                if (player != null) {
                    player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.failed_to_read_snapshot_file").formatted(net.minecraft.util.Formatting.RED));
                }
            }
        });
        return true;
    }

    public static boolean restorePatchArea(net.minecraft.server.network.ServerPlayerEntity player, ServerWorld world, Project project, String targetBranch, String targetCategory, int targetId, String baseBranch, String baseCategory, int baseId, boolean excludeIntersections) {
        Path targetPath = ProjectManager.getInstance().getSnapshotPath(project, targetBranch, targetCategory, targetId);
        Path basePath = ProjectManager.getInstance().getSnapshotPath(project, baseBranch, baseCategory, baseId);

        if (!Files.exists(targetPath) || !Files.exists(basePath)) {
            return false;
        }

        ProjectManager.getInstance().setProjectLocked(project, true);
        BlockPos min = project.getMin();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                NbtCompound targetRoot = net.minecraft.nbt.NbtIo.readCompressed(targetPath, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
                NbtCompound baseRoot = net.minecraft.nbt.NbtIo.readCompressed(basePath, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());

                boolean isV2Target = targetRoot.contains("Version") && targetRoot.getInt("Version", 1) == 2;
                boolean isV2Base = baseRoot.contains("Version") && baseRoot.getInt("Version", 1) == 2;
                if (!isV2Target || !isV2Base) {
                    ProjectManager.getInstance().setProjectLocked(project, false);
                    if (player != null) player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.patch_restore_only_supports_v2").formatted(net.minecraft.util.Formatting.RED));
                    return;
                }

                int[] tData = targetRoot.getIntArray("BlockData").orElse(new int[0]);
                NbtList tPalList = targetRoot.getListOrEmpty("Palette");
                BlockState[] tPalette = new BlockState[tPalList.size()];
                for (int i = 0; i < tPalList.size(); i++) {
                    NbtCompound p = tPalList.getCompoundOrEmpty(i);
                    Block block = Registries.BLOCK.get(Identifier.of(p.getString("BlockId", "minecraft:air")));
                    BlockState state = block.getDefaultState();
                    if (p.contains("Properties")) state = com.svcntrl.util.BlockUtils.applyProperties(state, p.getCompoundOrEmpty("Properties"));
                    tPalette[i] = state;
                }

                int[] bData = baseRoot.getIntArray("BlockData").orElse(new int[0]);
                NbtList bPalList = baseRoot.getListOrEmpty("Palette");
                BlockState[] bPalette = new BlockState[bPalList.size()];
                for (int i = 0; i < bPalList.size(); i++) {
                    NbtCompound p = bPalList.getCompoundOrEmpty(i);
                    Block block = Registries.BLOCK.get(Identifier.of(p.getString("BlockId", "minecraft:air")));
                    BlockState state = block.getDefaultState();
                    if (p.contains("Properties")) state = com.svcntrl.util.BlockUtils.applyProperties(state, p.getCompoundOrEmpty("Properties"));
                    bPalette[i] = state;
                }

                int sizeX = targetRoot.getInt("MaxX", 0) - targetRoot.getInt("MinX", 0) + 1;
                int sizeY = targetRoot.getInt("MaxY", 0) - targetRoot.getInt("MinY", 0) + 1;

                java.util.Map<Integer, NbtCompound> tBEs = new java.util.HashMap<>();
                NbtList tBEList = targetRoot.getListOrEmpty("BlockEntities");
                for (int i = 0; i < tBEList.size(); i++) {
                    NbtCompound be = tBEList.getCompoundOrEmpty(i);
                    int idx = be.getInt("X", 0) + be.getInt("Y", 0) * sizeX + be.getInt("Z", 0) * sizeX * sizeY;
                    tBEs.put(idx, be.getCompoundOrEmpty("Data"));
                }

                java.util.Map<Integer, NbtCompound> bBEs = new java.util.HashMap<>();
                NbtList bBEList = baseRoot.getListOrEmpty("BlockEntities");
                for (int i = 0; i < bBEList.size(); i++) {
                    NbtCompound be = bBEList.getCompoundOrEmpty(i);
                    int idx = be.getInt("X", 0) + be.getInt("Y", 0) * sizeX + be.getInt("Z", 0) * sizeX * sizeY;
                    bBEs.put(idx, be.getCompoundOrEmpty("Data"));
                }

                boolean[] patchMask = new boolean[tData.length];
                for (int i = 0; i < tData.length; i++) {
                    BlockState tState = (tData[i] >= 0 && tData[i] < tPalette.length) ? tPalette[tData[i]] : Blocks.AIR.getDefaultState();
                    BlockState bState = (bData.length > i && bData[i] >= 0 && bData[i] < bPalette.length) ? bPalette[bData[i]] : Blocks.AIR.getDefaultState();
                    NbtCompound tBE = tBEs.get(i);
                    NbtCompound bBE = bBEs.get(i);
                    boolean beEqual = (tBE == null && bBE == null) || (tBE != null && tBE.equals(bBE));
                    patchMask[i] = !(tState.equals(bState) && beEqual);
                }

                
                NbtList patchEntities = targetRoot.getListOrEmpty("Entities");


                world.getServer().execute(() -> {
                    try {
                        NbtList emptyEntities = new NbtList();
                        TaskScheduler.getInstance().schedule(new RestoreTask(player, world, min, targetRoot, emptyEntities, project, patchMask, patchEntities).setExcludeIntersections(excludeIntersections));
                        SvcntrlMod.LOGGER.info("[svcntrl] Scheduled patch task for project '{}' (target {} vs base {})", project.getName(), targetId, baseId);
                    } catch (Throwable t) {
                        SvcntrlMod.LOGGER.error("[svcntrl] Failed to schedule patch task", t);
                        ProjectManager.getInstance().setProjectLocked(project, false);
                        if (player != null) player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.failed_to_schedule_patch_resto").formatted(net.minecraft.util.Formatting.RED));
                    }
                });
            } catch (Throwable e) {
                SvcntrlMod.LOGGER.error("[svcntrl] Failed to read patch files", e);
                ProjectManager.getInstance().setProjectLocked(project, false);
                if (player != null) player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.failed_to_read_snapshots_for_p").formatted(net.minecraft.util.Formatting.RED));
            }
        });
        return true;
    }

    private static class RestoreTask implements TaskScheduler.TickTask {
        private final net.minecraft.server.network.ServerPlayerEntity player;
        private final ServerWorld world;
        private final BlockPos min;
        
        // V1 fields
        private final NbtList blocks;
        
        // V2 fields
        private final int[] blockData;
        private final BlockState[] palette;
        private final NbtList blockEntitiesList;
        private final int width, height, length;
        
        private final NbtList entities;
        private final Project project;
        private final boolean[] patchMask;
        private final NbtList patchEntities;
        
        private java.util.List<Project> overlappingProjects = null;
        
        private int currentIndex = 0;
        private int phase = -1; // -1 = clear entities, 0 = set blocks, 1 = block entities, 2 = spawn entities
        
        private final BlockPos.Mutable mutable = new BlockPos.Mutable();
        private long lastMessageTime = 0;

        public RestoreTask(net.minecraft.server.network.ServerPlayerEntity player, ServerWorld world, BlockPos min, NbtCompound root, NbtList entities, Project project) {
            this(player, world, min, root, entities, project, null, null);
        }

        public RestoreTask(net.minecraft.server.network.ServerPlayerEntity player, ServerWorld world, BlockPos min, NbtCompound root, NbtList entities, Project project, boolean[] patchMask, NbtList patchEntities) {
            this.player = player;
            this.world = world;
            this.min = min;
            this.entities = entities;
            this.project = project;
            this.patchMask = patchMask;
            this.patchEntities = patchEntities;
            
            this.phase = -1; // Always clear entities so removed entities are actually removed
            
            if (root.contains("Version") && root.getInt("Version", 1) == 2) {
                this.blocks = null;
                this.blockData = root.getIntArray("BlockData").orElse(new int[0]);
                this.blockEntitiesList = root.getListOrEmpty("BlockEntities");
                
                this.width = root.getInt("MaxX", 0) - root.getInt("MinX", 0) + 1;
                this.height = root.getInt("MaxY", 0) - root.getInt("MinY", 0) + 1;
                this.length = root.getInt("MaxZ", 0) - root.getInt("MinZ", 0) + 1;
                
                NbtList pList = root.getListOrEmpty("Palette");
                this.palette = new BlockState[pList.size()];
                for (int i = 0; i < pList.size(); i++) {
                    NbtCompound pEntry = pList.getCompoundOrEmpty(i);
                    String blockIdStr = pEntry.getString("BlockId", "minecraft:air");
                    Block block = Registries.BLOCK.get(Identifier.of(blockIdStr));
                    BlockState state = block.getDefaultState();
                    if (pEntry.contains("Properties")) {
                        state = com.svcntrl.util.BlockUtils.applyProperties(state, pEntry.getCompoundOrEmpty("Properties"));
                    }
                    this.palette[i] = state;
                }
            } else {
                this.blocks = root.getListOrEmpty("Blocks");
                this.blockData = null;
                this.palette = null;
                this.blockEntitiesList = null;
                this.width = this.height = this.length = 0;
            }
        }

        public RestoreTask setExcludeIntersections(boolean exclude) {
            if (exclude) {
                this.overlappingProjects = new java.util.ArrayList<>();
                for (Project p : ProjectManager.getInstance().getProjects()) {
                    if (p != project && p.getWorldId().equals(project.getWorldId()) && p.intersects(project)) {
                        this.overlappingProjects.add(p);
                    }
                }
            }
            return this;
        }

        @Override
        public boolean tick(long maxTimeNs) {
            try {
                return tickInternal(maxTimeNs);
            } catch (Throwable t) {
                onCancel(t);
                throw t;
            }
        }
        
        private boolean tickInternal(long maxTimeNs) {
            int ops = 0;
            long startTime = System.nanoTime();

            if (phase == -1) {
                int minChunkX = min.getX() >> 4;
                int maxChunkX = project.getMax().getX() >> 4;
                int minChunkZ = min.getZ() >> 4;
                int maxChunkZ = project.getMax().getZ() >> 4;
                int totalChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
                
                while (currentIndex < totalChunks) {
                    int rcz = currentIndex / (maxChunkX - minChunkX + 1);
                    int rcx = currentIndex % (maxChunkX - minChunkX + 1);
                    int chunkX = minChunkX + rcx;
                    int chunkZ = minChunkZ + rcz;
                    
                    Box chunkBox = new Box(chunkX * 16, min.getY(), chunkZ * 16, chunkX * 16 + 16, project.getMax().getY() + 1, chunkZ * 16 + 16);
                    Box intersect = chunkBox.intersection(new Box(min.getX(), min.getY(), min.getZ(), project.getMax().getX() + 1, project.getMax().getY() + 1, project.getMax().getZ() + 1));
                    
                    java.util.List<net.minecraft.entity.Entity> existingEntities = world.getOtherEntities(null, intersect, e -> {
                        if (e instanceof net.minecraft.entity.player.PlayerEntity) return false;
                        if (overlappingProjects != null) {
                            for (Project p : overlappingProjects) {
                                if (p.contains(e.getBlockPos())) return false;
                            }
                        }
                        return true;
                    });
                    for (net.minecraft.entity.Entity e : existingEntities) {
                        e.discard();
                    }
                    
                    currentIndex++;
                    ops++;
                    if ((ops & 0xF) == 0 && (System.nanoTime() - startTime) > maxTimeNs) {
                        if (player != null) {
                            long now = System.currentTimeMillis();
                            if (now - lastMessageTime > 500) {
                                float percent = (float) currentIndex / totalChunks * 100f;
                                player.sendMessage(net.minecraft.text.Text.literal(String.format("Clearing Entities: %.1f%%", percent)).formatted(net.minecraft.util.Formatting.GREEN), true);
                                lastMessageTime = now;
                            }
                        }
                        return false;
                    }
                }
                
                phase = 0;
                currentIndex = 0;
            }

            if (phase == 0) {
                int totalBlocks = blocks != null ? blocks.size() : (blockData != null ? blockData.length : 0);
                net.minecraft.world.chunk.WorldChunk cachedChunk = null;
                int cachedChunkX = Integer.MIN_VALUE;
                int cachedChunkZ = Integer.MIN_VALUE;
                
                while (currentIndex < totalBlocks) {
                    if (blocks != null) {
                        // V1
                        NbtCompound blockNbt = blocks.getCompoundOrEmpty(currentIndex);
                        mutable.set(min.getX() + blockNbt.getInt("X", 0), 
                                    min.getY() + blockNbt.getInt("Y", 0), 
                                    min.getZ() + blockNbt.getInt("Z", 0));
                        
                        if (overlappingProjects != null) {
                            boolean skip = false;
                            for (Project p : overlappingProjects) {
                                if (p.contains(mutable)) { skip = true; break; }
                            }
                            if (skip) {
                                currentIndex++; ops++;
                                if ((ops & 0xFF) == 0 && (System.nanoTime() - startTime) > maxTimeNs) return false;
                                continue;
                            }
                        }
                        
                        String blockIdStr = blockNbt.getString("BlockId", "minecraft:air");
                        Block block = Registries.BLOCK.get(Identifier.of(blockIdStr));
                        BlockState state = block.getDefaultState();
                        if (blockNbt.contains("Properties")) {
                            state = com.svcntrl.util.BlockUtils.applyProperties(state, blockNbt.getCompoundOrEmpty("Properties"));
                        }
                        
                        int currentChunkX = mutable.getX() >> 4;
                        int currentChunkZ = mutable.getZ() >> 4;
                        if (cachedChunk == null || cachedChunkX != currentChunkX || cachedChunkZ != currentChunkZ) {
                            net.minecraft.world.chunk.Chunk chunk = world.getChunk(currentChunkX, currentChunkZ, net.minecraft.world.chunk.ChunkStatus.FULL, false);
                            if (chunk == null) {
                                world.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.UNKNOWN, new net.minecraft.util.math.ChunkPos(currentChunkX, currentChunkZ), 2);
                                return false;
                            }
                            cachedChunk = (net.minecraft.world.chunk.WorldChunk) chunk;
                            cachedChunkX = currentChunkX;
                            cachedChunkZ = currentChunkZ;
                            if ((System.nanoTime() - startTime) > maxTimeNs) return false;
                        }
                        
                        if (!state.equals(cachedChunk.getBlockState(mutable))) {
                            world.setBlockState(mutable, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | 32); // 32 = SKIP_DROPS
                        }
                    } else if (blockData != null) {
                        // V2
                        int rz = currentIndex / (width * height);
                        int rem = currentIndex % (width * height);
                        int ry = rem / width;
                        int rx = rem % width;
                        
                        if (patchMask != null && !patchMask[currentIndex]) {
                            currentIndex++;
                            ops++;
                            if ((ops & 0xFF) == 0 && (System.nanoTime() - startTime) > maxTimeNs) return false;
                            continue;
                        }

                        mutable.set(min.getX() + rx, min.getY() + ry, min.getZ() + rz);

                        if (overlappingProjects != null) {
                            boolean skip = false;
                            for (Project p : overlappingProjects) {
                                if (p.contains(mutable)) { skip = true; break; }
                            }
                            if (skip) {
                                currentIndex++; ops++;
                                if ((ops & 0xFF) == 0 && (System.nanoTime() - startTime) > maxTimeNs) return false;
                                continue;
                            }
                        }
                        int pIndex = blockData[currentIndex];
                        BlockState state = (pIndex >= 0 && pIndex < palette.length) ? palette[pIndex] : Blocks.AIR.getDefaultState();
                        
                        int currentChunkX = mutable.getX() >> 4;
                        int currentChunkZ = mutable.getZ() >> 4;
                        if (cachedChunk == null || cachedChunkX != currentChunkX || cachedChunkZ != currentChunkZ) {
                            net.minecraft.world.chunk.Chunk chunk = world.getChunk(currentChunkX, currentChunkZ, net.minecraft.world.chunk.ChunkStatus.FULL, false);
                            if (chunk == null) {
                                world.getChunkManager().addTicket(net.minecraft.server.world.ChunkTicketType.UNKNOWN, new net.minecraft.util.math.ChunkPos(currentChunkX, currentChunkZ), 2);
                                return false;
                            }
                            cachedChunk = (net.minecraft.world.chunk.WorldChunk) chunk;
                            cachedChunkX = currentChunkX;
                            cachedChunkZ = currentChunkZ;
                            if ((System.nanoTime() - startTime) > maxTimeNs) return false;
                        }

                        if (!state.equals(cachedChunk.getBlockState(mutable))) {
                            world.setBlockState(mutable, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | 32); // 32 = SKIP_DROPS
                        }
                    }
                    
                    currentIndex++;
                    ops++;
                    
                    if ((ops & 0xFF) == 0) {
                        if (player != null) {
                            long now = System.currentTimeMillis();
                            if (now - lastMessageTime > 500) {
                                float percent = (float) currentIndex / totalBlocks * 100f;
                                player.sendMessage(net.minecraft.text.Text.literal(String.format("Restoring Blocks: %.1f%%", percent)).formatted(net.minecraft.util.Formatting.GREEN), true);
                                lastMessageTime = now;
                            }
                        }
                        if ((System.nanoTime() - startTime) > maxTimeNs) {
                            return false;
                        }
                    }
                }
                if (currentIndex >= totalBlocks) {
                    currentIndex = 0;
                    phase = 1;
                    if (player != null) player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.restoring_blocks_100_0").formatted(net.minecraft.util.Formatting.GREEN), true);
                }
            }
            
            if (phase == 1) {
                int totalBEs = blocks != null ? blocks.size() : (blockEntitiesList != null ? blockEntitiesList.size() : 0);
                while (currentIndex < totalBEs) {
                    if (patchMask != null && blockEntitiesList != null) {
                        NbtCompound beNbt = blockEntitiesList.getCompoundOrEmpty(currentIndex);
                        int rx = beNbt.getInt("X", 0);
                        int ry = beNbt.getInt("Y", 0);
                        int rz = beNbt.getInt("Z", 0);
                        int flatIndex = rx + ry * width + rz * width * height;
                        if (!patchMask[flatIndex]) {
                            currentIndex++;
                            ops++;
                            if ((ops & 0xFF) == 0 && (System.nanoTime() - startTime) > maxTimeNs) return false;
                            continue;
                        }
                    }

                    NbtCompound blockNbt = blocks != null ? blocks.getCompoundOrEmpty(currentIndex) : blockEntitiesList.getCompoundOrEmpty(currentIndex);
                    boolean hasBE = blocks != null ? blockNbt.contains("BlockEntityData") : blockNbt.contains("Data");
                    
                    if (hasBE) {
                        mutable.set(min.getX() + blockNbt.getInt("X", 0), 
                                    min.getY() + blockNbt.getInt("Y", 0), 
                                    min.getZ() + blockNbt.getInt("Z", 0));
                                    
                        if (overlappingProjects != null) {
                            boolean skip = false;
                            for (Project p : overlappingProjects) {
                                if (p.contains(mutable)) { skip = true; break; }
                            }
                            if (skip) {
                                currentIndex++; ops++;
                                if ((ops & 0xFF) == 0 && (System.nanoTime() - startTime) > maxTimeNs) return false;
                                continue;
                            }
                        }
                                    
                        NbtCompound beData = blocks != null ? blockNbt.getCompoundOrEmpty("BlockEntityData") : blockNbt.getCompoundOrEmpty("Data");
                        BlockEntity be = BlockEntity.createFromNbt(mutable, world.getBlockState(mutable), beData, world.getRegistryManager());
                        if (be != null) {
                            world.removeBlockEntity(mutable);
                            world.addBlockEntity(be);
                        }
                    }
                    currentIndex++;
                    ops++;
                    
                    if ((ops & 0xFF) == 0) {
                        if (player != null && totalBEs > 0) {
                            long now = System.currentTimeMillis();
                            if (now - lastMessageTime > 500) {
                                float percent = (float) currentIndex / totalBEs * 100f;
                                player.sendMessage(net.minecraft.text.Text.literal(String.format("Restoring BlockEntities: %.1f%%", percent)).formatted(net.minecraft.util.Formatting.GREEN), true);
                                lastMessageTime = now;
                            }
                        }
                        if ((System.nanoTime() - startTime) > maxTimeNs) {
                            return false;
                        }
                    }
                }
                if (currentIndex >= totalBEs) {
                    phase = 2;
                    currentIndex = 0;
                    if (player != null && totalBEs > 0) player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.restoring_blockentities_100_0").formatted(net.minecraft.util.Formatting.GREEN), true);
                }
            }

            if (phase == 2) {
                NbtList entitiesToSpawn = patchEntities != null ? patchEntities : entities;
                while (currentIndex < entitiesToSpawn.size()) {
                    NbtCompound entityNbt = entitiesToSpawn.getCompoundOrEmpty(currentIndex).copy();
                    double absX = min.getX() + entityNbt.getDouble("svcntrl_RelX", 0.0);
                    double absY = min.getY() + entityNbt.getDouble("svcntrl_RelY", 0.0);
                    double absZ = min.getZ() + entityNbt.getDouble("svcntrl_RelZ", 0.0);

                    entityNbt.remove("svcntrl_RelX");
                    entityNbt.remove("svcntrl_RelY");
                    entityNbt.remove("svcntrl_RelZ");

                    if (entityNbt.contains("svcntrl_AttachRelX")) {
                        int attachX = min.getX() + entityNbt.getInt("svcntrl_AttachRelX", 0);
                        int attachY = min.getY() + entityNbt.getInt("svcntrl_AttachRelY", 0);
                        int attachZ = min.getZ() + entityNbt.getInt("svcntrl_AttachRelZ", 0);
                        entityNbt.putInt("TileX", attachX);
                        entityNbt.putInt("TileY", attachY);
                        entityNbt.putInt("TileZ", attachZ);
                        entityNbt.remove("svcntrl_AttachRelX");
                        entityNbt.remove("svcntrl_AttachRelY");
                        entityNbt.remove("svcntrl_AttachRelZ");
                    }

                    NbtList posList = new NbtList();
                    posList.add(net.minecraft.nbt.NbtDouble.of(absX));
                    posList.add(net.minecraft.nbt.NbtDouble.of(absY));
                    posList.add(net.minecraft.nbt.NbtDouble.of(absZ));
                    entityNbt.put("Pos", posList);

                    if (overlappingProjects != null) {
                        BlockPos ePos = new BlockPos(
                                (int) Math.floor(absX),
                                (int) Math.floor(absY),
                                (int) Math.floor(absZ)
                        );
                        boolean skip = false;
                        for (Project p : overlappingProjects) {
                            if (p.contains(ePos)) { skip = true; break; }
                        }
                        if (skip) {
                            currentIndex++; ops++;
                            if ((ops & 0xFF) == 0 && (System.nanoTime() - startTime) > maxTimeNs) return false;
                            continue;
                        }
                    }

                    EntityType.loadEntityWithPassengers(entityNbt, world, SpawnReason.STRUCTURE, (entity) -> {
                        entity.setUuid(java.util.UUID.randomUUID());
                        entity.setPosition(absX, absY, absZ);
                        world.spawnEntityAndPassengers(entity);
                        return entity;
                    });
                    
                    currentIndex++;
                    ops++;
                    
                    if ((ops & 0xFF) == 0) {
                        if (player != null && entitiesToSpawn.size() > 0) {
                            long now = System.currentTimeMillis();
                            if (now - lastMessageTime > 500) {
                                float percent = (float) currentIndex / entitiesToSpawn.size() * 100f;
                                player.sendMessage(net.minecraft.text.Text.literal(String.format("Restoring Entities: %.1f%%", percent)).formatted(net.minecraft.util.Formatting.GREEN), true);
                                lastMessageTime = now;
                            }
                        }
                        if ((System.nanoTime() - startTime) > maxTimeNs) {
                            return false;
                        }
                    }
                }
                
                if (currentIndex >= entitiesToSpawn.size()) {
                    SvcntrlMod.LOGGER.info("[svcntrl] Restore task for project '{}' completed.", project.getName());
                    if (player != null) player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.restore_completed").formatted(net.minecraft.util.Formatting.GOLD), true);
                    ProjectManager.getInstance().setProjectLocked(project, false);
                    return true;
                }
            }

            return false;
        }

        @Override
        public void onCancel(Throwable t) {
            ProjectManager.getInstance().setProjectLocked(project, false);
            if (player != null) {
                player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.restore_failed_cancelled").formatted(net.minecraft.util.Formatting.RED), false);
            }
        }
    }

    /**
     * Reads a snapshot NBT from disk (for preview purposes, without modifying the world).
     */
    public static NbtCompound readSnapshot(Project project, String branchName, String category, int snapshotId) {
        Path filePath = ProjectManager.getInstance().getSnapshotPath(project, branchName, category, snapshotId);
        if (!Files.exists(filePath)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(filePath, NbtSizeTracker.ofUnlimitedBytes());
        } catch (IOException e) {
            SvcntrlMod.LOGGER.error("[svcntrl] Failed to read snapshot: {}", filePath, e);
            return null;
        }
    }

    // --- Helpers ---

    /**
     * Gets the string value of a block state property for serialization.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T extends Comparable<T>> String getPropertyValueString(BlockState state, net.minecraft.state.property.Property<T> property) {
        return property.name(state.get(property));
    }

}
