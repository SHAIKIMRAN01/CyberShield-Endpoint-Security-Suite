package com.cybershield.services;

import com.cybershield.TestSupport;
import com.cybershield.files.FileVaultService;
import com.cybershield.password.PasswordEntry;
import com.cybershield.password.PasswordService;
import com.cybershield.vault.VaultService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.cybershield.TestSupport.ACCOUNT_PASSWORD;
import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The home screen's security score: what raises it, and the hints that come with it. */
class SecurityScoreTest {

    private static final FileVaultService.JobListener QUIET = new FileVaultService.JobListener() { };

    @TempDir
    Path tempDir;

    private final AuthenticationService auth = new AuthenticationService();

    private final VaultService vaults = new VaultService();

    private Path store;

    private int alice;

    @BeforeEach
    void setUp() {

        store = TestSupport.freshEnvironment(tempDir);

        alice = TestSupport.newUser("alice");

        assertTrue(auth.login("alice", ACCOUNT_PASSWORD).ok());

    }

    @AfterEach
    void signOut() {
        vaults.lockVault();
        auth.logout();
    }

    @Test
    void eachStepRaisesTheScoreAndClearsItsHint() throws Exception {

        SecurityScore.Score noVault = SecurityScore.compute(alice);

        assertTrue(noVault.hints().get(0).contains("Set up your vault"), "the biggest gain comes first: " + noVault.hints());
        assertNull(noVault.passwords(), "no password health without an open vault");

        vaults.createVault(alice, MASTER_PASSWORD);

        PasswordService passwords = new PasswordService();

        passwords.savePassword(new PasswordEntry(alice, "weak.example", "alice", "abc123", null));

        SecurityScore.Score weak = SecurityScore.compute(alice);

        assertTrue(weak.percent() > noVault.percent());
        assertTrue(weak.hints().stream().anyMatch(hint -> hint.startsWith("Fix 1 password")), String.valueOf(weak.hints()));
        assertTrue(weak.hints().stream().anyMatch(hint -> hint.contains("Protect your private files")));
        assertEquals(1, weak.passwords().weak());

        PasswordEntry entry = passwords.getAllPasswords().get(0);
        entry.setPassword("Gh!x7-long-Password-2026");
        passwords.updatePassword(entry);

        Path file = Files.writeString(Files.createDirectories(tempDir.resolve("files")).resolve("diary.txt"), "dear diary");
        FileVaultService files = new FileVaultService(store);
        files.protect(files.scan(List.of(file), QUIET), QUIET);

        SecurityScore.Score protectedButNotBackedUp = SecurityScore.compute(alice);

        assertTrue(protectedButNotBackedUp.percent() > weak.percent());
        assertTrue(protectedButNotBackedUp.hints().stream().anyMatch(hint -> hint.contains("Back up")), String.valueOf(protectedButNotBackedUp.hints()));

        files.backUp(Files.createDirectories(tempDir.resolve("usb")), QUIET);

        SecurityScore.Score done = SecurityScore.compute(alice);

        assertEquals(100, done.percent(), String.valueOf(done.hints()));
        assertEquals("Strong", done.grade());
        assertTrue(done.hints().isEmpty(), String.valueOf(done.hints()));

        vaults.lockVault();

        SecurityScore.Score locked = SecurityScore.compute(alice);

        assertFalse(locked.hints().isEmpty());
        assertTrue(locked.hints().get(locked.hints().size() - 1).contains("Unlock the vault"), "passwords only count with the vault open");

    }

}
