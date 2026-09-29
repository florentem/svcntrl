package com.svcntrl.data;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class ProjectManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("svcntrl");
    private static final ProjectManager INSTANCE = new ProjectManager();
    private Path dataDir;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
        runnable -> {
            Thread t = new Thread(runnable, "Svcntrl-Worker");
            t.setDaemon(true);
            return t;
        }
    );

    public static ExecutorService getExecutor() {
        return EXECUTOR;
    }

    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, EXECUTOR);
    }

    public static <U> CompletableFuture<U> supplyAsync(java.util.function.Supplier<U> supplier) {
        return CompletableFuture.supplyAsync(supplier, EXECUTOR);
    }

    private final Map<String, Project> projects = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeProjects = new ConcurrentHashMap<>();
    private final Set<Project> lockedProjects = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> saveTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> autoUploadPrefs = new ConcurrentHashMap<>();
    private final Set<String> deletingProjects = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Set<Project>>> projectGrid = new ConcurrentHashMap<>();

    private long getRegionHash(int rx, int rz) {
        return ((long)rx & 0xFFFFFFFFL) | (((long)rz & 0xFFFFFFFFL) << 32);
    }

    private void addToGrid(Project project) {
        String dim = project.getWorldId();
        projectGrid.putIfAbsent(dim, new ConcurrentHashMap<>());
        ConcurrentHashMap<Long, Set<Project>> dimGrid = projectGrid.get(dim);
        int minRx = project.getMin().getX() >> 8;
        int maxRx = project.getMax().getX() >> 8;
        int minRz = project.getMin().getZ() >> 8;
        int maxRz = project.getMax().getZ() >> 8;
        for (int x = minRx; x <= maxRx; x++) {
            for (int z = minRz; z <= maxRz; z++) {
                long hash = getRegionHash(x, z);
                dimGrid.computeIfAbsent(hash, k -> ConcurrentHashMap.newKeySet()).add(project);
            }
        }
    }

    private void removeFromGrid(Project project) {
        String dim = project.getWorldId();
        ConcurrentHashMap<Long, Set<Project>> dimGrid = projectGrid.get(dim);
        if (dimGrid != null) {
            int minRx = project.getMin().getX() >> 8;
            int maxRx = project.getMax().getX() >> 8;
            int minRz = project.getMin().getZ() >> 8;
            int maxRz = project.getMax().getZ() >> 8;
            for (int x = minRx; x <= maxRx; x++) {
                for (int z = minRz; z <= maxRz; z++) {
                    long hash = getRegionHash(x, z);
                    Set<Project> set = dimGrid.get(hash);
                    if (set != null) {
                        set.remove(project);
                        if (set.isEmpty()) dimGrid.remove(hash);
                    }
                }
            }
        }
    }

    public Set<Project> getProjectsNearPos(String worldId, BlockPos pos, int radiusBlocks) {
        return getProjectsNearPos(worldId, pos.getX(), pos.getZ(), radiusBlocks);
    }

    public Set<Project> getProjectsNearPos(String worldId, int px, int pz, int radiusBlocks) {
        ConcurrentHashMap<Long, Set<Project>> dimGrid = projectGrid.get(worldId);
        if (dimGrid == null) return Collections.emptySet();
        int minRx = (px - radiusBlocks) >> 8;
        int maxRx = (px + radiusBlocks) >> 8;
        int minRz = (pz - radiusBlocks) >> 8;
        int maxRz = (pz + radiusBlocks) >> 8;
        Set<Project> result = new HashSet<>();
        for (int x = minRx; x <= maxRx; x++) {
            for (int z = minRz; z <= maxRz; z++) {
                Set<Project> cell = dimGrid.get(getRegionHash(x, z));
                if (cell != null) result.addAll(cell);
            }
        }
        return result;
    }

    private ProjectManager() {}

    public static ProjectManager getInstance() {
        return INSTANCE;
    }

    public Path getDataDir() { return dataDir; }

    public Path getProjectDir(Project project) {
        if (dataDir == null) throw new IllegalStateException("dataDir is not initialized");
        Path resolved = dataDir.resolve(project.getName().toLowerCase(Locale.ROOT)).normalize();
        if (!resolved.startsWith(dataDir.normalize())) throw new IllegalArgumentException("Path traversal attempt in project: " + project.getName());
        return resolved;
    }

    public Path getSnapshotPath(Project project, String branchName, String category, int id) {
        Path resolved = getProjectDir(project).resolve(branchName.toLowerCase(Locale.ROOT)).resolve(category).resolve("snapshot_" + id + ".nbt").normalize();
        if (!resolved.startsWith(dataDir.normalize())) throw new IllegalArgumentException("Path traversal attempt in branch: " + branchName);
        return resolved;
    }

    public Project getProject(String name) {
        if (name == null) return null;
        return projects.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Project> getProjects() {
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

    public Boolean getAutoUploadPref(UUID uuid) {
        return autoUploadPrefs.get(uuid);
    }

    public void setAutoUploadPref(UUID uuid, Boolean pref) {
        if (pref == null) {
            autoUploadPrefs.remove(uuid);
        } else {
            autoUploadPrefs.put(uuid, pref);
        }
        savePrefs();
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
        if (deletingProjects.contains(key) || projects.putIfAbsent(key, project) != null) {
            return false;
        }
        addToGrid(project);
        saveProject(project);
        return true;
    }

    public void removeProject(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Project project = projects.remove(key);
        if (project != null) {
            removeFromGrid(project);
            deletingProjects.add(key);
            Path projectDir = getProjectDir(project);
            Runnable deleteAction = () -> {
                if (Files.exists(projectDir)) {
                    try {
                        try (java.util.stream.Stream<Path> walk = Files.walk(projectDir)) {
                            walk.sorted(Comparator.reverseOrder())
                                .map(Path::toFile)
                                .forEach(f -> {
                                    if (!f.delete()) {
                                        LOGGER.error("Failed to delete file: {}", f.getAbsolutePath());
                                    }
                                });
                        }
                    } catch (Throwable e) {
                        LOGGER.error("Failed to delete project directory: {}", projectDir, e);
                    }
                }
            };
            Runnable finalDeleteAction = () -> {
                try {
                    deleteAction.run();
                } finally {
                    deletingProjects.remove(key);
                }
            };
            CompletableFuture<Void> oldFuture = saveTasks.remove(project.getName());
            if (oldFuture != null && !oldFuture.isDone()) {
                oldFuture.thenRunAsync(finalDeleteAction, EXECUTOR);
            } else {
                runAsync(finalDeleteAction);
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

    public void loadProjects(Path dir) {
        this.dataDir = dir;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            LOGGER.error("[svcntrl] Failed to create data directory", e);
            return;
        }
        projects.clear();
        activeProjects.clear();
        lockedProjects.clear();
        saveTasks.clear();

        try (java.util.stream.Stream<Path> stream = Files.list(dataDir)) {
            stream.filter(Files::isDirectory).forEach(projectDir -> {
                Path projectFile = projectDir.resolve("project.json");
                if (Files.exists(projectFile)) {
                    try (Reader reader = Files.newBufferedReader(projectFile)) {
                        JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                        Project project = deserializeProject(obj);
                        projects.put(project.getName().toLowerCase(Locale.ROOT), project);
                        addToGrid(project);
                    } catch (Exception e) {
                        LOGGER.error("[svcntrl] Failed to load project entry from {}", projectFile, e);
                    }
                }
            });
        } catch (IOException e) {
            LOGGER.error("[svcntrl] Failed to list data directory", e);
        }

        loadPrefs();
    }

    private void loadPrefs() {
        if (dataDir == null) return;
        Path prefsFile = dataDir.resolve("player_prefs.json");
        if (Files.exists(prefsFile)) {
            try (Reader reader = Files.newBufferedReader(prefsFile)) {
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                if (obj.has("autoUpload")) {
                    JsonObject uploads = obj.getAsJsonObject("autoUpload");
                    for (String key : uploads.keySet()) {
                        try {
                            autoUploadPrefs.put(UUID.fromString(key), uploads.get(key).getAsBoolean());
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                LOGGER.error("[svcntrl] Failed to load player prefs", e);
            }
        }
    }

    private CompletableFuture<Void> lastPrefsFuture = null;

    private void savePrefs() {
        if (dataDir == null) return;
        Path prefsFile = dataDir.resolve("player_prefs.json");
        JsonObject obj = new JsonObject();
        JsonObject uploads = new JsonObject();
        for (Map.Entry<UUID, Boolean> entry : autoUploadPrefs.entrySet()) {
            uploads.addProperty(entry.getKey().toString(), entry.getValue());
        }
        obj.add("autoUpload", uploads);

        Runnable saveTask = () -> {
            try {
                Files.writeString(prefsFile, new GsonBuilder().setPrettyPrinting().create().toJson(obj));
            } catch (IOException e) {
                LOGGER.error("[svcntrl] Failed to save player prefs", e);
            }
        };

        if (lastPrefsFuture == null || lastPrefsFuture.isDone()) {
            lastPrefsFuture = runAsync(saveTask);
        } else {
            lastPrefsFuture = lastPrefsFuture.thenRunAsync(saveTask, EXECUTOR);
        }
    }

    public void saveProjects() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Project project : projects.values()) {
            futures.add(saveProjectFuture(project));
        }
        if (lastPrefsFuture != null) {
            futures.add(lastPrefsFuture);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    public void saveProject(Project project) {
        saveProjectFuture(project);
    }

    private CompletableFuture<Void> saveProjectFuture(Project project) {
        if (dataDir == null) return CompletableFuture.completedFuture(null);

        JsonObject serialized;
        try {
            serialized = serializeProject(project);
        } catch (Exception e) {
            LOGGER.error("[svcntrl] Failed to serialize project " + project.getName(), e);
            return CompletableFuture.completedFuture(null);
        }

        return saveTasks.compute(project.getName(), (k, oldFuture) -> {
            Runnable saveTask = () -> {
                try {
                    Path projectDir = getProjectDir(project);
                    Files.createDirectories(projectDir);
                    Path projectFile = projectDir.resolve("project.json");
                    Path tempFile = projectDir.resolve("project.json.tmp");

                    try {
                        try (Writer writer = Files.newBufferedWriter(tempFile)) {
                            Gson gson = new GsonBuilder().setPrettyPrinting().create();
                            gson.toJson(serialized, writer);
                        }
                        Files.move(tempFile, projectFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (Throwable e) {
                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (IOException ignored) {}
                        throw e;
                    }
                } catch (Throwable e) {
                    LOGGER.error("[svcntrl] Failed to save project " + project.getName(), e);
                }
            };
            if (oldFuture == null || oldFuture.isDone()) {
                return runAsync(saveTask);
            } else {
                return oldFuture.thenRunAsync(saveTask, EXECUTOR);
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
            synchronized(branch.getManualSnapshots()) { bObj.add("manualSnapshots", serializeSnapshotList(branch.getManualSnapshots())); }
            synchronized(branch.getAutoSnapshots()) { bObj.add("autoSnapshots", serializeSnapshotList(branch.getAutoSnapshots())); }
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
