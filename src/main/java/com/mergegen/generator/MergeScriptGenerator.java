package com.mergegen.generator;

import com.mergegen.model.ColumnInfo;
import com.mergegen.model.TableRow;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Erzeugt Oracle MERGE-Statements aus TableRow-Objekten.
 *
 * Ein MERGE-Statement hat zwei Abschnitte:
 *   1. USING (SELECT ... FROM DUAL)  – liefert die neuen Werte als inline-Tabelle
 *   2. ON (...)                      – Vergleichsbedingung über den Primary Key
 *   3. WHEN NOT MATCHED THEN INSERT  – Datensatz fehlt → einfügen
 *
 * Optional: WHEN MATCHED THEN UPDATE für alle Nicht-PK-Spalten.
 */
public class MergeScriptGenerator {

    /**
     * Erzeugt ein vollständiges MERGE-Statement für eine einzelne Tabellenzeile.
     *
     * Die Werte in TableRow sind bereits als SQL-Literale gespeichert
     * (z.B. 42, 'Text', TO_DATE(...)) und werden direkt eingebettet.
     *
     * @param sequenceMap Key: TABLE.PK_COL, Value: SEQUENCE_NAME.
     *                    PK-Spalten mit Eintrag erhalten SEQ.NEXTVAL statt Quellwert.
     * @param rootTable   Name der führenden Tabelle (zum Erkennen der Root-Zeile)
     * @param nameColumn  Optionale Name-Spalte der Root-Tabelle; wenn gesetzt und Zeile
     *                    gehört zur Root-Tabelle, wird ON über diese Spalte statt PK gebaut.
     */
    /**
     * @param testSuffix  Wenn nicht leer: wird im Testmodus an den Wert der nameColumn
     *                    der Root-Zeile angehängt (z.B. "_20260224143022"), damit der
     *                    Datensatz stets als neu gilt und immer per INSERT angelegt wird.
     *                    Nur wirksam wenn nameColumn gesetzt ist.
     */
    /**
     * @param colVarSubstitutions Spaltenname → PL/SQL-Variablenname für DIESE Zeile.
     *                            Priorität: colVarSubstitutions > sequenceMap > SQL-Literal.
     *                            Null oder leer = kein PL/SQL-Pfad aktiv.
     */
    /**
     * @param includeUpdate Wenn true, wird WHEN MATCHED THEN UPDATE SET für alle
     *                      Nicht-PK-Spalten angehängt (Spalten mit Sequence/ColVar ausgenommen).
     */
    public String generate(TableRow row, Map<String, String> sequenceMap,
                           String rootTable, String nameColumn, String testSuffix,
                           Map<String, String> colVarSubstitutions,
                           boolean includeUpdate) {
        // PK-Spalten für die ON-Bedingung
        List<ColumnInfo> pkCols = row.getColumns().values().stream()
            .filter(ColumnInfo::isPrimaryKey)
            .collect(Collectors.toList());

        Map<String, String> values = row.getValues();
        String table  = row.getTableName();
        String indent = "    ";

        StringBuilder sb = new StringBuilder();

        // ── MERGE INTO ────────────────────────────────────────────────────────
        sb.append("MERGE INTO ").append(table).append(" tgt\n");

        // ── USING (SELECT <werte> FROM DUAL) ──────────────────────────────────
        // DUAL ist Oracles einzeilige Dummy-Tabelle; das SELECT liefert die
        // neuen Werte als benannte Spalten, auf die später via src.<spalte> zugegriffen wird.
        sb.append("USING (\n");
        sb.append(indent).append("SELECT\n");
        boolean applyTestSuffix = testSuffix != null && !testSuffix.isEmpty()
            && nameColumn != null && !nameColumn.isEmpty()
            && table.equalsIgnoreCase(rootTable);

        List<String> selectItems = row.getColumns().values().stream()
            .map(col -> {
                // Priorität 1: PL/SQL-Variable (eigener PK oder FK auf sequence-gemappten Parent)
                String varName = (colVarSubstitutions != null) ? colVarSubstitutions.get(col.getName()) : null;
                String seqKey = table + "." + col.getName();
                String seqName = sequenceMap != null ? sequenceMap.get(seqKey) : null;
                String val;
                if (varName != null && !varName.isEmpty()) {
                    val = varName;                              // PL/SQL-Variable
                } else if (seqName != null && !seqName.isEmpty()) {
                    val = seqName + ".NEXTVAL";                 // direktes NEXTVAL (altes Verhalten)
                } else {
                    val = values.get(col.getName());            // SQL-Literal
                }
                // Testmodus: Suffix an nameColumn-Wert anhängen (SQL-String-Literal)
                if (applyTestSuffix && col.getName().equalsIgnoreCase(nameColumn)
                        && val != null && val.startsWith("'") && val.endsWith("'")) {
                    val = val.substring(0, val.length() - 1) + testSuffix + "'";
                }
                return indent + indent + val + " AS " + col.getName();
            })
            .collect(Collectors.toList());
        sb.append(String.join(",\n", selectItems));
        sb.append("\n").append(indent).append("FROM DUAL\n");
        sb.append(") src\n");

        // ── ON ────────────────────────────────────────────────────────────────
        boolean useNameColumn = nameColumn != null && !nameColumn.isEmpty()
            && table.equalsIgnoreCase(rootTable);

        if (useNameColumn) {
            // Name-Spalte als Merge-Key: bestehender Datensatz mit gleichem Namen
            // wird aktualisiert; neuer Name → INSERT mit frischem Sequence-PK
            sb.append("ON (tgt.").append(nameColumn).append(" = src.").append(nameColumn).append(")\n");
        } else if (pkCols.isEmpty()) {
            // Kein PK: Statement wird als Kommentar markiert und nie gematcht (1=0)
            sb.append("-- WARNUNG: Kein PK gefunden fuer ").append(table).append(" - ON-Klausel unvollstaendig\n");
            sb.append("ON (1=0)\n");
        } else {
            // Standard: PK-basiertes Matching (bei NEXTVAL immer INSERT)
            String onClause = pkCols.stream()
                .map(pk -> "tgt." + pk.getName() + " = src." + pk.getName())
                .collect(Collectors.joining(" AND "));
            sb.append("ON (").append(onClause).append(")\n");
        }

        // ── WHEN MATCHED THEN UPDATE (optional) ──────────────────────────────
        if (includeUpdate) {
            // Alle Nicht-PK-Spalten, die keinen Sequence- oder ColVar-Ersatz haben
            java.util.Set<String> pkNames = pkCols.stream()
                .map(ColumnInfo::getName)
                .collect(Collectors.toSet());
            List<String> updateCols = row.getColumns().values().stream()
                .map(ColumnInfo::getName)
                .filter(name -> !pkNames.contains(name))
                .filter(name -> {
                    String seqKey = table + "." + name;
                    boolean hasSeq = sequenceMap != null && sequenceMap.containsKey(seqKey);
                    boolean hasVar = colVarSubstitutions != null && colVarSubstitutions.containsKey(name);
                    return !hasSeq && !hasVar;
                })
                .collect(Collectors.toList());
            if (!updateCols.isEmpty()) {
                sb.append("WHEN MATCHED THEN\n");
                sb.append(indent).append("UPDATE SET\n");
                String setClauses = updateCols.stream()
                    .map(col -> indent + indent + "tgt." + col + " = src." + col)
                    .collect(Collectors.joining(",\n"));
                sb.append(setClauses).append("\n");
            }
        }

        // ── WHEN NOT MATCHED THEN INSERT ──────────────────────────────────────
        // Alle Spalten inklusive PK werden eingefügt
        sb.append("WHEN NOT MATCHED THEN\n");
        String colList = row.getColumns().keySet().stream()
            .collect(Collectors.joining(", "));
        String valList = row.getColumns().keySet().stream()
            .map(col -> "src." + col)
            .collect(Collectors.joining(", "));
        sb.append(indent).append("INSERT (").append(colList).append(")\n");
        sb.append(indent).append("VALUES (").append(valList).append(")");
        sb.append(";\n");

        return sb.toString();
    }
}
