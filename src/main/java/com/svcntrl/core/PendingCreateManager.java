package com.svcntrl.core;

import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PendingCreateManager {

    private static final PendingCreateManager INSTANCE = new PendingCreateManager();

    private final Map<UUID, PendingCreate> pending = new ConcurrentHashMap<>();

    private PendingCreateManager() {}

    public static PendingCreateManager getInstance() {
        return INSTANCE;
    }

    public boolean hasPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    public void tick(net.minecraft.server.MinecraftServer server) {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> {
            if (now > entry.getValue().expiryTime) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player != null && !player.isDisconnected()) {
                    player.sendMessage(Text.translatable("svcntrl.msg.creation_timed_out", entry.getValue().projectName).formatted(Formatting.RED), false);
                }
                return true;
            }
            return false;
        });
    }

    public void startCreation(ServerPlayerEntity player, String projectName) {
        if (ProjectManager.getInstance().getProject(projectName) != null) {
            player.sendMessage(Text.translatable("svcntrl.msg.a_project_with_this_name_alrea").formatted(Formatting.RED), false);
            return;
        }

        pending.put(player.getUuid(), new PendingCreate(projectName, System.currentTimeMillis() + 60_000L));
        player.sendMessage(Text.literal("Started creation of project '" + projectName + "'.\n")
                .formatted(Formatting.GREEN)
                .append(Text.translatable("svcntrl.msg.left_right_click_blocks_to_set")
                        .formatted(Formatting.YELLOW))
                .append(Text.translatable("svcntrl.msg.you_have_1_minute_before_this")
                        .formatted(Formatting.GRAY)), false);
    }

    public boolean handleLeftClick(ServerPlayerEntity player, BlockPos pos) {
        PendingCreate state = pending.get(player.getUuid());
        if (state == null) return false;

        if (System.currentTimeMillis() > state.expiryTime) {
            pending.remove(player.getUuid());
            player.sendMessage(Text.translatable("svcntrl.msg.project_creation_timed_out").formatted(Formatting.RED), false);
            return false;
        }

        if (state.dimension != null && !state.dimension.equals(player.getWorld().getRegistryKey().getValue().toString())) {
            state.pos2 = null; // Reset pos2 if dimension changed
        }
        state.pos1 = pos;
        state.dimension = player.getWorld().getRegistryKey().getValue().toString();
        player.sendMessage(Text.translatable("svcntrl.msg.pos1_set", pos.toShortString(), state.dimension).formatted(Formatting.GREEN), false);
        checkCompletion(player, state);
        return true;
    }

    public boolean handleRightClick(ServerPlayerEntity player, BlockPos pos) {
        PendingCreate state = pending.get(player.getUuid());
        if (state == null) return false;

        if (System.currentTimeMillis() > state.expiryTime) {
            pending.remove(player.getUuid());
            player.sendMessage(Text.translatable("svcntrl.msg.project_creation_timed_out").formatted(Formatting.RED), false);
            return false;
        }

        if (state.dimension != null && !state.dimension.equals(player.getWorld().getRegistryKey().getValue().toString())) {
            state.pos1 = null; // Reset pos1 if dimension changed
        }
        state.pos2 = pos;
        state.dimension = player.getWorld().getRegistryKey().getValue().toString();
        player.sendMessage(Text.translatable("svcntrl.msg.pos2_set", pos.toShortString(), state.dimension).formatted(Formatting.GREEN), false);
        checkCompletion(player, state);
        return true;
    }

    private void checkCompletion(ServerPlayerEntity player, PendingCreate state) {
        if (state.pos1 != null && state.pos2 != null) {
            long dx = Math.abs(state.pos1.getX() - state.pos2.getX()) + 1;
            long dy = Math.abs(state.pos1.getY() - state.pos2.getY()) + 1;
            long dz = Math.abs(state.pos1.getZ() - state.pos2.getZ()) + 1;
            long volume = dx * dy * dz;
            int maxVol = com.svcntrl.config.SvcntrlConfig.getInstance().maxRegionVolume;
            if (volume > maxVol) {
                player.sendMessage(Text.translatable("svcntrl.msg.area_too_large", maxVol, volume)
                        .formatted(Formatting.RED), false);
                return;
            }

            String dim = player.getWorld().getRegistryKey().getValue().toString();
            int minX = Math.min(state.pos1.getX(), state.pos2.getX());
            int maxX = Math.max(state.pos1.getX(), state.pos2.getX());
            int minY = Math.min(state.pos1.getY(), state.pos2.getY());
            int maxY = Math.max(state.pos1.getY(), state.pos2.getY());
            int minZ = Math.min(state.pos1.getZ(), state.pos2.getZ());
            int maxZ = Math.max(state.pos1.getZ(), state.pos2.getZ());

            // Intersections are now allowed, but we warn the user
            Project project = new Project(
                    state.projectName,
                    player.getUuid(),
                    player.getName().getString(),
                    state.pos1,
                    state.pos2,
                    player.getWorld().getRegistryKey().getValue().toString()
            );

            boolean overlaps = false;
            for (Project p : com.svcntrl.data.ProjectManager.getInstance().getProjects()) {
                if (p.intersects(project)) {
                    overlaps = true;
                    break;
                }
            }

            pending.remove(player.getUuid());

            if (ProjectManager.getInstance().createProject(project)) {
                ProjectManager.getInstance().setActiveProject(player.getUuid(), project.getName());
                player.sendMessage(Text.translatable("svcntrl.msg.project_created_success", state.projectName)
                        .formatted(Formatting.GREEN), false);
                if (overlaps) {
                    player.sendMessage(Text.translatable("svcntrl.msg.warning_this_project_overlaps")
                            .formatted(Formatting.YELLOW), false);
                }
            } else {
                player.sendMessage(Text.translatable("svcntrl.msg.failed_to_create_project_name")
                        .formatted(Formatting.RED), false);
            }
        }
    }

    public void removePlayer(UUID uuid) {
        pending.remove(uuid);
    }

    private static class PendingCreate {
        String projectName;
        long expiryTime;
        BlockPos pos1;
        BlockPos pos2;
        String dimension;

        PendingCreate(String projectName, long expiryTime) {
            this.projectName = projectName;
            this.expiryTime = expiryTime;
        }
    }
}
