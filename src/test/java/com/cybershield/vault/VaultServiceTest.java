package com.cybershield.vault;

import com.cybershield.TestSupport;
import com.cybershield.encryption.AESUtil;
import com.cybershield.encryption.CryptoConstants;
import com.cybershield.encryption.KeyDerivation;
import com.cybershield.password.PasswordEntry;
import com.cybershield.password.PasswordService;
import com.cybershield.services.EncryptionService;
import com.cybershield.vault.VaultService.Result;
import com.cybershield.vault.VaultService.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static com.cybershield.TestSupport.count;
import static com.cybershield.TestSupport.execute;
import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultServiceTest {

    private static final String OTHER_MASTER = "Another-Master-7?";

    @TempDir
    Path tempDir;

    private Path store;

    private VaultService vaults;

    private int alice;

    private int bob;

    @BeforeEach
    void setUp() throws Exception {

        store = TestSupport.freshEnvironment(tempDir);

        Files.createDirectories(store);

        vaults = new VaultService(store);

        alice = TestSupport.newUser("alice");

        bob = TestSupport.newUser("bob");

    }

    @AfterEach
    void lock() {
        vaults.lockVault();
    }

    @Test
    void createUnlockAndLock() {

        assertEquals(Status.NEEDS_SETUP, vaults.status(alice));

        assertEquals(Result.OK, vaults.createVault(alice, MASTER_PASSWORD));

        assertEquals(Status.UNLOCKED, vaults.status(alice), "a new vault opens straight away");

        vaults.lockVault();

        assertEquals(Status.LOCKED, vaults.status(alice));

        assertFalse(EncryptionService.getInstance().isInitialized(), "locking drops the key");

        assertEquals(Result.WRONG_PASSWORD, vaults.unlock(alice, "Wrong-Master-1!"));

        assertEquals(Result.OK, vaults.unlock(alice, MASTER_PASSWORD));

        assertTrue(vaults.isUnlocked());

    }

    @Test
    void newVaultsUseTodaysRoundsAndStoreNoKeyInTheClear() throws Exception {

        vaults.createVault(alice, MASTER_PASSWORD);

        Vault vault = vaults.find(alice);

        assertEquals(CryptoConstants.PBKDF2_ITERATIONS, vault.kdfIterations());

        assertEquals(12 + 32 + 16, vault.wrappedKey().length, "only the wrapped key is stored");

        assertEquals(1, count("SELECT COUNT(*) FROM user_vaults WHERE user_id = ?", alice));

    }

    @Test
    void weakMasterPasswordsAreRefused() {

        assertEquals(Result.WEAK_PASSWORD, vaults.createVault(alice, "short1!A"));

        assertEquals(Status.NEEDS_SETUP, vaults.status(alice));

    }

    @Test
    void aSecondVaultForTheSameAccountIsRefused() {

        vaults.createVault(alice, MASTER_PASSWORD);

        assertEquals(Result.NOT_ALLOWED, vaults.createVault(alice, OTHER_MASTER));

    }

    @Test
    void eachAccountOnlyReachesItsOwnVault() {

        PasswordService passwords = new PasswordService();

        vaults.createVault(alice, MASTER_PASSWORD);

        passwords.savePassword(new PasswordEntry(alice, "example.com", "alice", "alice-secret", null));

        int aliceEntry = passwords.getAllPasswords().get(0).getId();

        vaults.lockVault();

        vaults.createVault(bob, OTHER_MASTER);

        assertTrue(passwords.getAllPasswords().isEmpty(), "bob sees none of alice's entries");

        assertFalse(passwords.deletePassword(aliceEntry), "bob can't delete alice's entry by its id");

        vaults.lockVault();

        assertEquals(Result.WRONG_PASSWORD, vaults.unlock(bob, MASTER_PASSWORD), "alice's master password doesn't open bob's vault");

        assertEquals(Result.OK, vaults.unlock(alice, MASTER_PASSWORD));

        assertEquals("alice-secret", passwords.getAllPasswords().get(0).getPassword());

    }

    @Test
    void changingTheMasterPasswordKeepsEverythingReadable() throws Exception {

        PasswordService passwords = new PasswordService();

        vaults.createVault(alice, MASTER_PASSWORD);

        passwords.savePassword(new PasswordEntry(alice, "example.com", "alice", "kept-secret", "a note"));

        String fileHeaderKey = EncryptionService.getInstance().encrypt("a protected file's key");

        assertEquals(Result.WRONG_PASSWORD, vaults.changeMasterPassword(alice, "Wrong-Master-1!", OTHER_MASTER));

        assertEquals(Result.WEAK_PASSWORD, vaults.changeMasterPassword(alice, MASTER_PASSWORD, "weak"));

        assertEquals(Result.OK, vaults.changeMasterPassword(alice, MASTER_PASSWORD, OTHER_MASTER));

        assertNotNull(vaults.find(alice).passwordChangedAt());

        vaults.lockVault();

        assertEquals(Result.WRONG_PASSWORD, vaults.unlock(alice, MASTER_PASSWORD), "the old master password stops working");

        assertEquals(Result.OK, vaults.unlock(alice, OTHER_MASTER));

        assertEquals("kept-secret", passwords.getAllPasswords().get(0).getPassword());

        assertEquals("a protected file's key", EncryptionService.getInstance().decrypt(fileHeaderKey),
                "the vault key itself didn't change, so protected files still open");

    }

    @Test
    void olderVaultsAreRewrappedWithMoreRoundsOnUnlock() throws Exception {

        byte[] keyBytes = random(32);

        SecretKey vaultKey = new SecretKeySpec(keyBytes, "AES");

        byte[] kdfSalt = random(16);

        SecretKey passwordKey = KeyDerivation.deriveKey(MASTER_PASSWORD, kdfSalt, 100_000);

        execute("INSERT INTO user_vaults (user_id, vault_salt, kdf_salt, kdf_iterations, wrapped_key, verification_data) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                alice, random(16), kdfSalt, 100_000, AESUtil.encryptBytes(keyBytes, passwordKey),
                AESUtil.encrypt(VaultService.VERIFY_TEXT, vaultKey));

        String before = AESUtil.encrypt("encrypted before the upgrade", vaultKey);

        assertEquals(Result.OK, vaults.unlock(alice, MASTER_PASSWORD));

        Vault upgraded = vaults.find(alice);

        assertEquals(CryptoConstants.PBKDF2_ITERATIONS, upgraded.kdfIterations());

        assertFalse(java.util.Arrays.equals(kdfSalt, upgraded.kdfSalt()), "a new salt comes with the new rounds");

        assertNull(upgraded.passwordChangedAt(), "an upgrade isn't a password change");

        assertEquals("encrypted before the upgrade", EncryptionService.getInstance().decrypt(before));

        vaults.lockVault();

        assertEquals(Result.OK, vaults.unlock(alice, MASTER_PASSWORD), "the same password still opens it");

    }

    @Test
    void theSharedVaultMovesIntoTheAccountThatOwnsItsItems() throws Exception {

        byte[] sharedSalt = random(16);

        SecretKey sharedKey = makeSharedVault(sharedSalt);

        execute("INSERT INTO password_entries (user_id, website, username, password) VALUES (?, 'example.com', 'alice', ?)",
                alice, AESUtil.encrypt("from-the-shared-vault", sharedKey));

        assertEquals(Status.CAN_TAKE_OVER_SHARED, vaults.status(alice));

        assertEquals(Status.NEEDS_SETUP, vaults.status(bob), "bob has nothing in it, so he starts his own");

        assertEquals(Result.NOT_ALLOWED, vaults.takeOverSharedVault(bob, MASTER_PASSWORD));

        assertEquals(Result.WRONG_PASSWORD, vaults.takeOverSharedVault(alice, "Wrong-Master-1!"));

        assertEquals(Result.OK, vaults.takeOverSharedVault(alice, MASTER_PASSWORD));

        assertEquals(Status.UNLOCKED, vaults.status(alice));

        List<PasswordEntry> entries = new PasswordService().getAllPasswords();

        assertEquals(1, entries.size());

        assertEquals("from-the-shared-vault", entries.get(0).getPassword());

        Vault vault = vaults.find(alice);

        assertArrayEquals(sharedSalt, vault.vaultSalt(), "files protected before still name this vault");

        assertEquals(CryptoConstants.PBKDF2_ITERATIONS, vault.kdfIterations());

        assertEquals(0, count("SELECT COUNT(*) FROM vault"), "nobody else has items in it, so it is gone");

        assertEquals(0, count("SELECT COUNT(*) FROM password_entries WHERE vault_id IS NULL"));

        vaults.lockVault();

        assertEquals(Result.OK, vaults.unlock(alice, MASTER_PASSWORD));

    }

    @Test
    void theSharedVaultStaysUntilEveryOwnerHasTakenTheirItems() throws Exception {

        SecretKey sharedKey = makeSharedVault(random(16));

        execute("INSERT INTO password_entries (user_id, website, username, password) VALUES (?, 'a.example', 'alice', ?)",
                alice, AESUtil.encrypt("alice-pw", sharedKey));

        execute("INSERT INTO password_entries (user_id, website, username, password) VALUES (?, 'b.example', 'bob', ?)",
                bob, AESUtil.encrypt("bob-pw", sharedKey));

        assertEquals(Result.OK, vaults.takeOverSharedVault(alice, MASTER_PASSWORD));

        assertEquals(1, new PasswordService().getAllPasswords().size(), "alice gets only her own entry");

        assertEquals(1, count("SELECT COUNT(*) FROM vault"), "bob still needs it");

        vaults.lockVault();

        assertEquals(Status.CAN_TAKE_OVER_SHARED, vaults.status(bob));

        assertEquals(Result.OK, vaults.takeOverSharedVault(bob, MASTER_PASSWORD));

        assertEquals("bob-pw", new PasswordService().getAllPasswords().get(0).getPassword());

        assertEquals(0, count("SELECT COUNT(*) FROM vault"));

    }

    @Test
    void plainTextEntriesFromBeforeEncryptionAreEncryptedIntoTheNewVault() throws Exception {

        execute("INSERT INTO password_entries (user_id, website, username, password) VALUES (?, 'old.example', 'alice', 'plain-text')",
                alice);

        assertEquals(Result.OK, vaults.createVault(alice, MASTER_PASSWORD));

        assertEquals("plain-text", new PasswordService().getAllPasswords().get(0).getPassword());

        assertNotEquals("plain-text", TestSupport.text("SELECT password FROM password_entries"));

    }

    @Test
    void theKeyFileInTheStoreOpensTheVaultWithoutTheDatabase() throws Exception {

        vaults.createVault(alice, MASTER_PASSWORD);

        String encrypted = EncryptionService.getInstance().encrypt("readable with the key file");

        Path keyFile = VaultKeyFile.pathFor(store, vaults.find(alice));

        assertTrue(Files.exists(keyFile));

        SecretKey key = vaults.keyFromBackup(VaultKeyFile.read(keyFile), MASTER_PASSWORD);

        assertNotNull(key);

        assertEquals("readable with the key file", AESUtil.decrypt(encrypted, key));

        assertNull(vaults.keyFromBackup(VaultKeyFile.read(keyFile), OTHER_MASTER));

        vaults.changeMasterPassword(alice, MASTER_PASSWORD, OTHER_MASTER);

        assertNotNull(vaults.keyFromBackup(VaultKeyFile.read(keyFile), OTHER_MASTER), "the key file follows the new password");

        assertNull(vaults.keyFromBackup(VaultKeyFile.read(keyFile), MASTER_PASSWORD));

    }

    /** A shared vault as databases from before per-account vaults have it. Returns its key. */
    private static SecretKey makeSharedVault(byte[] salt) throws Exception {

        execute("CREATE TABLE IF NOT EXISTS vault (id INTEGER PRIMARY KEY CHECK(id = 1), salt BLOB NOT NULL, "
                + "verification_data TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        SecretKey key = KeyDerivation.deriveKey(MASTER_PASSWORD, salt, CryptoConstants.LEGACY_PBKDF2_ITERATIONS);

        execute("INSERT INTO vault (id, salt, verification_data) VALUES (1, ?, ?)", salt, AESUtil.encrypt(VaultService.VERIFY_TEXT, key));

        return key;

    }

}
