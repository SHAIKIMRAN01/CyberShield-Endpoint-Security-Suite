package com.cybershield.privacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The Windows traces cleaner, on made-up folders only: never the PC's own. */
class TraceCleanerTest {

    private static final FileTime TWO_DAYS_AGO = FileTime.from(Instant.now().minus(Duration.ofDays(2)));

    @TempDir
    Path dir;

    private TraceCleaner.Places places;

    private Path recent;
    private Path automatic;
    private Path custom;
    private Path explorer;
    private Path temp;

    @BeforeEach
    void makeUpAWindowsProfile() throws IOException {

        recent = Files.createDirectories(dir.resolve("Recent"));
        automatic = Files.createDirectories(recent.resolve("AutomaticDestinations"));
        custom = Files.createDirectories(recent.resolve("CustomDestinations"));
        explorer = Files.createDirectories(dir.resolve("Explorer"));
        temp = Files.createDirectories(dir.resolve("Temp"));

        places = new TraceCleaner.Places(recent, automatic, custom, explorer, temp);

        Files.writeString(recent.resolve("taxes.pdf.lnk"), "shortcut");
        Files.writeString(recent.resolve("holiday.jpg.lnk"), "shortcut");
        Files.writeString(recent.resolve("desktop.ini"), "not a shortcut");

        Files.writeString(automatic.resolve("5f7b5f1e01b83767.automaticDestinations-ms"), "jump list");
        Files.writeString(custom.resolve("9b9cdc69c1c24e2b.customDestinations-ms"), "pinned");

        Files.writeString(explorer.resolve("thumbcache_256.db"), "thumbnails");
        Files.writeString(explorer.resolve("iconcache_16.db"), "icons, not ours to touch");

        Files.writeString(temp.resolve("new.tmp"), "a program may be using this");
        old(Files.writeString(temp.resolve("old.tmp"), "left behind"));

        Path oldFolder = Files.createDirectories(temp.resolve("setup-1234"));
        old(Files.writeString(oldFolder.resolve("unpacked.bin"), "left behind too"));
        old(oldFolder);

        Path newFolder = Files.createDirectories(temp.resolve("busy"));
        old(Files.writeString(newFolder.resolve("old inside new.tmp"), "old file, new folder"));

    }

    private static Path old(Path path) throws IOException {
        Files.setLastModifiedTime(path, TWO_DAYS_AGO);
        return path;
    }

    private static Map<TraceCleaner.Kind, TraceCleaner.Found> byKind(List<TraceCleaner.Found> found) {
        return found.stream().collect(Collectors.toMap(TraceCleaner.Found::kind, Function.identity()));
    }

    @Test
    void looksWithoutChangingAnything() throws Exception {

        Map<TraceCleaner.Kind, TraceCleaner.Found> found = byKind(new TraceCleaner(places).scan());

        assertEquals(4, found.size());
        assertEquals(2, found.get(TraceCleaner.Kind.RECENT_ITEMS).files().size(), "only the shortcuts");
        assertEquals(2, found.get(TraceCleaner.Kind.JUMP_LISTS).files().size());
        assertEquals(List.of(explorer.resolve("thumbcache_256.db")), found.get(TraceCleaner.Kind.THUMBNAILS).files());

        TraceCleaner.Found tempFiles = found.get(TraceCleaner.Kind.TEMP_FILES);

        assertEquals(3, tempFiles.files().size(), "only files older than a day: " + tempFiles.files());
        assertFalse(tempFiles.files().contains(temp.resolve("new.tmp")));
        assertEquals(List.of(temp.resolve("setup-1234")), tempFiles.folders(), "only folders untouched for a day");
        assertEquals("left behind".length() + "left behind too".length() + "old file, new folder".length(), tempFiles.bytes());

        assertTrue(Files.exists(recent.resolve("taxes.pdf.lnk")), "a scan removes nothing");
        assertTrue(Files.exists(temp.resolve("old.tmp")));

    }

    @Test
    void removesOnlyWhatWasChosen() throws Exception {

        TraceCleaner cleaner = new TraceCleaner(places);

        Map<TraceCleaner.Kind, TraceCleaner.Found> found = byKind(cleaner.scan());

        TraceCleaner.Result result = cleaner.clean(List.of(
                found.get(TraceCleaner.Kind.RECENT_ITEMS),
                found.get(TraceCleaner.Kind.TEMP_FILES)), TraceCleaner.Progress.NONE);

        assertEquals(5, result.removed());
        assertEquals(0, result.skipped());

        assertFalse(Files.exists(recent.resolve("taxes.pdf.lnk")));
        assertFalse(Files.exists(recent.resolve("holiday.jpg.lnk")));
        assertTrue(Files.exists(recent.resolve("desktop.ini")));

        assertTrue(Files.exists(automatic.resolve("5f7b5f1e01b83767.automaticDestinations-ms")), "jump lists weren't chosen");
        assertTrue(Files.exists(explorer.resolve("thumbcache_256.db")), "nor the thumbnails");
        assertTrue(Files.exists(explorer.resolve("iconcache_16.db")));

        assertTrue(Files.exists(temp.resolve("new.tmp")), "new temp files stay");
        assertFalse(Files.exists(temp.resolve("old.tmp")));
        assertFalse(Files.exists(temp.resolve("setup-1234")), "an old folder goes once empty");
        assertTrue(Files.isDirectory(temp.resolve("busy")), "a folder in use lately stays");

    }

    @Test
    void aFileInUseIsSkipped() throws Exception {

        assumeTrue(windows(), "only Windows refuses to delete an open file");

        TraceCleaner cleaner = new TraceCleaner(places);

        TraceCleaner.Found tempFiles = byKind(cleaner.scan()).get(TraceCleaner.Kind.TEMP_FILES);

        TraceCleaner.Result result;

        try (FileInputStream holdOpen = new FileInputStream(temp.resolve("old.tmp").toFile())) {
            result = cleaner.clean(List.of(tempFiles), TraceCleaner.Progress.NONE);
        }

        assertEquals(2, result.removed());
        assertEquals(1, result.skipped());
        assertEquals("left behind", Files.readString(temp.resolve("old.tmp")));

    }

    @Test
    void neverFollowsAJunctionOutOfTheTempFolder() throws Exception {

        assumeTrue(windows(), "junctions are a Windows thing");

        Path outside = Files.createDirectories(dir.resolve("Documents"));

        Path precious = old(Files.writeString(outside.resolve("thesis.docx"), "years of work"));

        ShredderTest.junction(temp.resolve("documents-link"), outside);

        TraceCleaner cleaner = new TraceCleaner(places);

        TraceCleaner.Found tempFiles = byKind(cleaner.scan()).get(TraceCleaner.Kind.TEMP_FILES);

        assertTrue(tempFiles.files().stream().noneMatch(file -> file.toString().contains("documents-link")), String.valueOf(tempFiles.files()));
        assertTrue(tempFiles.folders().stream().noneMatch(folder -> folder.toString().contains("documents-link")));

        cleaner.clean(List.of(tempFiles), TraceCleaner.Progress.NONE);

        assertEquals("years of work", Files.readString(precious));

    }

    @Test
    void missingFoldersFindNothing() {

        TraceCleaner.Places nowhere = new TraceCleaner.Places(dir.resolve("a"), dir.resolve("b"), null, dir.resolve("c"), dir.resolve("d"));

        List<TraceCleaner.Found> found = new TraceCleaner(nowhere).scan();

        assertEquals(4, found.size());
        assertTrue(found.stream().allMatch(kind -> kind.files().isEmpty() && kind.bytes() == 0));

    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

}
