package com.cybershield.scan;

import com.cybershield.scan.FileScan.Area;
import com.cybershield.scan.FileScan.Finding;
import com.cybershield.scan.FileScan.Options;
import com.cybershield.scan.FileScan.Reason;
import com.cybershield.scan.FileScan.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The risky-file finder. Most of this exercises the pure {@link FileScan#reasonFor} rule,
 * which never touches a disk; the last few use a throwaway temp folder, never real files.
 */
class FileScanTest {

    private static final long NO_LARGE = 0;

    private static Reason reason(String name, Area area) {
        return FileScan.reasonFor(name, area, false, 1, NO_LARGE);
    }

    @Test
    void aProgramDressedAsADocumentIsTheTopConcern() {
        assertEquals(Reason.DOUBLE_EXTENSION, reason("invoice.pdf.exe", Area.DOWNLOADS));
        assertEquals(Reason.DOUBLE_EXTENSION, reason("holiday.jpg.scr", Area.DESKTOP));
        assertEquals(Reason.DOUBLE_EXTENSION, reason("accounts.xlsx.bat", Area.DOCUMENTS));
        // even buried deep in the profile, a disguise is worth flagging
        assertEquals(Reason.DOUBLE_EXTENSION, reason("photo.png.exe", Area.OTHER));
    }

    @Test
    void anOrdinaryVersionedNameIsNotADisguise() {
        // the middle part is a version number, not a document type
        assertNull(reason("python3.11.exe", Area.DOCUMENTS));
        assertFalse(FileScan.hasDoubleExtension("app.1.2.exe"));
        assertTrue(FileScan.hasDoubleExtension("invoice.pdf.exe"));
        assertFalse(FileScan.hasDoubleExtension("setup.exe"));
        assertFalse(FileScan.hasDoubleExtension("readme"));
    }

    @Test
    void aHiddenProgramIsFlaggedWhereverItHides() {
        assertEquals(Reason.HIDDEN_PROGRAM,
                FileScan.reasonFor("svchost.exe", Area.DOCUMENTS, true, 1, NO_LARGE));
        assertEquals(Reason.HIDDEN_PROGRAM,
                FileScan.reasonFor("task.vbs", Area.OTHER, true, 1, NO_LARGE));
        // but a hidden program inside AppData is normal, so it is left alone
        assertNull(FileScan.reasonFor("helper.exe", Area.APPDATA, true, 1, NO_LARGE));
    }

    @Test
    void placeDecidesHowAProgramIsCalledOut() {
        assertEquals(Reason.STARTUP_ITEM, reason("updater.exe", Area.STARTUP));
        assertEquals(Reason.PROGRAM_IN_TEMP, reason("setup.exe", Area.TEMP));
        assertEquals(Reason.PROGRAM_IN_DOWNLOADS, reason("installer.exe", Area.DOWNLOADS));
    }

    @Test
    void anEverydayProgramInAnEverydayPlaceIsNotFlagged() {
        // people keep apps and games on the desktop and in Documents; don't cry wolf
        assertNull(reason("game.exe", Area.DESKTOP));
        assertNull(reason("tool.exe", Area.DOCUMENTS));
        assertNull(reason("app.exe", Area.OTHER));
        // and plain documents are never flagged
        assertNull(reason("letter.pdf", Area.DOWNLOADS));
        assertNull(reason("photo.jpg", Area.DESKTOP));
    }

    @Test
    void scriptsAreFlaggedOnlyWhereAPersonKeepsTheirOwnFiles() {
        assertEquals(Reason.SCRIPT_FILE, reason("run.bat", Area.DOWNLOADS));
        assertEquals(Reason.SCRIPT_FILE, reason("install.ps1", Area.DESKTOP));
        assertEquals(Reason.SCRIPT_FILE, reason("macro.vbs", Area.DOCUMENTS));
        // apps legitimately ship scripts inside AppData and elsewhere — too noisy to flag
        assertNull(reason("preload.js", Area.APPDATA));
        assertNull(reason("build.js", Area.OTHER));
    }

    @Test
    void aDeviceScanFlagsProgramsScriptsAndAutorunFiles() {
        // on a removable/external drive, programs and scripts are worth noting (USB is a spreading route)
        assertEquals(Reason.PROGRAM_ON_DEVICE, reason("setup.exe", Area.REMOVABLE));
        assertEquals(Reason.SCRIPT_FILE, reason("run.bat", Area.REMOVABLE));
        assertEquals(Reason.AUTORUN, reason("autorun.inf", Area.REMOVABLE));
        // the strong signals still apply on a device
        assertEquals(Reason.DOUBLE_EXTENSION, reason("photo.jpg.exe", Area.REMOVABLE));
        assertEquals(Reason.HIDDEN_PROGRAM,
                FileScan.reasonFor("hidden.exe", Area.REMOVABLE, true, 1, NO_LARGE));
        // an autorun file that is NOT on a device is just a config file, left alone
        assertNull(reason("autorun.inf", Area.OTHER));
        assertNull(reason("autorun.inf", Area.DOWNLOADS));
        // an ordinary document on a device is not flagged
        assertNull(reason("holiday.jpg", Area.REMOVABLE));
    }

    @Test
    void largeFilesAreInformationOnlyAndOnlyInAPersonsOwnFolders() {
        long twoGb = 2L * 1024 * 1024 * 1024;
        long oneGb = 1024L * 1024 * 1024;
        assertEquals(Reason.LARGE_FILE,
                FileScan.reasonFor("movie.mkv", Area.DOWNLOADS, false, twoGb, oneGb));
        assertEquals(FileScan.Level.INFO, Reason.LARGE_FILE.level());
        // a big cache file inside AppData is not something to tidy up by hand
        assertNull(FileScan.reasonFor("cache.dat", Area.APPDATA, false, twoGb, oneGb));
        // a small file never counts as large
        assertNull(FileScan.reasonFor("note.txt", Area.DOWNLOADS, false, 10, oneGb));
    }

    @Test
    void profilePathsAreSortedIntoTheRightArea() {
        assertEquals(Area.DOWNLOADS, FileScan.areaOf(Path.of("C:\\Users\\sam\\Downloads\\a.exe"), Area.PROFILE));
        assertEquals(Area.DOCUMENTS, FileScan.areaOf(Path.of("C:\\Users\\sam\\Documents\\a.exe"), Area.PROFILE));
        assertEquals(Area.STARTUP, FileScan.areaOf(
                Path.of("C:\\Users\\sam\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\a.exe"),
                Area.PROFILE));
        assertEquals(Area.APPDATA, FileScan.areaOf(Path.of("C:\\Users\\sam\\AppData\\Local\\x\\a.exe"), Area.PROFILE));
        // a node_modules script sits in neither a user folder nor AppData, so it stays OTHER and quiet
        assertEquals(Area.OTHER, FileScan.areaOf(Path.of("C:\\Users\\sam\\code\\node_modules\\x.js"), Area.PROFILE));
        // a non-profile root keeps the area it was given
        assertEquals(Area.TEMP, FileScan.areaOf(Path.of("C:\\Windows\\Temp\\a.exe"), Area.TEMP));
    }

    @Test
    void walkingAFolderFindsTheRightFilesAndLeavesTheRestAlone(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("invoice.pdf.exe"), "x");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.writeString(dir.resolve("holiday.jpg"), "x");
        Path sub = Files.createDirectory(dir.resolve("sub"));
        Files.writeString(sub.resolve("run.bat"), "x");

        List<Finding> found = new FileScan().scan(
                List.of(new Root(dir, Area.DOWNLOADS)), Options.quick(), FileScan.Progress.NONE);

        Map<String, Reason> byName = new java.util.HashMap<>();
        for (Finding f : found) {
            byName.put(f.path().getFileName().toString(), f.reason());
        }

        assertEquals(Reason.DOUBLE_EXTENSION, byName.get("invoice.pdf.exe"));
        assertEquals(Reason.SCRIPT_FILE, byName.get("run.bat"));
        assertFalse(byName.containsKey("notes.txt"), "a plain text file should not be flagged");
        assertFalse(byName.containsKey("holiday.jpg"), "a plain picture should not be flagged");
        assertEquals(2, found.size());
    }

    @Test
    void cancellingStopsTheScanWithoutError(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("installer.exe"), "x");

        List<Finding> found = new FileScan().scan(
                List.of(new Root(dir, Area.DOWNLOADS)), Options.quick(),
                new FileScan.Progress() {
                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                });

        assertTrue(found.isEmpty(), "a scan cancelled before it starts should find nothing");
    }

    @Test
    void aDeepScanHashesTheFilesWorthLookingAt(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("invoice.pdf.exe"), "hash me");

        AtomicInteger updates = new AtomicInteger();

        List<Finding> found = new FileScan().scan(
                List.of(new Root(dir, Area.DOWNLOADS)), Options.deep(),
                new FileScan.Progress() {
                    @Override
                    public void update(int filesSeen, Path current) {
                        updates.incrementAndGet();
                    }
                });

        assertEquals(1, found.size());
        String sha = found.get(0).sha256();
        assertEquals(64, sha.length(), "SHA-256 should be 64 hex characters");
        // SHA-256 of the bytes "hash me"
        assertEquals("eb201af5aaf0d60629d3d2a61e466cfc0fedb517add831ecac5235e1daa963d6", sha);
    }

}
