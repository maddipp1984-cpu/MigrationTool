package com.mergegen.model;

import java.util.*;

/** Ergebnis eines Traversal-Laufs: Baum für Vorschau + geordnete Zeilen für Generierung. */
public class TraversalResult {

    private final DependencyNode rootNode;
    private final List<TableRow> orderedRows;
    private final Map<String, Integer> tableCounts;
    /** Key = Child-Tabellenname (uppercase), Value = alle FK-Relationen für diese Child-Tabelle. */
    private final Map<String, List<ForeignKeyRelation>> fkRelations;

    public TraversalResult(DependencyNode rootNode,
                            List<TableRow> orderedRows,
                            Map<String, Integer> tableCounts,
                            Map<String, List<ForeignKeyRelation>> fkRelations) {
        this.rootNode    = rootNode;
        this.orderedRows = orderedRows;
        this.tableCounts = tableCounts;
        this.fkRelations = fkRelations != null ? fkRelations : new HashMap<>();
    }

    public DependencyNode                            getRootNode()    { return rootNode; }
    public List<TableRow>                            getOrderedRows() { return orderedRows; }
    public Map<String, Integer>                      getTableCounts() { return tableCounts; }
    public Map<String, List<ForeignKeyRelation>>     getFkRelations() { return fkRelations; }

    public int getTotalRows() { return orderedRows.size(); }

    /**
     * Führt mehrere TraversalResults zu einem zusammen.
     * Dedupliziert Rows anhand von Schema.Tabelle + Values-Map.
     */
    public static TraversalResult merge(List<TraversalResult> results) {
        DependencyNode mergedRoot = new DependencyNode("BATCH", "", "", results.size());

        List<TableRow> allRows = new ArrayList<>();
        Map<String, Integer> allCounts = new LinkedHashMap<>();
        Map<String, List<ForeignKeyRelation>> allFkRelations = new HashMap<>();
        Set<String> seen = new HashSet<>();

        for (TraversalResult r : results) {
            mergedRoot.addChild(r.getRootNode());
            for (TableRow row : r.getOrderedRows()) {
                String key = row.getSchema() + "." + row.getTableName() + "#" + row.getValues().toString();
                if (seen.add(key)) {
                    allRows.add(row);
                    allCounts.merge(row.getTableName(), 1, Integer::sum);
                }
            }
            r.getFkRelations().forEach((childTable, rels) ->
                allFkRelations.computeIfAbsent(childTable, k -> new ArrayList<>()).addAll(rels)
            );
        }
        return new TraversalResult(mergedRoot, allRows, allCounts, allFkRelations);
    }
}
