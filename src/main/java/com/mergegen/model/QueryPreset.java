package com.mergegen.model;

import java.util.Collections;
import java.util.List;

/**
 * Value-Objekt für ein gespeichertes Abfrage-Preset.
 * Enthält Einstiegstabelle, optionale Spalte, Suchwerte und Konstantentabellen.
 */
public class QueryPreset {

    private final String       name;
    private final String       table;
    private final String       column;
    private final List<String> values;
    private final List<String> constantTables;

    public QueryPreset(String name, String table, String column,
                       List<String> values, List<String> constantTables) {
        this.name           = name;
        this.table          = table;
        this.column         = column;
        this.values         = Collections.unmodifiableList(values);
        this.constantTables = Collections.unmodifiableList(constantTables);
    }

    public String       getName()           { return name; }
    public String       getTable()          { return table; }
    public String       getColumn()         { return column; }
    public List<String> getValues()         { return values; }
    public List<String> getConstantTables() { return constantTables; }
}
