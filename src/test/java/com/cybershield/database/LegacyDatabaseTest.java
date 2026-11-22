package com.cybershield.database;

import com.cybershield.config.DataFolders;
import com.cybershield.config.DatabaseConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Moving the database to the user's data folder: a copy, made once, never over an existing one. Temp folders only. */
class LegacyDatabaseTest {

    @TempDir
    Path dir;

    @Test
    void theOldDatabaseIsCopiedWholeAndLeftAsItWas() throws Exception {

        Path legacy = database(dir.resolve("project").resolve("database").resolve("cybershield.db"), "alice", "bob");

        byte[] before = Files.readAllBytes(legacy);

        Path target = dir.resolve("LocalAppData").resolve("CyberShield").resolve("Data").resolve("cybershield.db");

        assertTrue(LegacyDatabase.copy(legacy, target), "copied");

        assertEquals("alice,bob", names(target), "every row came across");
        assertArrayEquals(before, Files.readAllBytes(legacy), "the old file is untouched, as a backup");

        try (Stream<Path> left = Files.list(target.getParent())) {
            assertEquals(1, left.count(), "no .part file is left behind");
        }

        assertFalse(LegacyDatabase.copy(legacy, target), "only once");

    }

    @Test
    void anExistingDatabaseIsNeverReplaced() throws Exception {

        Path legacy = database(dir.resolve("old.db"), "alice");

        Path target = database(dir.resolve("Data").resolve("cybershield.db"), "carol");

        assertFalse(LegacyDatabase.copy(legacy, target));

        assertEquals("carol", names(target));

    }

    @Test
    void nothingToCopyCreatesNothing() throws Exception {

        Path target = dir.resolve("Data").resolve("cybershield.db");

        assertFalse(LegacyDatabase.copy(dir.resolve("missing.db"), target));

        assertFalse(Files.exists(target));

    }

    @Test
    void aDamagedOldFileStopsTheCopyAndLeavesNoDatabase() throws Exception {

        Path legacy = Files.writeString(dir.resolve("cybershield.db"), "this is not a database, just some text that is long enough to have a header");

        Path target = dir.resolve("Data").resolve("cybershield.db");

        assertThrows(SQLException.class, () -> LegacyDatabase.copy(legacy, target));

        assertFalse(Files.exists(target), "no empty database, so the next start tries again");
        assertFalse(Files.exists(target.resolveSibling("cybershield.db.part")));

    }

    @Test
    void theDataFolderFollowsItsPropertyAndTheTestsNeverUseTheRealOne() {

        // Maven points cybershield.home and cybershield.db at target/test-data (see pom.xml).
        assertTrue(DataFolders.home().toString().contains("test-data"), DataFolders.home().toString());
        assertEquals(DataFolders.home().resolve("Data").resolve("cybershield.db"), DataFolders.database());
        assertEquals(DataFolders.home().resolve("logs"), DataFolders.logs());

        assertEquals(Path.of("x.db").toAbsolutePath(), DatabaseConfig.resolve("x.db", dir.resolve("usual.db")));
        assertEquals(dir.resolve("usual.db"), DatabaseConfig.resolve(null, dir.resolve("usual.db")));
        assertEquals(dir.resolve("usual.db"), DatabaseConfig.resolve(" ", dir.resolve("usual.db")));

    }

    private static Path database(Path file, String... names) throws SQLException, java.io.IOException {

        Files.createDirectories(file.getParent());

        try (
                Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement()
        ) {

            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, username TEXT)");

            for (String name : names) {
                statement.execute("INSERT INTO users (username) VALUES ('" + name + "')");
            }

        }

        return file;

    }

    private static String names(Path file) throws SQLException {

        StringBuilder names = new StringBuilder();

        try (
                Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT username FROM users ORDER BY id")
        ) {

            while (rows.next()) {
                names.append(names.isEmpty() ? "" : ",").append(rows.getString(1));
            }

        }

        return names.toString();

    }

}
