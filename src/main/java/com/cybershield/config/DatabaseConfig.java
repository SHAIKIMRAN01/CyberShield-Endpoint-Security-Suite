package com.cybershield.config;

import java.nio.file.Path;

public final class DatabaseConfig {

    /** Optional system property naming another database file. The tests use it, so they never touch the real one. */
    public static final String PATH_PROPERTY = "cybershield.db";

    public static final String DEFAULT_PATH = "database/cybershield.db";

    private DatabaseConfig() {
    }

    /** The database file: database/cybershield.db in the working folder, unless the property names another. */
    public static Path databasePath() {

        String override = System.getProperty(PATH_PROPERTY);

        String path = override != null && !override.isBlank() ? override : DEFAULT_PATH;

        return Path.of(path).toAbsolutePath().normalize();

    }

    public static String databaseUrl() {

        return "jdbc:sqlite:" + databasePath();

    }

}
