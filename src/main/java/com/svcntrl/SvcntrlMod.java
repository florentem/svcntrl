package com.svcntrl;

import com.svcntrl.command.SvcntrlCommands;
import com.svcntrl.data.ProjectManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.svcntrl.core.PreviewManager;
import com.svcntrl.core.TaskScheduler;
import com.svcntrl.core.UXManager;
import com.svcntrl.core.PendingCreateManager;
import com.svcntrl.config.SvcntrlConfig;

/**
 * Main entry point for svcntrl — a server-side version control mod for Minecraft builds.
 */
public class SvcntrlMod implements net.fabricmc.api.ModInitializer {

    public static final String MOD_ID = "svcntrl";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[svcntrl] Initializing Svcntrl on the server...");

        SvcntrlConfig.load();

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            SvcntrlCommands.register(dispatcher, registryAccess);
        });

        // Load project data when server starts
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ProjectManager.getInstance().loadProjects(server);
            LOGGER.info("[svcntrl] Loaded {} project(s).", ProjectManager.getInstance().getProjectCount());
        });

        // Save project data when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ProjectManager.getInstance().saveProjects();
            TaskScheduler.getInstance().clear();
            LOGGER.info("[svcntrl] Project data saved.");
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TaskScheduler.getInstance().tick();
            UXManager.getInstance().tick(server);
            PendingCreateManager.getInstance().tick(server);
            com.svcntrl.core.ExportManager.tick();
        });

        // Clear pending creations and states on disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PendingCreateManager.getInstance().removePlayer(handler.player.getUuid());
            ProjectManager.getInstance().setActiveProject(handler.player.getUuid(), null);
            UXManager.getInstance().removePlayer(handler.player.getUuid());
            if (PreviewManager.getInstance().hasPreview(handler.player.getUuid())) {
                PreviewManager.getInstance().stopPreview(handler.player);
            }
        });

        // Block all interactions if player is in preview mode or setting positions
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient()) return true;
            if (UXManager.getInstance().isRaycasting(player.getUuid())) {
                player.sendMessage(Text.translatable("svcntrl.msg.you_cannot_break_blocks_in_sel").formatted(Formatting.RED), true);
                return false;
            }
            if (PreviewManager.getInstance().hasPreview(player.getUuid())) {
                player.sendMessage(Text.translatable("svcntrl.msg.you_cannot_break_blocks_while").formatted(Formatting.RED), true);
                PreviewManager.getInstance().resendBlock((net.minecraft.server.network.ServerPlayerEntity) player, pos);
                return false;
            }
            if (isPosPreviewed(world, pos)) {
                player.sendMessage(Text.translatable("svcntrl.msg.cannot_modify_area_a_preview_i").formatted(Formatting.RED), true);
                return false;
            }
            if (isPosLocked(world, pos)) {
                player.sendMessage(Text.translatable("svcntrl.msg.cannot_modify_area_a_save_rest").formatted(Formatting.RED), true);
                return false;
            }
            return true;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (handleRaycastSelection(player)) return ActionResult.FAIL;
            return ActionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (handleRaycastSelection(player)) return ActionResult.FAIL;

            if (!player.isSpectator() && hand == net.minecraft.util.Hand.MAIN_HAND && hitResult instanceof net.minecraft.util.hit.BlockHitResult blockHit) {
                if (PendingCreateManager.getInstance().handleRightClick((net.minecraft.server.network.ServerPlayerEntity) player, blockHit.getBlockPos())) {
                    return ActionResult.FAIL; // Prevent block placement/interaction
                }
            }

            if (PreviewManager.getInstance().hasPreview(player.getUuid())) {
                player.sendMessage(Text.translatable("svcntrl.msg.you_cannot_interact_while_prev").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }
            
            if (hitResult instanceof net.minecraft.util.hit.BlockHitResult blockHit) {
                if (isPosPreviewed(world, blockHit.getBlockPos())) {
                    player.sendMessage(Text.translatable("svcntrl.msg.cannot_modify_area_a_preview_i").formatted(Formatting.RED), true);
                    return ActionResult.FAIL;
                }
                if (isPosLocked(world, blockHit.getBlockPos())) {
                    player.sendMessage(Text.translatable("svcntrl.msg.cannot_modify_area_a_save_rest").formatted(Formatting.RED), true);
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (handleRaycastSelection(player)) return ActionResult.FAIL;

            if (PreviewManager.getInstance().hasPreview(player.getUuid())) {
                PreviewManager.getInstance().resendBlock((net.minecraft.server.network.ServerPlayerEntity) player, pos);
                return ActionResult.FAIL;
            }
            
            if (isPosPreviewed(world, pos)) {
                return ActionResult.FAIL;
            }
            
            if (isPosLocked(world, pos)) {
                return ActionResult.FAIL;
            }
            
            if (PendingCreateManager.getInstance().handleLeftClick((net.minecraft.server.network.ServerPlayerEntity) player, pos)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    private boolean handleRaycastSelection(net.minecraft.entity.player.PlayerEntity player) {
        if (player.getWorld().isClient()) return false;
        if (UXManager.getInstance().isRaycasting(player.getUuid())) {
            com.svcntrl.data.Project project = UXManager.getInstance().getProjectLookingAt((net.minecraft.server.network.ServerPlayerEntity) player);
            if (project != null) {
                if (!project.isMember(player.getUuid()) && !player.hasPermissionLevel(2)) {
                    player.sendMessage(Text.translatable("svcntrl.msg.you_don_t_have_access_to_this").formatted(Formatting.RED), false);
                } else {
                    ProjectManager.getInstance().setActiveProject(player.getUuid(), project.getName());
                    player.sendMessage(Text.translatable("svcntrl.msg.selected_project").append(Text.literal(project.getName()).formatted(Formatting.AQUA, Formatting.BOLD)), false);
                }
            } else {
                player.sendMessage(Text.translatable("svcntrl.msg.raycast_selection_cancelled_no").formatted(Formatting.RED), false);
            }
            UXManager.getInstance().setRaycasting(player.getUuid(), false);
            return true;
        }
        return false;
    }

    private boolean isPosPreviewed(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
        String worldId = world.getRegistryKey().getValue().toString();
        for (String projectName : PreviewManager.getInstance().getPreviewingProjects()) {
            com.svcntrl.data.Project p = com.svcntrl.data.ProjectManager.getInstance().getProject(projectName);
            if (p != null && p.getWorldId().equals(worldId) && p.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPosLocked(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
        String worldId = world.getRegistryKey().getValue().toString();
        for (com.svcntrl.data.Project p : com.svcntrl.data.ProjectManager.getInstance().getLockedProjects()) {
            if (p.getWorldId().equals(worldId) && p.contains(pos)) {
                return true;
            }
        }
        return false;
    }
}
