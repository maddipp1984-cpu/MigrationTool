package com.mergegen.service;

import com.mergegen.analyzer.SchemaAnalyzer;
import com.mergegen.config.VirtualFkStore;
import com.mergegen.model.DependencyNode;
import com.mergegen.model.ForeignKeyRelation;
import com.mergegen.model.TableRow;
import com.mergegen.model.TraversalResult;

import java.sql.SQLException;
import java.util.*;

/**
 * Traversiert ausgehend von einer führenden Tabelle alle abhängigen Datensätze
 * über Foreign-Key-Beziehungen (Breitensuche / BFS).
 *
 * Das Ergebnis enthält:
 *   - einen Abhängigkeitsbaum (DependencyNode) für die GUI-Vorschau
 *   - eine topologisch geordnete Liste aller Zeilen für die Script-Generierung
 *     (Eltern immer vor ihren Kindern, damit FK-Constraints beim Einspielen
 *      nicht verletzt werden)
 *
 * Zyklus-Schutz: Jede Tabelle+PK-Kombination wird nur einmal verarbeitet.
 * Das verhindert endlose Schleifen bei gegenseitigen FK-Referenzen.
 */
public class TraversalService {

    private final SchemaAnalyzer analyzer;
    private final VirtualFkStore virtualFkStore;
    private java.util.function.Consumer<String> logger;

    public TraversalService(SchemaAnalyzer analyzer, VirtualFkStore virtualFkStore) {
        this.analyzer       = analyzer;
        this.virtualFkStore = virtualFkStore;
    }

    /** Setzt einen Logger-Callback für Fortschrittsmeldungen. */
    public void setLogger(java.util.function.Consumer<String> logger) {
        this.logger = logger;
    }

    private void log(String msg) {
        if (logger != null) logger.accept(msg);
    }

    /**
     * Startet den Traversal ab der führenden Tabelle mit dem angegebenen Wert.
     *
     * Ablauf:
     *   1. PK der Wurzeltabelle aus dem Oracle-Dictionary ermitteln
     *   2. Wurzel-Datensatz über die angegebene (oder auto-erkannte PK-) Spalte laden
     *   3. Echten PK-Wert aus dem geladenen Datensatz extrahieren (für BFS-Traversal)
     *   4. BFS: für jeden Datensatz alle FK-Kinder laden und in die Queue stellen
     *   5. Ergebnis als TraversalResult zurückgeben
     *
     * @param rootTable   Name der führenden Tabelle (Groß-/Kleinschreibung egal)
     * @param rootColumn  Spaltenname für den initialen Lookup (null/leer → PK auto-ermitteln)
     * @param rootIdValue Suchwert als String (Zahl oder Text – wird automatisch gequotet)
     */
    public TraversalResult traverse(String rootTable, String rootColumn, String rootIdValue) throws SQLException {
        log("PK ermitteln für " + rootTable + "...");
        List<String> pkCols = analyzer.getPrimaryKeyColumns(rootTable);
        if (pkCols.isEmpty()) {
            throw new IllegalStateException("Kein Primary Key gefunden für Tabelle: " + rootTable);
        }
        String rootPkCol = pkCols.get(0);

        // Lookup-Spalte: explizit angegeben oder Fallback auf ersten PK
        String lookupColumn = (rootColumn != null && !rootColumn.isBlank())
            ? rootColumn.trim().toUpperCase()
            : rootPkCol;

        // Benutzereingabe in ein gültiges SQL-Literal umwandeln
        String rootValueLiteral = toSqlLiteral(rootIdValue);

        // Root-Row per Lookup-Spalte laden
        log("Lade Root-Datensatz: " + rootTable + "." + lookupColumn + " = " + rootValueLiteral);
        TableRow rootRow = analyzer.fetchRowByPk(rootTable, lookupColumn, rootValueLiteral);

        // Für die BFS-Traversal den echten PK-Wert verwenden,
        // da FK-Referenzen immer auf den PK zeigen (nicht auf beliebige Spalten)
        String rootPkLiteral = rootRow.getPkRawValue(rootPkCol);

        // orderedRows: BFS-Reihenfolge = Eltern vor Kinder → geeignet für MERGE-Reihenfolge
        List<TableRow>       orderedRows = new ArrayList<>();
        // tableCounts: LinkedHashMap erhält die Einfügereihenfolge für den Script-Header
        Map<String, Integer> tableCounts = new LinkedHashMap<>();
        // visited: verhindert doppelte Verarbeitung und Endlosschleifen bei Zyklen
        Set<String>          visited     = new HashSet<>();
        // fkRelations: Key = Child-Tabellenname, Value = alle FK-Relationen dieser Child-Tabelle
        Map<String, List<ForeignKeyRelation>> fkRelations = new HashMap<>();

        DependencyNode rootNode = new DependencyNode(rootTable, lookupColumn, rootValueLiteral, 1);
        String rootLabel = extractLabel(rootRow, pkCols);
        if (rootLabel != null) rootNode.addRowLabel(rootLabel);

        // ── Root: ausgehende FKs verfolgen (was referenziert die Root-Tabelle?) ──
        log("Ausgehende FK-Spalten suchen für " + rootTable + "...");
        List<ForeignKeyRelation> rootOutgoing = analyzer.getOutgoingFkRelations(rootTable);
        if (virtualFkStore != null) {
            // Virtuelle FKs, bei denen Root die Child-Tabelle ist (Root verweist auf Parent)
            for (ForeignKeyRelation vfk : virtualFkStore.getRelationsForChild(rootTable)) {
                rootOutgoing.add(vfk);
            }
        }

        // Root-Datensatz als erstes aufnehmen
        String rootKey = buildVisitedKey(rootTable, rootRow, pkCols, rootPkLiteral);
        visited.add(rootKey);
        orderedRows.add(rootRow);
        tableCounts.merge(rootTable, 1, Integer::sum);

        // Referenzierte Datensätze laden und als Startpunkte für BFS verwenden
        // Queue-Einträge: [Tabellenname, PK-Literal (erster PK), TableRow-Objekt, DependencyNode]
        Queue<Object[]> queue = new ArrayDeque<>();

        for (ForeignKeyRelation rel : rootOutgoing) {
            String fkValue = rootRow.getPkRawValue(rel.getFkColumn());
            if (fkValue == null || "NULL".equals(fkValue)) continue;

            log("  → " + rel.getParentTable() + "." + rel.getParentPkColumn() + " = " + fkValue);
            List<TableRow> refRows = analyzer.fetchChildRows(
                rel.getParentTable(), rel.getParentPkColumn(), fkValue);
            if (refRows.isEmpty()) continue;
            log("    " + refRows.size() + " Zeile(n) gefunden");

            List<String> refPkCols = analyzer.getPrimaryKeyColumns(rel.getParentTable());
            String refPkCol = refPkCols.isEmpty() ? rel.getParentPkColumn() : refPkCols.get(0);

            DependencyNode refNode = new DependencyNode(
                rel.getParentTable(), rel.getParentPkColumn(), fkValue, refRows.size());
            rootNode.addChild(refNode);

            for (TableRow refRow : refRows) {
                String label = extractLabel(refRow, refPkCols);
                if (label != null) refNode.addRowLabel(label);
            }

            // FK-Relation speichern (Root → referenzierte Tabelle)
            fkRelations.computeIfAbsent(rootTable.toUpperCase(), k -> new ArrayList<>())
                       .add(rel);

            for (TableRow refRow : refRows) {
                String refPkValue = refRow.getPkRawValue(refPkCol);
                String refKey = buildVisitedKey(rel.getParentTable(), refRow, refPkCols, refPkValue);
                if (!visited.contains(refKey)) {
                    queue.add(new Object[]{rel.getParentTable(), refPkValue, refRow, refNode});
                }
            }
        }

        // ── BFS: ab den referenzierten Tabellen normal nach unten (eingehende FKs) ──
        while (!queue.isEmpty()) {
            Object[] entry        = queue.poll();
            String currentTable   = (String) entry[0];
            String currentPkValue = (String) entry[1];
            TableRow currentRow   = (TableRow) entry[2];
            DependencyNode node   = (DependencyNode) entry[3];

            String key = buildVisitedKey(currentTable, currentRow,
                analyzer.getPrimaryKeyColumns(currentTable), currentPkValue);
            if (visited.contains(key)) continue;
            visited.add(key);

            orderedRows.add(currentRow);
            tableCounts.merge(currentTable, 1, Integer::sum);

            log("Kinder suchen für " + currentTable + " (bisher: " + orderedRows.size() + " Zeilen in " + tableCounts.size() + " Tabellen)");
            List<ForeignKeyRelation> realRelations = analyzer.getChildRelations(currentTable);

            if (virtualFkStore != null) {
                for (ForeignKeyRelation vfk : virtualFkStore.getRelationsForParent(currentTable)) {
                    boolean nowReal = realRelations.stream().anyMatch(r ->
                        r.getChildTable().equalsIgnoreCase(vfk.getChildTable()) &&
                        r.getFkColumn().equalsIgnoreCase(vfk.getFkColumn()));
                    if (nowReal) {
                        virtualFkStore.remove(vfk);
                    }
                }
            }

            List<ForeignKeyRelation> childRelations = new ArrayList<>(realRelations);
            if (virtualFkStore != null) {
                childRelations.addAll(virtualFkStore.getRelationsForParent(currentTable));
            }

            for (ForeignKeyRelation rel : childRelations) {
                fkRelations.computeIfAbsent(rel.getChildTable().toUpperCase(), k -> new ArrayList<>())
                           .add(rel);
            }

            for (ForeignKeyRelation rel : childRelations) {
                log("  → " + rel.getChildTable() + "." + rel.getFkColumn() + " = " + currentPkValue);
                List<TableRow> childRows = analyzer.fetchChildRows(
                    rel.getChildTable(), rel.getFkColumn(), currentPkValue);

                if (childRows.isEmpty()) continue;
                log("    " + childRows.size() + " Zeile(n) gefunden");

                DependencyNode childNode = new DependencyNode(
                    rel.getChildTable(), rel.getFkColumn(), currentPkValue, childRows.size());
                node.addChild(childNode);

                List<String> childPkCols = analyzer.getPrimaryKeyColumns(rel.getChildTable());
                String childPkCol = childPkCols.isEmpty() ? rel.getFkColumn() : childPkCols.get(0);

                for (TableRow childRow : childRows) {
                    String label = extractLabel(childRow, childPkCols);
                    if (label != null) childNode.addRowLabel(label);
                }

                for (TableRow childRow : childRows) {
                    String childPkValue = childRow.getPkRawValue(childPkCol);
                    String childKey = buildVisitedKey(rel.getChildTable(), childRow,
                        childPkCols, childPkValue);
                    if (!visited.contains(childKey)) {
                        queue.add(new Object[]{
                            rel.getChildTable(), childPkValue, childRow, childNode
                        });
                    }
                }
            }
        }

        return new TraversalResult(rootNode, orderedRows, tableCounts, fkRelations);
    }

    /**
     * Baut einen eindeutigen visited-Key aus allen PK-Spalten.
     * Bei zusammengesetzten PKs werden alle Werte einbezogen,
     * damit Zeilen mit gleichem Wert in der ersten PK-Spalte
     * aber unterschiedlichen Werten in weiteren PK-Spalten
     * korrekt als separate Datensätze erkannt werden.
     *
     * @param table      Tabellenname
     * @param row        Die Tabellenzeile mit allen Werten
     * @param pkCols     Alle PK-Spaltennamen (kann leer sein)
     * @param fallbackPk Fallback-PK-Wert (erster PK oder FK-Spalte)
     */
    private String buildVisitedKey(String table, TableRow row, List<String> pkCols, String fallbackPk) {
        if (pkCols.size() <= 1) {
            return table + "#" + fallbackPk;
        }
        StringBuilder sb = new StringBuilder(table);
        for (String pkCol : pkCols) {
            sb.append("#").append(row.getPkRawValue(pkCol));
        }
        return sb.toString();
    }

    /**
     * Wandelt einen Benutzereingabe-String in ein Oracle-SQL-Literal um.
     *
     * Logik: Wenn der Wert vollständig als Long parsebar ist → Zahl (kein Quoting).
     * Andernfalls → String-Literal mit einfachen Hochkommata, interne Hochkommata
     * werden durch doppelte Hochkommata escaped.
     *
     * Beispiele:
     *   "42"       → 42
     *   "ORD-0001" → 'ORD-0001'
     *   "O'Brien"  → 'O''Brien'
     */
    /**
     * Extrahiert den ersten lesbaren String-Wert einer Zeile für die Baum-Anzeige.
     * Überspringt PK-Spalten, NULL-Werte und numerische Literale.
     * String-Literale werden ohne umschließende Hochkommata zurückgegeben.
     */
    private String extractLabel(TableRow row, List<String> pkCols) {
        Set<String> pkSet = new HashSet<>(pkCols);
        for (Map.Entry<String, String> entry : row.getValues().entrySet()) {
            if (pkSet.contains(entry.getKey())) continue;
            String val = entry.getValue();
            if (val == null || val.equals("NULL")) continue;
            if (val.startsWith("'") && val.endsWith("'") && val.length() > 2) {
                return val.substring(1, val.length() - 1);
            }
        }
        return null;
    }

    public static String toSqlLiteral(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Wert darf nicht leer sein.");
        }
        try {
            Long.parseLong(value.trim());
            return value.trim();
        } catch (NumberFormatException e) {
            return "'" + value.trim().replace("'", "''") + "'";
        }
    }
}
