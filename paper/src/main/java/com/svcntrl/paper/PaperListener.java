package com.svcntrl.paper;

import com.svcntrl.config.SvcntrlConfig;
import com.svcntrl.core.PendingCreateManager;
import com.svcntrl.data.BlockPos;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import com.svcntrl.util.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PaperListener implements Listener {

    private boolean isPosPreviewed(String worldId, int x, int y, int z) {
        for (String projectName : PaperPreviewManager.getInstance().getPreviewingProjects()) {
            Project p = ProjectManager.getInstance().getProject(projectName);
            if (p != null && p.getWorldId().equalsIgnoreCase(worldId) && p.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPosLocked(String worldId, int x, int y, int z) {
        for (Project p : ProjectManager.getInstance().getLockedProjects()) {
            if (p.getWorldId().equalsIgnoreCase(worldId) && p.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        String worldName = block.getWorld().getName();

        if (PaperUXManager.getInstance().isRaycasting(player.getUniqueId())) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.you_cannot_break_blocks_in_sel"), NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        if (PaperPreviewManager.getInstance().hasPreview(player.getUniqueId())) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.you_cannot_break_blocks_while"), NamedTextColor.RED));
            PaperPreviewManager.getInstance().resendBlock(player, new BlockPos(x, y, z));
            event.setCancelled(true);
            return;
        }

        if (isPosPreviewed(worldName, x, y, z)) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.cannot_modify_area_a_preview_i"), NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        if (isPosLocked(worldName, x, y, z)) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.cannot_modify_area_a_save_rest"), NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        String worldName = block.getWorld().getName();

        if (PaperPreviewManager.getInstance().hasPreview(player.getUniqueId())) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.you_cannot_interact_while_prev"), NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        if (isPosPreviewed(worldName, x, y, z)) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.cannot_modify_area_a_preview_i"), NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        if (isPosLocked(worldName, x, y, z)) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.cannot_modify_area_a_save_rest"), NamedTextColor.RED));
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (PaperUXManager.getInstance().isRaycasting(player.getUniqueId())) {
            Project project = PaperUXManager.getInstance().getProjectLookingAt(player);
            if (project != null) {
                if (!project.isMember(player.getUniqueId()) && !player.hasPermission("svcntrl.admin")) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.you_don_t_have_access_to_this"), NamedTextColor.RED));
                } else {
                    ProjectManager.getInstance().setActiveProject(player.getUniqueId(), project.getName());
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.selected_project"), NamedTextColor.GREEN)
                            .append(Component.text(project.getName(), NamedTextColor.AQUA, TextDecoration.BOLD)));
                }
            } else {
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.raycast_selection_cancelled_no"), NamedTextColor.RED));
            }
            PaperUXManager.getInstance().setRaycasting(player.getUniqueId(), false);
            event.setCancelled(true);
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        BlockPos pos = new BlockPos(clicked.getX(), clicked.getY(), clicked.getZ());
        String worldName = clicked.getWorld().getName();

        if (action == Action.LEFT_CLICK_BLOCK) {
            if (PendingCreateManager.getInstance().hasPending(player.getUniqueId())) {
                if (PendingCreateManager.getInstance().setPos1(player.getUniqueId(), pos, worldName)) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.pos1_set", pos.toShortString(), worldName), NamedTextColor.GREEN));
                    checkCompletion(player);
                    event.setCancelled(true);
                    return;
                }
            }
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            if (PendingCreateManager.getInstance().hasPending(player.getUniqueId())) {
                if (PendingCreateManager.getInstance().setPos2(player.getUniqueId(), pos, worldName)) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.pos2_set", pos.toShortString(), worldName), NamedTextColor.GREEN));
                    checkCompletion(player);
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    private void checkCompletion(Player player) {
        PendingCreateManager.PendingCreate state = PendingCreateManager.getInstance().getPending(player.getUniqueId());
        if (state != null && state.pos1 != null && state.pos2 != null) {
            long dx = Math.abs(state.pos1.getX() - state.pos2.getX()) + 1;
            long dy = Math.abs(state.pos1.getY() - state.pos2.getY()) + 1;
            long dz = Math.abs(state.pos1.getZ() - state.pos2.getZ()) + 1;
            long volume = dx * dy * dz;
            int maxVol = SvcntrlConfig.getInstance().maxRegionVolume;

            if (volume > maxVol) {
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.area_too_large", maxVol, volume), NamedTextColor.RED));
                return;
            }

            Project project = new Project(
                    state.projectName,
                    player.getUniqueId(),
                    player.getName(),
                    state.pos1,
                    state.pos2,
                    state.dimension
            );

            boolean overlaps = false;
            for (Project p : ProjectManager.getInstance().getProjects()) {
                if (p.intersects(project)) {
                    overlaps = true;
                    break;
                }
            }

            PendingCreateManager.getInstance().removePlayer(player.getUniqueId());

            if (ProjectManager.getInstance().createProject(project)) {
                ProjectManager.getInstance().setActiveProject(player.getUniqueId(), project.getName());
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.project_created_success", state.projectName), NamedTextColor.GREEN));
                if (overlaps) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.warning_this_project_overlaps"), NamedTextColor.YELLOW));
                }
            } else {
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.failed_to_create_project_name"), NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PendingCreateManager.getInstance().removePlayer(player.getUniqueId());
        ProjectManager.getInstance().setActiveProject(player.getUniqueId(), null);
        PaperUXManager.getInstance().removePlayer(player.getUniqueId());
        if (PaperPreviewManager.getInstance().hasPreview(player.getUniqueId())) {
            PaperPreviewManager.getInstance().stopPreview(player);
        }
    }
}
