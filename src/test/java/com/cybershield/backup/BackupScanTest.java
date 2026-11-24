package com.cybershield.backup;

import com.cybershield.backup.BackupScan.Options;
import com.cybershield.backup.BackupScan.Plan;
import com.cybershield.backup.BackupScan.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing which files go into a backup. The filters are checked on their own; the walk is
 * checked on a throwaway temp folder, never real files.
 */
class BackupScanTest {

    @Test
    void theSizeFilterLetsThroughWhatFitsAndSkipsWhatDoesnt() {
        long limit = 500L * 1024 * 1024;
        assertTrue(BackupScan.withinSize(10, limit));
        assertTrue(BackupScan.withinSize(limit, limit), "exactly the limit is allowed");
        assertFalse(BackupScan.withinSize(limit + 1, limit));
        // a zero limit means "any size"
        assertTrue(BackupScan.withinSize(Long.MAX_VALUE, 0));
    }

    @Test
    void theAgeFilterKeepsRecentFilesAndDropsOldOnes() {
        Instant now = Instant.parse("2026-11-24T12:00:00Z");
        assertTrue(BackupScan.withinAge(now.minus(Duration.ofDays(2)), 7, now));
        assertTrue(BackupScan.withinAge(now.minus(Duration.ofDays(7)).plusSeconds(1), 7, now));
        assertFalse(BackupScan.withinAge(now.minus(Duration.ofDays(30)), 7, now));
        // zero days means "every date"
        assertTrue(BackupScan.withinAge(now.minus(Duration.ofDays(3650)), 0, now));
    }

    @Test
    void theScanAppliesBothFiltersAndCountsWhatItSkips(@TempDir Path dir) throws IOException {
        // a small recent file (kept), a big recent file (too big), an old small file (too old)
        Path recent = Files.writeString(dir.resolve("photo.jpg"), "x");
        Path big = Files.write(dir.resolve("movie.mp4"), new byte[2048]);
        Path old = Files.writeString(dir.resolve("letter.txt"), "y");

        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(40))));

        Options options = Options.everything().withRecentDays(7).withMaxFileBytes(1024);

        Plan plan = new BackupScan().scan(
                List.of(new Source(dir, "Pictures")), options, BackupScan.Progress.NONE);

        assertEquals(1, plan.count(), "only the small recent file is included");
        assertEquals("photo.jpg", plan.entries().get(0).path().getFileName().toString());
        assertEquals(1, plan.skippedTooBig());
        assertEquals(1, plan.skippedTooOld());
        assertTrue(plan.totalBytes() >= 1);
    }

    @Test
    void withNoFiltersEverythingIsIncluded(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "aa");
        Files.writeString(Files.createDirectory(dir.resolve("sub")).resolve("b.txt"), "bbb");

        Plan plan = new BackupScan().scan(
                List.of(new Source(dir, "Docs")), Options.everything(), BackupScan.Progress.NONE);

        assertEquals(2, plan.count(), "subfolders are included");
        assertEquals(5, plan.totalBytes());
        assertEquals(0, plan.skippedTooBig());
        assertEquals(0, plan.skippedTooOld());
    }

    @Test
    void cancellingStopsTheScan(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "aa");

        Plan plan = new BackupScan().scan(
                List.of(new Source(dir, "Docs")), Options.everything(),
                new BackupScan.Progress() {
                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                });

        assertTrue(plan.entries().isEmpty());
    }

}
