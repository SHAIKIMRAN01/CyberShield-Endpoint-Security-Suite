package com.cybershield.password;

import com.cybershield.TestSupport;
import com.cybershield.services.EncryptionService;
import com.cybershield.vault.VaultService;
import com.cybershield.vault.VaultSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every field of a saved login is encrypted, older logins are converted, and password health. */
class PasswordFieldsTest {

    private static final String STRONG = "Gh!x7-long-Password-2026";

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
    void everyFieldIsCiphertextInTheDatabase() throws Exception {

        PasswordEntry entry = new PasswordEntry(alice, "github.com", "alice@example.test", STRONG, "Work account");

        entry.setCategory("Work");

        assertTrue(passwords.savePassword(entry));

        for (String column : List.of("website", "username", "password", "notes", "category")) {
            String stored = TestSupport.text("SELECT " + column + " FROM password_entries");
            for (String plain : List.of("github", "alice@", STRONG, "Work")) {
                assertFalse(stored.contains(plain), column + " holds " + plain);
            }
        }

        assertEquals(1, TestSupport.count("SELECT fields_encrypted FROM password_entries"));

        PasswordEntry read = passwords.getAllPasswords().get(0);

        assertEquals("github.com", read.getWebsite());
        assertEquals("alice@example.test", read.getUsername());
        assertEquals(STRONG, read.getPassword());
        assertEquals("Work account", read.getNotes());
        assertEquals("Work", read.getCategory());

    }

    @Test
    void loginsSavedBeforeFieldEncryptionAreConvertedOnFirstRead() throws Exception {

        int vaultId = VaultSession.current().id();

        // As the app stored them before: only the password encrypted.
        TestSupport.execute("INSERT INTO password_entries (user_id, vault_id, website, username, password, notes) VALUES (?, ?, ?, ?, ?, ?)",
                alice, vaultId, "old-site.example", "old-user", EncryptionService.getInstance().encrypt(STRONG), "old note");

        PasswordEntry read = passwords.getAllPasswords().get(0);

        assertEquals("old-site.example", read.getWebsite());
        assertEquals("old-user", read.getUsername());
        assertEquals("old note", read.getNotes());
        assertEquals(STRONG, read.getPassword());

        assertEquals(1, TestSupport.count("SELECT fields_encrypted FROM password_entries"));
        assertFalse(TestSupport.text("SELECT website FROM password_entries").contains("old-site"));

        // A second read decrypts the converted fields, and doesn't encrypt them twice.
        assertEquals("old-site.example", passwords.getAllPasswords().get(0).getWebsite());

    }

    @Test
    void favouritesStayAndAnotherVaultCantDelete() throws Exception {

        passwords.savePassword(new PasswordEntry(alice, "mail.example", "alice", STRONG, null));

        PasswordEntry entry = passwords.getAllPasswords().get(0);

        assertTrue(passwords.setFavourite(entry, true));
        assertTrue(passwords.getAllPasswords().get(0).isFavourite());

        vaults.lockVault();

        int bob = TestSupport.newUser("bob");

        vaults.createVault(bob, "Bobs-Master-Pass-3!");

        assertTrue(passwords.getAllPasswords().isEmpty());
        assertFalse(passwords.deletePassword(entry.getId()), "bob can't delete alice's login");
        assertFalse(passwords.setFavourite(entry, false));

        vaults.lockVault();

        vaults.unlock(alice, MASTER_PASSWORD);

        assertTrue(passwords.getAllPasswords().get(0).isFavourite());

    }

    @Test
    void healthFlagsWeakReusedAndOldPasswords() {

        LocalDateTime now = LocalDateTime.of(2026, 11, 22, 12, 0);

        PasswordEntry weak = entry(1, "abc123", "2026-11-01 10:00:00");
        PasswordEntry reusedA = entry(2, STRONG, "2026-11-01 10:00:00");
        PasswordEntry reusedB = entry(3, STRONG, "2026-10-01 10:00:00");
        PasswordEntry old = entry(4, "Another!Strong-Pass-99", "2025-06-01 10:00:00");
        PasswordEntry fine = entry(5, "Third#Unique-Pass-77", "2026-11-20 10:00:00");

        Map<Integer, EnumSet<PasswordHealth.Issue>> issues = PasswordHealth.check(List.of(weak, reusedA, reusedB, old, fine), now);

        assertEquals(EnumSet.of(PasswordHealth.Issue.WEAK), issues.get(1));
        assertEquals(EnumSet.of(PasswordHealth.Issue.REUSED), issues.get(2));
        assertEquals(EnumSet.of(PasswordHealth.Issue.REUSED), issues.get(3));
        assertEquals(EnumSet.of(PasswordHealth.Issue.OLD), issues.get(4));
        assertTrue(issues.get(5).isEmpty());

        PasswordHealth.Summary summary = PasswordHealth.summarize(issues);

        assertEquals(new PasswordHealth.Summary(5, 1, 2, 1), summary);
        assertEquals(1, summary.healthy(issues));
        assertFalse(summary.allGood());

        assertEquals("OK", PasswordHealth.describe(issues.get(5)));
        assertEquals("Weak, reused", PasswordHealth.describe(EnumSet.of(PasswordHealth.Issue.WEAK, PasswordHealth.Issue.REUSED)));

    }

    private static PasswordEntry entry(int id, String password, String updatedAt) {

        PasswordEntry entry = new PasswordEntry(1, "site" + id + ".example", "user", password, null);

        entry.setId(id);
        entry.setUpdatedAt(updatedAt);

        return entry;

    }

}
