package com.svcntrl.core;

import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import com.svcntrl.config.SvcntrlConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
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
        synchronized (outlinePlayers) {
            if (outlinePlayers.contains(uuid)) {
                outlinePlayers.remove(uuid);
                return false;
            } else {
                outlinePlayers.add(uuid);
                return true;
            }
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

    public Project getProjectLookingAt(ServerPlayer player) {
        Vec3 cameraPos = player.getEyePosition(1.0F);
        Vec3 rotationVec = player.getViewVector(1.0F);
        Vec3 rayEnd = cameraPos.add(rotationVec.scale(100.0D));

        Project bestHitMatch = null;
        double minHitDistance = Double.MAX_VALUE;
        long minHitVolume = Long.MAX_VALUE;

        Project bestInsideMatch = null;
        long minInsideVolume = Long.MAX_VALUE;

        String worldId = player.level().dimension().identifier().toString();
        for (Project project : ProjectManager.getInstance().getAllProjects()) {
            if (!project.getWorldId().equals(worldId)) continue;
            
            // Fast AABB distance check to avoid instantiating Box for distant projects
            double dx = Math.max(0, Math.max(project.getMin().getX() - cameraPos.x, cameraPos.x - (project.getMax().getX() + 1.0)));
            double dy = Math.max(0, Math.max(project.getMin().getY() - cameraPos.y, cameraPos.y - (project.getMax().getY() + 1.0)));
            double dz = Math.max(0, Math.max(project.getMin().getZ() - cameraPos.z, cameraPos.z - (project.getMax().getZ() + 1.0)));
            if (dx * dx + dy * dy + dz * dz > 10000.0) continue; // Ray length is 100, squared is 10000

            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
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
                java.util.Optional<Vec3> intersection = box.clip(cameraPos, rayEnd);
                if (intersection.isPresent()) {
                    double dist = cameraPos.distanceToSqr(intersection.get());
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
        tickCounter = (tickCounter + 1) % 1000000;

        // Action bar for previewing players (every 10 ticks = 0.5 sec)
        if (tickCounter % 10 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (PreviewManager.getInstance().hasPreview(player.getUUID())) {
                    player.sendOverlayMessage(Component.translatable("svcntrl.msg.you_are_in_preview_mode_type_s").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                }
            }
        }

        // Raycast selection checking (every 2 ticks = 0.1 sec)
        if (tickCounter % 2 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (raycastPlayers.contains(player.getUUID())) {
                    Project lookedAt = getProjectLookingAt(player);
                    if (lookedAt != null) {
                        player.sendOverlayMessage(Component.translatable("svcntrl.msg.looking_at").withStyle(ChatFormatting.GRAY).append(Component.literal(lookedAt.getName()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)).append(Component.translatable("svcntrl.msg.click_to_select").withStyle(ChatFormatting.YELLOW)));
                    } else {
                        player.sendSystemMessage(Component.translatable("svcntrl.msg.looking_at").withStyle(ChatFormatting.GRAY).append(Component.translatable("svcntrl.msg.none").withStyle(ChatFormatting.DARK_GRAY)));
                    }
                }
            }
        }

        // Project boundary particles
        int freq = SvcntrlConfig.getInstance().outlineFrequencyTicks;
        if (freq <= 0) freq = 15;
        
        if (tickCounter % freq == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                boolean raycasting = raycastPlayers.contains(player.getUUID());
                if (raycasting) {
                    String[] pool = SvcntrlConfig.getInstance().raycastParticlePool;
                    String worldId = player.level().dimension().identifier().toString();
                    for (Project project : ProjectManager.getInstance().getAllProjects()) {
                        if (!project.getWorldId().equals(worldId)) continue;
                        if (!project.contains(player.blockPosition()) && player.blockPosition().distSqr(new net.minecraft.core.BlockPos((project.getMin().getX() + project.getMax().getX()) / 2, (project.getMin().getY() + project.getMax().getY()) / 2, (project.getMin().getZ() + project.getMax().getZ()) / 2)) > 16384) continue;
                        int index = (project.getName().hashCode() & 0x7fffffff) % pool.length;
                        spawnOutlineParticles(player, project, pool[index]);
                    }
                } else if (outlinePlayers.contains(player.getUUID())) {
                    Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
                    if (project != null && project.getWorldId().equals(player.level().dimension().identifier().toString())) {
                        spawnOutlineParticles(player, project, SvcntrlConfig.getInstance().outlineParticle);
                    }
                }
            }
        }
    }

    private void spawnOutlineParticles(ServerPlayer player, Project project, String particleStr) {
        BlockPos pos1 = project.getCorner1();
        BlockPos pos2 = project.getCorner2();
        if (pos1 == null || pos2 == null) return;

        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX()) + 1; // +1 to draw at the edge of the block
        int maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        int maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        double lengthX = maxX - minX;
        double lengthY = maxY - minY;
        double lengthZ = maxZ - minZ;

        double maxParticlesPerEdge = 50.0;
        
        double stepX = Math.max(2.0, lengthX / maxParticlesPerEdge);
        double stepY = Math.max(2.0, lengthY / maxParticlesPerEdge);
        double stepZ = Math.max(2.0, lengthZ / maxParticlesPerEdge);

        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(particleStr));
        if (type == null) type = ParticleTypes.FLAME;
        
        net.minecraft.core.particles.ParticleOptions effect;
        if (type instanceof net.minecraft.core.particles.ParticleOptions) {
            effect = (net.minecraft.core.particles.ParticleOptions) type;
        } else {
            effect = (net.minecraft.core.particles.ParticleOptions) ParticleTypes.FLAME;
        }

        // Bottom and Top rects
        for (double x = minX; x <= maxX; x += stepX) {
            sendParticle(player, x, minY, minZ, effect);
            sendParticle(player, x, minY, maxZ, effect);
            sendParticle(player, x, maxY, minZ, effect);
            sendParticle(player, x, maxY, maxZ, effect);
        }
        for (double z = minZ; z <= maxZ; z += stepZ) {
            sendParticle(player, minX, minY, z, effect);
            sendParticle(player, maxX, minY, z, effect);
            sendParticle(player, minX, maxY, z, effect);
            sendParticle(player, maxX, maxY, z, effect);
        }
        // Vertical lines
        for (double y = minY; y <= maxY; y += stepY) {
            sendParticle(player, minX, y, minZ, effect);
            sendParticle(player, maxX, y, minZ, effect);
            sendParticle(player, minX, y, maxZ, effect);
            sendParticle(player, maxX, y, maxZ, effect);
        }
    }

    private void sendParticle(ServerPlayer player, double x, double y, double z, net.minecraft.core.particles.ParticleOptions effect) {
        ((net.minecraft.server.level.ServerLevel)player.level()).sendParticles(player, effect, true, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }
}
