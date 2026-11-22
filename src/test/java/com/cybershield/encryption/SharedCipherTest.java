package com.cybershield.encryption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Encryption Center's password-based text and file encryption, and checksums. */
class SharedCipherTest {

    private static final String PASSWORD = "Shared-Secret-42!";

    @TempDir
    Path dir;

    @Test
    void textRoundTripsAndRefusesAWrongPasswordOrChanges() throws Exception {

        String message = "Meet at 6 – café near the station ☕";

        String sealed = SharedCipher.encryptText(message, PASSWORD);

        assertTrue(sealed.startsWith(SharedCipher.TEXT_PREFIX));

        assertFalse(sealed.contains("café"));

        assertEquals(message, SharedCipher.decryptText(sealed, PASSWORD));

        assertEquals(message, SharedCipher.decryptText("  " + sealed + "\n", PASSWORD), "spaces around it are fine");

        assertThrows(SharedCipher.WrongPasswordException.class, () -> SharedCipher.decryptText(sealed, "Wrong-Secret-42!"));

        char[] chars = sealed.toCharArray();
        chars[chars.length - 6] = chars[chars.length - 6] == 'A' ? 'B' : 'A';
        assertThrows(SharedCipher.WrongPasswordException.class, () -> SharedCipher.decryptText(new String(chars), PASSWORD));

        assertThrows(IOException.class, () -> SharedCipher.decryptText("hello", PASSWORD));

        assertFalse(sealed.equals(SharedCipher.encryptText(message, PASSWORD)), "a fresh salt and IV every time");

    }

    @Test
    void filesRoundTripUnderTheirOriginalName() throws Exception {

        for (int size : new int[] {0, 1, SharedCipher.CHUNK, SharedCipher.CHUNK + 5}) {

            Path folder = Files.createDirectories(dir.resolve("size-" + size));

            byte[] data = random(size);

            Path original = Files.write(folder.resolve("report.pdf"), data);

            Path sealed = folder.resolve("report.pdf.cshield");

            SharedCipher.encryptFile(original, sealed, PASSWORD, SharedCipher.Progress.NONE);

            Path out = Files.createDirectories(folder.resolve("out"));

            Path opened = SharedCipher.decryptFile(sealed, out, PASSWORD, SharedCipher.Progress.NONE);

            assertEquals("report.pdf", opened.getFileName().toString());

            assertArrayEquals(data, Files.readAllBytes(opened), "size " + size);

            Path again = SharedCipher.decryptFile(sealed, out, PASSWORD, SharedCipher.Progress.NONE);

            assertEquals("report (decrypted).pdf", again.getFileName().toString(), "an existing file isn't overwritten");

        }

    }

    @Test
    void aWrongPasswordOrAChangedFileLeavesNothingBehind() throws Exception {

        byte[] data = random(SharedCipher.CHUNK + 100);

        Path original = Files.write(dir.resolve("photo.jpg"), data);

        Path sealed = dir.resolve("photo.jpg.cshield");

        SharedCipher.encryptFile(original, sealed, PASSWORD, SharedCipher.Progress.NONE);

        Path out = Files.createDirectories(dir.resolve("out"));

        assertThrows(SharedCipher.WrongPasswordException.class,
                () -> SharedCipher.decryptFile(sealed, out, "Wrong-Secret-42!", SharedCipher.Progress.NONE));

        byte[] bytes = Files.readAllBytes(sealed);

        byte[] changed = bytes.clone();
        changed[bytes.length - 40] ^= 1;
        Path tampered = Files.write(dir.resolve("tampered.cshield"), changed);

        assertThrows(IOException.class, () -> SharedCipher.decryptFile(tampered, out, PASSWORD, SharedCipher.Progress.NONE));

        Path cut = Files.write(dir.resolve("cut.cshield"), Arrays.copyOf(bytes, bytes.length - 120));

        assertThrows(IOException.class, () -> SharedCipher.decryptFile(cut, out, PASSWORD, SharedCipher.Progress.NONE));

        try (Stream<Path> left = Files.list(out)) {
            assertEquals(0, left.count(), "no half-written files");
        }

        assertEquals("evil_.._name", SharedCipher.safeName("evil/../name"), "a stored name can't point elsewhere");

    }

    @Test
    void checksumsMatchKnownValues() {

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Checksums.of("abc", Checksums.Algorithm.SHA_256));

        assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                Checksums.of("abc", Checksums.Algorithm.SHA_512));

        assertTrue(Checksums.matches("ba7816bf8f01cfea", "BA78 16BF:8F01 CFEA"));

        assertFalse(Checksums.matches("ba7816bf8f01cfea", "ba7816bf8f01cfeb"));

        assertFalse(Checksums.matches("ba78", ""));

    }

    @Test
    void fileChecksumsReadInPieces() throws Exception {

        Path file = Files.writeString(dir.resolve("abc.txt"), "abc");

        long[] last = {0};

        assertEquals(Checksums.of("abc", Checksums.Algorithm.SHA_256), Checksums.of(file, Checksums.Algorithm.SHA_256, done -> last[0] = done));

        assertEquals(3, last[0]);

    }

}
