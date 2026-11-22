package com.cybershield.database;

import com.cybershield.config.DatabaseConfig;
import com.cybershield.logger.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Up to version 1.0 the database sat in database\cybershield.db in the folder the app ran
 * from. It now lives in the user's data folder, where an installed app can write too. On
 * the first start after that, the old database is copied across; the old file stays where
 * it is, as a backup.
 */
public final class LegacyDatabase {

    private LegacyDatabase() {
    }

    /**
     * Copies the old database into the data folder if that has none yet. Does nothing when
     * the tests (or anyone) name another database with -Dcybershield.db.
     *
     * @throws IllegalStateException if the old database exists but couldn't be copied: then
     *                               nothing was created, so the next start tries again
     */
    public static void copyIfNeeded() {

        if (DatabaseConfig.isOverridden()) {
            return;
        }

        Path legacy = Path.of(DatabaseConfig.LEGACY_PATH).toAbsolutePath().normalize();

        try {

            if (copy(legacy, DatabaseConfig.databasePath())) {
                AppLogger.info("Copied the database from the app's folder to the data folder. The old copy was left as it was.");
            }

        } catch (IOException | SQLException e) {

            throw new IllegalStateException("Your database couldn't be copied to its new place in your user folder ("
                    + e.getMessage() + "). Nothing was changed, and the next start tries again. To start without it,"
                    + " move database\\cybershield.db out of the folder CyberShield runs from.", e);

        }

    }

    /**
     * Copies legacy to target if target doesn't exist and legacy does, and says whether it
     * did. SQLite makes the copy itself (VACUUM INTO), so an unfinished write left in the old
     * database's journal is settled first, and the copy is whole. It is written beside the
     * target and only then renamed, so an interrupted copy never becomes the database.
     */
    static boolean copy(Path legacy, Path target) throws IOException, SQLException {

        Path from = legacy.toAbsolutePath().normalize();

        Path to = target.toAbsolutePath().normalize();

        if (Files.exists(to) || !Files.isRegularFile(from) || from.equals(to)) {
            return false;
        }

        Files.createDirectories(to.getParent());

        Path part = to.resolveSibling(to.getFileName() + ".part");

        Files.deleteIfExists(part);

        try {

            try (
                    Connection connection = DriverManager.getConnection("jdbc:sqlite:" + from);
                    Statement statement = connection.createStatement()
            ) {
                statement.execute("VACUUM INTO '" + part.toString().replace("'", "''") + "'");
            }

            Files.move(part, to, StandardCopyOption.ATOMIC_MOVE);

        } finally {

            Files.deleteIfExists(part);

        }

        return true;

    }

}
