package com.mergegen.config;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Haelt Traversal-Regeln im Speicher (nicht persistent).
 * Wird beim Laden eines Presets befuellt und beim Speichern ausgelesen.
 *
 * Key-Format: "PARENT>CHILD.FK_COL" (uppercase)
 * Value: TraversalRule (TRAVERSE, SKIP, SUBSELECT)
 */
public class TraversalRuleStore {

    public enum TraversalRule {
        TRAVERSE,    // ja, traversieren
        SKIP,        // nein, ueberspringen
        SUBSELECT    // nicht traversieren, aber FK durch Subselect ersetzen
    }

    // Key: "PARENT>CHILD.FK_COL", Value: TraversalRule
    private final Map<String, TraversalRule> rules = new LinkedHashMap<>();

    /** Prueft ob fuer diese FK-Beziehung eine Regel existiert. */
    public boolean hasRule(String parentTable, String childTable, String fkColumn) {
        return rules.containsKey(buildKey(parentTable, childTable, fkColumn));
    }

    /** Gibt die gespeicherte Regel zurueck (true = traversieren). */
    public boolean shouldTraverse(String parentTable, String childTable, String fkColumn) {
        TraversalRule rule = rules.get(buildKey(parentTable, childTable, fkColumn));
        return rule == TraversalRule.TRAVERSE;
    }

    /** Gibt die gespeicherte Regel als Enum zurueck (null wenn keine Regel existiert). */
    public TraversalRule getRule(String parentTable, String childTable, String fkColumn) {
        return rules.get(buildKey(parentTable, childTable, fkColumn));
    }

    /** Speichert eine Regel mit Enum. */
    public void setRule(String parentTable, String childTable, String fkColumn, TraversalRule rule) {
        rules.put(buildKey(parentTable, childTable, fkColumn), rule);
    }

    /** Convenience: Speichert eine Regel (boolean-Variante fuer Abwaertskompatibilitaet). */
    public void setRule(String parentTable, String childTable, String fkColumn, boolean traverse) {
        setRule(parentTable, childTable, fkColumn, traverse ? TraversalRule.TRAVERSE : TraversalRule.SKIP);
    }

    /** Gibt alle Regeln als Map zurueck (fuer Preset-Speicherung). */
    public Map<String, TraversalRule> getAll() {
        return new LinkedHashMap<>(rules);
    }

    /** Laedt Regeln aus einer Map (z.B. aus einem Preset). */
    public void loadFrom(Map<String, TraversalRule> presetRules) {
        rules.clear();
        if (presetRules != null) {
            rules.putAll(presetRules);
        }
    }

    /** Leert alle Regeln. */
    public void clear() {
        rules.clear();
    }

    /** Parst Traversal-Regeln aus Format: PARENT>CHILD.FK=JA;...=NEIN;...=SUBSELECT */
    public static Map<String, TraversalRule> parseRules(String s) {
        Map<String, TraversalRule> rules = new LinkedHashMap<>();
        for (String part : s.split(";", -1)) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int eq = part.lastIndexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq).trim();
            String val = part.substring(eq + 1).trim().toUpperCase();
            TraversalRule rule;
            switch (val) {
                case "JA":        rule = TraversalRule.TRAVERSE;  break;
                case "SUBSELECT": rule = TraversalRule.SUBSELECT; break;
                default:          rule = TraversalRule.SKIP;      break;
            }
            rules.put(key, rule);
        }
        return rules;
    }

    /** Formatiert Traversal-Regeln: PARENT>CHILD.FK=JA;...=NEIN;...=SUBSELECT */
    public static String formatRules(Map<String, TraversalRule> rules) {
        if (rules == null || rules.isEmpty()) return "";
        return rules.entrySet().stream()
            .map(e -> {
                String val;
                switch (e.getValue()) {
                    case TRAVERSE:  val = "JA";        break;
                    case SUBSELECT: val = "SUBSELECT"; break;
                    default:        val = "NEIN";      break;
                }
                return e.getKey() + "=" + val;
            })
            .collect(Collectors.joining(";"));
    }

    private String buildKey(String parentTable, String childTable, String fkColumn) {
        return parentTable.toUpperCase() + ">"
             + childTable.toUpperCase() + "."
             + fkColumn.toUpperCase();
    }
}
