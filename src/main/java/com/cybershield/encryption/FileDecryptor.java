package com.cybershield.encryption;

import com.cybershield.services.EncryptionService;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * Reads files written by {@link FileEncryptionService}. Every block is checked
 * before any of its bytes are handed out, so damaged or changed files are
 * rejected instead of producing wrong output.
 */
public final class FileDecryptor {

    /** The file is damaged, was changed, or was locked with a different master key. */
    public static final class DamagedFileException extends IOException {

        public DamagedFileException(String message) {
            super(message);
        }

        public DamagedFileException(String message, Throwable cause) {
            super(message, cause);
        }

    }

    /** The file is bigger than the caller is willing to hold in memory. */
    public static final class TooLargeException extends IOException {

        private final long size;

        public TooLargeException(long size) {
            super("The file is too large to open in memory.");
            this.size = size;
        }

        public long size() {
            return size;
        }

    }

    /** A file whose header and details have been read and checked: what EncryptedFileReader starts from. */
    record Opened(byte[] header, SecretKey fileKey, FileMetadata metadata, int chunkSize) {
    }

    /** Creates the in-memory destination once the plain-text size is known. */
    private interface SinkFactory {
        PositionSink create(int size) throws IOException;
    }

    /** An output that refuses to grow past the size the file says it has. */
    private abstract static class PositionSink extends OutputStream {

        abstract long position();

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

    }

    private FileDecryptor() {
    }

    /** Reads just the header and metadata. The vault must be unlocked. */
    public static FileMetadata readMetadata(Path encrypted, EncryptionService vaultKey) throws IOException {

        try (InputStream in = new BufferedInputStream(Files.newInputStream(encrypted))) {

            return open(in, vaultKey).metadata();

        }

    }

    /**
     * Returns the vault salt from the header. Together with the master password it
     * rebuilds the vault key, even without the database.
     */
    public static byte[] readVaultSalt(Path encrypted) throws IOException {

        try (InputStream in = Files.newInputStream(encrypted)) {

            ByteBuffer header = ByteBuffer.wrap(readHeader(in));

            header.position(EncryptedFileFormat.MAGIC.length + Integer.BYTES);

            byte[] salt = new byte[EncryptedFileFormat.SALT_BYTES];

            header.get(salt);

            return salt;

        }

    }

    /**
     * Decrypts into sink, checking every block. Returns the SHA-256 of the plain text.
     *
     * @throws DamagedFileException  if any block fails its check
     * @throws CancellationException if progress asks to stop
     */
    public static byte[] decrypt(
            Path encrypted,
            OutputStream sink,
            EncryptionService vaultKey,
            FileEncryptionService.Progress progress
    ) throws IOException {

        MessageDigest sha256 = FileEncryptionService.sha256();

        try (InputStream in = new BufferedInputStream(Files.newInputStream(encrypted), 64 * 1024)) {

            decryptChunks(in, open(in, vaultKey), sink, sha256, progress);

        }

        return sha256.digest();

    }

    /**
     * Decrypts the whole file into a byte array, for viewing. Nothing is
     * written to disk.
     *
     * @throws TooLargeException if the file is bigger than maxBytes
     */
    public static byte[] decryptToBytes(Path encrypted, EncryptionService vaultKey, long maxBytes) throws IOException {

        byte[][] holder = new byte[1][];

        decryptBounded(encrypted, vaultKey, maxBytes, size -> {

            byte[] array = new byte[size];

            holder[0] = array;

            return new PositionSink() {

                private int position;

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (len > array.length - position) {
                        throw new DamagedFileException("The encrypted file is longer than its recorded size.");
                    }
                    System.arraycopy(b, off, array, position, len);
                    position += len;
                }

                @Override
                long position() {
                    return position;
                }

            };

        });

        return holder[0];

    }

    /**
     * Decrypts the whole file into memory outside the Java heap, for media
     * playback. Nothing is written to disk.
     *
     * @throws TooLargeException if the file is bigger than maxBytes, or there isn't enough memory
     */
    public static ByteBuffer decryptToDirectBuffer(Path encrypted, EncryptionService vaultKey, long maxBytes)
            throws IOException {

        ByteBuffer[] holder = new ByteBuffer[1];

        decryptBounded(encrypted, vaultKey, maxBytes, size -> {

            ByteBuffer buffer;

            try {
                buffer = ByteBuffer.allocateDirect(size);
            } catch (OutOfMemoryError e) {
                throw new TooLargeException(size);
            }

            holder[0] = buffer;

            return new PositionSink() {

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (len > buffer.remaining()) {
                        throw new DamagedFileException("The encrypted file is longer than its recorded size.");
                    }
                    buffer.put(b, off, len);
                }

                @Override
                long position() {
                    return buffer.position();
                }

            };

        });

        return holder[0].flip();

    }

    private static void decryptBounded(Path encrypted, EncryptionService vaultKey, long maxBytes, SinkFactory sinks)
            throws IOException {

        try (InputStream in = new BufferedInputStream(Files.newInputStream(encrypted), 64 * 1024)) {

            Opened opened = open(in, vaultKey);

            long size = opened.metadata().size();

            if (size > maxBytes || size > Integer.MAX_VALUE - 64) {
                throw new TooLargeException(size);
            }

            PositionSink sink = sinks.create((int) size);

            decryptChunks(in, opened, sink, null, FileEncryptionService.Progress.NONE);

            if (sink.position() != size) {
                throw new DamagedFileException("The encrypted file doesn't match its recorded size.");
            }

        }

    }

    /** Decrypts every data chunk after the header and metadata, checking each one. */
    private static void decryptChunks(
            InputStream in,
            Opened opened,
            OutputStream sink,
            MessageDigest sha256,
            FileEncryptionService.Progress progress
    ) throws IOException {

        int cipherChunk = opened.chunkSize() + EncryptedFileFormat.TAG_BYTES;

        byte[] current = new byte[cipherChunk];

        byte[] next = new byte[cipherChunk];

        byte[] plain = new byte[cipherChunk];

        int currentLength = EncryptedFileFormat.readFully(in, current, cipherChunk);

        long index = 0;

        try {

            while (true) {

                if (progress.isCancelled()) {
                    throw new CancellationException();
                }

                if (currentLength < EncryptedFileFormat.TAG_BYTES) {
                    throw new DamagedFileException("The encrypted file is cut short.");
                }

                // The chunk right before end-of-file must be the one marked as last.
                int nextLength = currentLength == cipherChunk
                        ? EncryptedFileFormat.readFully(in, next, cipherChunk)
                        : 0;

                boolean last = nextLength == 0;

                int plainLength;

                try {

                    plainLength = EncryptedFileFormat
                            .chunkCipher(Cipher.DECRYPT_MODE, opened.fileKey(), index++, last, opened.header())
                            .doFinal(current, 0, currentLength, plain, 0);

                } catch (GeneralSecurityException e) {

                    throw new DamagedFileException("The encrypted file is damaged or was changed.", e);

                }

                sink.write(plain, 0, plainLength);

                if (sha256 != null) {
                    sha256.update(plain, 0, plainLength);
                }

                progress.bytesDone(plainLength);

                if (last) {
                    break;
                }

                byte[] swap = current;
                current = next;
                next = swap;
                currentLength = nextLength;

            }

        } finally {

            // Don't leave the last plain-text chunk lying around in memory.
            Arrays.fill(plain, (byte) 0);

        }

    }

    private static byte[] readHeader(InputStream in) throws IOException {

        byte[] header = new byte[EncryptedFileFormat.HEADER_BYTES];

        if (EncryptedFileFormat.readFully(in, header, header.length) != header.length
                || !Arrays.equals(Arrays.copyOf(header, EncryptedFileFormat.MAGIC.length), EncryptedFileFormat.MAGIC)) {

            throw new DamagedFileException("Not a CyberShield encrypted file.");

        }

        return header;

    }

    static Opened open(InputStream in, EncryptionService vaultKey) throws IOException {

        byte[] header = readHeader(in);

        ByteBuffer fields = ByteBuffer.wrap(header);

        fields.position(EncryptedFileFormat.MAGIC.length);

        int chunkSize = fields.getInt();

        if (chunkSize < EncryptedFileFormat.MIN_CHUNK_SIZE || chunkSize > EncryptedFileFormat.MAX_CHUNK_SIZE) {
            throw new DamagedFileException("The encrypted file has a damaged header.");
        }

        fields.position(fields.position() + EncryptedFileFormat.SALT_BYTES);

        byte[] wrappedKey = new byte[EncryptedFileFormat.WRAPPED_KEY_BYTES];

        fields.get(wrappedKey);

        byte[] keyBytes;

        try {

            keyBytes = vaultKey.decryptBytes(wrappedKey);

        } catch (IllegalStateException locked) {

            throw locked;

        } catch (RuntimeException e) {

            throw new DamagedFileException(
                    "This file was locked with a different master password, or it is damaged.", e);

        }

        if (keyBytes.length != EncryptedFileFormat.FILE_KEY_BYTES) {
            throw new DamagedFileException("The encrypted file has a damaged key.");
        }

        SecretKey fileKey = new SecretKeySpec(keyBytes, CryptoConstants.AES_ALGORITHM);

        Arrays.fill(keyBytes, (byte) 0);

        byte[] lengthBytes = new byte[Integer.BYTES];

        if (EncryptedFileFormat.readFully(in, lengthBytes, lengthBytes.length) != lengthBytes.length) {
            throw new DamagedFileException("The encrypted file is cut short.");
        }

        int metadataLength = ByteBuffer.wrap(lengthBytes).getInt();

        if (metadataLength <= EncryptedFileFormat.TAG_BYTES || metadataLength > EncryptedFileFormat.MAX_METADATA_BYTES) {
            throw new DamagedFileException("The encrypted file has damaged details.");
        }

        byte[] encryptedMetadata = new byte[metadataLength];

        if (EncryptedFileFormat.readFully(in, encryptedMetadata, metadataLength) != metadataLength) {
            throw new DamagedFileException("The encrypted file is cut short.");
        }

        try {

            FileMetadata metadata = FileMetadata.fromBytes(
                    EncryptedFileFormat
                            .metadataCipher(Cipher.DECRYPT_MODE, fileKey, header)
                            .doFinal(encryptedMetadata)
            );

            return new Opened(header, fileKey, metadata, chunkSize);

        } catch (GeneralSecurityException e) {

            throw new DamagedFileException("The encrypted file is damaged or was changed.", e);

        }

    }

}
