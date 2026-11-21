package com.cybershield.vault;

import com.cybershield.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class VaultDAO {

    public boolean vaultExists() {

        String sql = """
                SELECT COUNT(*)
                FROM vault
                """;

        try (
                Connection connection =
                        DatabaseManager.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(sql);

                ResultSet rs =
                        statement.executeQuery()
        ) {

            return rs.next() && rs.getInt(1) > 0;

        }

        catch (SQLException e) {

            e.printStackTrace();

        }

        return false;

    }

    /**
     * Saves the vault using the caller's connection, so it can share a
     * transaction with other changes. The caller commits or rolls back.
     */
    public boolean saveVault(
            Connection connection,
            Vault vault
    ) throws SQLException {

        String sql = """
                INSERT INTO vault
                (
                    id,
                    salt,
                    verification_data
                )
                VALUES
                (
                    1, ?, ?
                )
                """;

        try (
                PreparedStatement statement =
                        connection.prepareStatement(sql)
        ) {

            statement.setBytes(
                    1,
                    vault.getSalt()
            );

            statement.setString(
                    2,
                    vault.getVerificationData()
            );

            return statement.executeUpdate() > 0;

        }

    }

    public Vault getVault() {

        String sql = """
                SELECT *
                FROM vault
                WHERE id = 1
                """;

        try (
                Connection connection =
                        DatabaseManager.getConnection();

                PreparedStatement statement =
                        connection.prepareStatement(sql);

                ResultSet rs =
                        statement.executeQuery()
        ) {

            if (rs.next()) {

                Vault vault = new Vault();

                vault.setId(
                        rs.getInt("id")
                );

                vault.setSalt(
                        rs.getBytes("salt")
                );

                vault.setVerificationData(
                        rs.getString("verification_data")
                );

                vault.setCreatedAt(
                        rs.getString("created_at")
                );

                return vault;

            }

        }

        catch (SQLException e) {

            e.printStackTrace();

        }

        return null;

    }

}