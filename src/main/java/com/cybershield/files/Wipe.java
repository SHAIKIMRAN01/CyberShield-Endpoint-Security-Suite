package com.cybershield.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Overwrites a file's contents before deleting it, so undelete tools find only the
 * overwrite. File Security uses zeros for originals it has just encrypted; the Privacy
 * Tools shredder uses random data and renames the file first, so its name doesn't linger
 * either. On SSDs and USB sticks the drive itself may keep old copies out of reach.
 */
public final class Wipe {

    private static final int BUFFER = 1024 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Wipe() {
    }

    /** Overwrites with zeros, flushes to the disk, then deletes. */
    public static void zeroAndDelete(Path file) throws IOException {

        overwrite(file, false);

        Files.delete(file);

    }

    /**
     * Shreds: renames to a random name in the same folder (failing here if another program
     * has it open), overwrites with random data, flushes, empties and deletes it.
     */
    public static void shred(Path file) throws IOException {

        byte[] name = new byte[12];

        RANDOM.nextBytes(name);

        Path renamed = file.resolveSibling(HexFormat.of().formatHex(name) + ".tmp");

        Files.move(file, renamed);

        try {

            overwrite(renamed, true);

            try (FileChannel channel = FileChannel.open(renamed, StandardOpenOption.WRITE)) {
                channel.truncate(0);
            }

        } catch (IOException e) {

            // Shredding was asked for: what is left still goes, rather than stay under a random name.
            try {
                Files.deleteIfExists(renamed);
            } catch (IOException alsoFailed) {
                e.addSuppressed(alsoFailed);
            }

            throw e;

        }

        Files.delete(renamed);

    }

    private static void overwrite(Path file, boolean random) throws IOException {

        try {
            Files.setAttribute(file, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            // Not Windows, or already writable.
        }

        long size = Files.size(file);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {

            byte[] fill = new byte[(int) Math.min(BUFFER, Math.max(1, size))];

            ByteBuffer buffer = ByteBuffer.wrap(fill);

            long position = 0;

            while (position < size) {

                if (random) {
                    RANDOM.nextBytes(fill);
                }

                buffer.clear();

                buffer.limit((int) Math.min(fill.length, size - position));

                position += channel.write(buffer, position);

            }

            channel.force(true);

        }

    }

}
