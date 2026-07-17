import re

with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'r') as f:
    c = f.read()

# Replace the command tree for log
log_old = """                .then(literal("log").requires(requirePerm("svcntrl.command.log"))
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
                )"""

log_new = """                .then(literal("log").requires(requirePerm("svcntrl.command.log"))
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
                )"""

c = c.replace(log_old, log_new)

# Now rewrite executeLog
exec_old = """    private static int executeLog(ServerCommandSource source, String category, int page) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }

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
        source.sendFeedback(() -> Text.translatable("svcntrl.msg.snapshots_for")
                .append(Text.literal(project.getName()).formatted(Formatting.AQUA))
                .append(Text.literal(" (" + branch.getName() + ") === Page " + finalPage + "/" + totalPages).formatted(Formatting.GRAY)), false);

        int startIndex = snapshots.size() - 1 - (page - 1) * pageSize;
        int endIndex = Math.max(0, startIndex - pageSize + 1);"""

exec_new = """    private static int executeLog(ServerCommandSource source, String category, int page, String filter) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        Project project = ProjectManager.getInstance().getActiveProject(player.getUuid());
        if (project == null) { source.sendError(Text.translatable("svcntrl.msg.no_active_project")); return 0; }
        if (!project.isMember(player.getUuid()) && !hasAdminBypass(source)) { source.sendError(Text.translatable("svcntrl.msg.you_don_t_have_access")); return 0; }

        Project.Branch branch = project.getBranch(project.getCurrentBranchName());
        List<Project.SnapshotMeta> rawSnapshots = "auto".equalsIgnoreCase(category) ? branch.getAutoSnapshots() : branch.getManualSnapshots();

        List<Project.SnapshotMeta> snapshots = rawSnapshots;
        if (filter != null && !filter.trim().isEmpty()) {
            String f = filter.toLowerCase();
            String[] tokens = f.split("\\\\s+");
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
                source.sendFeedback(() -> Text.literal("No " + category + " snapshots match the filter '" + filter + "'."), false);
            } else {
                source.sendFeedback(() -> Text.literal("No " + category + " snapshots found for branch " + branch.getName() + "."), false);
            }
            return 1;
        }

        int pageSize = 5;
        int totalPages = (int) Math.ceil((double) snapshots.size() / pageSize);
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        final int finalPage = page;
        source.sendFeedback(() -> Text.translatable("svcntrl.msg.snapshots_for")
                .append(Text.literal(project.getName()).formatted(Formatting.AQUA))
                .append(Text.literal(" (" + branch.getName() + ")" + (filter != null ? " [Filter: " + filter + "]" : "") + " === Page " + finalPage + "/" + totalPages).formatted(Formatting.GRAY)), false);

        int startIndex = snapshots.size() - 1 - (page - 1) * pageSize;
        int endIndex = Math.max(0, startIndex - pageSize + 1);"""

c = c.replace(exec_old, exec_new)

with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'w') as f:
    f.write(c)

print("Updated log command successfully." if exec_new in c else "Failed to update log command.")
