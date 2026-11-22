package com.cybershield.password;

import com.cybershield.logger.AppLogger;
import com.cybershield.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Saved logins, as stored: every text field already encrypted by PasswordService.
 * Every query is limited to one vault, so an account only ever reaches its own entries.
 */
public class PasswordDAO {

    public boolean save(PasswordEntry entry) {

        String sql = """
                INSERT INTO password_entries
                    (user_id, vault_id, website, username, password, notes, category, favourite, fields_encrypted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
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
            statement.setString(7, entry.getCategory());
            statement.setInt(8, entry.isFavourite() ? 1 : 0);

            return statement.executeUpdate() > 0;

        } catch (SQLException e) {

            AppLogger.error("Couldn't save a login", e);

        }

        return false;

    }

    /** One vault's entries, newest first, as stored. */
    public List<PasswordEntry> getPasswords(int vaultId) {

        List<PasswordEntry> list = new ArrayList<>();

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT * FROM password_entries WHERE vault_id = ? ORDER BY id DESC")
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
                    entry.setCategory(rs.getString("category"));
                    entry.setFavourite(rs.getInt("favourite") == 1);
                    entry.setFieldsEncrypted(rs.getInt("fields_encrypted") == 1);
                    entry.setUpdatedAt(rs.getString("updated_at"));

                    list.add(entry);

                }

            }

        } catch (SQLException e) {

            AppLogger.error("Couldn't read the saved logins", e);

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
                SET website = ?, username = ?, password = ?, notes = ?, category = ?, favourite = ?,
                    fields_encrypted = 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND vault_id = ?
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setString(1, entry.getWebsite());
            statement.setString(2, entry.getUsername());
            statement.setString(3, entry.getPassword());
            statement.setString(4, entry.getNotes());
            statement.setString(5, entry.getCategory());
            statement.setInt(6, entry.isFavourite() ? 1 : 0);
            statement.setInt(7, entry.getId());
            statement.setInt(8, entry.getVaultId());

            return statement.executeUpdate() > 0;

        } catch (SQLException e) {

            AppLogger.error("Couldn't change a login", e);

        }

        return false;

    }

    /** Marks or unmarks a favourite without touching anything else (or the last-updated time). */
    public boolean setFavourite(int id, int vaultId, boolean favourite) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE password_entries SET favourite = ? WHERE id = ? AND vault_id = ?")
        ) {

            statement.setInt(1, favourite ? 1 : 0);
            statement.setInt(2, id);
            statement.setInt(3, vaultId);

            return statement.executeUpdate() == 1;

        }

    }

    public boolean delete(int id, int vaultId) {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM password_entries WHERE id = ? AND vault_id = ?")
        ) {

            statement.setInt(1, id);
            statement.setInt(2, vaultId);

            return statement.executeUpdate() > 0;

        } catch (SQLException e) {

            AppLogger.error("Couldn't delete a login", e);

        }

        return false;

    }

    /**
     * Entries saved before every field was encrypted have their website, username,
     * notes and category in the clear. Encrypts them, in one transaction, and returns
     * how many entries changed. The passwords were already encrypted.
     */
    public int encryptPlainFields(int vaultId, UnaryOperator<String> encryptor) throws SQLException {

        try (Connection connection = DatabaseManager.getConnection()) {

            connection.setAutoCommit(false);

            List<String[]> plain = new ArrayList<>();

            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, website, username, notes, category FROM password_entries WHERE vault_id = ? AND COALESCE(fields_encrypted, 0) = 0")) {

                select.setInt(1, vaultId);

                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        plain.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)});
                    }
                }

            }

            if (plain.isEmpty()) {
                return 0;
            }

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE password_entries SET website = ?, username = ?, notes = ?, category = ?, fields_encrypted = 1 WHERE id = ?")) {

                for (String[] row : plain) {

                    update.setString(1, encryptor.apply(row[1] == null ? "" : row[1]));
                    update.setString(2, encryptor.apply(row[2] == null ? "" : row[2]));
                    update.setString(3, row[3] == null ? null : encryptor.apply(row[3]));
                    update.setString(4, row[4] == null ? null : encryptor.apply(row[4]));
                    update.setInt(5, Integer.parseInt(row[0]));
                    update.addBatch();

                }

                update.executeBatch();

                connection.commit();

            } catch (SQLException | RuntimeException e) {

                connection.rollback();

                throw e;

            }

            return plain.size();

        }

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

        List<Object[]> stored = new ArrayList<>();

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, password FROM password_entries WHERE vault_id IS NULL AND user_id = ?")) {

            select.setInt(1, userId);

            try (ResultSet rs = select.executeQuery()) {

                while (rs.next()) {
                    stored.add(new Object[] {rs.getInt("id"), rs.getString("password")});
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

            for (Object[] row : stored) {

                update.setString(1, encryptor.apply((String) row[1]));
                update.setInt(2, vaultId);
                update.setInt(3, (Integer) row[0]);
                update.addBatch();

            }

            update.executeBatch();

        }

        return stored.size();

    }

}
