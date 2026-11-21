package com.cybershield.utils;

import java.util.regex.Pattern;

public final class ValidationUtil {

    private ValidationUtil() {

    }

    // ==========================================
    // USERNAME
    // ==========================================

    public static boolean isValidUsername(String username) {

        return username != null
                && username.matches("[A-Za-z0-9_]{3,30}");

    }

    // ==========================================
    // EMAIL
    // ==========================================

    public static boolean isValidEmail(String email) {

        if (email == null) {

            return false;

        }

        return Pattern.matches(

                "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$",

                email

        );

    }

    // ==========================================
    // WEBSITE
    // ==========================================

    public static boolean isValidWebsite(String website) {

        return website != null

                && !website.isBlank()

                && website.length() >= 3;

    }

    // ==========================================
    // PASSWORD
    // ==========================================

    public static boolean isValidPassword(String password) {

        if (password == null) {

            return false;

        }

        if (password.length() < 8) {

            return false;

        }

        if (!password.matches(".*[A-Z].*")) {

            return false;

        }

        if (!password.matches(".*[a-z].*")) {

            return false;

        }

        if (!password.matches(".*\\d.*")) {

            return false;

        }

        return password.matches(".*[^A-Za-z0-9].*");

    }

    // ==========================================
    // MASTER PASSWORD
    // ==========================================

    public static boolean isValidMasterPassword(

            String password

    ) {

        if (!isValidPassword(password)) {

            return false;

        }

        return password.length() >= 12;

    }

}