package com.cybershield.encryption;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * What an encrypted file needs to be put back exactly where and how it was.
 * Stored encrypted inside the file itself, never in plain text.
 *
 * @param originalPath       full path the file was taken from
 * @param itemId             the protected item (selected file or folder) it belongs to
 * @param relativePath       path inside the selected folder, or the file name
 * @param size               size in bytes
 * @param lastModifiedMillis last-modified time
 * @param createdMillis      creation time
 * @param dosAttributes      Windows attributes, see the flag constants
 */
public record FileMetadata(
        String originalPath,
        String itemId,
        String relativePath,
        long size,
        long lastModifiedMillis,
        long createdMillis,
        int dosAttributes
) {

    public static final int READ_ONLY = 1;

    public static final int HIDDEN = 2;

    public static final int SYSTEM = 4;

    public static final int ARCHIVE = 8;

    private static final byte VERSION = 1;

    byte[] toBytes() {

        try {

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            DataOutputStream out = new DataOutputStream(bytes);

            out.writeByte(VERSION);
            out.writeUTF(originalPath);
            out.writeUTF(itemId);
            out.writeUTF(relativePath);
            out.writeLong(size);
            out.writeLong(lastModifiedMillis);
            out.writeLong(createdMillis);
            out.writeInt(dosAttributes);

            return bytes.toByteArray();

        } catch (IOException e) {

            throw new UncheckedIOException(e);

        }

    }

    static FileMetadata fromBytes(byte[] data) throws IOException {

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));

        if (in.readByte() != VERSION) {
            throw new IOException("Unsupported metadata version.");
        }

        return new FileMetadata(
                in.readUTF(),
                in.readUTF(),
                in.readUTF(),
                in.readLong(),
                in.readLong(),
                in.readLong(),
                in.readInt()
        );

    }

}
