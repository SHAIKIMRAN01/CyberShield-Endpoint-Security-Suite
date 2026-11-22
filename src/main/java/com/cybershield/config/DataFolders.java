package com.cybershield.config;

import java.nio.file.Path;

/**
 * Where CyberShield keeps its data: one folder per Windows user, %LOCALAPPDATA%\CyberShield,
 * used alike by the installed app and by a copy run from the project. An installed app
 * can't write next to itself, and one place means both see the same accounts.
 *
 * <pre>
 * %LOCALAPPDATA%\CyberShield\
 *     Data\cybershield.db   accounts, encrypted logins and notes, settings, history
 *     FileVault\            protected files (see FileVaultService)
 *     logs\                 the app's log
 * </pre>
 */
public final class DataFolders {

    /** Optional system property naming another base folder. The tests use it, so they never touch the real one. */
    public static final String HOME_PROPERTY = "cybershield.home";

    private DataFolders() {
    }

    /** %LOCALAPPDATA%\CyberShield on Windows, ~/.cybershield elsewhere, unless the property names another. */
    public static Path home() {

        String override = System.getProperty(HOME_PROPERTY);

        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }

        String localAppData = System.getenv("LOCALAPPDATA");

        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "CyberShield");
        }

        return Path.of(System.getProperty("user.home"), ".cybershield");

    }

    /** The database file's usual place: Data\cybershield.db. */
    public static Path database() {

        return home().resolve("Data").resolve(AppConfig.DATABASE_NAME);

    }

    /** The log folder: logs\. */
    public static Path logs() {

        return home().resolve("logs");

    }

}
