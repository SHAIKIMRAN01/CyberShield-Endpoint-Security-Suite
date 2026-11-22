package com.cybershield.utils;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ValidationUtil, PasswordStrengthUtil and PasswordGenerator. */
class PasswordRulesTest {

    @Test
    void usernames() {

        assertTrue(ValidationUtil.isValidUsername("imran_01"));
        assertFalse(ValidationUtil.isValidUsername("ab"));
        assertFalse(ValidationUtil.isValidUsername("with space"));
        assertFalse(ValidationUtil.isValidUsername("a".repeat(31)));
        assertFalse(ValidationUtil.isValidUsername(null));

    }

    @Test
    void emails() {

        assertTrue(ValidationUtil.isValidEmail("someone@example.test"));
        assertFalse(ValidationUtil.isValidEmail("someone"));
        assertFalse(ValidationUtil.isValidEmail(null));

    }

    @Test
    void accountAndMasterPasswords() {

        assertTrue(ValidationUtil.isValidPassword("Abcdef1!"));
        assertFalse(ValidationUtil.isValidPassword("abcdef1!"), "needs an upper case letter");
        assertFalse(ValidationUtil.isValidPassword("ABCDEF1!"), "needs a lower case letter");
        assertFalse(ValidationUtil.isValidPassword("Abcdefg!"), "needs a digit");
        assertFalse(ValidationUtil.isValidPassword("Abcdefg1"), "needs a symbol");
        assertFalse(ValidationUtil.isValidPassword("Abc1!"), "too short");

        assertFalse(ValidationUtil.isValidMasterPassword("Abcdef1!"), "a master password needs 12 characters");
        assertTrue(ValidationUtil.isValidMasterPassword("Abcdefgh123!"));

    }

    @Test
    void strengthGrowsWithLengthAndVariety() {

        assertEquals("Empty", PasswordStrengthUtil.calculate(""));
        assertEquals("Weak", PasswordStrengthUtil.calculate("abc"));
        assertTrue(PasswordStrengthUtil.calculateScore("Correct-Horse-Battery-9")
                > PasswordStrengthUtil.calculateScore("password"));

    }

    @RepeatedTest(20)
    void generatedPasswordsMeetTheRules() {

        String generated = PasswordGenerator.generate(16);

        assertEquals(16, generated.length());

        assertTrue(ValidationUtil.isValidPassword(generated), generated);

    }

    @Test
    void generatedPasswordsDiffer() {

        assertNotEquals(PasswordGenerator.generate(20), PasswordGenerator.generate(20));

    }

}
