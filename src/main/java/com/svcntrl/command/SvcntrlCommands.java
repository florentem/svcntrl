package com.svcntrl.command;

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
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.*;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import com.svcntrl.core.PendingCreateManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class SvcntrlCommands {

    private static final java.time.format.DateTimeFormatter DATE_FORMAT = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault());

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(literal("svcntrl")
                // /svcntrl _upload (Hidden command for confirmation click)
                .then(literal("_upload").requires(s -> s.getPlayer() != null && com.svcntrl.core.ExportManager.hasPendingUpload(s.getPlayer().getUuid()))
                    .executes(ctx -> executeUpload(ctx.getSource()))
                )

                // /svcntrl project ...
                .then(literal("project")
                    .then(literal("create").requires(requirePerm("svcntrl.command.project.create"))
                        .then(argument("name", StringArgumentType.word())
                            .executes(ctx -> executeCreate(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                    .then(literal("remove").requires(requirePerm("svcntrl.command.project.remove"))
                        .then(argument("name", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestProjects(ctx, builder))
                            .executes(ctx -> executeRemoveProject(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))
                            .then(literal("force")
                                .executes(ctx -> executeRemoveProject(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)))))
                    .then(literal("trust").requires(requirePerm("svcntrl.command.project.trust"))
                        .then(argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> net.minecraft.command.CommandSource.suggestMatching(ctx.getSource().getServer().getPlayerNames(), builder))
                            .executes(ctx -> executeTrust(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                    .then(literal("untrust").requires(requirePerm("svcntrl.command.project.untrust"))
                        .then(argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestMembers(ctx, builder))
                            .executes(ctx -> executeUntrust(ctx.getSource(), StringArgumentType.getString(ctx, "player")))))
                )

                // /svcntrl branch ...
                .then(literal("branch")
                    .then(literal("list").requires(requirePerm("svcntrl.command.branch.list"))
                        .executes(ctx -> executeBranchList(ctx.getSource())))
                    .then(literal("create").requires(requirePerm("svcntrl.command.branch.create"))
                        .then(withNoSave(argument("name", StringArgumentType.word()),
                            ctx -> executeBranchCreate(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false),
                            ctx -> executeBranchCreate(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)
                        ))
                    )
                    .then(literal("checkout").requires(requirePerm("svcntrl.command.branch.checkout"))
                        .then(withNoSave(argument("name", StringArgumentType.word()).suggests((ctx, builder) -> suggestBranches(ctx, builder)),
                            ctx -> executeBranchCheckout(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false),
                            ctx -> executeBranchCheckout(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true)
                        ))
                    )
                    .then(literal("delete").requires(requirePerm("svcntrl.command.branch.delete"))
                        .then(argument("name", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestBranches(ctx, builder))
                            .executes(ctx -> executeBranchDelete(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                )

                // Quick actions (defaults to current project & branch)
                .then(literal("outline").requires(requirePerm("svcntrl.command.outline"))
                    .executes(ctx -> executeOutline(ctx.getSource())))

                .then(literal("reload").requires(requirePerm("svcntrl.command.reload"))
                    .executes(ctx -> executeReload(ctx.getSource())))

                .then(literal("tp").requires(requirePerm("svcntrl.command.tp"))
                    .executes(ctx -> executeTpActive(ctx.getSource()))
                    .then(argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestProjects(ctx, builder))
                        .executes(ctx -> executeTp(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))

                .then(literal("select").requires(requirePerm("svcntrl.command.select"))
                    .then(literal("raycast")
                        .executes(ctx -> executeSelectRaycast(ctx.getSource())))
                    .then(argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> suggestProjects(ctx, builder))
                        .executes(ctx -> executeSelect(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))

                .then(literal("save").requires(requirePerm("svcntrl.command.save"))
                    .executes(ctx -> executeSave(ctx.getSource(), ""))
                    .then(argument("description", StringArgumentType.greedyString())
                        .executes(ctx -> executeSave(ctx.getSource(), StringArgumentType.getString(ctx, "description")))))

                .then(literal("log").requires(requirePerm("svcntrl.command.log"))
                    .executes(ctx -> executeLog(ctx.getSource(), "manual", 1))
                    .then(argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeLog(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "page"))))
                    .then(literal("auto")
                        .executes(ctx -> executeLog(ctx.getSource(), "auto", 1))
                        .then(argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> executeLog(ctx.getSource(), "auto", IntegerArgumentType.getInteger(ctx, "page")))))
                    .then(literal("manual")
                        .executes(ctx -> executeLog(ctx.getSource(), "manual", 1))
                        .then(argument("page", IntegerArgumentType.integer(1))
                            .executes(ctx -> executeLog(ctx.getSource(), "manual", IntegerArgumentType.getInteger(ctx, "page")))))
                )
                
                .then(literal("deletesave").requires(requirePerm("svcntrl.command.snapshot.delete"))
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
                
                .then(literal("pos1").requires(s -> requirePerm("svcntrl.command.pos").test(s) && s.getPlayer() != null && com.svcntrl.core.PendingCreateManager.getInstance().hasPending(s.getPlayer().getUuid()))
                    .executes(ctx -> executePos1(ctx.getSource())))
                .then(literal("pos2").requires(s -> requirePerm("svcntrl.command.pos").test(s) && s.getPlayer() != null && com.svcntrl.core.PendingCreateManager.getInstance().hasPending(s.getPlayer().getUuid()))
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
    private static CompletableFuture<Suggestions> suggestProjects(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        return net.minecraft.command.CommandSource.suggestMatching(
                ProjectManager.getInstance().getAllProjects().stream().map(Project::getName), builder);
    }

    private static CompletableFuture<Suggestions> suggestSnapshotIds(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder, String category) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
            if (project != null) {
                Project.Branch branch = project.getBranch(project.getCurrentBranchName());
                if (branch != null) {
                    List<Project.SnapshotMeta> history = "auto".equalsIgnoreCase(category) ? branch.getAutoSnapshots() : branch.getManualSnapshots();
                    List<String> ids = history.stream().map(meta -> String.valueOf(meta.getId())).toList();
                    return net.minecraft.command.CommandSource.suggestMatching(ids, builder);
                }
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestCrossSnapshotIds(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder, String category, String branchArg) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
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
                    return net.minecraft.command.CommandSource.suggestMatching(ids, builder);
                }
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestMembers(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
            if (project != null) {
                for (UUID memberUuid : project.getMembers()) {
                    java.util.Optional<com.mojang.authlib.GameProfile> profileOpt = ctx.getSource().getServer().getUserCache().getByUuid(memberUuid);
                    if (profileOpt.isPresent()) builder.suggest(profileOpt.get().getName());
                }
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestBranches(CommandContext<ServerCommandSource> ctx, SuggestionsBuilder builder) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
            if (project != null) {
                List<String> names = project.getBranches().stream().map(Project.Branch::getName).toList();
                return net.minecraft.command.CommandSource.suggestMatching(names, builder);
            }
        }
        return builder.buildFuture();
    }

    private static ServerWorld getProjectWorld(ServerCommandSource source, Project project) {
        for (ServerWorld world : source.getServer().getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(project.getWorldId())) {
                return world;
            }
        }
        source.sendError(Text.literal("World for project not found."));
        return null;
    }

    private static int executeHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("=== Svcntrl Commands ===").formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("/svcntrl project create <name> - Create a project").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/svcntrl project remove <name> force - Delete a project permanently").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/svcntrl branch create <name> [--nosave] - Create a new branch").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/svcntrl branch checkout <name> [--nosave] - Switch to a branch").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/svcntrl branch list/delete - Manage branches").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/svcntrl save [desc] - Create a manual snapshot").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/svcntrl restore [manual|auto] <id> [--nosave] - Restore to a snapshot").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/svcntrl export <id> - Export snapshot to WorldEdit schematic").formatted(Formatting.YELLOW), false);
        source.sendFeedback(() -> Text.literal("/svcntrl pos1 / pos2 - Set positions for project creation").formatted(Formatting.YELLOW), false);
        return 1;
    }

    private static int executePos1(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        PendingCreateManager.getInstance().handleLeftClick(player, player.getBlockPos());
        return 1;
    }

    private static int executePos2(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        PendingCreateManager.getInstance().handleRightClick(player, player.getBlockPos());
        return 1;
    }

    private static boolean isValidName(String name) {
        return name != null && name.matches("^[a-zA-Z0-9_-]{1,32}$") && !name.equals(".") && !name.equals("..");
    }

    private static int executeCreate(ServerCommandSource source, String name) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) { source.sendError(Text.literal("This command can only be used by players.")); return 0; }
        if (!isValidName(name)) { source.sendError(Text.literal("Invalid name. Use only letters, numbers, underscores, and hyphens (max 32 chars).")); return 0; }
        PendingCreateManager.getInstance().startCreation(player, name);
        return 1;
    }

    private static int executeTpActive(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project. Select one or use /svcntrl tp <name>")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        return executeTp(source, project.getName());
    }

    private static int executeTp(ServerCommandSource source, String name) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getProject(name);
        if (project == null) { source.sendError(Text.literal("Project not found: " + name)); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }

        ServerWorld world = getProjectWorld(source, project);
        if (world == null) return 0;

        BlockPos min = project.getMin();
        BlockPos max = project.getMax();
        double centerX = min.getX() + (max.getX() - min.getX()) / 2.0;
        double centerZ = min.getZ() + (max.getZ() - min.getZ()) / 2.0;
        double y = max.getY() + 1.0;
        player.teleport(world, centerX, y, centerZ, java.util.EnumSet.noneOf(net.minecraft.network.packet.s2c.play.PositionFlag.class), player.getYaw(), player.getPitch(), true);
        source.sendFeedback(() -> Text.literal("Teleported to project '" + name + "'").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int executeRemoveProject(ServerCommandSource source, String name, boolean force) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getProject(name);
        if (project == null) { source.sendError(Text.literal("Project not found.")); return 0; }
        if (!project.isOwner(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("Only the project owner or admin can remove it.")); return 0; }
        if (!force) {
            source.sendError(Text.literal("Are you sure? Delete command: /svcntrl project remove " + name + " force"));
            return 0;
        }
        if (project.isLocked()) {
            source.sendError(Text.literal("Cannot delete project: an operation is in progress."));
            return 0;
        }
        com.svcntrl.core.PreviewManager.getInstance().stopPreviewForProject(source.getServer(), name);
        ProjectManager.getInstance().removeProject(name);
        source.sendFeedback(() -> Text.literal("Project '" + name + "' was permanently deleted.").formatted(Formatting.RED), true);
        return 1;
    }

    private static int executeSelectRaycast(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        UXManager.getInstance().setRaycasting(player.getUuid(), true);
        source.sendFeedback(() -> Text.literal("Raycast selection mode enabled.").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int executeSelect(ServerCommandSource source, String name) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getProject(name);
        if (project == null) { source.sendError(Text.literal("Project '" + name + "' not found.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        ProjectManager.getInstance().setActiveProject(player.getUuid(), name);
        source.sendFeedback(() -> Text.literal("Active project set to '").formatted(Formatting.GREEN).append(Text.literal(name).formatted(Formatting.GOLD)).append(Text.literal("'").formatted(Formatting.GREEN)), false);
        return 1;
    }

    private static int executeOutline(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project != null && !project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        boolean active = UXManager.getInstance().toggleOutline(player.getUuid());
        source.sendFeedback(() -> Text.literal("Project outline " + (active ? "enabled." : "disabled.")).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int executeTrust(ServerCommandSource source, String playerName) {
        ServerPlayerEntity sender = source.getPlayer();
        if (sender == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(sender.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isOwner(sender.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("Only the owner or admin can trust players.")); return 0; }
        java.util.Optional<com.mojang.authlib.GameProfile> profileOpt = source.getServer().getUserCache().findByName(playerName);
        if (profileOpt.isEmpty()) { source.sendError(Text.literal("Player not found.")); return 0; }
        UUID targetUuid = profileOpt.get().getId();
        if (project.addMember(targetUuid)) {
            ProjectManager.getInstance().saveProject(project);
            source.sendFeedback(() -> Text.literal("Added " + playerName + " to project.").formatted(Formatting.GREEN), false);
        }
        return 1;
    }

    private static int executeUntrust(ServerCommandSource source, String playerName) {
        ServerPlayerEntity sender = source.getPlayer();
        if (sender == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(sender.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isOwner(sender.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("Only the owner or admin can untrust players.")); return 0; }
        java.util.Optional<com.mojang.authlib.GameProfile> profileOpt = source.getServer().getUserCache().findByName(playerName);
        if (profileOpt.isEmpty()) { source.sendError(Text.literal("Player not found.")); return 0; }
        UUID targetUuid = profileOpt.get().getId();
        if (project.removeMember(targetUuid)) {
            ProjectManager.getInstance().saveProject(project);
            source.sendFeedback(() -> Text.literal("Removed " + playerName + " from project.").formatted(Formatting.GREEN), false);
        }
        return 1;
    }

    private static int executeBranchList(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        source.sendFeedback(() -> Text.literal("Branches for project '").append(Text.literal(project.getName()).formatted(Formatting.AQUA)).append(Text.literal("':")), false);
        for (Project.Branch b : project.getBranches()) {
            boolean isCurrent = b.getName().equals(project.getCurrentBranchName());
            source.sendFeedback(() -> Text.literal((isCurrent ? " * " : "   ") + b.getName())
                    .formatted(isCurrent ? Formatting.GREEN : Formatting.WHITE), false);
        }
        return 1;
    }

    private static int executeSave(ServerCommandSource source, String description) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        
        ServerWorld world = getProjectWorld(source, project);
        if (world == null) return 0;

        String branchName = project.getCurrentBranchName();

        if (description.isEmpty()) {
            description = "Manual save";
        }

        int snapshotId = project.addManualSnapshot(branchName, description, player.getUuid(), player.getName().getString());
        
        source.sendFeedback(() -> Text.literal("Saving project '").append(Text.literal(project.getName()).formatted(Formatting.AQUA)).append(Text.literal("'...")), false);
        
        AreaSerializer.saveAreaAsync(player, world, project, branchName, "manual", snapshotId, () -> {
            ProjectManager.getInstance().saveProject(project);
            source.sendFeedback(() -> Text.literal("Project saved! Snapshot ID: ").formatted(Formatting.GREEN).append(Text.literal(String.valueOf(snapshotId)).formatted(Formatting.GOLD)), false);
        }, error -> {
            project.getBranch(branchName).removeManualSnapshot(snapshotId);
            source.sendError(Text.literal("Failed to save: " + error));
        });

        return 1;
    }

    private static int executeBranchCreate(ServerCommandSource source, String nameArg, boolean noSave) {
        String name = nameArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        if (!isValidName(name)) { source.sendError(Text.literal("Invalid branch name. Use only letters, numbers, underscores, and hyphens.")); return 0; }
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        if (project.hasBranch(name)) { source.sendError(Text.literal("Branch already exists.")); return 0; }
        
        ServerWorld world = getProjectWorld(source, project);
        if (world == null) return 0;
        
        project.getOrCreateBranch(name);
        ProjectManager.getInstance().saveProject(project);
        
        if (!noSave) {
            String fallbackBranch = project.getCurrentBranchName();
            Runnable createInitialCommit = () -> {
                project.setCurrentBranchName(name);
                source.sendFeedback(() -> Text.literal("Branch '" + name + "' created. Saving initial commit...").formatted(Formatting.YELLOW), false);
                int autoId = project.addAutoSnapshot(name, "Initial commit for branch " + name, player.getUuid(), player.getName().getString());
                AreaSerializer.saveAreaAsync(player, world, project, name, "auto", autoId, () -> {
                    source.sendFeedback(() -> Text.literal("Branch state saved.").formatted(Formatting.GREEN), false);
                    ProjectManager.getInstance().saveProject(project);
                }, err -> {
                    project.setCurrentBranchName(fallbackBranch);
                    project.getBranch(name).removeAutoSnapshot(autoId);
                    ProjectManager.getInstance().saveProject(project);
                    source.sendError(Text.literal("Failed to save initial commit (branch switch rolled back): " + err));
                });
            };

            if (com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnBranchSwitch) {
                String currentBranch = project.getCurrentBranchName();
                source.sendFeedback(() -> Text.literal("Saving current state to branch '" + currentBranch + "' before branch creation...").formatted(Formatting.YELLOW), false);
                int currentAutoId = project.addAutoSnapshot(currentBranch, "Auto-save before creating branch " + name, player.getUuid(), player.getName().getString());
                AreaSerializer.saveAreaAsync(player, world, project, currentBranch, "auto", currentAutoId, createInitialCommit, err -> {
                    source.sendError(Text.literal("Failed to save current branch state: " + err));
                });
            } else {
                createInitialCommit.run();
            }
        } else {
            project.setCurrentBranchName(name);
            source.sendFeedback(() -> Text.literal("Branch '" + name + "' created.").formatted(Formatting.GREEN), false);
            ProjectManager.getInstance().saveProject(project);
        }
        return 1;
    }

    private static int executeBranchCheckout(ServerCommandSource source, String nameArg, boolean noSave) {
        String name = nameArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        if (!isValidName(name)) { source.sendError(Text.literal("Invalid branch name. Use only letters, numbers, underscores, and hyphens.")); return 0; }
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        if (!project.hasBranch(name)) { source.sendError(Text.literal("Branch not found.")); return 0; }
        if (project.getCurrentBranchName().equals(name)) { source.sendError(Text.literal("Already on branch " + name)); return 0; }
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        
        ServerWorld world = getProjectWorld(source, project);
        if (world == null) return 0;
        
        Runnable onCheckout = () -> {
            // Now restore the latest state from the new branch
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
            
            if (manualTime > autoTime) {
                restoreId = manualId;
                category = "manual";
            } else if (autoTime > -1) {
                restoreId = autoId;
                category = "auto";
            }
            
            project.setCurrentBranchName(name);
            ProjectManager.getInstance().saveProject(project);
            
            if (restoreId == -1) {
                source.sendFeedback(() -> Text.literal("Checked out to branch '" + name + "'. Branch is empty.").formatted(Formatting.GREEN), false);
                return;
            }
            
            source.sendFeedback(() -> Text.literal("Restoring branch '" + name + "' state...").formatted(Formatting.YELLOW), false);
            boolean success = AreaSerializer.restoreArea(player, world, project, name, category, restoreId, false);
            if (!success) {
                source.sendError(Text.literal("Failed to load branch data."));
            }
        };

        if (!noSave && com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnBranchSwitch) {
            String oldBranch = project.getCurrentBranchName();
            source.sendFeedback(() -> Text.literal("Saving current state to branch '" + oldBranch + "'...").formatted(Formatting.YELLOW), false);
            
            int autoId = project.addAutoSnapshot(oldBranch, "Auto-save before checkout to " + name, player.getUuid(), player.getName().getString());
            AreaSerializer.saveAreaAsync(player, world, project, oldBranch, "auto", autoId, () -> {
                project.trimAutoSnapshots(oldBranch);
                onCheckout.run();
            }, err -> {
                project.getBranch(oldBranch).removeAutoSnapshot(autoId);
                source.sendError(Text.literal("Failed to save branch state: " + err));
            });
        } else {
            onCheckout.run();
        }
        
        return 1;
    }

    private static int executeBranchDelete(ServerCommandSource source, String nameArg) {
        String name = nameArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        if (!isValidName(name)) { source.sendError(Text.literal("Invalid branch name. Use only letters, numbers, underscores, and hyphens.")); return 0; }
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        if (!project.isOwner(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("Only owner can delete branches.")); return 0; }
        if (project.getCurrentBranchName().equals(name)) { source.sendError(Text.literal("Cannot delete current branch.")); return 0; }
        if (!project.hasBranch(name)) { source.sendError(Text.literal("Branch not found.")); return 0; }
        
        project.deleteBranch(name);
        ProjectManager.getInstance().deleteBranchDir(project, name);
        ProjectManager.getInstance().saveProject(project);
        source.sendFeedback(() -> Text.literal("Branch '" + name + "' deleted.").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int executeSnapshotDelete(ServerCommandSource source, String category, int id) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        if (!project.isOwner(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access. Only the owner can delete snapshots.")); return 0; }

        Project.Branch branch = project.getBranch(project.getCurrentBranchName());
        java.util.List<Project.SnapshotMeta> snapshots = category.equals("manual") ? branch.getManualSnapshots() : branch.getAutoSnapshots();
        
        Project.SnapshotMeta target = null;
        for (Project.SnapshotMeta meta : snapshots) {
            if (meta.getId() == id) {
                target = meta;
                break;
            }
        }
        
        if (target == null) {
            source.sendError(Text.literal("Snapshot not found."));
            return 0;
        }
        
        java.nio.file.Path snapshotPath = ProjectManager.getInstance().getSnapshotPath(project, branch.getName(), category, id);
        if (category.equals("manual")) {
            branch.removeManualSnapshot(id);
        } else {
            branch.removeAutoSnapshot(id);
        }
        
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                java.nio.file.Files.deleteIfExists(snapshotPath);
                ProjectManager.getInstance().saveProject(project);
            } catch (java.io.IOException e) {
                com.svcntrl.SvcntrlMod.LOGGER.error("Failed to delete snapshot file", e);
            }
        });
        // Project is saved in async block
        source.sendFeedback(() -> Text.literal("Snapshot " + id + " (" + category + ") deleted.").formatted(Formatting.GREEN), false);
        return 1;
    }


    private static int executeLog(ServerCommandSource source, String category, int page) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }

        Project.Branch branch = project.getBranch(project.getCurrentBranchName());
        List<Project.SnapshotMeta> snapshots = "auto".equalsIgnoreCase(category) ? branch.getAutoSnapshots() : branch.getManualSnapshots();

        if (snapshots.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No " + category + " snapshots found for branch " + branch.getName() + "."), false);
            return 1;
        }

        int pageSize = 5;
        int totalPages = (int) Math.ceil((double) snapshots.size() / pageSize);
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        final int finalPage = page;
        source.sendFeedback(() -> Text.literal("=== Snapshots for ")
                .append(Text.literal(project.getName()).formatted(Formatting.AQUA))
                .append(Text.literal(" (" + branch.getName() + ") === Page " + finalPage + "/" + totalPages).formatted(Formatting.GRAY)), false);

        int startIndex = snapshots.size() - 1 - (page - 1) * pageSize;
        int endIndex = Math.max(0, startIndex - pageSize + 1);

        for (int i = startIndex; i >= endIndex; i--) {
            Project.SnapshotMeta meta = snapshots.get(i);
            String time = DATE_FORMAT.format(java.time.Instant.ofEpochMilli(meta.getTimestamp()));

            MutableText entry = Text.literal("  #" + meta.getId()).formatted(Formatting.YELLOW)
                    .append(Text.literal(" " + meta.getDescription()).formatted(Formatting.WHITE))
                    .append(Text.literal(" — " + meta.getAuthorName() + " " + time).formatted(Formatting.DARK_GRAY));

            MutableText previewBtn = Text.literal(" [Preview]").formatted(Formatting.AQUA)
                    .styled(style -> style.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/svcntrl preview start " + category + " " + meta.getId())).withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal("Click to preview"))));
            MutableText exportBtn = Text.literal(" [Export]").formatted(Formatting.LIGHT_PURPLE)
                    .styled(style -> style.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/svcntrl export " + category + " " + meta.getId())).withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal("Click to export"))));
            MutableText restoreBtn = Text.literal(" [Restore]").formatted(Formatting.RED)
                    .styled(style -> style.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/svcntrl restore " + category + " " + meta.getId())).withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal("Click to restore"))));

            entry.append(previewBtn).append(exportBtn).append(restoreBtn);
            source.sendFeedback(() -> entry, false);
        }

        return 1;
    }

    private static int executeRestore(ServerCommandSource source, String category, int id, String branchArg, boolean noSave, boolean excludeIntersections) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        ServerWorld world = getProjectWorld(source, project);
        if (world == null) return 0;

        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }

        String currentBranch = project.getCurrentBranchName();
        String targetBranch = (branchArg != null && !branchArg.isEmpty()) ? branchArg.toLowerCase(java.util.Locale.ROOT) : currentBranch;
        if (!project.hasBranch(targetBranch)) { source.sendError(Text.literal("Branch not found: " + targetBranch)); return 0; }

        java.nio.file.Path snapshotPath = ProjectManager.getInstance().getSnapshotPath(project, targetBranch, category, id);
        if (!java.nio.file.Files.exists(snapshotPath)) {
            source.sendError(Text.literal("Target snapshot missing: " + targetBranch + "/" + category + "/" + id));
            return 0;
        }

        if (!noSave && com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnRestore) {
            int autoId = project.addAutoSnapshot(currentBranch, "Auto-save before restore to " + targetBranch + ":" + id, player.getUuid(), player.getName().getString());
            source.sendFeedback(() -> Text.literal("Creating auto-save before restore...").formatted(Formatting.YELLOW), false);
            AreaSerializer.saveAreaAsync(player, world, project, currentBranch, "auto", autoId, () -> {
                project.trimAutoSnapshots(currentBranch, (category.equals("auto") && targetBranch.equals(currentBranch)) ? new int[]{id} : new int[0]);
                boolean success = AreaSerializer.restoreArea(player, world, project, targetBranch, category, id, excludeIntersections);
                if (success) {
                    ProjectManager.getInstance().saveProject(project);
                } else {
                    source.sendError(Text.literal("Failed to restore. Snapshot missing."));
                }
            }, err -> {
                project.getBranch(currentBranch).removeAutoSnapshot(autoId);
                source.sendError(Text.literal("Backup failed: " + err + ". Restore cancelled."));
            });
        } else {
            boolean success = AreaSerializer.restoreArea(player, world, project, targetBranch, category, id, excludeIntersections);
            if (!success) {
                source.sendError(Text.literal("Failed to restore. Snapshot missing."));
            }
        }
        return 1;
    }

    private static int executeRestorePatch(ServerCommandSource source, String category, int targetId, int baseId, boolean noSave, boolean excludeIntersections) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        ServerWorld world = getProjectWorld(source, project);
        if (world == null) return 0;

        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }

        String branchName = project.getCurrentBranchName();

        if (!noSave && com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnRestore) {
            int autoId = project.addAutoSnapshot(branchName, "Auto-save before patch restore (Target: " + targetId + ", Base: " + baseId + ")", player.getUuid(), player.getName().getString());
            source.sendFeedback(() -> Text.literal("Creating auto-save before patch restore...").formatted(Formatting.YELLOW), false);
            
            AreaSerializer.saveAreaAsync(player, world, project, branchName, "auto", autoId, () -> {
                project.trimAutoSnapshots(branchName, category.equals("auto") ? new int[]{targetId, baseId} : new int[0]);
                boolean success = AreaSerializer.restorePatchArea(player, world, project, branchName, category, targetId, branchName, category, baseId, excludeIntersections);
                if (success) {
                    source.sendFeedback(() -> Text.literal("Applying patch (Entities fully replaced)...").formatted(Formatting.GREEN), false);
                    ProjectManager.getInstance().saveProject(project);
                } else {
                    source.sendError(Text.literal("Failed to apply patch. Snapshots missing."));
                }
            }, err -> {
                project.getBranch(branchName).removeAutoSnapshot(autoId);
                source.sendError(Text.literal("Failed to save: " + err));
            });
        } else {
            boolean success = AreaSerializer.restorePatchArea(player, world, project, branchName, category, targetId, branchName, category, baseId, excludeIntersections);
            if (success) {
                source.sendFeedback(() -> Text.literal("Applying patch (Entities fully replaced)...").formatted(Formatting.GREEN), false);
                ProjectManager.getInstance().saveProject(project);
            } else {
                source.sendError(Text.literal("Failed to apply patch. Snapshots missing."));
            }
        }
        return 1;
    }

    private static int executeRestorePatchCross(ServerCommandSource source, String category, String targetBranchArg, int targetId, String baseBranchArg, int baseId, boolean noSave, boolean excludeIntersections) {
        String targetBranch = targetBranchArg.toLowerCase(java.util.Locale.ROOT);
        String baseBranch = baseBranchArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        
        ServerWorld world = getProjectWorld(source, project);
        if (world == null) return 0;

        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }

        String currentBranch = project.getCurrentBranchName();
        if (!noSave && com.svcntrl.config.SvcntrlConfig.getInstance().autoSaveOnRestore) {
            int autoId = project.addAutoSnapshot(currentBranch, "Auto-save before cross patch (Target: " + targetBranch + ":" + targetId + " Base: " + baseBranch + ":" + baseId + ")", player.getUuid(), player.getName().getString());
            source.sendFeedback(() -> Text.literal("Creating auto-save before cross patch...").formatted(Formatting.YELLOW), false);
            
            AreaSerializer.saveAreaAsync(player, world, project, currentBranch, "auto", autoId, () -> {
                project.trimAutoSnapshots(currentBranch, category.equals("auto") ? new int[]{targetBranch.equals(currentBranch) ? targetId : -1, baseBranch.equals(currentBranch) ? baseId : -1} : new int[0]);
                boolean success = AreaSerializer.restorePatchArea(player, world, project, targetBranch, category, targetId, baseBranch, category, baseId, excludeIntersections);
                if (success) {
                    source.sendFeedback(() -> Text.literal("Cross patch applied successfully! (Entities fully replaced)").formatted(Formatting.GREEN), false);
                    ProjectManager.getInstance().saveProject(project);
                } else {
                    source.sendError(Text.literal("Failed to apply patch. Snapshots missing."));
                }
            }, err -> {
                project.getBranch(currentBranch).removeAutoSnapshot(autoId);
                ProjectManager.getInstance().saveProject(project);
                source.sendError(Text.literal("Failed to auto-save, cancelling patch: " + err));
            });
        } else {
            boolean success = AreaSerializer.restorePatchArea(player, world, project, targetBranch, category, targetId, baseBranch, category, baseId, excludeIntersections);
            if (success) {
                source.sendFeedback(() -> Text.literal("Cross patch applied successfully! (Entities fully replaced)").formatted(Formatting.GREEN), false);
                ProjectManager.getInstance().saveProject(project);
            } else {
                source.sendError(Text.literal("Failed to apply patch. Snapshots missing."));
            }
        }
        return 1;
    }

    private static int executePreview(ServerCommandSource source, String category, int id, String branchArg) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }

        String targetBranch = (branchArg != null && !branchArg.isEmpty()) ? branchArg.toLowerCase(java.util.Locale.ROOT) : project.getCurrentBranchName();
        if (!project.hasBranch(targetBranch)) { source.sendError(Text.literal("Branch not found: " + targetBranch)); return 0; }

        PreviewManager.getInstance().startPreview(player, project, targetBranch, category, id);
        return 1;
    }

    private static int executePreviewStop(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        if (!PreviewManager.getInstance().hasPreview(player.getUuid())) {
            source.sendError(Text.literal("Not previewing."));
            return 0;
        }
        PreviewManager.getInstance().stopPreview(player);
        source.sendFeedback(() -> Text.literal("Preview stopped.").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int executeExport(ServerCommandSource source, String category, int id, String branchArg) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        
        String targetBranch = (branchArg != null && !branchArg.isEmpty()) ? branchArg.toLowerCase(java.util.Locale.ROOT) : project.getCurrentBranchName();
        if (!project.hasBranch(targetBranch)) { source.sendError(Text.literal("Branch not found: " + targetBranch)); return 0; }

        ExportManager.exportSnapshot(project, targetBranch, category, id, player);
        return 1;
    }

    private static int executeExportDiff(ServerCommandSource source, String category, int targetId, int baseId, String branchArg) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        
        String targetBranch = (branchArg != null && !branchArg.isEmpty()) ? branchArg.toLowerCase(java.util.Locale.ROOT) : project.getCurrentBranchName();
        if (!project.hasBranch(targetBranch)) { source.sendError(Text.literal("Branch not found: " + targetBranch)); return 0; }

        ExportManager.exportDiff(project, targetBranch, category, targetId, targetBranch, category, baseId, player);
        return 1;
    }

    private static int executeExportDiffCross(ServerCommandSource source, String category, String targetBranchArg, int targetId, String baseBranchArg, int baseId) {
        String targetBranch = targetBranchArg.toLowerCase(java.util.Locale.ROOT);
        String baseBranch = baseBranchArg.toLowerCase(java.util.Locale.ROOT);
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.literal("No active project.")); return 0; }
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }

        ExportManager.exportDiff(project, targetBranch, "manual", targetId, baseBranch, "manual", baseId, player);
        return 1;
    }

    private static int executeExportAll(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) return 0;
        if (project.isLocked()) { source.sendError(Text.literal("Project (or an overlapping project) is locked by another operation.")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.literal("You don't have access.")); return 0; }
        ExportManager.exportProjectFull(project, player);
        return 1;
    }

    private static int executeReload(ServerCommandSource source) {
        com.svcntrl.config.SvcntrlConfig.load();
        source.sendFeedback(() -> Text.literal("Svcntrl config reloaded!").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int executeUpload(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        
        java.nio.file.Path file = com.svcntrl.core.ExportManager.consumePendingUpload(player.getUuid());
        if (file == null || !java.nio.file.Files.exists(file)) {
            source.sendError(Text.literal("No valid export file pending for upload."));
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Uploading " + file.getFileName().toString() + " to tmpfiles.org...").formatted(Formatting.YELLOW), false);
        CompletableFuture.runAsync(() -> {
            com.svcntrl.core.ExportManager.doActualUpload(file, player);
        });
        
        return 1;
    }

    private static <T extends com.mojang.brigadier.builder.ArgumentBuilder<ServerCommandSource, T>> T withNoSave(T builder, com.mojang.brigadier.Command<ServerCommandSource> cmdNormal, com.mojang.brigadier.Command<ServerCommandSource> cmdNoSave) {
        return builder.executes(cmdNormal)
                      .then(literal("--nosave").executes(cmdNoSave));
    }

    private static java.util.function.Predicate<ServerCommandSource> requirePerm(String node) {
        return me.lucko.fabric.api.permissions.v0.Permissions.require(node, 2);
    }

    private static boolean hasAdminBypass(ServerCommandSource source) {
        return me.lucko.fabric.api.permissions.v0.Permissions.check(source, "svcntrl.admin", 3);
    }
}
