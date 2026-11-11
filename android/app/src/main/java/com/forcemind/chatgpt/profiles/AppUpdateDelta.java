package com.forcemind.chatgpt.profiles;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/** Rebuilds exact release bytes using bounded literal bytes and slices of the installed APK. */
final class AppUpdateDelta {
    private static final byte[] MAGIC = "STUDIOD1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_OPERATIONS = 16_384;

    interface Guard {
        void check() throws IOException;
    }

    private AppUpdateDelta() {}

    static boolean matchesBase(File base, String expected, Guard guard) throws Exception {
        if (Files.isSymbolicLink(base.toPath()) || !base.isFile() || base.length() < 1
                || base.length() > AppUpdateRelease.MAX_BYTES) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(base)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) {
                guard.check();
                digest.update(buffer, 0, count);
            }
        }
        return expected.equals(AppUpdateTransfer.hex(digest.digest()));
    }

    static void apply(File base, File patch, File destination, long targetSize, String targetDigest, Guard guard)
            throws Exception {
        if (targetSize < 1 || targetSize > AppUpdateRelease.MAX_BYTES || Files.exists(destination.toPath()))
            throw new IOException("差量输出路径或大小无效");
        Files.createFile(destination.toPath());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        try (RandomAccessFile original = new RandomAccessFile(base, "r");
             DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(patch)));
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new IOException("差量文件格式无效");
            long remaining = targetSize;
            for (int operations = 0; operations < MAX_OPERATIONS; operations++) {
                guard.check();
                int kind = input.readUnsignedByte();
                if (kind == 255) {
                    if (remaining != 0 || input.read() != -1 || !targetDigest.equals(AppUpdateTransfer.hex(digest.digest())))
                        throw new IOException("差量重建摘要不符");
                    output.getFD().sync();
                    return;
                }
                if (kind != 0 && kind != 1) throw new IOException("差量文件指令无效");
                long offset = kind == 1 ? input.readLong() : 0;
                int length = input.readInt();
                if (length < 1 || length > remaining || kind == 1
                        && (offset < 0 || offset > original.length() - length))
                    throw new IOException("差量文件范围无效");
                if (kind == 1) original.seek(offset);
                int left = length;
                while (left > 0) {
                    guard.check();
                    int chunk = Math.min(left, buffer.length);
                    if (kind == 1) original.readFully(buffer, 0, chunk);
                    else input.readFully(buffer, 0, chunk);
                    output.write(buffer, 0, chunk);
                    digest.update(buffer, 0, chunk);
                    left -= chunk;
                }
                remaining -= length;
            }
            throw new IOException("差量文件指令过多");
        } catch (Exception invalid) {
            Files.deleteIfExists(destination.toPath());
            throw invalid;
        }
    }
}
