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

    public TraversalService(SchemaAnalyzer analyzer, VirtualFkStore virtualFkStore) {
        this.analyzer       = analyzer;
        this.virtualFkStore = virtualFkStore;
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

        // Queue-Einträge: [Tabellenname, PK-Literal (erster PK), TableRow-Objekt, DependencyNode]
        Queue<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{rootTable, rootPkLiteral, rootRow, rootNode});

        while (!queue.isEmpty()) {
            Object[] entry        = queue.poll();
            String currentTable   = (String) entry[0];
            String currentPkValue = (String) entry[1];
            TableRow currentRow   = (TableRow) entry[2];
            DependencyNode node   = (DependencyNode) entry[3];

            // Schlüssel aus Tabellenname + allen PK-Werten: eindeutig pro Datensatz
            String key = buildVisitedKey(currentTable, currentRow,
                analyzer.getPrimaryKeyColumns(currentTable), currentPkValue);
            if (visited.contains(key)) continue;  // bereits verarbeitet → überspringen
            visited.add(key);

            orderedRows.add(currentRow);
            // merge() addiert 1 zu einem existierenden Zähler oder setzt ihn auf 1
            tableCounts.merge(currentTable, 1, Integer::sum);

            // Echte FK-Beziehungen aus dem DB-Dictionary
            List<ForeignKeyRelation> realRelations = analyzer.getChildRelations(currentTable);

            // Virtuelle FKs bereinigen: falls ein Eintrag inzwischen als echter Constraint
            // in der DB existiert (match auf childTable + fkColumn), wird er entfernt
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

            // Kombinierte Liste: echte FKs + verbleibende virtuelle FKs
            List<ForeignKeyRelation> childRelations = new ArrayList<>(realRelations);
            if (virtualFkStore != null) {
                childRelations.addAll(virtualFkStore.getRelationsForParent(currentTable));
            }

            // FK-Relationen für jede Child-Tabelle sammeln (für Script-Generierung)
            for (ForeignKeyRelation rel : childRelations) {
                fkRelations.computeIfAbsent(rel.getChildTable().toUpperCase(), k -> new ArrayList<>())
                           .add(rel);
            }

            for (ForeignKeyRelation rel : childRelations) {
                // Alle Child-Zeilen laden, bei denen die FK-Spalte dem aktuellen PK entspricht
                List<TableRow> childRows = analyzer.fetchChildRows(
                    rel.getChildTable(), rel.getFkColumn(), currentPkValue);

                if (childRows.isEmpty()) continue;

                // DependencyNode für die Child-Tabelle (repräsentiert alle gefundenen Zeilen)
                DependencyNode childNode = new DependencyNode(
                    rel.getChildTable(), rel.getFkColumn(), currentPkValue, childRows.size());
                node.addChild(childNode);

                for (TableRow childRow : childRows) {
                    List<String> childPkCols = analyzer.getPrimaryKeyColumns(rel.getChildTable());
                    // Fallback auf FK-Spalte, wenn kein PK definiert ist
                    String childPkCol   = childPkCols.isEmpty() ? rel.getFkColumn() : childPkCols.get(0);
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
