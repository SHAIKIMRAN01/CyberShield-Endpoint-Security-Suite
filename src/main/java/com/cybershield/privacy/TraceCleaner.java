package com.cybershield.privacy;

import com.cybershield.services.ActivityLog;
import com.cybershield.utils.DisplayFormat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Finds and removes the traces Windows keeps of what you opened: recent items, jump
 * lists, the thumbnail cache, and temporary files older than a day. It shows what it
 * found first and removes only what is chosen. Files in use are skipped, links and
 * junctions are never followed, and nothing outside these folders is touched.
 */
public class TraceCleaner {

    public enum Kind {

        RECENT_ITEMS("Recent items", "Shortcuts to the files you opened, shown in Quick access and Start."),
        JUMP_LISTS("Jump lists", "Each app's recent files on the taskbar. Items pinned there go too."),
        THUMBNAILS("Thumbnail cache", "Small copies of pictures and videos Explorer has shown. Windows rebuilds it."),
        TEMP_FILES("Temporary files", "Left behind by programs, older than a day.");

        private final String title;

        private final String detail;

        Kind(String title, String detail) {
            this.title = title;
            this.detail = detail;
        }

        public String title() {
            return title;
        }

        public String detail() {
            return detail;
        }

    }

    /** The folders Windows keeps them in. */
    public record Places(Path recent, Path automaticDestinations, Path customDestinations, Path explorer, Path temp) {

        public static Places ofThisPc() {

            String appData = System.getenv("APPDATA");

            String localAppData = System.getenv("LOCALAPPDATA");

            Path recent = appData == null ? null : Path.of(appData, "Microsoft", "Windows", "Recent");

            return new Places(
                    recent,
                    recent == null ? null : recent.resolve("AutomaticDestinations"),
                    recent == null ? null : recent.resolve("CustomDestinations"),
                    localAppData == null ? null : Path.of(localAppData, "Microsoft", "Windows", "Explorer"),
                    Path.of(System.getProperty("java.io.tmpdir")));

        }

    }

    /** One kind of trace: its files, and for temporary files the folders that may end up empty. */
    public record Found(Kind kind, List<Path> files, List<Path> folders, long bytes) {
    }

    public record Result(int removed, int skipped, long bytes) {
    }

    public interface Progress {

        Progress NONE = new Progress() { };

        default void update(int done, int total) {
        }

        default boolean isCancelled() {
            return false;
        }

    }

    private static final Duration TEMP_AGE = Duration.ofDays(1);

    private final Places places;

    public TraceCleaner() {
        this(Places.ofThisPc());
    }

    public TraceCleaner(Places places) {
        this.places = places;
    }

    /** Looks without changing anything. */
    public List<Found> scan() {

        List<Found> found = new ArrayList<>();

        found.add(flat(Kind.RECENT_ITEMS, places.recent(), ".lnk"));

        Found automatic = flat(Kind.JUMP_LISTS, places.automaticDestinations(), ".automaticdestinations-ms");

        Found custom = flat(Kind.JUMP_LISTS, places.customDestinations(), ".customdestinations-ms");

        List<Path> jumpLists = new ArrayList<>(automatic.files());
        jumpLists.addAll(custom.files());

        found.add(new Found(Kind.JUMP_LISTS, List.copyOf(jumpLists), List.of(), automatic.bytes() + custom.bytes()));

        Found thumbnails = flat(Kind.THUMBNAILS, places.explorer(), ".db");

        List<Path> caches = thumbnails.files().stream()
                .filter(file -> file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("thumbcache_"))
                .toList();

        found.add(new Found(Kind.THUMBNAILS, caches, List.of(), size(caches)));

        found.add(temp());

        return found;

    }

    /** The files directly in folder whose names end with ending. */
    private static Found flat(Kind kind, Path folder, String ending) {

        List<Path> files = new ArrayList<>();

        if (folder != null && Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {

            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {

                for (Path entry : entries) {

                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                            && entry.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(ending)) {
                        files.add(entry);
                    }

                }

            } catch (IOException e) {
                // Nothing readable there.
            }

        }

        return new Found(kind, List.copyOf(files), List.of(), size(files));

    }

    private Found temp() {

        Path root = places.temp();

        List<Path> files = new ArrayList<>();

        List<Path> folders = new ArrayList<>();

        if (root == null || !Files.isDirectory(root)) {
            return new Found(Kind.TEMP_FILES, List.of(), List.of(), 0);
        }

        Instant cutoff = Instant.now().minus(TEMP_AGE);

        long[] bytes = {0};

        try {

            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {

                    if (!dir.equals(root) && (attributes.isOther() || attributes.isSymbolicLink())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // Only folders untouched for a day may go once empty: a program may be using a newer one.
                    if (!dir.equals(root) && attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        folders.add(dir);
                    }

                    return FileVisitResult.CONTINUE;

                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {

                    if (attributes.isRegularFile() && attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        files.add(file);
                        bytes[0] += attributes.size();
                    }

                    return FileVisitResult.CONTINUE;

                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

            });

        } catch (IOException e) {
            // What was found so far stands.
        }

        return new Found(Kind.TEMP_FILES, List.copyOf(files), List.copyOf(folders), bytes[0]);

    }

    /** Removes the chosen traces. A file in use stays and counts as skipped. */
    public Result clean(List<Found> chosen, Progress progress) {

        int total = chosen.stream().mapToInt(found -> found.files().size()).sum();

        int done = 0;

        int removed = 0;

        int skipped = 0;

        long bytes = 0;

        for (Found found : chosen) {

            for (Path file : found.files()) {

                if (progress.isCancelled()) {
                    throw new CancellationException();
                }

                progress.update(done++, total);

                try {

                    long size = Files.size(file);

                    if (Files.deleteIfExists(file)) {
                        removed++;
                        bytes += size;
                    }

                } catch (IOException inUse) {

                    skipped++;

                }

            }

            // Folders inside the temp folder that are now empty go too, deepest first.
            List<Path> folders = new ArrayList<>(found.folders());

            folders.sort(Comparator.comparingInt(Path::getNameCount).reversed());

            for (Path folder : folders) {
                try {
                    Files.deleteIfExists(folder);
                } catch (IOException notEmpty) {
                    // Something in it is still there.
                }
            }

        }

        progress.update(total, total);

        if (removed > 0) {
            ActivityLog.recordPlain(ActivityLog.Action.TRACES_CLEANED,
                    removed + (removed == 1 ? " item, " : " items, ") + DisplayFormat.size(bytes));
        }

        return new Result(removed, skipped, bytes);

    }

    private static long size(List<Path> files) {

        long total = 0;

        for (Path file : files) {
            try {
                total += Files.size(file);
            } catch (IOException e) {
                // Gone already.
            }
        }

        return total;

    }

}
