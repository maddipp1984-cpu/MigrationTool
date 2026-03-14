package com.mergegen.config;

import java.io.*;
import java.util.*;

/**
 * Verwaltet die Liste der Konstantentabellen, für die kein MERGE erzeugt wird.
 *
 * Gespeichert in "config/mergegen/constant-tables.txt" (eine Zeile pro Tabellenname).
 * Zeilen, die mit '#' beginnen, werden als Kommentare ignoriert.
 */
public class ConstantTableStore {

    private static final String FILE_NAME = "config/mergegen/constant-tables.txt";
    private static final String COMMENT   = "#";

    private final LinkedHashSet<String> entries = new LinkedHashSet<>();

    public ConstantTableStore() {
        load();
    }

    /** Gibt eine Liste aller Einträge zurück (uppercase, Einfügereihenfolge). */
    public List<String> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Gibt ein Set aller Einträge zurück (uppercase) – effizient für contains-Prüfung. */
    public Set<String> getAsSet() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(entries));
    }

    /** Fügt eine Tabelle hinzu (falls nicht schon vorhanden) und speichert sofort. */
    public void add(String tableName) {
        String upper = tableName.trim().toUpperCase();
        if (upper.isEmpty()) return;
        if (entries.add(upper)) {
            save();
        }
    }

    /** Entfernt eine Tabelle und speichert sofort. */
    public void remove(String tableName) {
        if (entries.remove(tableName.trim().toUpperCase())) {
            save();
        }
    }

    /** Entfernt mehrere Tabellen auf einmal und speichert sofort. */
    public void removeAll(Collection<String> tableNames) {
        boolean changed = false;
        for (String t : tableNames) {
            if (entries.remove(t.trim().toUpperCase())) {
                changed = true;
            }
        }
        if (changed) save();
    }

    /** Prüft, ob eine Tabelle als Konstantentabelle markiert ist. */
    public boolean contains(String tableName) {
        return entries.contains(tableName.trim().toUpperCase());
    }

    private void load() {
        File file = new File(FILE_NAME);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(COMMENT)) continue;
                entries.add(line.toUpperCase());
            }
        } catch (IOException ex) {
            System.err.println("constant-tables.txt konnte nicht geladen werden: " + ex.getMessage());
        }
    }

    private void save() {
        File file = new File(FILE_NAME);
        file.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("# Konstantentabellen (kein MERGE)");
            writer.println("# Eine Tabelle pro Zeile");
            for (String entry : entries) {
                writer.println(entry);
            }
        } catch (IOException ex) {
            System.err.println("constant-tables.txt konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }
}
