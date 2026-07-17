import re

def modify_file(filepath, callback):
    try:
        with open(filepath, 'r') as f:
            content = f.read()
    except FileNotFoundError:
        print(f"File not found: {filepath}")
        return
    new_content = callback(content)
    if new_content != content:
        with open(filepath, 'w') as f:
            f.write(new_content)
        print(f"Modified {filepath}")
    else:
        print(f"No changes in {filepath}")

# 3.1, 3.2, 2.5
def fix_project(c):
    c = c.replace('private final Set<UUID> members = new HashSet<>();', 'private final java.util.Set<UUID> members = java.util.concurrent.ConcurrentHashMap.newKeySet();')
    c = re.sub(r'java\.util\.concurrent\.CompletableFuture\.runAsync\(\(\) -> \{\s*try \{\s*java\.nio\.file\.Files\.deleteIfExists\(snapshotFile\);\s*\} catch \(java\.io\.IOException e\) \{\s*System\.err\.println\("Failed to delete auto-snapshot: " \+ e\.getMessage\(\)\);\s*\}\s*\}\);',
               r'try { java.nio.file.Files.deleteIfExists(snapshotFile); } catch (java.io.IOException e) { System.err.println("Failed to delete auto-snapshot: " + e.getMessage()); }', c)
    return c
modify_file('src/main/java/com/svcntrl/data/Project.java', fix_project)

# 3.3, 3.4
def fix_pm(c):
    c = re.sub(r'(java\.util\.List<Project\.SnapshotMeta> manualSnapshots = branch\.getManualSnapshots\(\);)', r'\1\n            synchronized(manualSnapshots) {', c)
    c = c.replace('java.util.List<Project.SnapshotMeta> autoSnapshots = branch.getAutoSnapshots();', '}\n            java.util.List<Project.SnapshotMeta> autoSnapshots = branch.getAutoSnapshots();\n            synchronized(autoSnapshots) {')
    c = c.replace('branchNbt.put("AutoSnapshots", serializeSnapshotList(autoSnapshots));', 'branchNbt.put("AutoSnapshots", serializeSnapshotList(autoSnapshots));\n            }')
    c = c.replace('lastPrefsFuture = java.util.concurrent.CompletableFuture.runAsync(() -> {', 'lastPrefsFuture = lastPrefsFuture.thenRunAsync(() -> {')
    return c
modify_file('src/main/java/com/svcntrl/data/ProjectManager.java', fix_pm)

