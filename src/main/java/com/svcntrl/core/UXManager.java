package com.svcntrl.core;

import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import com.svcntrl.config.SvcntrlConfig;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UXManager {
    private static final UXManager INSTANCE = new UXManager();

    private final Set<UUID> outlinePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> raycastPlayers = ConcurrentHashMap.newKeySet();
    private int tickCounter = 0;

    private UXManager() {}

    public static UXManager getInstance() {
        return INSTANCE;
    }

    public boolean toggleOutline(UUID uuid) {
        if (outlinePlayers.contains(uuid)) {
            outlinePlayers.remove(uuid);
            return false;
        } else {
            outlinePlayers.add(uuid);
            return true;
        }
    }

    public void setRaycasting(UUID uuid, boolean state) {
        if (state) raycastPlayers.add(uuid);
        else raycastPlayers.remove(uuid);
    }

    public void removePlayer(UUID uuid) {
        outlinePlayers.remove(uuid);
        raycastPlayers.remove(uuid);
    }

    public boolean isRaycasting(UUID uuid) {
        return raycastPlayers.contains(uuid);
    }

    public Project getProjectLookingAt(ServerPlayerEntity player) {
        Vec3d cameraPos = player.getCameraPosVec(1.0F);
        Vec3d rotationVec = player.getRotationVec(1.0F);
        Vec3d rayEnd = cameraPos.add(rotationVec.multiply(100.0D));

        Project bestHitMatch = null;
        double minHitDistance = Double.MAX_VALUE;
        long minHitVolume = Long.MAX_VALUE;

        Project bestInsideMatch = null;
        long minInsideVolume = Long.MAX_VALUE;

        String worldId = player.getWorld().getRegistryKey().getValue().toString();
        for (Project project : ProjectManager.getInstance().getAllProjects()) {
            if (!project.getWorldId().equals(worldId)) continue;
            net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                project.getMin().getX(), project.getMin().getY(), project.getMin().getZ(),
                project.getMax().getX() + 1.0, project.getMax().getY() + 1.0, project.getMax().getZ() + 1.0
            );

            long volume = (long) (project.getMax().getX() - project.getMin().getX() + 1) *
                          (long) (project.getMax().getY() - project.getMin().getY() + 1) *
                          (long) (project.getMax().getZ() - project.getMin().getZ() + 1);

            if (box.contains(cameraPos)) {
                if (volume < minInsideVolume) {
                    minInsideVolume = volume;
                    bestInsideMatch = project;
                }
            } else {
                java.util.Optional<Vec3d> intersection = box.raycast(cameraPos, rayEnd);
                if (intersection.isPresent()) {
                    double dist = cameraPos.squaredDistanceTo(intersection.get());
                    if (dist < minHitDistance - 0.1 || (Math.abs(dist - minHitDistance) <= 0.1 && volume < minHitVolume)) {
                        minHitDistance = dist;
                        minHitVolume = volume;
                        bestHitMatch = project;
                    }
                }
            }
        }
        return bestHitMatch != null ? bestHitMatch : bestInsideMatch;
    }

    public void tick(MinecraftServer server) {
        tickCounter++;

        // Action bar for previewing players (every 10 ticks = 0.5 sec)
        if (tickCounter % 10 == 0) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (PreviewManager.getInstance().hasPreview(player.getUuid())) {
                    player.sendMessage(Text.translatable("svcntrl.msg.you_are_in_preview_mode_type_s").formatted(Formatting.AQUA, Formatting.BOLD), true);
                }
            }
        }

        // Raycast selection checking (every 2 ticks = 0.1 sec)
        if (tickCounter % 2 == 0) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (raycastPlayers.contains(player.getUuid())) {
                    Project lookedAt = getProjectLookingAt(player);
                    if (lookedAt != null) {
                        player.sendMessage(Text.translatable("svcntrl.msg.looking_at").formatted(Formatting.GRAY).append(Text.literal(lookedAt.getName()).formatted(Formatting.AQUA, Formatting.BOLD)).append(Text.translatable("svcntrl.msg.click_to_select").formatted(Formatting.YELLOW)), true);
                    } else {
                        player.sendMessage(Text.translatable("svcntrl.msg.looking_at").formatted(Formatting.GRAY).append(Text.translatable("svcntrl.msg.none").formatted(Formatting.DARK_GRAY)), true);
                    }
                }
            }
        }

        // Project boundary particles
        int freq = SvcntrlConfig.getInstance().outlineFrequencyTicks;
        if (freq <= 0) freq = 15;
        
        if (tickCounter % freq == 0) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                boolean raycasting = raycastPlayers.contains(player.getUuid());
                if (raycasting) {
                    String[] pool = SvcntrlConfig.getInstance().raycastParticlePool;
                    String worldId = player.getWorld().getRegistryKey().getValue().toString();
                    for (Project project : ProjectManager.getInstance().getAllProjects()) {
                        if (!project.getWorldId().equals(worldId)) continue;
                        if (project.getMin().getSquaredDistance(player.getBlockPos()) > 16384 && project.getMax().getSquaredDistance(player.getBlockPos()) > 16384) continue;
                        int index = Math.abs(project.getName().hashCode()) % pool.length;
                        spawnOutlineParticles(player, project, pool[index]);
                    }
                } else if (outlinePlayers.contains(player.getUuid())) {
                    Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
                    if (project != null && project.getWorldId().equals(player.getWorld().getRegistryKey().getValue().toString())) {
                        spawnOutlineParticles(player, project, SvcntrlConfig.getInstance().outlineParticle);
                    }
                }
            }
        }
    }

    private void spawnOutlineParticles(ServerPlayerEntity player, Project project, String particleStr) {
        BlockPos pos1 = project.getCorner1();
        BlockPos pos2 = project.getCorner2();
        if (pos1 == null || pos2 == null) return;

        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX()) + 1; // +1 to draw at the edge of the block
        int maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        int maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        double step = 2.0;

        // Bottom and Top rects
        for (double x = minX; x <= maxX; x += step) {
            sendParticle(player, x, minY, minZ, particleStr);
            sendParticle(player, x, minY, maxZ, particleStr);
            sendParticle(player, x, maxY, minZ, particleStr);
            sendParticle(player, x, maxY, maxZ, particleStr);
        }
        for (double z = minZ; z <= maxZ; z += step) {
            sendParticle(player, minX, minY, z, particleStr);
            sendParticle(player, maxX, minY, z, particleStr);
            sendParticle(player, minX, maxY, z, particleStr);
            sendParticle(player, maxX, maxY, z, particleStr);
        }
        // Vertical lines
        for (double y = minY; y <= maxY; y += step) {
            sendParticle(player, minX, y, minZ, particleStr);
            sendParticle(player, maxX, y, minZ, particleStr);
            sendParticle(player, minX, y, maxZ, particleStr);
            sendParticle(player, maxX, y, maxZ, particleStr);
        }
    }

    private void sendParticle(ServerPlayerEntity player, double x, double y, double z, String particleStr) {
        ParticleType<?> type = Registries.PARTICLE_TYPE.get(Identifier.of(particleStr));
        if (type == null) type = ParticleTypes.FLAME;
        
        ((net.minecraft.server.world.ServerWorld)player.getWorld()).spawnParticles(player, (net.minecraft.particle.ParticleEffect) type, true, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }
}
