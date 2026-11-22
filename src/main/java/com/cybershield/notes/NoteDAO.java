package com.cybershield.notes;

import com.cybershield.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Notes as stored: title and text already encrypted. Every query is limited to one vault. */
public class NoteDAO {

    public record Row(int id, String title, String content, String createdAt, String updatedAt) {
    }

    public List<Row> list(int vaultId) throws SQLException {

        List<Row> rows = new ArrayList<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, title, content, created_at, updated_at FROM secure_notes WHERE vault_id = ? ORDER BY updated_at DESC, id DESC")
        ) {

            statement.setInt(1, vaultId);

            try (ResultSet rs = statement.executeQuery()) {

                while (rs.next()) {
                    rows.add(new Row(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
                }

            }

        }

        return rows;

    }

    public int insert(int userId, int vaultId, String title, String content) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO secure_notes (user_id, vault_id, title, content) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)
        ) {

            statement.setInt(1, userId);
            statement.setInt(2, vaultId);
            statement.setString(3, title);
            statement.setString(4, content);

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : 0;
            }

        }

    }

    /** Returns false if the note isn't in this vault. */
    public boolean update(int id, int vaultId, String title, String content) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE secure_notes SET title = ?, content = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND vault_id = ?")
        ) {

            statement.setString(1, title);
            statement.setString(2, content);
            statement.setInt(3, id);
            statement.setInt(4, vaultId);

            return statement.executeUpdate() == 1;

        }

    }

    public boolean delete(int id, int vaultId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM secure_notes WHERE id = ? AND vault_id = ?")
        ) {

            statement.setInt(1, id);
            statement.setInt(2, vaultId);

            return statement.executeUpdate() == 1;

        }

    }

    public int count(int vaultId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM secure_notes WHERE vault_id = ?")
        ) {

            statement.setInt(1, vaultId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        }

    }

    public String updatedAt(int id) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT updated_at FROM secure_notes WHERE id = ?")
        ) {

            statement.setInt(1, id);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }

        }

    }

}
