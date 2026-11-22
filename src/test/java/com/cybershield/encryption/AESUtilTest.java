package com.cybershield.encryption;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.util.Base64;

import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AESUtilTest {

    private static SecretKey key() {
        return new SecretKeySpec(random(32), "AES");
    }

    @Test
    void textRoundTripsIncludingNonAsciiCharacters() {

        SecretKey key = key();

        String text = "Pässwörd – 秘密 – 🔐";

        assertEquals(text, AESUtil.decrypt(AESUtil.encrypt(text, key), key));

    }

    @Test
    void bytesRoundTrip() {

        SecretKey key = key();

        byte[] data = random(5000);

        assertArrayEquals(data, AESUtil.decryptBytes(AESUtil.encryptBytes(data, key), key));

    }

    @Test
    void everyEncryptionUsesAFreshIv() {

        SecretKey key = key();

        assertNotEquals(AESUtil.encrypt("same text", key), AESUtil.encrypt("same text", key));

    }

    @Test
    void outputIsIvPlusCiphertextPlusTag() {

        assertEquals(12 + 100 + 16, AESUtil.encryptBytes(new byte[100], key()).length);

    }

    @Test
    void aWrongKeyIsRefused() {

        String encrypted = AESUtil.encrypt("secret", key());

        assertThrows(RuntimeException.class, () -> AESUtil.decrypt(encrypted, key()));

    }

    @Test
    void aChangedCiphertextIsRefused() {

        SecretKey key = key();

        byte[] encrypted = Base64.getDecoder().decode(AESUtil.encrypt("secret", key));

        encrypted[encrypted.length - 3] ^= 1;

        String tampered = Base64.getEncoder().encodeToString(encrypted);

        assertThrows(RuntimeException.class, () -> AESUtil.decrypt(tampered, key));

    }

}
