package com.cybershield.encryption;

public final class CryptoConstants {

    private CryptoConstants() {
    }

    // =========================
    // AES
    // =========================

    public static final String AES_ALGORITHM =
            "AES";

    public static final String AES_TRANSFORMATION =
            "AES/GCM/NoPadding";

    public static final int AES_KEY_SIZE =
            256;

    public static final int IV_LENGTH =
            12;

    public static final int GCM_TAG_LENGTH =
            128;

    // =========================
    // PBKDF2
    // =========================

    public static final String KDF_ALGORITHM =
            "PBKDF2WithHmacSHA512";

    public static final int PBKDF2_ITERATIONS =
            100000;

    public static final int SALT_LENGTH =
            16;

}