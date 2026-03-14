package com.mergegen.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Speichert Subselect-Mappings: Tabelle -> PK-Spalte + Lookup-Spalten.
 * Datei: config/mergegen/subselect-mappings.txt
 * Format: TABLE|PK_COL|LOOKUP_COL1;LOOKUP_COL2
 */
public class SubselectMappingStore {

    private static final String FILE_NAME = "subselect-mappings.txt";

    private final Path filePath;
    // Key: TABLE (uppercase), Value: [0]=PK_COL, [1..n]=LOOKUP_COLS
    private final Map<String, String[]> mappings = new LinkedHashMap<>();

    public SubselectMappingStore() {
        this(Paths.get("config", "mergegen"));
    }

    /** Test-Konstruktor mit explizitem Basispfad. */
    public SubselectMappingStore(Path baseDir) {
        this.filePath = baseDir.resolve(FILE_NAME);
        load();
    }

    public boolean hasMapping(String table) {
        return mappings.containsKey(table.toUpperCase());
    }

    public String getPkColumn(String table) {
        String[] entry = mappings.get(table.toUpperCase());
        return entry != null ? entry[0] : null;
    }

    public List<String> getLookupColumns(String table) {
        String[] entry = mappings.get(table.toUpperCase());
        if (entry == null) return null;
        return List.of(Arrays.copyOfRange(entry, 1, entry.length));
    }

    public void add(String table, String pkColumn, List<String> lookupColumns) {
        String[] entry = new String[1 + lookupColumns.size()];
        entry[0] = pkColumn.toUpperCase();
        for (int i = 0; i < lookupColumns.size(); i++) {
            entry[i + 1] = lookupColumns.get(i).toUpperCase();
        }
        mappings.put(table.toUpperCase(), entry);
        save();
    }

    public void remove(String table) {
        mappings.remove(table.toUpperCase());
        save();
    }

    /**
     * Baut einen Subselect-Ausdruck fuer eine FK-Spalte.
     * @param table      Zieltabelle (z.B. DEPARTMENTS)
     * @param rowValues  Map aller Spaltenwerte der referenzierten Zeile (SQL-Literale)
     * @return "(SELECT PK FROM TABLE WHERE COL1 = val1 AND COL2 = val2)"
     */
    public String buildSubselect(String table, Map<String, String> rowValues) {
        String[] entry = mappings.get(table.toUpperCase());
        if (entry == null) return null;

        String pkCol = entry[0];
        List<String> lookupCols = List.of(Arrays.copyOfRange(entry, 1, entry.length));

        String whereClause = lookupCols.stream()
            .map(col -> col + " = " + rowValues.get(col))
            .collect(Collectors.joining(" AND "));

        return "(SELECT " + pkCol + " FROM " + table.toUpperCase()
             + " WHERE " + whereClause + ")";
    }

    public Map<String, String[]> getAll() {
        return new LinkedHashMap<>(mappings);
    }

    private void load() {
        if (!Files.exists(filePath)) return;
        try {
            for (String line : Files.readAllLines(filePath)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 3) continue;
                String table = parts[0].toUpperCase();
                String pkCol = parts[1].toUpperCase();
                String[] lookups = parts[2].toUpperCase().split(";");
                String[] entry = new String[1 + lookups.length];
                entry[0] = pkCol;
                System.arraycopy(lookups, 0, entry, 1, lookups.length);
                mappings.put(table, entry);
            }
        } catch (IOException e) {
            System.err.println("Subselect-Mappings laden fehlgeschlagen: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(filePath.getParent());
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String[]> e : mappings.entrySet()) {
                String[] entry = e.getValue();
                String lookups = String.join(";",
                    Arrays.copyOfRange(entry, 1, entry.length));
                lines.add(e.getKey() + "|" + entry[0] + "|" + lookups);
            }
            Files.write(filePath, lines);
        } catch (IOException e) {
            System.err.println("Subselect-Mappings speichern fehlgeschlagen: " + e.getMessage());
        }
    }
}
