package com.svcntrl.core;


import com.svcntrl.util.Lang;
import com.svcntrl.config.SvcntrlConfig;
import com.svcntrl.data.Project;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public class PreviewManager {
    private static final PreviewManager INSTANCE = new PreviewManager();

    // Map of Player UUID -> Map of ChunkPos -> Map of BlockPos to Preview BlockState
    private final Map<UUID, Map<net.minecraft.util.math.ChunkPos, Map<BlockPos, BlockState>>> activePreviews = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Set<Integer>> activePreviewEntities = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> playerHiddenEntities = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger PREVIEW_ENTITY_IDS = new java.util.concurrent.atomic.AtomicInteger(-1000000);
    private final Map<UUID, String> previewingProjects = new ConcurrentHashMap<>();
    private final Map<UUID, net.minecraft.util.math.Box> previewBoundingBoxes = new ConcurrentHashMap<>();
    private final Map<UUID, String> previewDimensions = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> pendingPreviews = ConcurrentHashMap.newKeySet();
    

    private PreviewManager() {}

    public static PreviewManager getInstance() {
        return INSTANCE;
    }

    public boolean hasPreview(UUID playerUuid) {
        return activePreviews.containsKey(playerUuid) || pendingPreviews.contains(playerUuid);
    }

    public boolean hasAnyPreviews() {
        return !activePreviewEntities.isEmpty();
    }

    public boolean isPreviewingProject(Project project) {
        String projectName = project.getName().toLowerCase(java.util.Locale.ROOT);
        return previewingProjects.values().stream().anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT).equals(projectName));
    }

    public java.util.Collection<String> getPreviewingProjects() {
        return previewingProjects.values();
    }

    public void stopPreviewForProject(net.minecraft.server.MinecraftServer server, String projectName) {
        String lowerName = projectName.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<UUID, String> entry : previewingProjects.entrySet()) {
            if (entry.getValue().toLowerCase(java.util.Locale.ROOT).equals(lowerName)) {
                net.minecraft.server.network.ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
                if (p != null) {
                    stopPreview(p, false);
                } else {
                    UUID uuid = entry.getKey();
                    pendingPreviews.remove(uuid);
                    activePreviews.remove(uuid);
                    activePreviewEntities.remove(uuid);
                    previewBoundingBoxes.remove(uuid);
                    previewingProjects.remove(uuid);
                }
            }
        }
    }

    public boolean isEntityHidden(ServerPlayerEntity player, Entity entity) {
        if (!hasPreview(player.getUuid())) return false;
        if (entity instanceof net.minecraft.server.network.ServerPlayerEntity) return false;
        
        java.util.Set<Integer> fakeEntities = activePreviewEntities.get(player.getUuid());
        if (fakeEntities != null && fakeEntities.contains(entity.getId())) return false;
        
        net.minecraft.util.math.Box box = previewBoundingBoxes.get(player.getUuid());
        if (box != null) {
            return box.intersects(entity.getBoundingBox());
        }
        return false;
    }

    public BlockState getPreviewBlock(UUID playerUuid, BlockPos pos) {
        Map<net.minecraft.util.math.ChunkPos, Map<BlockPos, BlockState>> chunks = activePreviews.get(playerUuid);
        if (chunks != null) {
            Map<BlockPos, BlockState> blocks = chunks.get(new net.minecraft.util.math.ChunkPos(pos));
            if (blocks != null) {
                return blocks.get(pos);
            }
        }
        return null;
    }

    public void resendBlock(ServerPlayerEntity player, BlockPos pos) {
        BlockState state = getPreviewBlock(player.getUuid(), pos);
        if (state != null) {
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, state));
        }
    }
    
    public void onChunkSent(ServerPlayerEntity player, int chunkX, int chunkZ) {
        Map<net.minecraft.util.math.ChunkPos, Map<BlockPos, BlockState>> chunks = activePreviews.get(player.getUuid());
        if (chunks != null) {
            Map<BlockPos, BlockState> blocks = chunks.get(new net.minecraft.util.math.ChunkPos(chunkX, chunkZ));
            if (blocks != null && !blocks.isEmpty()) {
                // Send updates for this chunk
                for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                    player.networkHandler.sendPacket(new BlockUpdateS2CPacket(entry.getKey(), entry.getValue()));
                }
            }
        }
    }

    public void startPreview(net.minecraft.server.network.ServerPlayerEntity player, Project project, String branchName, String category, int snapshotId) {
        if (hasPreview(player.getUuid())) {
            stopPreview(player, false);
        }

        pendingPreviews.add(player.getUuid());
        player.sendMessage(Lang.translatable("svcntrl.msg.loading_snapshot_for_preview").formatted(net.minecraft.util.Formatting.YELLOW));
        
        com.svcntrl.SvcntrlMod.supplyAsync(() -> {
            return AreaSerializer.readSnapshot(project, branchName, category, snapshotId);
        }).thenAccept(root -> {
            player.getEntityWorld().getServer().execute(() -> {
                pendingPreviews.remove(player.getUuid());
                if (root == null) {
                    player.sendMessage(Lang.translatable("svcntrl.msg.failed_to_load_snapshot_for_pr").formatted(net.minecraft.util.Formatting.RED));
                    return;
                }

                activePreviews.put(player.getUuid(), new ConcurrentHashMap<>());
                activePreviewEntities.put(player.getUuid(), ConcurrentHashMap.newKeySet());
                previewingProjects.put(player.getUuid(), project.getName());
                
                net.minecraft.util.math.Box bounds = new net.minecraft.util.math.Box(
                    project.getMin().getX(), project.getMin().getY(), project.getMin().getZ(),
                    project.getMax().getX() + 1, project.getMax().getY() + 1, project.getMax().getZ() + 1
                );
                previewBoundingBoxes.put(player.getUuid(), bounds);
                previewDimensions.put(player.getUuid(), player.getEntityWorld().getRegistryKey().getValue().toString());
                
                List<Entity> realEntities = ((net.minecraft.server.world.ServerWorld)player.getEntityWorld()).getOtherEntities(null, bounds);
                if (!realEntities.isEmpty()) {
                    int[] ids = realEntities.stream()
                        .filter(e -> !(e instanceof net.minecraft.server.network.ServerPlayerEntity))
                        .mapToInt(Entity::getId)
                        .toArray();
                    if (ids.length > 0) {
                        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket(ids));
                    }
                }

                NbtList entities = root.getListOrEmpty("Entities");
                TaskScheduler.getInstance().schedule(new StartPreviewTask(player, project.getMin(), root, entities));
                
                player.sendMessage(Lang.translatable("svcntrl.msg.previewing", branchName, category, snapshotId).formatted(net.minecraft.util.Formatting.AQUA));
            });
        });
    }
    
    public void tick(net.minecraft.server.MinecraftServer server) {
        if (!hasAnyPreviews()) return;
        
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String expectedDim = previewDimensions.get(player.getUuid());
            if (expectedDim != null && !expectedDim.equals(player.getEntityWorld().getRegistryKey().getValue().toString())) {
                stopPreview(player);
                continue;
            }
            
            net.minecraft.util.math.Box bounds = previewBoundingBoxes.get(player.getUuid());
            if (bounds != null) {
                List<Entity> entities = ((net.minecraft.server.world.ServerWorld)player.getEntityWorld()).getOtherEntities(null, bounds);
                if (!entities.isEmpty()) {
                    int[] idsToHide = entities.stream()
                        .filter(e -> !(e instanceof net.minecraft.server.network.ServerPlayerEntity) && !isEntityHidden(player, e))
                        .mapToInt(Entity::getId)
                        .toArray();
                    
                    if (idsToHide.length > 0) {
                        java.util.Set<Integer> hiddenSet = activePreviewEntities.get(player.getUuid());
                        if (hiddenSet != null) {
                            for (int id : idsToHide) hiddenSet.add(id);
                        }
                        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket(idsToHide));
                    }
                }
            }
        }
    }

    public void stopPreview(ServerPlayerEntity player) {
        stopPreview(player, true);
    }

    public void stopPreview(ServerPlayerEntity player, boolean showProgress) {
        UUID uuid = player.getUuid();
        pendingPreviews.remove(uuid);
        Map<net.minecraft.util.math.ChunkPos, Map<BlockPos, BlockState>> chunks = activePreviews.remove(uuid);
        previewingProjects.remove(uuid);
        previewDimensions.remove(uuid);
        net.minecraft.util.math.Box bounds = previewBoundingBoxes.remove(uuid);
        
        if (chunks != null && !chunks.isEmpty()) {
            List<BlockPos> allBlocks = new ArrayList<>();
            for (Map<BlockPos, BlockState> map : chunks.values()) {
                allBlocks.addAll(map.keySet());
            }
            TaskScheduler.getInstance().schedule(new StopPreviewTask(player, allBlocks, showProgress));
        }

        java.util.Set<Integer> entities = activePreviewEntities.remove(uuid);
        if (entities != null && !entities.isEmpty()) {
            int[] entityIds = entities.stream().mapToInt(i -> i).toArray();
            player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(entityIds));
        }
        
        // Re-track real entities that were hidden
        if (bounds != null) {
            List<Entity> realEntities = ((net.minecraft.server.world.ServerWorld)player.getEntityWorld()).getOtherEntities(null, bounds);
            for (Entity entity : realEntities) {
                if (!(entity instanceof net.minecraft.server.network.ServerPlayerEntity)) {
                    player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket(
                        entity.getId(), entity.getUuid(),
                        entity.getX(), entity.getY(), entity.getZ(),
                        entity.getPitch(), entity.getYaw(),
                        entity.getType(), 0, entity.getVelocity(), entity.getHeadYaw()
                    ));
                    if (entity.getDataTracker().getChangedEntries() != null) {
                        player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket(entity.getId(), entity.getDataTracker().getChangedEntries()));
                    }
                }
            }
        }
    }

    private static class StartPreviewTask implements TaskScheduler.TickTask {
        private final ServerPlayerEntity player;
        private final BlockPos min;
        
        // V1 fields
        private final NbtList blocks;
        
        // V2 fields
        private final int[] blockData;
        private final BlockState[] palette;
        private final int width, height, length;
        
        private final NbtList entities;
        
        private int currentIndex = 0;
        private int phase = 0; // 0 = blocks, 1 = entities
        private final BlockPos.Mutable mutable = new BlockPos.Mutable();
        private long lastMessageTime = 0;
        
        public StartPreviewTask(ServerPlayerEntity player, BlockPos min, NbtCompound root, NbtList entities) {
            this.player = player;
            this.min = min;
            this.entities = entities;
            
            if (root.contains("Version") && root.getInt("Version", 1) == 2) {
                this.blocks = null;
                this.blockData = root.getIntArray("BlockData").orElse(new int[0]);
                
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
                this.width = this.height = this.length = 0;
            }
        }

        @Override
        public boolean tick(long maxTimeNs) {
            if (player.isDisconnected() || !PreviewManager.getInstance().hasPreview(player.getUuid())) {
                return true; // Cancel if disconnected or preview stopped
            }
            
            int ops = 0;
            int packetsSent = 0;
            long startTime = System.nanoTime();
            ServerWorld world = player.getEntityWorld();

            if (phase == 0) {
                Map<net.minecraft.util.math.ChunkPos, Map<BlockPos, BlockState>> previewChunks = PreviewManager.getInstance().activePreviews.get(player.getUuid());
                if (previewChunks == null) return true;

                int totalBlocks = blocks != null ? blocks.size() : (blockData != null ? blockData.length : 0);
                net.minecraft.world.chunk.WorldChunk cachedChunk = null;
                int cachedChunkX = Integer.MIN_VALUE;
                int cachedChunkZ = Integer.MIN_VALUE;

                while (currentIndex < totalBlocks) {
                    BlockState state;
                    if (blocks != null) {
                        // V1
                        NbtCompound blockNbt = blocks.getCompoundOrEmpty(currentIndex);
                        mutable.set(min.getX() + blockNbt.getInt("X", 0), 
                                    min.getY() + blockNbt.getInt("Y", 0), 
                                    min.getZ() + blockNbt.getInt("Z", 0));

                        String blockIdStr = blockNbt.getString("BlockId", "minecraft:air");
                        Block block = Registries.BLOCK.get(Identifier.of(blockIdStr));
                        state = block.getDefaultState();
                        if (blockNbt.contains("Properties")) {
                            state = com.svcntrl.util.BlockUtils.applyProperties(state, blockNbt.getCompoundOrEmpty("Properties"));
                        }
                    } else if (blockData != null) {
                        // V2
                        int rz = currentIndex / (width * height);
                        int rem = currentIndex % (width * height);
                        int ry = rem / width;
                        int rx = rem % width;
                        
                        mutable.set(min.getX() + rx, min.getY() + ry, min.getZ() + rz);
                        int pIndex = blockData[currentIndex];
                        state = (pIndex >= 0 && pIndex < palette.length) ? palette[pIndex] : net.minecraft.block.Blocks.AIR.getDefaultState();
                    } else {
                        state = net.minecraft.block.Blocks.AIR.getDefaultState();
                    }

                    int currentChunkX = mutable.getX() >> 4;
                    int currentChunkZ = mutable.getZ() >> 4;
                    if (cachedChunk == null || cachedChunkX != currentChunkX || cachedChunkZ != currentChunkZ) {
                        cachedChunk = world.getWorldChunk(mutable);
                        cachedChunkX = currentChunkX;
                        cachedChunkZ = currentChunkZ;
                        if ((System.nanoTime() - startTime) > maxTimeNs) return false;
                    }

                    BlockState actualState = cachedChunk.getBlockState(mutable);
                    if (!state.equals(actualState)) {
                        BlockPos immutablePos = mutable.toImmutable();
                        player.networkHandler.sendPacket(new BlockUpdateS2CPacket(immutablePos, state));
                        packetsSent++;
                        
                        net.minecraft.util.math.ChunkPos cPos = new net.minecraft.util.math.ChunkPos(immutablePos);
                        Map<BlockPos, BlockState> chunkMap = previewChunks.computeIfAbsent(cPos, k -> new ConcurrentHashMap<>());
                        chunkMap.put(immutablePos, state);
                    }
                    currentIndex++;
                    ops++;
                    
                    if ((ops & 0x3F) == 0) {
                        long now = System.currentTimeMillis();
                        if (now - lastMessageTime > 500) {
                            float percent = (float) currentIndex / totalBlocks * 100f;
                            player.sendMessage(Lang.translatable("svcntrl.msg.loading_preview_progress", String.format(java.util.Locale.US, "%.1f", percent)).formatted(net.minecraft.util.Formatting.GREEN), true);
                            lastMessageTime = now;
                        }
                        if ((System.nanoTime() - startTime) > maxTimeNs || packetsSent > 2048) {
                            return false;
                        }
                    }
                }
                if (currentIndex >= totalBlocks) {
                    phase = 1;
                    currentIndex = 0;
                    player.sendMessage(Lang.translatable("svcntrl.msg.loading_preview_100_0").formatted(net.minecraft.util.Formatting.GREEN), true);
                }
                return false;
            }

            if (phase == 1) {
                java.util.Set<Integer> spawnedEntityIds = PreviewManager.getInstance().activePreviewEntities.get(player.getUuid());
                if (spawnedEntityIds == null) return true;

                while (currentIndex < entities.size()) {
                    NbtCompound entityNbt = entities.getCompoundOrEmpty(currentIndex).copy();
                    double absX = min.getX() + entityNbt.getDouble("svcntrl_RelX", 0.0);
                    double absY = min.getY() + entityNbt.getDouble("svcntrl_RelY", 0.0);
                    double absZ = min.getZ() + entityNbt.getDouble("svcntrl_RelZ", 0.0);

                    String idStr = entityNbt.getString("id").orElse("");
                    if (idStr != null && !idStr.isEmpty()) {
                        EntityType<?> type = net.minecraft.registry.Registries.ENTITY_TYPE.get(net.minecraft.util.Identifier.tryParse(idStr));
                        if (type != null) {
                            
                            int fakeId = PREVIEW_ENTITY_IDS.decrementAndGet();
                            UUID fakeUuid = UUID.randomUUID();
                            
                            float yaw = 0f;
                            float pitch = 0f;
                            if (entityNbt.contains("Rotation")) {
                                net.minecraft.nbt.NbtList rotation = entityNbt.getList("Rotation").orElse(new net.minecraft.nbt.NbtList());
                                yaw = rotation.getFloat(0).orElse(0f);
                                pitch = rotation.getFloat(1).orElse(0f);
                            }
                            
                            net.minecraft.util.math.Vec3d vel = net.minecraft.util.math.Vec3d.ZERO;
                            if (entityNbt.contains("Motion")) {
                                net.minecraft.nbt.NbtList motion = entityNbt.getList("Motion").orElse(new net.minecraft.nbt.NbtList());
                                vel = new net.minecraft.util.math.Vec3d(motion.getDouble(0).orElse(0.0), motion.getDouble(1).orElse(0.0), motion.getDouble(2).orElse(0.0));
                            }
                            
                            player.networkHandler.sendPacket(new EntitySpawnS2CPacket(
                                fakeId, fakeUuid,
                                absX, absY, absZ,
                                pitch, yaw,
                                type, 0, vel, yaw
                            ));
                            spawnedEntityIds.add(fakeId);
                        }
                    }
                    currentIndex++;
                    ops++;
                    
                    if ((ops & 0x3F) == 0 && (System.nanoTime() - startTime) > maxTimeNs) {
                        return false;
                    }
                }
                if (currentIndex >= entities.size()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class StopPreviewTask implements TaskScheduler.TickTask {
        private final ServerPlayerEntity player;
        private final List<BlockPos> positions;
        private final boolean showProgress;
        private int currentIndex = 0;
        private long lastMessageTime = 0;

        public StopPreviewTask(ServerPlayerEntity player, List<BlockPos> positions, boolean showProgress) {
            this.player = player;
            this.positions = positions;
            this.showProgress = showProgress;
        }

        @Override
        public boolean tick(long maxTimeNs) {
            if (player.isDisconnected()) return true;

            int ops = 0;
            int packetsSent = 0;
            long startTime = System.nanoTime();
            ServerWorld world = player.getEntityWorld();

            while (currentIndex < positions.size()) {
                BlockPos pos = positions.get(currentIndex);
                BlockState actualState = world.getBlockState(pos);
                player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, actualState));
                packetsSent++;
                currentIndex++;
                ops++;
                
                if ((ops & 0x3F) == 0) {
                    if (showProgress) {
                        long now = System.currentTimeMillis();
                        if (now - lastMessageTime > 500) {
                            float percent = (float) currentIndex / positions.size() * 100f;
                            player.sendMessage(Lang.translatable("svcntrl.msg.clearing_preview_progress", String.format(java.util.Locale.US, "%.1f", percent)).formatted(net.minecraft.util.Formatting.GREEN), true);
                            lastMessageTime = now;
                        }
                    }
                    if ((System.nanoTime() - startTime) > maxTimeNs || packetsSent > 2048) {
                        return false;
                    }
                }
            }
            if (showProgress) {
                player.sendMessage(Lang.translatable("svcntrl.msg.clearing_preview_100_0").formatted(net.minecraft.util.Formatting.GREEN), true);
            }
            return true;
        }
    }

}
