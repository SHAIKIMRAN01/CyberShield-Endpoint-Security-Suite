package com.cybershield.database;

import com.cybershield.logger.AppLogger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
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

}