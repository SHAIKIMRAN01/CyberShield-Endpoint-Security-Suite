package com.cybershield.database;

import com.cybershield.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class DatabaseManager {

    private DatabaseManager() {
    }

    public static Connection getConnection() throws SQLException {

        return DriverManager.getConnection(
                DatabaseConfig.DATABASE_URL);

    }

}