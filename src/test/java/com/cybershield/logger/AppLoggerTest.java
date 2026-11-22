package com.cybershield.logger;

import com.cybershield.config.DataFolders;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The log: paths never reach it, errors keep their cause, and the file stays small. */
class AppLoggerTest {

    @TempDir
    Path dir;

    private String home;

    @BeforeEach
    void ownLogFolder() {
        home = System.getProperty(DataFolders.HOME_PROPERTY);
        System.setProperty(DataFolders.HOME_PROPERTY, dir.toString());
    }

    @AfterEach
    void restore() {
        System.setProperty(DataFolders.HOME_PROPERTY, home);
    }

    @Test
    void pathsAreReplacedAndEverythingElseStays() {

        assertEquals("Couldn't open <path>: Access is denied",
                AppLogger.redact("Couldn't open C:\\Users\\sam\\Documents\\secret plan.docx: Access is denied"));

        assertEquals("<path> -> <path>: The process cannot access the file",
                AppLogger.redact("C:\\a\\b.txt -> D:\\c.txt: The process cannot access the file"));

        assertEquals("Copying <path>", AppLogger.redact("Copying \\\\nas\\photos\\holiday 2026\\beach.jpg"));

        assertEquals("Opened <path> in memory", AppLogger.redact("Opened file:///C:/Users/sam/Videos/trip.mp4 in memory"));

        assertEquals("jdbc:sqlite:<path>", AppLogger.redact("jdbc:sqlite:E:/project/CyberShield/database/cybershield.db"));

        assertEquals("Vault locked after 300 seconds at 10:22, see http://127.0.0.1:9/x",
                AppLogger.redact("Vault locked after 300 seconds at 10:22, see http://127.0.0.1:9/x"), "times and web addresses stay");

        assertEquals("", AppLogger.redact(null));

    }

    @Test
    void linesGoToTheFileWithTheirLevelAndErrorsWithTheirCause() throws Exception {

        String marker = "marker-" + System.nanoTime();

        AppLogger.info(marker + " info");
        AppLogger.warn(marker + " warn");
        AppLogger.error(marker + " error", new IOException("C:\\Users\\sam\\tax-return.pdf (Access is denied)",
                new NoSuchFileException("D:\\Backups\\tax-return.pdf")));

        Path file = AppLogger.logFile();

        assertEquals(dir.resolve("logs").resolve("cybershield.log"), file, "in the data folder's logs");

        String text = Files.readString(file);

        assertTrue(text.contains("INFO   " + marker + " info"), text);
        assertTrue(text.contains("WARN   " + marker + " warn"), text);
        assertTrue(text.contains("ERROR  " + marker + " error: IOException (<path>)"), text);
        assertTrue(text.contains("Caused by NoSuchFileException (<path>)"), text);
        assertTrue(text.contains("at com.cybershield.logger.AppLoggerTest.linesGoToTheFile"), "the stack trace is kept");
        assertFalse(text.contains("tax-return") || text.contains("sam"), "no file names or user names: " + text);

    }

    @Test
    void aFullFileMovesAsideAndTheOldestGoes() throws Exception {

        Path logs = Files.createDirectories(dir.resolve("logs"));

        Files.writeString(logs.resolve("cybershield.log"), "x".repeat((int) AppLogger.MAX_BYTES));

        String[] older = {"", "one", "two", "three", "four"};

        for (int n = 1; n <= AppLogger.OLDER_FILES; n++) {
            Files.writeString(logs.resolve("cybershield." + n + ".log"), older[n]);
        }

        AppLogger.info("after the move");

        assertTrue(Files.readString(logs.resolve("cybershield.log")).contains("after the move"));
        assertTrue(Files.size(logs.resolve("cybershield.log")) < 200, "a fresh file");
        assertEquals((int) AppLogger.MAX_BYTES, Files.readString(logs.resolve("cybershield.1.log")).length(), "the full one is .1");
        assertEquals("one", Files.readString(logs.resolve("cybershield.2.log")));
        assertEquals("two", Files.readString(logs.resolve("cybershield.3.log")));
        assertEquals("three", Files.readString(logs.resolve("cybershield.4.log")), "four, the oldest, is gone");

        try (var files = Files.list(logs)) {
            assertEquals(1 + AppLogger.OLDER_FILES, files.count(), "never more than five files");
        }

    }

}
