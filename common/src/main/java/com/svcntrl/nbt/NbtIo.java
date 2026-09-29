package com.svcntrl.nbt;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class NbtIo {

    public static NbtCompound readCompressed(Path path) throws IOException {
        try (InputStream fis = Files.newInputStream(path);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GZIPInputStream gis = new GZIPInputStream(bis);
             DataInputStream dis = new DataInputStream(gis)) {
            byte type = dis.readByte();
            if (type != NbtTag.TAG_COMPOUND) {
                throw new IOException("Root tag of NBT file must be a CompoundTag (got " + type + ")");
            }
            dis.readUTF(); // Root tag name (usually empty or "Schematic" / "")
            return (NbtCompound) NbtTag.read(NbtTag.TAG_COMPOUND, dis);
        }
    }

    public static void writeCompressed(NbtCompound compound, Path path) throws IOException {
        try (OutputStream fos = Files.newOutputStream(path);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             GZIPOutputStream gos = new GZIPOutputStream(bos);
             DataOutputStream dos = new DataOutputStream(gos)) {
            dos.writeByte(NbtTag.TAG_COMPOUND);
            dos.writeUTF(""); // Root tag name
            compound.write(dos);
        }
    }
}
