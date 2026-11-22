package com.cybershield.files;

import com.cybershield.TestSupport;
import com.cybershield.config.DatabaseConfig;
import com.cybershield.vault.VaultKeyFile;
import com.cybershield.vault.VaultService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.cybershield.TestSupport.MASTER_PASSWORD;
import static com.cybershield.TestSupport.random;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protecting, hiding and restoring files, the safety rules, and the selection tray. */
class FileVaultServiceTest {

    private static final int CHUNK = 1024 * 1024;

    private static final String MARKER = "SECRET-MARKER-7f3a9c";

    private static final FileVaultService.JobListener QUIET = new FileVaultService.JobListener() { };

    @TempDir
    Path tempDir;

    private Path store;

    private Path files;

    private Path outside;

    private FileVaultService service;

    private final VaultService vaults = new VaultService();

    private int alice;

    @BeforeEach
    void setUp() throws Exception {

        store = TestSupport.freshEnvironment(tempDir);

        files = Files.createDirectories(tempDir.resolve("files"));

        outside = Files.createDirectories(tempDir.resolve("outside"));

        service = new FileVaultService(store);

        alice = TestSupport.newUser("alice");

        vaults.createVault(alice, MASTER_PASSWORD);

    }

    @AfterEach
    void lock() {
        vaults.lockVault();
    }

    // ------------------------------------------------------------- safety rules

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void systemAndOwnFoldersAreRefused() throws Exception {

        String home = System.getProperty("user.home");

        assertNotNull(service.checkAllowed(Path.of("C:\\")), "drive root");
        assertNotNull(service.checkAllowed(Path.of(home)), "user folder");
        assertNotNull(service.checkAllowed(Path.of(home).getParent()), "parent of the user folder");
        assertNotNull(service.checkAllowed(Path.of(System.getenv("SystemRoot"), "System32")), "Windows");
        assertNotNull(service.checkAllowed(Path.of(System.getenv("ProgramFiles"))), "Program Files");
        assertNotNull(service.checkAllowed(Path.of(System.getenv("APPDATA"), "Anything")), "AppData Roaming");
        assertNotNull(service.checkAllowed(Path.of(System.getenv("LOCALAPPDATA"), "Programs")), "AppData Local");
        assertNotNull(service.checkAllowed(Path.of("C:\\$Recycle.Bin")), "Recycle Bin");

        if (Files.exists(Path.of("C:\\PROGRA~1"))) {
            assertNotNull(service.checkAllowed(Path.of("C:\\PROGRA~1")), "8.3 short name of Program Files");
        }

        assertNotNull(service.checkAllowed(store), "the hidden store");
        assertNotNull(service.checkAllowed(tempDir), "a folder holding the store");
        assertNotNull(service.checkAllowed(DatabaseConfig.databasePath().getParent()), "the database folder");

        assertNull(service.checkAllowed(Files.createDirectories(files.resolve("AllowedFolder"))), "an ordinary folder");

    }

    // ------------------------------------------------------------- selection tray

    @Test
    void selectionStacksNewestFirstAndMergesFolders() {

        FileSelection selection = new FileSelection();

        Path a = files.resolve("A"), ab = a.resolve("B"), abc = ab.resolve("c.txt"), ad = a.resolve("D"), e = files.resolve("E");

        selection.add(abc);
        selection.add(ad);

        assertEquals(List.of(ad, abc), selection.entries(), "newest first");

        FileSelection.AddResult merged = selection.add(a);

        assertEquals(FileSelection.Change.ADDED_AND_MERGED, merged.change());
        assertEquals(2, merged.merged());
        assertEquals(List.of(a), selection.entries(), "a parent replaces its selected children");

        FileSelection.AddResult inside = selection.add(ab);

        assertEquals(FileSelection.Change.ALREADY_INCLUDED, inside.change());
        assertEquals(a, inside.coveredBy());

        assertEquals(FileSelection.Change.ALREADY_INCLUDED, selection.add(a).change(), "the same path twice");

        selection.add(e);

        assertEquals(List.of(e, a), selection.entries(), "different places stack newest first");
        assertEquals(a, selection.includedBy(abc));
        assertNull(selection.includedBy(files.resolve("Z")));
        assertNull(selection.includedBy(files.resolve("AB")), "a sibling with a longer name isn't inside");

        selection.remove(a);

        assertEquals(List.of(e), selection.entries());
        assertFalse(selection.contains(a));

    }

    // ------------------------------------------------------------- protect and restore

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void protectHidesEverythingAndRestoreBringsItBackUnchanged() throws Exception {

        Path photos = files.resolve("Photos"), docs = files.resolve("Docs");
        Path single = files.resolve("single.txt"), keep = files.resolve("keep.txt");

        Files.createDirectories(photos.resolve("Nested/deep"));
        Files.createDirectories(photos.resolve("EmptyDir"));
        Files.createDirectories(docs);

        Files.write(photos.resolve("a.bin"), random(2 * CHUNK + CHUNK / 2));
        Files.writeString(photos.resolve("b.txt"), "photo notes " + MARKER);

        Path deep = photos.resolve("Nested/deep/c.txt");
        Files.writeString(deep, "deep file");
        Files.setLastModifiedTime(deep, FileTime.from(Instant.parse("2020-01-02T03:04:05Z")));

        Path hidden = photos.resolve("hidden.ini");
        Files.writeString(hidden, "[x]");
        Files.setAttribute(hidden, "dos:hidden", true);
        Files.setAttribute(hidden, "dos:system", true);

        Path readOnly = photos.resolve("readonly.txt");
        Files.writeString(readOnly, "read only");
        Files.setAttribute(readOnly, "dos:readonly", true);

        Files.writeString(docs.resolve("report.txt"), "report " + MARKER);

        Path locked = docs.resolve("locked.txt");
        Files.writeString(locked, "open in another program");

        Files.writeString(single, "single " + MARKER);
        Files.writeString(keep, "not selected");
        Files.writeString(outside.resolve("secret.txt"), "outside the selection");

        Path junction = photos.resolve("link-to-outside");
        Process mklink = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectErrorStream(true).start();
        mklink.getInputStream().readAllBytes();
        assertEquals(0, mklink.waitFor(), "test junction");

        Map<Path, String> before = hashes(List.of(photos, docs, single), junction);

        long createdB = Files.readAttributes(photos.resolve("b.txt"), BasicFileAttributes.class).creationTime().toMillis();

        FileVaultService.ScanResult scan = service.scan(List.of(photos, docs, single), QUIET);

        assertEquals(8, scan.fileCount());
        assertEquals(1, scan.skipped().size(), "the junction is skipped");
        assertTrue(scan.skipped().get(0).contains("link-to-outside"));
        assertTrue(scan.enoughSpace());

        FileVaultService.ProtectResult result;

        try (FileInputStream holdOpen = new FileInputStream(locked.toFile())) {
            result = service.protect(scan, QUIET);
        }

        assertEquals(7, result.protectedFiles());
        assertEquals(1, result.skippedInUse(), "the open file is skipped");
        assertEquals(3, result.items());
        assertTrue(result.problems().isEmpty(), String.valueOf(result.problems()));

        for (Path path : before.keySet()) {
            if (!path.equals(locked)) {
                assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), "original removed: " + files.relativize(path));
            }
        }

        assertEquals(before.get(locked), hash(locked), "the open file is untouched");
        assertEquals("not selected", Files.readString(keep));
        assertEquals("outside the selection", Files.readString(outside.resolve("secret.txt")), "the junction's target is untouched");
        assertFalse(Files.exists(photos.resolve("Nested")), "emptied folders go too");

        List<Path> blobs;

        try (Stream<Path> listing = Files.list(store)) {
            blobs = listing.filter(Files::isRegularFile).toList();
        }

        assertEquals(7, blobs.size());
        assertTrue(blobs.stream().allMatch(blob -> blob.getFileName().toString().matches("[0-9a-f]{32}")), "random names");

        for (Path blob : blobs) {
            assertFalse(new String(Files.readAllBytes(blob), StandardCharsets.ISO_8859_1).contains(MARKER), "no plain text in the store");
        }

        DosFileAttributes storeAttributes = Files.readAttributes(store, DosFileAttributes.class);
        assertTrue(storeAttributes.isHidden() && storeAttributes.isSystem(), "the store is hidden and system");

        assertTrue(Files.exists(VaultKeyFile.pathFor(store, vaults.find(alice))), "the vault's key file sits with its files");

        List<ProtectedItem> items = service.listItems();

        Map<String, ProtectedItem> byName = items.stream().collect(Collectors.toMap(ProtectedItem::name, item -> item));

        assertEquals(Set.of("Photos", "Docs", "single.txt"), byName.keySet());
        assertEquals(5, byName.get("Photos").storedFiles());
        assertEquals(1, byName.get("Docs").info().fileCount(), "Docs records the one file really protected");
        assertEquals(files.toString(), byName.get("single.txt").location());

        // Something new now sits where single.txt was: restoring must not overwrite it.
        Files.writeString(single, "a newer file with the same name");

        FileVaultService.RestoreResult restored = service.restore(items, QUIET);

        assertEquals(7, restored.restoredFiles());
        assertEquals(3, restored.restoredItems());
        assertEquals(1, restored.renamed());
        assertTrue(restored.problems().isEmpty(), String.valueOf(restored.problems()));
        assertEquals("a newer file with the same name", Files.readString(single));

        before.put(files.resolve("single (restored).txt"), before.remove(single));

        for (Map.Entry<Path, String> entry : before.entrySet()) {
            assertEquals(entry.getValue(), hash(entry.getKey()), "content identical: " + files.relativize(entry.getKey()));
        }

        assertEquals(Instant.parse("2020-01-02T03:04:05Z"), Files.getLastModifiedTime(deep).toInstant());
        assertEquals(createdB, Files.readAttributes(photos.resolve("b.txt"), BasicFileAttributes.class).creationTime().toMillis());
        assertTrue(Files.readAttributes(hidden, DosFileAttributes.class).isHidden());
        assertTrue(Files.readAttributes(readOnly, DosFileAttributes.class).isReadOnly());
        assertTrue(Files.isDirectory(photos.resolve("EmptyDir")), "empty folders come back");
        assertTrue(service.listItems().isEmpty());

        try (Stream<Path> listing = Files.list(store)) {
            assertEquals(0, listing.filter(Files::isRegularFile).count(), "the store is empty again");
        }

    }

    @Test
    void stoppingKeepsWhatIsDoneAndLeavesTheRestUntouched() throws Exception {

        Path folder = Files.createDirectories(files.resolve("StopMe"));

        for (int i = 1; i <= 3; i++) {
            Files.write(folder.resolve("f" + i + ".bin"), random(CHUNK + 10));
        }

        Map<Path, String> before = hashes(List.of(folder), null);

        boolean[] stop = {false};

        FileVaultService.JobListener stopDuringSecond = new FileVaultService.JobListener() {

            @Override
            public void message(String text) {
                if (text.startsWith("Encrypting 2 of")) {
                    stop[0] = true;
                }
            }

            @Override
            public boolean isCancelled() {
                return stop[0];
            }

        };

        FileVaultService.ProtectResult result = service.protect(service.scan(List.of(folder), QUIET), stopDuringSecond);

        assertTrue(result.stoppedEarly());
        assertEquals(1, result.protectedFiles());
        assertEquals(before.get(folder.resolve("f2.bin")), hash(folder.resolve("f2.bin")));
        assertEquals(before.get(folder.resolve("f3.bin")), hash(folder.resolve("f3.bin")));

        List<ProtectedItem> items = service.listItems();

        assertEquals(1, items.size());
        assertEquals(1, items.get(0).storedFiles());

        assertEquals(1, service.restore(items, QUIET).restoredFiles());
        assertEquals(before.get(folder.resolve("f1.bin")), hash(folder.resolve("f1.bin")));

    }

    @Test
    void anotherAccountDoesntSeeTheseItems() throws Exception {

        Path secret = Files.writeString(files.resolve("alice.txt"), "alice's file");

        service.protect(service.scan(List.of(secret), QUIET), QUIET);

        assertEquals(1, service.listItems().size());

        vaults.lockVault();

        int bob = TestSupport.newUser("bob");

        vaults.createVault(bob, "Bobs-Master-Pass-3!");

        assertTrue(service.listItems().isEmpty(), "bob's vault has nothing protected");

        vaults.lockVault();

        vaults.unlock(alice, MASTER_PASSWORD);

        assertEquals(1, service.listItems().size());

    }

    @Test
    void leftoverTempFilesAreTidiedAway() throws Exception {

        Files.createDirectories(store);

        Files.writeString(store.resolve("abandoned.tmp"), "half written");

        service.removeLeftoverTempFiles();

        assertFalse(Files.exists(store.resolve("abandoned.tmp")));

    }

    // ------------------------------------------------------------- helpers

    static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static Map<Path, String> hashes(List<Path> roots, Path skip) throws Exception {

        Map<Path, String> map = new HashMap<>();

        for (Path root : roots) {

            if (Files.isRegularFile(root)) {
                map.put(root, hash(root));
                continue;
            }

            try (Stream<Path> walk = Files.walk(root)) {

                for (Path path : walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    if (skip == null || !path.startsWith(skip)) {
                        map.put(path, hash(path));
                    }
                }

            }

        }

        return map;

    }

}
