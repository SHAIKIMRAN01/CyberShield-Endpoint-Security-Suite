package com.cybershield.backup;

import com.cybershield.backup.BackupScan.Options;
import com.cybershield.backup.BackupScan.Plan;
import com.cybershield.backup.BackupScan.Source;
import com.cybershield.backup.BackupWriter.Extra;
import com.cybershield.backup.BackupWriter.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writing the chosen files into one compressed archive, on throwaway temp files only. */
class BackupWriterTest {

    private Plan planFor(Path dir) {
        return new BackupScan().scan(List.of(new Source(dir, "Pictures")), Options.everything(), BackupScan.Progress.NONE);
    }

    @Test
    void writesEveryChosenFileUnderItsFolderNamePlusAnyExtra(@TempDir Path source, @TempDir Path out) throws IOException {
        Files.writeString(source.resolve("beach.jpg"), "a picture");
        Files.writeString(Files.createDirectory(source.resolve("trip")).resolve("map.png"), "a map");

        Path archive = out.resolve("backup.zip");
        Extra appsList = new Extra("installed-apps.txt", "App A\nApp B\n".getBytes(StandardCharsets.UTF_8));

        Result result = new BackupWriter().write(planFor(source), archive, List.of(appsList), BackupWriter.Progress.NONE);

        assertEquals(3, result.files(), "two pictures plus the apps list");
        assertTrue(Files.exists(archive));

        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertNotNull(zip.getEntry("installed-apps.txt"));
            ZipEntry beach = zip.getEntry("Pictures/beach.jpg");
            assertNotNull(beach, "files keep their folder name inside the archive");
            assertNotNull(zip.getEntry("Pictures/trip/map.png"), "subfolders are kept");

            String content = new String(zip.getInputStream(beach).readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("a picture", content, "the file's bytes are preserved");
        }
    }

    @Test
    void stoppingRemovesThePartWrittenArchive(@TempDir Path source, @TempDir Path out) throws IOException {
        Files.writeString(source.resolve("a.txt"), "x");
        Files.writeString(source.resolve("b.txt"), "y");

        Path archive = out.resolve("stopped.zip");

        assertThrows(CancellationException.class, () -> new BackupWriter().write(
                planFor(source), archive, List.of(),
                new BackupWriter.Progress() {
                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                }));

        assertFalse(Files.exists(archive), "a stopped backup leaves nothing behind");
    }

    @Test
    void twoFilesThatWouldShareANameBothSurvive(@TempDir Path source, @TempDir Path out) throws IOException {
        // same relative name under two source labels shouldn't collide in the archive
        Path a = Files.createDirectory(source.resolve("one"));
        Path b = Files.createDirectory(source.resolve("two"));
        Files.writeString(a.resolve("notes.txt"), "first");
        Files.writeString(b.resolve("notes.txt"), "second");

        Plan plan = new BackupScan().scan(
                List.of(new Source(a, "Docs"), new Source(b, "Docs")), Options.everything(), BackupScan.Progress.NONE);

        Path archive = out.resolve("dupes.zip");
        Result result = new BackupWriter().write(plan, archive, List.of(), BackupWriter.Progress.NONE);

        assertEquals(2, result.files());
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertNotNull(zip.getEntry("Docs/notes.txt"));
            assertNotNull(zip.getEntry("Docs/notes (2).txt"), "the clash is renamed, not dropped");
        }
    }

}
