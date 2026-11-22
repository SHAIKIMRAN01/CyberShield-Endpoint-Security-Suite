package com.cybershield.services;

import com.cybershield.encryption.AESUtil;

import javax.crypto.SecretKey;

/**
 * Holds the open vault's key while the vault is unlocked, and encrypts with it.
 * VaultService puts the key here on unlock and removes it on lock.
 */
public final class EncryptionService {

    private static final EncryptionService INSTANCE =
            new EncryptionService();

    // Read once per call, so locking at the same moment can't pass a null key on.
    private volatile SecretKey secretKey;

    private EncryptionService() {

    }

    public static EncryptionService getInstance() {

        return INSTANCE;

    }

    /** Starts using this vault key. */
    public void useKey(SecretKey key) {

        secretKey = key;

    }

    public boolean isInitialized() {

        return secretKey != null;

    }

    /** Forgets the key. */
    public void clear() {

        secretKey = null;

    }

    public String encrypt(String plainText) {

        return AESUtil.encrypt(plainText, key());

    }

    public String decrypt(String cipherText) {

        return AESUtil.decrypt(cipherText, key());

    }

    /** Encrypts raw bytes with the vault key. Output: IV + ciphertext + tag. */
    public byte[] encryptBytes(byte[] plain) {

        return AESUtil.encryptBytes(plain, key());

    }

    public byte[] decryptBytes(byte[] data) {

        return AESUtil.decryptBytes(data, key());

    }

    private SecretKey key() {

        SecretKey key = secretKey;

        if (key == null) {

            throw new IllegalStateException("Vault is locked.");

        }

        return key;

    }

}
