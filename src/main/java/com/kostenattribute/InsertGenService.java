package com.kostenattribute;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Business-Logik fuer den INSERT-Generator:
 * Preset-basierte CSV-Persistenz und SQL-Script-Generierung.
 */
public class InsertGenService {

    private static final Path PRESET_DIR = Paths.get("config/insertgen");

    // ── Preset-Verwaltung ───────────────────────────────────────────────────

    public List<String> listPresets() {
        if (!Files.isDirectory(PRESET_DIR)) return new ArrayList<>();
        try (Stream<Path> files = Files.list(PRESET_DIR)) {
            return files
                    .filter(p -> p.toString().endsWith(".csv"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.csv$", ""))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public PresetData loadPreset(String presetName) throws IOException {
        Path file = PRESET_DIR.resolve(presetName + ".csv");
        if (!Files.exists(file)) return null;

        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String tableName = "";
            String pkColumn = "";
            String sequenceName = "";
            Map<String, String> fkSubselects = new LinkedHashMap<>();
            String headerLine = null;

            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#TABLE=")) {
                    tableName = line.substring(7).trim();
                } else if (line.startsWith("#PK=")) {
                    pkColumn = line.substring(4).trim();
                } else if (line.startsWith("#SEQUENCE=")) {
                    sequenceName = line.substring(10).trim();
                } else if (line.startsWith("#FK=")) {
                    String fkDef = line.substring(4);
                    int sep = fkDef.indexOf('|');
                    if (sep > 0) fkSubselects.put(fkDef.substring(0, sep), fkDef.substring(sep + 1));
                } else if (!line.startsWith("#") && !line.isBlank()) {
                    headerLine = line;
                    break;
                }
            }

            if (headerLine == null) {
                return new PresetData(tableName, new String[0], new ArrayList<>(), pkColumn, sequenceName, fkSubselects);
            }

            String[] columnNames = parseCsvLine(headerLine);
            List<String[]> rows = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cells = parseCsvLine(line);
                String[] row = new String[columnNames.length];
                for (int c = 0; c < Math.min(cells.length, columnNames.length); c++)
                    row[c] = cells[c];
                rows.add(row);
            }
            return new PresetData(tableName, columnNames, rows, pkColumn, sequenceName, fkSubselects);
        }
    }

    public void savePreset(String presetName, String tableName,
                           String[] columnNames, List<String[]> rows,
                           String pkColumn, String sequenceName,
                           Map<String, String> fkSubselects) throws IOException {
        Files.createDirectories(PRESET_DIR);
        Path file = PRESET_DIR.resolve(presetName + ".csv");
        try (BufferedWriter bw = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            bw.write("#TABLE=" + (tableName != null ? tableName : ""));
            bw.newLine();
            if (pkColumn != null && !pkColumn.isEmpty()) {
                bw.write("#PK=" + pkColumn);
                bw.newLine();
            }
            if (sequenceName != null && !sequenceName.isEmpty()) {
                bw.write("#SEQUENCE=" + sequenceName);
                bw.newLine();
            }
            if (fkSubselects != null) {
                for (Map.Entry<String, String> entry : fkSubselects.entrySet()) {
                    bw.write("#FK=" + entry.getKey() + "|" + entry.getValue());
                    bw.newLine();
                }
            }
            if (columnNames.length > 0) {
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
    }

    public void deletePreset(String presetName) throws IOException {
        Path file = PRESET_DIR.resolve(presetName + ".csv");
        Files.deleteIfExists(file);
    }

    // ── Script-Generierung ──────────────────────────────────────────────────

    public ScriptResult buildScript(String tableName, List<String> columnOrder,
                                     List<String[]> rows, String pkColumn, String sequenceName,
                                     Map<String, String> fkSubselects) {
        StringBuilder sb = new StringBuilder();
        sb.append("DELETE FROM ").append(tableName).append(";\n\n");

        boolean hasSequence = pkColumn != null && !pkColumn.isEmpty()
                && sequenceName != null && !sequenceName.isEmpty();
        int pkIndex = hasSequence ? columnOrder.indexOf(pkColumn) : -1;

        int insertCount = 0;
        for (String[] row : rows) {
            boolean hasContent = false;
            for (String cell : row) {
                if (cell != null && !cell.isBlank()) { hasContent = true; break; }
            }
            if (!hasContent) continue;

            List<String> selectValues = new ArrayList<>();
            List<String> whereConditions = new ArrayList<>();

            for (int c = 0; c < columnOrder.size(); c++) {
                String col = columnOrder.get(c);
                String cell = (c < row.length) ? row[c] : null;
                String sqlVal;

                String fkSub = (fkSubselects != null) ? fkSubselects.get(col) : null;
                if (c == pkIndex) {
                    sqlVal = sequenceName + ".NEXTVAL";
                } else if (fkSub != null && !fkSub.isEmpty()) {
                    String literal;
                    if (cell == null || cell.isBlank()) {
                        literal = "NULL";
                    } else {
                        String trimmed = cell.trim();
                        try {
                            Double.parseDouble(trimmed);
                            literal = trimmed;
                        } catch (NumberFormatException e2) {
                            literal = "'" + trimmed.replace("'", "''") + "'";
                        }
                    }
                    String resolved = fkSub.replace("{WERT}", literal);
                    sqlVal = resolved.startsWith("(") ? resolved : "(" + resolved + ")";
                } else {
                    sqlVal = (cell == null || cell.isBlank())
                            ? "NULL"
                            : "'" + cell.replace("'", "''") + "'";
                }

                selectValues.add(sqlVal);

                // WHERE NOT EXISTS: PK-Spalte mit Sequence ueberspringen
                if (c != pkIndex) {
                    if ("NULL".equals(sqlVal)) {
                        whereConditions.add(col + " IS NULL");
                    } else {
                        whereConditions.add(col + " = " + sqlVal);
                    }
                }
            }

            String colList = String.join(", ", columnOrder);
            sb.append("INSERT INTO ").append(tableName)
              .append(" (").append(colList).append(")\n")
              .append("SELECT ").append(String.join(", ", selectValues)).append(" FROM DUAL\n")
              .append("WHERE NOT EXISTS (SELECT 1 FROM ").append(tableName)
              .append(" WHERE ").append(String.join(" AND ", whereConditions))
              .append(");\n\n");
            insertCount++;
        }
        sb.append("\nCOMMIT;\n");
        return new ScriptResult(sb.toString(), insertCount);
    }

    public Path writeScript(String sql, String outputDir, String tableName) throws IOException {
        Path sqlFile = Paths.get(outputDir, tableName + ".sql");
        Files.createDirectories(sqlFile.getParent());
        Files.writeString(sqlFile, sql, StandardCharsets.UTF_8);
        return sqlFile;
    }

    // ── CSV-Hilfsmethoden ───────────────────────────────────────────────────

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

    // ── Ergebnis-Klassen ────────────────────────────────────────────────────

    public static class PresetData {
        public final String tableName;
        public final String[] columnNames;
        public final List<String[]> rows;
        public final String pkColumn;
        public final String sequenceName;
        public final Map<String, String> fkSubselects;

        PresetData(String tableName, String[] columnNames, List<String[]> rows,
                   String pkColumn, String sequenceName, Map<String, String> fkSubselects) {
            this.tableName     = tableName;
            this.columnNames   = columnNames;
            this.rows          = rows;
            this.pkColumn      = pkColumn;
            this.sequenceName  = sequenceName;
            this.fkSubselects  = fkSubselects != null ? fkSubselects : new LinkedHashMap<>();
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
