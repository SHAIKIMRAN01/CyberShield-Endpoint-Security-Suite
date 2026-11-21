package com.cybershield.files;

import com.cybershield.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Database index of protected items and their encrypted files.
 * Unlike the older DAOs, these methods throw on failure, so the file
 * vault can keep the database and the files on disk in step.
 */
public class FileVaultDAO {

    public record ItemRow(String id, String info, String createdAt, int fileCount) {
    }

    public void insertItem(String id, int userId, String info) throws SQLException {

        update("INSERT INTO protected_items (id, user_id, info) VALUES (?, ?, ?)", id, userId, info);

    }

    public void updateItemInfo(String id, String info) throws SQLException {

        update("UPDATE protected_items SET info = ? WHERE id = ?", info, id);

    }

    /** Removes an item and its file records together. */
    public void deleteItem(String id) throws SQLException {

        try (Connection connection = DatabaseManager.getConnection()) {

            connection.setAutoCommit(false);

            try {

                execute(connection, "DELETE FROM protected_files WHERE item_id = ?", id);

                execute(connection, "DELETE FROM protected_items WHERE id = ?", id);

                connection.commit();

            } catch (SQLException e) {

                connection.rollback();

                throw e;

            }

        }

    }

    public void insertFile(String id, String itemId) throws SQLException {

        update("INSERT INTO protected_files (id, item_id) VALUES (?, ?)", id, itemId);

    }

    public void deleteFile(String id) throws SQLException {

        update("DELETE FROM protected_files WHERE id = ?", id);

    }

    public List<String> fileIds(String itemId) throws SQLException {

        List<String> ids = new ArrayList<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM protected_files WHERE item_id = ? ORDER BY rowid")
        ) {

            statement.setString(1, itemId);

            try (ResultSet rs = statement.executeQuery()) {

                while (rs.next()) {
                    ids.add(rs.getString(1));
                }

            }

        }

        return ids;

    }

    /** Newest first, each with the number of encrypted files it has in the store. */
    public List<ItemRow> items() throws SQLException {

        String sql = """
                SELECT i.id,
                       i.info,
                       i.created_at,
                       (SELECT COUNT(*) FROM protected_files f WHERE f.item_id = i.id) AS file_count
                FROM protected_items i
                ORDER BY i.created_at DESC, i.rowid DESC
                """;

        List<ItemRow> rows = new ArrayList<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()
        ) {

            while (rs.next()) {

                rows.add(new ItemRow(
                        rs.getString("id"),
                        rs.getString("info"),
                        rs.getString("created_at"),
                        rs.getInt("file_count")
                ));

            }

        }

        return rows;

    }

    private static void update(String sql, Object... parameters) throws SQLException {

        try (Connection connection = DatabaseManager.getConnection()) {

            execute(connection, sql, parameters);

        }

    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }

            statement.executeUpdate();

        }

    }

}
