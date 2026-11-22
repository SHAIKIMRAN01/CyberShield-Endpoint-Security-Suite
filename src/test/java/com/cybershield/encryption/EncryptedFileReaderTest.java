package com.cybershield.encryption;

import com.cybershield.services.EncryptionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading any part of a protected file without decrypting the rest: what streamed playback relies on. */
class EncryptedFileReaderTest {

    private static final int CHUNK = 1024 * 1024;

    private static final int TAG = 16;

    private final EncryptionService key = EncryptionService.getInstance();

    private final byte[] salt = random(16);

    @TempDir
    Path dir;

    @BeforeEach
    void openKey() {
        key.useKey(new SecretKeySpec(random(32), "AES"));
    }

    @AfterEach
    void dropKey() {
        key.clear();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 5})
    void anyPartReadsBackExactly(int size) throws Exception {

        byte[] data = random(size);

        Path blob = encrypt(data);

        try (EncryptedFileReader reader = EncryptedFileReader.open(blob, key)) {

            assertEquals(size, reader.size());

            assertEquals(size, reader.metadata().size());

            long[] positions = {0, 1, CHUNK - 3, CHUNK, CHUNK + 7, 2L * CHUNK - 1, 3L * CHUNK, size - 2L, size - 1L};

            for (long position : positions) {

                if (position < 0 || position >= size) {
                    continue;
                }

                byte[] read = readAt(reader, position, 64 * 1024);

                byte[] expected = Arrays.copyOfRange(data, (int) position, (int) Math.min(size, position + 64 * 1024));

                assertArrayEquals(expected, read, "bytes at " + position);

            }

            assertEquals(-1, reader.read(size, new byte[8], 0, 8), "nothing past the end");

            try (InputStream all = EncryptedFileReader.open(blob, key).stream()) {
                assertArrayEquals(data, all.readAllBytes(), "read straight through");
            }

        }

    }

    @Test
    void theStreamJumpsWithSkipMarkAndReset() throws Exception {

        byte[] data = random(3 * CHUNK + 5);

        try (InputStream in = EncryptedFileReader.open(encrypt(data), key).stream()) {

            assertTrue(in.markSupported());

            in.mark(Integer.MAX_VALUE);

            assertEquals(data.length, in.skip(Long.MAX_VALUE), "skipping to the end says how far it was");
            assertEquals(0, in.skip(10));
            assertEquals(-1, in.read());

            in.reset();

            assertEquals(2L * CHUNK + 3, in.skip(2L * CHUNK + 3));
            assertEquals(data[2 * CHUNK + 3] & 0xFF, in.read());
            assertEquals(data.length - (2 * CHUNK + 4), in.available());

            in.reset();

            assertEquals(data[0] & 0xFF, in.read(), "reset goes back to the mark");

        }

    }

    @Test
    void aChangedChunkIsRefusedWhileTheOthersStillRead() throws Exception {

        byte[] data = random(3 * CHUNK + 5);

        Path blob = encrypt(data);

        // From the end: the 5-byte last chunk, then three full ones. Change a byte in the second.
        long secondChunk = Files.size(blob) - (5 + TAG) - 2L * (CHUNK + TAG);

        try (RandomAccessFile file = new RandomAccessFile(blob.toFile(), "rw")) {
            file.seek(secondChunk + 100);
            int value = file.read();
            file.seek(secondChunk + 100);
            file.write(value ^ 0x01);
        }

        try (EncryptedFileReader reader = EncryptedFileReader.open(blob, key)) {

            assertArrayEquals(Arrays.copyOfRange(data, 0, 100), readAt(reader, 0, 100), "the first chunk is fine");

            assertThrows(FileDecryptor.DamagedFileException.class, () -> readAt(reader, CHUNK + 10, 100));

            assertArrayEquals(Arrays.copyOfRange(data, 2 * CHUNK, 2 * CHUNK + 100), readAt(reader, 2L * CHUNK, 100),
                    "the chunk after it is fine too");

        }

    }

    @Test
    void aCutShortFileOrAnotherKeyIsRefusedAtOnce() throws Exception {

        Path blob = encrypt(random(2 * CHUNK + 300));

        byte[] whole = Files.readAllBytes(blob);

        Path cut = Files.write(dir.resolve("cut"), Arrays.copyOf(whole, whole.length - 10));

        assertThrows(FileDecryptor.DamagedFileException.class, () -> EncryptedFileReader.open(cut, key));

        Path cutAtChunk = Files.write(dir.resolve("cut-at-chunk"), Arrays.copyOf(whole, whole.length - (300 + TAG)));

        assertThrows(FileDecryptor.DamagedFileException.class, () -> EncryptedFileReader.open(cutAtChunk, key),
                "losing the whole last chunk is noticed too");

        key.useKey(new SecretKeySpec(random(32), "AES"));

        assertThrows(FileDecryptor.DamagedFileException.class, () -> EncryptedFileReader.open(blob, key));

    }

    private Path encrypt(byte[] data) throws Exception {

        Path source = Files.write(dir.resolve("source-" + data.length), data);

        Path blob = dir.resolve("blob-" + data.length);

        FileMetadata metadata = new FileMetadata(source.toString(), "item", source.getFileName().toString(), data.length, 0, 0, 0);

        FileEncryptionService.encrypt(source, blob, metadata, salt, key, FileEncryptionService.Progress.NONE);

        return blob;

    }

    private static byte[] readAt(EncryptedFileReader reader, long position, int length) throws Exception {

        byte[] target = new byte[(int) Math.min(length, reader.size() - position)];

        int done = 0;

        while (done < target.length) {
            int read = reader.read(position + done, target, done, target.length - done);
            if (read < 0) {
                break;
            }
            done += read;
        }

        return Arrays.copyOf(target, done);

    }

}
