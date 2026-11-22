package com.cybershield.config;

import java.nio.file.Path;

public final class DatabaseConfig {

    /** Optional system property naming another database file. The tests use it, so they never touch the real one. */
    public static final String PATH_PROPERTY = "cybershield.db";

    /**
     * Where the database used to be: database\cybershield.db in the folder the app ran from.
     * It is copied to the data folder on the first start after the move (see LegacyDatabase).
     */
    public static final String LEGACY_PATH = "database/cybershield.db";

    private DatabaseConfig() {
    }

    /** The database file: Data\cybershield.db in the user's data folder, unless the property names another. */
    public static Path databasePath() {

        return resolve(System.getProperty(PATH_PROPERTY), DataFolders.database());

    }

    /** Whether the property names another file, so the usual one isn't used at all. */
    public static boolean isOverridden() {

        String override = System.getProperty(PATH_PROPERTY);

        return override != null && !override.isBlank();

    }

    /** The override if one is given, else the usual place; absolute either way. */
    public static Path resolve(String override, Path usual) {

        Path path = override != null && !override.isBlank() ? Path.of(override) : usual;

        return path.toAbsolutePath().normalize();

    }

    public static String databaseUrl() {

        return "jdbc:sqlite:" + databasePath();

    }

}
