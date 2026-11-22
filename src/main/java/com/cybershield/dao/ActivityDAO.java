package com.cybershield.dao;

import com.cybershield.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** What each sign-in session did (the activity_log table). Times are UTC, like the rest of the database. */
public class ActivityDAO {

    /** @param detail encrypted with the vault given by vaultId, or plain when vaultId is 0; may be null */
    public record Event(long id, long sessionId, String occurredAt, String action, String detail, int vaultId) {
    }

    public void insert(int userId, long sessionId, String action, String detail, int vaultId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO activity_log (user_id, session_id, action, detail, vault_id) VALUES (?, ?, ?, ?, ?)")
        ) {

            statement.setInt(1, userId);

            if (sessionId > 0) {
                statement.setLong(2, sessionId);
            } else {
                statement.setNull(2, java.sql.Types.INTEGER);
            }

            statement.setString(3, action);
            statement.setString(4, detail);

            if (vaultId > 0) {
                statement.setInt(5, vaultId);
            } else {
                statement.setNull(5, java.sql.Types.INTEGER);
            }

            statement.executeUpdate();

        }

    }

    /** One session's events, oldest first. */
    public List<Event> forSession(int userId, long sessionId) throws SQLException {

        return query("SELECT * FROM activity_log WHERE user_id = ? AND session_id = ? ORDER BY occurred_at, id", userId, sessionId);

    }

    /** The account's latest events, newest first (for reports). */
    public List<Event> recent(int userId, int limit) throws SQLException {

        return query("SELECT * FROM activity_log WHERE user_id = ? ORDER BY occurred_at DESC, id DESC LIMIT ?", userId, limit);

    }

    public int countSince(int userId, String action, int days) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM activity_log WHERE user_id = ? AND action = ? AND occurred_at >= datetime('now', ?)")
        ) {

            statement.setInt(1, userId);
            statement.setString(2, action);
            statement.setString(3, "-" + days + " days");

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        }

    }

    /** How often each action happened in the last days, most frequent first (for reports). */
    public java.util.Map<String, Integer> countsSince(int userId, int days) throws SQLException {

        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT action, COUNT(*) AS times
                        FROM activity_log
                        WHERE user_id = ? AND occurred_at >= datetime('now', ?)
                        GROUP BY action
                        ORDER BY times DESC, action
                        """)
        ) {

            statement.setInt(1, userId);
            statement.setString(2, "-" + days + " days");

            try (ResultSet rs = statement.executeQuery()) {

                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getInt(2));
                }

            }

        }

        return counts;

    }

    /** The account's newest event of one kind, or null. */
    public Event latest(int userId, String action) throws SQLException {

        List<Event> found = query("SELECT * FROM activity_log WHERE user_id = ? AND action = ? ORDER BY occurred_at DESC, id DESC LIMIT 1",
                userId, action);

        return found.isEmpty() ? null : found.get(0);

    }

    /** Removes the account's events, except those of the session keepSessionId. */
    public int clear(int userId, long keepSessionId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM activity_log WHERE user_id = ? AND (session_id IS NULL OR session_id <> ?)")
        ) {

            statement.setInt(1, userId);
            statement.setLong(2, keepSessionId);

            return statement.executeUpdate();

        }

    }

    /** Removes everyone's events older than the given number of days. */
    public int purgeOlderThan(int days) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM activity_log WHERE occurred_at < datetime('now', ?)")
        ) {

            statement.setString(1, "-" + days + " days");

            return statement.executeUpdate();

        }

    }

    private List<Event> query(String sql, Object... parameters) throws SQLException {

        List<Event> events = new ArrayList<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }

            try (ResultSet rs = statement.executeQuery()) {

                while (rs.next()) {
                    events.add(new Event(rs.getLong("id"), rs.getLong("session_id"), rs.getString("occurred_at"),
                            rs.getString("action"), rs.getString("detail"), rs.getInt("vault_id")));
                }

            }

        }

        return events;

    }

}
