package com.svcntrl.paper;

import com.svcntrl.config.SvcntrlConfig;
import com.svcntrl.core.TaskScheduler;
import com.svcntrl.data.BlockPos;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import com.svcntrl.util.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PaperUXManager {
    private static final PaperUXManager INSTANCE = new PaperUXManager();

    private final Set<UUID> outlinePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> raycastPlayers = ConcurrentHashMap.newKeySet();
    private int tickCounter = 0;

    private PaperUXManager() {}

    public static PaperUXManager getInstance() {
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

    public boolean isRaycasting(UUID uuid) {
        return raycastPlayers.contains(uuid);
    }

    public void removePlayer(UUID uuid) {
        outlinePlayers.remove(uuid);
        raycastPlayers.remove(uuid);
    }

    public Project getProjectLookingAt(Player player) {
        Location eye = player.getEyeLocation();
        Vector start = eye.toVector();
        Vector dir = eye.getDirection().normalize();

        Project bestHitMatch = null;
        double minHitDistance = Double.MAX_VALUE;
        long minHitVolume = Long.MAX_VALUE;

        Project bestInsideMatch = null;
        long minInsideVolume = Long.MAX_VALUE;

        String worldId = player.getWorld().getName();
        for (Project project : ProjectManager.getInstance().getProjectsNearPos(worldId, eye.getBlockX(), eye.getBlockZ(), 128)) {
            if (!project.getWorldId().equalsIgnoreCase(worldId)) continue;

            BoundingBox box = new BoundingBox(
                    project.getMin().getX(), project.getMin().getY(), project.getMin().getZ(),
                    project.getMax().getX() + 1.0, project.getMax().getY() + 1.0, project.getMax().getZ() + 1.0
            );

            long volume = project.getVolume();

            if (box.contains(start)) {
                if (volume < minInsideVolume) {
                    minInsideVolume = volume;
                    bestInsideMatch = project;
                }
            } else {
                RayTraceResult hit = box.rayTrace(start, dir, 100.0);
                if (hit != null) {
                    double dist = start.distanceSquared(hit.getHitPosition());
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

    public void tick(org.bukkit.Server server) {
        tickCounter = (tickCounter + 1) % 1000000;

        // Action bar for previewing players (every 10 ticks = 0.5s)
        if (tickCounter % 10 == 0) {
            for (Player player : server.getOnlinePlayers()) {
                if (PaperPreviewManager.getInstance().hasPreview(player.getUniqueId())) {
                    player.sendActionBar(Component.text(Lang.get("svcntrl.msg.you_are_in_preview_mode_type_s"), NamedTextColor.AQUA, TextDecoration.BOLD));
                }
            }
        }

        // Raycast selection checking (every 2 ticks = 0.1s)
        if (tickCounter % 2 == 0) {
            boolean isBusy = TaskScheduler.getInstance().hasActiveTasks();
            for (Player player : server.getOnlinePlayers()) {
                if (raycastPlayers.contains(player.getUniqueId())) {
                    Project lookedAt = getProjectLookingAt(player);
                    if (!isBusy) {
                        if (lookedAt != null) {
                            Component msg = Component.text(Lang.get("svcntrl.msg.looking_at"), NamedTextColor.GRAY)
                                    .append(Component.text(lookedAt.getName(), NamedTextColor.AQUA, TextDecoration.BOLD))
                                    .append(Component.text(Lang.get("svcntrl.msg.click_to_select"), NamedTextColor.YELLOW));
                            player.sendActionBar(msg);
                        } else {
                            Component msg = Component.text(Lang.get("svcntrl.msg.looking_at"), NamedTextColor.GRAY)
                                    .append(Component.text(Lang.get("svcntrl.msg.none"), NamedTextColor.DARK_GRAY));
                            player.sendActionBar(msg);
                        }
                    }
                }
            }
        }

        // Project boundary particles
        int freq = SvcntrlConfig.getInstance().outlineFrequencyTicks;
        if (freq <= 0) freq = 15;

        if (tickCounter % freq == 0) {
            for (Player player : server.getOnlinePlayers()) {
                boolean raycasting = raycastPlayers.contains(player.getUniqueId());
                if (raycasting) {
                    String[] pool = SvcntrlConfig.getInstance().raycastParticlePool;
                    String worldId = player.getWorld().getName();
                    for (Project project : ProjectManager.getInstance().getProjectsNearPos(worldId, player.getLocation().getBlockX(), player.getLocation().getBlockZ(), 128)) {
                        if (!project.getWorldId().equalsIgnoreCase(worldId)) continue;
                        int index = (project.getName().hashCode() & 0x7fffffff) % pool.length;
                        spawnOutlineParticles(player, project, pool[index]);
                    }
                } else if (outlinePlayers.contains(player.getUniqueId())) {
                    Project project = ProjectManager.getInstance().getActiveProject(player.getUniqueId());
                    if (project != null && project.getWorldId().equalsIgnoreCase(player.getWorld().getName())) {
                        spawnOutlineParticles(player, project, SvcntrlConfig.getInstance().outlineParticle);
                    }
                }
            }
        }
    }

    private void spawnOutlineParticles(Player player, Project project, String particleStr) {
        BlockPos pos1 = project.getCorner1();
        BlockPos pos2 = project.getCorner2();
        if (pos1 == null || pos2 == null) return;

        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        int maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        int maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        double lengthX = maxX - minX;
        double lengthY = maxY - minY;
        double lengthZ = maxZ - minZ;

        double maxParticlesPerEdge = 50.0;
        double stepX = Math.max(2.0, lengthX / maxParticlesPerEdge);
        double stepY = Math.max(2.0, lengthY / maxParticlesPerEdge);
        double stepZ = Math.max(2.0, lengthZ / maxParticlesPerEdge);

        Particle particle = Particle.FLAME;
        try {
            String name = particleStr.replace("minecraft:", "").toUpperCase(java.util.Locale.ROOT);
            particle = Particle.valueOf(name);
        } catch (Exception ignored) {}

        // Bottom and Top rects
        for (double x = minX; x <= maxX; x += stepX) {
            sendParticle(player, x, minY, minZ, particle);
            sendParticle(player, x, minY, maxZ, particle);
            sendParticle(player, x, maxY, minZ, particle);
            sendParticle(player, x, maxY, maxZ, particle);
        }
        for (double z = minZ; z <= maxZ; z += stepZ) {
            sendParticle(player, minX, minY, z, particle);
            sendParticle(player, maxX, minY, z, particle);
            sendParticle(player, minX, maxY, z, particle);
            sendParticle(player, maxX, maxY, z, particle);
        }
        // Vertical lines
        for (double y = minY; y <= maxY; y += stepY) {
            sendParticle(player, minX, y, minZ, particle);
            sendParticle(player, maxX, y, minZ, particle);
            sendParticle(player, minX, y, maxZ, particle);
            sendParticle(player, maxX, y, maxZ, particle);
        }
    }

    private void sendParticle(Player player, double x, double y, double z, Particle particle) {
        player.spawnParticle(particle, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }
}
