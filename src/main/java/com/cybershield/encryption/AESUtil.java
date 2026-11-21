package com.cybershield.encryption;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import java.security.SecureRandom;

import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM with a random 12-byte IV per message.
 * Output layout: IV followed by the ciphertext and its 16-byte tag.
 */
public final class AESUtil {

    private static final SecureRandom RANDOM =
            new SecureRandom();

    private AESUtil() {
    }

    public static String encrypt(
            String plainText,
            SecretKey key
    ) {

        return Base64
                .getEncoder()
                .encodeToString(
                        encryptBytes(plainText.getBytes(), key)
                );

    }

    public static String decrypt(
            String cipherText,
            SecretKey key
    ) {

        return new String(
                decryptBytes(
                        Base64.getDecoder().decode(cipherText),
                        key
                )
        );

    }

    public static byte[] encryptBytes(
            byte[] plain,
            SecretKey key
    ) {

        try {

            byte[] iv =
                    new byte[CryptoConstants.IV_LENGTH];

            RANDOM.nextBytes(iv);

            Cipher cipher =
                    Cipher.getInstance(
                            CryptoConstants.AES_TRANSFORMATION
                    );

            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    key,
                    new GCMParameterSpec(
                            CryptoConstants.GCM_TAG_LENGTH,
                            iv
                    )
            );

            byte[] encrypted =
                    cipher.doFinal(plain);

            byte[] combined =
                    new byte[iv.length + encrypted.length];

            System.arraycopy(iv, 0, combined, 0, iv.length);

            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return combined;

        }

        catch (Exception e) {

            throw new RuntimeException(e);

        }

    }

    public static byte[] decryptBytes(
            byte[] combined,
            SecretKey key
    ) {

        try {

            byte[] iv =
                    Arrays.copyOfRange(
                            combined,
                            0,
                            CryptoConstants.IV_LENGTH
                    );

            byte[] encrypted =
                    Arrays.copyOfRange(
                            combined,
                            CryptoConstants.IV_LENGTH,
                            combined.length
                    );

            Cipher cipher =
                    Cipher.getInstance(
                            CryptoConstants.AES_TRANSFORMATION
                    );

            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(
                            CryptoConstants.GCM_TAG_LENGTH,
                            iv
                    )
            );

            return cipher.doFinal(encrypted);

        }

        catch (Exception e) {

            throw new RuntimeException(e);

        }

    }

}
