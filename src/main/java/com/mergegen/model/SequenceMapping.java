package com.mergegen.model;

/**
 * Zuordnung einer Tabellen-PK-Spalte zu einer Oracle-Sequence.
 * Wird beim MERGE-Generieren verwendet, um statt des Quell-PK-Werts
 * {@code SEQUENCE_NAME.NEXTVAL} einzusetzen.
 */
public class SequenceMapping {

    private final String tableName;
    private final String pkColumn;
    private final String sequenceName;

    public SequenceMapping(String tableName, String pkColumn, String sequenceName) {
        this.tableName    = tableName.toUpperCase();
        this.pkColumn     = pkColumn.toUpperCase();
        this.sequenceName = sequenceName.toUpperCase();
    }

    public String getTableName()    { return tableName; }
    public String getPkColumn()     { return pkColumn; }
    public String getSequenceName() { return sequenceName; }

    /** Schlüssel für Map-Lookup: TABLE.PK_COL */
    public String getKey() {
        return tableName + "." + pkColumn;
    }
}
