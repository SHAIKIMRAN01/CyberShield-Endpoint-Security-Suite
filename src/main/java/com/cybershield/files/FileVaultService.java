package com.cybershield.files;

import com.cybershield.config.DatabaseConfig;
import com.cybershield.encryption.FileDecryptor;
import com.cybershield.encryption.FileEncryptionService;
import com.cybershield.encryption.FileMetadata;
import com.cybershield.logger.AppLogger;
import com.cybershield.services.ActivityLog;
import com.cybershield.services.EncryptionService;
import com.cybershield.vault.Vault;
import com.cybershield.vault.VaultKeyFile;
import com.cybershield.vault.VaultSession;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hides files and folders by moving them, encrypted with the vault key, into a
 * hidden store under random names, and puts them back on restore.
 *
 * <p>Each file is protected in this order, so it is never lost if something
 * fails or the app is closed half way:
 * <ol>
 *   <li>encrypt it into a temporary file in the store and force that to disk</li>
 *   <li>decrypt the copy again and compare checksums with what was read</li>
 *   <li>give the copy its final random name and record it in the database</li>
 *   <li>rename the original, which fails if another program has it open
 *       (the copy and record are then removed and the file is left alone)</li>
 *   <li>overwrite the original with zeros and delete it</li>
 * </ol>
 */
public class FileVaultService {

    /** Optional system property that moves the hidden store (used by tests). */
    public static final String STORE_DIR_PROPERTY = "cybershield.filevault.dir";

    /** Progress, status text and a stop request for long jobs. */
    public interface JobListener {

        default void progress(long done, long total) {
        }

        default void message(String text) {
        }

        default boolean isCancelled() {
            return false;
        }

    }

    public record ScanFile(Path path, String relativePath, long size) {
    }

    public record ScanItem(Path root, ProtectedItem.Type type, List<ScanFile> files, List<String> directories) {

        public long totalBytes() {
            return files.stream().mapToLong(ScanFile::size).sum();
        }

    }

    /** What a selection contains, what will be skipped, and whether there is room for it. */
    /** @param cloud the files that OneDrive keeps: protecting them takes them out of OneDrive too */
    public record ScanResult(List<ScanItem> items, List<String> skipped, long bytesNeeded, long bytesFree,
                             CloudFiles.Summary cloud) {

        public int fileCount() {
            return items.stream().mapToInt(item -> item.files().size()).sum();
        }

        public long totalBytes() {
            return items.stream().mapToLong(ScanItem::totalBytes).sum();
        }

        public boolean enoughSpace() {
            return bytesFree >= bytesNeeded;
        }

    }

    public record ProtectResult(
            int protectedFiles,
            int items,
            int skippedInUse,
            int skippedChanged,
            List<String> problems,
            boolean stoppedEarly
    ) {
    }

    public record RestoreResult(
            int restoredFiles,
            int restoredItems,
            int renamed,
            List<String> problems,
            boolean stoppedEarly
    ) {
    }

    private enum Kind { FILE, DIRECTORY, LINK }

    private enum Outcome { PROTECTED, IN_USE, CHANGED, GONE, FAILED }

    private static final AtomicBoolean JOB_RUNNING = new AtomicBoolean();

    private static final String TEMP_SUFFIX = ".tmp";

    private static final String WIPE_SUFFIX = ".cs-wipe";

    private static final String RESTORE_SUFFIX = ".cs-restore";

    private static final long SPACE_MARGIN = 64L * 1024 * 1024;

    private static final int WIPE_BUFFER = 1024 * 1024;

    private final Path storeDir;

    private final FileVaultDAO dao = new FileVaultDAO();

    private final EncryptionService encryption = EncryptionService.getInstance();

    private List<Path> systemFolders;

    private List<Path> tempFolders;

    private List<Path> ownFolders;

    public FileVaultService() {
        this(defaultStoreDir());
    }

    public FileVaultService(Path storeDir) {
        this.storeDir = storeDir.toAbsolutePath().normalize();
    }

    /** %LOCALAPPDATA%\CyberShield\FileVault on Windows, ~/.cybershield/filevault elsewhere. */
    public static Path defaultStoreDir() {

        String override = System.getProperty(STORE_DIR_PROPERTY);

        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }

        String localAppData = System.getenv("LOCALAPPDATA");

        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "CyberShield", "FileVault");
        }

        return Path.of(System.getProperty("user.home"), ".cybershield", "filevault");

    }

    public Path storeDir() {
        return storeDir;
    }

    public static boolean isJobRunning() {
        return JOB_RUNNING.get();
    }

    // =====================================================
    // SAFETY
    // =====================================================

    /** Returns why this path can't be protected, or null if it can. */
    public String checkAllowed(Path path) {

        Path target = canonical(path);

        if (target.getParent() == null) {
            return "Whole drives can't be protected. Pick folders or files on the drive instead.";
        }

        for (Path part : target) {

            String name = part.toString();

            if (name.equalsIgnoreCase("$Recycle.Bin") || name.equalsIgnoreCase("System Volume Information")) {
                return "System folders can't be protected.";
            }

        }

        Path home = canonical(Path.of(System.getProperty("user.home")));

        if (home.startsWith(target)) {
            return "Your whole user folder can't be protected. Pick folders inside it instead.";
        }

        for (Path own : ownFolders()) {

            if (target.startsWith(own) || own.startsWith(target)) {
                return "This location holds CyberShield's own files.";
            }

        }

        for (Path system : systemFolders()) {

            if (target.startsWith(system) && tempFolders().stream().noneMatch(target::startsWith)) {
                return "System, program and app-data folders can't be protected.";
            }

        }

        return null;

    }

    private List<Path> systemFolders() {

        if (systemFolders == null) {

            List<Path> folders = new ArrayList<>();

            for (String variable : List.of(
                    "SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramW6432",
                    "ProgramData", "APPDATA", "LOCALAPPDATA")) {

                String value = System.getenv(variable);

                if (value != null && !value.isBlank()) {
                    folders.add(canonical(Path.of(value)));
                }

            }

            String localAppData = System.getenv("LOCALAPPDATA");

            if (localAppData != null && Path.of(localAppData).getParent() != null) {
                // The whole AppData folder, including LocalLow.
                folders.add(canonical(Path.of(localAppData).getParent()));
            }

            folders.add(canonical(Path.of(System.getProperty("java.home"))));

            systemFolders = List.copyOf(folders);

        }

        return systemFolders;

    }

    /** Temp folders sit inside AppData but hold ordinary user files, so they are allowed. */
    private List<Path> tempFolders() {

        if (tempFolders == null) {

            List<Path> folders = new ArrayList<>();

            folders.add(canonical(Path.of(System.getProperty("java.io.tmpdir"))));

            String localAppData = System.getenv("LOCALAPPDATA");

            if (localAppData != null && !localAppData.isBlank()) {
                folders.add(canonical(Path.of(localAppData, "Temp")));
            }

            tempFolders = List.copyOf(folders);

        }

        return tempFolders;

    }

    /** The hidden store, the database folder and the app's own code. */
    private List<Path> ownFolders() {

        if (ownFolders == null) {

            List<Path> folders = new ArrayList<>();

            folders.add(canonical(storeDir));

            Path databaseFolder = DatabaseConfig.databasePath().getParent();

            if (databaseFolder != null) {
                folders.add(canonical(databaseFolder));
            }

            try {

                folders.add(canonical(Path.of(
                        FileVaultService.class.getProtectionDomain().getCodeSource().getLocation().toURI())));

            } catch (Exception e) {
                // Unknown code location, nothing extra to protect.
            }

            ownFolders = List.copyOf(folders);

        }

        return ownFolders;

    }

    /**
     * Absolute path with real, long names (no 8.3 short names or "..") so that
     * comparisons are reliable. Works for paths that don't exist yet too.
     */
    static Path canonical(Path path) {

        Path absolute = path.toAbsolutePath().normalize();

        Path existing = absolute;

        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }

        if (existing == null) {
            return absolute;
        }

        try {
            return existing.toRealPath().resolve(existing.relativize(absolute));
        } catch (IOException | SecurityException e) {
            return absolute;
        }

    }

    // =====================================================
    // SCANNING
    // =====================================================

    /** Walks the selection without changing anything. */
    public ScanResult scan(List<Path> selection, JobListener listener) throws IOException {

        List<ScanItem> items = new ArrayList<>();

        List<String> skipped = new ArrayList<>();

        int[] found = {0};

        for (Path selected : selection) {

            checkCancelled(listener);

            Path root = selected.toAbsolutePath().normalize();

            String label = displayName(root);

            String denied = checkAllowed(root);

            if (denied != null) {
                skipped.add(label + ": " + denied);
                continue;
            }

            BasicFileAttributes attributes;

            try {
                attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                skipped.add(label + ": it no longer exists.");
                continue;
            }

            switch (classify(root, attributes)) {

                case FILE -> {
                    items.add(new ScanItem(
                            root,
                            ProtectedItem.Type.FILE,
                            List.of(new ScanFile(root, label, attributes.size())),
                            List.of()));
                    found[0]++;
                }

                case DIRECTORY -> {
                    ScanItem folder = scanFolder(root, skipped, found, listener);
                    if (folder != null) {
                        items.add(folder);
                    }
                }

                case LINK -> skipped.add(label + ": links to other places are skipped.");

            }

            listener.message("Scanning… " + found[0] + " files found");

        }

        Path store = prepareStore();

        long free = Files.getFileStore(store).getUsableSpace();

        // Files on the store's drive are moved one at a time, so only the biggest
        // needs room at once. Files on other drives all end up on the store's drive.
        long needed = SPACE_MARGIN;

        long largestOnStoreDrive = 0;

        for (ScanItem item : items) {

            if (sameDrive(item.root(), store)) {

                for (ScanFile file : item.files()) {
                    largestOnStoreDrive = Math.max(largestOnStoreDrive, file.size());
                }

            } else {

                needed += item.totalBytes();

            }

        }

        return new ScanResult(List.copyOf(items), List.copyOf(skipped), needed + largestOnStoreDrive, free,
                CloudFiles.summarize(items));

    }

    private ScanItem scanFolder(Path root, List<String> skipped, int[] found, JobListener listener)
            throws IOException {

        List<ScanFile> files = new ArrayList<>();

        List<String> directories = new ArrayList<>();

        String rootLabel = displayName(root);

        boolean[] rootUnreadable = {false};

        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {

                checkCancelled(listener);

                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }

                String relative = root.relativize(dir).toString();

                // Never follow junctions or links into other places, such as system folders.
                if (classify(dir, attributes) != Kind.DIRECTORY) {
                    skipped.add(rootLabel + "/" + relative + ": links to other places are skipped.");
                    return FileVisitResult.SKIP_SUBTREE;
                }

                directories.add(relative);

                return FileVisitResult.CONTINUE;

            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {

                String relative = root.relativize(file).toString();

                if (classify(file, attributes) == Kind.FILE) {

                    files.add(new ScanFile(file, relative, attributes.size()));

                    if (++found[0] % 200 == 0) {
                        listener.message("Scanning… " + found[0] + " files found");
                    }

                } else {

                    skipped.add(rootLabel + "/" + relative + ": links and special files are skipped.");

                }

                return FileVisitResult.CONTINUE;

            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {

                if (file.equals(root)) {
                    rootUnreadable[0] = true;
                    skipped.add(rootLabel + ": it can't be read (" + reason(e) + ").");
                } else {
                    skipped.add(rootLabel + "/" + root.relativize(file) + ": it can't be read (" + reason(e) + ").");
                }

                return FileVisitResult.CONTINUE;

            }

        });

        if (rootUnreadable[0]) {
            return null;
        }

        return new ScanItem(root, ProtectedItem.Type.FOLDER, List.copyOf(files), List.copyOf(directories));

    }

    private static Kind classify(Path path, BasicFileAttributes attributes) {

        if (attributes.isSymbolicLink()) {
            return Kind.LINK;
        }

        if (attributes.isDirectory()) {
            // A directory that is also a reparse point is a junction unless it resolves to itself.
            return !attributes.isOther() || resolvesToItself(path) ? Kind.DIRECTORY : Kind.LINK;
        }

        if (attributes.isRegularFile()) {
            return Kind.FILE;
        }

        // Reparse points that aren't links, such as cloud placeholder files, read as normal files.
        return attributes.isOther() && resolvesToItself(path) ? Kind.FILE : Kind.LINK;

    }

    /** False for junctions and similar links, whose real path is somewhere else. */
    private static boolean resolvesToItself(Path path) {

        try {

            Path parent = path.getParent();

            if (parent == null) {
                return true;
            }

            return path.toRealPath().equals(parent.toRealPath().resolve(path.getFileName()));

        } catch (IOException e) {

            return false;

        }

    }

    private static boolean sameDrive(Path a, Path b) {

        Path rootA = canonical(a).getRoot();

        return rootA != null && rootA.equals(canonical(b).getRoot());

    }

    // =====================================================
    // PROTECTING
    // =====================================================

    /** Encrypts and hides everything in the scan, into the open vault. The vault must be unlocked. */
    public ProtectResult protect(ScanResult scan, JobListener listener) {

        if (!JOB_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("Another file job is still running.");
        }

        int protectedFiles = 0;
        int items = 0;
        int inUse = 0;
        int changed = 0;
        int attempted = 0;
        boolean stopped = false;
        List<String> problems = new ArrayList<>();

        try {

            Vault vault = VaultSession.current();

            Path store = prepareStore();

            byte[] salt = vault.vaultSalt();

            long total = Math.max(1, scan.totalBytes() * 2); // read once to encrypt, once to check

            long[] done = {0};

            for (ScanItem item : scan.items()) {

                if (listener.isCancelled()) {
                    stopped = true;
                    break;
                }

                String itemId = UUID.randomUUID().toString();

                ProtectedItem.Info info = new ProtectedItem.Info(
                        item.type(), item.root().toString(), item.files().size(), item.totalBytes(), item.directories());

                dao.insertItem(itemId, vault.userId(), vault.id(), encryptInfo(info));

                int itemFiles = 0;

                long itemBytes = 0;

                for (ScanFile file : item.files()) {

                    if (listener.isCancelled()) {
                        stopped = true;
                        break;
                    }

                    attempted++;

                    listener.message("Encrypting " + attempted + " of " + scan.fileCount() + ": " + file.relativePath());

                    long before = done[0];

                    Outcome outcome;

                    try {
                        outcome = protectFile(store, salt, itemId, file, listener, done, total, problems);
                    } catch (CancellationException e) {
                        stopped = true;
                        break;
                    }

                    done[0] = before + 2 * file.size();

                    listener.progress(done[0], total);

                    switch (outcome) {
                        case PROTECTED -> {
                            protectedFiles++;
                            itemFiles++;
                            itemBytes += file.size();
                        }
                        case IN_USE -> inUse++;
                        case CHANGED -> changed++;
                        case GONE, FAILED -> { }
                    }

                }

                if (item.type() == ProtectedItem.Type.FOLDER) {
                    removeEmptyFolders(item, problems);
                }

                if (itemFiles == 0 && !item.files().isEmpty()) {

                    dao.deleteItem(itemId);

                } else {

                    items++;

                    if (itemFiles != item.files().size()) {
                        dao.updateItemInfo(itemId, encryptInfo(info.withTotals(itemFiles, itemBytes)));
                    }

                }

                if (stopped) {
                    break;
                }

            }

        } catch (CancellationException e) {

            stopped = true;

        } catch (IllegalStateException e) {

            problems.add("Stopped because the vault was locked.");
            stopped = true;

        } catch (IOException | SQLException e) {

            problems.add("Stopped: " + reason(e));
            stopped = true;

        } finally {

            JOB_RUNNING.set(false);

        }

        AppLogger.info("File vault: protected " + protectedFiles + " file(s) in " + items + " item(s).");

        if (protectedFiles > 0) {
            ActivityLog.record(ActivityLog.Action.FILES_PROTECTED, count(protectedFiles, "file") + " from "
                    + names(scan.items().stream().map(item -> String.valueOf(item.root().getFileName())).toList()));
        }

        return new ProtectResult(protectedFiles, items, inUse, changed, List.copyOf(problems), stopped);

    }

    private Outcome protectFile(
            Path store,
            byte[] salt,
            String itemId,
            ScanFile file,
            JobListener listener,
            long[] done,
            long total,
            List<String> problems
    ) throws SQLException {

        Path source = file.path();

        BasicFileAttributes before;

        try {
            before = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            problems.add(file.relativePath() + ": it is no longer there.");
            return Outcome.GONE;
        }

        FileMetadata metadata = new FileMetadata(
                source.toString(),
                itemId,
                file.relativePath(),
                before.size(),
                before.lastModifiedTime().toMillis(),
                before.creationTime().toMillis(),
                readDosAttributes(source));

        String fileId = UUID.randomUUID().toString().replace("-", "");

        Path temp = store.resolve(fileId + TEMP_SUFFIX);

        Path stored = store.resolve(fileId);

        FileEncryptionService.Progress progress = new FileEncryptionService.Progress() {

            @Override
            public void bytesDone(long bytes) {
                done[0] += bytes;
                listener.progress(done[0], total);
            }

            @Override
            public boolean isCancelled() {
                return listener.isCancelled();
            }

        };

        // 1-3: encrypt, check by decrypting, then give the copy its final name.
        try {

            byte[] written = FileEncryptionService.encrypt(source, temp, metadata, salt, encryption, progress);

            byte[] check = FileDecryptor.decrypt(temp, OutputStream.nullOutputStream(), encryption, progress);

            if (!MessageDigest.isEqual(written, check)) {
                throw new IOException("the encrypted copy didn't match the original");
            }

            Files.move(temp, stored, StandardCopyOption.ATOMIC_MOVE);

        } catch (IllegalStateException e) {

            // A stop request (CancellationException is a subclass) or a locked vault: tidy up and pass it on.
            deleteQuietly(temp);
            throw e;

        } catch (IOException | RuntimeException e) {

            deleteQuietly(temp);
            problems.add(file.relativePath() + ": couldn't be encrypted (" + reason(e) + ").");
            return Outcome.FAILED;

        }

        try {
            dao.insertFile(fileId, itemId);
        } catch (SQLException e) {
            deleteQuietly(stored);
            throw e;
        }

        // 4: take the original away. Renaming fails if another program has it open.
        Path parked = source.resolveSibling(source.getFileName() + "." + fileId.substring(0, 8) + WIPE_SUFFIX);

        try {
            Files.move(source, parked);
        } catch (IOException e) {
            undoStored(fileId, stored, problems);
            return Outcome.IN_USE;
        }

        if (changedSince(parked, before)) {

            try {
                Files.move(parked, source);
            } catch (IOException e) {
                problems.add(file.relativePath() + ": changed while it was being encrypted, and is now named "
                        + parked.getFileName() + ".");
            }

            undoStored(fileId, stored, problems);

            return Outcome.CHANGED;

        }

        // 5: overwrite the original with zeros and delete it.
        try {
            wipeAndDelete(parked);
        } catch (IOException e) {
            problems.add(file.relativePath() + ": is encrypted, but the original couldn't be fully erased ("
                    + reason(e) + "). It is now named " + parked.getFileName() + ".");
        }

        return Outcome.PROTECTED;

    }

    private static boolean changedSince(Path file, BasicFileAttributes before) {

        try {

            BasicFileAttributes now = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            return now.size() != before.size() || !now.lastModifiedTime().equals(before.lastModifiedTime());

        } catch (IOException e) {

            return true;

        }

    }

    private void undoStored(String fileId, Path stored, List<String> problems) {

        try {
            dao.deleteFile(fileId);
        } catch (SQLException e) {
            problems.add("A database record couldn't be removed: " + e.getMessage());
        }

        deleteQuietly(stored);

    }

    private static void wipeAndDelete(Path file) throws IOException {

        try {
            Files.setAttribute(file, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            // Not Windows, or already writable.
        }

        long size = Files.size(file);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {

            ByteBuffer zeros = ByteBuffer.allocate((int) Math.min(WIPE_BUFFER, Math.max(1, size)));

            long position = 0;

            while (position < size) {

                zeros.clear();

                zeros.limit((int) Math.min(zeros.capacity(), size - position));

                position += channel.write(zeros, position);

            }

            channel.force(true);

        }

        Files.delete(file);

    }

    /** Deletes the folders that are now empty, deepest first. Folders that still hold skipped files stay. */
    private static void removeEmptyFolders(ScanItem item, List<String> problems) {

        List<Path> folders = new ArrayList<>();

        for (String relative : item.directories()) {
            folders.add(item.root().resolve(relative));
        }

        folders.sort(Comparator.comparingInt(Path::getNameCount).reversed());

        folders.add(item.root());

        int left = 0;

        for (Path folder : folders) {

            if (!deleteEmptyFolder(folder)) {
                left++;
            }

        }

        if (left > 0) {
            problems.add(left + " emptied folder(s) in " + displayName(item.root())
                    + " couldn't be removed. They may be open in another window.");
        }

    }

    /** True if the folder is gone, or still holds files and so should stay. */
    private static boolean deleteEmptyFolder(Path folder) {

        for (int attempt = 0; attempt < 2; attempt++) {

            try {

                Files.deleteIfExists(folder);

                return true;

            } catch (DirectoryNotEmptyException e) {

                return true;

            } catch (IOException e) {

                // Read-only folders can't be deleted on Windows; clear the flag and try once more.
                try {
                    Files.setAttribute(folder, "dos:readonly", false);
                } catch (Exception ignored) {
                    return false;
                }

            }

        }

        return false;

    }

    // =====================================================
    // LISTING AND RESTORING
    // =====================================================

    /** The open vault's items, newest first. Items that can't be read with its key are left out. */
    public List<ProtectedItem> listItems() throws SQLException {

        List<ProtectedItem> items = new ArrayList<>();

        int unreadable = 0;

        for (FileVaultDAO.ItemRow row : dao.items(VaultSession.current().id())) {

            try {

                items.add(new ProtectedItem(row.id(), decryptInfo(row.info()), row.fileCount(), row.createdAt()));

            } catch (IllegalStateException locked) {

                throw locked;

            } catch (IOException | RuntimeException e) {

                unreadable++;

            }

        }

        if (unreadable > 0) {
            AppLogger.error(unreadable + " protected item(s) can't be read with the current vault key.");
        }

        return items;

    }

    /** Running totals for a restore job. */
    private static final class RestoreCounts {

        int files;
        int items;
        int renamed;
        boolean stopped;
        final List<String> problems = new ArrayList<>();

        RestoreResult result() {
            return new RestoreResult(files, items, renamed, List.copyOf(problems), stopped);
        }

    }

    /** Puts every file of the given items back where it was. The vault must be unlocked. */
    public RestoreResult restore(List<ProtectedItem> items, JobListener listener) {

        if (!JOB_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("Another file job is still running.");
        }

        RestoreCounts counts = new RestoreCounts();

        try {

            long total = Math.max(1, items.stream().mapToLong(item -> item.info().totalBytes()).sum());

            FileEncryptionService.Progress progress = progressReporter(listener, total);

            for (ProtectedItem item : items) {

                if (listener.isCancelled()) {
                    counts.stopped = true;
                    break;
                }

                int failures = recreateFolders(item, counts.problems) ? 0 : 1;

                for (String fileId : dao.fileIds(item.id())) {

                    if (listener.isCancelled()) {
                        counts.stopped = true;
                        break;
                    }

                    if (!restoreStoredFile(item, fileId, progress, listener, counts)) {
                        failures++;
                    }

                }

                if (counts.stopped) {
                    break;
                }

                if (failures == 0) {
                    dao.deleteItem(item.id());
                    counts.items++;
                }

            }

        } catch (CancellationException e) {

            counts.stopped = true;

        } catch (IllegalStateException e) {

            counts.problems.add("Stopped because the vault was locked.");
            counts.stopped = true;

        } catch (SQLException e) {

            counts.problems.add("Stopped: " + reason(e));
            counts.stopped = true;

        } finally {

            JOB_RUNNING.set(false);

        }

        AppLogger.info("File vault: restored " + counts.files + " file(s).");

        if (counts.files > 0) {
            ActivityLog.record(ActivityLog.Action.FILES_RESTORED, count(counts.files, "file") + " from "
                    + names(items.stream().map(ProtectedItem::name).toList()));
        }

        return counts.result();

    }

    /**
     * Puts some of an item's files back where they were. When its last file is
     * restored, the item's folders are recreated and the item is removed.
     */
    public RestoreResult restoreFiles(ProtectedItem item, List<VaultFile> files, JobListener listener) {

        if (!JOB_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("Another file job is still running.");
        }

        RestoreCounts counts = new RestoreCounts();

        try {

            long total = Math.max(1, files.stream().mapToLong(VaultFile::size).sum());

            FileEncryptionService.Progress progress = progressReporter(listener, total);

            long restoredBytes = 0;

            for (VaultFile file : files) {

                if (listener.isCancelled()) {
                    counts.stopped = true;
                    break;
                }

                if (restoreStoredFile(item, file.id(), progress, listener, counts)) {
                    restoredBytes += file.size();
                }

            }

            List<String> left = dao.fileIds(item.id());

            if (left.isEmpty()) {

                if (recreateFolders(item, counts.problems)) {
                    dao.deleteItem(item.id());
                    counts.items++;
                }

            } else if (restoredBytes > 0) {

                dao.updateItemInfo(item.id(), encryptInfo(item.info().withTotals(
                        left.size(), Math.max(0, item.info().totalBytes() - restoredBytes))));

            }

        } catch (CancellationException e) {

            counts.stopped = true;

        } catch (IllegalStateException e) {

            counts.problems.add("Stopped because the vault was locked.");
            counts.stopped = true;

        } catch (SQLException e) {

            counts.problems.add("Stopped: " + reason(e));
            counts.stopped = true;

        } finally {

            JOB_RUNNING.set(false);

        }

        AppLogger.info("File vault: restored " + counts.files + " file(s).");

        if (counts.files > 0) {
            ActivityLog.record(ActivityLog.Action.FILES_RESTORED, item.name() + ": "
                    + names(files.stream().map(VaultFile::relativePath).toList()));
        }

        return counts.result();

    }

    /** Recreates a folder item's folders, including empty ones. False if that failed. */
    private static boolean recreateFolders(ProtectedItem item, List<String> problems) {

        if (item.type() != ProtectedItem.Type.FOLDER) {
            return true;
        }

        Path root = Path.of(item.info().originalPath());

        try {

            Files.createDirectories(root);

            for (String relative : item.info().directories()) {
                Files.createDirectories(root.resolve(relative));
            }

            return true;

        } catch (IOException e) {

            problems.add(item.name() + ": its folders couldn't be recreated (" + reason(e) + ").");

            return false;

        }

    }

    /**
     * Decrypts one stored file back to where it came from, then removes the
     * encrypted copy. Returns false (with a problem noted) if it couldn't.
     * A stop request or a locked vault is passed on to the caller.
     */
    private boolean restoreStoredFile(
            ProtectedItem item,
            String fileId,
            FileEncryptionService.Progress progress,
            JobListener listener,
            RestoreCounts counts
    ) throws SQLException {

        Path stored = storeDir.resolve(fileId);

        if (!Files.exists(stored)) {
            counts.problems.add(item.name() + ": one of its encrypted files is missing from the store.");
            return false;
        }

        try {

            FileMetadata metadata = FileDecryptor.readMetadata(stored, encryption);

            listener.message("Restoring " + (counts.files + 1) + ": " + metadata.relativePath());

            Path target = Path.of(metadata.originalPath());

            Files.createDirectories(target.getParent());

            boolean nameTaken = Files.exists(target, LinkOption.NOFOLLOW_LINKS);

            if (nameTaken) {
                target = freeName(target);
            }

            Path temp = target.resolveSibling(target.getFileName() + "." + fileId.substring(0, 8) + RESTORE_SUFFIX);

            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {

                FileDecryptor.decrypt(stored, out, encryption, progress);

            } catch (IOException | RuntimeException e) {

                deleteQuietly(temp);
                throw e;

            }

            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);

            applyFileDetails(target, metadata);

            Files.delete(stored);

            dao.deleteFile(fileId);

            counts.files++;

            if (nameTaken) {
                counts.renamed++;
            }

            return true;

        } catch (IOException e) {

            counts.problems.add(item.name() + ": a file couldn't be restored (" + reason(e) + ").");

            return false;

        }

    }

    private static FileEncryptionService.Progress progressReporter(JobListener listener, long total) {

        long[] done = {0};

        return new FileEncryptionService.Progress() {

            @Override
            public void bytesDone(long bytes) {
                done[0] += bytes;
                listener.progress(done[0], total);
            }

            @Override
            public boolean isCancelled() {
                return listener.isCancelled();
            }

        };

    }

    // =====================================================
    // VIEWING
    // =====================================================

    /** A protected file, as described inside its encrypted copy. */
    public record VaultFile(
            String id,
            String itemId,
            String relativePath,
            String originalPath,
            long size,
            long lastModifiedMillis
    ) {

        public String name() {
            Path name = Path.of(relativePath).getFileName();
            return name == null ? relativePath : name.toString();
        }

        /** The lower-case extension without the dot, or "" if there is none. */
        public String extension() {
            String name = name();
            int dot = name.lastIndexOf('.');
            return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        }

    }

    /**
     * The files inside a protected item, read from the encrypted details in each
     * stored file and sorted by path. Missing or damaged files are left out.
     */
    public List<VaultFile> listFiles(ProtectedItem item) throws SQLException {

        List<VaultFile> files = new ArrayList<>();

        for (String fileId : dao.fileIds(item.id())) {

            try {

                FileMetadata metadata = FileDecryptor.readMetadata(storeDir.resolve(fileId), encryption);

                files.add(new VaultFile(fileId, item.id(), metadata.relativePath(), metadata.originalPath(),
                        metadata.size(), metadata.lastModifiedMillis()));

            } catch (IllegalStateException locked) {

                throw locked;

            } catch (IOException | RuntimeException e) {

                AppLogger.error("A protected file couldn't be read: " + reason(e));

            }

        }

        files.sort(Comparator.comparing(file -> file.relativePath().toLowerCase(Locale.ROOT)));

        return files;

    }

    /**
     * Decrypts a file into memory for viewing. Nothing is written to disk.
     *
     * @throws FileDecryptor.TooLargeException if it is bigger than maxBytes
     */
    public byte[] readFile(VaultFile file, long maxBytes) throws IOException {

        return FileDecryptor.decryptToBytes(storeDir.resolve(file.id()), encryption, maxBytes);

    }

    /**
     * Decrypts a file into memory outside the Java heap, for media playback.
     * Nothing is written to disk.
     *
     * @throws FileDecryptor.TooLargeException if it is bigger than maxBytes or memory runs short
     */
    public ByteBuffer readFileForPlayback(VaultFile file, long maxBytes) throws IOException {

        return FileDecryptor.decryptToDirectBuffer(storeDir.resolve(file.id()), encryption, maxBytes);

    }

    /**
     * Saves a decrypted copy of one file at target, replacing anything there.
     * The file stays protected in the vault.
     */
    public void exportCopy(VaultFile file, Path target) throws IOException {

        Path temp = target.resolveSibling(target.getFileName() + "." + file.id().substring(0, 8) + RESTORE_SUFFIX);

        try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {

            FileDecryptor.decrypt(storeDir.resolve(file.id()), out, encryption, FileEncryptionService.Progress.NONE);

        } catch (IOException | RuntimeException e) {

            deleteQuietly(temp);
            throw e;

        }

        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        try {
            Files.setLastModifiedTime(target, FileTime.fromMillis(file.lastModifiedMillis()));
        } catch (IOException e) {
            // The copy is there; only its date differs.
        }

        ActivityLog.record(ActivityLog.Action.FILE_EXPORTED, file.originalPath() + " to " + target);

    }

    /** "a, b, c and 4 more", for the history. */
    private static String names(List<String> names) {

        int shown = Math.min(5, names.size());

        String list = String.join(", ", names.subList(0, shown));

        return names.size() > shown ? list + " and " + (names.size() - shown) + " more" : list;

    }

    private static String count(int n, String noun) {

        return n + " " + noun + (n == 1 ? "" : "s");

    }

    /** "report.pdf" becomes "report (restored).pdf", then "report (restored 2).pdf", and so on. */
    private static Path freeName(Path target) {

        String name = target.getFileName().toString();

        int dot = name.lastIndexOf('.');

        String base = dot > 0 ? name.substring(0, dot) : name;

        String extension = dot > 0 ? name.substring(dot) : "";

        for (int n = 1; ; n++) {

            Path candidate = target.resolveSibling(
                    base + (n == 1 ? " (restored)" : " (restored " + n + ")") + extension);

            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }

        }

    }

    private static int readDosAttributes(Path path) {

        try {

            DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

            return (dos.isReadOnly() ? FileMetadata.READ_ONLY : 0)
                    | (dos.isHidden() ? FileMetadata.HIDDEN : 0)
                    | (dos.isSystem() ? FileMetadata.SYSTEM : 0)
                    | (dos.isArchive() ? FileMetadata.ARCHIVE : 0);

        } catch (UnsupportedOperationException | IOException e) {

            return 0;

        }

    }

    /** Puts back the original times and Windows attributes. */
    private static void applyFileDetails(Path target, FileMetadata metadata) {

        try {

            Files.getFileAttributeView(target, BasicFileAttributeView.class).setTimes(
                    FileTime.fromMillis(metadata.lastModifiedMillis()),
                    null,
                    FileTime.fromMillis(metadata.createdMillis()));

        } catch (IOException e) {
            // The file is back; only its dates differ.
        }

        DosFileAttributeView dos = Files.getFileAttributeView(target, DosFileAttributeView.class);

        if (dos == null) {
            return;
        }

        int flags = metadata.dosAttributes();

        try {

            dos.setArchive((flags & FileMetadata.ARCHIVE) != 0);
            dos.setHidden((flags & FileMetadata.HIDDEN) != 0);
            dos.setSystem((flags & FileMetadata.SYSTEM) != 0);
            dos.setReadOnly((flags & FileMetadata.READ_ONLY) != 0);

        } catch (IOException e) {
            // The file is back; only its attributes differ.
        }

    }

    // =====================================================
    // TOOLS: CHECK, RECOVER, BACK UP, RESTORE A BACKUP
    // =====================================================

    /** @param damaged names of files that failed decryption; missing ones the database lists but the store lacks */
    public record CheckResult(int checked, List<String> damaged, List<String> missing, int strays) {

        public boolean allGood() {
            return damaged.isEmpty() && missing.isEmpty();
        }

    }

    public record RecoveryResult(int found, int itemsAdded, int filesAdded) {
    }

    public record BackupResult(Path folder, int files) {
    }

    public record BackupRestoreResult(int itemsAdded, int filesCopied, int alreadyThere) {
    }

    private static final String MANIFEST = "manifest.csb";

    private static final java.util.regex.Pattern STORE_NAME = java.util.regex.Pattern.compile("[0-9a-f]{32}");

    /** Decrypts every file of the open vault to check it, without writing anything. */
    public CheckResult checkFiles(JobListener listener) throws SQLException {

        if (!JOB_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("Another file job is still running.");
        }

        try {

            List<ProtectedItem> items = listItems();

            List<String> damaged = new ArrayList<>();

            List<String> missing = new ArrayList<>();

            int total = items.stream().mapToInt(ProtectedItem::storedFiles).sum();

            int checked = 0;

            for (ProtectedItem item : items) {

                for (String fileId : dao.fileIds(item.id())) {

                    if (listener.isCancelled()) {
                        throw new CancellationException();
                    }

                    checked++;

                    listener.message("Checking " + checked + " of " + total + " files…");

                    listener.progress(checked, Math.max(1, total));

                    Path stored = storeDir.resolve(fileId);

                    if (!Files.exists(stored)) {
                        missing.add(item.name() + " (" + fileId.substring(0, 8) + ")");
                        continue;
                    }

                    try {

                        FileDecryptor.decrypt(stored, OutputStream.nullOutputStream(), encryption, FileEncryptionService.Progress.NONE);

                    } catch (IOException | RuntimeException e) {

                        if (e instanceof IllegalStateException locked) {
                            throw locked;
                        }

                        damaged.add(item.name() + " (" + fileId.substring(0, 8) + ")");

                    }

                }

            }

            int strays = strays(dao.allFileIds()).size();

            ActivityLog.recordPlain(ActivityLog.Action.VAULT_CHECKED, checked + " files: " + damaged.size() + " damaged, "
                    + missing.size() + " missing");

            return new CheckResult(checked, List.copyOf(damaged), List.copyOf(missing), strays);

        } finally {

            JOB_RUNNING.set(false);

        }

    }

    /** Store files that no account lists. */
    private List<Path> strays(java.util.Set<String> known) {

        List<Path> found = new ArrayList<>();

        if (!Files.isDirectory(storeDir)) {
            return found;
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(storeDir)) {

            for (Path file : files) {

                String name = file.getFileName().toString();

                if (Files.isRegularFile(file) && STORE_NAME.matcher(name).matches() && !known.contains(name)) {
                    found.add(file);
                }

            }

        } catch (IOException e) {

            AppLogger.error("Couldn't list the file store: " + e.getMessage());

        }

        return found;

    }

    /**
     * Rebuilds missing list entries from the store: every stray file the open vault's key
     * opens is put back under its item, read from the file's own encrypted details. Empty
     * sub-folders of a recovered folder can't be known and don't come back.
     */
    public RecoveryResult recoverMissingItems(JobListener listener) throws SQLException {

        if (!JOB_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("Another file job is still running.");
        }

        try {

            Vault vault = VaultSession.current();

            List<Path> strays = strays(dao.allFileIds());

            java.util.Map<String, List<Object[]>> byItem = new java.util.LinkedHashMap<>();

            int scanned = 0;

            for (Path file : strays) {

                if (listener.isCancelled()) {
                    throw new CancellationException();
                }

                listener.message("Looking at " + (++scanned) + " of " + strays.size() + " unlisted files…");

                listener.progress(scanned, Math.max(1, strays.size()));

                try {

                    FileMetadata metadata = FileDecryptor.readMetadata(file, encryption);

                    byItem.computeIfAbsent(metadata.itemId(), id -> new ArrayList<>()).add(new Object[] {file.getFileName().toString(), metadata});

                } catch (IOException | RuntimeException notThisVault) {

                    if (notThisVault instanceof IllegalStateException locked) {
                        throw locked;
                    }

                    // Another account's file, or a damaged one: leave it alone.

                }

            }

            int itemsAdded = 0;

            int filesAdded = 0;

            for (java.util.Map.Entry<String, List<Object[]>> group : byItem.entrySet()) {

                String itemId = group.getKey();

                List<Object[]> files = group.getValue();

                if (!dao.itemExists(itemId)) {
                    dao.insertItem(itemId, vault.userId(), vault.id(), encryptInfo(recoveredInfo(files)));
                    itemsAdded++;
                }

                for (Object[] file : files) {
                    dao.insertFile((String) file[0], itemId);
                    filesAdded++;
                }

            }

            if (filesAdded > 0) {
                ActivityLog.recordPlain(ActivityLog.Action.STORE_RECOVERED, filesAdded + " files in " + byItem.size() + " items");
            }

            return new RecoveryResult(strays.size(), itemsAdded, filesAdded);

        } finally {

            JOB_RUNNING.set(false);

        }

    }

    /** An item's details worked out from its files' details. */
    private static ProtectedItem.Info recoveredInfo(List<Object[]> files) {

        long bytes = 0;

        java.util.TreeSet<String> directories = new java.util.TreeSet<>();

        boolean nested = false;

        FileMetadata first = (FileMetadata) files.get(0)[1];

        for (Object[] file : files) {

            FileMetadata metadata = (FileMetadata) file[1];

            bytes += metadata.size();

            Path relative = Path.of(metadata.relativePath());

            nested |= relative.getNameCount() > 1;

            for (Path parent = relative.getParent(); parent != null; parent = parent.getParent()) {
                directories.add(parent.toString());
            }

        }

        if (files.size() == 1 && !nested) {
            return new ProtectedItem.Info(ProtectedItem.Type.FILE, first.originalPath(), 1, bytes, List.of());
        }

        String original = first.originalPath();

        String relative = first.relativePath();

        String root = original.endsWith(relative) ? original.substring(0, original.length() - relative.length()) : original;

        while (root.endsWith("\\") || root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }

        return new ProtectedItem.Info(ProtectedItem.Type.FOLDER, root, files.size(), bytes, List.copyOf(directories));

    }

    /**
     * Copies the open vault's encrypted files to a new folder inside destination, with an
     * encrypted list of its items and a copy of the vault's key file. Nothing is decrypted:
     * the backup is as private as the store, and the master password opens it.
     */
    public BackupResult backUp(Path destination, JobListener listener) throws IOException, SQLException {

        if (!JOB_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("Another file job is still running.");
        }

        try {

            Vault vault = VaultSession.current();

            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"));

            Path folder = Files.createDirectories(freeFolderName(destination.resolve("CyberShield backup " + stamp)));

            Path filesFolder = Files.createDirectories(folder.resolve("files"));

            StringBuilder manifest = new StringBuilder("CyberShield backup 1\n");

            List<String> fileIds = new ArrayList<>();

            for (FileVaultDAO.ItemRow row : dao.items(vault.id())) {

                manifest.append("item\t").append(row.id()).append('\t').append(row.createdAt()).append('\t').append(row.info()).append('\n');

                for (String fileId : dao.fileIds(row.id())) {
                    manifest.append("file\t").append(row.id()).append('\t').append(fileId).append('\n');
                    fileIds.add(fileId);
                }

            }

            int copied = 0;

            for (String fileId : fileIds) {

                if (listener.isCancelled()) {
                    throw new CancellationException();
                }

                listener.message("Copying " + (copied + 1) + " of " + fileIds.size() + " encrypted files…");

                listener.progress(copied, Math.max(1, fileIds.size()));

                Path stored = storeDir.resolve(fileId);

                if (Files.exists(stored)) {
                    Files.copy(stored, filesFolder.resolve(fileId), StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }

            }

            Files.write(folder.resolve(MANIFEST), encryption.encryptBytes(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            VaultKeyFile.write(folder, vault);

            ActivityLog.record(ActivityLog.Action.STORE_BACKED_UP, copied + " files to " + folder);

            return new BackupResult(folder, copied);

        } finally {

            JOB_RUNNING.set(false);

        }

    }

    /** Brings back the items of a backup that aren't in the list any more. Items still there are left alone. */
    public BackupRestoreResult restoreBackup(Path folder, JobListener listener) throws IOException, SQLException {

        if (!JOB_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("Another file job is still running.");
        }

        try {

            Vault vault = VaultSession.current();

            Path manifestFile = folder.resolve(MANIFEST);

            if (!Files.exists(manifestFile)) {
                throw new IOException("That folder isn't a CyberShield backup: it has no " + MANIFEST + ".");
            }

            String manifest;

            try {
                manifest = new String(encryption.decryptBytes(Files.readAllBytes(manifestFile)), java.nio.charset.StandardCharsets.UTF_8);
            } catch (RuntimeException otherVault) {
                throw new IOException("This backup belongs to another vault: your vault key doesn't open it.");
            }

            prepareStore();

            java.util.Map<String, String[]> items = new java.util.LinkedHashMap<>();

            java.util.Map<String, List<String>> files = new java.util.LinkedHashMap<>();

            for (String line : manifest.split("\n")) {

                String[] parts = line.split("\t");

                if (parts.length == 4 && parts[0].equals("item")) {
                    items.put(parts[1], parts);
                } else if (parts.length == 3 && parts[0].equals("file")) {
                    files.computeIfAbsent(parts[1], id -> new ArrayList<>()).add(parts[2]);
                }

            }

            int itemsAdded = 0;

            int copied = 0;

            int alreadyThere = 0;

            int done = 0;

            for (String[] item : items.values()) {

                if (listener.isCancelled()) {
                    throw new CancellationException();
                }

                listener.message("Restoring item " + (++done) + " of " + items.size() + "…");

                listener.progress(done, Math.max(1, items.size()));

                if (dao.itemExists(item[1])) {
                    alreadyThere++;
                    continue;
                }

                List<String> itemFiles = files.getOrDefault(item[1], List.of());

                for (String fileId : itemFiles) {

                    Path from = folder.resolve("files").resolve(fileId);

                    Path to = storeDir.resolve(fileId);

                    if (Files.exists(from) && !Files.exists(to)) {
                        Files.copy(from, to);
                        copied++;
                    }

                }

                dao.insertItem(item[1], vault.userId(), vault.id(), item[3], item[2]);

                for (String fileId : itemFiles) {
                    if (Files.exists(storeDir.resolve(fileId))) {
                        dao.insertFile(fileId, item[1]);
                    }
                }

                itemsAdded++;

            }

            if (itemsAdded > 0) {
                ActivityLog.record(ActivityLog.Action.STORE_RESTORED, itemsAdded + " items from " + folder);
            }

            return new BackupRestoreResult(itemsAdded, copied, alreadyThere);

        } finally {

            JOB_RUNNING.set(false);

        }

    }

    /** "CyberShield backup 2026-11-22 1405", then "... (2)", and so on. */
    private static Path freeFolderName(Path wanted) {

        if (!Files.exists(wanted)) {
            return wanted;
        }

        for (int n = 2; ; n++) {

            Path candidate = wanted.resolveSibling(wanted.getFileName() + " (" + n + ")");

            if (!Files.exists(candidate)) {
                return candidate;
            }

        }

    }

    // =====================================================
    // HOUSEKEEPING
    // =====================================================

    /** Deletes half-written copies left by a job that was interrupted, e.g. by closing the app. */
    public void removeLeftoverTempFiles() {

        if (JOB_RUNNING.get() || !Files.isDirectory(storeDir)) {
            return;
        }

        try (DirectoryStream<Path> temps = Files.newDirectoryStream(storeDir, "*" + TEMP_SUFFIX)) {

            for (Path temp : temps) {
                deleteQuietly(temp);
            }

        } catch (IOException e) {

            AppLogger.error("Couldn't tidy the file vault: " + e.getMessage());

        }

    }

    private Path prepareStore() throws IOException {

        Files.createDirectories(storeDir);

        try {

            // Hidden + system: Explorer doesn't show it, even with "show hidden files" on.
            Files.setAttribute(storeDir, "dos:hidden", true);
            Files.setAttribute(storeDir, "dos:system", true);

        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            // Not Windows; the folder is still encrypted content under random names.
        }

        // A copy of the vault's wrapped key sits with its files, so the master password can
        // still open them if the database is lost. VaultService keeps it current afterwards.
        if (VaultSession.isUnlocked()) {

            Vault vault = VaultSession.current();

            if (!Files.exists(VaultKeyFile.pathFor(storeDir, vault))) {
                VaultKeyFile.write(storeDir, vault);
            }

        }

        return storeDir;

    }


    private String encryptInfo(ProtectedItem.Info info) {

        return Base64.getEncoder().encodeToString(encryption.encryptBytes(info.toBytes()));

    }

    private ProtectedItem.Info decryptInfo(String stored) throws IOException {

        return ProtectedItem.Info.fromBytes(encryption.decryptBytes(Base64.getDecoder().decode(stored)));

    }

    private static void checkCancelled(JobListener listener) {

        if (listener.isCancelled()) {
            throw new CancellationException();
        }

    }

    private static String displayName(Path path) {

        return path.getFileName() == null ? path.toString() : path.getFileName().toString();

    }

    private static String reason(Exception e) {

        if (e instanceof AccessDeniedException) {
            return "access denied";
        }

        if (e instanceof NoSuchFileException) {
            return "not found";
        }

        if (e instanceof FileSystemException fs && fs.getReason() != null) {
            return fs.getReason();
        }

        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

    }

    private static void deleteQuietly(Path path) {

        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Left for removeLeftoverTempFiles or the user.
        }

    }

}
