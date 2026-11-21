package com.cybershield.dao;

import com.cybershield.database.DatabaseManager;
import com.cybershield.logger.AppLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads the single row of the settings table, falling back to the schema's defaults. */
public class SettingsDAO {

    public static final int DEFAULT_AUTO_LOCK_MINUTES = 5;

    /** Minutes without mouse or keyboard use before the vault locks itself. */
    public int autoLockMinutes() {

        String sql = "SELECT auto_lock_minutes FROM settings WHERE id = 1";

        try (
                Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()
        ) {

            if (rs.next() && rs.getInt(1) > 0) {
                return rs.getInt(1);
            }

        } catch (SQLException e) {

            AppLogger.error("Couldn't read the auto-lock setting: " + e.getMessage());

        }

        return DEFAULT_AUTO_LOCK_MINUTES;

    }

}
