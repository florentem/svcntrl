package com.svcntrl.data;

import java.util.Objects;

/**
 * Platform-agnostic 3D integer coordinates.
 */
public class BlockPos {
    private final int x;
    private final int y;
    private final int z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    public String toShortString() {
        return x + ", " + y + ", " + z;
    }

    public double distanceSquared(BlockPos other) {
        double dx = (double) this.x - other.x;
        double dy = (double) this.y - other.y;
        double dz = (double) this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceSquared(double ox, double oy, double oz) {
        double dx = (double) this.x - ox;
        double dy = (double) this.y - oy;
        double dz = (double) this.z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlockPos blockPos = (BlockPos) o;
        return x == blockPos.x && y == blockPos.y && z == blockPos.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return "BlockPos{" + x + ", " + y + ", " + z + "}";
    }
}
