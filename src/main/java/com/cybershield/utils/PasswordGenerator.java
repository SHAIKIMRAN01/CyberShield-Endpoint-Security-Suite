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

    private static final String ALL =
            UPPER +
            LOWER +
            NUMBERS +
            SYMBOLS;

    private static final SecureRandom RANDOM =
            new SecureRandom();

    private PasswordGenerator() {
    }

    /** @param length at least 8; shorter requests get 8 characters */
    public static String generate(int length) {

        length = Math.max(8, length);

        char[] password = new char[length];

        // One of each kind first, so every password passes the rules...
        password[0] = pick(UPPER);
        password[1] = pick(LOWER);
        password[2] = pick(NUMBERS);
        password[3] = pick(SYMBOLS);

        for (int i = 4; i < length; i++) {
            password[i] = pick(ALL);
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
