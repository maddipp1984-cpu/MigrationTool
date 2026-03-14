package com.mergegen.config;

import java.util.*;

/**
 * Hält Traversal-Regeln im Speicher (nicht persistent).
 * Wird beim Laden eines Presets befüllt und beim Speichern ausgelesen.
 *
 * Key-Format: "PARENT>CHILD.FK_COL" (uppercase)
 * Value: true = traversieren, false = überspringen
 */
public class TraversalRuleStore {

    // Key: "PARENT>CHILD.FK_COL", Value: true = traversieren
    private final Map<String, Boolean> rules = new LinkedHashMap<>();

    /** Prüft ob für diese FK-Beziehung eine Regel existiert. */
    public boolean hasRule(String parentTable, String childTable, String fkColumn) {
        return rules.containsKey(buildKey(parentTable, childTable, fkColumn));
    }

    /** Gibt die gespeicherte Regel zurück (true = traversieren). */
    public boolean shouldTraverse(String parentTable, String childTable, String fkColumn) {
        Boolean val = rules.get(buildKey(parentTable, childTable, fkColumn));
        return val != null && val;
    }

    /** Speichert eine Regel (nur im Speicher). */
    public void setRule(String parentTable, String childTable, String fkColumn, boolean traverse) {
        rules.put(buildKey(parentTable, childTable, fkColumn), traverse);
    }

    /** Gibt alle Regeln als Map zurück (für Preset-Speicherung). */
    public Map<String, Boolean> getAll() {
        return new LinkedHashMap<>(rules);
    }

    /** Lädt Regeln aus einer Map (z.B. aus einem Preset). */
    public void loadFrom(Map<String, Boolean> presetRules) {
        rules.clear();
        if (presetRules != null) {
            rules.putAll(presetRules);
        }
    }

    /** Leert alle Regeln. */
    public void clear() {
        rules.clear();
    }

    private String buildKey(String parentTable, String childTable, String fkColumn) {
        return parentTable.toUpperCase() + ">"
             + childTable.toUpperCase() + "."
             + fkColumn.toUpperCase();
    }
}
