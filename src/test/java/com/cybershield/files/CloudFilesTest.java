package com.cybershield.files;

import com.cybershield.TestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Files in OneDrive are found before they are protected, so the review step can warn
 * that protecting them takes them out of OneDrive too. A made-up OneDrive folder is
 * used: real online-only files would need changes in the user's OneDrive.
 */
class CloudFilesTest {

    private static final FileVaultService.JobListener QUIET = new FileVaultService.JobListener() { };

    @TempDir
    Path tempDir;

    @AfterEach
    void forgetRoots() {
        System.clearProperty(CloudFiles.ROOTS_PROPERTY);
    }

    @Test
    void filesInsideOneDriveAreCountedAndOthersArent() throws Exception {

        Path store = TestSupport.freshEnvironment(tempDir);

        Path oneDrive = Files.createDirectories(tempDir.resolve("OneDrive"));

        System.setProperty(CloudFiles.ROOTS_PROPERTY, oneDrive.toString());

        Path synced = Files.createDirectories(oneDrive.resolve("Documents"));
        Files.writeString(synced.resolve("a.txt"), "in OneDrive");
        Files.writeString(synced.resolve("b.txt"), "in OneDrive too");

        Path local = Files.writeString(Files.createDirectories(tempDir.resolve("Local")).resolve("c.txt"), "only here");

        FileVaultService.ScanResult scan = new FileVaultService(store).scan(List.of(synced, local), QUIET);

        assertEquals(3, scan.fileCount());
        assertEquals(2, scan.cloud().files());
        assertEquals(0, scan.cloud().onlineOnly(), "files written here are on this PC, not online-only");
        assertTrue(scan.cloud().any());

    }

    @Test
    void noOneDriveMeansNothingToWarnAbout() throws Exception {

        Path store = TestSupport.freshEnvironment(tempDir);

        System.setProperty(CloudFiles.ROOTS_PROPERTY, "");

        Path local = Files.writeString(tempDir.resolve("c.txt"), "only here");

        assertFalse(new FileVaultService(store).scan(List.of(local), QUIET).cloud().any());

    }

    @Test
    void onlineOnlyPlaceholdersAreRecognisedByTheirAttributes() {

        assertTrue(CloudFiles.isOnlineOnly(CloudFiles.RECALL_ON_DATA_ACCESS));
        assertTrue(CloudFiles.isOnlineOnly(CloudFiles.RECALL_ON_OPEN | 0x20));
        assertTrue(CloudFiles.isOnlineOnly(CloudFiles.OFFLINE));
        assertFalse(CloudFiles.isOnlineOnly(0x20), "an ordinary file (archive bit only)");
        assertFalse(CloudFiles.isOnlineOnly(0x80000 | 0x20), "pinned, always kept on this PC");

    }

}
