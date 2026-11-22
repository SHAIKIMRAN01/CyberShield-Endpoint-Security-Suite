package com.cybershield.vault;

import com.cybershield.database.DatabaseManager;
import com.cybershield.encryption.AESUtil;
import com.cybershield.encryption.CryptoConstants;
import com.cybershield.encryption.KeyDerivation;
import com.cybershield.files.FileVaultService;
import com.cybershield.logger.AppLogger;
import com.cybershield.password.PasswordDAO;
import com.cybershield.services.EncryptionService;
import com.cybershield.utils.ValidationUtil;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;

/**
 * Each account has its own vault. A random vault key encrypts the account's
 * passwords, notes and files, and is stored wrapped (AES-GCM encrypted) with a
 * key derived from the master password by PBKDF2. Changing the master password,
 * or raising the PBKDF2 rounds, therefore rewraps one value; nothing else is
 * rewritten, so it can't leave data half converted.
 */
public class VaultService {

    /** What an account needs before a vault screen can open. */
    public enum Status {
        /** No vault yet: ask for a new master password. */
        NEEDS_SETUP,
        /** No vault yet, but the shared vault from before per-account vaults holds this account's data. */
        CAN_TAKE_OVER_SHARED,
        LOCKED,
        UNLOCKED
    }

    public enum Result { OK, WRONG_PASSWORD, WEAK_PASSWORD, NOT_ALLOWED, FAILED }

    static final String VERIFY_TEXT = "CyberShield Vault Verification";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int KEY_BYTES = 32;

    private final VaultDAO dao = new VaultDAO();

    private final PasswordDAO passwordDAO = new PasswordDAO();

    private final EncryptionService encryptionService = EncryptionService.getInstance();

    // Where the key backup goes. null: the file vault's own store, if it exists yet.
    private final Path keyFileStore;

    public VaultService() {
        this(null);
    }

    public VaultService(Path keyFileStore) {
        this.keyFileStore = keyFileStore;
    }

    // =====================================================
    // STATE
    // =====================================================

    public boolean isUnlocked() {

        return VaultSession.isUnlocked() && encryptionService.isInitialized();

    }

    /** @throws IllegalStateException if the database can't be read */
    public Status status(int userId) {

        if (isUnlocked() && VaultSession.current().userId() == userId) {
            return Status.UNLOCKED;
        }

        try {

            if (dao.find(userId) != null) {
                return Status.LOCKED;
            }

            return mayTakeOverShared(userId) ? Status.CAN_TAKE_OVER_SHARED : Status.NEEDS_SETUP;

        } catch (SQLException e) {

            throw new IllegalStateException("Couldn't read the vault: " + e.getMessage(), e);

        }

    }

    /** The account's vault record, or null. Holds nothing secret. */
    public Vault find(int userId) throws SQLException {

        return dao.find(userId);

    }

    // =====================================================
    // CREATE, UNLOCK, TAKE OVER
    // =====================================================

    /** Makes the account's vault and opens it. */
    public Result createVault(int userId, String masterPassword) {

        if (!ValidationUtil.isValidMasterPassword(masterPassword)) {
            return Result.WEAK_PASSWORD;
        }

        try {

            if (dao.find(userId) != null) {
                return Result.NOT_ALLOWED;
            }

            boolean sharedVaultExists = dao.findShared() != null;

            byte[] keyBytes = random(KEY_BYTES);

            SecretKey vaultKey = new SecretKeySpec(keyBytes, CryptoConstants.AES_ALGORITHM);

            byte[] kdfSalt = random(CryptoConstants.SALT_LENGTH);

            byte[] wrapped = AESUtil.encryptBytes(keyBytes, passwordKey(masterPassword, kdfSalt, CryptoConstants.PBKDF2_ITERATIONS));

            Arrays.fill(keyBytes, (byte) 0);

            Vault vault = new Vault(0, userId, random(CryptoConstants.SALT_LENGTH), kdfSalt, CryptoConstants.PBKDF2_ITERATIONS,
                    wrapped, AESUtil.encrypt(VERIFY_TEXT, vaultKey), null, null);

            try (Connection connection = DatabaseManager.getConnection()) {

                connection.setAutoCommit(false);

                try {

                    vault = dao.insert(connection, vault);

                    int encrypted = 0;

                    // Passwords saved as plain text before encryption existed (there was never a shared
                    // vault to encrypt them) are encrypted into the new vault in the same transaction.
                    if (!sharedVaultExists) {
                        encrypted = passwordDAO.encryptPlainTextPasswords(
                                connection, userId, vault.id(), plainText -> AESUtil.encrypt(plainText, vaultKey));
                    }

                    connection.commit();

                    AppLogger.success("Vault created" + (encrypted > 0 ? ", with " + encrypted + " older entries encrypted." : "."));

                } catch (SQLException | RuntimeException e) {

                    connection.rollback();

                    throw e;

                }

            }

            open(vault, vaultKey);

            backUpKey(vault);

            return Result.OK;

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Could not create the vault: " + e.getMessage());

            return Result.FAILED;

        }

    }

    /** Opens the account's vault. A vault wrapped with fewer PBKDF2 rounds than today's is rewrapped on the way. */
    public Result unlock(int userId, String masterPassword) {

        try {

            Vault vault = dao.find(userId);

            if (vault == null) {
                return Result.NOT_ALLOWED;
            }

            SecretKey vaultKey = unwrap(vault, masterPassword);

            if (vaultKey == null) {
                return Result.WRONG_PASSWORD;
            }

            if (vault.kdfIterations() < CryptoConstants.PBKDF2_ITERATIONS) {

                int before = vault.kdfIterations();

                vault = rewrap(vault, vaultKey, masterPassword, false);

                AppLogger.info("Vault key rewrapped with " + vault.kdfIterations() + " PBKDF2 rounds (was " + before + ").");

            }

            open(vault, vaultKey);

            return Result.OK;

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Could not unlock the vault: " + e.getMessage());

            return Result.FAILED;

        }

    }

    /**
     * Moves the shared vault from before per-account vaults into this account:
     * the same vault key, now wrapped with this master password at today's PBKDF2
     * rounds, and this account's saved items with it. The shared vault is removed
     * once no account has items left in it.
     */
    public Result takeOverSharedVault(int userId, String masterPassword) {

        try {

            VaultDAO.SharedVault shared = dao.findShared();

            if (shared == null || dao.find(userId) != null || !mayTakeOverShared(userId)) {
                return Result.NOT_ALLOWED;
            }

            SecretKey vaultKey = KeyDerivation.deriveKey(masterPassword, shared.salt(), CryptoConstants.LEGACY_PBKDF2_ITERATIONS);

            if (!verifies(shared.verificationData(), vaultKey)) {
                return Result.WRONG_PASSWORD;
            }

            byte[] kdfSalt = random(CryptoConstants.SALT_LENGTH);

            byte[] keyBytes = vaultKey.getEncoded();

            byte[] wrapped = AESUtil.encryptBytes(keyBytes, passwordKey(masterPassword, kdfSalt, CryptoConstants.PBKDF2_ITERATIONS));

            Arrays.fill(keyBytes, (byte) 0);

            // Same vault salt, so files protected with the shared vault still name this vault.
            Vault vault = new Vault(0, userId, shared.salt(), kdfSalt, CryptoConstants.PBKDF2_ITERATIONS,
                    wrapped, shared.verificationData(), null, null);

            int moved;

            boolean sharedRemoved;

            try (Connection connection = DatabaseManager.getConnection()) {

                connection.setAutoCommit(false);

                try {

                    vault = dao.insert(connection, vault);

                    moved = dao.moveSharedRows(connection, userId, vault.id());

                    sharedRemoved = !dao.sharedRowsLeft(connection);

                    if (sharedRemoved) {
                        dao.deleteShared(connection);
                    }

                    connection.commit();

                } catch (SQLException | RuntimeException e) {

                    connection.rollback();

                    throw e;

                }

            }

            open(vault, vaultKey);

            backUpKey(vault);

            AppLogger.success("Shared vault moved into account " + userId + " with " + moved + " saved items"
                    + (sharedRemoved ? "; the shared vault is gone." : "; other accounts still have items in it."));

            return Result.OK;

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Could not take over the shared vault: " + e.getMessage());

            return Result.FAILED;

        }

    }

    // =====================================================
    // MASTER PASSWORD
    // =====================================================

    /**
     * Rewraps the vault key with a new master password. Saved passwords, notes and
     * files don't change, because the vault key itself stays the same.
     */
    public Result changeMasterPassword(int userId, String currentPassword, String newPassword) {

        if (!ValidationUtil.isValidMasterPassword(newPassword)) {
            return Result.WEAK_PASSWORD;
        }

        try {

            Vault vault = dao.find(userId);

            if (vault == null) {
                return Result.NOT_ALLOWED;
            }

            SecretKey vaultKey = unwrap(vault, currentPassword);

            if (vaultKey == null) {
                return Result.WRONG_PASSWORD;
            }

            Vault updated = rewrap(vault, vaultKey, newPassword, true);

            if (VaultSession.isUnlocked() && VaultSession.current().id() == updated.id()) {
                VaultSession.unlock(updated);
            }

            AppLogger.success("Master password changed for account " + userId + ".");

            return Result.OK;

        } catch (SQLException | RuntimeException e) {

            AppLogger.error("Could not change the master password: " + e.getMessage());

            return Result.FAILED;

        }

    }

    /**
     * The vault key from a key file in the store (see {@link VaultKeyFile}), or null
     * if the password is wrong. This is what opens the files if the database is lost.
     */
    public SecretKey keyFromBackup(Vault backup, String masterPassword) {

        return unwrap(backup, masterPassword);

    }

    public void lockVault() {

        // Drop the key from memory, not just the flag.
        encryptionService.clear();

        VaultSession.lock();

    }

    // =====================================================
    // HELPERS
    // =====================================================

    private boolean mayTakeOverShared(int userId) throws SQLException {

        if (dao.findShared() == null) {
            return false;
        }

        Set<Integer> owners = dao.sharedOwners();

        // Only an account that has items in it, or anyone if it is empty.
        return owners.isEmpty() || owners.contains(userId);

    }

    private void open(Vault vault, SecretKey vaultKey) {

        encryptionService.useKey(vaultKey);

        VaultSession.unlock(vault);

    }

    private Vault rewrap(Vault vault, SecretKey vaultKey, String password, boolean passwordChanged) throws SQLException {

        byte[] kdfSalt = random(CryptoConstants.SALT_LENGTH);

        byte[] keyBytes = vaultKey.getEncoded();

        byte[] wrapped = AESUtil.encryptBytes(keyBytes, passwordKey(password, kdfSalt, CryptoConstants.PBKDF2_ITERATIONS));

        Arrays.fill(keyBytes, (byte) 0);

        Vault updated = vault.rewrapped(kdfSalt, CryptoConstants.PBKDF2_ITERATIONS, wrapped, vault.passwordChangedAt());

        try (Connection connection = DatabaseManager.getConnection()) {

            dao.updateKey(connection, updated, passwordChanged);

        }

        // Read it back for the times the database filled in.
        Vault stored = dao.find(vault.userId());

        backUpKey(stored);

        return stored;

    }

    /** The vault key, or null if the password is wrong (AES-GCM refuses to unwrap). */
    private static SecretKey unwrap(Vault vault, String password) {

        byte[] keyBytes;

        try {

            keyBytes = AESUtil.decryptBytes(vault.wrappedKey(), passwordKey(password, vault.kdfSalt(), vault.kdfIterations()));

        } catch (RuntimeException wrongPassword) {

            return null;

        }

        SecretKey key = new SecretKeySpec(keyBytes, CryptoConstants.AES_ALGORITHM);

        Arrays.fill(keyBytes, (byte) 0);

        return verifies(vault.verificationData(), key) ? key : null;

    }

    private static SecretKey passwordKey(String password, byte[] salt, int iterations) {

        return KeyDerivation.deriveKey(password, salt, iterations);

    }

    private static boolean verifies(String verificationData, SecretKey key) {

        try {

            return VERIFY_TEXT.equals(AESUtil.decrypt(verificationData, key));

        } catch (RuntimeException e) {

            return false;

        }

    }

    /** Keeps the key file in the store up to date, if the store exists yet (FileVaultService writes it when it makes the store). */
    private void backUpKey(Vault vault) {

        Path store = keyFileStore != null ? keyFileStore : FileVaultService.defaultStoreDir();

        if (!Files.isDirectory(store)) {
            return;
        }

        try {

            VaultKeyFile.write(store, vault);

        } catch (IOException e) {

            AppLogger.error("Couldn't update the vault key file in the store: " + e.getMessage());

        }

    }

    private static byte[] random(int length) {

        byte[] bytes = new byte[length];

        RANDOM.nextBytes(bytes);

        return bytes;

    }

}
