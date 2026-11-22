package com.cybershield.dao;

import com.cybershield.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Sign-in attempts of each account (the login_history table). Times are UTC,
 * as SQLite's CURRENT_TIMESTAMP writes them; DisplayFormat shows them in local time.
 */
public class LoginHistoryDAO {

    public static final String SUCCESS = "SUCCESS";

    public static final String FAILED = "FAILED";

    /** Refused without checking the password, because recent failures make the account wait. */
    public static final String LOCKED = "LOCKED";

    public record Entry(long id, String signedInAt, String signedOutAt, String device, String status) {
    }

    /** Failed attempts since the last successful sign-in, within the look-back window. */
    public record Failures(int count, long lastFailureEpochSeconds) {
    }

    /** Records an attempt and returns its row id. */
    public long record(int userId, String status, String device) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO login_history (user_id, device_name, status) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)
        ) {

            statement.setInt(1, userId);
            statement.setString(2, device);
            statement.setString(3, status);

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }

        }

    }

    public void recordSignOut(long id) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE login_history SET logout_time = CURRENT_TIMESTAMP WHERE id = ? AND logout_time IS NULL")
        ) {

            statement.setLong(1, id);

            statement.executeUpdate();

        }

    }

    /** The account's latest attempts, newest first. */
    public List<Entry> recent(int userId, int limit) throws SQLException {

        List<Entry> entries = new ArrayList<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, login_time, logout_time, device_name, status
                        FROM login_history
                        WHERE user_id = ?
                        ORDER BY login_time DESC, id DESC
                        LIMIT ?
                        """)
        ) {

            statement.setInt(1, userId);
            statement.setInt(2, limit);

            try (ResultSet rs = statement.executeQuery()) {

                while (rs.next()) {
                    entries.add(new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }

            }

        }

        return entries;

    }

    /** The successful sign-in before the current one, or null: shown as "last sign-in" on the home screen. */
    public Entry previousSuccess(int userId, long currentId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, login_time, logout_time, device_name, status
                        FROM login_history
                        WHERE user_id = ? AND status = 'SUCCESS' AND id <> ?
                        ORDER BY login_time DESC, id DESC
                        LIMIT 1
                        """)
        ) {

            statement.setInt(1, userId);
            statement.setLong(2, currentId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? new Entry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) : null;
            }

        }

    }

    /** Failed attempts after the last success and within the last lookBackSeconds. */
    public Failures failuresSinceLastSuccess(int userId, int lookBackSeconds) throws SQLException {

        String sql = """
                SELECT COUNT(*), COALESCE(MAX(CAST(strftime('%s', login_time) AS INTEGER)), 0)
                FROM login_history
                WHERE user_id = ?
                  AND status = 'FAILED'
                  AND login_time >= datetime('now', ?)
                  AND id > COALESCE((SELECT MAX(id) FROM login_history WHERE user_id = ? AND status = 'SUCCESS'), 0)
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setInt(1, userId);
            statement.setString(2, "-" + lookBackSeconds + " seconds");
            statement.setInt(3, userId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? new Failures(rs.getInt(1), rs.getLong(2)) : new Failures(0, 0);
            }

        }

    }

    /** Failed attempts since the given sign-in (the one before the current), for the home screen. */
    public int failuresSince(int userId, long afterId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM login_history WHERE user_id = ? AND status IN ('FAILED', 'LOCKED') AND id > ?")
        ) {

            statement.setInt(1, userId);
            statement.setLong(2, afterId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        }

    }

}
