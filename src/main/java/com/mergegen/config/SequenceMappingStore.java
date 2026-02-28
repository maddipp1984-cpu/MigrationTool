package com.mergegen.config;

import com.mergegen.model.SequenceMapping;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Verwaltet Sequence-Zuordnungen für PK-Spalten.
 *
 * Gespeichert in "sequence-mappings.txt" im Arbeitsverzeichnis.
 * Format: TABLE_NAME|PK_COLUMN|SEQUENCE_NAME (eine Zeile pro Eintrag)
 * Zeilen, die mit '#' beginnen, werden als Kommentare ignoriert.
 */
public class SequenceMappingStore {

    private static final String FILE_NAME = "config/mergegen/sequence-mappings.txt";
    private static final String SEPARATOR = "|";
    private static final String COMMENT   = "#";

    private final List<SequenceMapping> entries = new ArrayList<>();

    public SequenceMappingStore() {
        load();
    }

    /** Gibt eine unveränderliche Kopie aller Einträge zurück. */
    public List<SequenceMapping> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Fügt ein neues Mapping hinzu und speichert sofort. */
    public void add(SequenceMapping mapping) {
        entries.add(mapping);
        save();
    }

    /** Entfernt ein Mapping anhand von Tabelle und PK-Spalte und speichert sofort. */
    public void remove(String tableName, String pkColumn) {
        entries.removeIf(e ->
            e.getTableName().equalsIgnoreCase(tableName) &&
            e.getPkColumn().equalsIgnoreCase(pkColumn));
        save();
    }

    /** Sucht ein Mapping für die angegebene Tabelle. */
    public Optional<SequenceMapping> findByTable(String tableName) {
        return entries.stream()
            .filter(e -> e.getTableName().equalsIgnoreCase(tableName))
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
                if (parts.length != 3) continue;
                entries.add(new SequenceMapping(
                    parts[0].trim(),
                    parts[1].trim(),
                    parts[2].trim()
                ));
            }
        } catch (IOException ex) {
            System.err.println("sequence-mappings.txt konnte nicht geladen werden: " + ex.getMessage());
        }
    }

    private void save() {
        File file = new File(FILE_NAME);
        file.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("# Sequence-Zuordnungen fuer PK-Spalten");
            writer.println("# Format: TABLE_NAME|PK_COLUMN|SEQUENCE_NAME");
            for (SequenceMapping m : entries) {
                writer.println(m.getTableName() + SEPARATOR +
                               m.getPkColumn()  + SEPARATOR +
                               m.getSequenceName());
            }
        } catch (IOException ex) {
            System.err.println("sequence-mappings.txt konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }
}
