package com.cybershield.encryption;

import com.cybershield.services.EncryptionService;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/**
 * Encrypts a file into the vault format described in {@link EncryptedFileFormat},
 * using a new random file key that is locked with the master vault key.
 */
public final class FileEncryptionService {

    /** Progress reporting for long file jobs, and a way to stop them. */
    public interface Progress {

        Progress NONE = new Progress() { };

        default void bytesDone(long bytes) {
        }

        default boolean isCancelled() {
            return false;
        }

    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private FileEncryptionService() {
    }

    /**
     * Encrypts source into target, which must not exist yet. The vault must be
     * unlocked. Returns the SHA-256 of the plain text that was read, so the
     * caller can check the result by decrypting it again.
     *
     * @throws CancellationException if progress asks to stop; the caller deletes target
     */
    public static byte[] encrypt(
            Path source,
            Path target,
            FileMetadata metadata,
            byte[] vaultSalt,
            EncryptionService vaultKey,
            Progress progress
    ) throws IOException {

        byte[] keyBytes = new byte[EncryptedFileFormat.FILE_KEY_BYTES];

        RANDOM.nextBytes(keyBytes);

        try {

            SecretKey fileKey = new SecretKeySpec(keyBytes, CryptoConstants.AES_ALGORITHM);

            byte[] header = EncryptedFileFormat.header(vaultSalt, vaultKey.encryptBytes(keyBytes));

            MessageDigest sha256 = sha256();

            try (
                    InputStream in = Files.newInputStream(source);
                    FileChannel channel = FileChannel.open(
                            target,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                    )
            ) {

                OutputStream out = new BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024);

                out.write(header);

                byte[] encryptedMetadata = EncryptedFileFormat
                        .metadataCipher(Cipher.ENCRYPT_MODE, fileKey, header)
                        .doFinal(metadata.toBytes());

                out.write(ByteBuffer.allocate(Integer.BYTES).putInt(encryptedMetadata.length).array());

                out.write(encryptedMetadata);

                int chunk = EncryptedFileFormat.CHUNK_SIZE;

                byte[] current = new byte[chunk];

                byte[] next = new byte[chunk];

                byte[] output = new byte[chunk + EncryptedFileFormat.TAG_BYTES];

                int currentLength = EncryptedFileFormat.readFully(in, current, chunk);

                long index = 0;

                while (true) {

                    if (progress.isCancelled()) {
                        throw new CancellationException();
                    }

                    // Read one chunk ahead: the chunk before end-of-file is marked as the last one.
                    int nextLength = currentLength == chunk
                            ? EncryptedFileFormat.readFully(in, next, chunk)
                            : 0;

                    boolean last = nextLength == 0;

                    int written = EncryptedFileFormat
                            .chunkCipher(Cipher.ENCRYPT_MODE, fileKey, index++, last, header)
                            .doFinal(current, 0, currentLength, output, 0);

                    out.write(output, 0, written);

                    sha256.update(current, 0, currentLength);

                    progress.bytesDone(currentLength);

                    if (last) {
                        break;
                    }

                    byte[] swap = current;
                    current = next;
                    next = swap;
                    currentLength = nextLength;

                }

                out.flush();

                // Make sure the encrypted copy is really on disk before anyone deletes the original.
                channel.force(true);

            } catch (GeneralSecurityException e) {

                throw new IOException("Encryption failed.", e);

            }

            return sha256.digest();

        } finally {

            Arrays.fill(keyBytes, (byte) 0);

        }

    }

    static MessageDigest sha256() {

        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

    }

}
