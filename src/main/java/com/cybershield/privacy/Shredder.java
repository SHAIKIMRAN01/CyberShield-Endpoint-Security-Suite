package com.cybershield.privacy;

import com.cybershield.files.FileVaultService;
import com.cybershield.files.Wipe;
import com.cybershield.services.ActivityLog;
import com.cybershield.utils.DisplayFormat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Shreds files: each is renamed, overwritten with random data and deleted, so undelete
 * tools can't bring it back from a hard drive. Folders are shredded file by file and
 * then removed once empty. The same safety rules as File Security apply: no drives, no
 * user folder, no system folders and nothing of CyberShield's own; links and junctions
 * are never followed.
 */
public class Shredder {

    /** What will be shredded, worked out before anything is touched. */
    public record Plan(List<Path> files, List<Path> folders, long bytes, List<String> refused) {

        public boolean isEmpty() {
            return files.isEmpty() && folders.isEmpty();
        }

    }

    public record Result(int shredded, int failed, long bytes, List<String> problems) {
    }

    public interface Progress {

        Progress NONE = new Progress() { };

        default void update(int done, int total) {
        }

        default boolean isCancelled() {
            return false;
        }

    }

    private final FileVaultService rules;

    public Shredder() {
        this(new FileVaultService());
    }

    /** rules decides which places are off limits (File Security's safety rules). */
    public Shredder(FileVaultService rules) {
        this.rules = rules;
    }

    public Plan plan(List<Path> chosen) {

        Set<Path> files = new LinkedHashSet<>();

        List<Path> folders = new ArrayList<>();

        List<String> refused = new ArrayList<>();

        long[] bytes = {0};

        for (Path path : chosen) {

            Path absolute = path.toAbsolutePath().normalize();

            String why = rules.checkAllowed(absolute);

            if (why != null) {
                refused.add(label(absolute) + ": " + why.replace("protected", "shredded"));
                continue;
            }

            BasicFileAttributes attributes;

            try {
                attributes = Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                refused.add(label(absolute) + ": it isn't there any more.");
                continue;
            }

            // A junction or link chosen directly is refused too: shredding it would reach its target.
            switch (FileVaultService.classify(absolute, attributes)) {

                case FILE -> {
                    if (files.add(absolute)) {
                        bytes[0] += attributes.size();
                    }
                }

                case DIRECTORY -> {
                    folders.add(absolute);
                    walk(absolute, files, folders, bytes, refused);
                }

                case LINK -> refused.add(label(absolute) + ": a link to another place. Links aren't followed.");

            }

        }

        return new Plan(List.copyOf(files), List.copyOf(folders), bytes[0], List.copyOf(refused));

    }

    /** A file or folder's name, or the whole path for a drive. */
    private static String label(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static void walk(Path root, Set<Path> files, List<Path> folders, long[] bytes, List<String> refused) {

        try {

            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {

                    // A junction or link to somewhere else: its target is left alone.
                    if (!dir.equals(root) && FileVaultService.classify(dir, attributes) != FileVaultService.PathKind.DIRECTORY) {
                        refused.add(root.relativize(dir) + ": a link to another place, skipped.");
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    if (!dir.equals(root)) {
                        folders.add(dir);
                    }

                    return FileVisitResult.CONTINUE;

                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {

                    if (FileVaultService.classify(file, attributes) == FileVaultService.PathKind.FILE) {
                        if (files.add(file)) {
                            bytes[0] += attributes.size();
                        }
                    } else {
                        refused.add(root.relativize(file) + ": a link, skipped.");
                    }

                    return FileVisitResult.CONTINUE;

                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    refused.add(root.relativize(file) + ": couldn't be read.");
                    return FileVisitResult.CONTINUE;
                }

            });

        } catch (IOException e) {
            refused.add(label(root) + ": couldn't be read.");
        }

    }

    /** Shreds what the plan lists. A file another program has open is left alone and reported. */
    public Result shred(Plan plan, Progress progress) {

        int shredded = 0;

        int failed = 0;

        long bytes = 0;

        List<String> problems = new ArrayList<>(plan.refused());

        int done = 0;

        for (Path file : plan.files()) {

            if (progress.isCancelled()) {
                throw new CancellationException();
            }

            progress.update(done++, plan.files().size());

            try {

                long size = Files.size(file);

                Wipe.shred(file);

                shredded++;

                bytes += size;

            } catch (IOException e) {

                failed++;

                problems.add(file.getFileName() + ": couldn't be shredded. Is it open in another program?");

            }

        }

        progress.update(plan.files().size(), plan.files().size());

        // Folders go deepest first, once empty; any still holding a file stays.
        List<Path> folders = new ArrayList<>(plan.folders());

        folders.sort(Comparator.comparingInt(Path::getNameCount).reversed());

        for (Path folder : folders) {
            try {
                Files.deleteIfExists(folder);
            } catch (IOException notEmpty) {
                // A file in it couldn't be shredded.
            }
        }

        if (shredded > 0) {
            ActivityLog.recordPlain(ActivityLog.Action.FILES_SHREDDED,
                    shredded + (shredded == 1 ? " file, " : " files, ") + DisplayFormat.size(bytes));
        }

        return new Result(shredded, failed, bytes, List.copyOf(problems));

    }

}
