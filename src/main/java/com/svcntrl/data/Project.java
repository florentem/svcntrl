package com.svcntrl.data;

import net.minecraft.util.math.BlockPos;

import java.util.*;

public class Project {

    private String name;
    private UUID ownerUuid;
    private String ownerName;

    private BlockPos corner1;
    private BlockPos corner2;
    private String worldId;

    private final Set<UUID> members = new HashSet<>();

    private String currentBranch = "main";
    private final Map<String, Branch> branches = new HashMap<>();

    private transient volatile boolean locked = false;

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public Project() {
        branches.put("main", new Branch("main"));
    }

    public Project(String name, UUID ownerUuid, String ownerName, BlockPos corner1, BlockPos corner2, String worldId) {
        this();
        this.name = name;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.worldId = worldId;

        this.corner1 = new BlockPos(
                Math.min(corner1.getX(), corner2.getX()),
                Math.min(corner1.getY(), corner2.getY()),
                Math.min(corner1.getZ(), corner2.getZ())
        );
        this.corner2 = new BlockPos(
                Math.max(corner1.getX(), corner2.getX()),
                Math.max(corner1.getY(), corner2.getY()),
                Math.max(corner1.getZ(), corner2.getZ())
        );
    }

    public Branch getBranch(String name) {
        return branches.get(name);
    }

    public Branch getOrCreateBranch(String name) {
        return branches.computeIfAbsent(name, Branch::new);
    }

    public Collection<Branch> getBranches() {
        return branches.values();
    }

    public String getCurrentBranchName() {
        return currentBranch;
    }

    public void setCurrentBranchName(String name) {
        this.currentBranch = name;
    }

    public boolean hasBranch(String name) {
        return branches.containsKey(name);
    }

    public void deleteBranch(String name) {
        branches.remove(name);
    }

    public int addManualSnapshot(String branchName, String description, UUID authorUuid, String authorName) {
        Branch branch = getOrCreateBranch(branchName);
        int id = branch.nextManualId++;
        branch.manualSnapshots.add(new SnapshotMeta(id, description, authorUuid, authorName, System.currentTimeMillis()));
        return id;
    }

    public int addAutoSnapshot(String branchName, String description, UUID authorUuid, String authorName) {
        Branch branch = getOrCreateBranch(branchName);
        int id = branch.nextAutoId++;
        branch.autoSnapshots.add(new SnapshotMeta(id, description, authorUuid, authorName, System.currentTimeMillis()));
        return id;
    }

    public void trimAutoSnapshots(String branchName, int... excludeIds) {
        Branch branch = getOrCreateBranch(branchName);
        while (branch.autoSnapshots.size() > 10) {
            // Find the oldest snapshot that is NOT in the excluded list
            SnapshotMeta toRemove = null;
            for (SnapshotMeta meta : branch.autoSnapshots) {
                boolean excluded = false;
                for (int ex : excludeIds) {
                    if (meta.getId() == ex) {
                        excluded = true;
                        break;
                    }
                }
                if (!excluded) {
                    toRemove = meta;
                    break;
                }
            }
            if (toRemove == null) break;
            
            branch.autoSnapshots.remove(toRemove);
            try {
                java.nio.file.Path snapshotFile = ProjectManager.getInstance().getSnapshotPath(this, branchName, "auto", toRemove.getId());
                java.nio.file.Files.deleteIfExists(snapshotFile);
            } catch (Exception e) {
                com.svcntrl.SvcntrlMod.LOGGER.error("Failed to delete old auto snapshot file: " + toRemove.getId(), e);
            }
        }
    }

    public boolean isOwner(UUID uuid) {
        return ownerUuid.equals(uuid);
    }

    public boolean isMember(UUID uuid) {
        return ownerUuid.equals(uuid) || members.contains(uuid);
    }

    public boolean addMember(UUID uuid) {
        return members.add(uuid);
    }

    public boolean removeMember(UUID uuid) {
        return members.remove(uuid);
    }

    public BlockPos getMin() {
        return corner1;
    }

    public BlockPos getMax() {
        return corner2;
    }

    public long getVolume() {
        return (long)(corner2.getX() - corner1.getX() + 1) * (long)(corner2.getY() - corner1.getY() + 1) * (long)(corner2.getZ() - corner1.getZ() + 1);
    }

    public boolean contains(net.minecraft.util.math.BlockPos pos) {
        return pos.getX() >= corner1.getX() && pos.getX() <= corner2.getX()
            && pos.getY() >= corner1.getY() && pos.getY() <= corner2.getY()
            && pos.getZ() >= corner1.getZ() && pos.getZ() <= corner2.getZ();
    }

    public String getName() { return name; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public BlockPos getCorner1() { return corner1; }
    public BlockPos getCorner2() { return corner2; }
    public String getWorldId() { return worldId; }
    public Set<UUID> getMembers() { return Collections.unmodifiableSet(members); }

    public void setName(String name) { this.name = name; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public void setCorner1(BlockPos corner1) { this.corner1 = corner1; }
    public void setCorner2(BlockPos corner2) { this.corner2 = corner2; }
    public void setWorldId(String worldId) { this.worldId = worldId; }

    public void addMemberDirect(UUID uuid) { members.add(uuid); }

    public static class SnapshotMeta {
        private int id;
        private final String description;
        private final UUID authorUuid;
        private final String authorName;
        private final long timestamp;

        public SnapshotMeta(int id, String description, UUID authorUuid, String authorName, long timestamp) {
            this.id = id;
            this.description = description;
            this.authorUuid = authorUuid;
            this.authorName = authorName;
            this.timestamp = timestamp;
        }

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getDescription() { return description; }
        public UUID getAuthorUuid() { return authorUuid; }
        public String getAuthorName() { return authorName; }
        public long getTimestamp() { return timestamp; }
    }

    public static class Branch {
        private String name;
        private int nextManualId = 1;
        private int nextAutoId = 1;
        private final List<SnapshotMeta> manualSnapshots = new ArrayList<>();
        private final List<SnapshotMeta> autoSnapshots = new LinkedList<>();

        public Branch(String name) {
            this.name = name;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getNextManualId() { return nextManualId; }
        public void setNextManualId(int nextManualId) { this.nextManualId = nextManualId; }
        public int getNextAutoId() { return nextAutoId; }
        public void setNextAutoId(int nextAutoId) { this.nextAutoId = nextAutoId; }
        public List<SnapshotMeta> getManualSnapshots() { return Collections.unmodifiableList(manualSnapshots); }
        public List<SnapshotMeta> getAutoSnapshots() { return Collections.unmodifiableList(autoSnapshots); }
        
        public void addManualSnapshotDirect(SnapshotMeta meta) { manualSnapshots.add(meta); }
        public void addAutoSnapshotDirect(SnapshotMeta meta) { autoSnapshots.add(meta); }
        public boolean removeManualSnapshot(int id) { return manualSnapshots.removeIf(s -> s.getId() == id); }
        public boolean removeAutoSnapshot(int id) { return autoSnapshots.removeIf(s -> s.getId() == id); }
    }
}
