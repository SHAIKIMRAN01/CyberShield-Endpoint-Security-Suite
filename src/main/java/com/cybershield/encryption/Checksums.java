package com.cybershield.encryption;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.LongConsumer;

/** SHA-256 and SHA-512 checksums of text or files, and comparing them with an expected value. */
public final class Checksums {

    public enum Algorithm {

        SHA_256("SHA-256"),
        SHA_512("SHA-512");

        private final String javaName;

        Algorithm(String javaName) {
            this.javaName = javaName;
        }

        @Override
        public String toString() {
            return javaName;
        }

    }

    private Checksums() {
    }

    public static String of(String text, Algorithm algorithm) {

        return HexFormat.of().formatHex(digest(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));

    }

    /** Reads the file in pieces, so its size doesn't matter. progress gets the bytes read so far. */
    public static String of(Path file, Algorithm algorithm, LongConsumer progress) throws IOException {

        MessageDigest digest = digest(algorithm);

        byte[] buffer = new byte[1024 * 1024];

        long done = 0;

        try (InputStream in = Files.newInputStream(file)) {

            int read;

            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                done += read;
                progress.accept(done);
            }

        }

        return HexFormat.of().formatHex(digest.digest());

    }

    /** True if expected (hex, any case, spaces and colons ignored) is the same checksum. */
    public static boolean matches(String checksum, String expected) {

        if (checksum == null || expected == null) {
            return false;
        }

        String cleaned = expected.replaceAll("[\\s:]", "").toLowerCase();

        return !cleaned.isEmpty() && MessageDigest.isEqual(
                checksum.toLowerCase().getBytes(StandardCharsets.US_ASCII),
                cleaned.getBytes(StandardCharsets.US_ASCII));

    }

    private static MessageDigest digest(Algorithm algorithm) {

        try {
            return MessageDigest.getInstance(algorithm.javaName);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " isn't available.", e);
        }

    }

}
