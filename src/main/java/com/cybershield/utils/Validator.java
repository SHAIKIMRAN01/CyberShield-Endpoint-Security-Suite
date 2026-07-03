package com.cybershield.utils;

public final class Validator {

    private Validator() {
    }

    public static boolean isEmpty(String value) {

        return value == null || value.trim().isEmpty();

    }

}