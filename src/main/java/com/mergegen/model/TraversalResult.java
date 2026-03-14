package com.mergegen.model;

import java.util.*;

/** Ergebnis eines Traversal-Laufs: Baum fuer Vorschau + geordnete Zeilen fuer Generierung. */
public class TraversalResult {

    private final DependencyNode rootNode;
    private final List<TableRow> orderedRows;
    private final Map<String, Integer> tableCounts;
    /** Key = Child-Tabellenname (uppercase), Value = alle FK-Relationen fuer diese Child-Tabelle. */
    private final Map<String, List<ForeignKeyRelation>> fkRelations;
    /** Key = "TABLE#PK_VALUE", Value = geladene Referenz-Zeile (fuer Subselect-Ersetzung). */
    private final Map<String, TableRow> subselectRows;

    public TraversalResult(DependencyNode rootNode,
                            List<TableRow> orderedRows,
                            Map<String, Integer> tableCounts,
                            Map<String, List<ForeignKeyRelation>> fkRelations) {
        this(rootNode, orderedRows, tableCounts, fkRelations, new HashMap<>());
    }

    public TraversalResult(DependencyNode rootNode,
                            List<TableRow> orderedRows,
                            Map<String, Integer> tableCounts,
                            Map<String, List<ForeignKeyRelation>> fkRelations,
                            Map<String, TableRow> subselectRows) {
        this.rootNode       = rootNode;
        this.orderedRows    = orderedRows;
        this.tableCounts    = tableCounts;
        this.fkRelations    = fkRelations != null ? fkRelations : new HashMap<>();
        this.subselectRows  = subselectRows != null ? subselectRows : new HashMap<>();
    }

    public DependencyNode                            getRootNode()      { return rootNode; }
    public List<TableRow>                            getOrderedRows()   { return orderedRows; }
    public Map<String, Integer>                      getTableCounts()   { return tableCounts; }
    public Map<String, List<ForeignKeyRelation>>     getFkRelations()   { return fkRelations; }
    public Map<String, TableRow>                     getSubselectRows() { return subselectRows; }

    public int getTotalRows() { return orderedRows.size(); }

    /**
     * Fuehrt mehrere TraversalResults zu einem zusammen.
     * Dedupliziert Rows anhand von Schema.Tabelle + Values-Map.
     */
    public static TraversalResult merge(List<TraversalResult> results) {
        DependencyNode mergedRoot = new DependencyNode("BATCH", "", "", results.size());

        List<TableRow> allRows = new ArrayList<>();
        Map<String, Integer> allCounts = new LinkedHashMap<>();
        Map<String, List<ForeignKeyRelation>> allFkRelations = new HashMap<>();
        Map<String, TableRow> allSubselectRows = new HashMap<>();
        Set<String> seen = new HashSet<>();

        for (TraversalResult r : results) {
            DependencyNode childRoot = r.getRootNode();
            mergedRoot.addChild(childRoot);
            childRoot.getRowLabels().forEach(mergedRoot::addRowLabel);
            for (TableRow row : r.getOrderedRows()) {
                String key = row.getSchema() + "." + row.getTableName() + "#" + row.getValues().toString();
                if (seen.add(key)) {
                    allRows.add(row);
                    allCounts.merge(row.getTableName(), 1, Integer::sum);
                }
            }
            r.getFkRelations().forEach((childTable, rels) -> {
                List<ForeignKeyRelation> existing = allFkRelations.computeIfAbsent(childTable, k -> new ArrayList<>());
                for (ForeignKeyRelation rel : rels) {
                    boolean duplicate = existing.stream().anyMatch(e ->
                        e.getChildTable().equalsIgnoreCase(rel.getChildTable()) &&
                        e.getFkColumn().equalsIgnoreCase(rel.getFkColumn()) &&
                        e.getParentTable().equalsIgnoreCase(rel.getParentTable()));
                    if (!duplicate) existing.add(rel);
                }
            });
            allSubselectRows.putAll(r.getSubselectRows());
        }
        return new TraversalResult(mergedRoot, allRows, allCounts, allFkRelations, allSubselectRows);
    }
}
