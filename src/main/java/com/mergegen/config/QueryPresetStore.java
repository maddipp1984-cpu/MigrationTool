package com.mergegen.config;

import com.mergegen.model.QueryPreset;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Verwaltet gespeicherte Abfrage-Presets.
 *
 * Gespeichert in "query-presets.txt" im Arbeitsverzeichnis.
 * Format pro Zeile: NAME|TABLE|COLUMN|VALUE1;VALUE2|CONST1;CONST2
 * Leere Liste → leerer String zwischen den |
 * Zeilen, die mit '#' beginnen, werden als Kommentare ignoriert.
 */
public class QueryPresetStore {

    private static final String FILE_NAME  = "query-presets.txt";
    private static final String SEP        = "|";
    private static final String LIST_SEP   = ";";
    private static final String COMMENT    = "#";

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
                if (parts.length != 5) continue;
                String       name           = parts[0].trim();
                String       table          = parts[1].trim();
                String       column         = parts[2].trim();
                List<String> values         = splitList(parts[3]);
                List<String> constantTables = splitList(parts[4]);
                entries.add(new QueryPreset(name, table, column, values, constantTables));
            }
        } catch (IOException ex) {
            System.err.println("query-presets.txt konnte nicht geladen werden: " + ex.getMessage());
        }
    }

    private void save() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(FILE_NAME))) {
            writer.println("# Gespeicherte Abfrage-Presets");
            writer.println("# Format: NAME|TABLE|COLUMN|VALUE1;VALUE2|CONST1;CONST2");
            for (QueryPreset p : entries) {
                writer.println(
                    p.getName()                           + SEP +
                    p.getTable()                          + SEP +
                    p.getColumn()                         + SEP +
                    joinList(p.getValues())               + SEP +
                    joinList(p.getConstantTables())
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
