package com.cybershield.backup;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Works out which of the folders you chose will go into a backup, applying your filters —
 * only files changed within the last so-many days, and nothing bigger than a chosen size.
 * It looks and counts; it copies nothing. The copying is {@link BackupWriter}'s job, and it
 * only ever runs after you have seen this plan and said go ahead.
 *
 * <p>The per-file decision ({@link #withinAge}, {@link #withinSize}) is pure, so it is tested
 * on made-up facts; the walk just gathers each file's size and date and asks those.
 */
public final class BackupScan {

    /** A folder you chose to back up. {@code label} names it, and becomes its top folder in the archive. */
    public record Source(Path dir, String label) {
    }

    /** The filters. {@code recentDays} 0 means every date; {@code maxFileBytes} 0 means any size. */
    public record Options(int recentDays, long maxFileBytes, int maxFiles) {

        public static Options everything() {
            return new Options(0, 0, 200_000);
        }

        public Options withRecentDays(int days) {
            return new Options(days, maxFileBytes, maxFiles);
        }

        public Options withMaxFileBytes(long bytes) {
            return new Options(recentDays, bytes, maxFiles);
        }

    }

    /** One file that will be copied. */
    public record Entry(Path path, Source source, long bytes, Instant modified) {
    }

    /** What the scan found: the files to copy, their total size, and what the filters left out. */
    public record Plan(List<Entry> entries, long totalBytes, int skippedTooBig, int skippedTooOld) {

        public int count() {
            return entries.size();
        }

    }

    /** Lets a caller follow along and stop the scan. */
    public interface Progress {

        Progress NONE = new Progress() { };

        default void update(int filesSeen, Path current) {
        }

        default boolean isCancelled() {
            return false;
        }

    }

    /** Looks through the chosen folders and returns the plan. Copies nothing. */
    public Plan scan(List<Source> sources, Options options, Progress progress) {

        List<Entry> entries = new ArrayList<>();

        int[] seen = {0};
        long[] totalBytes = {0};
        int[] skippedBig = {0};
        int[] skippedOld = {0};

        Instant now = Instant.now();

        for (Source source : sources) {
            if (progress.isCancelled() || seen[0] >= options.maxFiles()) {
                break;
            }
            walk(source, options, now, progress, entries, seen, totalBytes, skippedBig, skippedOld);
        }

        return new Plan(entries, totalBytes[0], skippedBig[0], skippedOld[0]);
    }

    private void walk(Source source, Options options, Instant now, Progress progress, List<Entry> out,
                      int[] seen, long[] totalBytes, int[] skippedBig, int[] skippedOld) {

        if (source.dir() == null || !Files.isDirectory(source.dir())) {
            return;
        }

        try {
            Files.walkFileTree(source.dir(), EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<>() {

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (progress.isCancelled() || seen[0] >= options.maxFiles()) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (!attrs.isRegularFile()) {
                                return FileVisitResult.CONTINUE;
                            }

                            seen[0]++;
                            if ((seen[0] & 0x1FF) == 0) {
                                progress.update(seen[0], file);
                            }

                            long bytes = attrs.size();
                            Instant modified = attrs.lastModifiedTime().toInstant();

                            if (!withinSize(bytes, options.maxFileBytes())) {
                                skippedBig[0]++;
                            } else if (!withinAge(modified, options.recentDays(), now)) {
                                skippedOld[0]++;
                            } else {
                                out.add(new Entry(file, source, bytes, modified));
                                totalBytes[0] += bytes;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }

                    });
        } catch (IOException | RuntimeException ignored) {
            // a folder that vanished or can't be read mid-walk — keep what we already have
        }
    }

    /** True when the size filter is off, or the file is within the limit. */
    public static boolean withinSize(long bytes, long maxFileBytes) {
        return maxFileBytes <= 0 || bytes <= maxFileBytes;
    }

    /** True when the age filter is off, or the file was changed within the last {@code recentDays} days. */
    public static boolean withinAge(Instant modified, int recentDays, Instant now) {
        if (recentDays <= 0) {
            return true;
        }
        return !modified.isBefore(now.minus(Duration.ofDays(recentDays)));
    }

}
