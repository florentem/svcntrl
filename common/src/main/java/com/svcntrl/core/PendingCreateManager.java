package com.svcntrl.core;

import com.svcntrl.data.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PendingCreateManager {

    private static final PendingCreateManager INSTANCE = new PendingCreateManager();

    public static class PendingCreate {
        public String projectName;
        public long expiryTime;
        public BlockPos pos1;
        public BlockPos pos2;
        public String dimension;

        public PendingCreate(String projectName, long expiryTime) {
            this.projectName = projectName;
            this.expiryTime = expiryTime;
        }
    }

    private final Map<UUID, PendingCreate> pending = new ConcurrentHashMap<>();

    private PendingCreateManager() {}

    public static PendingCreateManager getInstance() {
        return INSTANCE;
    }

    public boolean hasPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    public PendingCreate getPending(UUID uuid) {
        return pending.get(uuid);
    }

    public void startCreation(UUID uuid, String projectName) {
        pending.put(uuid, new PendingCreate(projectName, System.currentTimeMillis() + 60_000L));
    }

    public void removePlayer(UUID uuid) {
        pending.remove(uuid);
    }

    public boolean setPos1(UUID uuid, BlockPos pos, String dimension) {
        PendingCreate state = pending.get(uuid);
        if (state == null) return false;
        if (System.currentTimeMillis() > state.expiryTime) {
            pending.remove(uuid);
            return false;
        }
        if (state.dimension != null && !state.dimension.equals(dimension)) {
            state.pos2 = null;
        }
        state.pos1 = pos;
        state.dimension = dimension;
        return true;
    }

    public boolean setPos2(UUID uuid, BlockPos pos, String dimension) {
        PendingCreate state = pending.get(uuid);
        if (state == null) return false;
        if (System.currentTimeMillis() > state.expiryTime) {
            pending.remove(uuid);
            return false;
        }
        if (state.dimension != null && !state.dimension.equals(dimension)) {
            state.pos1 = null;
        }
        state.pos2 = pos;
        state.dimension = dimension;
        return true;
    }

    public void tick(Consumer<PendingCreate> onTimeout) {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> {
            if (now > entry.getValue().expiryTime) {
                if (onTimeout != null) {
                    onTimeout.accept(entry.getValue());
                }
                return true;
            }
            return false;
        });
    }
}
