package com.cybershield.encryption;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.GeneralSecurityException;

/** Turns a password into an AES-256 key with PBKDF2-HMAC-SHA512. */
public final class KeyDerivation {

    private KeyDerivation() {
    }

    /**
     * @param iterations PBKDF2 rounds: {@link CryptoConstants#PBKDF2_ITERATIONS} for current vaults,
     *                   or whatever a stored vault records
     */
    public static SecretKey deriveKey(String password, byte[] salt, int iterations) {

        if (iterations < 1) {
            throw new IllegalArgumentException("PBKDF2 needs at least one round.");
        }

        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, CryptoConstants.AES_KEY_SIZE);

        try {

            SecretKeyFactory factory = SecretKeyFactory.getInstance(CryptoConstants.KDF_ALGORITHM);

            byte[] keyBytes = factory.generateSecret(spec).getEncoded();

            return new SecretKeySpec(keyBytes, CryptoConstants.AES_ALGORITHM);

        } catch (GeneralSecurityException e) {

            throw new IllegalStateException("PBKDF2 isn't available: " + e.getMessage(), e);

        } finally {

            spec.clearPassword();

        }

    }

}
