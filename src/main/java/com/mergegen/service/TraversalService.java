package com.mergegen.service;

import com.mergegen.analyzer.SchemaAnalyzer;
import com.mergegen.config.TraversalRuleStore;
import com.mergegen.config.VirtualFkStore;
import com.mergegen.model.DependencyNode;
import com.mergegen.model.ForeignKeyRelation;
import com.mergegen.model.TableRow;
import com.mergegen.model.TraversalResult;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    private static final Path LOG_FILE = Paths.get("config", "mergegen", "traversal.log");

    /** Ergebnis der Traversal-Entscheidung. */
    public enum TraversalDecision {
        TRAVERSE,    // ja, traversieren
        SKIP,        // nein, ueberspringen
        SUBSELECT    // nicht traversieren, aber FK durch Subselect ersetzen
    }

    /**
     * Callback-Interface: Fragt den Benutzer, wie eine FK-Beziehung behandelt werden soll.
     * Wird aus dem Hintergrundthread aufgerufen – Implementierung muss thread-safe sein.
     */
    @FunctionalInterface
    public interface TraversalDecider {
        /**
         * @param sourceTable  Tabelle, von der aus traversiert wird
         * @param targetTable  Zieltabelle der FK-Beziehung
         * @param fkColumn     FK-Spalte
         * @param rowCount     Anzahl gefundener Zeilen
         * @return TraversalDecision (TRAVERSE, SKIP oder SUBSELECT)
         */
        TraversalDecision decide(String sourceTable, String targetTable, String fkColumn, int rowCount);
    }

    /** Typsicherer Queue-Eintrag für die BFS-Traversal. */
    private static class QueueEntry {
        final String tableName;
        final String pkValue;
        final TableRow row;
        final DependencyNode node;

        QueueEntry(String tableName, String pkValue, TableRow row, DependencyNode node) {
            this.tableName = tableName;
            this.pkValue   = pkValue;
            this.row       = row;
            this.node      = node;
        }
    }

    private final SchemaAnalyzer analyzer;
    private final VirtualFkStore virtualFkStore;
    private final TraversalRuleStore ruleStore;
    private java.util.function.Consumer<String> logger;
    private TraversalDecider decider;
    private PrintWriter logWriter;

    public TraversalService(SchemaAnalyzer analyzer, VirtualFkStore virtualFkStore,
                            TraversalRuleStore ruleStore) {
        this.analyzer       = analyzer;
        this.virtualFkStore = virtualFkStore;
        this.ruleStore      = ruleStore;
    }

    /** Setzt einen Logger-Callback für Fortschrittsmeldungen. */
    public void setLogger(java.util.function.Consumer<String> logger) {
        this.logger = logger;
    }

    /** Setzt den Callback, der bei unbekannten FK-Beziehungen den Benutzer fragt. */
    public void setDecider(TraversalDecider decider) {
        this.decider = decider;
    }

    private void openLogFile() {
        try {
            Files.createDirectories(LOG_FILE.getParent());
            logWriter = new PrintWriter(Files.newBufferedWriter(LOG_FILE));
            logWriter.println("=== Traversal gestartet: "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " ===");
        } catch (IOException e) {
            // Datei-Logging nicht möglich – kein Abbruch
        }
    }

    private void closeLogFile() {
        if (logWriter != null) {
            logWriter.println("=== Traversal beendet ===");
            logWriter.flush();
            logWriter.close();
            logWriter = null;
        }
    }

    private void log(String msg) {
        if (logger != null) logger.accept(msg);
        if (logWriter != null) {
            logWriter.println(msg);
            logWriter.flush();
        }
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
        openLogFile();
        try {
            return doTraverse(rootTable, rootColumn, rootIdValue);
        } catch (SQLException | RuntimeException e) {
            log("FEHLER: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (logWriter != null) e.printStackTrace(logWriter);
            throw e;
        } finally {
            closeLogFile();
        }
    }

    private TraversalResult doTraverse(String rootTable, String rootColumn, String rootIdValue) throws SQLException {
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
        TableRow rootRow;
        try {
            rootRow = analyzer.fetchRowByPk(rootTable, lookupColumn, rootValueLiteral);
        } catch (SQLException e) {
            log("FEHLER beim Laden des Root-Datensatzes: " + e.getMessage());
            throw e;
        }
        log("Root-Datensatz geladen: " + pkCols.size() + " PK-Spalte(n)");

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
        // PK-Cache: vermeidet mehrfache SQL-Abfragen fuer dieselbe Tabelle
        Map<String, List<String>> pkCache = new HashMap<>();
        pkCache.put(rootTable.toUpperCase(), pkCols);
        // subselectRows: Referenz-Zeilen fuer Subselect-Ersetzung (TABLE#PK_VALUE -> Row)
        Map<String, TableRow> subselectRows = new HashMap<>();

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
        Queue<QueueEntry> queue = new ArrayDeque<>();

        for (ForeignKeyRelation rel : rootOutgoing) {
            String fkValue = rootRow.getPkRawValue(rel.getFkColumn());
            if (fkValue == null || "NULL".equals(fkValue)) continue;

            log("  → " + rel.getParentTable() + "." + rel.getParentPkColumn() + " = " + fkValue);
            List<TableRow> refRows;
            try {
                refRows = analyzer.fetchChildRows(
                    rel.getParentTable(), rel.getParentPkColumn(), fkValue);
            } catch (SQLException e) {
                log("FEHLER bei ausgehendem FK " + rootTable + "." + rel.getFkColumn()
                    + " → " + rel.getParentTable() + "." + rel.getParentPkColumn()
                    + " = " + fkValue + ": " + e.getMessage());
                throw e;
            }
            if (refRows.isEmpty()) continue;
            log("    " + refRows.size() + " Zeile(n) gefunden");

            // Traversal-Regel pruefen: soll diese ausgehende FK-Beziehung verfolgt werden?
            TraversalDecision decision = getTraversalDecision(rootTable, rel.getParentTable(),
                    rel.getFkColumn(), refRows.size());
            if (decision == TraversalDecision.SUBSELECT) {
                log("    -> Subselect (Referenz-Zeilen gespeichert)");
                collectSubselectRows(rel.getParentTable(), rel.getParentPkColumn(), refRows, pkCache, subselectRows);
                continue;
            }
            if (decision == TraversalDecision.SKIP) {
                log("    -> Uebersprungen (Traversal-Regel)");
                continue;
            }

            List<String> refPkCols = getCachedPkColumns(rel.getParentTable(), pkCache);
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
                    queue.add(new QueueEntry(rel.getParentTable(), refPkValue, refRow, refNode));
                }
            }
        }

        // ── Root: eingehende FKs (Kinder der Root-Tabelle) direkt suchen ──
        log("Eingehende FK-Spalten suchen für " + rootTable + "...");
        List<ForeignKeyRelation> rootIncoming = new ArrayList<>(analyzer.getChildRelations(rootTable));
        if (virtualFkStore != null) {
            for (ForeignKeyRelation vfk : virtualFkStore.getRelationsForParent(rootTable)) {
                boolean nowReal = rootIncoming.stream().anyMatch(r ->
                    r.getChildTable().equalsIgnoreCase(vfk.getChildTable()) &&
                    r.getFkColumn().equalsIgnoreCase(vfk.getFkColumn()));
                if (nowReal) {
                    virtualFkStore.remove(vfk);
                } else {
                    rootIncoming.add(vfk);
                }
            }
        }

        for (ForeignKeyRelation rel : rootIncoming) {
            fkRelations.computeIfAbsent(rel.getChildTable().toUpperCase(), k -> new ArrayList<>())
                       .add(rel);
        }

        for (ForeignKeyRelation rel : rootIncoming) {
            log("  → " + rel.getChildTable() + "." + rel.getFkColumn() + " = " + rootPkLiteral);
            List<TableRow> childRows;
            try {
                childRows = analyzer.fetchChildRows(
                    rel.getChildTable(), rel.getFkColumn(), rootPkLiteral);
            } catch (SQLException e) {
                log("FEHLER bei Kind-Abfrage " + rel.getChildTable() + "." + rel.getFkColumn()
                    + " = " + rootPkLiteral + ": " + e.getMessage());
                throw e;
            }
            if (childRows.isEmpty()) continue;
            log("    " + childRows.size() + " Zeile(n) gefunden");

            // Traversal-Regel pruefen
            TraversalDecision decisionChild = getTraversalDecision(rootTable, rel.getChildTable(),
                    rel.getFkColumn(), childRows.size());
            if (decisionChild == TraversalDecision.SUBSELECT) {
                log("    -> Subselect (Referenz-Zeilen gespeichert)");
                collectSubselectRows(rel.getChildTable(), rel.getFkColumn(), childRows, pkCache, subselectRows);
                continue;
            }
            if (decisionChild == TraversalDecision.SKIP) {
                log("    -> Uebersprungen (Traversal-Regel)");
                continue;
            }

            DependencyNode childNode = new DependencyNode(
                rel.getChildTable(), rel.getFkColumn(), rootPkLiteral, childRows.size());
            rootNode.addChild(childNode);

            List<String> childPkCols = getCachedPkColumns(rel.getChildTable(), pkCache);
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
                    queue.add(new QueueEntry(
                        rel.getChildTable(), childPkValue, childRow, childNode
                    ));
                }
            }
        }

        // ── BFS: ab hier normal nach unten (eingehende FKs) ──
        while (!queue.isEmpty()) {
            QueueEntry entry      = queue.poll();
            String currentTable   = entry.tableName;
            String currentPkValue = entry.pkValue;
            TableRow currentRow   = entry.row;
            DependencyNode node   = entry.node;

            List<String> currentPkCols = getCachedPkColumns(currentTable, pkCache);
            String key = buildVisitedKey(currentTable, currentRow, currentPkCols, currentPkValue);
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
                List<TableRow> childRows;
                try {
                    childRows = analyzer.fetchChildRows(
                        rel.getChildTable(), rel.getFkColumn(), currentPkValue);
                } catch (SQLException e) {
                    log("FEHLER bei Kind-Abfrage " + rel.getChildTable() + "." + rel.getFkColumn()
                        + " = " + currentPkValue + ": " + e.getMessage());
                    throw e;
                }
                if (childRows.isEmpty()) continue;
                log("    " + childRows.size() + " Zeile(n) gefunden");

                // Traversal-Regel pruefen: soll diese FK-Beziehung verfolgt werden?
                TraversalDecision bfsDecision = getTraversalDecision(currentTable, rel.getChildTable(),
                        rel.getFkColumn(), childRows.size());
                if (bfsDecision == TraversalDecision.SUBSELECT) {
                    log("    -> Subselect (Referenz-Zeilen gespeichert)");
                    collectSubselectRows(rel.getChildTable(), rel.getFkColumn(), childRows, pkCache, subselectRows);
                    continue;
                }
                if (bfsDecision == TraversalDecision.SKIP) {
                    log("    -> Uebersprungen (Traversal-Regel)");
                    continue;
                }

                DependencyNode childNode = new DependencyNode(
                    rel.getChildTable(), rel.getFkColumn(), currentPkValue, childRows.size());
                node.addChild(childNode);

                List<String> childPkCols = getCachedPkColumns(rel.getChildTable(), pkCache);
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
                        queue.add(new QueueEntry(
                            rel.getChildTable(), childPkValue, childRow, childNode
                        ));
                    }
                }
            }
        }

        // Topologische Sortierung: Tabellen, die von anderen referenziert werden, kommen zuerst
        List<TableRow> sorted = topoSort(orderedRows, fkRelations, rootTable);
        log("Topologische Sortierung: " + sorted.size() + " Zeilen, Reihenfolge:");
        String prevTable = null;
        for (TableRow row : sorted) {
            if (!row.getTableName().equals(prevTable)) {
                log("  " + row.getTableName());
                prevTable = row.getTableName();
            }
        }

        return new TraversalResult(rootNode, sorted, tableCounts, fkRelations, subselectRows);
    }

    /**
     * Topologische Sortierung der Zeilen nach FK-Abhängigkeiten.
     * Tabellen, die von anderen Tabellen referenziert werden, kommen zuerst.
     * Die Root-Tabelle bleibt immer an erster Stelle.
     * Innerhalb einer Tabelle bleibt die BFS-Reihenfolge erhalten.
     */
    private List<TableRow> topoSort(List<TableRow> rows,
                                     Map<String, List<ForeignKeyRelation>> fkRelations,
                                     String rootTable) {
        // Alle Tabellen sammeln (in BFS-Reihenfolge)
        List<String> tables = new ArrayList<>();
        for (TableRow row : rows) {
            String t = row.getTableName().toUpperCase();
            if (!tables.contains(t)) tables.add(t);
        }

        // Abhängigkeitsgraph aufbauen: childTable hängt von parentTable ab
        // (= parentTable muss vor childTable kommen)
        Map<String, Set<String>> dependsOn = new HashMap<>();
        for (String t : tables) dependsOn.put(t, new HashSet<>());

        for (Map.Entry<String, List<ForeignKeyRelation>> entry : fkRelations.entrySet()) {
            for (ForeignKeyRelation rel : entry.getValue()) {
                String child  = rel.getChildTable().toUpperCase();
                String parent = rel.getParentTable().toUpperCase();
                // Nur Abhängigkeiten zwischen Tabellen, die beide im Ergebnis sind
                if (tables.contains(child) && tables.contains(parent) && !child.equals(parent)) {
                    dependsOn.computeIfAbsent(child, k -> new HashSet<>()).add(parent);
                }
            }
        }

        // Kahn's Algorithmus (topologische Sortierung)
        Map<String, Integer> inDegree = new HashMap<>();
        for (String t : tables) inDegree.put(t, 0);
        for (Map.Entry<String, Set<String>> e : dependsOn.entrySet()) {
            inDegree.put(e.getKey(), e.getValue().size());
        }

        Queue<String> ready = new ArrayDeque<>();
        // Root-Tabelle zuerst, dann andere ohne Abhängigkeiten
        String rootUpper = rootTable.toUpperCase();
        if (inDegree.getOrDefault(rootUpper, 0) == 0) {
            ready.add(rootUpper);
        }
        for (String t : tables) {
            if (!t.equals(rootUpper) && inDegree.get(t) == 0) {
                ready.add(t);
            }
        }

        List<String> sortedTables = new ArrayList<>();
        while (!ready.isEmpty()) {
            String t = ready.poll();
            sortedTables.add(t);
            // In-Degree der abhängigen Tabellen reduzieren
            for (String other : tables) {
                if (dependsOn.containsKey(other) && dependsOn.get(other).remove(t)) {
                    int newDegree = inDegree.merge(other, -1, Integer::sum);
                    if (newDegree == 0) ready.add(other);
                }
            }
        }

        // Falls Zyklen existieren: restliche Tabellen anhängen (BFS-Reihenfolge)
        for (String t : tables) {
            if (!sortedTables.contains(t)) {
                log("  WARNUNG: Zyklische Abhängigkeit bei " + t + " – BFS-Reihenfolge beibehalten");
                sortedTables.add(t);
            }
        }

        // Zeilen nach sortierter Tabellenreihenfolge umordnen
        Map<String, List<TableRow>> rowsByTable = new LinkedHashMap<>();
        for (TableRow row : rows) {
            rowsByTable.computeIfAbsent(row.getTableName().toUpperCase(), k -> new ArrayList<>())
                       .add(row);
        }

        List<TableRow> result = new ArrayList<>();
        for (String t : sortedTables) {
            List<TableRow> tableRows = rowsByTable.get(t);
            if (tableRows != null) result.addAll(tableRows);
        }
        return result;
    }

    /** Cached PK-Spalten-Abfrage (vermeidet wiederholte SQL-Queries für dieselbe Tabelle). */
    private List<String> getCachedPkColumns(String table, Map<String, List<String>> pkCache) {
        String key = table.toUpperCase();
        List<String> cached = pkCache.get(key);
        if (cached != null) return cached;
        try {
            cached = analyzer.getPrimaryKeyColumns(table);
        } catch (Exception e) {
            cached = Collections.emptyList();
        }
        pkCache.put(key, cached);
        return cached;
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

    /** Sammelt Referenz-Zeilen fuer Subselect-Ersetzung in die subselectRows-Map. */
    private void collectSubselectRows(String table, String fallbackPkCol,
                                       List<TableRow> rows,
                                       Map<String, List<String>> pkCache,
                                       Map<String, TableRow> subselectRows) {
        List<String> pkCols = getCachedPkColumns(table, pkCache);
        String pkCol = pkCols.isEmpty() ? fallbackPkCol : pkCols.get(0);
        for (TableRow row : rows) {
            String pkValue = row.getPkRawValue(pkCol);
            subselectRows.put(table.toUpperCase() + "#" + pkValue, row);
        }
    }

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

    /**
     * Prueft wie eine FK-Beziehung behandelt werden soll.
     * Prioritaet: 1. gespeicherte Regel -> 2. Decider-Callback (Benutzerdialog) -> 3. TRAVERSE
     */
    private TraversalDecision getTraversalDecision(String parentTable, String childTable,
                                                    String fkColumn, int rowCount) {
        // 1. Gespeicherte Regel vorhanden?
        if (ruleStore != null && ruleStore.hasRule(parentTable, childTable, fkColumn)) {
            TraversalRuleStore.TraversalRule rule = ruleStore.getRule(parentTable, childTable, fkColumn);
            TraversalDecision decision;
            switch (rule) {
                case TRAVERSE:  decision = TraversalDecision.TRAVERSE;  break;
                case SUBSELECT: decision = TraversalDecision.SUBSELECT; break;
                default:        decision = TraversalDecision.SKIP;      break;
            }
            log("    Regel: " + parentTable + " -> " + childTable + "." + fkColumn
                + " = " + decision);
            return decision;
        }

        // 2. Decider-Callback (Benutzerdialog)?
        if (decider != null) {
            return decider.decide(parentTable, childTable, fkColumn, rowCount);
        }

        // 3. Kein Store, kein Decider -> traversieren
        return TraversalDecision.TRAVERSE;
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
