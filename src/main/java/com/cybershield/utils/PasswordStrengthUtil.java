package com.cybershield.utils;

public final class PasswordStrengthUtil {

    private PasswordStrengthUtil() {

    }

    public static String calculate(String password) {

        if (password == null || password.isBlank()) {

            return "Empty";

        }

        int score = calculateScore(password);

        if (score <= 2) {

            return "Weak";

        }

        if (score <= 4) {

            return "Medium";

        }

        if (score <= 6) {

            return "Strong";

        }

        return "Very Strong";

    }

    public static int calculateScore(String password) {

        if (password == null) {

            return 0;

        }

        int score = 0;

        if (password.length() >= 8) {

            score++;

        }

        if (password.length() >= 12) {

            score++;

        }

        if (password.matches(".*[A-Z].*")) {

            score++;

        }

        if (password.matches(".*[a-z].*")) {

            score++;

        }

        if (password.matches(".*\\d.*")) {

            score++;

        }

        if (password.matches(".*[^A-Za-z0-9].*")) {

            score++;

        }

        if (password.length() >= 16) {

            score++;

        }

        return score;

    }

    public static boolean hasUpperCase(String password) {

        return password != null &&
                password.matches(".*[A-Z].*");

    }

    public static boolean hasLowerCase(String password) {

        return password != null &&
                password.matches(".*[a-z].*");

    }

    public static boolean hasDigit(String password) {

        return password != null &&
                password.matches(".*\\d.*");

    }

    public static boolean hasSpecialCharacter(String password) {

        return password != null &&
                password.matches(".*[^A-Za-z0-9].*");

    }

}           