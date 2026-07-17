import re

with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'r') as f:
    cmds = f.read()
cmds = cmds.replace('.executes(ctx -> executeProjectList(ctx.getSource())))', '.executes(ctx -> executeProjectList(ctx.getSource(), 1)))')
with open('src/main/java/com/svcntrl/command/SvcntrlCommands.java', 'w') as f:
    f.write(cmds)

with open('src/main/java/com/svcntrl/core/PreviewManager.java', 'r') as f:
    pm = f.read()

# Add activePreviewEntities
pm = pm.replace(
    'private final Map<UUID, Set<Integer>> playerHiddenEntities = new ConcurrentHashMap<>();',
    'private final Map<UUID, java.util.Set<Integer>> activePreviewEntities = new ConcurrentHashMap<>();\n    private final Map<UUID, Set<Integer>> playerHiddenEntities = new ConcurrentHashMap<>();'
)
# We had a typo earlier in Python: it added it twice? No, it didn't add it because `java.util.Set` was what I used but I searched for `Set`.

# Fix NBT methods
# 'java.util.Optional<EntityType<?>> typeOpt = net.minecraft.registry.Registries.ENTITY_TYPE.getOptional('
pm = pm.replace(
    'java.util.Optional<EntityType<?>> typeOpt = net.minecraft.registry.Registries.ENTITY_TYPE.getOptional(net.minecraft.util.Identifier.tryParse(idStr));',
    'EntityType<?> type = net.minecraft.registry.Registries.ENTITY_TYPE.get(net.minecraft.util.Identifier.tryParse(idStr));'
)
pm = pm.replace(
    'if (typeOpt.isPresent()) {\n                            EntityType<?> type = typeOpt.get();',
    'if (type != null) {'
)
pm = pm.replace(
    'net.minecraft.nbt.NbtList rotation = entityNbt.getList("Rotation");',
    'net.minecraft.nbt.NbtList rotation = entityNbt.getList("Rotation").orElse(new net.minecraft.nbt.NbtList());'
)
pm = pm.replace(
    'net.minecraft.nbt.NbtList motion = entityNbt.getList("Motion");',
    'net.minecraft.nbt.NbtList motion = entityNbt.getList("Motion").orElse(new net.minecraft.nbt.NbtList());'
)
with open('src/main/java/com/svcntrl/core/PreviewManager.java', 'w') as f:
    f.write(pm)

