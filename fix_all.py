import re

with open('src/main/java/com/svcntrl/core/PreviewManager.java', 'r') as f:
    content = f.read()

# Add activePreviewEntities back
content = content.replace(
    'private final Map<UUID, Set<Integer>> playerHiddenEntities = new ConcurrentHashMap<>();',
    'private final Map<UUID, java.util.Set<Integer>> activePreviewEntities = new ConcurrentHashMap<>();\n    private final Map<UUID, Set<Integer>> playerHiddenEntities = new ConcurrentHashMap<>();'
)

# Fix Optional<String> for NbtCompound
# Wait, in yarn 1.21.1, NbtCompound doesn't have `getString` if it returns Optional? No, maybe it's because NBT was heavily refactored.
# Actually, I can use `entityNbt.asString().get()`? No.
# If `entityNbt.getString` returns `String`, but error says `Optional<String>`, let's use `entityNbt.getString("id").orElse("")` if it's Optional? But wait, what if I just use `entityNbt.get("id").asString()`?
# Let's write a simple patch for NbtCompound handling:
content = content.replace('String idStr = entityNbt.getString("id");', 'String idStr = entityNbt.contains("id", 8) ? entityNbt.getString("id") : "";')
# Wait, error says `entityNbt.getString("id")` is Optional.
content = content.replace('String idStr = entityNbt.getString("id");', 'String idStr = entityNbt.contains("id") ? entityNbt.getString("id") : "";')
