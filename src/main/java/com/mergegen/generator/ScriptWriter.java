package com.mergegen.generator;

import com.mergegen.model.ColumnInfo;
import com.mergegen.model.ForeignKeyRelation;
import com.mergegen.model.TableRow;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Schreibt die vom MergeScriptGenerator erzeugten Statements in eine .sql-Datei.
 *
 * Zwei Ausgabemodi:
 *   - Ohne Sequences (sequenceMap leer): isolierte MERGE-Statements (bisheriges Format)
 *   - Mit Sequences: PL/SQL-Block (DECLARE / BEGIN / END) mit Variablen für NEXTVAL-Werte,
 *     damit FK-Spalten in Child-Tabellen den neuen PK-Wert korrekt referenzieren.
 */
public class ScriptWriter {

    private final MergeScriptGenerator mergeGenerator = new MergeScriptGenerator();

    /**
     * Schreibt alle MERGE-Statements in eine .sql-Datei.
     *
     * @param orderedRows    Alle Datensätze in Einfüge-Reihenfolge (Eltern vor Kinder)
     * @param tableCounts    Anzahl Datensätze je Tabelle (für den Header)
     * @param rootTable      Name der führenden Tabelle
     * @param rootIds        ID-Wert(e) des Startobjekts
     * @param outputDir      Ausgabeverzeichnis
     * @param sequenceMap    Key: TABLE.PK_COL, Value: SEQUENCE_NAME (null = keine Sequences)
     * @param nameColumn     Optionale Name-Spalte der Root-Tabelle für ON-Matching
     * @param testSuffix     Timestamp-Suffix im Testmodus (leer = kein Testmodus)
     * @param fkRelations    Key: Child-Tabellenname (uppercase), Value: FK-Relationen für diese Tabelle
     * @param includeUpdate  Wenn true, wird WHEN MATCHED THEN UPDATE SET erzeugt
     * @return Dateipfad der erstellten .sql-Datei
     */
    public String write(List<TableRow> orderedRows,
                        Map<String, Integer> tableCounts,
                        String rootTable, List<String> rootIds,
                        String outputDir,
                        Map<String, String> sequenceMap,
                        String nameColumn,
                        String testSuffix,
                        Map<String, List<ForeignKeyRelation>> fkRelations,
                        boolean includeUpdate) throws IOException {

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename  = "MERGE_" + rootTable.toUpperCase() + ".sql";

        // Unterordner pro Root-Tabelle anlegen
        File tableDir = new File(outputDir, rootTable.toUpperCase());
        tableDir.mkdirs();

        // Alte Scripts im Tabellenordner löschen
        File[] oldFiles = tableDir.listFiles((d, name) -> name.endsWith(".sql"));
        if (oldFiles != null) {
            for (File f : oldFiles) f.delete();
        }

        File outputFile = new File(tableDir, filename);
        boolean hasChildren = orderedRows.stream()
            .anyMatch(r -> !r.getTableName().equalsIgnoreCase(rootTable));
        boolean needsSkipCheck = !includeUpdate && hasChildren;
        boolean usePlSql = (sequenceMap != null && !sequenceMap.isEmpty()) || needsSkipCheck;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writeHeader(writer, rootTable, rootIds, tableCounts, timestamp);

            if (usePlSql) {
                writePlSqlBlock(writer, orderedRows, tableCounts, rootTable, nameColumn,
                        testSuffix, sequenceMap, fkRelations != null ? fkRelations : new HashMap<>(), includeUpdate);
            } else {
                writePlainStatements(writer, orderedRows, tableCounts, rootTable, nameColumn, testSuffix, sequenceMap, includeUpdate);
            }

            writer.write("\n-- Ende des generierten Scripts\n");
        }

        System.out.println("Script erstellt: " + outputFile.getAbsolutePath());
        System.out.println("Gesamt: " + orderedRows.size() + " MERGE-Statement(s) in " + tableCounts.size() + " Tabelle(n)");
        return outputFile.getAbsolutePath();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Altes Format: isolierte MERGE-Statements (keine Sequences)
    // ─────────────────────────────────────────────────────────────────────────

    private void writePlainStatements(BufferedWriter writer,
                                      List<TableRow> orderedRows,
                                      Map<String, Integer> tableCounts,
                                      String rootTable, String nameColumn, String testSuffix,
                                      Map<String, String> sequenceMap,
                                      boolean includeUpdate) throws IOException {
        String currentTable = null;
        for (TableRow row : orderedRows) {
            if (!row.getTableName().equals(currentTable)) {
                currentTable = row.getTableName();
                writeTableHeader(writer, currentTable, tableCounts.getOrDefault(currentTable, 0));
            }
            write(writer, mergeGenerator.generate(row, sequenceMap, rootTable, nameColumn, testSuffix, null, includeUpdate));
            writer.write("\n");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Neues Format: PL/SQL-Block mit Variablen für Sequence-PKs
    // ─────────────────────────────────────────────────────────────────────────

    private void writePlSqlBlock(BufferedWriter writer,
                                 List<TableRow> orderedRows,
                                 Map<String, Integer> tableCounts,
                                 String rootTable, String nameColumn, String testSuffix,
                                 Map<String, String> sequenceMap,
                                 Map<String, List<ForeignKeyRelation>> fkRelations,
                                 boolean includeUpdate) throws IOException {

        // ── Phase 1: varMap aufbauen ───────────────────────────────────────────
        // varMap:  "TABLE.PKCOL#altWert" → Variablenname
        // varSeq:  Variablenname → Sequence-Name
        // varType: Variablenname → Oracle-Typ ("NUMBER" oder "VARCHAR2(200)")
        Map<String, String> varMap  = new LinkedHashMap<>();
        Map<String, String> varSeq  = new LinkedHashMap<>();
        Map<String, String> varType = new LinkedHashMap<>();
        // Counter pro "TABLE.PKCOL" für eindeutige Variablennamen
        Map<String, Integer> varCounter = new HashMap<>();

        for (TableRow row : orderedRows) {
            String table = row.getTableName().toUpperCase();
            for (ColumnInfo col : row.getColumns().values()) {
                if (!col.isPrimaryKey()) continue;
                String seqKey = table + "." + col.getName();
                String seqName = sequenceMap.get(seqKey);
                if (seqName == null || seqName.isEmpty()) continue;

                String altWert = row.getValues().get(col.getName());
                String mapKey  = table + "." + col.getName() + "#" + altWert;
                if (varMap.containsKey(mapKey)) continue; // dieselbe Zeile nicht doppelt

                int n = varCounter.merge(col.getName(), 1, Integer::sum);
                String varName = buildVarName(col.getName(), n);

                varMap.put(mapKey, varName);
                varSeq.put(varName, seqName.toUpperCase());
                // Typ: Zahlenliteral (kein ' am Anfang) → NUMBER, sonst VARCHAR2
                boolean isNumber = altWert != null && !altWert.startsWith("'");
                varType.put(varName, isNumber ? "NUMBER" : "VARCHAR2(200)");
            }
        }

        // ── Phase 2: DECLARE-Block ─────────────────────────────────────────────
        boolean hasChildren = orderedRows.stream()
            .anyMatch(r -> !r.getTableName().equalsIgnoreCase(rootTable));
        boolean needsSkipCheck = !includeUpdate && hasChildren;

        writer.write("DECLARE\n");
        if (needsSkipCheck) {
            writer.write("  v_root_count NUMBER := 0;\n");
        }
        for (Map.Entry<String, String> e : varType.entrySet()) {
            writer.write("  " + e.getKey() + " " + e.getValue() + ";\n");
        }
        writer.write("BEGIN\n");

        // ── Phase 3: MERGE-Statements mit Variablen ────────────────────────────
        String currentTable = null;
        boolean rootCheckWritten = false;
        for (TableRow row : orderedRows) {
            String table = row.getTableName().toUpperCase();

            // Abschnitts-Kommentar bei Tabellenwechsel
            if (!table.equals(currentTable)) {
                // Beim Verlassen der Root-Tabelle: Skip-Check einfügen
                if (needsSkipCheck && !rootCheckWritten
                        && currentTable != null
                        && currentTable.equalsIgnoreCase(rootTable)
                        && !table.equalsIgnoreCase(rootTable)) {
                    writer.write("\n  IF v_root_count = 0 THEN\n");
                    writer.write("    RETURN;\n");
                    writer.write("  END IF;\n");
                    rootCheckWritten = true;
                }
                currentTable = table;
                int count = tableCounts.getOrDefault(table, 0);
                writer.write("\n  -- ============================================================\n");
                writer.write("  -- Tabelle: " + table);
                writer.write("  (" + count + " Datensatz" + (count != 1 ? "e" : "") + ")\n");
                writer.write("  -- ============================================================\n");
            }

            // colVarSubstitutions für DIESE Zeile aufbauen
            Map<String, String> colVarSubs = buildColVarSubstitutions(row, table, sequenceMap, varMap, fkRelations);

            // NEXTVAL-Statement vor dem MERGE (nur wenn diese Zeile eigene sequence-PK hat)
            for (ColumnInfo col : row.getColumns().values()) {
                if (!col.isPrimaryKey()) continue;
                String varName = colVarSubs.get(col.getName());
                if (varName == null) continue;
                String seqName = varSeq.get(varName);
                if (seqName == null) continue;
                writer.write("\n  SELECT " + seqName + ".NEXTVAL INTO " + varName + " FROM DUAL;\n");
            }

            // MERGE-Statement (eingerückt für PL/SQL-Körper)
            writer.write("\n");
            String mergeStmt = mergeGenerator.generate(row, sequenceMap, rootTable, nameColumn, testSuffix, colVarSubs, includeUpdate);
            // Jede Zeile des MERGE um 2 Spaces einrücken
            for (String line : mergeStmt.split("\n", -1)) {
                writer.write("  " + line + "\n");
            }

            // Nach Root-MERGE: SQL%ROWCOUNT akkumulieren
            if (needsSkipCheck && table.equalsIgnoreCase(rootTable)) {
                writer.write("  v_root_count := v_root_count + SQL%ROWCOUNT;\n");
            }
        }

        // ── Phase 4: END; ──────────────────────────────────────────────────────
        writer.write("\nEND;\n/\n");
    }

    /**
     * Baut die colVarSubstitutions-Map für eine einzelne Zeile:
     *   - Eigene PK-Spalten mit Sequence → eigene Variable
     *   - FK-Spalten auf sequence-gemappte Parent-Tabellen → Variable des Parent-PKs
     */
    Map<String, String> buildColVarSubstitutions(
            TableRow row, String tableUpper,
            Map<String, String> sequenceMap,
            Map<String, String> varMap,
            Map<String, List<ForeignKeyRelation>> fkRelations) {

        Map<String, String> subs = new HashMap<>();

        for (ColumnInfo col : row.getColumns().values()) {
            String colName = col.getName();
            String colVal  = row.getValues().get(colName);

            if (col.isPrimaryKey()) {
                // Eigene PK-Variable
                String mapKey = tableUpper + "." + colName + "#" + colVal;
                String varName = varMap.get(mapKey);
                if (varName != null) subs.put(colName, varName);
            } else {
                // FK-Spalte? → Relation nachschlagen
                List<ForeignKeyRelation> rels = fkRelations.get(tableUpper);
                if (rels == null) continue;
                for (ForeignKeyRelation rel : rels) {
                    if (!rel.getFkColumn().equalsIgnoreCase(colName)) continue;
                    // Ist der Parent sequence-gemappt?
                    String parentTable  = rel.getParentTable().toUpperCase();
                    String parentPkCol  = rel.getParentPkColumn().toUpperCase();
                    String parentSeqKey = parentTable + "." + parentPkCol;
                    if (!sequenceMap.containsKey(parentSeqKey)) continue;
                    // Variable des Parent-PKs suchen
                    String mapKey  = parentTable + "." + parentPkCol + "#" + colVal;
                    String varName = varMap.get(mapKey);
                    if (varName != null) subs.put(colName, varName);
                    break;
                }
            }
        }
        return subs;
    }

    /**
     * Erzeugt einen Oracle-kompatiblen PL/SQL-Variablennamen.
     * Format: v_<PKCOL>_<N>, maximal 30 Zeichen (Oracle-Limit).
     * Beispiel: AUFTRPOS_ID, 2 → v_AUFTRPOS_ID_2
     *           SEHR_LANGER_SPALTENNAME_XYZ, 1 → v_SEHR_LANGER_SPALTENNAM_1
     */
    String buildVarName(String pkCol, int n) {
        String suffix = "_" + n;
        String base   = "v_" + pkCol.toUpperCase();
        int maxBase   = 30 - suffix.length();
        if (base.length() > maxBase) base = base.substring(0, maxBase);
        return base + suffix;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hilfsmethoden
    // ─────────────────────────────────────────────────────────────────────────

    private void writeTableHeader(BufferedWriter writer, String table, int count) throws IOException {
        writer.write("\n-- ============================================================\n");
        writer.write("-- Tabelle: " + table);
        writer.write("  (" + count + " Datensatz" + (count != 1 ? "e" : "") + ")\n");
        writer.write("-- ============================================================\n\n");
    }

    private void writeHeader(BufferedWriter writer, String rootTable, List<String> rootIds,
                             Map<String, Integer> tableCounts, String timestamp) throws IOException {
        writer.write("-- =================================================================\n");
        writer.write("-- Oracle MERGE Script\n");
        writer.write("-- Generiert: " + timestamp.replace("_", " ").replace(
            timestamp.substring(9), timestamp.substring(9).replace(
                timestamp.substring(11), ":"  + timestamp.substring(11, 13) + ":" + timestamp.substring(13))) + "\n");
        writer.write("-- Fuehrende Tabelle: " + rootTable.toUpperCase() + "\n");
        if (rootIds.size() == 1) {
            writer.write("-- ID: " + rootIds.get(0) + "\n");
        } else {
            writer.write("-- IDs: " + String.join(", ", rootIds) + "\n");
        }
        writer.write("-- Traversierte Tabellen:\n");
        tableCounts.forEach((table, count) ->
            write(writer, "--   " + table + ": " + count + " Datensatz" + (count != 1 ? "e" : "") + "\n")
        );
        writer.write("-- =================================================================\n\n");
    }

    /**
     * Wrapper für BufferedWriter.write(), der IOException in RuntimeException
     * umwandelt (nötig in Lambda-Ausdrücken).
     */
    private void write(BufferedWriter w, String s) {
        try { w.write(s); } catch (IOException e) { throw new RuntimeException(e); }
    }
}
