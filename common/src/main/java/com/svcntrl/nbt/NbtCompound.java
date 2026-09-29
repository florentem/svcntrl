package com.svcntrl.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.*;

public class NbtCompound extends NbtTag {
    private final Map<String, NbtTag> data = new LinkedHashMap<>();

    public byte getId() {
        return TAG_COMPOUND;
    }

    public void put(String key, NbtTag tag) {
        if (tag != null) data.put(key, tag);
    }

    public void putInt(String key, int value) {
        put(key, new NbtInt(value));
    }

    public void putLong(String key, long value) {
        put(key, new NbtLong(value));
    }

    public void putDouble(String key, double value) {
        put(key, new NbtDouble(value));
    }

    public void putString(String key, String value) {
        put(key, new NbtString(value));
    }

    public void putIntArray(String key, int[] value) {
        put(key, new NbtIntArray(value));
    }

    public void putLongArray(String key, long[] value) {
        put(key, new NbtLongArray(value));
    }

    public NbtTag get(String key) {
        return data.get(key);
    }

    public boolean contains(String key) {
        return data.containsKey(key);
    }

    public int getInt(String key, int defaultValue) {
        NbtTag tag = data.get(key);
        if (tag instanceof NbtInt n) return n.value;
        if (tag instanceof NbtShort s) return s.value;
        if (tag instanceof NbtByte b) return b.value;
        return defaultValue;
    }

    public long getLong(String key, long defaultValue) {
        NbtTag tag = data.get(key);
        if (tag instanceof NbtLong l) return l.value;
        if (tag instanceof NbtInt n) return n.value;
        return defaultValue;
    }

    public double getDouble(String key, double defaultValue) {
        NbtTag tag = data.get(key);
        if (tag instanceof NbtDouble d) return d.value;
        if (tag instanceof NbtFloat f) return f.value;
        return defaultValue;
    }

    public String getString(String key, String defaultValue) {
        NbtTag tag = data.get(key);
        if (tag instanceof NbtString s) return s.value;
        return defaultValue;
    }

    public int[] getIntArray(String key) {
        NbtTag tag = data.get(key);
        if (tag instanceof NbtIntArray arr) return arr.value;
        return new int[0];
    }

    public long[] getLongArray(String key) {
        NbtTag tag = data.get(key);
        if (tag instanceof NbtLongArray arr) return arr.value;
        return new long[0];
    }

    public NbtCompound getCompoundOrEmpty(String key) {
        NbtTag tag = data.get(key);
        if (tag instanceof NbtCompound comp) return comp;
        return new NbtCompound();
    }

    public NbtList getListOrEmpty(String key) {
        NbtTag tag = data.get(key);
        if (tag instanceof NbtList list) return list;
        return new NbtList();
    }

    public Set<String> getKeys() {
        return data.keySet();
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    public void remove(String key) {
        data.remove(key);
    }

    @Override
    public void write(DataOutput out) throws IOException {
        for (Map.Entry<String, NbtTag> entry : data.entrySet()) {
            NbtTag tag = entry.getValue();
            out.writeByte(tag.getId());
            out.writeUTF(entry.getKey());
            tag.write(out);
        }
        out.writeByte(TAG_END);
    }

    @Override
    public NbtCompound copy() {
        NbtCompound copy = new NbtCompound();
        for (Map.Entry<String, NbtTag> entry : data.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NbtCompound that = (NbtCompound) o;
        return Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data);
    }
}
