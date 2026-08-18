package com.svcntrl.command;

import com.svcntrl.util.Lang;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.svcntrl.core.AreaSerializer;
import com.svcntrl.core.ExportManager;
import com.svcntrl.core.PreviewManager;
import com.svcntrl.core.UXManager;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.svcntrl.core.PendingCreateManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class SvcntrlCommands {

    private static final java.time.format.DateTimeFormatter DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(literal("svcntrl")
                // /svcntrl upload
                .then(literal("upload").requires(s -> s.getPlayer() != null && requirePerm("svcntrl.command.export").test(s))
                    .then(literal("always").executes(ctx -> executeUpload(ctx.getSource(), "always")))
                    .then(literal("never").executes(ctx -> executeUpload(ctx.getSource(), "never")))
                    .then(literal("reset").executes(ctx -> executeUpload(ctx.getSource(), "reset")))
                    .then(literal("yes").requires(s -> com.svcntrl.core.ExportManager.hasPendingUpload(s.getPlayer().getUUID()))
                        .executes(ctx -> executeUpload(ctx.getSource(), "yes")))
                    .then(literal("no").requires(s -> com.svcntrl.core.ExportManager.hasPendingUpload(s.getPlayer().getUUID()))
                        .executes(ctx -> executeUpload(ctx.getSource(), "no")))
                )

                // /svcntrl project ...
                .then(literal("project")
                    .then(literal("list").requires(requirePerm("svcntrl.command.project.list"))
                        .executes(ctx -> executeProjectList(ctx.getSource(), 1)))
                    .then(literal("create").requires(requirePerm("svcntrl.command.project.create"))
                        .then(argument("name", StringArgumentType.word())
                            .executes(ctx -> executeCreate(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                    .then(literal("remove").requires(requirePerm("svcntrl.command.project.remove"))
                        .then(argument("name", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestProjects(ctx, builder))
                            .executes(ctx -> executeRemoveProject(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))
                            .then(literal("force")
                                .executes(ctx -> executeRemoveProject(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)))))
                    .then(literal("trust").requires(s -> requirePerm("svcntrl.command.project.trust").test(s) && isOwnerOrAdmin(s))
                        .then(argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerNames(), builder))
                            .executes(ctx -> executeTrust(ctx.getSource(), StringArgumentType.getString(ctx, "player"), true))))
                    .then(literal("untrust").requires(s -> requirePerm("svcntrl.command.project.untrust").test(s) && isOwnerOrAdmin(s))
                        .then(argument("player", StringArgumentType.word())
                            .suggests(SvcntrlCommands::suggestMembers)
                            .executes(ctx -> executeTrust(ctx.getSource(), StringArgumentType.getString(ctx, "player"), false))))
                    .then(literal("tp").requires(requirePerm("svcntrl.command.project.tp"))
                        .then(argument("name", StringArgumentType.word())
                            .suggests(SvcntrlCommands::suggestProjects)
                            .executes(ctx -> executeTeleport(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                    .then(literal("select").requires(requirePerm("svcntrl.command.project.select"))
                        .then(argument("name", StringArgumentType.word())
                            .suggests(SvcntrlCommands::suggestProjects)
                            .executes(ctx -> executeSelect(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                    .then(literal("raycast").requires(requirePerm("svcntrl.command.project.raycast"))
                        .executes(ctx -> executeSelectRaycast(ctx.getSource())))
                )

                // /svcntrl branch ...
                .then(literal("branch")
                    .then(literal("list").requires(requirePerm("svcntrl.command.branch.list"))
                        .executes(ctx -> executeBranchList(ctx.getSource(), 1)))
                    .then(literal("create").requires(requirePerm("svcntrl.command.branch.create"))
                        .then(withNoSave(argument("name", StringArgumentType.word()),
                            ctx -> executeBranchCreate(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false),
                            ctx -> executeBranchCreate(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true))))
                    .then(literal("checkout").requires(requirePerm("svcntrl.command.branch.checkout"))
                        .then(withNoSave(argument("name", StringArgumentType.word()).suggests(SvcntrlCommands::suggestBranches),
                            ctx -> executeBranchCheckout(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false),
                            ctx -> executeBranchCheckout(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true))))
                    .then(literal("delete").requires(s -> requirePerm("svcntrl.command.branch.delete").test(s) && isOwnerOrAdmin(s))
                        .then(argument("name", StringArgumentType.word())
                            .suggests(SvcntrlCommands::suggestBranches)
                            .executes(ctx -> executeBranchDelete(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))
                            .then(literal("force")
                                .executes(ctx -> executeBranchDelete(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)))))
                )

                // Quick actions (defaults to current project & branch)
                .then(literal("outline").requires(requirePerm("svcntrl.command.outline"))
                    .executes(ctx -> executeOutline(ctx.getSource())))

                .then(literal("reload").requires(source -> source.getPlayer() == null || source.getServer().getPlayerList().isOp(source.getPlayer().nameAndId()))
                    .executes(ctx -> executeReload(ctx.getSource())))


                .then(literal("save").requires(requirePerm("svcntrl.command.save"))
                    .executes(ctx -> executeSave(ctx.getSource(), ""))
                    .then(argument("description", StringArgumentType.greedyString())
                        .executes(ctx -> executeSave(ctx.getSource(), StringArgumentType.getString(ctx, "description")))))

                .then(literal("log").requires(requirePerm("svcntrl.command.log"))
                    .executes(ctx -> executeLog(ctx.getSource(), "manual", 1, null))
                    .then(argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeLog(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "page"), null))
                        .then(argument("filter", StringArgumentType.string())
                            .executes(ctx -> executeLog(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "page"), StringArgumentType.getString(ctx, "filter"))))
                    )
                    .then(argument("filter", StringArgumentType.string())
                        .executes(ctx -> executeLog(ctx.getSource(), "manual", 1, StringArgumentType.getString(ctx, "filter")))
                    )
                    .then(literal("auto")
                        .executes(ctx -> executeLog(ctx.getSource(), "auto", 1, null))
                        .then(argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> executeLog(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "page"), null))
                            .then(argument("filter", StringArgumentType.string())
                                .executes(ctx -> executeLog(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "page"), StringArgumentType.getString(ctx, "filter"))))
                        )
                        .then(argument("filter", StringArgumentType.string())
                            .executes(ctx -> executeLog(ctx.getSource(), "auto", 1, StringArgumentType.getString(ctx, "filter")))
                        )
                    )
                    .then(literal("manual")
                        .executes(ctx -> executeLog(ctx.getSource(), "manual", 1, null))
                        .then(argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> executeLog(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "page"), null))
                            .then(argument("filter", StringArgumentType.string())
                                .executes(ctx -> executeLog(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "page"), StringArgumentType.getString(ctx, "filter"))))
                        )
                        .then(argument("filter", StringArgumentType.string())
                            .executes(ctx -> executeLog(ctx.getSource(), "manual", 1, StringArgumentType.getString(ctx, "filter")))
                        )
                    )
                )
                
                .then(literal("deletesave").requires(s -> requirePerm("svcntrl.command.snapshot.delete").test(s) && isOwnerOrAdmin(s))
                    .then(literal("manual")
                        .then(argument("id", IntegerArgumentType.integer(1))
                            .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                            .executes(ctx -> executeSnapshotDelete(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id")))))
                    .then(literal("auto")
                        .then(argument("id", IntegerArgumentType.integer(1))
                            .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "auto"))
                            .executes(ctx -> executeSnapshotDelete(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"))))))

                .then(literal("restore").requires(requirePerm("svcntrl.command.restore"))
                    .then(literal("patch")
                        .then(argument("target_id", IntegerArgumentType.integer(1))
                            .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                            .then(argument("base_id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                                .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), true, true))
                                            )
                                        )
                            )
                        )
                        .then(literal("auto")
                            .then(argument("target_id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "auto"))
                                .then(argument("base_id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "auto"))
                                    .executes(ctx -> executeRestorePatch(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestorePatch(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestorePatch(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestorePatch(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestorePatch(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), true, true))
                                            )
                                        )
                                )
                            )
                        )
                        .then(literal("manual")
                            .then(argument("target_id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                                .then(argument("base_id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                                    .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestorePatch(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), true, true))
                                            )
                                        )
                                )
                            )
                        )
                        .then(literal("cross")
                            .then(argument("target_branch", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestBranches(ctx, builder))
                                .then(argument("target_id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "target_branch"))
                                    .then(argument("base_branch", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestBranches(ctx, builder))
                                        .then(argument("base_id", IntegerArgumentType.integer(1))
                                            .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "base_branch"))
                                            .executes(ctx -> executeRestorePatchCross(ctx.getSource(), "manual", StringArgumentType.getString(ctx, "target_branch"), IntegerArgumentType.getInteger(ctx, "target_id"), StringArgumentType.getString(ctx, "base_branch"), IntegerArgumentType.getInteger(ctx, "base_id"), false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestorePatchCross(ctx.getSource(), "manual", StringArgumentType.getString(ctx, "target_branch"), IntegerArgumentType.getInteger(ctx, "target_id"), StringArgumentType.getString(ctx, "base_branch"), IntegerArgumentType.getInteger(ctx, "base_id"), true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestorePatchCross(ctx.getSource(), "manual", StringArgumentType.getString(ctx, "target_branch"), IntegerArgumentType.getInteger(ctx, "target_id"), StringArgumentType.getString(ctx, "base_branch"), IntegerArgumentType.getInteger(ctx, "base_id"), true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestorePatchCross(ctx.getSource(), "manual", StringArgumentType.getString(ctx, "target_branch"), IntegerArgumentType.getInteger(ctx, "target_id"), StringArgumentType.getString(ctx, "base_branch"), IntegerArgumentType.getInteger(ctx, "base_id"), false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestorePatchCross(ctx.getSource(), "manual", StringArgumentType.getString(ctx, "target_branch"), IntegerArgumentType.getInteger(ctx, "target_id"), StringArgumentType.getString(ctx, "base_branch"), IntegerArgumentType.getInteger(ctx, "base_id"), true, true))
                                            )
                                        )
                                        )
                                    )
                                )
                            )
                        )
                    )
                    .then(argument("id", IntegerArgumentType.integer(1))
                        .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                        .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, true, true))
                                            )
                                        )
                    )
                    .then(literal("auto")
                        .then(argument("id", IntegerArgumentType.integer(1))
                            .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "auto"))
                            .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), null, false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), null, true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), null, true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), null, false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), null, true, true))
                                            )
                                        )
                        )
                    )
                    .then(literal("manual")
                        .then(argument("id", IntegerArgumentType.integer(1))
                            .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                            .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null, true, true))
                                            )
                                        )
                        )
                    )
                    .then(literal("cross")
                        .then(argument("branch", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestBranches(ctx, builder))
                            .then(argument("id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "branch"))
                                .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), true, true))
                                            )
                                        )
                            )
                            .then(literal("auto")
                                .then(argument("id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "auto", "branch"))
                                    .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), true, true))
                                            )
                                        )
                                )
                            )
                            .then(literal("manual")
                                .then(argument("id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "branch"))
                                    .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), false, false))
                                        .then(literal("--nosave")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), true, false))
                                            .then(literal("--exclude-intersections")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), true, true))
                                            )
                                        )
                                        .then(literal("--exclude-intersections")
                                            .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), false, true))
                                            .then(literal("--nosave")
                                                .executes(ctx -> executeRestore(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch"), true, true))
                                            )
                                        )
                                )
                            )
                        )
                    )
                )
                .then(literal("help")
                    .executes(ctx -> executeHelp(ctx.getSource()))
                )
                
                .then(literal("pos1").requires(s -> requirePerm("svcntrl.command.pos").test(s) && s.getPlayer() != null && com.svcntrl.core.PendingCreateManager.getInstance().hasPending(s.getPlayer().getUUID()))
                    .executes(ctx -> executePos1(ctx.getSource())))
                .then(literal("pos2").requires(s -> requirePerm("svcntrl.command.pos").test(s) && s.getPlayer() != null && com.svcntrl.core.PendingCreateManager.getInstance().hasPending(s.getPlayer().getUUID()))
                    .executes(ctx -> executePos2(ctx.getSource())))

                .then(literal("export").requires(requirePerm("svcntrl.command.export"))
                    .then(literal("all")
                        .executes(ctx -> executeExportAll(ctx.getSource()))
                    )
                    .then(literal("diff")
                        .then(argument("target_id", IntegerArgumentType.integer(1))
                            .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                            .then(argument("base_id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                                .executes(ctx -> executeExportDiff(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), null))
                            )
                        )
                        .then(literal("auto")
                            .then(argument("target_id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "auto"))
                                .then(argument("base_id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "auto"))
                                    .executes(ctx -> executeExportDiff(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), null))
                                )
                            )
                        )
                        .then(literal("manual")
                            .then(argument("target_id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                                .then(argument("base_id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                                    .executes(ctx -> executeExportDiff(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "target_id"), IntegerArgumentType.getInteger(ctx, "base_id"), null))
                                )
                            )
                        )
                        .then(literal("cross")
                            .then(argument("target_branch", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestBranches(ctx, builder))
                                .then(argument("target_id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "target_branch"))
                                    .then(argument("base_branch", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestBranches(ctx, builder))
                                        .then(argument("base_id", IntegerArgumentType.integer(1))
                                            .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "base_branch"))
                                            .executes(ctx -> executeExportDiffCross(ctx.getSource(), "manual", StringArgumentType.getString(ctx, "target_branch"), IntegerArgumentType.getInteger(ctx, "target_id"), StringArgumentType.getString(ctx, "base_branch"), IntegerArgumentType.getInteger(ctx, "base_id")))
                                        )
                                    )
                                )
                            )
                        )
                    )
                    .then(argument("id", IntegerArgumentType.integer(1))
                        .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                        .executes(ctx -> executeExport(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null))
                    )
                    .then(literal("auto")
                        .then(argument("id", IntegerArgumentType.integer(1))
                            .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "auto"))
                            .executes(ctx -> executeExport(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), null))
                        )
                    )
                    .then(literal("manual")
                        .then(argument("id", IntegerArgumentType.integer(1))
                            .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                            .executes(ctx -> executeExport(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null))
                        )
                    )
                    .then(literal("cross")
                        .then(argument("branch", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestBranches(ctx, builder))
                            .then(argument("id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "branch"))
                                .executes(ctx -> executeExport(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch")))
                            )
                            .then(literal("auto")
                                .then(argument("id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "auto", "branch"))
                                    .executes(ctx -> executeExport(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch")))
                                )
                            )
                            .then(literal("manual")
                                .then(argument("id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "branch"))
                                    .executes(ctx -> executeExport(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch")))
                                )
                            )
                        )
                    )
                )

                .then(literal("preview").requires(requirePerm("svcntrl.command.preview"))
                    .then(literal("start")
                        .then(argument("id", IntegerArgumentType.integer(1))
                            .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                            .executes(ctx -> executePreview(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null))
                        )
                        .then(literal("auto")
                            .then(argument("id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "auto"))
                                .executes(ctx -> executePreview(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), null))
                            )
                        )
                        .then(literal("manual")
                            .then(argument("id", IntegerArgumentType.integer(1))
                                .suggests((ctx, builder) -> suggestSnapshotIds(ctx, builder, "manual"))
                                .executes(ctx -> executePreview(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), null))
                            )
                        )
                        .then(literal("cross")
                            .then(argument("branch", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestBranches(ctx, builder))
                                .then(argument("id", IntegerArgumentType.integer(1))
                                    .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "branch"))
                                    .executes(ctx -> executePreview(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch")))
                                )
                                .then(literal("auto")
                                    .then(argument("id", IntegerArgumentType.integer(1))
                                        .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "auto", "branch"))
                                        .executes(ctx -> executePreview(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch")))
                                    )
                                )
                                .then(literal("manual")
                                    .then(argument("id", IntegerArgumentType.integer(1))
                                        .suggests((ctx, builder) -> suggestCrossSnapshotIds(ctx, builder, "manual", "branch"))
                                        .executes(ctx -> executePreview(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "id"), StringArgumentType.getString(ctx, "branch")))
                                    )
                                )
                            )
                        )
                    )
                    .then(literal("stop")
                        .executes(ctx -> executePreviewStop(ctx.getSource()))
                    )
                )
        );
    }
    private static CompletableFuture<Suggestions> suggestProjects(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return net.minecraft.commands.SharedSuggestionProvider.suggest(
                ProjectManager.getInstance().getAllProjects().stream().map(Project::getName), builder);
    }

    private static CompletableFuture<Suggestions> suggestSnapshotIds(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder, String category) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
            if (project != null) {
                Project.Branch branch = project.getBranch(project.getCurrentBranchName());
                if (branch != null) {
                    List<Project.SnapshotMeta> history = "auto".equalsIgnoreCase(category) ? branch.getAutoSnapshots() : branch.getManualSnapshots();
                    List<String> ids = history.stream().map(meta -> String.valueOf(meta.getId())).toList();
                    return net.minecraft.commands.SharedSuggestionProvider.suggest(ids, builder);
                }
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestCrossSnapshotIds(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder, String category, String branchArg) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
            if (project != null) {
                String branchName;
                try {
                    branchName = StringArgumentType.getString(ctx, branchArg);
                } catch (IllegalArgumentException e) {
                    branchName = project.getCurrentBranchName();
                }
                Project.Branch branch = project.getBranch(branchName);
                if (branch != null) {
                    List<Project.SnapshotMeta> history = "auto".equalsIgnoreCase(category) ? branch.getAutoSnapshots() : branch.getManualSnapshots();
                    List<String> ids = history.stream().map(meta -> String.valueOf(meta.getId())).toList();
                    return net.minecraft.commands.SharedSuggestionProvider.suggest(ids, builder);
                }
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestMembers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
            if (project != null) {
                List<String> names = project.getMembers().stream()
                        .map(uuid -> ctx.getSource().getServer().services().nameToIdCache().get(uuid))
                        .filter(java.util.Optional::isPresent)
                        .map(opt -> opt.get().name())
                        .toList();
                return net.minecraft.commands.SharedSuggestionProvider.suggest(names, builder);
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestBranches(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
            if (project != null) {
                List<String> names = project.getBranches().stream().map(Project.Branch::getName).toList();
                return net.minecraft.commands.SharedSuggestionProvider.suggest(names, builder);
            }
        }
        return builder.buildFuture();
    }

    private static ServerLevel getProjectWorld(CommandSourceStack source, Project project) {
        for (ServerLevel world : source.getServer().getAllLevels()) {
            if (world.dimension().identifier().toString().equals(project.getWorldId())) {
                return world;
            }
        }
        source.sendFailure(Lang.translatable("svcntrl.msg.world_for_project_not_found"));
        return null;
    }



    private static int executeHelp(CommandSourceStack source) {
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_commands").withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.projects").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_project_create_name_cr").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.project_select").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.project_raycast").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.project_tp").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.project_trust").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_project_remove_name_fo").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.project_list").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.snapshots").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_save_desc_create_a_man").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.log").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_restore_manual_auto_id").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.restore_patch").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.restore_patch_cross").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.warning_restore_immediate").withStyle(ChatFormatting.RED), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.deletesave").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.preview").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.preview_start").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.preview_stop").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.export").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_export_id_export_snaps").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.export_diff").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.export_all").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.upload").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.branches").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_branch_create_name_nos").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_branch_checkout_name_n").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_branch_list_delete_man").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.other").withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.outline").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_pos1_pos2_set_position").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.help.reload").withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int executePos1(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        PendingCreateManager.getInstance().handleLeftClick(player, player.blockPosition());
        return 1;
    }

    private static int executePos2(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        PendingCreateManager.getInstance().handleRightClick(player, player.blockPosition());
        return 1;
    }

    private static int executeProjectList(CommandSourceStack source, int page) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        List<Project> projects = ProjectManager.getInstance().getProjectsForPlayer(player.getUUID());
        if (projects.isEmpty()) {
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.no_projects").withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        
        int perPage = 5;
        int totalPages = (int) Math.ceil((double) projects.size() / perPage);
        int finalPage = Math.max(1, Math.min(page, totalPages));
        int start = (finalPage - 1) * perPage;
        int end = Math.min(start + perPage, projects.size());
        
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.projects_page", finalPage, totalPages).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
        for (int i = start; i < end; i++) {
            Project p = projects.get(i);
            String role = p.isOwner(player.getUUID()) ? "Owner" : "Member";
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.project_list_item", p.getName(), role).withStyle(ChatFormatting.GREEN), false);
        }
        return 1;
    }

    private static boolean isValidName(String name) {
        return name != null && name.matches("^[a-zA-Z0-9_-]{1,32}$") && !name.equals(".") && !name.equals("..");
    }

    private static int executeCreate(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Lang.translatable("svcntrl.msg.this_command_can_only_be_used")); return 0; }
        if (!isValidName(name)) { source.sendFailure(Lang.translatable("svcntrl.msg.invalid_name_use_only_letters")); return 0; }
        PendingCreateManager.getInstance().startCreation(player, name);
        return 1;
    }

    private static int executeTeleport(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getProject(name);
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.project_not_found_name", name)); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }

        ServerLevel world = getProjectWorld(source, project);
        if (world == null) return 0;

        BlockPos min = project.getMin();
        BlockPos max = project.getMax();
        double centerX = min.getX() + (max.getX() - min.getX()) / 2.0;
        double centerZ = min.getZ() + (max.getZ() - min.getZ()) / 2.0;
        double y = max.getY() + 1.0;
        player.teleportTo(world, centerX, y, centerZ, java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class), player.getYRot(), player.getXRot(), true);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.teleported_to_project", name).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int executeRemoveProject(CommandSourceStack source, String name, boolean force) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getProject(name);
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.project_not_found")); return 0; }
        if (!project.isOwner(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.only_the_project_owner_or_admi")); return 0; }
        if (!force) {
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.remove_project_confirm", name).withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        if (project.isLocked()) {
            source.sendFailure(Lang.translatable("svcntrl.msg.cannot_delete_project_an_opera"));
            return 0;
        }
        com.svcntrl.core.PreviewManager.getInstance().stopPreviewForProject(source.getServer(), name);
        ProjectManager.getInstance().removeProject(name);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.project_was_permanently_deleted", name).withStyle(ChatFormatting.RED), true);
        return 1;
    }

    private static int executeSelectRaycast(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        UXManager.getInstance().setRaycasting(player.getUUID(), true);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.raycast_selection_mode_enabled").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int executeSelect(CommandSourceStack source, String name) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getProject(name);
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.project_not_found_name2", name)); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        ProjectManager.getInstance().setActiveProject(player.getUUID(), name);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.active_project_set_to").withStyle(ChatFormatting.GREEN).append(Component.literal(name).withStyle(ChatFormatting.GOLD)), false);
        resyncCommands(player);
        return 1;
    }

    private static int executeOutline(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        boolean active = UXManager.getInstance().toggleOutline(player.getUUID());
        source.sendSuccess(() -> Lang.translatable(active ? "svcntrl.msg.outline_enabled" : "svcntrl.msg.outline_disabled").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int executeTrust(CommandSourceStack source, String playerName, boolean add) {
        ServerPlayer sender = source.getPlayer();
        if (sender == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(sender.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        java.util.Optional<net.minecraft.server.players.NameAndId> profileOpt = source.getServer().services().nameToIdCache().get(playerName);
        if (profileOpt.isEmpty()) { source.sendFailure(Lang.translatable("svcntrl.msg.player_not_found")); return 0; }
        UUID targetUuid = profileOpt.get().id();
        if (add) {
            if (project.addMember(targetUuid)) {
                ProjectManager.getInstance().saveProject(project);
                source.sendSuccess(() -> Lang.translatable("svcntrl.msg.added_to_project", playerName).withStyle(ChatFormatting.GREEN), false);
            } else {
                source.sendFailure(Lang.translatable("svcntrl.msg.already_in_project", playerName));
            }
        } else {
            if (project.removeMember(targetUuid)) {
                ProjectManager.getInstance().saveProject(project);
                source.sendSuccess(() -> Lang.translatable("svcntrl.msg.removed_from_project", playerName).withStyle(ChatFormatting.GREEN), false);
            } else {
                source.sendFailure(Lang.translatable("svcntrl.msg.not_in_project", playerName));
            }
        }
        return 1;
    }

    private static int executeBranchList(CommandSourceStack source, int page) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.branches_for_project").append(Component.literal(project.getName()).withStyle(ChatFormatting.AQUA)), false);
        for (Project.Branch b : project.getBranches()) {
            boolean isCurrent = b.getName().equals(project.getCurrentBranchName());
            source.sendSuccess(() -> Lang.translatable(isCurrent ? "svcntrl.msg.branch_list_item_current" : "svcntrl.msg.branch_list_item", b.getName())
                    .withStyle(isCurrent ? ChatFormatting.GREEN : ChatFormatting.WHITE), false);
        }
        return 1;
    }

    private static int executeSave(CommandSourceStack source, String description) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }
        
        ServerLevel world = getProjectWorld(source, project);
        if (world == null) return 0;

        String branchName = project.getCurrentBranchName();

        if (description.isEmpty()) {
            description = "Manual save";
        }

        int snapshotId = project.addManualSnapshot(branchName, description, player.getUUID(), player.getName().getString());
        
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.saving_project").append(Component.literal(project.getName()).withStyle(ChatFormatting.AQUA)), false);
        
        AreaSerializer.saveAreaAsync(player, world, project, branchName, "manual", snapshotId, () -> {
            ProjectManager.getInstance().saveProject(project);
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.project_saved_snapshot_id").withStyle(ChatFormatting.GREEN).append(Component.literal(String.valueOf(snapshotId)).withStyle(ChatFormatting.GOLD)), false);
        }, error -> {
            rollbackSnapshot(project, branchName, snapshotId, false);
                    source.sendFailure(Lang.translatable("svcntrl.msg.failed_to_save", error));
        });

        return 1;
    }

    private static int executeBranchCreate(CommandSourceStack source, String nameArg, boolean noSave) {
        String name = nameArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        if (!isValidName(name)) { source.sendFailure(Lang.translatable("svcntrl.msg.invalid_branch_name_use_only_l")); return 0; }
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }
        if (project.hasBranch(name)) { source.sendFailure(Lang.translatable("svcntrl.msg.branch_already_exists")); return 0; }
        
        ServerLevel world = getProjectWorld(source, project);
        if (world == null) return 0;
        
        project.getOrCreateBranch(name);
        ProjectManager.getInstance().saveProject(project);
        
        String fallbackBranch = project.getCurrentBranchName();
        Runnable createInitialCommit = () -> {
            project.setCurrentBranchName(name);
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.branch_created_saving", name).withStyle(ChatFormatting.YELLOW), false);
            int manualId = project.addManualSnapshot(name, "Initial commit for branch " + name, player.getUUID(), player.getName().getString());
            AreaSerializer.saveAreaAsync(player, world, project, name, "manual", manualId, () -> {
                source.sendSuccess(() -> Lang.translatable("svcntrl.msg.branch_state_saved").withStyle(ChatFormatting.GREEN), false);
                ProjectManager.getInstance().saveProject(project);
            }, err -> {
                project.setCurrentBranchName(fallbackBranch);
                rollbackSnapshot(project, name, manualId, true);
                source.sendFailure(Lang.translatable("svcntrl.msg.failed_initial_commit", err));
            });
        };

        if (!noSave && com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnBranchCreate) {
            String currentBranch = project.getCurrentBranchName();
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.saving_before_branch_create", currentBranch).withStyle(ChatFormatting.YELLOW), false);
            int currentAutoId = project.addAutoSnapshot(currentBranch, "Auto-save before creating branch " + name, player.getUUID(), player.getName().getString());
            AreaSerializer.saveAreaAsync(player, world, project, currentBranch, "auto", currentAutoId, () -> {
                project.trimAutoSnapshots(currentBranch);
                createInitialCommit.run();
            }, err -> {
                rollbackSnapshot(project, currentBranch, currentAutoId, true);
                source.sendFailure(Lang.translatable("svcntrl.msg.failed_current_branch_save", err));
            });
        } else {
            createInitialCommit.run();
        }
        resyncCommands(player);
        return 1;
    }

    private static int executeBranchCheckout(CommandSourceStack source, String nameArg, boolean noSave) {
        String name = nameArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        if (!isValidName(name)) { source.sendFailure(Lang.translatable("svcntrl.msg.invalid_branch_name_use_only_l")); return 0; }
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        if (!project.hasBranch(name)) { source.sendFailure(Lang.translatable("svcntrl.msg.branch_not_found")); return 0; }
        if (project.getCurrentBranchName().equals(name)) { source.sendFailure(Lang.translatable("svcntrl.msg.already_on_branch", name)); return 0; }
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }
        
        ServerLevel world = getProjectWorld(source, project);
        if (world == null) return 0;
        
        Runnable onCheckout = () -> {
            Project.Branch newBranch = project.getBranch(name);
            int restoreId = -1;
            String category = "auto";
            
            long manualTime = -1;
            int manualId = -1;
            if (!newBranch.getManualSnapshots().isEmpty()) {
                Project.SnapshotMeta manual = newBranch.getManualSnapshots().get(newBranch.getManualSnapshots().size() - 1);
                manualTime = manual.getTimestamp();
                manualId = manual.getId();
            }
            
            long autoTime = -1;
            int autoId = -1;
            if (!newBranch.getAutoSnapshots().isEmpty()) {
                Project.SnapshotMeta auto = newBranch.getAutoSnapshots().get(newBranch.getAutoSnapshots().size() - 1);
                autoTime = auto.getTimestamp();
                autoId = auto.getId();
            }
            
            if (manualTime > -1) {
                restoreId = manualId;
                category = "manual";
            } else if (autoTime > -1) {
                restoreId = autoId;
                category = "auto";
            }
            
            if (restoreId == -1) {
                project.setCurrentBranchName(name);
                ProjectManager.getInstance().saveProject(project);
                source.sendSuccess(() -> Lang.translatable("svcntrl.msg.checked_out_empty", name).withStyle(ChatFormatting.GREEN), false);
                return;
            }
            
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.restoring_branch", name).withStyle(ChatFormatting.YELLOW), false);
            boolean success = AreaSerializer.restoreArea(player, world, project, name, category, restoreId, false, () -> {
                project.setCurrentBranchName(name);
                ProjectManager.getInstance().saveProject(project);
            }, null);
            if (!success) {
                source.sendFailure(Lang.translatable("svcntrl.msg.failed_to_load_branch_data"));
            }
        };

        if (!noSave && com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnBranchSwitch) {
            String oldBranch = project.getCurrentBranchName();
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.saving_current_state", oldBranch).withStyle(ChatFormatting.YELLOW), false);
            
            int autoId = project.addAutoSnapshot(oldBranch, "Auto-save before checkout to " + name, player.getUUID(), player.getName().getString());
            AreaSerializer.saveAreaAsync(player, world, project, oldBranch, "auto", autoId, () -> {
                project.trimAutoSnapshots(oldBranch);
                onCheckout.run();
            }, err -> {
                rollbackSnapshot(project, oldBranch, autoId, true);
                    source.sendFailure(Lang.translatable("svcntrl.msg.failed_save_branch_state", err));
            });
        } else {
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.checkout_no_save_warning").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false);
            onCheckout.run();
        }
        resyncCommands(player);
        return 1;
    }

    private static int executeBranchDelete(CommandSourceStack source, String nameArg, boolean force) {
        if (!force) {
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.remove_branch_confirm", nameArg).withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        String name = nameArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        if (!isValidName(name)) { source.sendFailure(Lang.translatable("svcntrl.msg.invalid_branch_name_use_only_l")); return 0; }
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }
        if (project.getCurrentBranchName().equals(name)) { source.sendFailure(Lang.translatable("svcntrl.msg.cannot_delete_current_branch")); return 0; }
        if (!project.hasBranch(name)) { source.sendFailure(Lang.translatable("svcntrl.msg.branch_not_found")); return 0; }
        
        project.deleteBranch(name);
        ProjectManager.getInstance().deleteBranchDir(project, name);
        ProjectManager.getInstance().saveProject(project);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.branch_deleted", name).withStyle(ChatFormatting.GREEN), false);
        resyncCommands(player);
        return 1;
    }

    private static int executeSnapshotDelete(CommandSourceStack source, String category, int id) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }

        Project.Branch branch = project.getBranch(project.getCurrentBranchName());
        java.util.List<Project.SnapshotMeta> snapshots = category.equals("manual") ? branch.getManualSnapshots() : branch.getAutoSnapshots();
        
        // Project guarantees snapshots are appended in monotonically increasing ID order
        int left = 0, right = snapshots.size() - 1;
        boolean found = false;
        while (left <= right) {
            int mid = left + (right - left) / 2;
            int midId = snapshots.get(mid).getId();
            if (midId == id) {
                found = true;
                break;
            } else if (midId < id) {
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        
        if (!found) {
            source.sendFailure(Lang.translatable("svcntrl.msg.snapshot_not_found"));
            return 0;
        }
        
        java.nio.file.Path snapshotPath = ProjectManager.getInstance().getSnapshotPath(project, branch.getName(), category, id);
        
        if (category.equals("manual")) {
            branch.removeManualSnapshot(id);
        } else {
            branch.removeAutoSnapshot(id);
        }
        
        ProjectManager.getInstance().saveProject(project);
        
        com.svcntrl.SvcntrlMod.runAsync(() -> {
            try {
                java.nio.file.Files.deleteIfExists(snapshotPath);
            } catch (java.io.IOException e) {
                com.svcntrl.SvcntrlMod.LOGGER.error("Failed to delete snapshot file", e);
            }
        });
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.snapshot_deleted", String.valueOf(id), category).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }


    private static int executeLog(CommandSourceStack source, String category, int page, String filter) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;

        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }

        Project.Branch branch = project.getBranch(project.getCurrentBranchName());
        List<Project.SnapshotMeta> rawSnapshots = "auto".equalsIgnoreCase(category) ? branch.getAutoSnapshots() : branch.getManualSnapshots();

        List<Project.SnapshotMeta> snapshots = rawSnapshots;
        if (filter != null && !filter.trim().isEmpty()) {
            String f = filter.toLowerCase();
            String[] tokens = f.split("\\s+");
            snapshots = rawSnapshots.stream().filter(meta -> {
                String desc = (meta.getDescription() == null ? "" : meta.getDescription()).toLowerCase();
                String author = (meta.getAuthorName() == null ? "" : meta.getAuthorName()).toLowerCase();
                String combined = desc + " " + author;
                for (String t : tokens) {
                    if (!combined.contains(t)) return false;
                }
                return true;
            }).collect(java.util.stream.Collectors.toList());
        }

        if (snapshots.isEmpty()) {
            if (filter != null) {
                source.sendSuccess(() -> Lang.translatable("svcntrl.msg.no_snapshots_filter", category, filter), false);
            } else {
                source.sendSuccess(() -> Lang.translatable("svcntrl.msg.no_snapshots_branch", category, branch.getName()), false);
            }
            return 1;
        }

        int pageSize = 5;
        int totalPages = (int) Math.ceil((double) snapshots.size() / pageSize);
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        final int finalPage = page;
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.snapshots_for")
                .append(Component.literal(project.getName()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" (" + branch.getName() + ")").withStyle(ChatFormatting.GRAY))
                .append(filter != null ? Lang.translatable("svcntrl.msg.history_filter", filter).withStyle(ChatFormatting.GRAY) : Component.literal(""))
                .append(Lang.translatable("svcntrl.msg.history_page_suffix", finalPage, totalPages).withStyle(ChatFormatting.GRAY)), false);

        int startIndex = snapshots.size() - 1 - (page - 1) * pageSize;
        int endIndex = Math.max(0, startIndex - pageSize + 1);

        for (int i = startIndex; i >= endIndex; i--) {
            Project.SnapshotMeta meta = snapshots.get(i);
            String time = DATE_FORMAT.format(java.time.Instant.ofEpochMilli(meta.getTimestamp()));

            MutableComponent entry = Component.literal("  #" + meta.getId()).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" " + meta.getDescription()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" — " + meta.getAuthorName() + " " + time).withStyle(ChatFormatting.DARK_GRAY));

            MutableComponent previewBtn = Lang.translatable("svcntrl.msg.preview").withStyle(ChatFormatting.AQUA)
                    .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/svcntrl preview start " + category + " " + meta.getId())).withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Lang.translatable("svcntrl.msg.click_to_preview"))));
            MutableComponent exportBtn = Lang.translatable("svcntrl.msg.export").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/svcntrl export " + category + " " + meta.getId())).withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Lang.translatable("svcntrl.msg.click_to_export"))));
            MutableComponent restoreBtn = Lang.translatable("svcntrl.msg.restore").withStyle(ChatFormatting.RED)
                    .withStyle(style -> style.withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/svcntrl restore " + category + " " + meta.getId())).withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Lang.translatable("svcntrl.msg.click_to_restore"))));

            entry.append(previewBtn).append(exportBtn).append(restoreBtn);
            source.sendSuccess(() -> entry, false);
        }

        return 1;
    }

    private static int executeRestore(CommandSourceStack source, String category, int id, String branchArg, boolean noSave, boolean excludeIntersections) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        ServerLevel world = getProjectWorld(source, project);
        if (world == null) return 0;

        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }

        String currentBranch = project.getCurrentBranchName();
        String targetBranch = (branchArg != null && !branchArg.isEmpty()) ? branchArg.toLowerCase(java.util.Locale.ROOT) : currentBranch;
        if (!project.hasBranch(targetBranch)) { source.sendFailure(Lang.translatable("svcntrl.msg.branch_not_found", targetBranch)); return 0; }

        java.nio.file.Path snapshotPath = ProjectManager.getInstance().getSnapshotPath(project, targetBranch, category, id);
        if (!java.nio.file.Files.exists(snapshotPath)) {
            source.sendFailure(Lang.translatable("svcntrl.msg.target_snapshot_missing", targetBranch, category, id));
            return 0;
        }

        if (!noSave && com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnRestore) {
            int autoId = project.addAutoSnapshot(currentBranch, "Auto-save before restore to " + targetBranch + ":" + id, player.getUUID(), player.getName().getString());
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.creating_auto_save_before_rest").withStyle(ChatFormatting.YELLOW), false);
            AreaSerializer.saveAreaAsync(player, world, project, currentBranch, "auto", autoId, () -> {
                project.trimAutoSnapshots(currentBranch, (category.equals("auto") && targetBranch.equals(currentBranch)) ? new int[]{id} : new int[0]);
                boolean success = AreaSerializer.restoreArea(player, world, project, targetBranch, category, id, excludeIntersections, null, null);
                if (success) {
                    ProjectManager.getInstance().saveProject(project);
                } else {
                    source.sendFailure(Lang.translatable("svcntrl.msg.failed_to_restore_snapshot_mis"));
                }
            }, err -> {
                rollbackSnapshot(project, currentBranch, autoId, true);
                    source.sendFailure(Lang.translatable("svcntrl.msg.backup_failed", err));
            });
        } else {
            boolean success = AreaSerializer.restoreArea(player, world, project, targetBranch, category, id, excludeIntersections, null, null);
            if (!success) {
                source.sendFailure(Lang.translatable("svcntrl.msg.failed_to_restore_snapshot_mis"));
            }
        }
        return 1;
    }

    private static int executeRestorePatch(CommandSourceStack source, String category, int targetId, int baseId, boolean noSave, boolean excludeIntersections) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        ServerLevel world = getProjectWorld(source, project);
        if (world == null) return 0;

        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }

        String branchName = project.getCurrentBranchName();

        if (!noSave && com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnRestore) {
            int autoId = project.addAutoSnapshot(branchName, "Auto-save before patch restore (Target: " + targetId + ", Base: " + baseId + ")", player.getUUID(), player.getName().getString());
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.creating_auto_save_before_patc").withStyle(ChatFormatting.YELLOW), false);
            
            AreaSerializer.saveAreaAsync(player, world, project, branchName, "auto", autoId, () -> {
                project.trimAutoSnapshots(branchName, category.equals("auto") ? new int[]{targetId, baseId} : new int[0]);
                boolean success = AreaSerializer.restorePatchArea(player, world, project, branchName, category, targetId, branchName, category, baseId, excludeIntersections, null, null);
                if (success) {
                    source.sendSuccess(() -> Lang.translatable("svcntrl.msg.applying_patch_entities_fully").withStyle(ChatFormatting.GREEN), false);
                    ProjectManager.getInstance().saveProject(project);
                } else {
                    source.sendFailure(Lang.translatable("svcntrl.msg.failed_to_apply_patch_snapshot"));
                }
            }, err -> {
                rollbackSnapshot(project, branchName, autoId, true);
                    source.sendFailure(Lang.translatable("svcntrl.msg.failed_to_save", err));
            });
        } else {
            boolean success = AreaSerializer.restorePatchArea(player, world, project, branchName, category, targetId, branchName, category, baseId, excludeIntersections, null, null);
            if (success) {
                source.sendSuccess(() -> Lang.translatable("svcntrl.msg.applying_patch_entities_fully").withStyle(ChatFormatting.GREEN), false);
                ProjectManager.getInstance().saveProject(project);
            } else {
                source.sendFailure(Lang.translatable("svcntrl.msg.failed_to_apply_patch_snapshot"));
            }
        }
        return 1;
    }

    private static int executeRestorePatchCross(CommandSourceStack source, String category, String targetBranchArg, int targetId, String baseBranchArg, int baseId, boolean noSave, boolean excludeIntersections) {
        String targetBranch = targetBranchArg.toLowerCase(java.util.Locale.ROOT);
        String baseBranch = baseBranchArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        
        ServerLevel world = getProjectWorld(source, project);
        if (world == null) return 0;

        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }

        String currentBranch = project.getCurrentBranchName();
        if (!noSave && com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnRestore) {
            int autoId = project.addAutoSnapshot(currentBranch, "Auto-save before cross patch (Target: " + targetBranch + ":" + targetId + " Base: " + baseBranch + ":" + baseId + ")", player.getUUID(), player.getName().getString());
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.creating_auto_save_before_cros").withStyle(ChatFormatting.YELLOW), false);
            
            AreaSerializer.saveAreaAsync(player, world, project, currentBranch, "auto", autoId, () -> {
                project.trimAutoSnapshots(currentBranch, category.equals("auto") ? new int[]{targetBranch.equals(currentBranch) ? targetId : -1, baseBranch.equals(currentBranch) ? baseId : -1} : new int[0]);
                boolean success = AreaSerializer.restorePatchArea(player, world, project, targetBranch, category, targetId, baseBranch, category, baseId, excludeIntersections, null, null);
                if (success) {
                    source.sendSuccess(() -> Lang.translatable("svcntrl.msg.cross_patch_applied_successful").withStyle(ChatFormatting.GREEN), false);
                    ProjectManager.getInstance().saveProject(project);
                } else {
                    source.sendFailure(Lang.translatable("svcntrl.msg.failed_to_apply_patch_snapshot"));
                }
            }, err -> {
                rollbackSnapshot(project, currentBranch, autoId, true);
                    source.sendFailure(Lang.translatable("svcntrl.msg.failed_autosave_patch", err));
            });
        } else {
            boolean success = AreaSerializer.restorePatchArea(player, world, project, targetBranch, category, targetId, baseBranch, category, baseId, excludeIntersections, null, null);
            if (success) {
                source.sendSuccess(() -> Lang.translatable("svcntrl.msg.cross_patch_applied_successful").withStyle(ChatFormatting.GREEN), false);
                ProjectManager.getInstance().saveProject(project);
            } else {
                source.sendFailure(Lang.translatable("svcntrl.msg.failed_to_apply_patch_snapshot"));
            }
        }
        return 1;
    }

    private static int executePreview(CommandSourceStack source, String category, int id, String branchArg) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }

        String targetBranch = (branchArg != null && !branchArg.isEmpty()) ? branchArg.toLowerCase(java.util.Locale.ROOT) : project.getCurrentBranchName();
        if (!project.hasBranch(targetBranch)) { source.sendFailure(Lang.translatable("svcntrl.msg.branch_not_found", targetBranch)); return 0; }

        PreviewManager.getInstance().startPreview(player, project, targetBranch, category, id);
        return 1;
    }

    private static int executePreviewStop(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        if (!PreviewManager.getInstance().hasPreview(player.getUUID())) {
            source.sendFailure(Lang.translatable("svcntrl.msg.not_previewing"));
            return 0;
        }
        PreviewManager.getInstance().stopPreview(player);
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.preview_stopped").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int executeExport(CommandSourceStack source, String category, int id, String branchArg) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        
        String targetBranch = (branchArg != null && !branchArg.isEmpty()) ? branchArg.toLowerCase(java.util.Locale.ROOT) : project.getCurrentBranchName();
        if (!project.hasBranch(targetBranch)) { source.sendFailure(Lang.translatable("svcntrl.msg.branch_not_found", targetBranch)); return 0; }

        ExportManager.exportSnapshot(project, targetBranch, category, id, player);
        return 1;
    }

    private static int executeExportDiff(CommandSourceStack source, String category, int targetId, int baseId, String branchArg) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        
        String targetBranch = (branchArg != null && !branchArg.isEmpty()) ? branchArg.toLowerCase(java.util.Locale.ROOT) : project.getCurrentBranchName();
        if (!project.hasBranch(targetBranch)) { source.sendFailure(Lang.translatable("svcntrl.msg.branch_not_found", targetBranch)); return 0; }

        ExportManager.exportDiff(project, targetBranch, category, targetId, targetBranch, category, baseId, player);
        return 1;
    }

    private static int executeExportDiffCross(CommandSourceStack source, String category, String targetBranchArg, int targetId, String baseBranchArg, int baseId) {
        String targetBranch = targetBranchArg.toLowerCase(java.util.Locale.ROOT);
        String baseBranch = baseBranchArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) { source.sendFailure(Lang.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }

        ExportManager.exportDiff(project, targetBranch, category, targetId, baseBranch, category, baseId, player);
        return 1;
    }

    private static int executeExportAll(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUUID());
        if (project == null) return 0;
        if (project.isLocked()) { source.sendFailure(Lang.translatable("svcntrl.msg.project_or_an_overlapping_proj")); return 0; }
        if (!project.isMember(player.getUUID()) && !hasAdminBypass(source)) { source.sendFailure(Lang.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }
        ExportManager.exportProjectFull(project, player);
        return 1;
    }

    private static int executeReload(CommandSourceStack source) {
        com.svcntrl.config.SvcntrlConfig.load();
        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.svcntrl_config_reloaded").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int executeUpload(CommandSourceStack source, String choice) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        
        if ("reset".equalsIgnoreCase(choice)) {
            com.svcntrl.data.ProjectManager.getInstance().setAutoUploadPref(player.getUUID(), null);
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.upload_pref_reset").withStyle(ChatFormatting.GREEN), false);
            return 1;
        }

        if ("never".equalsIgnoreCase(choice)) {
            com.svcntrl.data.ProjectManager.getInstance().setAutoUploadPref(player.getUUID(), false);
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.upload_pref_disabled").withStyle(ChatFormatting.GREEN), false);
            com.svcntrl.core.ExportManager.consumePendingUpload(player.getUUID()); // discard if any
            resyncCommands(player);
            return 1;
        }

        if ("always".equalsIgnoreCase(choice)) {
            com.svcntrl.data.ProjectManager.getInstance().setAutoUploadPref(player.getUUID(), true);
            source.sendSuccess(() -> Lang.translatable("svcntrl.msg.upload_pref_enabled").withStyle(ChatFormatting.GREEN), false);
            // Fallthrough to upload if there is a pending file
        }

        java.nio.file.Path file = com.svcntrl.core.ExportManager.consumePendingUpload(player.getUUID());

        if ("no".equalsIgnoreCase(choice)) {
            if (file != null) {
                source.sendSuccess(() -> Lang.translatable("svcntrl.msg.upload_skipped").withStyle(ChatFormatting.YELLOW), false);
            }
            resyncCommands(player);
            return 1;
        }

        if (file == null || !java.nio.file.Files.exists(file)) {
            if ("yes".equalsIgnoreCase(choice)) {
                source.sendFailure(Lang.translatable("svcntrl.msg.no_valid_export_file_pending_f"));
                return 0;
            }
            return 1; // 'always' with no file is valid (we just enabled it)
        }

        source.sendSuccess(() -> Lang.translatable("svcntrl.msg.uploading_to_public", file.getFileName().toString()).withStyle(ChatFormatting.YELLOW), false);
        resyncCommands(player);
        com.svcntrl.SvcntrlMod.runAsync(() -> {
            com.svcntrl.core.ExportManager.doActualUpload(file, player);
        });
        
        return 1;
    }

    private static <T extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, T>> T withNoSave(T builder, com.mojang.brigadier.Command<CommandSourceStack> cmdNormal, com.mojang.brigadier.Command<CommandSourceStack> cmdNoSave) {
        return builder.executes(cmdNormal)
                      .then(literal("--nosave").executes(cmdNoSave));
    }

    private static java.util.function.Predicate<CommandSourceStack> requirePerm(String node) {
        return source -> source.getPlayer() == null || source.getServer().getPlayerList().isOp(source.getPlayer().nameAndId());
    }

    private static boolean hasAdminBypass(CommandSourceStack source) {
        return source.getPlayer() == null || source.getServer().getPlayerList().isOp(source.getPlayer().nameAndId());
    }

    private static boolean isOwnerOrAdmin(CommandSourceStack source) {
        if (hasAdminBypass(source)) return true;
        ServerPlayer player = source.getPlayer();
        if (player == null) return false;
        com.svcntrl.data.Project p = com.svcntrl.data.ProjectManager.getInstance().getActiveProject(player.getUUID());
        return p != null && p.getOwnerUuid().equals(player.getUUID());
    }

    
    private static void rollbackSnapshot(com.svcntrl.data.Project project, String branchName, int id, boolean auto) {
        if (project == null || branchName == null) return;
        com.svcntrl.data.Project.Branch branch = project.getBranch(branchName);
        if (branch != null) {
            if (auto) branch.removeAutoSnapshot(id);
            else branch.removeManualSnapshot(id);
            com.svcntrl.data.ProjectManager.getInstance().saveProject(project);
        }
    }

    private static void resyncCommands(ServerPlayer player) {
        if (player != null && player.level().getServer() != null) {
            player.level().getServer().getPlayerList().sendPlayerPermissionLevel(player);
        }
    }
}
