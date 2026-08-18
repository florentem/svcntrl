package com.svcntrl.core;

import com.svcntrl.util.Lang;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

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
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null && !player.hasDisconnected()) {
                    player.sendSystemMessage(Lang.translatable("svcntrl.msg.creation_timed_out", entry.getValue().projectName).withStyle(ChatFormatting.RED));
                }
                return true;
            }
            return false;
        });
    }

    public void startCreation(ServerPlayer player, String projectName) {
        if (ProjectManager.getInstance().getProject(projectName) != null) {
            player.sendSystemMessage(Lang.translatable("svcntrl.msg.a_project_with_this_name_alrea").withStyle(ChatFormatting.RED));
            return;
        }

        pending.put(player.getUUID(), new PendingCreate(projectName, System.currentTimeMillis() + 60_000L));
        player.sendSystemMessage(Lang.translatable("svcntrl.msg.started_creation", projectName).withStyle(ChatFormatting.GREEN));
        player.sendSystemMessage(Lang.translatable("svcntrl.msg.left_right_click_blocks_to_set").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Lang.translatable("svcntrl.msg.you_have_1_minute_before_this").withStyle(ChatFormatting.GRAY));
    }

    public boolean handleLeftClick(ServerPlayer player, BlockPos pos) {
        PendingCreate state = pending.get(player.getUUID());
        if (state == null) return false;

        if (System.currentTimeMillis() > state.expiryTime) {
            pending.remove(player.getUUID());
            player.sendSystemMessage(Lang.translatable("svcntrl.msg.project_creation_timed_out").withStyle(ChatFormatting.RED));
            return false;
        }

        if (state.dimension != null && !state.dimension.equals(player.level().dimension().identifier().toString())) {
            state.pos2 = null; // Reset pos2 if dimension changed
        }
        state.pos1 = pos;
        state.dimension = player.level().dimension().identifier().toString();
        player.sendSystemMessage(Lang.translatable("svcntrl.msg.pos1_set", pos.toShortString(), state.dimension).withStyle(ChatFormatting.GREEN));
        checkCompletion(player, state);
        return true;
    }

    public boolean handleRightClick(ServerPlayer player, BlockPos pos) {
        PendingCreate state = pending.get(player.getUUID());
        if (state == null) return false;

        if (System.currentTimeMillis() > state.expiryTime) {
            pending.remove(player.getUUID());
            player.sendSystemMessage(Lang.translatable("svcntrl.msg.project_creation_timed_out").withStyle(ChatFormatting.RED));
            return false;
        }

        if (state.dimension != null && !state.dimension.equals(player.level().dimension().identifier().toString())) {
            state.pos1 = null; // Reset pos1 if dimension changed
        }
        state.pos2 = pos;
        state.dimension = player.level().dimension().identifier().toString();
        player.sendSystemMessage(Lang.translatable("svcntrl.msg.pos2_set", pos.toShortString(), state.dimension).withStyle(ChatFormatting.GREEN));
        checkCompletion(player, state);
        return true;
    }

    private void checkCompletion(ServerPlayer player, PendingCreate state) {
        if (state.pos1 != null && state.pos2 != null) {
            long dx = Math.abs(state.pos1.getX() - state.pos2.getX()) + 1;
            long dy = Math.abs(state.pos1.getY() - state.pos2.getY()) + 1;
            long dz = Math.abs(state.pos1.getZ() - state.pos2.getZ()) + 1;
            long volume = dx * dy * dz;
            int maxVol = com.svcntrl.config.SvcntrlConfig.getInstance().maxRegionVolume;
            if (volume > maxVol) {
                player.sendSystemMessage(Lang.translatable("svcntrl.msg.area_too_large", maxVol, volume)
                        .withStyle(ChatFormatting.RED), false);
                return;
            }

            String dim = player.level().dimension().identifier().toString();
            int minX = Math.min(state.pos1.getX(), state.pos2.getX());
            int maxX = Math.max(state.pos1.getX(), state.pos2.getX());
            int minY = Math.min(state.pos1.getY(), state.pos2.getY());
            int maxY = Math.max(state.pos1.getY(), state.pos2.getY());
            int minZ = Math.min(state.pos1.getZ(), state.pos2.getZ());
            int maxZ = Math.max(state.pos1.getZ(), state.pos2.getZ());

            // Intersections are now allowed, but we warn the user
            Project project = new Project(
                    state.projectName,
                    player.getUUID(),
                    player.getName().getString(),
                    state.pos1,
                    state.pos2,
                    player.level().dimension().identifier().toString()
            );

            boolean overlaps = false;
            for (Project p : com.svcntrl.data.ProjectManager.getInstance().getProjects()) {
                if (p.intersects(project)) {
                    overlaps = true;
                    break;
                }
            }

            pending.remove(player.getUUID());

            if (ProjectManager.getInstance().createProject(project)) {
                ProjectManager.getInstance().setActiveProject(player.getUUID(), project.getName());
                player.sendSystemMessage(Lang.translatable("svcntrl.msg.project_created_success", state.projectName)
                        .withStyle(ChatFormatting.GREEN), false);
                if (overlaps) {
                    player.sendSystemMessage(Lang.translatable("svcntrl.msg.warning_this_project_overlaps")
                            .withStyle(ChatFormatting.YELLOW), false);
                }
            } else {
                player.sendSystemMessage(Lang.translatable("svcntrl.msg.failed_to_create_project_name")
                        .withStyle(ChatFormatting.RED));
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
