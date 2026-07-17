import re

with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'r') as f:
    content = f.read()

confirm_code = """    private static final java.util.Map<java.util.UUID, Long> pendingConfirms = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, String> pendingConfirmCmds = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean checkConfirmation(ServerCommandSource source, String commandDesc) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return false;
        java.util.UUID uuid = player.getUuid();
        long now = System.currentTimeMillis();
        
        if (pendingConfirms.containsKey(uuid) && pendingConfirmCmds.containsKey(uuid) 
                && pendingConfirmCmds.get(uuid).equals(commandDesc)
                && (now - pendingConfirms.get(uuid) < 15000)) {
            pendingConfirms.remove(uuid);
            pendingConfirmCmds.remove(uuid);
            return true;
        } else {
            pendingConfirms.put(uuid, now);
            pendingConfirmCmds.put(uuid, commandDesc);
            source.sendError(net.minecraft.text.Text.literal("Are you sure? This action is destructive! Run the exact same command again within 15 seconds to confirm."));
            return false;
        }
    }

"""

if 'checkConfirmation' not in content:
    # Insert before executeHelp
    content = content.replace("    private static int executeHelp(", confirm_code + "    private static int executeHelp(")

# Inject into executeRestore
content = re.sub(
    r'(private static int executeRestore\(ServerCommandSource source, String category, int id, String branchArg, boolean noSave, boolean excludeIntersections\) \{)',
    r'\1\n        if (!checkConfirmation(source, "restore " + category + " " + id + " " + branchArg)) return 0;',
    content
)

# Inject into executeRestorePatch
content = re.sub(
    r'(private static int executeRestorePatch\(ServerCommandSource source, String category, int targetId, int baseId, boolean noSave, boolean excludeIntersections\) \{)',
    r'\1\n        if (!checkConfirmation(source, "restorePatch " + category + " " + targetId + " " + baseId)) return 0;',
    content
)

# Inject into executeRestorePatchCross
content = re.sub(
    r'(private static int executeRestorePatchCross\(ServerCommandSource source, String category, String targetBranch, int targetId, String baseBranch, int baseId, boolean noSave, boolean excludeIntersections\) \{)',
    r'\1\n        if (!checkConfirmation(source, "restorePatchCross " + category + " " + targetBranch + " " + targetId + " " + baseBranch + " " + baseId)) return 0;',
    content
)

# Inject into executeBranchDelete
content = re.sub(
    r'(private static int executeBranchDelete\(ServerCommandSource source, String nameArg\) \{)',
    r'\1\n        if (!checkConfirmation(source, "branchDelete " + nameArg)) return 0;',
    content
)


with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'w') as f:
    f.write(content)
