package com.cybershield.logger;

import com.cybershield.config.DataFolders;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * The app's log: one line per event, on the console and in logs\cybershield.log in the
 * data folder, where an installed app (which has no console) leaves its trace. The file
 * is kept to five files of 1 MB: cybershield.log, then cybershield.1.log (older) up to
 * cybershield.4.log.
 *
 * <p>Lines say what happened, with counts; never passwords, note text, website names or
 * file names. Error messages from Java can name a file, so every folder or file path in
 * a line is replaced with &lt;path&gt; before it is written. Accounts appear by number.
 */
public final class AppLogger {

    public enum Level { INFO, WARN, ERROR }

    /** Each log file is started afresh after this many bytes. */
    static final long MAX_BYTES = 1024 * 1024;

    /** cybershield.log and up to this many older files. */
    static final int OLDER_FILES = 4;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // A Windows path: a drive letter (not inside a word, so "http://" isn't one), a colon and a
    // slash, then anything a file name may hold (no colon, quote, <, >, |, ? or *), up to the
    // " -> " Java puts between two paths.
    private static final Pattern DRIVE_PATH = Pattern.compile("(?<![A-Za-z])[A-Za-z]:[\\\\/](?:(?! -> )[^:\"<>|?*\\r\\n])*");

    // A network path: \\server\share\...
    private static final Pattern NETWORK_PATH = Pattern.compile("\\\\\\\\[^\\\\/:\"<>|?*\\r\\n]+[\\\\/](?:(?! -> )[^:\"<>|?*\\r\\n])*");

    private static final Pattern FILE_URI = Pattern.compile("file:/+[^\\s\"<>]*");

    private static boolean fileBroken;

    private AppLogger() {
    }

    public static void info(String message) {
        write(Level.INFO, message, null);
    }

    /** Something went differently than it should, but the app carries on (for example, no sound output). */
    public static void warn(String message) {
        write(Level.WARN, message, null);
    }

    public static void error(String message) {
        write(Level.ERROR, message, null);
    }

    /** An error with its cause: the cause's type and message go on the line, its stack trace into the file. */
    public static void error(String message, Throwable cause) {
        write(Level.ERROR, message, cause);
    }

    /** The current log file. */
    public static Path logFile() {
        return DataFolders.logs().resolve("cybershield.log");
    }

    /** Replaces every folder or file path in text with &lt;path&gt;. */
    static String redact(String text) {

        if (text == null) {
            return "";
        }

        String clean = FILE_URI.matcher(text).replaceAll("<path>");

        clean = NETWORK_PATH.matcher(clean).replaceAll("<path>");

        return DRIVE_PATH.matcher(clean).replaceAll("<path>");

    }

    private static synchronized void write(Level level, String message, Throwable cause) {

        String text = redact(message) + (cause == null ? "" : ": " + describe(cause));

        String line = LocalDateTime.now().format(TIME) + "  " + String.format("%-5s", level) + "  " + text;

        PrintStream console = level == Level.INFO ? System.out : System.err;

        console.println(line);

        if (fileBroken) {
            return;
        }

        try {

            Path file = logFile();

            Files.createDirectories(file.getParent());

            if (Files.exists(file) && Files.size(file) >= MAX_BYTES) {
                rotate(file);
            }

            StringBuilder entry = new StringBuilder(line).append(System.lineSeparator());

            if (cause != null) {
                stackTrace(cause, entry);
            }

            Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        } catch (IOException | RuntimeException e) {

            // The console still has it. Don't try the file again this run.
            fileBroken = true;

            System.err.println(LocalDateTime.now().format(TIME) + "  WARN   The log file can't be written: " + redact(e.getMessage()));

        }

    }

    /** cybershield.log becomes cybershield.1.log, .1 becomes .2, and so on; the oldest goes. */
    private static void rotate(Path file) throws IOException {

        Path folder = file.getParent();

        Files.deleteIfExists(folder.resolve("cybershield." + OLDER_FILES + ".log"));

        for (int n = OLDER_FILES - 1; n >= 1; n--) {

            Path older = folder.resolve("cybershield." + n + ".log");

            if (Files.exists(older)) {
                Files.move(older, folder.resolve("cybershield." + (n + 1) + ".log"), StandardCopyOption.REPLACE_EXISTING);
            }

        }

        Files.move(file, folder.resolve("cybershield.1.log"), StandardCopyOption.REPLACE_EXISTING);

    }

    private static String describe(Throwable cause) {

        String message = cause.getMessage();

        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : " (" + redact(message) + ")");

    }

    private static void stackTrace(Throwable cause, StringBuilder entry) {

        Throwable each = cause;

        for (int depth = 0; each != null && depth < 5; depth++) {

            if (depth > 0) {
                entry.append("    Caused by ").append(describe(each)).append(System.lineSeparator());
            }

            for (StackTraceElement frame : each.getStackTrace()) {
                entry.append("        at ").append(redact(frame.toString())).append(System.lineSeparator());
            }

            each = each.getCause() == each ? null : each.getCause();

        }

    }

}
