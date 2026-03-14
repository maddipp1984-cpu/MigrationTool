package com.mergegen.model;

import java.util.*;

/**
 * Value-Objekt für ein gespeichertes Abfrage-Preset.
 * Enthält Einstiegstabelle, optionale Spalte, Suchwerte und Traversal-Regeln.
 */
public class QueryPreset {

    private final String       name;
    private final String       table;
    private final String       column;
    private final List<String> values;
    // Key: "PARENT>CHILD.FK_COL", Value: true = traversieren
    private final Map<String, Boolean> traversalRules;

    public QueryPreset(String name, String table, String column, List<String> values) {
        this(name, table, column, values, new LinkedHashMap<>());
    }

    public QueryPreset(String name, String table, String column, List<String> values,
                       Map<String, Boolean> traversalRules) {
        this.name   = name;
        this.table  = table;
        this.column = column;
        this.values = Collections.unmodifiableList(values);
        this.traversalRules = new LinkedHashMap<>(traversalRules);
    }

    public String       getName()   { return name; }
    public String       getTable()  { return table; }
    public String       getColumn() { return column; }
    public List<String> getValues() { return values; }
    public Map<String, Boolean> getTraversalRules() {
        return Collections.unmodifiableMap(traversalRules);
    }
}
