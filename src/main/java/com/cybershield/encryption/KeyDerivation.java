package com.cybershield.encryption;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

public class KeyDerivation {

    private KeyDerivation() {
    }

    public static SecretKey deriveKey(
            String password,
            byte[] salt
    ) {

        try {

            PBEKeySpec spec =
                    new PBEKeySpec(

                            password.toCharArray(),

                            salt,

                            CryptoConstants.PBKDF2_ITERATIONS,

                            CryptoConstants.AES_KEY_SIZE

                    );

            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance(
                            CryptoConstants.KDF_ALGORITHM
                    );

            byte[] keyBytes =
                    factory.generateSecret(spec).getEncoded();

            return new SecretKeySpec(
                    keyBytes,
                    CryptoConstants.AES_ALGORITHM
            );

        }

        catch (NoSuchAlgorithmException |
               InvalidKeySpecException e) {

            throw new RuntimeException(e);

        }

    }

}