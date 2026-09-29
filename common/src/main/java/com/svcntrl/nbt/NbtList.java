package com.svcntrl.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public class NbtList extends NbtTag implements Iterable<NbtTag> {
    private byte elementId;
    private final List<NbtTag> list = new ArrayList<>();

    public NbtList() {
        this.elementId = TAG_END;
    }

    public NbtList(byte elementId) {
        this.elementId = elementId;
    }

    @Override
    public byte getId() {
        return TAG_LIST;
    }

    public byte getElementId() {
        return elementId;
    }

    public int size() {
        return list.size();
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    public void add(NbtTag tag) {
        if (tag == null) return;
        if (list.isEmpty()) {
            this.elementId = tag.getId();
        }
        list.add(tag);
    }

    public NbtTag get(int index) {
        return list.get(index);
    }

    public NbtCompound getCompoundOrEmpty(int index) {
        if (index >= 0 && index < list.size()) {
            NbtTag tag = list.get(index);
            if (tag instanceof NbtCompound comp) return comp;
        }
        return new NbtCompound();
    }

    @Override
    public Iterator<NbtTag> iterator() {
        return list.iterator();
    }

    @Override
    public void write(DataOutput out) throws IOException {
        if (list.isEmpty()) {
            out.writeByte(TAG_END);
            out.writeInt(0);
        } else {
            out.writeByte(elementId);
            out.writeInt(list.size());
            for (NbtTag tag : list) {
                tag.write(out);
            }
        }
    }

    @Override
    public NbtList copy() {
        NbtList copy = new NbtList(elementId);
        for (NbtTag tag : list) {
            copy.add(tag.copy());
        }
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NbtList tags = (NbtList) o;
        return elementId == tags.elementId && Objects.equals(list, tags.list);
    }

    @Override
    public int hashCode() {
        return Objects.hash(elementId, list);
    }
}
