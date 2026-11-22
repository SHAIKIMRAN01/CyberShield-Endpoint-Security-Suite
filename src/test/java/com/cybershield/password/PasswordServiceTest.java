package com.cybershield.password;

import com.cybershield.TestSupport;
import com.cybershield.vault.VaultService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordServiceTest {

    @TempDir
    Path tempDir;

    private final VaultService vaults = new VaultService();

    private final PasswordService passwords = new PasswordService();

    private int alice;

    @BeforeEach
    void setUp() {

        TestSupport.freshEnvironment(tempDir);

        alice = TestSupport.newUser("alice");

        vaults.createVault(alice, MASTER_PASSWORD);

    }

    @AfterEach
    void lock() {
        vaults.lockVault();
    }

    @Test
    void saveUpdateAndDelete() {

        assertTrue(passwords.savePassword(new PasswordEntry(alice, "example.com", "alice@example.test", "first-secret", "note")));

        List<PasswordEntry> saved = passwords.getAllPasswords();

        assertEquals(1, saved.size());

        PasswordEntry entry = saved.get(0);

        assertEquals("first-secret", entry.getPassword());

        entry.setPassword("second-secret");

        assertTrue(passwords.updatePassword(entry));

        assertEquals("second-secret", passwords.getAllPasswords().get(0).getPassword());

        assertTrue(passwords.deletePassword(entry.getId()));

        assertTrue(passwords.getAllPasswords().isEmpty());

    }

    @Test
    void theDatabaseOnlyHoldsCiphertext() throws Exception {

        passwords.savePassword(new PasswordEntry(alice, "example.com", "alice", "visible-only-in-the-app", null));

        String stored = TestSupport.text("SELECT password FROM password_entries");

        assertFalse(stored.contains("visible-only-in-the-app"));

        assertEquals(1, TestSupport.count("SELECT COUNT(*) FROM password_entries WHERE vault_id = ?", vaults.find(alice).id()));

    }

    @Test
    void entriesAlwaysBelongToTheOpenVault() throws Exception {

        // Even if a caller puts another account's id on the entry.
        passwords.savePassword(new PasswordEntry(9999, "example.com", "x", "secret", null));

        assertEquals(alice, TestSupport.count("SELECT user_id FROM password_entries"));

    }

    @Test
    void nothingWorksWhileLocked() {

        vaults.lockVault();

        assertThrows(IllegalStateException.class, passwords::getAllPasswords);

        assertThrows(IllegalStateException.class,
                () -> passwords.savePassword(new PasswordEntry(alice, "example.com", "x", "secret", null)));

    }

}
