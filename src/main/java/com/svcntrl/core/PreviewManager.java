package com.svcntrl.core;

import com.svcntrl.config.SvcntrlConfig;
import com.svcntrl.data.Project;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Set;

public class PreviewManager {
    private static final PreviewManager INSTANCE = new PreviewManager();

    // Map of Player UUID -> Map of ChunkPos -> Map of BlockPos to Preview BlockState
    private final Map<UUID, Map<net.minecraft.world.level.ChunkPos, Map<BlockPos, BlockState>>> activePreviews = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.Set<Integer>> activePreviewEntities = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> playerHiddenEntities = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger PREVIEW_ENTITY_IDS = new java.util.concurrent.atomic.AtomicInteger(-1000000);
    private final Map<UUID, String> previewingProjects = new ConcurrentHashMap<>();
    private final Map<UUID, net.minecraft.world.phys.AABB> previewBoundingBoxes = new ConcurrentHashMap<>();
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
                net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
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

    public boolean isEntityHidden(ServerPlayer player, Entity entity) {
        if (!hasPreview(player.getUUID())) return false;
        if (entity instanceof net.minecraft.server.level.ServerPlayer) return false;
        
        java.util.Set<Integer> fakeEntities = activePreviewEntities.get(player.getUUID());
        if (fakeEntities != null && fakeEntities.contains(entity.getId())) return false;
        
        net.minecraft.world.phys.AABB box = previewBoundingBoxes.get(player.getUUID());
        if (box != null) {
            return box.intersects(entity.getBoundingBox());
        }
        return false;
    }

    public BlockState getPreviewBlock(UUID playerUuid, BlockPos pos) {
        Map<net.minecraft.world.level.ChunkPos, Map<BlockPos, BlockState>> chunks = activePreviews.get(playerUuid);
        if (chunks != null) {
            Map<BlockPos, BlockState> blocks = chunks.get(new net.minecraft.world.level.ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
            if (blocks != null) {
                return blocks.get(pos);
            }
        }
        return null;
    }

    public void resendBlock(ServerPlayer player, BlockPos pos) {
        BlockState state = getPreviewBlock(player.getUUID(), pos);
        if (state != null) {
            player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
        }
    }
    
    public void onChunkSent(ServerPlayer player, int chunkX, int chunkZ) {
        Map<net.minecraft.world.level.ChunkPos, Map<BlockPos, BlockState>> chunks = activePreviews.get(player.getUUID());
        if (chunks != null) {
            Map<BlockPos, BlockState> blocks = chunks.get(new net.minecraft.world.level.ChunkPos(chunkX, chunkZ));
            if (blocks != null && !blocks.isEmpty()) {
                // Send updates for this chunk
                for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
                    player.connection.send(new ClientboundBlockUpdatePacket(entry.getKey(), entry.getValue()));
                }
            }
        }
    }

    public void startPreview(net.minecraft.server.level.ServerPlayer player, Project project, String branchName, String category, int snapshotId) {
        if (hasPreview(player.getUUID())) {
            stopPreview(player, false);
        }

        pendingPreviews.add(player.getUUID());
        player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.loading_snapshot_for_preview").withStyle(net.minecraft.ChatFormatting.YELLOW));
        
        com.svcntrl.SvcntrlMod.supplyAsync(() -> {
            return AreaSerializer.readSnapshot(project, branchName, category, snapshotId);
        }).thenAccept(root -> {
            player.level().getServer().execute(() -> {
                pendingPreviews.remove(player.getUUID());
                if (root == null) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.failed_to_load_snapshot_for_pr").withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }

                activePreviews.put(player.getUUID(), new ConcurrentHashMap<>());
                activePreviewEntities.put(player.getUUID(), ConcurrentHashMap.newKeySet());
                previewingProjects.put(player.getUUID(), project.getName());
                
                net.minecraft.world.phys.AABB bounds = new net.minecraft.world.phys.AABB(
                    project.getMin().getX(), project.getMin().getY(), project.getMin().getZ(),
                    project.getMax().getX() + 1, project.getMax().getY() + 1, project.getMax().getZ() + 1
                );
                previewBoundingBoxes.put(player.getUUID(), bounds);
                previewDimensions.put(player.getUUID(), player.level().dimension().identifier().toString());
                
                List<Entity> realEntities = ((net.minecraft.server.level.ServerLevel)player.level()).getEntities(null, bounds);
                if (!realEntities.isEmpty()) {
                    int[] ids = realEntities.stream()
                        .filter(e -> !(e instanceof net.minecraft.server.level.ServerPlayer))
                        .mapToInt(Entity::getId)
                        .toArray();
                    if (ids.length > 0) {
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(ids));
                    }
                }

                ListTag entities = root.getListOrEmpty("Entities");
                TaskScheduler.getInstance().schedule(new StartPreviewTask(player, project.getMin(), root, entities));
                
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.previewing", branchName, category, snapshotId).withStyle(net.minecraft.ChatFormatting.AQUA));
            });
        });
    }
    
    public void tick(net.minecraft.server.MinecraftServer server) {
        if (!hasAnyPreviews()) return;
        
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String expectedDim = previewDimensions.get(player.getUUID());
            if (expectedDim != null && !expectedDim.equals(player.level().dimension().identifier().toString())) {
                stopPreview(player);
                continue;
            }
            
            net.minecraft.world.phys.AABB bounds = previewBoundingBoxes.get(player.getUUID());
            if (bounds != null) {
                List<Entity> entities = ((net.minecraft.server.level.ServerLevel)player.level()).getEntities(null, bounds);
                if (!entities.isEmpty()) {
                    int[] idsToHide = entities.stream()
                        .filter(e -> !(e instanceof net.minecraft.server.level.ServerPlayer) && !isEntityHidden(player, e))
                        .mapToInt(Entity::getId)
                        .toArray();
                    
                    if (idsToHide.length > 0) {
                        java.util.Set<Integer> hiddenSet = activePreviewEntities.get(player.getUUID());
                        if (hiddenSet != null) {
                            for (int id : idsToHide) hiddenSet.add(id);
                        }
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(idsToHide));
                    }
                }
            }
        }
    }

    public void stopPreview(ServerPlayer player) {
        stopPreview(player, true);
    }

    public void stopPreview(ServerPlayer player, boolean showProgress) {
        UUID uuid = player.getUUID();
        pendingPreviews.remove(uuid);
        Map<net.minecraft.world.level.ChunkPos, Map<BlockPos, BlockState>> chunks = activePreviews.remove(uuid);
        previewingProjects.remove(uuid);
        previewDimensions.remove(uuid);
        net.minecraft.world.phys.AABB bounds = previewBoundingBoxes.remove(uuid);
        
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
            player.connection.send(new ClientboundRemoveEntitiesPacket(entityIds));
        }
        
        // Re-track real entities that were hidden
        if (bounds != null) {
            List<Entity> realEntities = ((net.minecraft.server.level.ServerLevel)player.level()).getEntities(null, bounds);
            for (Entity entity : realEntities) {
                if (!(entity instanceof net.minecraft.server.level.ServerPlayer)) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(
                        entity.getId(), entity.getUUID(),
                        entity.getX(), entity.getY(), entity.getZ(),
                        entity.getXRot(), entity.getYRot(),
                        entity.getType(), 0, entity.getDeltaMovement(), entity.getYHeadRot()
                    ));
                    if (entity.getEntityData().getNonDefaultValues() != null) {
                        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData().getNonDefaultValues()));
                    }
                }
            }
        }
    }

    private static class StartPreviewTask implements TaskScheduler.TickTask {
        private final ServerPlayer player;
        private final BlockPos min;
        
        // V1 fields
        private final ListTag blocks;
        
        // V2 fields
        private final int[] blockData;
        private final BlockState[] palette;
        private final int width, height, length;
        
        private final ListTag entities;
        
        private int currentIndex = 0;
        private int phase = 0; // 0 = blocks, 1 = entities
        private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        private long lastMessageTime = 0;
        
        public StartPreviewTask(ServerPlayer player, BlockPos min, CompoundTag root, ListTag entities) {
            this.player = player;
            this.min = min;
            this.entities = entities;
            
            if (root.contains("Version") && root.getIntOr("Version", 1) == 2) {
                this.blocks = null;
                this.blockData = root.getIntArray("BlockData").orElse(new int[0]);
                
                this.width = root.getIntOr("MaxX", 0) - root.getIntOr("MinX", 0) + 1;
                this.height = root.getIntOr("MaxY", 0) - root.getIntOr("MinY", 0) + 1;
                this.length = root.getIntOr("MaxZ", 0) - root.getIntOr("MinZ", 0) + 1;
                
                ListTag pList = root.getListOrEmpty("Palette");
                this.palette = new BlockState[pList.size()];
                for (int i = 0; i < pList.size(); i++) {
                    CompoundTag pEntry = pList.getCompoundOrEmpty(i);
                    String blockIdStr = pEntry.getStringOr("BlockId", "minecraft:air");
                    Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockIdStr));
                    BlockState state = block.defaultBlockState();
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
            if (player.hasDisconnected() || !PreviewManager.getInstance().hasPreview(player.getUUID())) {
                return true; // Cancel if disconnected or preview stopped
            }
            
            int ops = 0;
            int packetsSent = 0;
            long startTime = System.nanoTime();
            ServerLevel world = player.level();

            if (phase == 0) {
                Map<net.minecraft.world.level.ChunkPos, Map<BlockPos, BlockState>> previewChunks = PreviewManager.getInstance().activePreviews.get(player.getUUID());
                if (previewChunks == null) return true;

                int totalBlocks = blocks != null ? blocks.size() : (blockData != null ? blockData.length : 0);
                net.minecraft.world.level.chunk.LevelChunk cachedChunk = null;
                int cachedChunkX = Integer.MIN_VALUE;
                int cachedChunkZ = Integer.MIN_VALUE;

                while (currentIndex < totalBlocks) {
                    BlockState state;
                    if (blocks != null) {
                        // V1
                        CompoundTag blockNbt = blocks.getCompoundOrEmpty(currentIndex);
                        mutable.set(min.getX() + blockNbt.getIntOr("X", 0), 
                                    min.getY() + blockNbt.getIntOr("Y", 0), 
                                    min.getZ() + blockNbt.getIntOr("Z", 0));

                        String blockIdStr = blockNbt.getStringOr("BlockId", "minecraft:air");
                        Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(blockIdStr));
                        state = block.defaultBlockState();
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
                        state = (pIndex >= 0 && pIndex < palette.length) ? palette[pIndex] : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    } else {
                        state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                    }

                    int currentChunkX = mutable.getX() >> 4;
                    int currentChunkZ = mutable.getZ() >> 4;
                    if (cachedChunk == null || cachedChunkX != currentChunkX || cachedChunkZ != currentChunkZ) {
                        cachedChunk = world.getChunkAt(mutable);
                        cachedChunkX = currentChunkX;
                        cachedChunkZ = currentChunkZ;
                        if ((System.nanoTime() - startTime) > maxTimeNs) return false;
                    }

                    BlockState actualState = cachedChunk.getBlockState(mutable);
                    if (!state.equals(actualState)) {
                        BlockPos immutablePos = mutable.immutable();
                        player.connection.send(new ClientboundBlockUpdatePacket(immutablePos, state));
                        packetsSent++;
                        
                        net.minecraft.world.level.ChunkPos cPos = new net.minecraft.world.level.ChunkPos(immutablePos.getX() >> 4, immutablePos.getZ() >> 4);
                        Map<BlockPos, BlockState> chunkMap = previewChunks.computeIfAbsent(cPos, k -> new ConcurrentHashMap<>());
                        chunkMap.put(immutablePos, state);
                    }
                    currentIndex++;
                    ops++;
                    
                    if ((ops & 0x3F) == 0) {
                        long now = System.currentTimeMillis();
                        if (now - lastMessageTime > 500) {
                            float percent = (float) currentIndex / totalBlocks * 100f;
                            player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(String.format("Loading Preview: %.1f%%", percent)).withStyle(net.minecraft.ChatFormatting.GREEN));
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
                    player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.loading_preview_100_0").withStyle(net.minecraft.ChatFormatting.GREEN));
                }
                return false;
            }

            if (phase == 1) {
                java.util.Set<Integer> spawnedEntityIds = PreviewManager.getInstance().activePreviewEntities.get(player.getUUID());
                if (spawnedEntityIds == null) return true;

                while (currentIndex < entities.size()) {
                    CompoundTag entityNbt = entities.getCompoundOrEmpty(currentIndex).copy();
                    double absX = min.getX() + entityNbt.getDoubleOr("svcntrl_RelX", 0.0);
                    double absY = min.getY() + entityNbt.getDoubleOr("svcntrl_RelY", 0.0);
                    double absZ = min.getZ() + entityNbt.getDoubleOr("svcntrl_RelZ", 0.0);

                    String idStr = entityNbt.getString("id").orElse("");
                    if (idStr != null && !idStr.isEmpty()) {
                        EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.tryParse(idStr));
                        if (type != null) {
                            
                            int fakeId = PREVIEW_ENTITY_IDS.decrementAndGet();
                            UUID fakeUuid = UUID.randomUUID();
                            
                            float yaw = 0f;
                            float pitch = 0f;
                            if (entityNbt.contains("Rotation")) {
                                net.minecraft.nbt.ListTag rotation = entityNbt.getList("Rotation").orElse(new net.minecraft.nbt.ListTag());
                                yaw = rotation.getFloat(0).orElse(0f);
                                pitch = rotation.getFloat(1).orElse(0f);
                            }
                            
                            net.minecraft.world.phys.Vec3 vel = net.minecraft.world.phys.Vec3.ZERO;
                            if (entityNbt.contains("Motion")) {
                                net.minecraft.nbt.ListTag motion = entityNbt.getList("Motion").orElse(new net.minecraft.nbt.ListTag());
                                vel = new net.minecraft.world.phys.Vec3(motion.getDouble(0).orElse(0.0), motion.getDouble(1).orElse(0.0), motion.getDouble(2).orElse(0.0));
                            }
                            
                            player.connection.send(new ClientboundAddEntityPacket(
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
        private final ServerPlayer player;
        private final List<BlockPos> positions;
        private final boolean showProgress;
        private int currentIndex = 0;
        private long lastMessageTime = 0;

        public StopPreviewTask(ServerPlayer player, List<BlockPos> positions, boolean showProgress) {
            this.player = player;
            this.positions = positions;
            this.showProgress = showProgress;
        }

        @Override
        public boolean tick(long maxTimeNs) {
            if (player.hasDisconnected()) return true;

            int ops = 0;
            int packetsSent = 0;
            long startTime = System.nanoTime();
            ServerLevel world = player.level();

            while (currentIndex < positions.size()) {
                BlockPos pos = positions.get(currentIndex);
                BlockState actualState = world.getBlockState(pos);
                player.connection.send(new ClientboundBlockUpdatePacket(pos, actualState));
                packetsSent++;
                currentIndex++;
                ops++;
                
                if ((ops & 0x3F) == 0) {
                    if (showProgress) {
                        long now = System.currentTimeMillis();
                        if (now - lastMessageTime > 500) {
                            float percent = (float) currentIndex / positions.size() * 100f;
                            player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(String.format("Clearing Preview: %.1f%%", percent)).withStyle(net.minecraft.ChatFormatting.GREEN));
                            lastMessageTime = now;
                        }
                    }
                    if ((System.nanoTime() - startTime) > maxTimeNs || packetsSent > 2048) {
                        return false;
                    }
                }
            }
            if (showProgress) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.clearing_preview_100_0").withStyle(net.minecraft.ChatFormatting.GREEN));
            }
            return true;
        }
    }

}
