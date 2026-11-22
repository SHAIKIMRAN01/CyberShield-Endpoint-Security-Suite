package com.cybershield.encryption;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.CancellationException;

/**
 * Password-based encryption for sharing, separate from the vault (the Encryption Center).
 * The key comes from the password with PBKDF2-HMAC-SHA512 and a random salt, so every
 * message and file gets its own key; AES-256-GCM detects a wrong password or any change.
 *
 * <pre>
 *   text   "CYBERSHIELD-TEXT-1:" base64( rounds (4) | salt (16) | IV (12) | ciphertext | tag (16) )
 *   file   "CSP1" | rounds (4) | salt (16) | chunk size (4)
 *          | name length (4) | encrypted original name
 *          | chunks of up to chunk size, each encrypted, the last one marked
 * </pre>
 *
 * File blocks use a nonce built from a block type and a counter (the key is never
 * reused across files), and authenticate the header plus a flag that marks the last
 * chunk, so blocks can't be reordered, swapped or cut off unnoticed.
 */
public final class SharedCipher {

    public static final String TEXT_PREFIX = "CYBERSHIELD-TEXT-1:";

    public static final String FILE_EXTENSION = ".cshield";

    static final byte[] FILE_MAGIC = {'C', 'S', 'P', '1'};

    static final int CHUNK = 1024 * 1024;

    private static final int SALT_BYTES = 16;

    private static final int TAG_BYTES = 16;

    private static final int HEADER_BYTES = 4 + 4 + SALT_BYTES + 4;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** The password is wrong, or the data was changed: GCM can't tell the two apart. */
    public static final class WrongPasswordException extends IOException {

        WrongPasswordException() {
            super("Wrong password, or the data was changed.");
        }

    }

    /** Reports progress, and can ask to stop. */
    public interface Progress {

        Progress NONE = (done, total) -> { };

        void update(long done, long total);

        default boolean isCancelled() {
            return false;
        }

    }

    private SharedCipher() {
    }

    // =====================================================
    // TEXT
    // =====================================================

    public static String encryptText(String text, String password) {

        return TEXT_PREFIX + Base64.getEncoder().encodeToString(sealBytes(text.getBytes(StandardCharsets.UTF_8), password));

    }

    /** The extra bytes sealing adds: rounds, salt, IV and tag. */
    public static final int SEAL_OVERHEAD = 4 + SALT_BYTES + 12 + TAG_BYTES;

    /**
     * Seals bytes with a password: rounds (4) | salt (16) | IV (12) | ciphertext | tag (16).
     * Used for text here, and by Steganography for what it hides in pictures.
     */
    public static byte[] sealBytes(byte[] data, String password) {

        byte[] salt = random(SALT_BYTES);

        int rounds = CryptoConstants.PBKDF2_ITERATIONS;

        byte[] sealed = AESUtil.encryptBytes(data, KeyDerivation.deriveKey(password, salt, rounds));

        return ByteBuffer.allocate(4 + SALT_BYTES + sealed.length).putInt(rounds).put(salt).put(sealed).array();

    }

    /** Opens what sealBytes made. A wrong password or any change is refused. */
    public static byte[] openBytes(byte[] packed, String password) throws IOException {

        if (packed.length < SEAL_OVERHEAD) {
            throw new IOException("The sealed data is cut short.");
        }

        ByteBuffer in = ByteBuffer.wrap(packed);

        int rounds = checkedRounds(in.getInt());

        byte[] salt = new byte[SALT_BYTES];

        in.get(salt);

        byte[] sealed = new byte[in.remaining()];

        in.get(sealed);

        try {
            return AESUtil.decryptBytes(sealed, KeyDerivation.deriveKey(password, salt, rounds));
        } catch (RuntimeException wrong) {
            throw new WrongPasswordException();
        }

    }

    public static String decryptText(String armored, String password) throws IOException {

        String body = armored == null ? "" : armored.strip();

        if (!body.startsWith(TEXT_PREFIX)) {
            throw new IOException("This isn't text encrypted by CyberShield. It starts with " + TEXT_PREFIX);
        }

        byte[] packed;

        try {
            // MIME decoding tolerates the line breaks mail programs add.
            packed = Base64.getMimeDecoder().decode(body.substring(TEXT_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IOException("The encrypted text is damaged.");
        }

        if (packed.length < SEAL_OVERHEAD) {
            throw new IOException("The encrypted text is cut short.");
        }

        return new String(openBytes(packed, password), StandardCharsets.UTF_8);

    }

    // =====================================================
    // FILES
    // =====================================================

    /** Encrypts source into target (normally the source's name + .cshield). Nothing is left behind on failure. */
    public static void encryptFile(Path source, Path target, String password, Progress progress) throws IOException {

        byte[] salt = random(SALT_BYTES);

        int rounds = CryptoConstants.PBKDF2_ITERATIONS;

        SecretKey key = KeyDerivation.deriveKey(password, salt, rounds);

        byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(FILE_MAGIC).putInt(rounds).put(salt).putInt(CHUNK).array();

        long total = Files.size(source);

        Path temp = target.resolveSibling(target.getFileName() + ".part");

        try (
                InputStream in = new BufferedInputStream(Files.newInputStream(source), CHUNK);
                OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp), 64 * 1024)
        ) {

            out.write(header);

            byte[] name = cipher(Cipher.ENCRYPT_MODE, key, 1, 0, header, (byte) 'M')
                    .doFinal(source.getFileName().toString().getBytes(StandardCharsets.UTF_8));

            out.write(ByteBuffer.allocate(4).putInt(name.length).array());

            out.write(name);

            byte[] current = in.readNBytes(CHUNK);

            byte[] next = current.length == CHUNK ? in.readNBytes(CHUNK) : new byte[0];

            long index = 0;

            long done = 0;

            while (true) {

                if (progress.isCancelled()) {
                    throw new CancellationException();
                }

                boolean last = next.length == 0;

                out.write(cipher(Cipher.ENCRYPT_MODE, key, 2, index++, header, last ? (byte) 'L' : (byte) 'C').doFinal(current));

                done += current.length;

                progress.update(done, total);

                if (last) {
                    break;
                }

                current = next;

                next = current.length == CHUNK ? in.readNBytes(CHUNK) : new byte[0];

            }

        } catch (GeneralSecurityException e) {

            Files.deleteIfExists(temp);

            throw new IOException("Encryption failed: " + e.getMessage(), e);

        } catch (IOException | RuntimeException e) {

            Files.deleteIfExists(temp);

            throw e;

        }

        move(temp, target);

    }

    /**
     * Decrypts a .cshield file into the folder, under its original name (or "name
     * (decrypted).ext" if that is taken). Returns the file written. Nothing is left
     * behind if the password is wrong or the file was changed.
     */
    public static Path decryptFile(Path source, Path folder, String password, Progress progress) throws IOException {

        long total = Files.size(source);

        Path temp = folder.resolve(".cybershield-" + Long.toHexString(RANDOM.nextLong()) + ".part");

        String name;

        try (
                InputStream in = new BufferedInputStream(Files.newInputStream(source), CHUNK + TAG_BYTES);
                OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp), 64 * 1024)
        ) {

            byte[] header = in.readNBytes(HEADER_BYTES);

            ByteBuffer fields = ByteBuffer.wrap(header);

            if (header.length < HEADER_BYTES || !java.util.Arrays.equals(header, 0, 4, FILE_MAGIC, 0, 4)) {
                throw new IOException("This isn't a file encrypted by CyberShield's Encryption Center.");
            }

            fields.position(4);

            int rounds = checkedRounds(fields.getInt());

            byte[] salt = new byte[SALT_BYTES];

            fields.get(salt);

            int chunk = fields.getInt();

            if (chunk < 4096 || chunk > 16 * 1024 * 1024) {
                throw new IOException("The encrypted file has a damaged header.");
            }

            SecretKey key = KeyDerivation.deriveKey(password, salt, rounds);

            byte[] lengthBytes = in.readNBytes(4);

            int nameLength = lengthBytes.length == 4 ? ByteBuffer.wrap(lengthBytes).getInt() : -1;

            if (nameLength <= TAG_BYTES || nameLength > 4096) {
                throw new IOException("The encrypted file is damaged.");
            }

            byte[] sealedName = in.readNBytes(nameLength);

            try {
                name = new String(cipher(Cipher.DECRYPT_MODE, key, 1, 0, header, (byte) 'M').doFinal(sealedName), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException wrong) {
                throw new WrongPasswordException();
            }

            byte[] current = in.readNBytes(chunk + TAG_BYTES);

            byte[] next = current.length == chunk + TAG_BYTES ? in.readNBytes(chunk + TAG_BYTES) : new byte[0];

            long index = 0;

            long done = HEADER_BYTES + 4L + nameLength;

            while (true) {

                if (progress.isCancelled()) {
                    throw new CancellationException();
                }

                boolean last = next.length == 0;

                try {
                    out.write(cipher(Cipher.DECRYPT_MODE, key, 2, index++, header, last ? (byte) 'L' : (byte) 'C').doFinal(current));
                } catch (GeneralSecurityException damaged) {
                    throw new IOException("The encrypted file was changed or is incomplete.");
                }

                done += current.length;

                progress.update(done, total);

                if (last) {
                    break;
                }

                current = next;

                next = current.length == chunk + TAG_BYTES ? in.readNBytes(chunk + TAG_BYTES) : new byte[0];

            }

        } catch (IOException | RuntimeException e) {

            Files.deleteIfExists(temp);

            throw e;

        }

        Path target = freeName(folder.resolve(safeName(name)), "decrypted");

        move(temp, target);

        return target;

    }

    // =====================================================
    // HELPERS
    // =====================================================

    private static Cipher cipher(int mode, SecretKey key, int type, long counter, byte[] header, byte flag)
            throws GeneralSecurityException {

        byte[] nonce = ByteBuffer.allocate(12).putInt(type).putLong(counter).array();

        Cipher cipher = Cipher.getInstance(CryptoConstants.AES_TRANSFORMATION);

        cipher.init(mode, key, new GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH, nonce));

        cipher.updateAAD(header);

        cipher.updateAAD(new byte[] {flag});

        return cipher;

    }

    private static int checkedRounds(int rounds) throws IOException {

        if (rounds < 10_000 || rounds > 10_000_000) {
            throw new IOException("The encrypted data has a damaged header.");
        }

        return rounds;

    }

    /** Only a plain file name from inside the encrypted file is used, never a path. */
    static String safeName(String name) {

        String plain = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();

        return plain.isEmpty() || plain.equals(".") || plain.equals("..") ? "decrypted file" : plain;

    }

    /** "report.pdf" becomes "report (decrypted).pdf", then "report (decrypted 2).pdf", and so on. */
    public static Path freeName(Path wanted, String word) {

        if (!Files.exists(wanted)) {
            return wanted;
        }

        String name = wanted.getFileName().toString();

        int dot = name.lastIndexOf('.');

        String base = dot > 0 ? name.substring(0, dot) : name;

        String extension = dot > 0 ? name.substring(dot) : "";

        for (int n = 1; ; n++) {

            Path candidate = wanted.resolveSibling(base + " (" + word + (n == 1 ? "" : " " + n) + ")" + extension);

            if (!Files.exists(candidate)) {
                return candidate;
            }

        }

    }

    private static void move(Path temp, Path target) throws IOException {

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }

    }

    private static byte[] random(int length) {

        byte[] bytes = new byte[length];

        RANDOM.nextBytes(bytes);

        return bytes;

    }

}
