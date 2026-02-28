package com.mergegen.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Eintrag im Analyse-Verlauf.
 * Enthält Tabelle, Spalte, Werte und Timestamp.
 */
public class TableHistoryEntry {

    private final String       table;
    private final String       column;
    private final List<String> values;
    private       long         timestamp;

    public TableHistoryEntry(String table, String column, List<String> values) {
        this.table     = table;
        this.column    = column;
        this.values    = new ArrayList<>(values);
        this.timestamp = System.currentTimeMillis();
    }

    public String       getTable()     { return table; }
    public String       getColumn()    { return column; }
    public List<String> getValues()    { return new ArrayList<>(values); }
    public long         getTimestamp() { return timestamp; }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return table + (values.isEmpty() ? "" : " – " + String.join(", ", values));
    }
}
