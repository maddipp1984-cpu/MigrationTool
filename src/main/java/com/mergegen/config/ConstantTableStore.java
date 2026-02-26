package com.mergegen.config;

import java.io.*;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Verwaltet Tabellen, die als Konstantentabellen gelten (fixe PKs, existieren
 * bereits in der Zieldatenbank). Für diese Tabellen wird kein MERGE-Statement
 * erzeugt; FK-Werte in abhängigen Tabellen werden direkt übernommen.
 *
 * Gespeichert in "constant-tables.txt" im Arbeitsverzeichnis.
 * Format: ein Tabellenname pro Zeile (uppercase).
 */
public class ConstantTableStore {

    private static final String FILE_NAME = "constant-tables.txt";

    private final Set<String> tables = new LinkedHashSet<>();

    public ConstantTableStore() {
        load();
    }

    /** Gibt true zurück, wenn die Tabelle als Konstantentabelle markiert ist. */
    public boolean isConstant(String tableName) {
        return tables.contains(tableName.toUpperCase());
    }

    /** Fügt eine Tabelle hinzu und speichert sofort. */
    public void add(String tableName) {
        if (tables.add(tableName.toUpperCase())) {
            save();
        }
    }

    /** Entfernt eine Tabelle und speichert sofort. */
    public void remove(String tableName) {
        if (tables.remove(tableName.toUpperCase())) {
            save();
        }
    }

    /** Entfernt alle Einträge und speichert sofort. */
    public void clear() {
        if (!tables.isEmpty()) {
            tables.clear();
            save();
        }
    }

    /** Gibt eine unveränderliche Kopie aller Einträge zurück. */
    public Set<String> getAll() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tables));
    }

    private void load() {
        File file = new File(FILE_NAME);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                tables.add(line.toUpperCase());
            }
        } catch (IOException ex) {
            System.err.println("constant-tables.txt konnte nicht geladen werden: " + ex.getMessage());
        }
    }

    private void save() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(FILE_NAME))) {
            writer.println("# Konstantentabellen mit fixen Primary Keys");
            writer.println("# Fuer diese Tabellen wird kein MERGE-Statement erzeugt.");
            for (String t : tables) {
                writer.println(t);
            }
        } catch (IOException ex) {
            System.err.println("constant-tables.txt konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }
}
