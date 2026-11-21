package com.cybershield.vault;

import com.cybershield.database.DatabaseManager;
import com.cybershield.encryption.AESUtil;
import com.cybershield.encryption.CryptoConstants;
import com.cybershield.encryption.KeyDerivation;
import com.cybershield.logger.AppLogger;
import com.cybershield.password.PasswordDAO;
import com.cybershield.services.EncryptionService;

import javax.crypto.SecretKey;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;


public class VaultService {

    private static final String VERIFY_TEXT =
            "CyberShield Vault Verification";

    private final VaultDAO dao =
            new VaultDAO();

    private final PasswordDAO passwordDAO =
            new PasswordDAO();

    private final EncryptionService encryptionService =
        EncryptionService.getInstance();

    public boolean vaultExists() {

        return dao.vaultExists();

    }

    public boolean isUnlocked() {

        return VaultSession.isUnlocked()
                && encryptionService.isInitialized();

    }

    public boolean createVault(String masterPassword) {

        if (dao.vaultExists()) {

            return false;

        }

        byte[] salt =
                new byte[CryptoConstants.SALT_LENGTH];

        SecureRandom random =
                new SecureRandom();

        random.nextBytes(salt);

        SecretKey key =
                KeyDerivation.deriveKey(
                        masterPassword,
                        salt
                );

        String verification =
                AESUtil.encrypt(
                        VERIFY_TEXT,
                        key
                );

        Vault vault =
                new Vault(
                        salt,
                        verification
                );

        // Passwords saved before a vault existed could not be encrypted,
        // so encrypt them with the new key in the same transaction.
        // If anything fails, nothing is saved and the entries stay as they were.
        try (Connection connection = DatabaseManager.getConnection()) {

            connection.setAutoCommit(false);

            try {

                dao.saveVault(connection, vault);

                int encrypted =
                        passwordDAO.encryptStoredPasswords(
                                connection,
                                plainText -> AESUtil.encrypt(plainText, key)
                        );

                connection.commit();

                AppLogger.success(
                        "Vault created. Encrypted "
                                + encrypted
                                + " existing password entries."
                );

                return true;

            }

            catch (SQLException | RuntimeException e) {

                connection.rollback();

                AppLogger.error("Could not create the vault: " + e.getMessage());

                return false;

            }

        }

        catch (SQLException e) {

            AppLogger.error("Could not create the vault: " + e.getMessage());

            return false;

        }

    }

    public boolean unlockVault(String masterPassword) {

        Vault vault =
                dao.getVault();

        if (vault == null) {

            return false;

        }

        SecretKey key =
                KeyDerivation.deriveKey(
                        masterPassword,
                        vault.getSalt()
                );

        try {

            String decrypted =
                    AESUtil.decrypt(
                            vault.getVerificationData(),
                            key
                    );

            if (!VERIFY_TEXT.equals(decrypted)) {

                return false;

            }

            encryptionService.initialize(
                    masterPassword,
                    vault.getSalt()
            );

            VaultSession.unlock();

            return true;

        }

        catch (Exception e) {

            return false;

        }

    }

    public void lockVault() {

        // Drop the key from memory, not just the flag.
        encryptionService.clear();

        VaultSession.lock();

    }

}