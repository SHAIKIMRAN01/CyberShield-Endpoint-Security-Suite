package com.cybershield.vault;

/**
 * Which vault is open. File jobs read it from background threads, so it is volatile.
 * VaultService opens and closes it together with the key in EncryptionService.
 */
public final class VaultSession {

    private static volatile Vault open;

    private VaultSession() {
    }

    public static boolean isUnlocked() {

        return open != null;

    }

    /** The open vault. @throws IllegalStateException while it is locked */
    public static Vault current() {

        Vault vault = open;

        if (vault == null) {
            throw new IllegalStateException("Vault is locked.");
        }

        return vault;

    }

    static void unlock(Vault vault) {

        open = vault;

    }

    public static void lock() {

        open = null;

    }

}
