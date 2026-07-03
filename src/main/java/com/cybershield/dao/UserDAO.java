package com.cybershield.dao;

import com.cybershield.database.DatabaseManager;
import com.cybershield.models.User;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class UserDAO {

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

        String sql = """
                SELECT *
                FROM users
                WHERE username = ?
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setString(1, username);

            ResultSet resultSet = statement.executeQuery();

            if (resultSet.next()) {

                User user = new User();

                user.setId(resultSet.getInt("id"));
                user.setUsername(resultSet.getString("username"));
                user.setEmail(resultSet.getString("email"));
                user.setPasswordHash(resultSet.getString("password_hash"));
                user.setActive(resultSet.getInt("active") == 1);

                return user;
            }

        } catch (SQLException e) {

            e.printStackTrace();

        }

        return null;
    }

    // Find User By Email
  public User findByEmail(String email) {

    String sql = """
            SELECT *
            FROM users
            WHERE email = ?
            """;

    try (
            Connection connection = DatabaseManager.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
    ) {

        statement.setString(1, email);

        ResultSet resultSet = statement.executeQuery();

        if (resultSet.next()) {

            User user = new User();

            user.setId(resultSet.getInt("id"));
            user.setUsername(resultSet.getString("username"));
            user.setEmail(resultSet.getString("email"));
            user.setPasswordHash(resultSet.getString("password_hash"));
            user.setActive(resultSet.getInt("active") == 1);

            return user;
        }

    } catch (SQLException e) {

        e.printStackTrace();

    }

    return null;
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

        String sql = """
                UPDATE users
                SET last_login = CURRENT_TIMESTAMP
                WHERE id = ?
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setInt(1, userId);

            return statement.executeUpdate() > 0;

        } catch (SQLException e) {

            e.printStackTrace();

        }

        return false;
    }

    // Delete User
    public boolean deleteUser(int id) {

        String sql = """
                DELETE FROM users
                WHERE id = ?
                """;

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {

            statement.setInt(1, id);

            return statement.executeUpdate() > 0;

        } catch (SQLException e) {

            e.printStackTrace();

        }

        return false;
    }

}