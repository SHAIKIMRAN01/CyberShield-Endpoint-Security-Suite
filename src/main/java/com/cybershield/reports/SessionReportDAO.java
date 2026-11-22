package com.cybershield.reports;

import com.cybershield.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The PDF of each sign-in (the session_reports table), stored encrypted. Times are UTC. */
public class SessionReportDAO {

    /** A stored report. The PDF itself is read separately, only when it is wanted. */
    public record Row(long id, long sessionId, int vaultId, String signedInAt, String signedOutAt, String device,
                      int actions, String createdAt) {
    }

    /** Stores a report, unless the sign-in already has one (or had one that was deleted). */
    public boolean insert(int userId, int vaultId, long sessionId, String signedInAt, String signedOutAt, String device,
                          int actions, byte[] encryptedPdf) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT OR IGNORE INTO session_reports
                            (user_id, vault_id, session_id, signed_in_at, signed_out_at, device, actions, pdf)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
        ) {

            statement.setInt(1, userId);
            statement.setInt(2, vaultId);
            statement.setLong(3, sessionId);
            statement.setString(4, signedInAt);
            statement.setString(5, signedOutAt);
            statement.setString(6, device);
            statement.setInt(7, actions);
            statement.setBytes(8, encryptedPdf);

            return statement.executeUpdate() > 0;

        }

    }

    /** The account's reports that haven't been deleted, newest sign-in first. */
    public List<Row> forUser(int userId) throws SQLException {

        List<Row> rows = new ArrayList<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, session_id, vault_id, signed_in_at, signed_out_at, device, actions, created_at
                        FROM session_reports
                        WHERE user_id = ? AND pdf IS NOT NULL
                        ORDER BY signed_in_at DESC, id DESC
                        """)
        ) {

            statement.setInt(1, userId);

            try (ResultSet rs = statement.executeQuery()) {

                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getInt(7), rs.getString(8)));
                }

            }

        }

        return rows;

    }

    /** Every sign-in that has, or had, a report: those aren't made again. */
    public Set<Long> knownSessions(int userId) throws SQLException {

        Set<Long> sessions = new HashSet<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT session_id FROM session_reports WHERE user_id = ?")
        ) {

            statement.setInt(1, userId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    sessions.add(rs.getLong(1));
                }
            }

        }

        return sessions;

    }

    /** The encrypted PDF of one of the account's reports, or null if it was deleted or isn't theirs. */
    public byte[] pdf(int userId, long id) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT pdf FROM session_reports WHERE id = ? AND user_id = ?")
        ) {

            statement.setLong(1, id);
            statement.setInt(2, userId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }

        }

    }

    /** Deletes the PDF and keeps a marker, so it isn't made again. */
    public boolean markDeleted(int userId, long id) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE session_reports SET pdf = NULL, deleted_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND user_id = ? AND pdf IS NOT NULL
                        """)
        ) {

            statement.setLong(1, id);
            statement.setInt(2, userId);

            return statement.executeUpdate() > 0;

        }

    }

    public int markAllDeleted(int userId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE session_reports SET pdf = NULL, deleted_at = CURRENT_TIMESTAMP
                        WHERE user_id = ? AND pdf IS NOT NULL
                        """)
        ) {

            statement.setInt(1, userId);

            return statement.executeUpdate();

        }

    }

}
