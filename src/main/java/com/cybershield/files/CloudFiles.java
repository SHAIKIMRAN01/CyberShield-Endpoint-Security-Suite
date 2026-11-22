package com.cybershield.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Files kept by OneDrive. Protecting one deletes the original, and OneDrive syncs
 * that deletion: the file leaves OneDrive on every device, and the encrypted copy
 * stays only on this PC. Online-only files are downloaded when they are read.
 * The review step uses this to warn before anything changes.
 */
public final class CloudFiles {

    /** Optional list of OneDrive folders, separated by ';' (used by the tests). */
    public static final String ROOTS_PROPERTY = "cybershield.onedrive.roots";

    // Windows file attribute bits of cloud placeholders whose data isn't on this PC.
    static final int OFFLINE = 0x00001000;

    static final int RECALL_ON_OPEN = 0x00040000;

    static final int RECALL_ON_DATA_ACCESS = 0x00400000;

    /** @param onlineOnlyBytes how much reading the online-only files will download */
    public record Summary(int files, int onlineOnly, long onlineOnlyBytes) {

        public static final Summary NONE = new Summary(0, 0, 0);

        public boolean any() {
            return files > 0;
        }

    }

    private CloudFiles() {
    }

    /** The OneDrive folders of this Windows account (personal and work or school). */
    public static List<Path> oneDriveRoots() {

        List<Path> roots = new ArrayList<>();

        String override = System.getProperty(ROOTS_PROPERTY);

        if (override != null) {

            for (String root : override.split(";")) {
                add(roots, root);
            }

            return roots;

        }

        for (String variable : new String[] {"OneDrive", "OneDriveConsumer", "OneDriveCommercial"}) {
            add(roots, System.getenv(variable));
        }

        return roots;

    }

    /** Counts the scanned files that live in OneDrive, and those that are online-only. */
    public static Summary summarize(List<FileVaultService.ScanItem> items) {

        List<Path> roots = oneDriveRoots();

        if (roots.isEmpty()) {
            return Summary.NONE;
        }

        int files = 0;
        int onlineOnly = 0;
        long onlineBytes = 0;

        for (FileVaultService.ScanItem item : items) {

            for (FileVaultService.ScanFile file : item.files()) {

                Path path = file.path().toAbsolutePath().normalize();

                if (roots.stream().noneMatch(path::startsWith)) {
                    continue;
                }

                files++;

                if (isOnlineOnly(attributes(path))) {
                    onlineOnly++;
                    onlineBytes += file.size();
                }

            }

        }

        return new Summary(files, onlineOnly, onlineBytes);

    }

    /** True for placeholders whose data has to be downloaded first. */
    static boolean isOnlineOnly(int attributes) {

        return (attributes & (OFFLINE | RECALL_ON_OPEN | RECALL_ON_DATA_ACCESS)) != 0;

    }

    /** The raw Windows attributes, or 0 where they can't be read. */
    private static int attributes(Path path) {

        try {

            Object value = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);

            return value instanceof Integer bits ? bits : 0;

        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {

            return 0;

        }

    }

    private static void add(List<Path> roots, String folder) {

        if (folder == null || folder.isBlank()) {
            return;
        }

        Path root = Path.of(folder.trim()).toAbsolutePath().normalize();

        if (!roots.contains(root)) {
            roots.add(root);
        }

    }

}
