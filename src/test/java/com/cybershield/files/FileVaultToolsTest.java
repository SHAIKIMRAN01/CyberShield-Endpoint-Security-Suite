package com.cybershield.files;

import com.cybershield.TestSupport;
import com.cybershield.vault.VaultKeyFile;
import com.cybershield.vault.VaultService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The File Security tools: checking the store, recovering a lost list, and backups. */
class FileVaultToolsTest {

    private static final String MARKER = "TOOLS-MARKER-41c9";

    private static final FileVaultService.JobListener QUIET = new FileVaultService.JobListener() { };

    @TempDir
    Path tempDir;

    private Path store;

    private Path files;

    private FileVaultService service;

    private final VaultService vaults = new VaultService();

    private int alice;

    private Map<Path, String> before;

    @BeforeEach
    void setUp() throws Exception {

        store = TestSupport.freshEnvironment(tempDir);

        files = Files.createDirectories(tempDir.resolve("files"));

        service = new FileVaultService(store);

        alice = TestSupport.newUser("alice");

        vaults.createVault(alice, MASTER_PASSWORD);

        Path album = Files.createDirectories(files.resolve("Album").resolve("2026"));

        Files.write(files.resolve("Album").resolve("cover.bin"), random(300_000));
        Files.writeString(album.resolve("trip.txt"), "trip notes " + MARKER);
        Files.writeString(files.resolve("letter.txt"), "a letter " + MARKER);

        List<Path> chosen = List.of(files.resolve("Album"), files.resolve("letter.txt"));

        before = FileVaultServiceTest.hashes(chosen, null);

        FileVaultService.ProtectResult result = service.protect(service.scan(chosen, QUIET), QUIET);

        assertEquals(3, result.protectedFiles(), String.valueOf(result.problems()));

    }

    @AfterEach
    void lock() {
        vaults.lockVault();
    }

    @Test
    void checkFindsDamagedMissingAndUnlistedFiles() throws Exception {

        FileVaultService.CheckResult clean = service.checkFiles(QUIET);

        assertTrue(clean.allGood());
        assertEquals(3, clean.checked());
        assertEquals(0, clean.strays());

        List<Path> stored = storedFiles();

        byte[] bytes = Files.readAllBytes(stored.get(0));
        bytes[bytes.length - 20] ^= 1;
        Files.write(stored.get(0), bytes);

        Files.delete(stored.get(1));

        Files.write(store.resolve("0123456789abcdef0123456789abcdef"), random(100));

        FileVaultService.CheckResult found = service.checkFiles(QUIET);

        assertFalse(found.allGood());
        assertEquals(3, found.checked());
        assertEquals(1, found.damaged().size(), String.valueOf(found.damaged()));
        assertEquals(1, found.missing().size(), String.valueOf(found.missing()));
        assertEquals(1, found.strays(), "the unlisted file");

        assertFalse(FileVaultService.isJobRunning(), "the job guard is released");

    }

    @Test
    void recoveryListsTheFilesAgainAndTheyRestoreUnchanged() throws Exception {

        Set<String> names = service.listItems().stream().map(ProtectedItem::name).collect(Collectors.toSet());

        loseTheList();

        assertTrue(service.listItems().isEmpty());

        FileVaultService.RecoveryResult result = service.recoverMissingItems(QUIET);

        assertEquals(3, result.found());
        assertEquals(2, result.itemsAdded());
        assertEquals(3, result.filesAdded());

        List<ProtectedItem> items = service.listItems();

        assertEquals(names, items.stream().map(ProtectedItem::name).collect(Collectors.toSet()));

        assertEquals(0, service.recoverMissingItems(QUIET).filesAdded(), "nothing is added twice");

        FileVaultService.RestoreResult restored = service.restore(items, QUIET);

        assertEquals(3, restored.restoredFiles(), String.valueOf(restored.problems()));

        for (Map.Entry<Path, String> entry : before.entrySet()) {
            assertEquals(entry.getValue(), FileVaultServiceTest.hash(entry.getKey()), files.relativize(entry.getKey()).toString());
        }

    }

    @Test
    void recoveryLeavesAnotherAccountsFilesAlone() throws Exception {

        vaults.lockVault();

        int bob = TestSupport.newUser("bob");

        vaults.createVault(bob, "Bobs-Master-Pass-3!");

        Path bobs = Files.writeString(files.resolve("bob.txt"), "bob's file");

        service.protect(service.scan(List.of(bobs), QUIET), QUIET);

        loseTheList();

        vaults.lockVault();

        vaults.unlock(alice, MASTER_PASSWORD);

        FileVaultService.RecoveryResult forAlice = service.recoverMissingItems(QUIET);

        assertEquals(4, forAlice.found(), "four unlisted files in the store");
        assertEquals(3, forAlice.filesAdded(), "only alice's own three");
        assertEquals(Set.of("Album", "letter.txt"), service.listItems().stream().map(ProtectedItem::name).collect(Collectors.toSet()));

        vaults.lockVault();

        vaults.unlock(bob, "Bobs-Master-Pass-3!");

        assertEquals(1, service.recoverMissingItems(QUIET).filesAdded());
        assertEquals(List.of("bob.txt"), service.listItems().stream().map(ProtectedItem::name).toList());

    }

    @Test
    void aBackupBringsBackItemsAfterTheStoreIsLost() throws Exception {

        Path drive = Files.createDirectories(tempDir.resolve("usb"));

        FileVaultService.BackupResult backup = service.backUp(drive, QUIET);

        assertEquals(3, backup.files());
        assertTrue(backup.folder().getFileName().toString().startsWith("CyberShield backup "));
        assertTrue(Files.exists(VaultKeyFile.pathFor(backup.folder(), vaults.find(alice))), "the key file goes with it");

        try (Stream<Path> all = Files.walk(backup.folder())) {
            for (Path file : all.filter(Files::isRegularFile).toList()) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(content.contains(MARKER) || content.contains("letter.txt") || content.contains("Album"),
                        "nothing readable in " + backup.folder().relativize(file));
            }
        }

        Path second = service.backUp(drive, QUIET).folder();

        assertFalse(second.equals(backup.folder()), "a second backup doesn't overwrite the first");
        assertTrue(Files.exists(backup.folder().resolve("manifest.csb")));

        loseTheList();

        for (Path stored : storedFiles()) {
            Files.delete(stored);
        }

        FileVaultService.BackupRestoreResult restored = service.restoreBackup(backup.folder(), QUIET);

        assertEquals(2, restored.itemsAdded());
        assertEquals(3, restored.filesCopied());
        assertEquals(0, restored.alreadyThere());

        assertTrue(service.checkFiles(QUIET).allGood());

        assertEquals(2, service.restoreBackup(backup.folder(), QUIET).alreadyThere(), "items already listed are left alone");

        assertEquals(3, service.restore(service.listItems(), QUIET).restoredFiles());

        for (Map.Entry<Path, String> entry : before.entrySet()) {
            assertEquals(entry.getValue(), FileVaultServiceTest.hash(entry.getKey()), files.relativize(entry.getKey()).toString());
        }

    }

    @Test
    void anotherVaultCantOpenTheBackupAndOtherFoldersAreRefused() throws Exception {

        Path backup = service.backUp(Files.createDirectories(tempDir.resolve("usb")), QUIET).folder();

        IOException notABackup = assertThrows(IOException.class, () -> service.restoreBackup(files, QUIET));

        assertTrue(notABackup.getMessage().contains("isn't a CyberShield backup"));

        vaults.lockVault();

        int bob = TestSupport.newUser("bob");

        vaults.createVault(bob, "Bobs-Master-Pass-3!");

        IOException otherVault = assertThrows(IOException.class, () -> service.restoreBackup(backup, QUIET));

        assertTrue(otherVault.getMessage().contains("another vault"));

        assertTrue(service.listItems().isEmpty());

        assertFalse(FileVaultService.isJobRunning(), "the job guard is released after a failure");

    }

    // ------------------------------------------------------------- helpers

    /** As if the database had been replaced: the encrypted files stay, their list is gone. */
    private static void loseTheList() throws Exception {
        TestSupport.execute("DELETE FROM protected_files");
        TestSupport.execute("DELETE FROM protected_items");
    }

    private List<Path> storedFiles() throws IOException {

        try (Stream<Path> listing = Files.list(store)) {
            return listing.filter(file -> file.getFileName().toString().matches("[0-9a-f]{32}")).sorted().toList();
        }

    }

}
