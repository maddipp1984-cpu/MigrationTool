package com.mergegen.analyzer;

import com.mergegen.config.DatabaseConfig;
import com.mergegen.model.ColumnInfo;
import com.mergegen.model.ForeignKeyRelation;
import com.mergegen.model.TableRow;

import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest Struktur- und Nutzdaten aus der Oracle-Datenbank.
 *
 * Alle Abfragen sind rein lesend (SELECT) und nutzen ausschließlich
 * die Oracle Data-Dictionary-Views ALL_CONSTRAINTS, ALL_CONS_COLUMNS
 * und ALL_TAB_COLUMNS sowie direkte Tabellenzugriffe.
 *
 * Die Verbindung ist read-only (gesetzt in DatabaseConnection), d.h.
 * ein versehentlicher Schreibzugriff wird bereits vom JDBC-Treiber abgelehnt.
 */
public class SchemaAnalyzer {

    private final Connection connection;
    private final String schema;

    public SchemaAnalyzer(Connection connection, DatabaseConfig config) {
        this.connection = connection;
        this.schema = config.getSchema();
    }

    /**
     * Ermittelt die Spaltennamen des Primary Keys einer Tabelle.
     * Bei zusammengesetzten PKs werden die Spalten in der Reihenfolge
     * ihrer Definition (cc.position) zurückgegeben.
     * Gibt eine leere Liste zurück, wenn kein PK definiert ist.
     */
    public List<String> getPrimaryKeyColumns(String tableName) throws SQLException {
        // ALL_CONSTRAINTS enthält die Constraint-Definitionen (Type 'P' = Primary Key).
        // ALL_CONS_COLUMNS verknüpft Constraints mit den zugehörigen Spalten.
        String sql =
            "SELECT cc.column_name " +
            "FROM all_constraints c " +
            "JOIN all_cons_columns cc ON cc.constraint_name = c.constraint_name AND cc.owner = c.owner " +
            "WHERE c.constraint_type = 'P' " +
            "  AND c.table_name = ? " +
            "  AND c.owner = ? " +
            "ORDER BY cc.position";

        List<String> pkColumns = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pkColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }
        return pkColumns;
    }

    /**
     * Liefert alle Spalten einer Tabelle als ColumnInfo-Objekte,
     * sortiert nach ihrer physischen Reihenfolge (column_id).
     *
     * @param pkColumns Liste der PK-Spaltennamen – wird genutzt, um
     *                  das isPrimaryKey-Flag in ColumnInfo zu setzen.
     */
    public List<ColumnInfo> getColumns(String tableName, List<String> pkColumns) throws SQLException {
        String sql =
            "SELECT column_name, data_type, nullable " +
            "FROM all_tab_columns " +
            "WHERE table_name = ? AND owner = ? " +
            "ORDER BY column_id";

        List<ColumnInfo> columns = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String colName  = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("DATA_TYPE");
                    boolean nullable = "Y".equals(rs.getString("NULLABLE"));
                    boolean isPk     = pkColumns.contains(colName);
                    columns.add(new ColumnInfo(colName, dataType, nullable, isPk));
                }
            }
        }
        return columns;
    }

    /**
     * Findet alle Child-Tabellen, die per Foreign Key auf parentTable zeigen.
     *
     * Der SQL-Join über vier Views ist nötig, weil Oracle FK-Informationen
     * aufgeteilt speichert:
     *   c   = FK-Constraint der Child-Tabelle (Type 'R' = Referential)
     *   cc  = Spalte(n) des FK in der Child-Tabelle
     *   rc  = Referenzierter Constraint (der PK der Parent-Tabelle)
     *   rcc = Spalte(n) des referenzierten PK
     *
     * @return Liste aller FK-Beziehungen, die auf parentTable zeigen.
     *         Leer, wenn keine Child-Tabellen existieren.
     */
    public List<ForeignKeyRelation> getChildRelations(String parentTable) throws SQLException {
        String sql =
            "SELECT " +
            "    c.table_name        AS child_table, " +
            "    cc.column_name      AS fk_column, " +
            "    rcc.column_name     AS parent_pk_column " +
            "FROM all_constraints c " +
            // FK-Spalte der Child-Tabelle
            "JOIN all_cons_columns cc  ON cc.constraint_name = c.constraint_name  AND cc.owner = c.owner " +
            // Referenzierter Constraint (PK der Parent-Tabelle)
            "JOIN all_constraints rc   ON rc.constraint_name = c.r_constraint_name AND rc.owner = c.r_owner " +
            // Spalte des referenzierten PK
            "JOIN all_cons_columns rcc ON rcc.constraint_name = rc.constraint_name AND rcc.owner = rc.owner " +
            "WHERE c.constraint_type = 'R' " +      // nur Foreign Keys
            "  AND rc.table_name = ? " +             // Parent-Tabelle
            "  AND rc.owner = ? " +                  // Parent im richtigen Schema
            "  AND c.owner = ? " +                   // Child ebenfalls im Schema
            "ORDER BY c.table_name, cc.position";

        List<ForeignKeyRelation> relations = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, parentTable.toUpperCase());
            ps.setString(2, schema);
            ps.setString(3, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    relations.add(new ForeignKeyRelation(
                        rs.getString("CHILD_TABLE"),
                        rs.getString("FK_COLUMN"),
                        parentTable.toUpperCase(),
                        rs.getString("PARENT_PK_COLUMN")
                    ));
                }
            }
        }
        return relations;
    }

    /**
     * Lädt alle Zeilen aus childTable, bei denen fkColumn den Wert
     * parentPkValue hat (also alle direkten Kinder eines Parent-Datensatzes).
     *
     * @param parentPkValue SQL-Literal des Parent-PK-Werts (z.B. "42" oder "'ABC'")
     */
    public List<TableRow> fetchChildRows(String childTable, String fkColumn,
                                          String parentPkValue) throws SQLException {
        List<String> pkCols  = getPrimaryKeyColumns(childTable);
        List<ColumnInfo> columns = getColumns(childTable, pkCols);

        // parentPkValue ist bereits ein gültiges SQL-Literal, daher direkte Einbettung
        String sql = "SELECT * FROM " + schema + "." + childTable.toUpperCase() +
                     " WHERE " + fkColumn.toUpperCase() + " = " + parentPkValue;

        return fetchRows(childTable, columns, sql);
    }

    /**
     * Lädt eine einzelne Zeile anhand ihres Primary-Key-Werts.
     *
     * @param pkValue SQL-Literal des PK-Werts (z.B. "42" oder "'ABC'")
     * @throws IllegalArgumentException wenn kein Datensatz gefunden wird
     */
    public TableRow fetchRowByPk(String tableName, String pkColumn,
                                  String pkValue) throws SQLException {
        List<String> pkCols  = getPrimaryKeyColumns(tableName);
        List<ColumnInfo> columns = getColumns(tableName, pkCols);

        String sql = "SELECT * FROM " + schema + "." + tableName.toUpperCase() +
                     " WHERE " + pkColumn.toUpperCase() + " = " + pkValue;

        List<TableRow> rows = fetchRows(tableName, columns, sql);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                "Kein Datensatz gefunden: " + tableName + "." + pkColumn + " = " + pkValue);
        }
        return rows.get(0);
    }

    /**
     * Führt eine SELECT-Abfrage aus und wandelt jede Ergebniszeile in ein
     * TableRow-Objekt um. Die Spaltenwerte werden dabei direkt als SQL-Literale
     * gespeichert, damit sie später ohne Umwandlung ins MERGE-Statement
     * eingefügt werden können.
     */
    private List<TableRow> fetchRows(String tableName, List<ColumnInfo> columns,
                                      String sql) throws SQLException {
        List<TableRow> rows = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                TableRow row = new TableRow(schema, tableName.toUpperCase());
                for (ColumnInfo col : columns) {
                    // Jeden Wert sofort als Oracle-SQL-Literal konvertieren
                    String literal = toSqlLiteral(rs, col);
                    row.addValue(col, literal);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Konvertiert einen JDBC-Spaltenwert in ein Oracle-SQL-Literal,
     * das direkt in ein MERGE-Statement eingebettet werden kann.
     *
     * Typbehandlung:
     *   - NUMBER, FLOAT, BINARY_FLOAT, BINARY_DOUBLE → Rohzahl ohne Quotes
     *   - DATE                                       → TO_DATE('...', 'YYYY-MM-DD HH24:MI:SS')
     *   - TIMESTAMP                                  → TO_TIMESTAMP('...', 'YYYY-MM-DD HH24:MI:SS')
     *   - CLOB, BLOB, NCLOB                          → NULL mit erklärendem Kommentar
     *   - Alles andere (VARCHAR2, CHAR, etc.)        → 'wert' mit escapten Hochkommata
     *   - NULL-Werte jedes Typs                      → NULL
     */
    private String toSqlLiteral(ResultSet rs, ColumnInfo col) throws SQLException {
        String colName  = col.getName();
        String dataType = col.getDataType();

        Object value = rs.getObject(colName);
        if (value == null || rs.wasNull()) {
            return "NULL";
        }

        switch (dataType) {
            case "NUMBER":
            case "FLOAT":
            case "BINARY_FLOAT":
            case "BINARY_DOUBLE":
                // Zahlen benötigen keine Anführungszeichen
                return value.toString();

            case "DATE": {
                // Oracle DATE enthält Uhrzeit – daher Timestamp-Lesen statt Date
                Timestamp ts = rs.getTimestamp(colName);
                if (ts == null) return "NULL";
                String fmt = ts.toLocalDateTime()
                               .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return "TO_DATE('" + fmt + "', 'YYYY-MM-DD HH24:MI:SS')";
            }

            case "TIMESTAMP":
            case "TIMESTAMP(6)":
            case "TIMESTAMP(3)": {
                Timestamp ts = rs.getTimestamp(colName);
                if (ts == null) return "NULL";
                String fmt = ts.toLocalDateTime()
                               .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return "TO_TIMESTAMP('" + fmt + "', 'YYYY-MM-DD HH24:MI:SS')";
            }

            case "CLOB":
            case "BLOB":
            case "NCLOB":
                // LOB-Werte können nicht sinnvoll als SQL-Literal dargestellt werden
                return "NULL /* " + dataType + "-Wert in " + colName + " nicht exportierbar */";

            default:
                // VARCHAR2, CHAR, NVARCHAR2, etc.
                // Einfache Hochkommata im Wert werden durch doppelte Hochkommata escaped ('O''Brien')
                return "'" + value.toString().replace("'", "''") + "'";
        }
    }

    /**
     * Sucht in BEFORE-INSERT-Triggern der Tabelle nach einem NEXTVAL-Aufruf
     * und gibt den Sequence-Namen zurück, falls gefunden.
     *
     * Wird nur als Vorschlag genutzt – der User bestätigt oder ändert den Wert.
     */
    public Optional<String> detectTriggerSequence(String tableName) {
        String sql =
            "SELECT trigger_body FROM all_triggers " +
            "WHERE table_name = ? AND owner = ? " +
            "  AND trigger_type LIKE 'BEFORE%' " +
            "  AND triggering_event LIKE '%INSERT%'";

        Pattern nextvalPattern = Pattern.compile("(\\w+)\\.NEXTVAL", Pattern.CASE_INSENSITIVE);

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName.toUpperCase());
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String body = rs.getString("TRIGGER_BODY");
                    if (body == null) continue;
                    Matcher m = nextvalPattern.matcher(body);
                    if (m.find()) {
                        return Optional.of(m.group(1).toUpperCase());
                    }
                }
            }
        } catch (SQLException ex) {
            // Kein Fehler – Trigger-Erkennung ist nur ein Vorschlag
            System.err.println("Trigger-Sequence-Erkennung fehlgeschlagen: " + ex.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Hilfsmethode: gibt den gespeicherten SQL-Literal-Wert einer PK-Spalte zurück.
     * Wird im TraversalService benötigt, um den PK-Wert für Child-Abfragen weiterzugeben.
     */
    public String getRawPkValue(TableRow row, String pkColumn) {
        return row.getPkRawValue(pkColumn);
    }
}
