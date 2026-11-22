package com.cybershield.password;

import com.cybershield.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Saved logins. Every query is limited to one vault, so an account only ever reaches its own entries. */
public class PasswordDAO {

    public boolean save(PasswordEntry entry) {

        String sql = """
                INSERT INTO password_entries
                (
                    user_id,
                    vault_id,
                    website,
                    username,
                    password,
                    notes
                )
                VALUES
                (
                    ?, ?, ?, ?, ?, ?
                )
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setInt(1, entry.getUserId());
            statement.setInt(2, entry.getVaultId());
            statement.setString(3, entry.getWebsite());
            statement.setString(4, entry.getUsername());
            statement.setString(5, entry.getPassword());
            statement.setString(6, entry.getNotes());

            return statement.executeUpdate() > 0;

        }

        catch (SQLException e) {

            e.printStackTrace();

        }

        return false;
    }

    /** One vault's entries, newest first. */
    public List<PasswordEntry> getPasswords(int vaultId) {

        List<PasswordEntry> list = new ArrayList<>();

        String sql = """
                SELECT *
                FROM password_entries
                WHERE vault_id = ?
                ORDER BY id DESC
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setInt(1, vaultId);

            try (ResultSet rs = statement.executeQuery()) {

                while (rs.next()) {

                    PasswordEntry entry = new PasswordEntry();

                    entry.setId(rs.getInt("id"));
                    entry.setUserId(rs.getInt("user_id"));
                    entry.setVaultId(rs.getInt("vault_id"));
                    entry.setWebsite(rs.getString("website"));
                    entry.setUsername(rs.getString("username"));
                    entry.setPassword(rs.getString("password"));
                    entry.setNotes(rs.getString("notes"));
                    entry.setUpdatedAt(rs.getString("updated_at"));

                    list.add(entry);

                }

            }

        }

        catch (SQLException e) {

            e.printStackTrace();

        }

        return list;

    }

    /** How many entries a vault holds. Reading the count needs no key. */
    public int count(int vaultId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM password_entries WHERE vault_id = ?")
        ) {

            statement.setInt(1, vaultId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }

        }

    }

    /** Updates an entry of the given vault. An id from another vault changes nothing. */
    public boolean update(PasswordEntry entry) {

        String sql = """
                UPDATE password_entries
                SET website=?,
                    username=?,
                    password=?,
                    notes=?,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND vault_id=?
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setString(1, entry.getWebsite());
            statement.setString(2, entry.getUsername());
            statement.setString(3, entry.getPassword());
            statement.setString(4, entry.getNotes());
            statement.setInt(5, entry.getId());
            statement.setInt(6, entry.getVaultId());

            return statement.executeUpdate() > 0;

        } catch (SQLException e) {

            e.printStackTrace();

        }

        return false;

    }

    public boolean delete(int id, int vaultId) {

        String sql = """
                DELETE FROM password_entries
                WHERE id=? AND vault_id=?
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setInt(1, id);
            statement.setInt(2, vaultId);

            return statement.executeUpdate() > 0;

        } catch (SQLException e) {

            e.printStackTrace();

        }

        return false;

    }

    /**
     * The very first builds saved passwords as plain text, before any vault existed.
     * When an account then makes its vault, its plain-text entries are encrypted with
     * the new key and moved into it, in the caller's transaction. Returns how many.
     */
    public int encryptPlainTextPasswords(
            Connection connection,
            int userId,
            int vaultId,
            UnaryOperator<String> encryptor
    ) throws SQLException {

        Map<Integer, String> stored = new LinkedHashMap<>();

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, password FROM password_entries WHERE vault_id IS NULL AND user_id = ?")) {

            select.setInt(1, userId);

            try (ResultSet rs = select.executeQuery()) {

                while (rs.next()) {
                    stored.put(rs.getInt("id"), rs.getString("password"));
                }

            }

        }

        if (stored.isEmpty()) {
            return 0;
        }

        try (
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE password_entries SET password = ?, vault_id = ? WHERE id = ?")
        ) {

            for (Map.Entry<Integer, String> row : stored.entrySet()) {

                update.setString(1, encryptor.apply(row.getValue()));
                update.setInt(2, vaultId);
                update.setInt(3, row.getKey());
                update.addBatch();

            }

            update.executeBatch();

        }

        return stored.size();

    }

}
