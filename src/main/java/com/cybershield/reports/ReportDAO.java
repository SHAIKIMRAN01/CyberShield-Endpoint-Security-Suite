package com.cybershield.reports;

import com.cybershield.database.DatabaseManager;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** The reports each account has made (the reports table). Times are UTC, like the rest of the database. */
public class ReportDAO {

    /** @param path where the PDF was saved; the user may have moved or deleted it since */
    public record Row(long id, String name, String createdAt, String type, String path) {

        public boolean fileExists() {

            try {
                return path != null && Files.isRegularFile(Path.of(path));
            } catch (InvalidPathException e) {
                return false;
            }

        }

    }

    public long insert(int userId, String name, String type, String path) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO reports (user_id, report_name, report_type, file_path) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)
        ) {

            statement.setInt(1, userId);
            statement.setString(2, name);
            statement.setString(3, type);
            statement.setString(4, path);

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }

        }

    }

    /** The account's reports, newest first. */
    public List<Row> forUser(int userId) throws SQLException {

        List<Row> rows = new ArrayList<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, report_name, generated_at, report_type, file_path
                        FROM reports
                        WHERE user_id = ?
                        ORDER BY generated_at DESC, id DESC
                        """)
        ) {

            statement.setInt(1, userId);

            try (ResultSet rs = statement.executeQuery()) {

                while (rs.next()) {
                    rows.add(new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }

            }

        }

        return rows;

    }

    /** Removes one of the account's reports from the list. Another account's id does nothing. */
    public boolean delete(int userId, long id) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM reports WHERE id = ? AND user_id = ?")
        ) {

            statement.setLong(1, id);
            statement.setInt(2, userId);

            return statement.executeUpdate() > 0;

        }

    }

}
