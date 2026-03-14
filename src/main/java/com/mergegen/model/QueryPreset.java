package com.mergegen.model;

import com.mergegen.config.TraversalRuleStore.TraversalRule;

import java.util.*;

/**
 * Value-Objekt fuer ein gespeichertes Abfrage-Preset.
 * Enthaelt Einstiegstabelle, optionale Spalte, Suchwerte und Traversal-Regeln.
 */
public class QueryPreset {

    private final String       name;
    private final String       table;
    private final String       column;
    private final List<String> values;
    // Key: "PARENT>CHILD.FK_COL", Value: TraversalRule
    private final Map<String, TraversalRule> traversalRules;

    public QueryPreset(String name, String table, String column, List<String> values) {
        this(name, table, column, values, new LinkedHashMap<>());
    }

    public QueryPreset(String name, String table, String column, List<String> values,
                       Map<String, TraversalRule> traversalRules) {
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
    public Map<String, TraversalRule> getTraversalRules() {
        return Collections.unmodifiableMap(traversalRules);
    }
}
