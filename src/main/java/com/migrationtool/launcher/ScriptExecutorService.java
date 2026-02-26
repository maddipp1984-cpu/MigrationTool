package com.migrationtool.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Führt eine Liste von SQL/PL-SQL-Dateien in einer einzigen Transaktion
 * gegen die Ziel-Datenbank aus.
 * Bei jedem Fehler wird ein vollständiger Rollback durchgeführt.
 */
public class ScriptExecutorService {

    /**
     * @return true wenn alle Scripts erfolgreich ausgeführt und committed wurden
     */
    public boolean execute(List<Path> scripts, String url, String user, String password,
                           Consumer<String> logger) {
        try {
            Class.forName("oracle.jdbc.OracleDriver");
        } catch (ClassNotFoundException e) {
            logger.accept("FEHLER: Oracle JDBC-Treiber nicht gefunden.");
            return false;
        }

        Connection conn = null;
        try {
            conn = DriverManager.getConnection(url, user, password);
            conn.setAutoCommit(false);

            for (Path script : scripts) {
                String name = script.getFileName().toString();
                logger.accept("▶  " + name);

                String sql;
                try {
                    sql = prepare(Files.readString(script, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    logger.accept("   FEHLER beim Lesen: " + e.getMessage());
                    rollback(conn, logger);
                    return false;
                }

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                    logger.accept("   ✓  OK");
                } catch (SQLException e) {
                    logger.accept("   ✗  FEHLER: " + e.getMessage());
                    rollback(conn, logger);
                    return false;
                }
            }

            conn.commit();
            logger.accept("─────────────────────────────────────────");
            logger.accept("Alle Scripts erfolgreich ausgeführt. Commit.");
            return true;

        } catch (SQLException e) {
            logger.accept("FEHLER bei DB-Verbindung: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
    }

    private void rollback(Connection conn, Consumer<String> logger) {
        try {
            conn.rollback();
            logger.accept("Rollback durchgeführt.");
        } catch (SQLException e) {
            logger.accept("Rollback fehlgeschlagen: " + e.getMessage());
        }
    }

    /**
     * Entfernt SQL*Plus-Terminatoren (trailing / und führende Leerzeilen).
     * Oracle JDBC verarbeitet MERGE- und PL/SQL-Statements ohne abschließenden /.
     */
    private String prepare(String content) {
        String sql = content.strip();
        if (sql.endsWith("/")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }
        return sql;
    }
}
