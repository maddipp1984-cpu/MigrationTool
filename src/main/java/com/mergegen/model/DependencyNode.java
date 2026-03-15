package com.mergegen.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Knoten im Abhängigkeitsbaum (für die GUI-Vorschau). */
public class DependencyNode {

    private final String tableName;
    private final String pkColumn;
    private final String pkValue;
    private final int rowCount;
    private final List<DependencyNode> children  = new ArrayList<>();
    private final List<String>         rowLabels = new ArrayList<>();
    private String cachedString;

    public DependencyNode(String tableName, String pkColumn, String pkValue, int rowCount) {
        this.tableName = tableName;
        this.pkColumn  = pkColumn;
        this.pkValue   = pkValue;
        this.rowCount  = rowCount;
    }

    public void addChild(DependencyNode child)   { children.add(child); cachedString = null; }
    public void addRowLabel(String label)        { rowLabels.add(label); cachedString = null; }
    public List<String> getRowLabels()           { return Collections.unmodifiableList(rowLabels); }

    public String getTableName()       { return tableName; }
    public String getPkColumn()        { return pkColumn; }
    public String getPkValue()         { return pkValue; }
    public int    getRowCount()        { return rowCount; }
    public List<DependencyNode> getChildren() { return Collections.unmodifiableList(children); }

    @Override
    public String toString() {
        if (cachedString != null) return cachedString;
        String base = tableName + "  (" + rowCount + " Datensatz" + (rowCount != 1 ? "e" : "") + ")";
        if (rowLabels.isEmpty()) { cachedString = base; return base; }
        List<String> display = rowLabels.size() > 3 ? rowLabels.subList(0, 3) : rowLabels;
        String suffix = String.join(", ", display);
        if (rowLabels.size() > 3) suffix += ", …";
        cachedString = base + "  –  " + suffix;
        return cachedString;
    }
}
