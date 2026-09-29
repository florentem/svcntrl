package com.svcntrl;

import com.svcntrl.util.Lang;
import com.svcntrl.command.SvcntrlCommands;
import com.svcntrl.data.ProjectManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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

    private static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
        runnable -> {
            Thread t = new Thread(runnable, "Svcntrl-Worker");
            t.setDaemon(true);
            return t;
        }
    );

    public static java.util.concurrent.ExecutorService getExecutor() {
        return EXECUTOR;
    }

    public static java.util.concurrent.CompletableFuture<Void> runAsync(Runnable runnable) {
        return java.util.concurrent.CompletableFuture.runAsync(runnable, EXECUTOR);
    }

    public static <U> java.util.concurrent.CompletableFuture<U> supplyAsync(java.util.function.Supplier<U> supplier) {
        return java.util.concurrent.CompletableFuture.supplyAsync(supplier, EXECUTOR);
    }

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
            PreviewManager.getInstance().tick(server);
            PendingCreateManager.getInstance().tick(server);
            com.svcntrl.core.ExportManager.tick();
        });

        // Clear pending creations and states on disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PendingCreateManager.getInstance().removePlayer(handler.player.getUUID());
            ProjectManager.getInstance().setActiveProject(handler.player.getUUID(), null);
            UXManager.getInstance().removePlayer(handler.player.getUUID());
            if (PreviewManager.getInstance().hasPreview(handler.player.getUUID())) {
                PreviewManager.getInstance().stopPreview(handler.player);
            }
        });

        // Block all interactions if player is in preview mode or setting positions
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide()) return true;
            if (UXManager.getInstance().isRaycasting(player.getUUID())) {
                player.sendOverlayMessage(Lang.translatable("svcntrl.msg.you_cannot_break_blocks_in_sel").withStyle(ChatFormatting.RED));
                return false;
            }
            if (PreviewManager.getInstance().hasPreview(player.getUUID())) {
                player.sendOverlayMessage(Lang.translatable("svcntrl.msg.you_cannot_break_blocks_while").withStyle(ChatFormatting.RED));
                PreviewManager.getInstance().resendBlock((net.minecraft.server.level.ServerPlayer) player, pos);
                return false;
            }
            if (isPosPreviewed(world, pos)) {
                player.sendOverlayMessage(Lang.translatable("svcntrl.msg.cannot_modify_area_a_preview_i").withStyle(ChatFormatting.RED));
                return false;
            }
            if (isPosLocked(world, pos)) {
                player.sendOverlayMessage(Lang.translatable("svcntrl.msg.cannot_modify_area_a_save_rest").withStyle(ChatFormatting.RED));
                return false;
            }
            return true;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (handleRaycastSelection(player)) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (handleRaycastSelection(player)) return InteractionResult.FAIL;

            if (!player.isSpectator() && hand == net.minecraft.world.InteractionHand.MAIN_HAND && hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                if (PendingCreateManager.getInstance().handleRightClick((net.minecraft.server.level.ServerPlayer) player, blockHit.getBlockPos())) {
                    return InteractionResult.FAIL; // Prevent block placement/interaction
                }
            }

            if (PreviewManager.getInstance().hasPreview(player.getUUID())) {
                player.sendOverlayMessage(Lang.translatable("svcntrl.msg.you_cannot_interact_while_prev").withStyle(ChatFormatting.RED));
                return InteractionResult.FAIL;
            }
            
            if (hitResult instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                if (isPosPreviewed(world, blockHit.getBlockPos())) {
                    player.sendOverlayMessage(Lang.translatable("svcntrl.msg.cannot_modify_area_a_preview_i").withStyle(ChatFormatting.RED));
                    return InteractionResult.FAIL;
                }
                if (isPosLocked(world, blockHit.getBlockPos())) {
                    player.sendSystemMessage(Lang.translatable("svcntrl.msg.cannot_modify_area_a_save_rest").withStyle(ChatFormatting.RED));
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClientSide()) return InteractionResult.PASS;
            if (handleRaycastSelection(player)) return InteractionResult.FAIL;

            if (!player.isSpectator() && hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                if (PendingCreateManager.getInstance().handleLeftClick((net.minecraft.server.level.ServerPlayer) player, pos)) {
                    return InteractionResult.FAIL;
                }
            }

            if (PreviewManager.getInstance().hasPreview(player.getUUID())) {
                PreviewManager.getInstance().resendBlock((net.minecraft.server.level.ServerPlayer) player, pos);
                return InteractionResult.FAIL;
            }
            
            if (isPosPreviewed(world, pos)) {
                return InteractionResult.FAIL;
            }
            
            if (isPosLocked(world, pos)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    private boolean handleRaycastSelection(net.minecraft.world.entity.player.Player player) {
        if (player.level().isClientSide()) return false;
        if (UXManager.getInstance().isRaycasting(player.getUUID())) {
            com.svcntrl.data.Project project = UXManager.getInstance().getProjectLookingAt((net.minecraft.server.level.ServerPlayer) player);
            if (project != null) {
                if (!project.isMember(player.getUUID()) && !player.level().getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()))) {
                    player.sendSystemMessage(Lang.translatable("svcntrl.msg.you_don_t_have_access_to_this").withStyle(ChatFormatting.RED));
                } else {
                    ProjectManager.getInstance().setActiveProject(player.getUUID(), project.getName());
                    player.sendSystemMessage(Lang.translatable("svcntrl.msg.selected_project").append(Component.literal(project.getName()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
                }
            } else {
                player.sendSystemMessage(Lang.translatable("svcntrl.msg.raycast_selection_cancelled_no").withStyle(ChatFormatting.RED));
            }
            UXManager.getInstance().setRaycasting(player.getUUID(), false);
            return true;
        }
        return false;
    }

    private boolean isPosPreviewed(net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos) {
        String worldId = world.dimension().identifier().toString();
        for (String projectName : PreviewManager.getInstance().getPreviewingProjects()) {
            com.svcntrl.data.Project p = com.svcntrl.data.ProjectManager.getInstance().getProject(projectName);
            if (p != null && p.getWorldId().equals(worldId) && p.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPosLocked(net.minecraft.world.level.Level world, net.minecraft.core.BlockPos pos) {
        String worldId = world.dimension().identifier().toString();
        for (com.svcntrl.data.Project p : com.svcntrl.data.ProjectManager.getInstance().getLockedProjects()) {
            if (p.getWorldId().equals(worldId) && p.contains(pos)) {
                return true;
            }
        }
        return false;
    }
}
