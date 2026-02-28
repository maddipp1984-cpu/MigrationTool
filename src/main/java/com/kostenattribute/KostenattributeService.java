package com.kostenattribute;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Business-Logik für den Kostenattribute-Generator:
 * CSV-Persistenz und SQL-Script-Generierung.
 * Spaltennamen sind dynamisch – sie werden im CSV-Header gespeichert und geladen.
 */
public class KostenattributeService {

    static final int      ROW_COUNT       = 200;
    static final String   TABLE_NAME      = "SUW_MIG_KOSTENATTRIBUTE";
    static final String[] DEFAULT_COLUMNS = {
        "Spalte_01", "Spalte_02", "Spalte_03", "Spalte_04", "Spalte_05",
        "Spalte_06", "Spalte_07", "Spalte_08", "Spalte_09", "Spalte_10"
    };

    private static final Path DATA_FILE = Paths.get("config/kostenattribute/kostenattribute-data.csv");

    // ── Persistenz ────────────────────────────────────────────────────────────

    /**
     * Lädt Spaltennamen und Zeilen aus der CSV.
     * Gibt immer genau {@link #ROW_COUNT} Zeilen zurück (mit leeren Zeilen aufgefüllt).
     * Existiert keine Datei, werden die Default-Spalten verwendet.
     */
    public LoadResult load() throws IOException {
        if (!Files.exists(DATA_FILE)) {
            List<String[]> rows = new ArrayList<>();
            while (rows.size() < ROW_COUNT)
                rows.add(new String[DEFAULT_COLUMNS.length]);
            return new LoadResult(DEFAULT_COLUMNS.clone(), rows);
        }
        try (BufferedReader br = Files.newBufferedReader(DATA_FILE, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            String[] columnNames = (headerLine != null && !headerLine.isBlank())
                    ? parseCsvLine(headerLine)
                    : DEFAULT_COLUMNS.clone();
            List<String[]> rows = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) {
                String[] cells = parseCsvLine(line);
                String[] row = new String[columnNames.length];
                for (int c = 0; c < Math.min(cells.length, columnNames.length); c++)
                    row[c] = cells[c];
                rows.add(row);
            }
            while (rows.size() < ROW_COUNT)
                rows.add(new String[columnNames.length]);
            return new LoadResult(columnNames, rows);
        }
    }

    /**
     * Speichert Spaltennamen (in View-Reihenfolge) und Zeilen als CSV.
     */
    public void save(String[] columnNames, List<String[]> rows) throws IOException {
        Files.createDirectories(DATA_FILE.getParent());
        try (BufferedWriter bw = Files.newBufferedWriter(DATA_FILE, StandardCharsets.UTF_8)) {
            List<String> header = new ArrayList<>();
            for (String name : columnNames) header.add(escapeCsv(name));
            bw.write(String.join(";", header));
            bw.newLine();
            for (String[] row : rows) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < columnNames.length; c++) {
                    String val = (c < row.length && row[c] != null) ? row[c] : "";
                    cells.add(escapeCsv(val));
                }
                bw.write(String.join(";", cells));
                bw.newLine();
            }
        }
    }

    // ── Script-Generierung ────────────────────────────────────────────────────

    /**
     * Erzeugt den SQL-Script-Inhalt (DELETE + INSERTs + COMMIT).
     *
     * @param columnOrder Spaltennamen in der aktuellen View-Reihenfolge
     * @param rows        Zellwerte passend zur columnOrder
     * @return {@link ScriptResult} mit SQL-Text und Anzahl der INSERTs
     */
    public ScriptResult buildScript(List<String> columnOrder, List<String[]> rows) {
        String colList = String.join(", ", columnOrder);
        StringBuilder sb = new StringBuilder();
        sb.append("DELETE FROM ").append(TABLE_NAME).append(";\n\n");
        int insertCount = 0;
        for (String[] row : rows) {
            boolean hasContent = false;
            for (String cell : row) {
                if (cell != null && !cell.isBlank()) { hasContent = true; break; }
            }
            if (!hasContent) continue;
            List<String> values = new ArrayList<>();
            for (String cell : row) {
                values.add((cell == null || cell.isBlank())
                        ? "NULL"
                        : "'" + cell.replace("'", "''") + "'");
            }
            sb.append("INSERT INTO ").append(TABLE_NAME)
              .append(" (").append(colList).append(")")
              .append(" VALUES (").append(String.join(", ", values)).append(");\n");
            insertCount++;
        }
        sb.append("\nCOMMIT;\n");
        return new ScriptResult(sb.toString(), insertCount);
    }

    /** Schreibt den SQL-Script ins Ausgabeverzeichnis und gibt den Dateipfad zurück. */
    public Path writeScript(String sql, String outputDir) throws IOException {
        Path sqlFile = Paths.get(outputDir, TABLE_NAME + ".sql");
        Files.createDirectories(sqlFile.getParent());
        Files.writeString(sqlFile, sql, StandardCharsets.UTF_8);
        return sqlFile;
    }

    // ── CSV-Hilfsmethoden ─────────────────────────────────────────────────────

    static String escapeCsv(String value) {
        if (value.contains(";") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }

    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); i++;
                } else if (ch == '"') {
                    inQuotes = false;
                } else {
                    sb.append(ch);
                }
            } else {
                if      (ch == '"') { inQuotes = true; }
                else if (ch == ';') { fields.add(sb.toString()); sb.setLength(0); }
                else                { sb.append(ch); }
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    // ── Ergebnis-Klassen ──────────────────────────────────────────────────────

    public static class LoadResult {
        public final String[]       columnNames;
        public final List<String[]> rows;

        LoadResult(String[] columnNames, List<String[]> rows) {
            this.columnNames = columnNames;
            this.rows        = rows;
        }
    }

    public static class ScriptResult {
        public final String sql;
        public final int    insertCount;

        ScriptResult(String sql, int insertCount) {
            this.sql         = sql;
            this.insertCount = insertCount;
        }
    }
}
