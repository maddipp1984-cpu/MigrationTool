package com.mergegen.model;

public class ColumnInfo {

    private final String name;
    private final String dataType;
    private final boolean nullable;
    private final boolean isPrimaryKey;

    public ColumnInfo(String name, String dataType, boolean nullable, boolean isPrimaryKey) {
        this.name = name;
        this.dataType = dataType;
        this.nullable = nullable;
        this.isPrimaryKey = isPrimaryKey;
    }

    public String getName() { return name; }
    public String getDataType() { return dataType; }
    public boolean isNullable() { return nullable; }
    public boolean isPrimaryKey() { return isPrimaryKey; }

    @Override
    public String toString() {
        return name + " (" + dataType + (isPrimaryKey ? ", PK" : "") + ")";
    }
}
