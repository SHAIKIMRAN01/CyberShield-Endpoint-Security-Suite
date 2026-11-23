package com.cybershield.utils;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The three generator modes: random characters, a passphrase, and a PIN. */
class PasswordGeneratorTest {

    private static final BigInteger THREE_MILLION = BigInteger.valueOf(3_000_000);

    @Test
    void randomUsesEveryChosenSetAndTheRightLength() {

        for (int i = 0; i < 200; i++) {

            String password = PasswordGenerator.random(16, true, true, true, true, false);

            assertEquals(16, password.length());
            assertTrue(password.chars().anyMatch(Character::isUpperCase), password);
            assertTrue(password.chars().anyMatch(Character::isLowerCase), password);
            assertTrue(password.chars().anyMatch(Character::isDigit), password);
            assertTrue(password.chars().anyMatch(c -> "!@#$%^&*()-_=+[]{}<>?/".indexOf(c) >= 0), password);

        }

    }

    @Test
    void randomCanLeaveOutSetsAndLookalikes() {

        for (int i = 0; i < 200; i++) {

            String digitsOnly = PasswordGenerator.random(12, false, false, true, false, false);
            assertTrue(digitsOnly.chars().allMatch(Character::isDigit), digitsOnly);

            String noLookalikes = PasswordGenerator.random(24, true, true, true, false, true);
            assertTrue(noLookalikes.chars().noneMatch(c -> "Il1O0o".indexOf(c) >= 0), noLookalikes);

        }

        assertThrows(IllegalArgumentException.class, () -> PasswordGenerator.random(16, false, false, false, false, false));

    }

    @Test
    void randomLengthIsPulledIntoRange() {

        assertEquals(4, PasswordGenerator.random(1, true, true, true, true, false).length());
        assertEquals(128, PasswordGenerator.random(9999, true, true, true, true, false).length());

    }

    @Test
    void aFullRandomPasswordHasWellOverThreeMillionCombinations() {

        BigInteger combos = PasswordGenerator.randomCombinations(16, true, true, true, true, false);

        assertTrue(combos.compareTo(THREE_MILLION) > 0, String.valueOf(combos));
        assertTrue(PasswordGenerator.entropyBits(combos) > 90, "a 16-char password is strong");

        // The pool is every upper, lower, digit and symbol: a fixed size, to the 16th power.
        int pool = combos.equals(BigInteger.ONE) ? 0 : (int) Math.round(Math.pow(combos.doubleValue(), 1.0 / 16));
        assertTrue(pool >= 80 && pool <= 95, "pool size " + pool);
        assertEquals(BigInteger.valueOf(pool).pow(16), combos);

    }

    @Test
    void passphraseJoinsWordsFromTheListAndCanAddCaseAndNumbers() {

        Set<String> list = Set.copyOf(PasswordGenerator.words());

        assertTrue(list.size() >= 400, "a useful word list: " + list.size());

        String plain = PasswordGenerator.passphrase(4, '-', false, false);

        String[] parts = plain.split("-");

        assertEquals(4, parts.length, plain);

        for (String word : parts) {
            assertTrue(list.contains(word), word + " is from the list");
        }

        String fancy = PasswordGenerator.passphrase(5, '.', true, true);

        assertTrue(Pattern.matches("([A-Z][a-z]+\\.){5}\\d\\d", fancy), fancy);

    }

    @Test
    void evenAShortPassphraseBeatsThreeMillionCombinations() {

        assertTrue(PasswordGenerator.passphraseCombinations(3, false).compareTo(THREE_MILLION) > 0,
                String.valueOf(PasswordGenerator.passphraseCombinations(3, false)));

        BigInteger six = PasswordGenerator.passphraseCombinations(6, true);

        assertEquals(BigInteger.valueOf(PasswordGenerator.words().size()).pow(6).multiply(BigInteger.valueOf(90)), six);

    }

    @Test
    void pinIsNumericAndTheRightLength() {

        for (int length : new int[] {4, 6, 8}) {
            String pin = PasswordGenerator.pin(length);
            assertEquals(length, pin.length());
            assertTrue(pin.chars().allMatch(Character::isDigit), pin);
        }

        assertEquals(3, PasswordGenerator.pin(1).length(), "pulled up to the minimum");
        assertEquals(BigInteger.TEN.pow(6), PasswordGenerator.pinCombinations(6));

    }

    @Test
    void theOldApiStillMeetsTheRules() {

        assertAll("every generated password meets the rules", () -> {
            for (int i = 0; i < 200; i++) {
                String password = PasswordGenerator.generate(12);
                assertTrue(ValidationUtil.isValidPassword(password), password);
                assertFalse(password.isBlank());
            }
        });

    }

}
