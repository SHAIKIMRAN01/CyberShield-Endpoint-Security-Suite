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


public class PasswordDAO {

    public boolean save(PasswordEntry entry) {

        String sql = """
                INSERT INTO password_entries
                (
                    user_id,
                    website,
                    username,
                    password,
                    notes
                )
                VALUES
                (
                    ?, ?, ?, ?, ?
                )
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setInt(1, entry.getUserId());
            statement.setString(2, entry.getWebsite());
            statement.setString(3, entry.getUsername());
            statement.setString(4, entry.getPassword());
            statement.setString(5, entry.getNotes());

            return statement.executeUpdate() > 0;

        }

        catch (SQLException e) {

            e.printStackTrace();

        }

        return false;
    }

    public List<PasswordEntry> getAllPasswords() {

        List<PasswordEntry> list = new ArrayList<>();

        String sql = """
                SELECT *
                FROM password_entries
                ORDER BY id DESC
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()
        ) {

            while (rs.next()) {

                PasswordEntry entry = new PasswordEntry();

                entry.setId(rs.getInt("id"));
                entry.setUserId(rs.getInt("user_id"));
                entry.setWebsite(rs.getString("website"));
                entry.setUsername(rs.getString("username"));
                entry.setPassword(rs.getString("password"));
                entry.setNotes(rs.getString("notes"));
                entry.setUpdatedAt(rs.getString("updated_at"));

                list.add(entry);

            }

        }

        catch (SQLException e) {

            e.printStackTrace();

        }

        return list;

    }
    public boolean update(PasswordEntry entry) {

    String sql = """
        UPDATE password_entries
        SET website=?,
            username=?,
            password=?,
            notes=?,
            updated_at=CURRENT_TIMESTAMP
        WHERE id=?
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

        return statement.executeUpdate() > 0;

    } catch (SQLException e) {

        e.printStackTrace();

    }

    return false;

}

public boolean delete(int id) {

    String sql = """
        DELETE FROM password_entries
        WHERE id=?
        """;

    try (
            Connection connection = DatabaseManager.getConnection();
            PreparedStatement statement =
                    connection.prepareStatement(sql)
    ) {

        statement.setInt(1, id);

        return statement.executeUpdate() > 0;

    } catch (SQLException e) {

        e.printStackTrace();

    }

    return false;

}

    /**
     * Replaces every stored password with encryptor's output, using the
     * caller's connection and transaction. Used once, when the vault is
     * created, to encrypt entries that were saved as plain text before
     * encryption existed. Returns the number of entries changed.
     */
    public int encryptStoredPasswords(
            Connection connection,
            UnaryOperator<String> encryptor
    ) throws SQLException {

        Map<Integer, String> stored = new LinkedHashMap<>();

        try (
                PreparedStatement select = connection.prepareStatement(
                        "SELECT id, password FROM password_entries");
                ResultSet rs = select.executeQuery()
        ) {

            while (rs.next()) {

                stored.put(rs.getInt("id"), rs.getString("password"));

            }

        }

        if (stored.isEmpty()) {
            return 0;
        }

        try (
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE password_entries SET password = ? WHERE id = ?")
        ) {

            for (Map.Entry<Integer, String> row : stored.entrySet()) {

                update.setString(1, encryptor.apply(row.getValue()));
                update.setInt(2, row.getKey());
                update.addBatch();

            }

            update.executeBatch();

        }

        return stored.size();

    }

}