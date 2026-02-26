package com.mergegen.model;

/**
 * Beschreibt eine FK-Beziehung: childTable.fkColumn → parentTable.parentPkColumn
 */
public class ForeignKeyRelation {

    private final String childTable;
    private final String fkColumn;
    private final String parentTable;
    private final String parentPkColumn;

    public ForeignKeyRelation(String childTable, String fkColumn,
                               String parentTable, String parentPkColumn) {
        this.childTable = childTable;
        this.fkColumn = fkColumn;
        this.parentTable = parentTable;
        this.parentPkColumn = parentPkColumn;
    }

    public String getChildTable() { return childTable; }
    public String getFkColumn() { return fkColumn; }
    public String getParentTable() { return parentTable; }
    public String getParentPkColumn() { return parentPkColumn; }

    @Override
    public String toString() {
        return childTable + "." + fkColumn + " → " + parentTable + "." + parentPkColumn;
    }
}
