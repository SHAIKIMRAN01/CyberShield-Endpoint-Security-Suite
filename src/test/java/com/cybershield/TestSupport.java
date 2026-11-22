package com.cybershield;

import com.cybershield.config.DatabaseConfig;
import com.cybershield.dao.UserDAO;
import com.cybershield.database.DatabaseInitializer;
import com.cybershield.database.DatabaseManager;
import com.cybershield.files.FileVaultService;
import com.cybershield.security.SessionManager;
import com.cybershield.services.AuthenticationService;
import com.cybershield.vault.VaultService;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Gives each test its own database and file store in a temp folder, so nothing real is ever touched. */
public final class TestSupport {

    /** Throwaway values used only by the tests. */
    public static final String ACCOUNT_PASSWORD = "Account-Pass-9!";

    public static final String MASTER_PASSWORD = "Master-Password-9!";

    public static final SecureRandom RANDOM = new SecureRandom();

    private TestSupport() {
    }

    /** Points the database and the file store into tempDir and creates the tables. Returns the store folder. */
    public static Path freshEnvironment(Path tempDir) {

        new VaultService().lockVault();

        SessionManager.logout();

        System.setProperty(DatabaseConfig.PATH_PROPERTY, tempDir.resolve("db").resolve("cybershield.db").toString());

        Path store = tempDir.resolve("store");

        System.setProperty(FileVaultService.STORE_DIR_PROPERTY, store.toString());

        DatabaseInitializer.initialize();

        return store;

    }

    /** Registers an account with the test password and returns its id. */
    public static int newUser(String username) {

        AuthenticationService.Result result =
                new AuthenticationService().register(username, username + "@example.test", ACCOUNT_PASSWORD);

        if (!result.ok()) {
            throw new IllegalStateException("Couldn't register " + username + ": " + result.message());
        }

        return new UserDAO().findByUsername(username).getId();

    }

    public static void execute(String sql, Object... parameters) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }

            statement.executeUpdate();

        }

    }

    public static long count(String sql, Object... parameters) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }

        }

    }

    public static String text(String sql, Object... parameters) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }

        }

    }

    public static byte[] random(int size) {

        byte[] bytes = new byte[size];

        RANDOM.nextBytes(bytes);

        return bytes;

    }

}
