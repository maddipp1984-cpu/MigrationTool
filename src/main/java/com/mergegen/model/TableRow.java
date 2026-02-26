package com.mergegen.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Repräsentiert eine einzelne Zeile einer Datenbanktabelle.
 *
 * Die Spaltenwerte werden nicht als Java-Typen gespeichert, sondern direkt
 * als Oracle-SQL-Literale (z.B. 42, 'Text', TO_DATE(...), NULL). Dadurch
 * kann der MergeScriptGenerator die Werte ohne weitere Umwandlung ins
 * MERGE-Statement einbetten.
 *
 * LinkedHashMap wird verwendet, um die ursprüngliche Spaltenreihenfolge
 * aus der Datenbank (column_id) beizubehalten.
 */
public class TableRow {

    private final String tableName;
    private final String schema;
    /** Spaltenname → SQL-Literal-Wert, z.B. "42", "'Text'", "NULL", "TO_DATE(...)" */
    private final Map<String, String> values;
    /** Spaltenname → Spaltenmetadaten (Typ, PK-Flag, Nullable) */
    private final Map<String, ColumnInfo> columns;

    public TableRow(String schema, String tableName) {
        this.schema = schema;
        this.tableName = tableName;
        // LinkedHashMap: erhält die Einfügereihenfolge → Spalten bleiben in DB-Reihenfolge
        this.values  = new LinkedHashMap<>();
        this.columns = new LinkedHashMap<>();
    }

    /**
     * Fügt eine Spalte mit ihrem SQL-Literal-Wert hinzu.
     * Beide Maps werden synchron befüllt, damit Metadaten und Werte
     * immer konsistent über denselben Schlüssel (Spaltenname) abrufbar sind.
     */
    public void addValue(ColumnInfo col, String sqlLiteral) {
        columns.put(col.getName(), col);
        values.put(col.getName(), sqlLiteral);
    }

    public String getTableName() { return tableName; }
    public String getSchema() { return schema; }
    public Map<String, String> getValues() { return values; }
    public Map<String, ColumnInfo> getColumns() { return columns; }

    /** Gibt den SQL-Literal-Wert der PK-Spalte zurück (für Child-Traversal). */
    public String getPkRawValue(String pkColumn) {
        return values.get(pkColumn);
    }

    /** Eindeutiger Schlüssel für Zyklus-Erkennung */
    public String getUniqueKey(String pkColumn) {
        return schema + "." + tableName + "#" + values.get(pkColumn);
    }
}
