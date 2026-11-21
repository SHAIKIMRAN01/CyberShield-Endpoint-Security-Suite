package com.cybershield.database;

import com.cybershield.logger.AppLogger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

public final class DatabaseInitializer {

    private DatabaseInitializer() {
    }

    public static void initialize() {

        AppLogger.info("Initializing Database...");

        try (
                Connection connection = DatabaseManager.getConnection();
                Statement statement = connection.createStatement()
        ) {

            upgradeLegacyVaultTable(statement);

            InputStream inputStream =
                    DatabaseInitializer.class.getResourceAsStream("/sql/schema.sql");

            if (inputStream == null) {
                throw new RuntimeException("schema.sql not found.");
            }

 String sql = new BufferedReader(
        new InputStreamReader(inputStream))
        .lines()
        .collect(Collectors.joining("\n"));

String[] statements = sql.split(";");

for (String s : statements) {

    if (!s.trim().isEmpty()) {

        statement.execute(s);

    }

}

            AppLogger.success("Database initialized successfully.");

        } catch (Exception e) {

            AppLogger.error(e.getMessage());

        }

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

        boolean hasSaltColumn = false;

        try (ResultSet rs = statement.executeQuery("PRAGMA table_info(vault)")) {

            while (rs.next()) {

                if ("salt".equalsIgnoreCase(rs.getString("name"))) {
                    hasSaltColumn = true;
                }

            }

        }

        if (hasSaltColumn) {
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