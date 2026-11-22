package com.cybershield.utils;

import java.security.SecureRandom;

/** Random passwords that always meet ValidationUtil's rules: upper and lower case, a digit and a symbol. */
public final class PasswordGenerator {

    private static final String UPPER =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private static final String LOWER =
            "abcdefghijklmnopqrstuvwxyz";

    private static final String NUMBERS =
            "0123456789";

    private static final String SYMBOLS =
            "!@#$%^&*()-_=+[]{}<>?/";

    private static final SecureRandom RANDOM =
            new SecureRandom();

    private PasswordGenerator() {
    }

    /** @param length at least 8; shorter requests get 8 characters */
    public static String generate(int length) {

        return generate(length, true, true);

    }

    /**
     * Upper and lower case letters always; digits and symbols when asked for. With
     * both, the password meets ValidationUtil's rules. Lengths run from 8 to 128.
     */
    public static String generate(int length, boolean digits, boolean symbols) {

        length = Math.max(8, Math.min(128, length));

        String pool = UPPER + LOWER + (digits ? NUMBERS : "") + (symbols ? SYMBOLS : "");

        char[] password = new char[length];

        // One of each kind asked for first, so the password has them all...
        int filled = 0;
        password[filled++] = pick(UPPER);
        password[filled++] = pick(LOWER);
        if (digits) {
            password[filled++] = pick(NUMBERS);
        }
        if (symbols) {
            password[filled++] = pick(SYMBOLS);
        }

        for (int i = filled; i < length; i++) {
            password[i] = pick(pool);
        }

        // ...then shuffled (Fisher-Yates), so those four aren't always at the front.
        for (int i = length - 1; i > 0; i--) {

            int j = RANDOM.nextInt(i + 1);

            char swap = password[i];
            password[i] = password[j];
            password[j] = swap;

        }

        return new String(password);

    }

    private static char pick(String characters) {

        return characters.charAt(RANDOM.nextInt(characters.length()));

    }

}
