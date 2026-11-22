package com.cybershield.database;

import com.cybershield.config.DatabaseConfig;
import com.cybershield.logger.AppLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

public final class DatabaseInitializer {

    private DatabaseInitializer() {
    }

    /**
     * Creates the database, or brings an older one up to date. Safe to run on every start.
     *
     * @throws IllegalStateException if the database can't be opened or prepared
     */
    public static void initialize() {

        AppLogger.info("Initializing Database...");

        Path file = DatabaseConfig.databasePath();

        try {

            // SQLite won't create a missing folder.
            Files.createDirectories(file.getParent());

        } catch (IOException e) {

            throw new IllegalStateException("Couldn't create the database folder " + file.getParent() + ".", e);

        }

        try (
                Connection connection = DatabaseManager.getConnection();
                Statement statement = connection.createStatement()
        ) {

            upgradeLegacyVaultTable(statement);

            runSchema(statement);

            addVaultColumns(statement);

            AppLogger.info("Database initialized successfully.");

        } catch (SQLException | IOException e) {

            throw new IllegalStateException("The database couldn't be prepared: " + e.getMessage(), e);

        }

    }

    private static void runSchema(Statement statement) throws IOException, SQLException {

        String sql;

        try (InputStream in = DatabaseInitializer.class.getResourceAsStream("/sql/schema.sql")) {

            if (in == null) {
                throw new IOException("schema.sql not found.");
            }

            sql = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

        }

        for (String part : sql.split(";")) {

            if (!part.isBlank()) {
                statement.execute(part);
            }

        }

    }

    /**
     * Each account now has its own vault, and every saved item records the vault
     * that encrypted it. Rows from older databases keep vault_id NULL: they were
     * encrypted with the shared vault, and move to an account when it is unlocked.
     */
    private static void addVaultColumns(Statement statement) throws SQLException {

        for (String table : new String[] {"password_entries", "secure_notes", "protected_items"}) {

            if (!hasColumn(statement, table, "vault_id")) {

                statement.execute("ALTER TABLE " + table + " ADD COLUMN vault_id INTEGER");

                AppLogger.info("Added vault_id to " + table + ".");

            }

        }

        addColumn(statement, "password_entries", "category", "TEXT");
        addColumn(statement, "password_entries", "favourite", "INTEGER DEFAULT 0");
        addColumn(statement, "password_entries", "fields_encrypted", "INTEGER DEFAULT 0");

        addColumn(statement, "reports", "user_id", "INTEGER");

        statement.execute("CREATE INDEX IF NOT EXISTS idx_reports_user ON reports(user_id)");

        statement.execute("CREATE INDEX IF NOT EXISTS idx_password_vault ON password_entries(vault_id)");

        statement.execute("CREATE INDEX IF NOT EXISTS idx_notes_vault ON secure_notes(vault_id)");

        statement.execute("CREATE INDEX IF NOT EXISTS idx_protected_items_vault ON protected_items(vault_id)");

    }

    private static void addColumn(Statement statement, String table, String column, String definition) throws SQLException {

        if (!hasColumn(statement, table, column)) {

            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);

            AppLogger.info("Added " + column + " to " + table + ".");

        }

    }

    private static boolean hasColumn(Statement statement, String table, String column) throws SQLException {

        try (ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {

            while (rs.next()) {

                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }

            }

        }

        return false;

    }

    /**
     * Early builds created the vault table with a master_password_hash
     * column. The vault now stores a salt and an encrypted verification
     * value, and CREATE TABLE IF NOT EXISTS never changes an existing
     * table, so the old one has to be replaced before the schema runs.
     */
    private static void upgradeLegacyVaultTable(Statement statement)
            throws SQLException {

        boolean vaultTableExists;

        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'vault'")) {

            vaultTableExists = rs.next();

        }

        if (!vaultTableExists) {
            return;
        }

        if (hasColumn(statement, "vault", "salt")) {
            return;
        }

        int rows;

        try (ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM vault")) {

            rows = rs.next() ? rs.getInt(1) : 0;

        }

        if (rows == 0) {

            statement.execute("DROP TABLE vault");

            AppLogger.info("Replaced the empty legacy vault table.");

        } else {

            // Keep the old data instead of deleting it.
            statement.execute("ALTER TABLE vault RENAME TO vault_legacy");

            AppLogger.info("Moved the legacy vault table to vault_legacy.");

        }

    }

}
