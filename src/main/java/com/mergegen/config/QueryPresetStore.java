package com.mergegen.config;

import com.mergegen.config.TraversalRuleStore.TraversalRule;
import com.mergegen.model.QueryPreset;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Verwaltet gespeicherte Abfrage-Presets.
 *
 * Gespeichert in "query-presets.txt" im Arbeitsverzeichnis.
 * Format pro Zeile: NAME|TABLE|COLUMN|VALUE1;VALUE2
 * Zeilen, die mit '#' beginnen, werden als Kommentare ignoriert.
 */
public class QueryPresetStore {

    private static final String FILE_NAME = "config/mergegen/query-presets.txt";
    private static final String SEP       = "|";
    private static final String LIST_SEP  = ";";
    private static final String COMMENT   = "#";

    private final List<QueryPreset> entries = new ArrayList<>();

    public QueryPresetStore() {
        load();
    }

    /** Gibt eine unveränderliche Kopie aller Presets zurück. */
    public List<QueryPreset> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Fügt ein Preset hinzu und speichert sofort. */
    public void add(QueryPreset preset) {
        entries.add(preset);
        save();
    }

    /** Entfernt ein Preset anhand des Namens (case-insensitive) und speichert sofort. */
    public void remove(String name) {
        entries.removeIf(p -> p.getName().equalsIgnoreCase(name));
        save();
    }

    /** Sucht ein Preset anhand des Namens (case-insensitive). */
    public Optional<QueryPreset> findByName(String name) {
        return entries.stream()
            .filter(p -> p.getName().equalsIgnoreCase(name))
            .findFirst();
    }

    private void load() {
        File file = new File(FILE_NAME);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(COMMENT)) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length < 4) continue;
                String       name   = parts[0].trim();
                String       table  = parts[1].trim();
                String       column = parts[2].trim();
                List<String> values = splitList(parts[3]);
                Map<String, TraversalRule> rules = new LinkedHashMap<>();
                if (parts.length >= 5 && !parts[4].trim().isEmpty()) {
                    rules = TraversalRuleStore.parseRules(parts[4].trim());
                }
                String alias = (parts.length >= 7) ? parts[6].trim() : "";
                entries.add(new QueryPreset(name, table, column, values, rules, alias));
            }
        } catch (IOException ex) {
            System.err.println("query-presets.txt konnte nicht geladen werden: " + ex.getMessage());
        }
    }

    private void save() {
        File file = new File(FILE_NAME);
        file.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("# Gespeicherte Abfrage-Presets");
            writer.println("# Format: NAME|TABLE|COLUMN|VALUE1;VALUE2|TRAVERSAL_RULES|CONST_TABLES|ALIAS");
            for (QueryPreset p : entries) {
                writer.println(
                    p.getName()             + SEP +
                    p.getTable()            + SEP +
                    p.getColumn()           + SEP +
                    joinList(p.getValues()) + SEP +
                    TraversalRuleStore.formatRules(p.getTraversalRules()) + SEP +
                    SEP +                   // Feld 6 (reserved/const_tables)
                    p.getAlias()            // Feld 7
                );
            }
        } catch (IOException ex) {
            System.err.println("query-presets.txt konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    private static List<String> splitList(String s) {
        s = s.trim();
        if (s.isEmpty()) return new ArrayList<>();
        return Arrays.stream(s.split(LIST_SEP, -1))
            .map(String::trim)
            .filter(v -> !v.isEmpty())
            .collect(Collectors.toList());
    }

    private static String joinList(List<String> list) {
        return String.join(LIST_SEP, list);
    }

}
