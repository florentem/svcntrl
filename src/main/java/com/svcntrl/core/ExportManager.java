package com.svcntrl.core;

import com.svcntrl.SvcntrlMod;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

public class ExportManager {

    private static class PendingUpload {
        Path path;
        long expiryTime;
        PendingUpload(Path path, long expiryTime) { this.path = path; this.expiryTime = expiryTime; }
    }

    private static final Map<java.util.UUID, PendingUpload> pendingUploads = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.net.http.HttpClient HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    public static void tick() {
        long now = System.currentTimeMillis();
        pendingUploads.entrySet().removeIf(entry -> {
            if (now > entry.getValue().expiryTime) {
                try {
                    Files.deleteIfExists(entry.getValue().path);
                    String fName = entry.getValue().path.getFileName().toString();
                    String base = fName.endsWith(".zip") ? fName.substring(0, fName.length() - 4) : fName;
                    Files.deleteIfExists(entry.getValue().path.getParent().resolve(base));
                } catch (Exception ignored) {}
                return true;
            }
            return false;
        });
    }

    public static boolean hasPendingUpload(java.util.UUID uuid) {
        return pendingUploads.containsKey(uuid);
    }

    public static Path consumePendingUpload(java.util.UUID uuid) {
        PendingUpload upload = pendingUploads.remove(uuid);
        return upload != null ? upload.path : null;
    }

    private static class PaletteEntry {
        String name;
        CompoundTag properties;

        PaletteEntry(String name, CompoundTag properties) {
            this.name = name;
            this.properties = properties;
        }

        CompoundTag toNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", name);
            if (properties != null && !properties.isEmpty()) {
                tag.put("Properties", properties.copy());
            }
            return tag;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PaletteEntry that = (PaletteEntry) o;
            return name.equals(that.name) && java.util.Objects.equals(properties, that.properties);
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + (properties != null ? properties.hashCode() : 0);
            return result;
        }
    }

    private static long[] packLitematicaArray(int[] v2Data, int width, int height, int length, int bitsPerEntry, net.minecraft.server.level.ServerPlayer player) {
        long lastUpdate = System.currentTimeMillis();
        long arraySize = (long) width * height * length;
        long arrayLength = (arraySize * bitsPerEntry + 63L) / 64L;
        long[] longArray = new long[(int) arrayLength];
        int maxEntryValue = (1 << bitsPerEntry) - 1;
        
        for (int i = 0; i < v2Data.length; i++) {
            if ((i & 0x3FFF) == 0 && player != null) {
                long now = System.currentTimeMillis();
                if (now - lastUpdate > 100) {
                    if (player != null && !player.hasDisconnected()) {
                        float percent = (float) i / v2Data.length * 100f;
                        player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(String.format("Packing Blocks: %.1f%%", percent)).withStyle(net.minecraft.ChatFormatting.GREEN));
                    }
                    lastUpdate = now;
                }
            }
            int value = v2Data[i];
            
            int rz = i / (width * height);
            int rem = i % (width * height);
            int ry = rem / width;
            int rx = rem % width;
            
            long lIndex = ((long) ry * width * length) + ((long) rz * width) + rx;
            
            long startOffset = lIndex * bitsPerEntry;
            int startArrIndex = (int) (startOffset >> 6);
            int endArrIndex = (int) (((lIndex + 1L) * bitsPerEntry - 1L) >> 6);
            int startBitOffset = (int) (startOffset & 0x3F);
            
            longArray[startArrIndex] = longArray[startArrIndex] & ~((long) maxEntryValue << startBitOffset) | (long) (value & maxEntryValue) << startBitOffset;
            
            if (startArrIndex != endArrIndex) {
                int endOffset = 64 - startBitOffset;
                int j1 = bitsPerEntry - endOffset;
                longArray[endArrIndex] = longArray[endArrIndex] >>> j1 << j1 | (value & maxEntryValue) >> endOffset;
            }
        }
        if (player != null && !player.hasDisconnected()) player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.packing_blocks_100_0").withStyle(net.minecraft.ChatFormatting.GREEN));
        return longArray;
    }

    public static void exportSnapshot(Project project, String branchName, String category, int id, ServerPlayer player) {
        com.svcntrl.SvcntrlMod.runAsync(() -> {
            try {
                CompoundTag root = AreaSerializer.readSnapshot(project, branchName, category, id);
                if (root == null) {
                    player.sendSystemMessage(Component.translatable("svcntrl.msg.snapshot_not_found").withStyle(ChatFormatting.RED));
                    return;
                }

                CompoundTag litematic = convertToLitematic(root, project, category, id, player);

                Path exportDir = ProjectManager.getInstance().getDataDir().resolve("exports");
                Files.createDirectories(exportDir);
                String fileName = project.getName() + "_" + category + "_" + id + ".litematic";
                Path filePath = exportDir.resolve(fileName);

                NbtIo.writeCompressed(litematic, filePath);

                if (com.svcntrl.config.SvcntrlConfig.getInstance().allowPublicExport && player.level().getServer().isDedicatedServer()) {
                    player.sendSystemMessage(Component.translatable("svcntrl.msg.uploading_schematic_to_public").withStyle(ChatFormatting.YELLOW));

                    com.svcntrl.SvcntrlMod.runAsync(() -> {
                        try {
                            Path zipPath = exportDir.resolve(fileName + ".zip");
                            try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {
                                zout.putNextEntry(new java.util.zip.ZipEntry(fileName));
                                Files.copy(filePath, zout);
                                zout.closeEntry();
                            }
                            uploadToTmpfiles(zipPath, player);
                        } catch (Throwable e) {
                            SvcntrlMod.LOGGER.error("Export zip failed", e);
                            if (player != null && !player.hasDisconnected()) player.sendSystemMessage(Component.translatable("svcntrl.msg.failed_to_create_zip_check_log").withStyle(ChatFormatting.RED));
                        }
                    });
                } else {
                    player.sendSystemMessage(Component.translatable("svcntrl.msg.export_saved", fileName).withStyle(ChatFormatting.GREEN));
                }

            } catch (Throwable e) {
                SvcntrlMod.LOGGER.error("Failed to export", e);
                player.sendSystemMessage(Component.translatable("svcntrl.msg.error_exporting_snapshot_check").withStyle(ChatFormatting.RED));
            }
        });
    }

    public static void exportProjectFull(Project project, ServerPlayer player) {
        new Thread(() -> {
            try {
                Path projectDir = ProjectManager.getInstance().getProjectDir(project);
                if (!Files.exists(projectDir)) {
                    player.sendSystemMessage(Component.translatable("svcntrl.msg.project_directory_not_found").withStyle(ChatFormatting.RED));
                    return;
                }

                Path exportDir = ProjectManager.getInstance().getDataDir().resolve("exports");
                Files.createDirectories(exportDir);
                String fileName = project.getName() + "_full.zip";
                Path zipPath = exportDir.resolve(fileName);

                if (player != null && !player.hasDisconnected()) player.sendSystemMessage(Component.translatable("svcntrl.msg.converting_and_packing_full_pr")
                        .withStyle(ChatFormatting.YELLOW), false);

                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath));
                     Stream<Path> paths = Files.walk(projectDir)) {
                    paths.filter(path -> !Files.isDirectory(path))
                            .forEach(path -> {
                                try {
                                    String relPath = projectDir.relativize(path).toString().replace("\\", "/");
                                    if (path.toString().endsWith(".nbt")) {
                                        CompoundTag snapRoot = net.minecraft.nbt.NbtIo.readCompressed(path, net.minecraft.nbt.NbtAccounter.create(512L * 1024L * 1024L));
                                        String[] pathParts = relPath.split("/");
                                        String cat = pathParts.length > 1 ? pathParts[pathParts.length - 2] : "snapshot";
                                        int sid = 0;
                                        String fileNameOnly = path.getFileName().toString();
                                        if (fileNameOnly.startsWith("snapshot_") && fileNameOnly.endsWith(".nbt")) {
                                            try {
                                                sid = Integer.parseInt(fileNameOnly.substring(9, fileNameOnly.length() - 4));
                                            } catch (NumberFormatException ignored) {}
                                        }
                                        CompoundTag lite = convertToLitematic(snapRoot, project, cat, sid, player);
                                        
                                        zos.putNextEntry(new ZipEntry(relPath.replace(".nbt", ".litematic")));
                                        java.io.OutputStream unclosableZos = new java.io.FilterOutputStream(zos) {
                                            @Override
                                            public void close() throws java.io.IOException {
                                                // Prevent NbtIo from closing the ZipOutputStream
                                            }
                                        };
                                        net.minecraft.nbt.NbtIo.writeCompressed(lite, unclosableZos);
                                        zos.closeEntry();
                                    } else {
                                        zos.putNextEntry(new ZipEntry(relPath));
                                        Files.copy(path, zos);
                                        zos.closeEntry();
                                    }
                                } catch (Exception ex) {
                                    SvcntrlMod.LOGGER.error("Failed to add file to zip", ex);
                                }
                            });
                }

                if (com.svcntrl.config.SvcntrlConfig.getInstance().allowPublicExport && player != null && player.level().getServer().isDedicatedServer()) {
                    com.svcntrl.SvcntrlMod.runAsync(() -> {
                        uploadToTmpfiles(zipPath, player);
                    });
                } else if (player != null) {
                    player.sendSystemMessage(Component.translatable("svcntrl.msg.export_saved", fileName).withStyle(ChatFormatting.GREEN));
                }

            } catch (Throwable e) {
                SvcntrlMod.LOGGER.error("Failed to export full project", e);
                player.sendSystemMessage(Component.translatable("svcntrl.msg.error_exporting_full_project_c").withStyle(ChatFormatting.RED));
            }
        }, "Svcntrl-Export-Full").start();
    }

    public static CompoundTag convertToLitematic(CompoundTag root, Project project, String category, int id, ServerPlayer player) {
        CompoundTag litematic = new CompoundTag();
        litematic.putInt("MinecraftDataVersion", 4189);
        litematic.putInt("Version", 5);
        litematic.putString("Author", player != null ? player.getName().getString() : "svcntrl");

        int sizeX = root.getIntOr("MaxX", 0) - root.getIntOr("MinX", 0) + 1;
        int sizeY = root.getIntOr("MaxY", 0) - root.getIntOr("MinY", 0) + 1;
        int sizeZ = root.getIntOr("MaxZ", 0) - root.getIntOr("MinZ", 0) + 1;

        CompoundTag metadata = new CompoundTag();
        CompoundTag enclosing = new CompoundTag();
        enclosing.putInt("x", sizeX);
        enclosing.putInt("y", sizeY);
        enclosing.putInt("z", sizeZ);
        metadata.put("EnclosingSize", enclosing);
        metadata.putString("Author", player != null ? player.getName().getString() : "svcntrl");
        metadata.putString("Description", "Exported by SVControl");
        metadata.putString("Name", project.getName() + "_" + category + "_" + id);
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TimeCreated", System.currentTimeMillis());
        metadata.putLong("TimeModified", System.currentTimeMillis());
        metadata.putInt("TotalVolume", sizeX * sizeY * sizeZ);
        litematic.put("Metadata", metadata);

        List<PaletteEntry> palette = new ArrayList<>();
        Map<PaletteEntry, Integer> paletteMap = new HashMap<>();
        
        int[] blockData;
        ListTag blockEntitiesOut = new ListTag();

        boolean isV2 = root.contains("Version") && root.getIntOr("Version", 1) == 2;
        if (isV2) {
            blockData = root.getIntArray("BlockData").orElse(new int[0]);
            ListTag inPalette = root.getListOrEmpty("Palette");
            ListTag blockEntities = root.getListOrEmpty("BlockEntities");
            
            for (int i = 0; i < inPalette.size(); i++) {
                CompoundTag pEntry = inPalette.getCompoundOrEmpty(i);
                String blockId = pEntry.getStringOr("BlockId", "minecraft:air");
                CompoundTag props = pEntry.contains("Properties") ? pEntry.getCompoundOrEmpty("Properties") : new CompoundTag();
                PaletteEntry entry = new PaletteEntry(blockId, props);
                palette.add(entry);
                paletteMap.put(entry, i);
            }
            
            for (int i = 0; i < blockEntities.size(); i++) {
                CompoundTag be = blockEntities.getCompoundOrEmpty(i);
                CompoundTag beOut = be.getCompoundOrEmpty("Data").copy();
                beOut.putInt("x", be.getIntOr("X", 0));
                beOut.putInt("y", be.getIntOr("Y", 0));
                beOut.putInt("z", be.getIntOr("Z", 0));
                blockEntitiesOut.add(beOut);
            }
        } else {
            blockData = new int[sizeX * sizeY * sizeZ];
            
            PaletteEntry airEntry = new PaletteEntry("minecraft:air", new CompoundTag());
            palette.add(airEntry);
            paletteMap.put(airEntry, 0);
            
            ListTag inBlocks = root.getListOrEmpty("Blocks");
            long lastUpdate = System.currentTimeMillis();
            for (int i = 0; i < inBlocks.size(); i++) {
                if ((i & 0x3FFF) == 0 && player != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 100) {
                        if (player != null && !player.hasDisconnected()) {
                            float percent = (float) i / inBlocks.size() * 100f;
                            player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(String.format("Converting V1 Blocks: %.1f%%", percent)).withStyle(net.minecraft.ChatFormatting.GREEN));
                        }
                        lastUpdate = now;
                    }
                }
                CompoundTag blockIn = inBlocks.getCompoundOrEmpty(i);
                String blockId = blockIn.getStringOr("BlockId", "minecraft:air");
                CompoundTag props = blockIn.contains("Properties") ? blockIn.getCompoundOrEmpty("Properties") : new CompoundTag();

                PaletteEntry entry = new PaletteEntry(blockId, props);
                int pIndex = paletteMap.getOrDefault(entry, -1);
                if (pIndex == -1) {
                    pIndex = palette.size();
                    palette.add(entry);
                    paletteMap.put(entry, pIndex);
                }

                int relX = blockIn.getIntOr("X", 0);
                int relY = blockIn.getIntOr("Y", 0);
                int relZ = blockIn.getIntOr("Z", 0);
                int flatIndex = relX + relY * sizeX + relZ * sizeX * sizeY;
                blockData[flatIndex] = pIndex;

                if (blockIn.contains("BlockEntityData")) {
                    CompoundTag beOut = blockIn.getCompoundOrEmpty("BlockEntityData").copy();
                    beOut.putInt("x", relX);
                    beOut.putInt("y", relY);
                    beOut.putInt("z", relZ);
                    // id is usually already in BlockEntityData
                    blockEntitiesOut.add(beOut);
                }
            }
            if (player != null && !player.hasDisconnected()) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.converting_v1_blocks_100_0").withStyle(net.minecraft.ChatFormatting.GREEN));
        }

        int totalBlocks = 0;
        int airIndex = -1;
        for (int i = 0; i < palette.size(); i++) {
            if (palette.get(i).name.equals("minecraft:air") || palette.get(i).name.equals("minecraft:cave_air") || palette.get(i).name.equals("minecraft:void_air")) {
                airIndex = i;
                break;
            }
        }
        for (int pIdx : blockData) {
            if (pIdx != airIndex) {
                totalBlocks++;
            }
        }
        metadata.putInt("TotalBlocks", totalBlocks);

        CompoundTag regions = new CompoundTag();
        CompoundTag region = new CompoundTag();
        
        CompoundTag pos = new CompoundTag();
        pos.putInt("x", 0);
        pos.putInt("y", 0);
        pos.putInt("z", 0);
        region.put("Position", pos);
        
        CompoundTag size = new CompoundTag();
        size.putInt("x", sizeX);
        size.putInt("y", sizeY);
        size.putInt("z", sizeZ);
        region.put("Size", size);
        
        ListTag blockStatePalette = new ListTag();
        for (PaletteEntry e : palette) {
            CompoundTag pEntry = new CompoundTag();
            pEntry.putString("Name", e.name);
            if (!e.properties.isEmpty()) {
                pEntry.put("Properties", e.properties);
            }
            blockStatePalette.add(pEntry);
        }
        region.put("BlockStatePalette", blockStatePalette);
        
        int bitsPerEntry = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
        long[] packed = packLitematicaArray(blockData, sizeX, sizeY, sizeZ, bitsPerEntry, player);
        region.putLongArray("BlockStates", packed);
        
        region.put("TileEntities", blockEntitiesOut);
        
        ListTag snapshotEntities = root.getListOrEmpty("Entities");
        ListTag entitiesOut = new ListTag();
        for (int i = 0; i < snapshotEntities.size(); i++) {
            CompoundTag entityNbt = snapshotEntities.getCompoundOrEmpty(i).copy();
            
            double relX = entityNbt.getDoubleOr("svcntrl_RelX", 0.0);
            double relY = entityNbt.getDoubleOr("svcntrl_RelY", 0.0);
            double relZ = entityNbt.getDoubleOr("svcntrl_RelZ", 0.0);

            entityNbt.remove("svcntrl_RelX");
            entityNbt.remove("svcntrl_RelY");
            entityNbt.remove("svcntrl_RelZ");

            if (entityNbt.contains("svcntrl_AttachRelX")) {
                int attachX = entityNbt.getIntOr("svcntrl_AttachRelX", 0);
                int attachY = entityNbt.getIntOr("svcntrl_AttachRelY", 0);
                int attachZ = entityNbt.getIntOr("svcntrl_AttachRelZ", 0);
                entityNbt.putInt("TileX", attachX);
                entityNbt.putInt("TileY", attachY);
                entityNbt.putInt("TileZ", attachZ);
                entityNbt.remove("svcntrl_AttachRelX");
                entityNbt.remove("svcntrl_AttachRelY");
                entityNbt.remove("svcntrl_AttachRelZ");
            }

            entityNbt.remove("UUID");
            
            ListTag posList = new ListTag();
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(relX));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(relY));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(relZ));
            entityNbt.put("Pos", posList);
            
            entitiesOut.add(entityNbt);
        }
        region.put("Entities", entitiesOut);
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        
        regions.put("Exported", region);
        litematic.put("Regions", regions);
        return litematic;
    }

    public static void exportDiff(Project project, String targetBranch, String targetCategory, int targetId, String baseBranch, String baseCategory, int baseId, ServerPlayer player) {
        com.svcntrl.SvcntrlMod.runAsync(() -> {
            try {
                Path targetPath = ProjectManager.getInstance().getSnapshotPath(project, targetBranch, targetCategory, targetId);
                Path basePath = ProjectManager.getInstance().getSnapshotPath(project, baseBranch, baseCategory, baseId);

                if (!Files.exists(targetPath) || !Files.exists(basePath)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.one_or_both_snapshots_not_foun").withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }

                if (player != null && !player.hasDisconnected()) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.calculating_diff_and_generatin")
                        .withStyle(net.minecraft.ChatFormatting.YELLOW), false);

                CompoundTag targetRoot = net.minecraft.nbt.NbtIo.readCompressed(targetPath, net.minecraft.nbt.NbtAccounter.create(512L * 1024L * 1024L));
                CompoundTag baseRoot = net.minecraft.nbt.NbtIo.readCompressed(basePath, net.minecraft.nbt.NbtAccounter.create(512L * 1024L * 1024L));

                CompoundTag litematic = convertToLitematicDiff(targetRoot, baseRoot, project, targetCategory, targetId, baseId, player);
                
                if (litematic == null) return;

                Path exportDir = ProjectManager.getInstance().getDataDir().resolve("exports");
                Files.createDirectories(exportDir);
                String fileName = project.getName() + "_diff_" + targetId + "_vs_" + baseId + ".litematic";
                Path filePath = exportDir.resolve(fileName);

                net.minecraft.nbt.NbtIo.writeCompressed(litematic, filePath);

                if (com.svcntrl.config.SvcntrlConfig.getInstance().allowPublicExport && player.level().getServer().isDedicatedServer()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.uploading_diff_schematic_pleas")
                            .withStyle(net.minecraft.ChatFormatting.YELLOW), false);

                    com.svcntrl.SvcntrlMod.runAsync(() -> {
                        try {
                            Path zipPath = exportDir.resolve(fileName + ".zip");
                            try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {
                                zout.putNextEntry(new java.util.zip.ZipEntry(fileName));
                                Files.copy(filePath, zout);
                                zout.closeEntry();
                            }
                            uploadToTmpfiles(zipPath, player);
                        } catch (Throwable e) {
                            SvcntrlMod.LOGGER.error("Diff zip failed", e);
                            if (player != null && !player.hasDisconnected()) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.failed_to_create_zip_check_log").withStyle(net.minecraft.ChatFormatting.RED));
                        }
                    });
                } else {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.diff_export_saved", fileName).withStyle(net.minecraft.ChatFormatting.GREEN));
                }

            } catch (Throwable e) {
                SvcntrlMod.LOGGER.error("Failed to export diff", e);
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.error_exporting_diff_check_ser").withStyle(net.minecraft.ChatFormatting.RED));
            }
        });
    }

    public static CompoundTag convertToLitematicDiff(CompoundTag targetRoot, CompoundTag baseRoot, Project project, String category, int targetId, int baseId, ServerPlayer player) {
        boolean isV2Target = targetRoot.contains("Version") && targetRoot.getIntOr("Version", 1) == 2;
        boolean isV2Base = baseRoot.contains("Version") && baseRoot.getIntOr("Version", 1) == 2;
        
        if (!isV2Target || !isV2Base) {
            if (player != null && !player.hasDisconnected()) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.diff_export_currently_only_sup").withStyle(net.minecraft.ChatFormatting.RED));
            return null;
        }

        if (targetRoot.getIntOr("MinX", 0) != baseRoot.getIntOr("MinX", 0) ||
            targetRoot.getIntOr("MaxX", 0) != baseRoot.getIntOr("MaxX", 0) ||
            targetRoot.getIntOr("MinY", 0) != baseRoot.getIntOr("MinY", 0) ||
            targetRoot.getIntOr("MaxY", 0) != baseRoot.getIntOr("MaxY", 0) ||
            targetRoot.getIntOr("MinZ", 0) != baseRoot.getIntOr("MinZ", 0) ||
            targetRoot.getIntOr("MaxZ", 0) != baseRoot.getIntOr("MaxZ", 0)) {
            if (player != null && !player.hasDisconnected()) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.export_diff_dims").withStyle(net.minecraft.ChatFormatting.RED));
            return null;
        }

        CompoundTag litematic = new CompoundTag();
        litematic.putInt("MinecraftDataVersion", 4189);
        litematic.putInt("Version", 5);
        litematic.putString("Author", player != null ? player.getName().getString() : "svcntrl");

        int sizeX = targetRoot.getIntOr("MaxX", 0) - targetRoot.getIntOr("MinX", 0) + 1;
        int sizeY = targetRoot.getIntOr("MaxY", 0) - targetRoot.getIntOr("MinY", 0) + 1;
        int sizeZ = targetRoot.getIntOr("MaxZ", 0) - targetRoot.getIntOr("MinZ", 0) + 1;

        CompoundTag metadata = new CompoundTag();
        CompoundTag enclosing = new CompoundTag();
        enclosing.putInt("x", sizeX);
        enclosing.putInt("y", sizeY);
        enclosing.putInt("z", sizeZ);
        metadata.put("EnclosingSize", enclosing);
        metadata.putString("Author", player != null ? player.getName().getString() : "svcntrl");
        metadata.putString("Description", "Diff exported by SVControl");
        metadata.putString("Name", project.getName() + "_diff_" + targetId + "_vs_" + baseId);
        metadata.putInt("RegionCount", 1);
        metadata.putLong("TimeCreated", System.currentTimeMillis());
        metadata.putLong("TimeModified", System.currentTimeMillis());
        metadata.putInt("TotalVolume", sizeX * sizeY * sizeZ);
        litematic.put("Metadata", metadata);

        int[] tData = targetRoot.getIntArray("BlockData").orElse(new int[0]);
        ListTag tPalList = targetRoot.getListOrEmpty("Palette");
        PaletteEntry[] tPalette = new PaletteEntry[tPalList.size()];
        for (int i = 0; i < tPalList.size(); i++) {
            CompoundTag p = tPalList.getCompoundOrEmpty(i);
            tPalette[i] = new PaletteEntry(p.getStringOr("BlockId", "minecraft:air"), p.contains("Properties") ? p.getCompoundOrEmpty("Properties") : new CompoundTag());
        }

        int[] bData = baseRoot.getIntArray("BlockData").orElse(new int[0]);
        ListTag bPalList = baseRoot.getListOrEmpty("Palette");
        PaletteEntry[] bPalette = new PaletteEntry[bPalList.size()];
        for (int i = 0; i < bPalList.size(); i++) {
            CompoundTag p = bPalList.getCompoundOrEmpty(i);
            bPalette[i] = new PaletteEntry(p.getStringOr("BlockId", "minecraft:air"), p.contains("Properties") ? p.getCompoundOrEmpty("Properties") : new CompoundTag());
        }

        Map<Integer, CompoundTag> tBEs = new HashMap<>();
        ListTag tBEList = targetRoot.getListOrEmpty("BlockEntities");
        for (int i = 0; i < tBEList.size(); i++) {
            CompoundTag be = tBEList.getCompoundOrEmpty(i);
            int idx = (be.getIntOr("Z", 0) * sizeX * sizeY) + (be.getIntOr("Y", 0) * sizeX) + be.getIntOr("X", 0);
            tBEs.put(idx, be.getCompoundOrEmpty("Data"));
        }

        Map<Integer, CompoundTag> bBEs = new HashMap<>();
        ListTag bBEList = baseRoot.getListOrEmpty("BlockEntities");
        for (int i = 0; i < bBEList.size(); i++) {
            CompoundTag be = bBEList.getCompoundOrEmpty(i);
            int idx = (be.getIntOr("Z", 0) * sizeX * sizeY) + (be.getIntOr("Y", 0) * sizeX) + be.getIntOr("X", 0);
            bBEs.put(idx, be.getCompoundOrEmpty("Data"));
        }

        List<PaletteEntry> outPalette = new ArrayList<>();
        Map<PaletteEntry, Integer> outPaletteMap = new HashMap<>();
        PaletteEntry airEntry = new PaletteEntry("minecraft:air", new CompoundTag());
        
        // Ensure air is first, so it's the default background block
        outPalette.add(airEntry);
        outPaletteMap.put(airEntry, 0);

        int[] outData = new int[sizeX * sizeY * sizeZ];
        ListTag outBEs = new ListTag();

        for (int i = 0; i < tData.length; i++) {
            PaletteEntry tEntry = (tData[i] >= 0 && tData[i] < tPalette.length) ? tPalette[tData[i]] : airEntry;
            PaletteEntry bEntry = (bData.length > i && bData[i] >= 0 && bData[i] < bPalette.length) ? bPalette[bData[i]] : airEntry;
            
            CompoundTag tBE = tBEs.get(i);
            CompoundTag bBE = bBEs.get(i);
            boolean beEqual = (tBE == null && bBE == null) || (tBE != null && tBE.equals(bBE));

            PaletteEntry finalEntry;
            if (tEntry.equals(bEntry) && beEqual) {
                finalEntry = airEntry;
            } else {
                finalEntry = tEntry;
                if (tBE != null) {
                    CompoundTag outBE = tBE.copy();
                    int relX = i % sizeX;
                    int relY = (i / sizeX) % sizeY;
                    int relZ = i / (sizeX * sizeY);
                    outBE.putInt("x", relX);
                    outBE.putInt("y", relY);
                    outBE.putInt("z", relZ);
                    outBEs.add(outBE);
                }
            }

            int pIndex = outPaletteMap.getOrDefault(finalEntry, -1);
            if (pIndex == -1) {
                pIndex = outPalette.size();
                outPalette.add(finalEntry);
                outPaletteMap.put(finalEntry, pIndex);
            }
            outData[i] = pIndex;
        }
        int totalBlocks = 0;
        int airIdx = outPaletteMap.getOrDefault(airEntry, -1);
        for (int pIdx : outData) {
            if (pIdx != airIdx) totalBlocks++;
        }
        metadata.putInt("TotalBlocks", totalBlocks);

        CompoundTag regions = new CompoundTag();
        CompoundTag region = new CompoundTag();
        
        CompoundTag pos = new CompoundTag();
        pos.putInt("x", 0);
        pos.putInt("y", 0);
        pos.putInt("z", 0);
        region.put("Position", pos);
        
        CompoundTag sizeNbt = new CompoundTag();
        sizeNbt.putInt("x", sizeX);
        sizeNbt.putInt("y", sizeY);
        sizeNbt.putInt("z", sizeZ);
        region.put("Size", sizeNbt);
        
        ListTag blockStatePalette = new ListTag();
        for (PaletteEntry e : outPalette) {
            CompoundTag pEntry = new CompoundTag();
            pEntry.putString("Name", e.name);
            if (!e.properties.isEmpty()) {
                pEntry.put("Properties", e.properties);
            }
            blockStatePalette.add(pEntry);
        }
        region.put("BlockStatePalette", blockStatePalette);
        
        int bitsPerEntry = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(outPalette.size() - 1));
        long[] packed = packLitematicaArray(outData, sizeX, sizeY, sizeZ, bitsPerEntry, player);
        region.putLongArray("BlockStates", packed);
        
        region.put("TileEntities", outBEs);
        // For diff entities, we filter out entities that are identical in the base snapshot.
        // Litematica format cannot encode "deleted" entities, but we can avoid exporting unmodified ones.
        ListTag targetEntities = targetRoot.getListOrEmpty("Entities");
        ListTag baseEntities = baseRoot.getListOrEmpty("Entities");
        ListTag entitiesOut = new ListTag();

        java.util.function.Function<CompoundTag, String> getEntityHash = (nbt) -> {
            String id = nbt.getStringOr("id", "");
            double rx = nbt.getDoubleOr("svcntrl_RelX", 0);
            double ry = nbt.getDoubleOr("svcntrl_RelY", 0);
            double rz = nbt.getDoubleOr("svcntrl_RelZ", 0);
            return id + "|" + Math.round(rx * 10) + "|" + Math.round(ry * 10) + "|" + Math.round(rz * 10);
        };

        java.util.Set<String> baseEntitiesSet = new java.util.HashSet<>();
        for (int i = 0; i < baseEntities.size(); i++) {
            baseEntitiesSet.add(getEntityHash.apply(baseEntities.getCompoundOrEmpty(i)));
        }

        for (int i = 0; i < targetEntities.size(); i++) {
            CompoundTag targetEnt = targetEntities.getCompoundOrEmpty(i);
            if (baseEntitiesSet.contains(getEntityHash.apply(targetEnt))) {
                continue; // Unmodified entity, skip exporting it
            }
            CompoundTag entityNbt = targetEnt.copy();
            double relX = entityNbt.getDoubleOr("svcntrl_RelX", 0.0);
            double relY = entityNbt.getDoubleOr("svcntrl_RelY", 0.0);
            double relZ = entityNbt.getDoubleOr("svcntrl_RelZ", 0.0);
            
            entityNbt.remove("svcntrl_RelX");
            entityNbt.remove("svcntrl_RelY");
            entityNbt.remove("svcntrl_RelZ");
            entityNbt.remove("UUID");
            
            ListTag posList = new ListTag();
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(relX));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(relY));
            posList.add(net.minecraft.nbt.DoubleTag.valueOf(relZ));
            entityNbt.put("Pos", posList);
            
            entitiesOut.add(entityNbt);
        }
        region.put("Entities", entitiesOut);
        region.put("PendingBlockTicks", new ListTag());
        region.put("PendingFluidTicks", new ListTag());
        
        regions.put("Exported", region);
        litematic.put("Regions", regions);
        return litematic;
    }

    public static void uploadToTmpfiles(Path zipPath, net.minecraft.server.level.ServerPlayer player) {
        if (player == null) return;
        
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.export_saved_server", zipPath.getFileName().toString()).withStyle(net.minecraft.ChatFormatting.GREEN));
        
        if (!com.svcntrl.config.SvcntrlConfig.getInstance().allowPublicExport || !player.level().getServer().isDedicatedServer()) {
            return;
        }

        Boolean pref = com.svcntrl.data.ProjectManager.getInstance().getAutoUploadPref(player.getUUID());
        if (Boolean.TRUE.equals(pref)) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.auto_uploading", zipPath.getFileName().toString()).withStyle(net.minecraft.ChatFormatting.YELLOW));
            com.svcntrl.SvcntrlMod.runAsync(() -> doActualUpload(zipPath, player));
            return;
        } else if (Boolean.FALSE.equals(pref)) {
            return;
        }

        long expiry = System.currentTimeMillis() + 60_000; // 60 seconds
        pendingUploads.put(player.getUUID(), new PendingUpload(zipPath, expiry));

        net.minecraft.network.chat.MutableComponent uploadPrompt = net.minecraft.network.chat.Component.translatable("svcntrl.msg.do_you_want_to_upload_this_fil")
                .withStyle(net.minecraft.ChatFormatting.YELLOW)
                .append(" ")
                .append(net.minecraft.network.chat.Component.literal("[YES]")
                        .withStyle(net.minecraft.ChatFormatting.AQUA, net.minecraft.ChatFormatting.BOLD)
                        .withStyle(style -> style
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/svcntrl upload yes"))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.translatable("svcntrl.msg.hover_upload_once")))))
                .append(" ")
                .append(net.minecraft.network.chat.Component.literal("[ALWAYS]")
                        .withStyle(net.minecraft.ChatFormatting.GREEN, net.minecraft.ChatFormatting.BOLD)
                        .withStyle(style -> style
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/svcntrl upload always"))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.translatable("svcntrl.msg.hover_upload_always")))))
                .append(" ")
                .append(net.minecraft.network.chat.Component.literal("[NO]")
                        .withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD)
                        .withStyle(style -> style
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/svcntrl upload no"))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.translatable("svcntrl.msg.hover_upload_skip")))))
                .append(" ")
                .append(net.minecraft.network.chat.Component.literal("[NEVER]")
                        .withStyle(net.minecraft.ChatFormatting.DARK_RED, net.minecraft.ChatFormatting.BOLD)
                        .withStyle(style -> style
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/svcntrl upload never"))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.translatable("svcntrl.msg.hover_upload_never")))));

        player.sendSystemMessage(uploadPrompt);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("(You can change this later with /svcntrl upload <always/never/reset>)").withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    public static void doActualUpload(Path zipPath, net.minecraft.server.level.ServerPlayer player) {
        Path tempFile = null;
        try {
            String boundary = "===" + System.currentTimeMillis() + "===";
            
            // Write the entire multipart request to a temporary file to avoid OutOfMemoryError on large exports
            tempFile = Files.createTempFile("svcntrl_upload_", ".tmp");
            try (java.io.OutputStream fos = new java.io.BufferedOutputStream(Files.newOutputStream(tempFile))) {
                fos.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + zipPath.getFileName().toString() + "\"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.write(("Content-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                
                try (java.io.InputStream fis = new java.io.BufferedInputStream(Files.newInputStream(zipPath))) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                
                fos.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            
            String endpoint = com.svcntrl.config.SvcntrlConfig.getInstance().customExportEndpoint;
            if (endpoint == null || endpoint.isEmpty()) {
                endpoint = "https://tmpfiles.org/api/v1/upload";
            }
            
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(endpoint))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(java.time.Duration.ofMinutes(5)) // allow up to 5 mins for upload
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofFile(tempFile))
                    .build();

            java.net.http.HttpResponse<String> response = HTTP_CLIENT.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200 || response.statusCode() == 201) {
                String output = response.body();
                String dlUrl = null;
                try {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(output).getAsJsonObject();
                    if (json.has("data") && json.getAsJsonObject("data").has("url")) {
                        dlUrl = json.getAsJsonObject("data").get("url").getAsString().replace("tmpfiles.org/", "tmpfiles.org/dl/");
                    } else if (json.has("url")) {
                        dlUrl = json.get("url").getAsString();
                    }
                } catch (Exception e) {
                    if (output.startsWith("http")) {
                        dlUrl = output.trim();
                    }
                }

                if (dlUrl != null) {
                    final String finalDlUrl = dlUrl;
                    if (player != null && !player.hasDisconnected()) {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.export_uploaded_download_link")
                                .withStyle(net.minecraft.ChatFormatting.GREEN)
                                .append(net.minecraft.network.chat.Component.translatable("svcntrl.msg.download")
                                        .withStyle(net.minecraft.ChatFormatting.GOLD)
                                        .withStyle(s -> {
                                            try {
                                                return s.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(new java.net.URI(finalDlUrl)));
                                            } catch (Exception ex) {
                                                return s;
                                            }
                                        })), false);
                        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.security_notice_this_link_is_p").withStyle(net.minecraft.ChatFormatting.GRAY));
                    }
                    try {
                        Files.deleteIfExists(zipPath);
                        String fName = zipPath.getFileName().toString();
                        String base = fName.endsWith(".zip") ? fName.substring(0, fName.length() - 4) : fName;
                        Files.deleteIfExists(zipPath.getParent().resolve(base));
                    } catch (Exception ignored) {}
                } else {
                    if (player != null && !player.hasDisconnected()) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.failed_to_upload_unrecognized").withStyle(net.minecraft.ChatFormatting.RED));
                }
            } else {
                if (player != null && !player.hasDisconnected()) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.upload_failed_http", response.statusCode()).withStyle(net.minecraft.ChatFormatting.RED));
            }
        } catch (Exception e) {
            com.svcntrl.SvcntrlMod.LOGGER.error("Failed to upload export to tmpfiles.org", e);
            if (player != null && !player.hasDisconnected()) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("svcntrl.msg.upload_error", e.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
        } finally {
            if (tempFile != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tempFile);
                } catch (java.io.IOException e) {
                    com.svcntrl.SvcntrlMod.LOGGER.error("Failed to delete temp upload file: " + tempFile, e);
                }
            }
        }
    }
}
