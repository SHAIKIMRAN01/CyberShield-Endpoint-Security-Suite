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

    /** Rounds for every vault key wrapped from now on (current OWASP advice for PBKDF2-HMAC-SHA512). */
    public static final int PBKDF2_ITERATIONS =
            210_000;

    /** Rounds used by the shared vault from before accounts had their own vaults. */
    public static final int LEGACY_PBKDF2_ITERATIONS =
            100_000;

    public static final int SALT_LENGTH =
            16;

}