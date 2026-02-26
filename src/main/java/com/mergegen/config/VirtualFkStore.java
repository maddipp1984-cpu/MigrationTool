package com.mergegen.config;

import com.mergegen.model.ForeignKeyRelation;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Verwaltet manuell definierte ("virtuelle") FK-Beziehungen, die in der Datenbank
 * nicht als Constraint hinterlegt sind.
 *
 * Gespeichert in "virtual-fks.txt" im Arbeitsverzeichnis.
 * Format: CHILD_TABLE|FK_COLUMN|PARENT_TABLE|PARENT_PK_COLUMN (eine Zeile pro Eintrag)
 * Zeilen, die mit '#' beginnen, werden als Kommentare ignoriert.
 *
 * Beim nächsten Traversal wird automatisch geprüft, ob ein virtueller FK inzwischen
 * als echter Constraint in der DB existiert – solche Einträge werden still entfernt.
 */
public class VirtualFkStore {

    private static final String FILE_NAME  = "virtual-fks.txt";
    private static final String SEPARATOR  = "|";
    private static final String COMMENT    = "#";

    private final List<ForeignKeyRelation> entries = new ArrayList<>();

    public VirtualFkStore() {
        load();
    }

    /**
     * Gibt alle virtuellen FKs zurück, bei denen parentTable der angegebenen
     * Parent-Tabelle entspricht (case-insensitiv).
     */
    public List<ForeignKeyRelation> getRelationsForParent(String parentTable) {
        List<ForeignKeyRelation> result = new ArrayList<>();
        for (ForeignKeyRelation rel : entries) {
            if (rel.getParentTable().equalsIgnoreCase(parentTable)) {
                result.add(rel);
            }
        }
        return result;
    }

    /** Gibt eine unveränderliche Kopie aller Einträge zurück (für die GUI). */
    public List<ForeignKeyRelation> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Fügt einen neuen virtuellen FK hinzu und speichert sofort. */
    public void add(ForeignKeyRelation rel) {
        entries.add(rel);
        save();
    }

    /** Entfernt einen virtuellen FK (Vergleich auf alle 4 Felder) und speichert sofort. */
    public void remove(ForeignKeyRelation rel) {
        entries.removeIf(e ->
            e.getChildTable().equalsIgnoreCase(rel.getChildTable()) &&
            e.getFkColumn().equalsIgnoreCase(rel.getFkColumn()) &&
            e.getParentTable().equalsIgnoreCase(rel.getParentTable()) &&
            e.getParentPkColumn().equalsIgnoreCase(rel.getParentPkColumn()));
        save();
    }

    /** Lädt die Einträge aus virtual-fks.txt (fehlende Datei ist kein Fehler). */
    private void load() {
        File file = new File(FILE_NAME);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(COMMENT)) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length != 4) continue;
                entries.add(new ForeignKeyRelation(
                    parts[0].trim().toUpperCase(),
                    parts[1].trim().toUpperCase(),
                    parts[2].trim().toUpperCase(),
                    parts[3].trim().toUpperCase()
                ));
            }
        } catch (IOException ex) {
            System.err.println("virtual-fks.txt konnte nicht geladen werden: " + ex.getMessage());
        }
    }

    /** Schreibt alle Einträge in virtual-fks.txt. */
    private void save() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(FILE_NAME))) {
            writer.println("# Virtuelle FK-Definitionen");
            writer.println("# Format: CHILD_TABLE|FK_COLUMN|PARENT_TABLE|PARENT_PK_COLUMN");
            for (ForeignKeyRelation rel : entries) {
                writer.println(rel.getChildTable() + SEPARATOR +
                               rel.getFkColumn()   + SEPARATOR +
                               rel.getParentTable() + SEPARATOR +
                               rel.getParentPkColumn());
            }
        } catch (IOException ex) {
            System.err.println("virtual-fks.txt konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }
}
