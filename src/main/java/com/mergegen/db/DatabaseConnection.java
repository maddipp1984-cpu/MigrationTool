package com.mergegen.db;

import com.mergegen.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DatabaseConnection implements AutoCloseable {

    private final Connection connection;

    public DatabaseConnection(DatabaseConfig config) throws SQLException {
        this.connection = DriverManager.getConnection(
            config.getUrl(),
            config.getUser(),
            config.getPassword()
        );
        this.connection.setReadOnly(true);
        System.out.println("Verbunden (read-only) mit: " + config.getUrl());
    }

    public Connection get() {
        return connection;
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println("Fehler beim Schliessen der Verbindung: " + e.getMessage());
        }
    }
}
