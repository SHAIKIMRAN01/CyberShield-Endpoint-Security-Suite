package com.cybershield.security;

import org.mindrot.jbcrypt.BCrypt;

public final class BCryptUtil {

    private BCryptUtil() {
    }

    /**
     * Hash a plain-text password.
     */
    public static String hashPassword(String password) {

        return BCrypt.hashpw(password, BCrypt.gensalt());

    }

    /**
     * Verify a password against its hash.
     */
    public static boolean verifyPassword(String password,
                                         String hashedPassword) {

        return BCrypt.checkpw(password, hashedPassword);

    }

}