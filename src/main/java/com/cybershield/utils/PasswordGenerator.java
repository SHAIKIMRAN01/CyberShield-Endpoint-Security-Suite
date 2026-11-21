package com.cybershield.utils;

import java.security.SecureRandom;

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

    public static String generate(int length) {

        if (length < 8) {

            length = 8;

        }

        StringBuilder password =
                new StringBuilder();

        for (int i = 0; i < length; i++) {

            password.append(

                    ALL.charAt(

                            RANDOM.nextInt(

                                    ALL.length()

                            )

                    )

            );

        }

        return password.toString();

    }

}