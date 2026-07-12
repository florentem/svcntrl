import re

with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'r') as f:
    content = f.read()

# Update executeRestore calls
# Before: executeRestore(ctx.getSource(), "category", ..., branch, nosave)
# After: executeRestore(ctx.getSource(), "category", ..., branch, nosave, excludeOverlaps)

# First, modify the executeRestore method signature and internals
content = re.sub(
    r'private static int executeRestore\(ServerCommandSource source, String category, int id, String branchArg, boolean noSave\) \{',
    r'private static int executeRestore(ServerCommandSource source, String category, int id, String branchArg, boolean noSave, boolean excludeOverlaps) {',
    content
)

content = content.replace(
    'boolean success = AreaSerializer.restoreArea(player, world, project, targetBranch, category, id);',
    'boolean success = AreaSerializer.restoreArea(player, world, project, targetBranch, category, id, excludeOverlaps);'
)

# Update executeRestorePatch
content = re.sub(
    r'private static int executeRestorePatch\(ServerCommandSource source, String category, int targetId, int baseId, boolean noSave\) \{',
    r'private static int executeRestorePatch(ServerCommandSource source, String category, int targetId, int baseId, boolean noSave, boolean excludeOverlaps) {',
    content
)

content = content.replace(
    'boolean success = AreaSerializer.restorePatchArea(player, world, project, branchName, category, targetId, branchName, category, baseId);',
    'boolean success = AreaSerializer.restorePatchArea(player, world, project, branchName, category, targetId, branchName, category, baseId, excludeOverlaps);'
)

# Update executeRestorePatchCross
content = re.sub(
    r'private static int executeRestorePatchCross\(ServerCommandSource source, String category, int targetId, String baseBranch, int baseId, boolean noSave\) \{',
    r'private static int executeRestorePatchCross(ServerCommandSource source, String category, int targetId, String baseBranch, int baseId, boolean noSave, boolean excludeOverlaps) {',
    content
)

content = content.replace(
    'boolean success = AreaSerializer.restorePatchArea(player, world, project, currentBranch, category, targetId, baseBranch, category, baseId);',
    'boolean success = AreaSerializer.restorePatchArea(player, world, project, currentBranch, category, targetId, baseBranch, category, baseId, excludeOverlaps);'
)

# Now, we need to replace the command tree builder.
# We will look for patterns like:
# .executes(ctx -> executeRestore(..., false))
# .then(literal("--nosave").executes(ctx -> executeRestore(..., true)))

def replace_command_node(match):
    base_exec = match.group(1)
    # The base_exec has the arguments up to 'false' or 'true' at the end.
    # We will expand it to have --nosave and --exclude-overlaps
    # Let's extract the command name and args
    return match.group(0)

# Actually, doing this with regex on Java syntax is extremely brittle and will probably ruin the file.
