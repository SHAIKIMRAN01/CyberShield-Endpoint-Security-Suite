package com.cybershield.encryption;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

/**
 * Layout of a file in the file vault (format "CSF1"):
 *
 * <pre>
 *   header    "CSF1" | chunk size (int) | vault salt (16) | wrapped file key (60)
 *   metadata  length (int) | metadata encrypted with the file key
 *   data      chunks of up to chunk-size bytes, each encrypted with the file key
 * </pre>
 *
 * Every file gets its own random AES-256 key. That key is encrypted ("wrapped")
 * with the master vault key and kept in the header. The vault salt is in the
 * header too, so the master password alone can still open the file if the
 * database is ever lost.
 *
 * Each block is AES-GCM with a nonce built from a block type and a counter, so
 * no nonce is ever reused under a file key. The whole header plus a block-type
 * flag is authenticated with every block, so blocks can't be reordered, moved
 * between files, or cut off at the end, and the header can't be changed,
 * without decryption failing.
 */
final class EncryptedFileFormat {

    static final byte[] MAGIC = {'C', 'S', 'F', '1'};

    static final int CHUNK_SIZE = 1024 * 1024;

    static final int MIN_CHUNK_SIZE = 4 * 1024;

    static final int MAX_CHUNK_SIZE = 16 * 1024 * 1024;

    static final int TAG_BYTES = CryptoConstants.GCM_TAG_LENGTH / 8;

    static final int FILE_KEY_BYTES = 32;

    static final int SALT_BYTES = CryptoConstants.SALT_LENGTH;

    // IV + encrypted file key + tag, as produced by AESUtil.encryptBytes
    static final int WRAPPED_KEY_BYTES =
            CryptoConstants.IV_LENGTH + FILE_KEY_BYTES + TAG_BYTES;

    static final int HEADER_BYTES =
            MAGIC.length + Integer.BYTES + SALT_BYTES + WRAPPED_KEY_BYTES;

    static final int MAX_METADATA_BYTES = 1024 * 1024;

    private static final byte METADATA_BLOCK = 'M';

    private static final byte DATA_CHUNK = 'C';

    private static final byte LAST_DATA_CHUNK = 'L';

    private static final int METADATA_NONCE_TYPE = 1;

    private static final int DATA_NONCE_TYPE = 2;

    private EncryptedFileFormat() {
    }

    static byte[] header(byte[] vaultSalt, byte[] wrappedKey) {

        if (vaultSalt.length != SALT_BYTES || wrappedKey.length != WRAPPED_KEY_BYTES) {
            throw new IllegalArgumentException("Unexpected salt or key length.");
        }

        return ByteBuffer.allocate(HEADER_BYTES)
                .put(MAGIC)
                .putInt(CHUNK_SIZE)
                .put(vaultSalt)
                .put(wrappedKey)
                .array();

    }

    static Cipher metadataCipher(int mode, SecretKey fileKey, byte[] header)
            throws GeneralSecurityException {

        return cipher(mode, fileKey, METADATA_NONCE_TYPE, 0, header, METADATA_BLOCK);

    }

    static Cipher chunkCipher(int mode, SecretKey fileKey, long index, boolean last, byte[] header)
            throws GeneralSecurityException {

        return cipher(mode, fileKey, DATA_NONCE_TYPE, index, header, last ? LAST_DATA_CHUNK : DATA_CHUNK);

    }

    private static Cipher cipher(
            int mode,
            SecretKey fileKey,
            int nonceType,
            long counter,
            byte[] header,
            byte blockType
    ) throws GeneralSecurityException {

        byte[] nonce = ByteBuffer.allocate(CryptoConstants.IV_LENGTH)
                .putInt(nonceType)
                .putLong(counter)
                .array();

        Cipher cipher = Cipher.getInstance(CryptoConstants.AES_TRANSFORMATION);

        cipher.init(mode, fileKey, new GCMParameterSpec(CryptoConstants.GCM_TAG_LENGTH, nonce));

        cipher.updateAAD(header);

        cipher.updateAAD(new byte[] {blockType});

        return cipher;

    }

    /** Reads until the buffer holds length bytes or the stream ends. */
    static int readFully(InputStream in, byte[] buffer, int length) throws IOException {

        int total = 0;

        while (total < length) {

            int read = in.read(buffer, total, length - total);

            if (read < 0) {
                break;
            }

            total += read;

        }

        return total;

    }

}
