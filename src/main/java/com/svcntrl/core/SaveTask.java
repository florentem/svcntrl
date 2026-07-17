package com.svcntrl.core;

import com.svcntrl.SvcntrlMod;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SaveTask implements TaskScheduler.TickTask {
    private final ServerWorld world;
    private final Project project;
    private final String branchName;
    private final String category;
    private final int snapshotId;
    private final Runnable onSuccess;
    private final java.util.function.Consumer<String> onError;
    private final net.minecraft.server.network.ServerPlayerEntity player;

    private final BlockPos min;
    private final BlockPos max;
    private final int width, height, length;
    private final NbtCompound root;
    
    // Memory-efficient storage
    private final int[] blockData;
    private final Map<BlockState, Integer> statePaletteMap = new HashMap<>();
    private final List<NbtCompound> paletteList = new ArrayList<>();
    private final NbtList blockEntitiesList = new NbtList();

    private int cx, cy, cz;
    private boolean finishedBlocks = false;
    private int processed = 0;
    private long lastMessageTime = 0;

    public SaveTask(net.minecraft.server.network.ServerPlayerEntity player, ServerWorld world, Project project, String branchName, String category, int snapshotId, Runnable onSuccess, java.util.function.Consumer<String> onError) {
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
        root.putInt("Version", 2); // Version 2 uses Palette + IntArray
        root.putInt("MinX", min.getX());
        root.putInt("MinY", min.getY());
        root.putInt("MinZ", min.getZ());
        root.putInt("MaxX", max.getX());
        root.putInt("MaxY", max.getY());
        root.putInt("MaxZ", max.getZ());
    }

    private int getPaletteIndex(BlockState state) {
        Integer existing = statePaletteMap.get(state);
        if (existing != null) {
            return existing;
        }

        Identifier blockId = Registries.BLOCK.getId(state.getBlock());
        String idStr = blockId.toString();
        
        NbtCompound propsNbt = new NbtCompound();
        state.getProperties().forEach(property -> {
            propsNbt.putString(property.getName(), AreaSerializer.getPropertyValueString(state, property));
        });
        
        int newIndex = paletteList.size();
        statePaletteMap.put(state, newIndex);
        
        NbtCompound entry = new NbtCompound();
        entry.putString("BlockId", idStr);
        if (!propsNbt.isEmpty()) {
            entry.put("Properties", propsNbt);
        }
        paletteList.add(entry);
        
        return newIndex;
    }

    @Override
    public boolean tick(long maxTimeNs) {
        if (finishedBlocks) return true;

        long startTime = System.nanoTime();

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        net.minecraft.world.chunk.WorldChunk cachedChunk = null;
        int cachedChunkX = Integer.MIN_VALUE;
        int cachedChunkZ = Integer.MIN_VALUE;

        while (cy <= max.getY()) {
            while (cz <= max.getZ()) {
                while (cx <= max.getX()) {
                    mutable.set(cx, cy, cz);
                    
                    int currentChunkX = cx >> 4;
                    int currentChunkZ = cz >> 4;
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

                    BlockState state = cachedChunk.getBlockState(mutable);
                    
                    int rx = cx - min.getX();
                    int ry = cy - min.getY();
                    int rz = cz - min.getZ();
                    
                    int index = rz * (width * height) + ry * width + rx;
                    blockData[index] = getPaletteIndex(state);

                    BlockEntity blockEntity = cachedChunk.getBlockEntity(mutable);
                    if (blockEntity != null) {
                        NbtCompound blockEntityNbt = blockEntity.createNbtWithIdentifyingData(world.getRegistryManager());
                        blockEntityNbt.remove("x");
                        blockEntityNbt.remove("y");
                        blockEntityNbt.remove("z");
                        
                        NbtCompound entry = new NbtCompound();
                        entry.putInt("X", rx);
                        entry.putInt("Y", ry);
                        entry.putInt("Z", rz);
                        entry.put("Data", blockEntityNbt);
                        blockEntitiesList.add(entry);
                    }

                    processed++;
                    cx++;

                    if ((processed & 0xFF) == 0 && (System.nanoTime() - startTime) > maxTimeNs) {
                        if (player != null) {
                            long now = System.currentTimeMillis();
                            if (now - lastMessageTime > 500) {
                                float percent = (float) processed / (width * height * length) * 100f;
                                player.sendMessage(net.minecraft.text.Text.literal(String.format("Saving Blocks: %.1f%%", percent)).formatted(net.minecraft.util.Formatting.GREEN), true);
                                lastMessageTime = now;
                            }
                        }
                        return false;
                    }
                }
                cx = min.getX();
                cz++;
            }
            cz = min.getZ();
            cy++;
        }

        finishedBlocks = true;
        if (player != null) {
            player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.saving_100_0").formatted(net.minecraft.util.Formatting.GREEN), true);
        }
        finishSave();
        return true;
    }

    @Override
    public void onCancel(Throwable t) {
        ProjectManager.getInstance().setProjectLocked(project, false);
        if (player != null) {
            player.sendMessage(net.minecraft.text.Text.translatable("svcntrl.msg.save_failed_cancelled").formatted(net.minecraft.util.Formatting.RED), false);
        }
        if (onError != null) {
            onError.accept(t.getMessage());
        }
    }

    private void finishSave() {
        // Serialize Palette
        NbtList pList = new NbtList();
        for (NbtCompound comp : paletteList) {
            pList.add(comp);
        }
        root.put("Palette", pList);
        
        // Serialize BlockData
        root.putIntArray("BlockData", blockData);
        root.put("BlockEntities", blockEntitiesList);

        NbtList entityList = new NbtList();
        Box areaBounds = new Box(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1);

        List<Entity> entities = world.getOtherEntities(null, areaBounds, entity -> !(entity instanceof PlayerEntity));
        for (Entity entity : entities) {
            if (entity.hasVehicle()) continue; // Let the vehicle save its passengers
            
            NbtWriteView writeView = NbtWriteView.create(ErrorReporter.EMPTY);
            if (entity.saveData(writeView)) {
                NbtCompound entityNbt = writeView.getNbt();
                
                if (!entityNbt.contains("id")) {
                    Identifier entityId = Registries.ENTITY_TYPE.getId(entity.getType());
                    if (entityId != null) {
                        entityNbt.putString("id", entityId.toString());
                    }
                }

                entityNbt.putDouble("svcntrl_RelX", entity.getX() - min.getX());
                entityNbt.putDouble("svcntrl_RelY", entity.getY() - min.getY());
                entityNbt.putDouble("svcntrl_RelZ", entity.getZ() - min.getZ());

                if (entity instanceof AbstractDecorationEntity decoration) {
                    BlockPos attachPos = decoration.getBlockPos();
                    entityNbt.putInt("svcntrl_AttachRelX", attachPos.getX() - min.getX());
                    entityNbt.putInt("svcntrl_AttachRelY", attachPos.getY() - min.getY());
                    entityNbt.putInt("svcntrl_AttachRelZ", attachPos.getZ() - min.getZ());
                }

                entityList.add(entityNbt);
            }
        }
        root.put("Entities", entityList);

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Path filePath = ProjectManager.getInstance().getSnapshotPath(project, branchName, category, snapshotId);
                Files.createDirectories(filePath.getParent());
                Path tempPath = filePath.getParent().resolve(filePath.getFileName() + ".tmp");
                NbtIo.writeCompressed(root, tempPath);
                Files.move(tempPath, filePath, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                SvcntrlMod.LOGGER.info("[svcntrl] Saved V2 snapshot {} ({}) for project '{}' — {} blocks ({} unique), {} entities",
                        snapshotId, category, project.getName(), blockData.length, paletteList.size(), entityList.size());
                if (onSuccess != null) {
                    world.getServer().execute(() -> {
                        try {
                            onSuccess.run();
                        } finally {
                            ProjectManager.getInstance().setProjectLocked(project, false);
                        }
                    });
                } else {
                    world.getServer().execute(() -> ProjectManager.getInstance().setProjectLocked(project, false));
                }
            } catch (Throwable t) {
                SvcntrlMod.LOGGER.error("[svcntrl] Fatal error in async save", t);
                if (onError != null) {
                    world.getServer().execute(() -> {
                        try {
                            onError.accept(t.getMessage());
                        } finally {
                            ProjectManager.getInstance().setProjectLocked(project, false);
                        }
                    });
                } else {
                    world.getServer().execute(() -> ProjectManager.getInstance().setProjectLocked(project, false));
                }
            }
        });
    }
}
