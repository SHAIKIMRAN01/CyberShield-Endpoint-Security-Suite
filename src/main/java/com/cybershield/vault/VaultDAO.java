package com.cybershield.vault;

import com.cybershield.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stores each account's vault (user_vaults), and reads the shared vault that
 * databases from before per-account vaults still have (the one-row vault table).
 * Methods throw on failure so VaultService can keep its changes in one transaction.
 */
public class VaultDAO {

    /** The shared vault from before accounts had their own. Its key came straight from the password. */
    public record SharedVault(byte[] salt, String verificationData) {
    }

    // Tables whose rows belong to a vault. vault_id NULL means the shared vault.
    private static final String[] VAULT_TABLES = {"password_entries", "secure_notes", "protected_items"};

    public Vault find(int userId) throws SQLException {

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM user_vaults WHERE user_id = ?")
        ) {

            statement.setInt(1, userId);

            try (ResultSet rs = statement.executeQuery()) {

                return rs.next() ? map(rs) : null;

            }

        }

    }

    /** Saves a new vault in the caller's transaction and returns it with its id. */
    public Vault insert(Connection connection, Vault vault) throws SQLException {

        String sql = """
                INSERT INTO user_vaults
                    (user_id, vault_salt, kdf_salt, kdf_iterations, wrapped_key, verification_data)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            statement.setInt(1, vault.userId());
            statement.setBytes(2, vault.vaultSalt());
            statement.setBytes(3, vault.kdfSalt());
            statement.setInt(4, vault.kdfIterations());
            statement.setBytes(5, vault.wrappedKey());
            statement.setString(6, vault.verificationData());

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {

                if (!keys.next()) {
                    throw new SQLException("The new vault got no id.");
                }

                int id = keys.getInt(1);

                return new Vault(id, vault.userId(), vault.vaultSalt(), vault.kdfSalt(), vault.kdfIterations(),
                        vault.wrappedKey(), vault.verificationData(), vault.createdAt(), vault.passwordChangedAt());

            }

        }

    }

    /** Replaces the wrapped key. passwordChanged also records the time, for the Settings screen. */
    public void updateKey(Connection connection, Vault vault, boolean passwordChanged) throws SQLException {

        String sql = passwordChanged
                ? "UPDATE user_vaults SET kdf_salt = ?, kdf_iterations = ?, wrapped_key = ?, password_changed_at = CURRENT_TIMESTAMP WHERE id = ?"
                : "UPDATE user_vaults SET kdf_salt = ?, kdf_iterations = ?, wrapped_key = ? WHERE id = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setBytes(1, vault.kdfSalt());
            statement.setInt(2, vault.kdfIterations());
            statement.setBytes(3, vault.wrappedKey());
            statement.setInt(4, vault.id());

            if (statement.executeUpdate() != 1) {
                throw new SQLException("The vault to update wasn't found.");
            }

        }

    }

    /** The shared vault, or null if this database never had one or it has been moved into an account. */
    public SharedVault findShared() throws SQLException {

        try (Connection connection = DatabaseManager.getConnection()) {

            if (!sharedTableExists(connection)) {
                return null;
            }

            try (
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT salt, verification_data FROM vault WHERE id = 1");
                    ResultSet rs = statement.executeQuery()
            ) {

                return rs.next() ? new SharedVault(rs.getBytes(1), rs.getString(2)) : null;

            }

        }

    }

    /** Accounts that still have rows in the shared vault. Rows of deleted accounts don't count. */
    public Set<Integer> sharedOwners() throws SQLException {

        Set<Integer> owners = new LinkedHashSet<>();

        try (Connection connection = DatabaseManager.getConnection()) {

            for (String table : VAULT_TABLES) {

                try (
                        PreparedStatement statement = connection.prepareStatement(
                                "SELECT DISTINCT user_id FROM " + table
                                        + " WHERE vault_id IS NULL AND user_id IN (SELECT id FROM users)");
                        ResultSet rs = statement.executeQuery()
                ) {

                    while (rs.next()) {
                        owners.add(rs.getInt(1));
                    }

                }

            }

        }

        return owners;

    }

    /** Moves one account's rows from the shared vault into its own vault. Returns how many moved. */
    public int moveSharedRows(Connection connection, int userId, int vaultId) throws SQLException {

        int moved = 0;

        for (String table : VAULT_TABLES) {

            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + table + " SET vault_id = ? WHERE vault_id IS NULL AND user_id = ?")) {

                statement.setInt(1, vaultId);
                statement.setInt(2, userId);

                moved += statement.executeUpdate();

            }

        }

        return moved;

    }

    /** True while an existing account still has rows in the shared vault. */
    public boolean sharedRowsLeft(Connection connection) throws SQLException {

        for (String table : VAULT_TABLES) {

            try (
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT 1 FROM " + table + " WHERE vault_id IS NULL AND user_id IN (SELECT id FROM users) LIMIT 1");
                    ResultSet rs = statement.executeQuery()
            ) {

                if (rs.next()) {
                    return true;
                }

            }

        }

        return false;

    }

    /** Removes the shared vault once nobody needs it any more. */
    public void deleteShared(Connection connection) throws SQLException {

        if (sharedTableExists(connection)) {

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM vault");
            }

        }

    }

    private static boolean sharedTableExists(Connection connection) throws SQLException {

        try (
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'vault'");
                ResultSet rs = statement.executeQuery()
        ) {

            return rs.next();

        }

    }

    private static Vault map(ResultSet rs) throws SQLException {

        return new Vault(
                rs.getInt("id"),
                rs.getInt("user_id"),
                rs.getBytes("vault_salt"),
                rs.getBytes("kdf_salt"),
                rs.getInt("kdf_iterations"),
                rs.getBytes("wrapped_key"),
                rs.getString("verification_data"),
                rs.getString("created_at"),
                rs.getString("password_changed_at")
        );

    }

}
