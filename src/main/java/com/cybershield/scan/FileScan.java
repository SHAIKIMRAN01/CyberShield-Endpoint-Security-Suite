package com.cybershield.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Looks through the folders where risky files tend to collect — Downloads, the
 * desktop, temporary folders and the start-up folder — and points out the ones
 * worth a second look: a program dressed up as a document, a hidden program, an
 * installer left in a temp folder, a loose script, or something set to run at
 * sign-in.
 *
 * <p>It only reads and reports. Nothing is opened, moved, changed or deleted; the
 * walk is bounded by depth and a file cap so it always finishes, and it can be
 * cancelled at any time. These are plain, honest heuristics — a flag means "have a
 * look", not "this is a virus". CyberShield is not an antivirus and never claims a
 * file is malware.
 *
 * <p>The decision for a single file lives in {@link #reasonFor}, which is pure and
 * has no contact with the disk, so it can be tested on its own. The walk only
 * gathers facts (name, place, hidden flag, size) and hands them to it.
 */
public final class FileScan {

    /** How much a finding is worth paying attention to. */
    public enum Level {

        REVIEW("Worth a look"),
        NOTE("Good to know"),
        INFO("For information");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

    }

    /** Why a file was flagged. Each reason carries its own wording and level. */
    public enum Reason {

        DOUBLE_EXTENSION(Level.REVIEW, "Hidden file type",
                "Named like a document but is really a program — e.g. photo.jpg.exe. A common disguise."),
        HIDDEN_PROGRAM(Level.REVIEW, "Hidden program",
                "A program or script set to stay out of sight."),
        STARTUP_ITEM(Level.NOTE, "Runs at start-up",
                "Set to run every time you sign in. Make sure you recognise it."),
        PROGRAM_IN_TEMP(Level.NOTE, "Program in a temporary folder",
                "Programs rarely belong in a temp folder; one you ran once can leave them there."),
        PROGRAM_IN_DOWNLOADS(Level.NOTE, "Installer in Downloads",
                "A program or installer sitting in Downloads. Fine if you put it there — delete it once used."),
        SCRIPT_FILE(Level.NOTE, "Script file",
                "Can run commands (.bat, .cmd, .ps1, .vbs, .js and the like). Open only ones you trust."),
        AUTORUN(Level.REVIEW, "Autorun file",
                "On a device this can auto-start a program when the drive is opened — a classic way USB malware spreads."),
        PROGRAM_ON_DEVICE(Level.NOTE, "Program on a device",
                "A program or installer on a removable or external drive. Drives are a common way malware spreads — open it only if you trust it."),
        LARGE_FILE(Level.INFO, "Large file",
                "Taking up a lot of space. Not a security problem — shown so you can tidy up.");

        private final Level level;

        private final String title;

        private final String detail;

        Reason(Level level, String title, String detail) {
            this.level = level;
            this.title = title;
            this.detail = detail;
        }

        public Level level() {
            return level;
        }

        public String title() {
            return title;
        }

        public String detail() {
            return detail;
        }

    }

    /** Which kind of folder a file lives in — some rules only apply in some places. */
    public enum Area {
        DOWNLOADS, DESKTOP, DOCUMENTS, TEMP, STARTUP, APPDATA, PROFILE, REMOVABLE, OTHER
    }

    /** A folder to look through, and what kind of place it is. */
    public record Root(Path dir, Area area) {
    }

    /** One flagged file. {@code sha256} is filled only on a deep scan, and may be null. */
    public record Finding(Path path, Area area, Reason reason, long bytes, Instant modified, String sha256) {

        public Level level() {
            return reason.level();
        }

    }

    /** Scan settings. Quick is shallow and fast; deep goes wider and can hash. */
    public record Options(long largeFileBytes, int maxDepth, int maxFiles, boolean hash) {

        public static Options quick() {
            return new Options(2L * 1024 * 1024 * 1024, 6, 50_000, false);
        }

        public static Options deep() {
            return new Options(1024L * 1024 * 1024, 40, 400_000, true);
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

    /** Runnable programs. */
    private static final Set<String> PROGRAM_EXT = Set.of(
            "exe", "msi", "scr", "com", "pif", "cpl", "msc", "gadget");

    /** Scripts and other things that run code. */
    private static final Set<String> SCRIPT_EXT = Set.of(
            "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse",
            "wsf", "wsh", "hta", "jar", "reg", "py");

    /** Extensions that look like an ordinary document, used to spot a disguise. */
    private static final Set<String> DOCUMENT_EXT = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf",
            "jpg", "jpeg", "png", "gif", "bmp", "zip", "rar", "csv", "mp3", "mp4");

    /** Only hash files up to this size, to keep a deep scan responsive. */
    private static final long MAX_HASH_BYTES = 200L * 1024 * 1024;

    /** The folders a quick scan looks through on this PC. */
    public static List<Root> quickRoots() {

        List<Root> roots = new ArrayList<>();

        String profile = System.getenv("USERPROFILE");

        if (profile != null) {
            addIfDir(roots, Path.of(profile, "Downloads"), Area.DOWNLOADS);
            addIfDir(roots, Path.of(profile, "Desktop"), Area.DESKTOP);
        }

        String tmp = System.getProperty("java.io.tmpdir");

        if (tmp != null) {
            addIfDir(roots, Path.of(tmp), Area.TEMP);
        }

        String appData = System.getenv("APPDATA");

        if (appData != null) {
            addIfDir(roots, Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup"),
                    Area.STARTUP);
        }

        return roots;
    }

    /** The folders a deep scan looks through: the whole user profile, plus temp if it sits elsewhere. */
    public static List<Root> deepRoots() {

        String profile = System.getenv("USERPROFILE");

        if (profile == null) {
            return quickRoots();
        }

        List<Root> roots = new ArrayList<>();

        Path home = Path.of(profile);

        addIfDir(roots, home, Area.PROFILE);

        String tmp = System.getProperty("java.io.tmpdir");

        if (tmp != null) {
            Path t = Path.of(tmp);
            if (!t.startsWith(home)) {
                addIfDir(roots, t, Area.TEMP);
            }
        }

        return roots;
    }

    private static void addIfDir(List<Root> roots, Path dir, Area area) {
        if (dir != null && Files.isDirectory(dir)) {
            roots.add(new Root(dir, area));
        }
    }

    /** Looks through the roots and returns what is worth a look. Changes nothing on disk. */
    public List<Finding> scan(List<Root> roots, Options options, Progress progress) {

        List<Finding> findings = new ArrayList<>();

        int[] seen = {0};

        for (Root root : roots) {
            if (progress.isCancelled() || seen[0] >= options.maxFiles()) {
                break;
            }
            walk(root, options, progress, findings, seen);
        }

        return findings;
    }

    private void walk(Root root, Options options, Progress progress, List<Finding> out, int[] seen) {

        try {
            Files.walkFileTree(root.dir(), EnumSet.noneOf(FileVisitOption.class), options.maxDepth(),
                    new SimpleFileVisitor<>() {

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (progress.isCancelled() || seen[0] >= options.maxFiles()) {
                                return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (progress.isCancelled() || seen[0] >= options.maxFiles()) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (!attrs.isRegularFile()) {
                                return FileVisitResult.CONTINUE;
                            }

                            seen[0]++;
                            if ((seen[0] & 0x3FF) == 0) {
                                progress.update(seen[0], file);
                            }

                            Area area = areaOf(file, root.area());
                            boolean hidden = isHidden(file, attrs);

                            Reason reason = reasonFor(file.getFileName().toString(), area, hidden,
                                    attrs.size(), options.largeFileBytes());

                            if (reason != null) {
                                String hash = null;
                                if (options.hash() && reason.level() != Level.INFO && attrs.size() <= MAX_HASH_BYTES) {
                                    hash = sha256(file);
                                }
                                out.add(new Finding(file, area, reason, attrs.size(),
                                        attrs.lastModifiedTime().toInstant(), hash));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            // a file we cannot read (in use, denied) is simply skipped
                            return FileVisitResult.CONTINUE;
                        }

                    });
        } catch (IOException | RuntimeException ignored) {
            // the root went away or became unreadable mid-walk — keep what we already found
        }
    }

    /**
     * The whole decision for one file, with no contact with the disk. Given the file's
     * name, where it lives, whether it is hidden and how big it is, it returns the single
     * most important reason to flag it, or {@code null} to leave it alone.
     */
    public static Reason reasonFor(String name, Area area, boolean hidden, long bytes, long largeThreshold) {

        String lower = name.toLowerCase(Locale.ROOT);

        // on a device, an autorun file can auto-launch a program when the drive is opened
        if (area == Area.REMOVABLE && lower.equals("autorun.inf")) {
            return Reason.AUTORUN;
        }

        String ext = ext(lower);

        boolean program = PROGRAM_EXT.contains(ext);

        boolean script = SCRIPT_EXT.contains(ext);

        boolean runnable = program || script;

        if (runnable && hasDoubleExtension(lower)) {
            return Reason.DOUBLE_EXTENSION;
        }
        if (runnable && hidden && area != Area.APPDATA) {
            return Reason.HIDDEN_PROGRAM;
        }
        if (runnable && area == Area.STARTUP) {
            return Reason.STARTUP_ITEM;
        }
        if (runnable && area == Area.TEMP) {
            return Reason.PROGRAM_IN_TEMP;
        }
        if (program && area == Area.DOWNLOADS) {
            return Reason.PROGRAM_IN_DOWNLOADS;
        }
        if (program && area == Area.REMOVABLE) {
            return Reason.PROGRAM_ON_DEVICE;
        }
        if (script && (area == Area.DOWNLOADS || area == Area.DESKTOP
                || area == Area.DOCUMENTS || area == Area.REMOVABLE)) {
            return Reason.SCRIPT_FILE;
        }
        if (largeThreshold > 0 && bytes >= largeThreshold
                && (area == Area.DOWNLOADS || area == Area.DESKTOP
                || area == Area.DOCUMENTS || area == Area.TEMP)) {
            return Reason.LARGE_FILE;
        }
        return null;
    }

    /** True when the name looks like a document but ends in a program/script extension. */
    static boolean hasDoubleExtension(String lowerName) {

        int last = lowerName.lastIndexOf('.');

        if (last <= 0) {
            return false;
        }

        String beforeLast = lowerName.substring(0, last);

        int prev = beforeLast.lastIndexOf('.');

        if (prev < 0) {
            return false;
        }

        return DOCUMENT_EXT.contains(beforeLast.substring(prev + 1));
    }

    /** When walking the whole profile, work out which kind of place a file is really in. */
    public static Area areaOf(Path file, Area rootArea) {

        if (rootArea != Area.PROFILE) {
            return rootArea;
        }

        String p = file.toString().toLowerCase(Locale.ROOT).replace('/', '\\');

        if (p.contains("\\start menu\\programs\\startup\\")) {
            return Area.STARTUP;
        }
        if (p.contains("\\downloads\\")) {
            return Area.DOWNLOADS;
        }
        if (p.contains("\\desktop\\")) {
            return Area.DESKTOP;
        }
        if (p.contains("\\documents\\")) {
            return Area.DOCUMENTS;
        }
        if (p.contains("\\temp\\") || p.contains("\\tmp\\")
                || p.contains("\\inetcache\\") || p.contains("\\temporary internet files\\")) {
            return Area.TEMP;
        }
        if (p.contains("\\appdata\\")) {
            return Area.APPDATA;
        }
        return Area.OTHER;
    }

    private static String ext(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        if (dot < 0 || dot == lowerName.length() - 1) {
            return "";
        }
        return lowerName.substring(dot + 1);
    }

    private static boolean isHidden(Path file, BasicFileAttributes attrs) {
        if (attrs instanceof DosFileAttributes dos) {
            return dos.isHidden();
        }
        try {
            return Files.isHidden(file);
        } catch (IOException e) {
            return false;
        }
    }

    private static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

}
