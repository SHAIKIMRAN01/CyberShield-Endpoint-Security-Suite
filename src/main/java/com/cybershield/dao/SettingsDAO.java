package com.cybershield.dao;

import com.cybershield.database.DatabaseManager;
import com.cybershield.logger.AppLogger;
import com.cybershield.security.SessionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Each account's settings (user_settings). An account that hasn't saved any yet
 * gets the defaults from the original settings row, or the built-in ones.
 */
public class SettingsDAO {

    public static final int DEFAULT_AUTO_LOCK_MINUTES = 5;

    public static final int DEFAULT_CLIPBOARD_CLEAR_SECONDS = 30;

    public static final int MIN_AUTO_LOCK_MINUTES = 1;

    public static final int MAX_AUTO_LOCK_MINUTES = 60;

    public static final int MIN_CLIPBOARD_CLEAR_SECONDS = 5;

    public static final int MAX_CLIPBOARD_CLEAR_SECONDS = 300;

    public record Settings(int autoLockMinutes, int clipboardClearSeconds) {

        public static final Settings DEFAULTS = new Settings(DEFAULT_AUTO_LOCK_MINUTES, DEFAULT_CLIPBOARD_CLEAR_SECONDS);

        /** The same values pulled into the allowed ranges. */
        public Settings clamped() {

            return new Settings(
                    clamp(autoLockMinutes, MIN_AUTO_LOCK_MINUTES, MAX_AUTO_LOCK_MINUTES),
                    clamp(clipboardClearSeconds, MIN_CLIPBOARD_CLEAR_SECONDS, MAX_CLIPBOARD_CLEAR_SECONDS)
            );

        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

    }

    /** The account's settings, or the defaults. Never fails: a read error falls back to the defaults. */
    public Settings load(int userId) {

        try (Connection connection = DatabaseManager.getConnection()) {

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT auto_lock_minutes, clipboard_clear_seconds FROM user_settings WHERE user_id = ?")) {

                statement.setInt(1, userId);

                try (ResultSet rs = statement.executeQuery()) {

                    if (rs.next()) {
                        return new Settings(rs.getInt(1), rs.getInt(2)).clamped();
                    }

                }

            }

            try (
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT auto_lock_minutes, clipboard_clear_seconds FROM settings WHERE id = 1");
                    ResultSet rs = statement.executeQuery()
            ) {

                if (rs.next()) {
                    return new Settings(rs.getInt(1), rs.getInt(2)).clamped();
                }

            }

        } catch (SQLException e) {

            AppLogger.error("Couldn't read the settings: " + e.getMessage());

        }

        return Settings.DEFAULTS;

    }

    /** Saves the account's settings, pulled into the allowed ranges. */
    public Settings save(int userId, Settings settings) throws SQLException {

        Settings values = settings.clamped();

        String sql = """
                INSERT INTO user_settings (user_id, auto_lock_minutes, clipboard_clear_seconds, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                    auto_lock_minutes = excluded.auto_lock_minutes,
                    clipboard_clear_seconds = excluded.clipboard_clear_seconds,
                    updated_at = CURRENT_TIMESTAMP
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setInt(1, userId);
            statement.setInt(2, values.autoLockMinutes());
            statement.setInt(3, values.clipboardClearSeconds());

            statement.executeUpdate();

        }

        return values;

    }

    // =====================================================
    // THEME
    // =====================================================

    /** The account's theme (LIGHT or DARK), or null if it never chose one. Never fails: a read error gives null. */
    public String theme(int userId) {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT theme FROM user_settings WHERE user_id = ?")
        ) {

            statement.setInt(1, userId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }

        } catch (SQLException e) {

            AppLogger.error("Couldn't read the theme", e);

            return null;

        }

    }

    /** Saves the account's theme; its other settings stay as they are. */
    public void saveTheme(int userId, String theme) throws SQLException {

        Settings current = load(userId);

        String sql = """
                INSERT INTO user_settings (user_id, auto_lock_minutes, clipboard_clear_seconds, theme, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(user_id) DO UPDATE SET
                    theme = excluded.theme,
                    updated_at = CURRENT_TIMESTAMP
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setInt(1, userId);
            statement.setInt(2, current.autoLockMinutes());
            statement.setInt(3, current.clipboardClearSeconds());
            statement.setString(4, theme);

            statement.executeUpdate();

        }

    }

    /** The theme the sign-in screen shows: the one the last account to sign in used, or null. Never fails. */
    public String appTheme() {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT value FROM app_settings WHERE key = 'theme'");
                ResultSet rs = statement.executeQuery()
        ) {

            return rs.next() ? rs.getString(1) : null;

        } catch (SQLException e) {

            AppLogger.error("Couldn't read the sign-in screen's theme", e);

            return null;

        }

    }

    public void saveAppTheme(String theme) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO app_settings (key, value) VALUES ('theme', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
        ) {

            statement.setString(1, theme);

            statement.executeUpdate();

        }

    }

    /** Minutes without mouse or keyboard use before the signed-in account's vault locks itself. */
    public int autoLockMinutes() {

        return load(SessionManager.currentUserId()).autoLockMinutes();

    }

    /** Seconds before a copied password is cleared from the clipboard, for the signed-in account. */
    public int clipboardClearSeconds() {

        return load(SessionManager.currentUserId()).clipboardClearSeconds();

    }

}
