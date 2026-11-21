package com.cybershield.services;

import com.cybershield.encryption.AESUtil;
import com.cybershield.encryption.KeyDerivation;

import javax.crypto.SecretKey;

public final class EncryptionService {

    private static final EncryptionService INSTANCE =
            new EncryptionService();

    private SecretKey secretKey;

    private EncryptionService() {

    }

    public static EncryptionService getInstance() {

        return INSTANCE;

    }

    public void initialize(String masterPassword,
                           byte[] salt) {

        secretKey =
                KeyDerivation.deriveKey(
                        masterPassword,
                        salt
                );

    }

    public boolean isInitialized() {

        return secretKey != null;

    }

    public void clear() {

        secretKey = null;

    }

    public String encrypt(String plainText) {

        if (!isInitialized()) {

            throw new IllegalStateException(
                    "Vault is locked."
            );

        }

        return AESUtil.encrypt(
                plainText,
                secretKey
        );

    }

    public String decrypt(String cipherText) {

        if (!isInitialized()) {

            throw new IllegalStateException(
                    "Vault is locked."
            );

        }

        return AESUtil.decrypt(
                cipherText,
                secretKey
        );

    }

    /** Encrypts raw bytes with the vault key. Output: IV + ciphertext + tag. */
    public byte[] encryptBytes(byte[] plain) {

        // Read the key once, so locking at the same moment can't pass a null key on.
        SecretKey key = secretKey;

        if (key == null) {

            throw new IllegalStateException("Vault is locked.");

        }

        return AESUtil.encryptBytes(plain, key);

    }

    public byte[] decryptBytes(byte[] data) {

        SecretKey key = secretKey;

        if (key == null) {

            throw new IllegalStateException("Vault is locked.");

        }

        return AESUtil.decryptBytes(data, key);

    }

}