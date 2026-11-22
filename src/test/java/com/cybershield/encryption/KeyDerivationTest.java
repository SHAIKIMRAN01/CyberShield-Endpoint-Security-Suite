package com.cybershield.encryption;

import org.junit.jupiter.api.Test;

import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

class KeyDerivationTest {

    // Few rounds keep these tests fast; the rounds only change the cost, not the logic.
    private static final int ROUNDS = 1000;

    @Test
    void sameInputsGiveTheSameAes256Key() {

        byte[] salt = random(16);

        byte[] first = KeyDerivation.deriveKey("password", salt, ROUNDS).getEncoded();

        assertEquals(32, first.length);

        assertArrayEquals(first, KeyDerivation.deriveKey("password", salt, ROUNDS).getEncoded());

    }

    @Test
    void passwordSaltAndRoundsAllChangeTheKey() {

        byte[] salt = random(16);

        byte[] base = KeyDerivation.deriveKey("password", salt, ROUNDS).getEncoded();

        assertFalse(Arrays.equals(base, KeyDerivation.deriveKey("Password", salt, ROUNDS).getEncoded()));

        assertFalse(Arrays.equals(base, KeyDerivation.deriveKey("password", random(16), ROUNDS).getEncoded()));

        assertFalse(Arrays.equals(base, KeyDerivation.deriveKey("password", salt, ROUNDS + 1).getEncoded()));

    }

    @Test
    void currentRoundsFollowOwaspAdvice() {

        assertTrue(CryptoConstants.PBKDF2_ITERATIONS >= 210_000);

        assertEquals(100_000, CryptoConstants.LEGACY_PBKDF2_ITERATIONS);

    }

    @Test
    void zeroRoundsAreRefused() {

        assertThrows(IllegalArgumentException.class, () -> KeyDerivation.deriveKey("password", random(16), 0));

    }

}
