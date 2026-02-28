package com.mergegen.config;

import com.mergegen.model.TableHistoryEntry;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Verwaltet den Analyse-Verlauf (zuletzt verwendete Tabellen/Werte).
 *
 * Gespeichert in "table-history.txt" im Arbeitsverzeichnis.
 * Format pro Zeile: TABLE|COLUMN|VAL1;VAL2|TIMESTAMP
 * Neueste Einträge stehen zuerst.
 */
public class TableHistoryStore {

    private static final String FILE_NAME = "table-history.txt";
    private static final String SEP       = "|";
    private static final String LIST_SEP  = ";";

    private final List<TableHistoryEntry> entries = new ArrayList<>();
    private final String filePath;

    public TableHistoryStore() {
        this(".");
    }

    public TableHistoryStore(String baseDir) {
        this.filePath = baseDir + File.separator + FILE_NAME;
        load();
    }

    /**
     * Fügt einen Eintrag hinzu oder aktualisiert einen vorhandenen (gleiche Tabelle,
     * Spalte und Werte). Bei Duplikat wird der Timestamp aktualisiert und der Eintrag
     * an den Anfang der Liste verschoben.
     */
    public void addOrUpdate(TableHistoryEntry entry) {
        Optional<TableHistoryEntry> existing = findMatch(
            entry.getTable(), entry.getColumn(), entry.getValues());
        if (existing.isPresent()) {
            TableHistoryEntry e = existing.get();
            e.setTimestamp(entry.getTimestamp());
            entries.remove(e);
            entries.add(0, e);
        } else {
            entries.add(0, entry);
        }
        save();
    }

    /** Gibt eine unveränderliche Kopie aller Einträge zurück (neueste zuerst). */
    public List<TableHistoryEntry> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Entfernt den Eintrag und speichert sofort. */
    public void remove(TableHistoryEntry entry) {
        entries.remove(entry);
        save();
    }

    /**
     * Aktualisiert einen bestehenden Eintrag (Werte + Timestamp), schiebt ihn an den
     * Listenanfang und speichert.
     */
    public void updateEntry(TableHistoryEntry oldEntry, String table, String column,
                            List<String> newValues) {
        entries.remove(oldEntry);
        entries.add(0, new TableHistoryEntry(table, column, newValues));
        save();
    }

    /**
     * Sucht einen Eintrag anhand von Tabelle, Spalte und Werte-Menge
     * (case-insensitive, reihenfolgeunabhängig).
     */
    public Optional<TableHistoryEntry> findMatch(String table, String column, List<String> values) {
        Set<String> valueSet = values.stream()
            .map(String::toUpperCase)
            .collect(Collectors.toSet());
        return entries.stream()
            .filter(e -> e.getTable().equalsIgnoreCase(table)
                      && e.getColumn().equalsIgnoreCase(column)
                      && e.getValues().stream()
                            .map(String::toUpperCase)
                            .collect(Collectors.toSet())
                            .equals(valueSet))
            .findFirst();
    }

    /** Schreibt alle Einträge sofort in die Datei. */
    public void save() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath))) {
            writer.println("# Analyse-Verlauf");
            writer.println("# Format: TABLE|COLUMN|VAL1;VAL2|TIMESTAMP");
            for (TableHistoryEntry e : entries) {
                writer.println(
                    e.getTable()          + SEP +
                    e.getColumn()         + SEP +
                    joinList(e.getValues()) + SEP +
                    e.getTimestamp()
                );
            }
        } catch (IOException ex) {
            System.err.println("table-history.txt konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }

    private void load() {
        File file = new File(filePath);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length < 4) continue;
                String       table  = parts[0].trim();
                String       column = parts[1].trim();
                List<String> values = splitList(parts[2]);
                // parts[3] kann Timestamp oder (altes Format) Konstantentabellen sein
                long timestamp;
                try {
                    timestamp = Long.parseLong(parts[parts.length - 1].trim());
                } catch (NumberFormatException ex) {
                    timestamp = 0L;
                }
                TableHistoryEntry entry = new TableHistoryEntry(table, column, values);
                entry.setTimestamp(timestamp);
                entries.add(entry);
            }
        } catch (IOException ex) {
            System.err.println("table-history.txt konnte nicht geladen werden: " + ex.getMessage());
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
