package com.mergegen.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Eintrag im Analyse-Verlauf.
 * Enthält Tabelle, Spalte, Werte sowie (nachträglich ergänzbare) Konstantentabellen.
 */
public class TableHistoryEntry {

    private final String       table;
    private final String       column;
    private final List<String> values;
    private       List<String> constantTables;
    private       long         timestamp;

    public TableHistoryEntry(String table, String column, List<String> values,
                             List<String> constantTables) {
        this.table          = table;
        this.column         = column;
        this.values         = new ArrayList<>(values);
        this.constantTables = new ArrayList<>(constantTables);
        this.timestamp      = System.currentTimeMillis();
    }

    public String       getTable()          { return table; }
    public String       getColumn()         { return column; }
    public List<String> getValues()         { return new ArrayList<>(values); }
    public List<String> getConstantTables() { return new ArrayList<>(constantTables); }
    public long         getTimestamp()      { return timestamp; }

    public void setConstantTables(List<String> constantTables) {
        this.constantTables = new ArrayList<>(constantTables);
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return table + (values.isEmpty() ? "" : " – " + String.join(", ", values));
    }
}
