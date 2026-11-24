package com.cybershield.backup;

import com.cybershield.backup.BackupScan.Entry;
import com.cybershield.backup.BackupScan.Plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the files in a {@link Plan} into one compressed {@code .zip} archive, keeping each
 * chosen folder under its own name inside it. It reports progress and can be stopped; if it
 * is stopped, the half-written archive is removed so nothing incomplete is left behind. It
 * only reads the files it was given and writes the one archive you asked for — nothing else.
 */
public final class BackupWriter {

    /** An extra text file to drop in at the top of the archive, such as the installed-apps list. */
    public record Extra(String name, byte[] content) {
    }

    /** What was written. */
    public record Result(int files, long bytes, Path archive) {
    }

    /** Lets a caller follow along and stop the writing. */
    public interface Progress {

        Progress NONE = new Progress() { };

        default void update(int done, int total, Path current) {
        }

        default boolean isCancelled() {
            return false;
        }

    }

    /**
     * Writes {@code plan} and {@code extras} into {@code archive}. Returns what was written, or
     * throws {@link CancellationException} (after removing the part-written file) if stopped.
     */
    public Result write(Plan plan, Path archive, List<Extra> extras, Progress progress) throws IOException {

        Path parent = archive.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Set<String> used = new HashSet<>();
        int total = plan.count() + extras.size();
        int done = 0;
        int files = 0;
        long bytes = 0;

        boolean cancelled = false;

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {

            for (Extra extra : extras) {
                if (progress.isCancelled()) {
                    cancelled = true;
                    break;
                }
                zip.putNextEntry(new ZipEntry(unique(used, extra.name())));
                zip.write(extra.content());
                zip.closeEntry();
                files++;
                bytes += extra.content().length;
                progress.update(++done, total, Path.of(extra.name()));
            }

            if (!cancelled) {
                for (Entry entry : plan.entries()) {
                    if (progress.isCancelled()) {
                        cancelled = true;
                        break;
                    }

                    String name = unique(used, entryName(entry));
                    try {
                        zip.putNextEntry(new ZipEntry(name));
                        bytes += Files.copy(entry.path(), zip);
                        zip.closeEntry();
                        files++;
                    } catch (IOException fileProblem) {
                        // a file that was removed or locked since the scan — skip it, keep going
                    }

                    done++;
                    if ((done & 0x3F) == 0 || done == total) {
                        progress.update(done, total, entry.path());
                    }
                }
            }
        }

        if (cancelled) {
            Files.deleteIfExists(archive);
            throw new CancellationException("Backup stopped");
        }

        return new Result(files, bytes, archive);
    }

    private static String entryName(Entry entry) {
        String relative = entry.source().dir().relativize(entry.path()).toString().replace('\\', '/');
        return entry.source().label() + "/" + relative;
    }

    /** Keeps zip entry names unique, so two files that would land on the same name don't clash. */
    private static String unique(Set<String> used, String name) {

        if (used.add(name)) {
            return name;
        }

        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        boolean hasExt = dot > slash + 1;

        String base = hasExt ? name.substring(0, dot) : name;
        String ext = hasExt ? name.substring(dot) : "";

        int n = 2;
        String candidate;
        do {
            candidate = base + " (" + n++ + ")" + ext;
        } while (!used.add(candidate));

        return candidate;
    }

}
