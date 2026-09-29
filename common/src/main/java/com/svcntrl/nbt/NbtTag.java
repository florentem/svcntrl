package com.svcntrl.nbt;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public abstract class NbtTag {
    public static final byte TAG_END = 0;
    public static final byte TAG_BYTE = 1;
    public static final byte TAG_SHORT = 2;
    public static final byte TAG_INT = 3;
    public static final byte TAG_LONG = 4;
    public static final byte TAG_FLOAT = 5;
    public static final byte TAG_DOUBLE = 6;
    public static final byte TAG_BYTE_ARRAY = 7;
    public static final byte TAG_STRING = 8;
    public static final byte TAG_LIST = 9;
    public static final byte TAG_COMPOUND = 10;
    public static final byte TAG_INT_ARRAY = 11;
    public static final byte TAG_LONG_ARRAY = 12;

    public abstract byte getId();
    public abstract void write(DataOutput out) throws IOException;
    public abstract NbtTag copy();

    public static NbtTag read(byte id, DataInput in) throws IOException {
        switch (id) {
            case TAG_END: return null;
            case TAG_BYTE: return new NbtByte(in.readByte());
            case TAG_SHORT: return new NbtShort(in.readShort());
            case TAG_INT: return new NbtInt(in.readInt());
            case TAG_LONG: return new NbtLong(in.readLong());
            case TAG_FLOAT: return new NbtFloat(in.readFloat());
            case TAG_DOUBLE: return new NbtDouble(in.readDouble());
            case TAG_BYTE_ARRAY: {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                return new NbtByteArray(bytes);
            }
            case TAG_STRING: return new NbtString(in.readUTF());
            case TAG_LIST: {
                byte elemId = in.readByte();
                int len = in.readInt();
                NbtList list = new NbtList(elemId);
                for (int i = 0; i < len; i++) {
                    list.add(read(elemId, in));
                }
                return list;
            }
            case TAG_COMPOUND: {
                NbtCompound compound = new NbtCompound();
                while (true) {
                    byte tagId = in.readByte();
                    if (tagId == TAG_END) break;
                    String name = in.readUTF();
                    NbtTag tag = read(tagId, in);
                    if (tag != null) {
                        compound.put(name, tag);
                    }
                }
                return compound;
            }
            case TAG_INT_ARRAY: {
                int len = in.readInt();
                int[] ints = new int[len];
                for (int i = 0; i < len; i++) ints[i] = in.readInt();
                return new NbtIntArray(ints);
            }
            case TAG_LONG_ARRAY: {
                int len = in.readInt();
                long[] longs = new long[len];
                for (int i = 0; i < len; i++) longs[i] = in.readLong();
                return new NbtLongArray(longs);
            }
            default: throw new IOException("Unknown NBT tag type: " + id);
        }
    }

    public static class NbtByte extends NbtTag {
        public byte value;
        public NbtByte(byte value) { this.value = value; }
        public byte getId() { return TAG_BYTE; }
        public void write(DataOutput out) throws IOException { out.writeByte(value); }
        public NbtTag copy() { return new NbtByte(value); }
    }

    public static class NbtShort extends NbtTag {
        public short value;
        public NbtShort(short value) { this.value = value; }
        public byte getId() { return TAG_SHORT; }
        public void write(DataOutput out) throws IOException { out.writeShort(value); }
        public NbtTag copy() { return new NbtShort(value); }
    }

    public static class NbtInt extends NbtTag {
        public int value;
        public NbtInt(int value) { this.value = value; }
        public byte getId() { return TAG_INT; }
        public void write(DataOutput out) throws IOException { out.writeInt(value); }
        public NbtTag copy() { return new NbtInt(value); }
    }

    public static class NbtLong extends NbtTag {
        public long value;
        public NbtLong(long value) { this.value = value; }
        public byte getId() { return TAG_LONG; }
        public void write(DataOutput out) throws IOException { out.writeLong(value); }
        public NbtTag copy() { return new NbtLong(value); }
    }

    public static class NbtFloat extends NbtTag {
        public float value;
        public NbtFloat(float value) { this.value = value; }
        public byte getId() { return TAG_FLOAT; }
        public void write(DataOutput out) throws IOException { out.writeFloat(value); }
        public NbtTag copy() { return new NbtFloat(value); }
    }

    public static class NbtDouble extends NbtTag {
        public double value;
        public NbtDouble(double value) { this.value = value; }
        public byte getId() { return TAG_DOUBLE; }
        public void write(DataOutput out) throws IOException { out.writeDouble(value); }
        public NbtTag copy() { return new NbtDouble(value); }
    }

    public static class NbtString extends NbtTag {
        public String value;
        public NbtString(String value) { this.value = value != null ? value : ""; }
        public byte getId() { return TAG_STRING; }
        public void write(DataOutput out) throws IOException { out.writeUTF(value); }
        public NbtTag copy() { return new NbtString(value); }
    }

    public static class NbtByteArray extends NbtTag {
        public byte[] value;
        public NbtByteArray(byte[] value) { this.value = value; }
        public byte getId() { return TAG_BYTE_ARRAY; }
        public void write(DataOutput out) throws IOException {
            out.writeInt(value.length);
            out.write(value);
        }
        public NbtTag copy() { return new NbtByteArray(value.clone()); }
    }

    public static class NbtIntArray extends NbtTag {
        public int[] value;
        public NbtIntArray(int[] value) { this.value = value; }
        public byte getId() { return TAG_INT_ARRAY; }
        public void write(DataOutput out) throws IOException {
            out.writeInt(value.length);
            for (int v : value) out.writeInt(v);
        }
        public NbtTag copy() { return new NbtIntArray(value.clone()); }
    }

    public static class NbtLongArray extends NbtTag {
        public long[] value;
        public NbtLongArray(long[] value) { this.value = value; }
        public byte getId() { return TAG_LONG_ARRAY; }
        public void write(DataOutput out) throws IOException {
            out.writeInt(value.length);
            for (long v : value) out.writeLong(v);
        }
        public NbtTag copy() { return new NbtLongArray(value.clone()); }
    }
}
