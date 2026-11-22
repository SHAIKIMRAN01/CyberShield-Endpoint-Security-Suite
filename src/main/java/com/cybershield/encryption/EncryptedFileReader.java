package com.cybershield.encryption;

import com.cybershield.services.EncryptionService;

import javax.crypto.Cipher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads any part of a protected file without decrypting the rest. Each 1 MiB chunk is
 * decrypted and checked only when it is needed, and the last few stay in memory. Nothing
 * is written anywhere, and the file can be any size: this is how the viewer plays long
 * videos straight from the store.
 *
 * <p>Not thread-safe: give each reading thread its own reader.
 */
public final class EncryptedFileReader implements AutoCloseable {

    // Decoders mostly read forwards, with the odd jump back; four chunks cover that.
    private static final int CACHED_CHUNKS = 4;

    private final FileChannel channel;

    private final FileDecryptor.Opened opened;

    private final long dataStart;

    private final long size;

    private final long chunkCount;

    private final int lastPlainLength;

    private final byte[] cipherBuffer;

    private final Map<Long, byte[]> cache = new LinkedHashMap<>(8, 0.75f, true);

    private boolean closed;

    private EncryptedFileReader(FileChannel channel, FileDecryptor.Opened opened, long dataStart) throws IOException {

        this.channel = channel;
        this.opened = opened;
        this.dataStart = dataStart;
        this.size = opened.metadata().size();

        int cipherChunk = opened.chunkSize() + EncryptedFileFormat.TAG_BYTES;

        long dataLength = channel.size() - dataStart;

        if (dataLength < EncryptedFileFormat.TAG_BYTES) {
            throw new FileDecryptor.DamagedFileException("The encrypted file is cut short.");
        }

        // Worked out from the file's length, so it holds whether or not the last chunk is full.
        this.chunkCount = (dataLength + cipherChunk - 1) / cipherChunk;

        long lastCipher = dataLength - (chunkCount - 1) * cipherChunk;

        this.lastPlainLength = (int) (lastCipher - EncryptedFileFormat.TAG_BYTES);

        if (lastPlainLength < 0 || (chunkCount - 1) * opened.chunkSize() + lastPlainLength != size) {
            throw new FileDecryptor.DamagedFileException("The encrypted file doesn't match its recorded size.");
        }

        this.cipherBuffer = new byte[cipherChunk];

    }

    /** Opens a protected file for reading. The vault must be unlocked; afterwards it needn't stay so. */
    public static EncryptedFileReader open(Path encrypted, EncryptionService vaultKey) throws IOException {

        FileChannel channel = FileChannel.open(encrypted, StandardOpenOption.READ);

        try {

            CountingStream counting = new CountingStream(Channels.newInputStream(channel));

            FileDecryptor.Opened opened = FileDecryptor.open(counting, vaultKey);

            return new EncryptedFileReader(channel, opened, counting.count);

        } catch (IOException | RuntimeException e) {

            channel.close();

            throw e;

        }

    }

    /** The plain-text size. */
    public long size() {
        return size;
    }

    public FileMetadata metadata() {
        return opened.metadata();
    }

    /**
     * Copies up to length bytes from position into target, and returns how many were
     * copied, or -1 at the end. Never more than the rest of the chunk at position.
     */
    public int read(long position, byte[] target, int offset, int length) throws IOException {

        if (closed) {
            throw new IOException("The file is closed.");
        }

        if (position < 0) {
            throw new IllegalArgumentException("position " + position);
        }

        if (position >= size) {
            return -1;
        }

        if (length == 0) {
            return 0;
        }

        long index = position / opened.chunkSize();

        byte[] plain = chunk(index);

        int within = (int) (position - index * opened.chunkSize());

        int count = Math.min(length, plain.length - within);

        System.arraycopy(plain, within, target, offset, count);

        return count;

    }

    /** A stream over the plain text that can jump anywhere at no cost; closing it closes the reader. */
    public InputStream stream() {
        return new Stream();
    }

    private byte[] chunk(long index) throws IOException {

        byte[] plain = cache.get(index);

        if (plain != null) {
            return plain;
        }

        boolean last = index == chunkCount - 1;

        int plainLength = last ? lastPlainLength : opened.chunkSize();

        int cipherLength = plainLength + EncryptedFileFormat.TAG_BYTES;

        long offset = dataStart + index * (long) cipherBuffer.length;

        ByteBuffer into = ByteBuffer.wrap(cipherBuffer, 0, cipherLength);

        while (into.hasRemaining()) {
            if (channel.read(into, offset + into.position()) < 0) {
                throw new FileDecryptor.DamagedFileException("The encrypted file is cut short.");
            }
        }

        plain = new byte[plainLength];

        try {

            Cipher cipher = EncryptedFileFormat.chunkCipher(Cipher.DECRYPT_MODE, opened.fileKey(), index, last, opened.header());

            int written = cipher.doFinal(cipherBuffer, 0, cipherLength, plain, 0);

            if (written != plainLength) {
                throw new FileDecryptor.DamagedFileException("The encrypted file is damaged or was changed.");
            }

        } catch (GeneralSecurityException e) {

            Arrays.fill(plain, (byte) 0);

            throw new FileDecryptor.DamagedFileException("The encrypted file is damaged or was changed.", e);

        }

        cache.put(index, plain);

        if (cache.size() > CACHED_CHUNKS) {
            Iterator<byte[]> oldest = cache.values().iterator();
            Arrays.fill(oldest.next(), (byte) 0);
            oldest.remove();
        }

        return plain;

    }

    /** Closes the file and wipes the decrypted chunks still held. */
    @Override
    public void close() throws IOException {

        if (closed) {
            return;
        }

        closed = true;

        for (byte[] plain : cache.values()) {
            Arrays.fill(plain, (byte) 0);
        }

        cache.clear();

        Arrays.fill(cipherBuffer, (byte) 0);

        channel.close();

    }

    /** Reads forwards like any stream, while mark, reset and skip jump without reading. */
    private final class Stream extends InputStream {

        private long position;

        private long mark;

        @Override
        public int read() throws IOException {

            byte[] one = new byte[1];

            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;

        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {

            int count = EncryptedFileReader.this.read(position, target, offset, length);

            if (count > 0) {
                position += count;
            }

            return count;

        }

        @Override
        public long skip(long count) {

            long skipped = Math.max(0, Math.min(count, size - position));

            position += skipped;

            return skipped;

        }

        @Override
        public int available() {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, size - position));
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int readLimit) {
            mark = position;
        }

        @Override
        public void reset() {
            position = mark;
        }

        @Override
        public void close() throws IOException {
            EncryptedFileReader.this.close();
        }

    }

    /** Counts what the header check reads, to know where the data chunks start. */
    private static final class CountingStream extends InputStream {

        private final InputStream in;

        private long count;

        CountingStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {

            int value = in.read();

            if (value >= 0) {
                count++;
            }

            return value;

        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {

            int read = in.read(target, offset, length);

            if (read > 0) {
                count += read;
            }

            return read;

        }

    }

}
