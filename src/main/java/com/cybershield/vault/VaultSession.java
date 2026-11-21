package com.cybershield.vault;

public final class VaultSession {

    private static boolean unlocked = false;

    private VaultSession() {
    }

    public static boolean isUnlocked() {

        return unlocked;

    }

    public static void unlock() {

        unlocked = true;

    }

    public static void lock() {

        unlocked = false;

    }

}
