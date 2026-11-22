package com.cybershield.vault;

/**
 * One account's vault, as stored in user_vaults. Nothing here is secret on its own:
 * the vault key is only kept wrapped (AES-GCM encrypted) with a key derived from
 * the master password.
 *
 * @param vaultSalt        names the vault; written into the header of every protected file
 * @param kdfSalt          PBKDF2 salt for the key that unwraps the vault key
 * @param kdfIterations    PBKDF2 rounds for that key
 * @param wrappedKey       the vault key, encrypted with the password-derived key
 * @param verificationData a known text encrypted with the vault key, to confirm it is the right one
 */
public record Vault(
        int id,
        int userId,
        byte[] vaultSalt,
        byte[] kdfSalt,
        int kdfIterations,
        byte[] wrappedKey,
        String verificationData,
        String createdAt,
        String passwordChangedAt
) {

    /** The same vault with its key wrapped again (new master password or more PBKDF2 rounds). */
    public Vault rewrapped(byte[] newKdfSalt, int newIterations, byte[] newWrappedKey, String changedAt) {

        return new Vault(id, userId, vaultSalt, newKdfSalt, newIterations, newWrappedKey, verificationData, createdAt, changedAt);

    }

}
