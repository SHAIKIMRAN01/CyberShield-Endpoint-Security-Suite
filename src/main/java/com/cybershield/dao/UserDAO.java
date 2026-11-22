package com.cybershield.dao;

import com.cybershield.database.DatabaseManager;
import com.cybershield.models.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class UserDAO {

    // SQLite's CURRENT_TIMESTAMP format (UTC).
    private static final DateTimeFormatter DB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Save User
    public boolean saveUser(User user) {

        String sql = """
                INSERT INTO users
                (
                    username,
                    email,
                    password_hash,
                    active
                )
                VALUES
                (
                    ?, ?, ?, ?
                )
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setString(1, user.getUsername());
            statement.setString(2, user.getEmail());
            statement.setString(3, user.getPasswordHash());
            statement.setInt(4, user.isActive() ? 1 : 0);

            return statement.executeUpdate() > 0;

        } catch (SQLException e) {

            e.printStackTrace();
            return false;

        }
    }

    // Find User By Username
    public User findByUsername(String username) {

        return findOne("SELECT * FROM users WHERE username = ?", username);

    }

    /** Registration uses this, so "Imran" can't be registered next to "imran". */
    public User findByUsernameIgnoringCase(String username) {

        return findOne("SELECT * FROM users WHERE username = ? COLLATE NOCASE", username);

    }

    // Find User By Email (emails are compared without regard to case)
    public User findByEmail(String email) {

        return findOne("SELECT * FROM users WHERE email = ? COLLATE NOCASE", email);

    }

    public User findById(int id) {

        return findOne("SELECT * FROM users WHERE id = ?", id);

    }

    // Check Username Exists
    public boolean existsUsername(String username) {

        return findByUsername(username) != null;

    }

    // Check Email Exists
    public boolean existsEmail(String email) {

        return findByEmail(email) != null;

    }

    // Update Last Login
    public boolean updateLastLogin(int userId) {

        return update("UPDATE users SET last_login = CURRENT_TIMESTAMP WHERE id = ?", userId);

    }

    public boolean updatePasswordHash(int userId, String passwordHash) {

        return update("UPDATE users SET password_hash = ? WHERE id = ?", passwordHash, userId);

    }

    // Delete User
    public boolean deleteUser(int id) {

        return update("DELETE FROM users WHERE id = ?", id);

    }

    private User findOne(String sql, Object parameter) {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setObject(1, parameter);

            try (ResultSet resultSet = statement.executeQuery()) {

                return resultSet.next() ? map(resultSet) : null;

            }

        } catch (SQLException e) {

            e.printStackTrace();

        }

        return null;

    }

    private boolean update(String sql, Object... parameters) {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }

            return statement.executeUpdate() > 0;

        } catch (SQLException e) {

            e.printStackTrace();

        }

        return false;

    }

    private static User map(ResultSet resultSet) throws SQLException {

        User user = new User();

        user.setId(resultSet.getInt("id"));
        user.setUsername(resultSet.getString("username"));
        user.setEmail(resultSet.getString("email"));
        user.setPasswordHash(resultSet.getString("password_hash"));
        user.setCreatedAt(time(resultSet.getString("created_at")));
        user.setLastLogin(time(resultSet.getString("last_login")));
        user.setActive(resultSet.getInt("active") == 1);

        return user;

    }

    /** A database time (UTC), or null. */
    private static LocalDateTime time(String value) {

        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDateTime.parse(value, DB_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }

    }

}
