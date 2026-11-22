package com.cybershield.database;

import com.cybershield.TestSupport;
import com.cybershield.config.DatabaseConfig;
import com.cybershield.encryption.AESUtil;
import com.cybershield.encryption.CryptoConstants;
import com.cybershield.encryption.KeyDerivation;
import com.cybershield.password.PasswordService;
import com.cybershield.security.BCryptUtil;
import com.cybershield.vault.VaultService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static com.cybershield.TestSupport.count;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A database written by v1 (one shared vault, no vault_id columns) is brought up to
 * date on start-up without losing anything, and its owner can take the vault over.
 */
class UpgradeFromV1Test {

    @TempDir
    Path tempDir;

    private final VaultService vaults = new VaultService();

    @AfterEach
    void lock() {
        vaults.lockVault();
    }

    @Test
    void aV1DatabaseIsUpgradedAndNothingIsLost() throws Exception {

        Path database = Files.createDirectories(tempDir.resolve("db")).resolve("cybershield.db");

        System.setProperty(DatabaseConfig.PATH_PROPERTY, database.toString());

        System.setProperty("cybershield.filevault.dir", tempDir.resolve("store").toString());

        byte[] salt = TestSupport.random(16);

        SecretKey sharedKey = KeyDerivation.deriveKey(MASTER_PASSWORD, salt, CryptoConstants.LEGACY_PBKDF2_ITERATIONS);

        // The tables as v1 created them, with one shared vault and two accounts.
        try (Connection connection = DatabaseManager.getConnection(); Statement statement = connection.createStatement()) {

            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE, "
                    + "email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "last_login TIMESTAMP, active INTEGER DEFAULT 1)");
            statement.execute("CREATE TABLE vault (id INTEGER PRIMARY KEY CHECK(id = 1), salt BLOB NOT NULL, "
                    + "verification_data TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE password_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, "
                    + "website TEXT NOT NULL, username TEXT NOT NULL, password TEXT NOT NULL, notes TEXT, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE secure_notes (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, "
                    + "title TEXT NOT NULL, content TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE protected_items (id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, info TEXT NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("CREATE TABLE login_history (id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, "
                    + "login_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, logout_time TIMESTAMP, ip_address TEXT, device_name TEXT, status TEXT)");
            statement.execute("CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id = 1), theme TEXT DEFAULT 'Dark', "
                    + "auto_lock_minutes INTEGER DEFAULT 5, clipboard_clear_seconds INTEGER DEFAULT 30, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        }

        TestSupport.execute("INSERT INTO users (username, email, password_hash) VALUES ('owner', 'owner@example.test', ?)",
                BCryptUtil.hashPassword(TestSupport.ACCOUNT_PASSWORD));
        TestSupport.execute("INSERT INTO users (username, email, password_hash) VALUES ('other', 'other@example.test', ?)",
                BCryptUtil.hashPassword(TestSupport.ACCOUNT_PASSWORD));
        TestSupport.execute("INSERT INTO vault (id, salt, verification_data) VALUES (1, ?, ?)",
                salt, AESUtil.encrypt("CyberShield Vault Verification", sharedKey));

        for (int i = 1; i <= 4; i++) {
            TestSupport.execute("INSERT INTO password_entries (user_id, website, username, password) VALUES (1, ?, 'owner', ?)",
                    "site" + i + ".example.test", AESUtil.encrypt("secret-" + i, sharedKey));
        }

        // Start-up, as the app does it.
        DatabaseInitializer.initialize();

        DatabaseInitializer.initialize();  // a second start changes nothing

        assertEquals(4, count("SELECT COUNT(*) FROM password_entries WHERE vault_id IS NULL"), "entries kept, still in the shared vault");
        assertEquals(1, count("SELECT COUNT(*) FROM vault"));
        assertEquals(0, count("SELECT COUNT(*) FROM user_vaults"));
        assertEquals(1, count("SELECT COUNT(*) FROM pragma_table_info('protected_items') WHERE name = 'vault_id'"));

        assertEquals(VaultService.Status.CAN_TAKE_OVER_SHARED, vaults.status(1));
        assertEquals(VaultService.Status.NEEDS_SETUP, vaults.status(2), "the other account starts its own vault");

        assertEquals(VaultService.Result.OK, vaults.takeOverSharedVault(1, MASTER_PASSWORD));

        var entries = new PasswordService().getAllPasswords();

        assertEquals(4, entries.size());
        assertEquals("secret-1", entries.stream().filter(e -> e.getWebsite().startsWith("site1")).findFirst().orElseThrow().getPassword());
        assertEquals(0, count("SELECT COUNT(*) FROM vault"), "the shared vault is gone once moved");

    }

}
