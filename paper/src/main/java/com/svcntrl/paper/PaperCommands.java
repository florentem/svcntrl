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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class PaperCommands implements CommandExecutor, TabCompleter {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM-dd HH:mm");

    public static boolean hasPerm(CommandSender sender, String node) {
        if (sender == null || sender.isOp() || sender.hasPermission("svcntrl.admin")) {
            return true;
        }
        return sender.hasPermission(node)
                || sender.hasPermission("svcntrl.command")
                || sender.hasPermission("svcntrl");
    }

    private boolean checkPerm(Player player, String node) {
        if (hasPerm(player, node)) {
            return true;
        }
        player.sendMessage(Component.text(Lang.get("svcntrl.msg.you_don_t_have_permission_to"), NamedTextColor.RED));
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!hasPerm(sender, "svcntrl.command.reload") && !sender.isOp()) {
                sender.sendMessage(Component.text(Lang.get("svcntrl.msg.you_don_t_have_permission_to"), NamedTextColor.RED));
                return true;
            }
            SvcntrlConfig.load();
            sender.sendMessage(Component.text(Lang.get("svcntrl.msg.configuration_reloaded_success"), NamedTextColor.GREEN));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text(Lang.get("svcntrl.msg.only_players_can_use_this_com"), NamedTextColor.RED));
            return true;
        }

        switch (sub) {
            case "project" -> handleProject(player, args);
            case "branch" -> handleBranch(player, args);
            case "outline" -> {
                if (checkPerm(player, "svcntrl.command.outline")) handleOutline(player);
            }
            case "save" -> {
                if (checkPerm(player, "svcntrl.command.save")) handleSave(player, args);
            }
            case "restore" -> {
                if (checkPerm(player, "svcntrl.command.restore")) handleRestore(player, args);
            }
            case "log" -> {
                if (checkPerm(player, "svcntrl.command.log")) handleLog(player, args);
            }
            case "preview" -> {
                if (checkPerm(player, "svcntrl.command.preview")) handlePreview(player, args);
            }
            case "pos1" -> {
                if (checkPerm(player, "svcntrl.command.pos")) handlePos1(player);
            }
            case "pos2" -> {
                if (checkPerm(player, "svcntrl.command.pos")) handlePos2(player);
            }
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("--- svcntrl Commands ---", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("/svcntrl project create <name> - Create a new project", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl project list - List your projects", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl project select <name> - Select active project", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl project tp <name> - Teleport to project", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl project raycast - Select by looking", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl project remove <name> [force] - Delete project", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl branch create <name> - Create branch", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl branch checkout <name> - Switch branch", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl branch list - List branches", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl save [description] - Create manual snapshot", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl restore <id> [--nosave] [--exclude-intersections] - Restore snapshot", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl log [page] - View snapshot history", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl preview start <id> / stop - Preview snapshot", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/svcntrl outline - Toggle boundary particles", NamedTextColor.YELLOW));
    }

    private void handleProject(Player player, String[] args) {
        if (args.length < 2) {
            sendHelp(player);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> {
                if (!checkPerm(player, "svcntrl.command.project.create")) return;
                if (args.length < 3) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.specify_project_name"), NamedTextColor.RED));
                    return;
                }
                String name = args[2];
                if (ProjectManager.getInstance().getProject(name) != null) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.a_project_with_this_name_alrea"), NamedTextColor.RED));
                    return;
                }
                PendingCreateManager.getInstance().startCreation(player.getUniqueId(), name);
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.started_creation", name), NamedTextColor.GREEN));
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.left_right_click_blocks_to_set"), NamedTextColor.YELLOW));
            }
            case "list" -> {
                if (!checkPerm(player, "svcntrl.command.project.list")) return;
                Collection<Project> projects = ProjectManager.getInstance().getProjectsForPlayer(player.getUniqueId());
                if (projects.isEmpty()) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.you_don_t_have_any_projects"), NamedTextColor.GRAY));
                    return;
                }
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.projects_header"), NamedTextColor.GOLD, TextDecoration.BOLD));
                for (Project p : projects) {
                    player.sendMessage(Component.text("- " + p.getName() + " (branch: " + p.getCurrentBranchName() + ")", NamedTextColor.AQUA));
                }
            }
            case "select" -> {
                if (!checkPerm(player, "svcntrl.command.project.select")) return;
                if (args.length < 3) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.specify_project_name"), NamedTextColor.RED));
                    return;
                }
                Project p = ProjectManager.getInstance().getProject(args[2]);
                if (p == null || (!p.isMember(player.getUniqueId()) && !player.hasPermission("svcntrl.admin"))) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.project_not_found"), NamedTextColor.RED));
                    return;
                }
                ProjectManager.getInstance().setActiveProject(player.getUniqueId(), p.getName());
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.selected_project"), NamedTextColor.GREEN)
                        .append(Component.text(p.getName(), NamedTextColor.AQUA, TextDecoration.BOLD)));
            }
            case "tp" -> {
                if (!checkPerm(player, "svcntrl.command.project.tp")) return;
                if (args.length < 3) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.specify_project_name"), NamedTextColor.RED));
                    return;
                }
                Project p = ProjectManager.getInstance().getProject(args[2]);
                if (p == null || (!p.isMember(player.getUniqueId()) && !player.hasPermission("svcntrl.admin"))) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.project_not_found"), NamedTextColor.RED));
                    return;
                }
                World world = Bukkit.getWorld(p.getWorldId());
                if (world == null) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.world_not_loaded"), NamedTextColor.RED));
                    return;
                }
                Location loc = new Location(world, (p.getMin().getX() + p.getMax().getX()) / 2.0, p.getMax().getY() + 1.0, (p.getMin().getZ() + p.getMax().getZ()) / 2.0);
                player.teleport(loc);
                ProjectManager.getInstance().setActiveProject(player.getUniqueId(), p.getName());
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.teleported_to_project", p.getName()), NamedTextColor.GREEN));
            }
            case "raycast" -> {
                if (!checkPerm(player, "svcntrl.command.project.raycast")) return;
                PaperUXManager.getInstance().setRaycasting(player.getUniqueId(), true);
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.raycast_selection_mode_enabled"), NamedTextColor.GREEN));
            }
            case "remove" -> {
                if (!checkPerm(player, "svcntrl.command.project.remove")) return;
                if (args.length < 3) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.specify_project_name"), NamedTextColor.RED));
                    return;
                }
                String name = args[2];
                Project p = ProjectManager.getInstance().getProject(name);
                if (p == null || (!p.isOwner(player.getUniqueId()) && !player.hasPermission("svcntrl.admin"))) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.project_not_found"), NamedTextColor.RED));
                    return;
                }
                boolean force = args.length >= 4 && args[3].equalsIgnoreCase("force");
                if (!force) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.type_svcntrl_project_remove_name_fo", name), NamedTextColor.YELLOW));
                    return;
                }
                ProjectManager.getInstance().removeProject(name);
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.project_deleted_success", name), NamedTextColor.GREEN));
            }
            case "trust" -> {
                if (!checkPerm(player, "svcntrl.command.project.trust")) return;
                if (args.length < 3) {
                    player.sendMessage(Component.text("Specify player name", NamedTextColor.RED));
                    return;
                }
                Project p = ProjectManager.getInstance().getActiveProject(player.getUniqueId());
                if (p == null || (!p.isOwner(player.getUniqueId()) && !player.hasPermission("svcntrl.admin"))) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.only_owner_can_trust"), NamedTextColor.RED));
                    return;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.player_not_found"), NamedTextColor.RED));
                    return;
                }
                p.addMember(target.getUniqueId());
                ProjectManager.getInstance().saveProject(p);
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.trusted_player", target.getName(), p.getName()), NamedTextColor.GREEN));
            }
            case "untrust" -> {
                if (!checkPerm(player, "svcntrl.command.project.untrust")) return;
                if (args.length < 3) {
                    player.sendMessage(Component.text("Specify player name", NamedTextColor.RED));
                    return;
                }
                Project p = ProjectManager.getInstance().getActiveProject(player.getUniqueId());
                if (p == null || (!p.isOwner(player.getUniqueId()) && !player.hasPermission("svcntrl.admin"))) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.only_owner_can_trust"), NamedTextColor.RED));
                    return;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.player_not_found"), NamedTextColor.RED));
                    return;
                }
                p.removeMember(target.getUniqueId());
                ProjectManager.getInstance().saveProject(p);
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.untrusted_player", target.getName(), p.getName()), NamedTextColor.GREEN));
            }
        }
    }

    private void handleBranch(Player player, String[] args) {
        Project p = ProjectManager.getInstance().getActiveProject(player.getUniqueId());
        if (p == null) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.no_active_project_selected"), NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sendHelp(player);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> {
                if (!checkPerm(player, "svcntrl.command.branch.create")) return;
                if (args.length < 3) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.specify_branch_name"), NamedTextColor.RED));
                    return;
                }
                String name = args[2];
                if (p.hasBranch(name)) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.branch_already_exists"), NamedTextColor.RED));
                    return;
                }
                p.getOrCreateBranch(name);
                ProjectManager.getInstance().saveProject(p);
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.branch_created", name), NamedTextColor.GREEN));
            }
            case "checkout" -> {
                if (!checkPerm(player, "svcntrl.command.branch.checkout")) return;
                if (args.length < 3) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.specify_branch_name"), NamedTextColor.RED));
                    return;
                }
                String name = args[2];
                if (!p.hasBranch(name)) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.branch_not_found"), NamedTextColor.RED));
                    return;
                }
                p.setCurrentBranchName(name);
                ProjectManager.getInstance().saveProject(p);
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.switched_to_branch", name), NamedTextColor.GREEN));
            }
            case "list" -> {
                if (!checkPerm(player, "svcntrl.command.branch.list")) return;
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.branches_for_project") + p.getName(), NamedTextColor.GOLD, TextDecoration.BOLD));
                for (Project.Branch b : p.getBranches()) {
                    String prefix = b.getName().equalsIgnoreCase(p.getCurrentBranchName()) ? "* " : "  ";
                    player.sendMessage(Component.text(prefix + b.getName(), NamedTextColor.AQUA));
                }
            }
            case "delete" -> {
                if (!checkPerm(player, "svcntrl.command.branch.delete")) return;
                if (args.length < 3) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.specify_branch_name"), NamedTextColor.RED));
                    return;
                }
                String name = args[2];
                if (name.equalsIgnoreCase("main") || name.equalsIgnoreCase(p.getCurrentBranchName())) {
                    player.sendMessage(Component.text(Lang.get("svcntrl.msg.cannot_delete_active_or_main_bra"), NamedTextColor.RED));
                    return;
                }
                p.deleteBranch(name);
                ProjectManager.getInstance().saveProject(p);
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.branch_deleted", name), NamedTextColor.GREEN));
            }
        }
    }

    private void handleOutline(Player player) {
        boolean active = PaperUXManager.getInstance().toggleOutline(player.getUniqueId());
        if (active) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.project_outline_enabled"), NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.project_outline_disabled"), NamedTextColor.YELLOW));
        }
    }

    private void handleSave(Player player, String[] args) {
        Project p = ProjectManager.getInstance().getActiveProject(player.getUniqueId());
        if (p == null) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.no_active_project_selected"), NamedTextColor.RED));
            return;
        }
        if (p.isLocked()) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.project_or_an_overlapping_proj"), NamedTextColor.RED));
            return;
        }

        StringBuilder desc = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) desc.append(" ");
            desc.append(args[i]);
        }
        String description = desc.toString().trim();

        String branchName = p.getCurrentBranchName();
        int snapshotId = p.addManualSnapshot(branchName, description, player.getUniqueId(), player.getName());
        ProjectManager.getInstance().saveProject(p);

        player.sendMessage(Component.text(Lang.get("svcntrl.msg.saving_snapshot", snapshotId, branchName), NamedTextColor.YELLOW));
        PaperAreaSerializer.saveAreaAsync(player, player.getWorld(), p, branchName, "manual", snapshotId, () -> {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.snapshot_saved_success", snapshotId), NamedTextColor.GREEN));
        }, err -> {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.save_failed_cancelled") + ": " + err, NamedTextColor.RED));
        });
    }

    private void handleRestore(Player player, String[] args) {
        Project p = ProjectManager.getInstance().getActiveProject(player.getUniqueId());
        if (p == null) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.no_active_project_selected"), NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.specify_snapshot_id"), NamedTextColor.RED));
            return;
        }

        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.invalid_snapshot_id"), NamedTextColor.RED));
            return;
        }

        boolean excludeIntersections = false;
        for (String a : args) {
            if (a.equalsIgnoreCase("--exclude-intersections")) excludeIntersections = true;
        }

        String branch = p.getCurrentBranchName();
        player.sendMessage(Component.text(Lang.get("svcntrl.msg.restoring_snapshot", id, branch), NamedTextColor.YELLOW));
        PaperAreaSerializer.restoreArea(player, player.getWorld(), p, branch, "manual", id, excludeIntersections, () -> {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.restore_completed"), NamedTextColor.GREEN));
        }, () -> {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.restore_failed_cancelled"), NamedTextColor.RED));
        });
    }

    private void handleLog(Player player, String[] args) {
        Project p = ProjectManager.getInstance().getActiveProject(player.getUniqueId());
        if (p == null) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.no_active_project_selected"), NamedTextColor.RED));
            return;
        }

        Project.Branch branch = p.getBranch(p.getCurrentBranchName());
        if (branch == null || branch.getManualSnapshots().isEmpty()) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.no_snapshots_found"), NamedTextColor.GRAY));
            return;
        }

        player.sendMessage(Component.text(Lang.get("svcntrl.msg.history_header", branch.getName()), NamedTextColor.GOLD, TextDecoration.BOLD));
        for (Project.SnapshotMeta meta : branch.getManualSnapshots()) {
            String time = DATE_FORMAT.format(new Date(meta.getTimestamp()));
            String desc = meta.getDescription().isEmpty() ? "(no description)" : meta.getDescription();
            player.sendMessage(Component.text("#" + meta.getId() + " [" + time + "] " + meta.getAuthorName() + ": " + desc, NamedTextColor.AQUA));
        }
    }

    private void handlePreview(Player player, String[] args) {
        Project p = ProjectManager.getInstance().getActiveProject(player.getUniqueId());
        if (p == null) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.no_active_project_selected"), NamedTextColor.RED));
            return;
        }

        if (args.length < 2) {
            sendHelp(player);
            return;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("stop")) {
            PaperPreviewManager.getInstance().stopPreview(player);
            return;
        }

        if (action.equals("start")) {
            if (args.length < 3) {
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.specify_snapshot_id"), NamedTextColor.RED));
                return;
            }
            int id;
            try {
                id = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.invalid_snapshot_id"), NamedTextColor.RED));
                return;
            }
            com.svcntrl.nbt.NbtCompound root = PaperAreaSerializer.readSnapshot(p, p.getCurrentBranchName(), "manual", id);
            if (root == null) {
                player.sendMessage(Component.text(Lang.get("svcntrl.msg.snapshot_not_found"), NamedTextColor.RED));
                return;
            }
            // Parse preview blocks
            Map<BlockPos, org.bukkit.block.data.BlockData> previewBlocks = new HashMap<>();
            int[] blockData = root.getIntArray("BlockData");
            com.svcntrl.nbt.NbtList pList = root.getListOrEmpty("Palette");
            org.bukkit.block.data.BlockData[] palette = new org.bukkit.block.data.BlockData[pList.size()];
            for (int i = 0; i < pList.size(); i++) {
                com.svcntrl.nbt.NbtCompound pe = pList.getCompoundOrEmpty(i);
                String dataStr = pe.getString("BlockDataString", "");
                try {
                    palette[i] = !dataStr.isEmpty() ? Bukkit.createBlockData(dataStr) : Bukkit.createBlockData(pe.getString("BlockId", "minecraft:air"));
                } catch (Exception ex) {
                    palette[i] = Bukkit.createBlockData("minecraft:air");
                }
            }

            int minX = root.getInt("MinX", 0);
            int minY = root.getInt("MinY", 0);
            int minZ = root.getInt("MinZ", 0);
            int width = root.getInt("MaxX", 0) - minX + 1;
            int height = root.getInt("MaxY", 0) - minY + 1;
            int length = root.getInt("MaxZ", 0) - minZ + 1;

            for (int idx = 0; idx < blockData.length; idx++) {
                int rz = idx / (width * height);
                int rem = idx % (width * height);
                int ry = rem / width;
                int rx = rem % width;
                int pIdx = blockData[idx];
                org.bukkit.block.data.BlockData bData = (pIdx >= 0 && pIdx < palette.length) ? palette[pIdx] : Bukkit.createBlockData("minecraft:air");
                previewBlocks.put(new BlockPos(minX + rx, minY + ry, minZ + rz), bData);
            }

            PaperPreviewManager.getInstance().startPreview(player, p, previewBlocks, p.getCurrentBranchName(), "manual", id);
        }
    }

    private void handlePos1(Player player) {
        Location loc = player.getLocation();
        BlockPos pos = new BlockPos(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        if (PendingCreateManager.getInstance().setPos1(player.getUniqueId(), pos, loc.getWorld().getName())) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.pos1_set", pos.toShortString(), loc.getWorld().getName()), NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("No pending project creation! Use /svcntrl project create <name> first.", NamedTextColor.RED));
        }
    }

    private void handlePos2(Player player) {
        Location loc = player.getLocation();
        BlockPos pos = new BlockPos(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        if (PendingCreateManager.getInstance().setPos2(player.getUniqueId(), pos, loc.getWorld().getName())) {
            player.sendMessage(Component.text(Lang.get("svcntrl.msg.pos2_set", pos.toShortString(), loc.getWorld().getName()), NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("No pending project creation! Use /svcntrl project create <name> first.", NamedTextColor.RED));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            if (hasPerm(sender, "svcntrl.command.project") || hasPerm(sender, "svcntrl.command.project.list") || hasPerm(sender, "svcntrl.command.project.create")) list.add("project");
            if (hasPerm(sender, "svcntrl.command.branch") || hasPerm(sender, "svcntrl.command.branch.list") || hasPerm(sender, "svcntrl.command.branch.create")) list.add("branch");
            if (hasPerm(sender, "svcntrl.command.save")) list.add("save");
            if (hasPerm(sender, "svcntrl.command.restore")) list.add("restore");
            if (hasPerm(sender, "svcntrl.command.log")) list.add("log");
            if (hasPerm(sender, "svcntrl.command.preview")) list.add("preview");
            if (hasPerm(sender, "svcntrl.command.outline")) list.add("outline");
            if (hasPerm(sender, "svcntrl.command.pos")) {
                list.add("pos1");
                list.add("pos2");
            }
            if (hasPerm(sender, "svcntrl.command.reload") || sender.isOp()) list.add("reload");
            list.add("help");
            return list.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("project")) {
                List<String> list = new ArrayList<>();
                if (hasPerm(sender, "svcntrl.command.project.create")) list.add("create");
                if (hasPerm(sender, "svcntrl.command.project.list")) list.add("list");
                if (hasPerm(sender, "svcntrl.command.project.select")) list.add("select");
                if (hasPerm(sender, "svcntrl.command.project.tp")) list.add("tp");
                if (hasPerm(sender, "svcntrl.command.project.raycast")) list.add("raycast");
                if (hasPerm(sender, "svcntrl.command.project.remove")) list.add("remove");
                if (hasPerm(sender, "svcntrl.command.project.trust")) list.add("trust");
                if (hasPerm(sender, "svcntrl.command.project.untrust")) list.add("untrust");
                return list.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("branch")) {
                List<String> list = new ArrayList<>();
                if (hasPerm(sender, "svcntrl.command.branch.create")) list.add("create");
                if (hasPerm(sender, "svcntrl.command.branch.checkout")) list.add("checkout");
                if (hasPerm(sender, "svcntrl.command.branch.list")) list.add("list");
                if (hasPerm(sender, "svcntrl.command.branch.delete")) list.add("delete");
                return list.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("preview")) {
                if (hasPerm(sender, "svcntrl.command.preview")) {
                    return Arrays.asList("start", "stop")
                            .stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                }
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("project") && (args[1].equalsIgnoreCase("select") || args[1].equalsIgnoreCase("tp") || args[1].equalsIgnoreCase("remove"))) {
            return ProjectManager.getInstance().getAllProjects().stream().map(Project::getName)
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
