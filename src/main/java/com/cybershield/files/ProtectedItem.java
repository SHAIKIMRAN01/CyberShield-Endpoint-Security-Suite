package com.cybershield.files;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One protected selection: a single file, or a whole folder. */
public final class ProtectedItem {

    public enum Type { FILE, FOLDER }

    /**
     * The details kept encrypted in protected_items.info.
     *
     * @param directories every sub-folder, relative to the folder, so empty ones come back on restore
     */
    public record Info(
            Type type,
            String originalPath,
            int fileCount,
            long totalBytes,
            List<String> directories
    ) {

        private static final byte VERSION = 1;

        public Info {
            directories = List.copyOf(directories);
        }

        Info withTotals(int files, long bytes) {
            return new Info(type, originalPath, files, bytes, directories);
        }

        byte[] toBytes() {

            try {

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                DataOutputStream out = new DataOutputStream(bytes);

                out.writeByte(VERSION);
                writeString(out, type.name());
                writeString(out, originalPath);
                out.writeInt(fileCount);
                out.writeLong(totalBytes);
                out.writeInt(directories.size());

                for (String directory : directories) {
                    writeString(out, directory);
                }

                return bytes.toByteArray();

            } catch (IOException e) {

                throw new UncheckedIOException(e);

            }

        }

        static Info fromBytes(byte[] data) throws IOException {

            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));

            if (in.readByte() != VERSION) {
                throw new IOException("Unsupported item version.");
            }

            Type type = Type.valueOf(readString(in));
            String originalPath = readString(in);
            int fileCount = in.readInt();
            long totalBytes = in.readLong();
            int directoryCount = in.readInt();

            List<String> directories = new ArrayList<>(directoryCount);

            for (int i = 0; i < directoryCount; i++) {
                directories.add(readString(in));
            }

            return new Info(type, originalPath, fileCount, totalBytes, directories);

        }

        private static void writeString(DataOutputStream out, String value) throws IOException {

            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

            out.writeInt(bytes.length);

            out.write(bytes);

        }

        private static String readString(DataInputStream in) throws IOException {

            byte[] bytes = new byte[in.readInt()];

            in.readFully(bytes);

            return new String(bytes, StandardCharsets.UTF_8);

        }

    }

    private final String id;

    private final Info info;

    private final int storedFiles;

    private final String createdAt;

    ProtectedItem(String id, Info info, int storedFiles, String createdAt) {

        this.id = id;
        this.info = info;
        this.storedFiles = storedFiles;
        this.createdAt = createdAt;

    }

    public String id() {
        return id;
    }

    public Info info() {
        return info;
    }

    public Type type() {
        return info.type();
    }

    /** How many encrypted files are in the store for this item right now. */
    public int storedFiles() {
        return storedFiles;
    }

    /** When it was protected, as stored by SQLite (UTC). */
    public String createdAt() {
        return createdAt;
    }

    public String name() {

        Path path = Path.of(info.originalPath());

        return path.getFileName() == null ? path.toString() : path.getFileName().toString();

    }

    /** The folder it was taken from. */
    public String location() {

        Path parent = Path.of(info.originalPath()).getParent();

        return parent == null ? "" : parent.toString();

    }

}
