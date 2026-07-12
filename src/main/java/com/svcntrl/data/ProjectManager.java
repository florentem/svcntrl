package com.svcntrl.data;

import com.google.gson.*;
import com.svcntrl.SvcntrlMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProjectManager {

    private static final ProjectManager INSTANCE = new ProjectManager();
    private Path dataDir;
    
    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeProjects = new ConcurrentHashMap<>();
    private final Set<Project> lockedProjects = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Void>> saveTasks = new java.util.concurrent.ConcurrentHashMap<>();

    private ProjectManager() {
    }

    public static ProjectManager getInstance() {
        return INSTANCE;
    }

    public Path getDataDir() { return dataDir; }

    public Path getProjectDir(Project project) {
        return dataDir.resolve(project.getName().toLowerCase(Locale.ROOT));
    }

    public Path getSnapshotPath(Project project, String branchName, String category, int id) {
        return getProjectDir(project).resolve(branchName.toLowerCase(Locale.ROOT)).resolve(category).resolve("snapshot_" + id + ".nbt");
    }

    public Project getProject(String name) {
        return projects.get(name.toLowerCase(Locale.ROOT));
    }
    
    public java.util.Collection<Project> getProjects() {
        return projects.values();
    }
    
    public Set<Project> getLockedProjects() {
        return lockedProjects;
    }
    
    public void setProjectLocked(Project project, boolean locked) {
        if (locked) {
            lockedProjects.add(project);
            project.setLocked(true);
        } else {
            lockedProjects.remove(project);
            project.setLocked(false);
        }
    }

    public boolean isOverlappingLocked(Project project) {
        for (Project locked : lockedProjects) {
            if (locked != project && locked.intersects(project)) {
                return true;
            }
        }
        return false;
    }

    public boolean createProject(Project project) {
        String key = project.getName().toLowerCase(Locale.ROOT);
        if (projects.putIfAbsent(key, project) != null) {
            return false;
        }
        saveProject(project);
        return true;
    }

    public void removeProject(String name) {
        Project project = projects.remove(name.toLowerCase(Locale.ROOT));
        if (project != null) {
            Path projectDir = getProjectDir(project);
            Runnable deleteAction = () -> {
                if (java.nio.file.Files.exists(projectDir)) {
                    try {
                        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(projectDir)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(java.io.File::delete);
                        }
                    } catch (Throwable e) {
                        com.svcntrl.SvcntrlMod.LOGGER.error("Failed to delete project directory: {}", projectDir, e);
                    }
                }
            };
            java.util.concurrent.CompletableFuture<Void> oldFuture = saveTasks.remove(project.getName());
            if (oldFuture != null && !oldFuture.isDone()) {
                oldFuture.thenRunAsync(deleteAction);
            } else {
                java.util.concurrent.CompletableFuture.runAsync(deleteAction);
            }

            activeProjects.entrySet().removeIf(entry -> entry.getValue().equalsIgnoreCase(name));
            lockedProjects.remove(project);
        }
    }

    public Collection<Project> getAllProjects() {
        return Collections.unmodifiableCollection(projects.values());
    }

    public List<Project> getProjectsForPlayer(UUID playerUuid) {
        List<Project> list = new ArrayList<>();
        for (Project p : projects.values()) {
            if (p.isMember(playerUuid)) list.add(p);
        }
        return list;
    }

    public int getProjectCount() {
        return projects.size();
    }

    public void setActiveProject(UUID playerUuid, String projectName) {
        if (projectName == null) {
            activeProjects.remove(playerUuid);
        } else {
            activeProjects.put(playerUuid, projectName.toLowerCase(Locale.ROOT));
        }
    }

    public Project getActiveProject(UUID playerUuid) {
        String name = activeProjects.get(playerUuid);
        if (name != null) {
            return getProject(name);
        }
        return null;
    }

    public void loadProjects(net.minecraft.server.MinecraftServer server) {
        if (dataDir == null) {
            dataDir = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("svcntrl_data");
        }

        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            SvcntrlMod.LOGGER.error("[svcntrl] Failed to create data directory", e);
            return;
        }

        projects.clear();
        
        try (java.util.stream.Stream<Path> stream = Files.list(dataDir)) {
            stream.filter(Files::isDirectory).forEach(projectDir -> {
                Path projectFile = projectDir.resolve("project.json");
                if (Files.exists(projectFile)) {
                    try (Reader reader = Files.newBufferedReader(projectFile)) {
                        JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                        
                        // JSON and File Migration for branches
                        if (obj.has("branches")) {
                            JsonObject branchesObj = obj.getAsJsonObject("branches");
                            JsonObject newBranchesObj = new JsonObject();
                            java.util.Map<String, String> nameMap = new java.util.HashMap<>();
                            
                            for (String bName : branchesObj.keySet()) {
                                String lowerName = bName.toLowerCase(Locale.ROOT);
                                String finalName = lowerName;
                                
                                if (!bName.equals(lowerName) && branchesObj.has(lowerName)) {
                                    int i = 1;
                                    while (branchesObj.has(lowerName + "_conflict_" + i) || newBranchesObj.has(lowerName + "_conflict_" + i)) {
                                        i++;
                                    }
                                    finalName = lowerName + "_conflict_" + i;
                                    SvcntrlMod.LOGGER.warn("[svcntrl] Branch name collision detected for '{}' in project '{}'. Renaming to '{}'", bName, obj.get("name").getAsString(), finalName);
                                }
                                nameMap.put(bName, finalName);
                                newBranchesObj.add(finalName, branchesObj.get(bName));
                                
                                if (!bName.equals(finalName)) {
                                    Path oldDir = projectDir.resolve(bName);
                                    if (Files.exists(oldDir)) {
                                        try {
                                            Files.move(oldDir, projectDir.resolve(finalName));
                                        } catch (IOException e) {
                                            SvcntrlMod.LOGGER.error("[svcntrl] Failed to rename branch dir " + bName, e);
                                        }
                                    }
                                }
                            }
                            obj.add("branches", newBranchesObj);
                            
                            if (obj.has("currentBranch") && !obj.get("currentBranch").isJsonNull()) {
                                String currentBranch = obj.get("currentBranch").getAsString();
                                if (nameMap.containsKey(currentBranch)) {
                                    obj.addProperty("currentBranch", nameMap.get(currentBranch));
                                } else {
                                    obj.addProperty("currentBranch", currentBranch.toLowerCase(Locale.ROOT));
                                }
                            }
                        }

                        Project project = deserializeProject(obj);
                        projects.put(project.getName().toLowerCase(Locale.ROOT), project);
                        
                        // Legacy File Migration logic
                        Path oldManual = projectDir.resolve("manual");
                        Path oldAuto = projectDir.resolve("auto");
                        try {
                            if (Files.exists(oldManual)) {
                                Files.createDirectories(projectDir.resolve("main"));
                                Files.move(oldManual, projectDir.resolve("main").resolve("manual"));
                            }
                            if (Files.exists(oldAuto)) {
                                Files.createDirectories(projectDir.resolve("main"));
                                Files.move(oldAuto, projectDir.resolve("main").resolve("auto"));
                            }
                        } catch (Exception e) {
                            SvcntrlMod.LOGGER.error("[svcntrl] Legacy migration failed for project {}", project.getName(), e);
                        }
                        
                        Path expectedDir = getProjectDir(project);
                        if (!projectDir.equals(expectedDir)) {
                            if (!Files.exists(expectedDir)) {
                                try {
                                    Files.move(projectDir, expectedDir);
                                } catch (IOException e) {
                                    SvcntrlMod.LOGGER.error("[svcntrl] Failed to migrate project directory from {} to {}", projectDir, expectedDir, e);
                                }
                            } else {
                                SvcntrlMod.LOGGER.warn("[svcntrl] Could not migrate project directory {} to {} because target already exists.", projectDir, expectedDir);
                            }
                        }
                        
                    } catch (Exception e) {
                        SvcntrlMod.LOGGER.error("[svcntrl] Failed to load project entry from {}", projectFile, e);
                    }
                }
            });
        } catch (IOException e) {
            SvcntrlMod.LOGGER.error("[svcntrl] Failed to list data directory", e);
        }
    }

    public void saveProjects() {
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
        for (Project project : projects.values()) {
            futures.add(saveProjectFuture(project));
        }
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
    }

    public void deleteBranchDir(Project project, String branchName) {
        if (dataDir == null) return;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Path branchDir = getProjectDir(project).resolve(branchName);
                if (Files.exists(branchDir)) {
                    Files.walkFileTree(branchDir, new java.nio.file.SimpleFileVisitor<Path>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                            Files.delete(file);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException {
                            Files.delete(dir);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
                }
            } catch (Throwable e) {
                SvcntrlMod.LOGGER.error("[svcntrl] Failed to delete branch dir " + branchName, e);
            }
        });
    }

    public void saveProject(Project project) {
        saveProjectFuture(project);
    }

    private java.util.concurrent.CompletableFuture<Void> saveProjectFuture(Project project) {
        if (dataDir == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        
        // Serialize synchronously on the calling thread to prevent ConcurrentModificationException
        JsonObject serialized;
        try {
            serialized = serializeProject(project);
        } catch (Exception e) {
            SvcntrlMod.LOGGER.error("[svcntrl] Failed to serialize project " + project.getName(), e);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        return saveTasks.compute(project.getName(), (k, oldFuture) -> {
            Runnable saveTask = () -> {
                try {
                    Path projectDir = getProjectDir(project);
                    Files.createDirectories(projectDir);
                    Path projectFile = projectDir.resolve("project.json");
                    Path tempFile = projectDir.resolve("project.json.tmp");
                    
                    try (Writer writer = Files.newBufferedWriter(tempFile)) {
                        Gson gson = new GsonBuilder().setPrettyPrinting().create();
                        gson.toJson(serialized, writer);
                    }
                    Files.move(tempFile, projectFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (Throwable e) {
                    SvcntrlMod.LOGGER.error("[svcntrl] Failed to save project " + project.getName(), e);
                }
            };
            if (oldFuture == null || oldFuture.isDone()) {
                return java.util.concurrent.CompletableFuture.runAsync(saveTask);
            } else {
                return oldFuture.thenRunAsync(saveTask);
            }
        });
    }

    private JsonObject serializeProject(Project project) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", project.getName());
        obj.addProperty("ownerUuid", project.getOwnerUuid().toString());
        obj.addProperty("ownerName", project.getOwnerName());
        obj.addProperty("worldId", project.getWorldId());

        obj.add("corner1", serializeBlockPos(project.getCorner1()));
        obj.add("corner2", serializeBlockPos(project.getCorner2()));

        JsonArray membersArr = new JsonArray();
        for (UUID uuid : project.getMembers()) {
            membersArr.add(uuid.toString());
        }
        obj.add("members", membersArr);

        obj.addProperty("currentBranch", project.getCurrentBranchName());
        JsonObject branchesObj = new JsonObject();
        for (Project.Branch branch : project.getBranches()) {
            JsonObject bObj = new JsonObject();
            bObj.addProperty("nextManualId", branch.getNextManualId());
            bObj.addProperty("nextAutoId", branch.getNextAutoId());
            bObj.add("manualSnapshots", serializeSnapshotList(branch.getManualSnapshots()));
            bObj.add("autoSnapshots", serializeSnapshotList(branch.getAutoSnapshots()));
            branchesObj.add(branch.getName(), bObj);
        }
        obj.add("branches", branchesObj);

        return obj;
    }

    private Project deserializeProject(JsonObject obj) {
        Project project = new Project();
        project.setName(obj.get("name").getAsString());
        project.setOwnerUuid(UUID.fromString(obj.get("ownerUuid").getAsString()));
        project.setOwnerName(obj.get("ownerName").getAsString());
        project.setWorldId(obj.get("worldId").getAsString());
        project.setCorner1(deserializeBlockPos(obj.getAsJsonObject("corner1")));
        project.setCorner2(deserializeBlockPos(obj.getAsJsonObject("corner2")));

        if (obj.has("members")) {
            for (JsonElement e : obj.getAsJsonArray("members")) {
                project.addMemberDirect(UUID.fromString(e.getAsString()));
            }
        }

        if (obj.has("branches")) {
            project.setCurrentBranchName(obj.get("currentBranch").getAsString());
            JsonObject branchesObj = obj.getAsJsonObject("branches");
            for (String bName : branchesObj.keySet()) {
                JsonObject bObj = branchesObj.getAsJsonObject(bName);
                Project.Branch branch = project.getOrCreateBranch(bName);
                branch.setNextManualId(bObj.get("nextManualId").getAsInt());
                branch.setNextAutoId(bObj.get("nextAutoId").getAsInt());
                if (bObj.has("manualSnapshots")) {
                    for (JsonElement e : bObj.getAsJsonArray("manualSnapshots")) {
                        branch.addManualSnapshotDirect(deserializeSnapshotMeta(e.getAsJsonObject()));
                    }
                }
                if (bObj.has("autoSnapshots")) {
                    for (JsonElement e : bObj.getAsJsonArray("autoSnapshots")) {
                        branch.addAutoSnapshotDirect(deserializeSnapshotMeta(e.getAsJsonObject()));
                    }
                }
            }
        } else {
            // Legacy Migration
            Project.Branch mainBranch = project.getOrCreateBranch("main");
            project.setCurrentBranchName("main");
            
            if (obj.has("nextManualId")) mainBranch.setNextManualId(obj.get("nextManualId").getAsInt());
            if (obj.has("nextAutoId")) mainBranch.setNextAutoId(obj.get("nextAutoId").getAsInt());

            if (obj.has("manualSnapshots")) {
                for (JsonElement e : obj.getAsJsonArray("manualSnapshots")) {
                    mainBranch.addManualSnapshotDirect(deserializeSnapshotMeta(e.getAsJsonObject()));
                }
            }
            if (obj.has("autoSnapshots")) {
                for (JsonElement e : obj.getAsJsonArray("autoSnapshots")) {
                    mainBranch.addAutoSnapshotDirect(deserializeSnapshotMeta(e.getAsJsonObject()));
                }
            }
        }

        return project;
    }

    private JsonObject serializeBlockPos(BlockPos pos) {
        JsonObject obj = new JsonObject();
        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());
        return obj;
    }

    private BlockPos deserializeBlockPos(JsonObject obj) {
        return new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
    }

    private JsonArray serializeSnapshotList(List<Project.SnapshotMeta> list) {
        JsonArray arr = new JsonArray();
        for (Project.SnapshotMeta meta : list) {
            JsonObject m = new JsonObject();
            m.addProperty("id", meta.getId());
            m.addProperty("description", meta.getDescription());
            m.addProperty("authorUuid", meta.getAuthorUuid().toString());
            m.addProperty("authorName", meta.getAuthorName());
            m.addProperty("timestamp", meta.getTimestamp());
            arr.add(m);
        }
        return arr;
    }

    private Project.SnapshotMeta deserializeSnapshotMeta(JsonObject obj) {
        return new Project.SnapshotMeta(
                obj.get("id").getAsInt(),
                obj.get("description").getAsString(),
                UUID.fromString(obj.get("authorUuid").getAsString()),
                obj.get("authorName").getAsString(),
                obj.get("timestamp").getAsLong()
        );
    }
}
