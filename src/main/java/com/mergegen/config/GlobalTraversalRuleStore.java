package com.mergegen.config;

import com.mergegen.config.TraversalRuleStore.TraversalRule;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persistiert Traversal-Regeln global pro Root-Tabelle.
 * Datei: config/mergegen/traversal-rules.txt
 */
public class GlobalTraversalRuleStore {

    private static final String LIST_SEP = ";";
    private static final String DEFAULT_PATH = "config/mergegen/traversal-rules.txt";

    private final Path filePath;
    private final Map<String, Map<String, TraversalRule>> rulesByTable = new LinkedHashMap<>();

    public GlobalTraversalRuleStore() {
        this(Paths.get(DEFAULT_PATH));
    }

    public GlobalTraversalRuleStore(Path filePath) {
        this.filePath = filePath;
        load();
    }

    public boolean hasRulesFor(String rootTable) {
        return rulesByTable.containsKey(rootTable.toUpperCase());
    }

    public Map<String, TraversalRule> getRulesFor(String rootTable) {
        Map<String, TraversalRule> rules = rulesByTable.get(rootTable.toUpperCase());
        return rules != null ? new LinkedHashMap<>(rules) : Collections.emptyMap();
    }

    public void saveRulesFor(String rootTable, Map<String, TraversalRule> rules) {
        String key = rootTable.toUpperCase();
        if (rules == null || rules.isEmpty()) {
            rulesByTable.remove(key);
        } else {
            rulesByTable.put(key, new LinkedHashMap<>(rules));
        }
        save();
    }

    private void load() {
        if (!Files.exists(filePath)) return;
        try {
            for (String line : Files.readAllLines(filePath)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length < 2) continue;
                String table = parts[0].trim().toUpperCase();
                Map<String, TraversalRule> rules = parseRules(parts[1]);
                if (!rules.isEmpty()) {
                    rulesByTable.put(table, rules);
                }
            }
        } catch (IOException e) {
            // Datei nicht lesbar – leerer Store
        }
    }

    private void save() {
        try {
            Files.createDirectories(filePath.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Globale Traversal-Regeln pro Root-Tabelle");
            lines.add("# Format: ROOT_TABLE|PARENT>CHILD.FK=JA/NEIN/SUBSELECT;...");
            for (Map.Entry<String, Map<String, TraversalRule>> entry : rulesByTable.entrySet()) {
                lines.add(entry.getKey() + "|" + formatRules(entry.getValue()));
            }
            Files.write(filePath, lines);
        } catch (IOException e) {
            // Silent fail – analog zu anderen Stores
        }
    }

    private static Map<String, TraversalRule> parseRules(String s) {
        Map<String, TraversalRule> rules = new LinkedHashMap<>();
        for (String part : s.split(LIST_SEP, -1)) {
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

    private static String formatRules(Map<String, TraversalRule> rules) {
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
            .collect(Collectors.joining(LIST_SEP));
    }
}
