import re

with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'r') as f:
    content = f.read()

# Replace executeProjectList
old_func = """    private static int executeProjectList(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        List<Project> projects = ProjectManager.getInstance().getProjectsForPlayer(player.getUuid());
        if (projects.isEmpty()) {
            source.sendFeedback(() -> Text.literal("You have no projects.").formatted(Formatting.YELLOW), false);
            return 1;
        }
        source.sendFeedback(() -> Text.literal("=== Your Projects ===").formatted(Formatting.AQUA, Formatting.BOLD), false);
        for (Project p : projects) {
            String role = p.isOwner(player.getUuid()) ? "Owner" : "Member";
            source.sendFeedback(() -> Text.literal("- " + p.getName() + " (" + role + ")").formatted(Formatting.GREEN), false);
        }
        return 1;
    }"""

new_func = """    private static int executeProjectList(ServerCommandSource source, int page) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        List<Project> projects = ProjectManager.getInstance().getProjectsForPlayer(player.getUuid());
        if (projects.isEmpty()) {
            source.sendFeedback(() -> Text.literal("You have no projects.").formatted(Formatting.YELLOW), false);
            return 1;
        }
        
        int perPage = 5;
        int totalPages = (int) Math.ceil((double) projects.size() / perPage);
        int finalPage = Math.max(1, Math.min(page, totalPages));
        int start = (finalPage - 1) * perPage;
        int end = Math.min(start + perPage, projects.size());
        
        source.sendFeedback(() -> Text.literal("=== Your Projects (Page " + finalPage + "/" + totalPages + ") ===").formatted(Formatting.AQUA, Formatting.BOLD), false);
        for (int i = start; i < end; i++) {
            Project p = projects.get(i);
            String role = p.isOwner(player.getUuid()) ? "Owner" : "Member";
            source.sendFeedback(() -> Text.literal("- " + p.getName() + " (" + role + ")").formatted(Formatting.GREEN), false);
        }
        return 1;
    }"""

content = content.replace(old_func, new_func)

# Fix command tree for list
old_tree = """                .then(literal("list").requires(requirePerm("svcntrl.command.list"))
                    .executes(ctx -> executeProjectList(ctx.getSource())))"""

new_tree = """                .then(literal("list").requires(requirePerm("svcntrl.command.list"))
                    .executes(ctx -> executeProjectList(ctx.getSource(), 1))
                    .then(argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> executeProjectList(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))))"""

content = content.replace(old_tree, new_tree)

with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'w') as f:
    f.write(content)

