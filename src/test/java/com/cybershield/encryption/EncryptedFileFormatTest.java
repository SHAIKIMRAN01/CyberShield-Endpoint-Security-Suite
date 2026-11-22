package com.cybershield.encryption;

import com.cybershield.services.EncryptionService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CSF1 format of protected files: round trips, and every kind of tampering is refused. */
class EncryptedFileFormatTest {

    private static final int CHUNK = 1024 * 1024;

    private static final String MARKER = "SECRET-MARKER-7f3a9c";

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
    void roundTripKeepsEveryByte(int size) throws Exception {

        byte[] data = random(size);

        Path source = Files.write(dir.resolve("source"), data);

        Path blob = dir.resolve("blob");

        byte[] written = FileEncryptionService.encrypt(source, blob, meta(source, size), salt, key, FileEncryptionService.Progress.NONE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        byte[] read = FileDecryptor.decrypt(blob, out, key, FileEncryptionService.Progress.NONE);

        assertArrayEquals(data, out.toByteArray());

        assertArrayEquals(written, read);

        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data), read);

    }

    @Test
    void tamperingIsAlwaysRefused() throws Exception {

        byte[] data = random(3 * CHUNK + 5);

        byte[] marker = MARKER.getBytes(StandardCharsets.UTF_8);

        System.arraycopy(marker, 0, data, CHUNK + 100, marker.length);

        Path source = Files.write(dir.resolve("marked"), data);

        Path blob = dir.resolve("marked.blob");

        FileEncryptionService.encrypt(source, blob, meta(source, data.length), salt, key, FileEncryptionService.Progress.NONE);

        byte[] good = Files.readAllBytes(blob);

        assertEquals(-1, indexOf(good, marker), "the encrypted file must not contain the plain text");

        assertEquals(source.toString(), FileDecryptor.readMetadata(blob, key).originalPath());

        assertArrayEquals(salt, FileDecryptor.readVaultSalt(blob));

        int dataStart = good.length - (3 * (CHUNK + 16)) - (5 + 16);

        assertRefused(flip(good, dataStart + CHUNK + 50), "a flipped byte in a chunk");
        assertRefused(Arrays.copyOf(good, good.length - 10), "a cut-off end");
        assertRefused(Arrays.copyOf(good, good.length - (5 + 16)), "a missing last chunk");
        assertRefused(flip(good, 10), "a changed header");
        assertRefused(Arrays.copyOf(good, good.length + 40), "extra bytes at the end");

        byte[] swapped = good.clone();
        System.arraycopy(good, dataStart, swapped, dataStart + CHUNK + 16, CHUNK + 16);
        System.arraycopy(good, dataStart + CHUNK + 16, swapped, dataStart, CHUNK + 16);
        assertRefused(swapped, "swapped chunks");

    }

    @Test
    void anotherVaultKeyCantOpenIt() throws Exception {

        Path source = Files.write(dir.resolve("source"), random(1000));

        Path blob = dir.resolve("blob");

        FileEncryptionService.encrypt(source, blob, meta(source, 1000), salt, key, FileEncryptionService.Progress.NONE);

        key.useKey(new SecretKeySpec(random(32), "AES"));

        assertThrows(FileDecryptor.DamagedFileException.class, () -> FileDecryptor.readMetadata(blob, key));

    }

    @Test
    void encryptingWhileLockedIsRefusedAndLeavesNoFile() throws Exception {

        Path source = Files.write(dir.resolve("source"), random(1000));

        Path blob = dir.resolve("never.blob");

        key.clear();

        assertThrows(IllegalStateException.class, () ->
                FileEncryptionService.encrypt(source, blob, meta(source, 1000), salt, key, FileEncryptionService.Progress.NONE));

        assertFalse(Files.exists(blob));

    }

    private void assertRefused(byte[] blobBytes, String what) throws IOException {

        Path tampered = Files.write(dir.resolve("tampered-" + UUID.randomUUID()), blobBytes);

        assertThrows(FileDecryptor.DamagedFileException.class,
                () -> FileDecryptor.decrypt(tampered, new ByteArrayOutputStream(), key, FileEncryptionService.Progress.NONE),
                what + " must be refused");

    }

    private static FileMetadata meta(Path source, long size) {
        return new FileMetadata(source.toString(), "item", source.getFileName().toString(), size, 0, 0, 0);
    }

    private static byte[] flip(byte[] data, int index) {
        byte[] copy = data.clone();
        copy[index] ^= 0x01;
        return copy;
    }

    static int indexOf(byte[] haystack, byte[] needle) {

        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {

            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }

            return i;

        }

        return -1;

    }

    @Test
    void indexOfFindsTheMarker() {
        assertTrue(indexOf("abcSECRETdef".getBytes(StandardCharsets.UTF_8), "SECRET".getBytes(StandardCharsets.UTF_8)) == 3);
    }

}
