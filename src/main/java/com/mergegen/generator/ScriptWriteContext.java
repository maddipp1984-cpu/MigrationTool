package com.mergegen.generator;

import com.mergegen.config.SubselectMappingStore;
import com.mergegen.model.ForeignKeyRelation;
import com.mergegen.model.TableRow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Buendelt alle Parameter fuer die Script-Generierung.
 */
public class ScriptWriteContext {
    private final String rootTable;
    private final List<String> rootIds;
    private final String outputDir;
    private final Map<String, String> sequenceMap;
    private final String nameColumn;
    private final String testSuffix;
    private final Map<String, List<ForeignKeyRelation>> fkRelations;
    private final boolean includeUpdate;
    private final SubselectMappingStore subselectStore;
    private final Map<String, TableRow> subselectRows;
    private final String alias;

    public ScriptWriteContext(String rootTable, List<String> rootIds, String outputDir,
                               Map<String, String> sequenceMap, String nameColumn,
                               String testSuffix, Map<String, List<ForeignKeyRelation>> fkRelations,
                               boolean includeUpdate, SubselectMappingStore subselectStore,
                               Map<String, TableRow> subselectRows, String alias) {
        this.rootTable = rootTable;
        this.rootIds = rootIds;
        this.outputDir = outputDir;
        this.sequenceMap = sequenceMap;
        this.nameColumn = nameColumn;
        this.testSuffix = testSuffix;
        this.fkRelations = fkRelations != null ? fkRelations : new HashMap<>();
        this.includeUpdate = includeUpdate;
        this.subselectStore = subselectStore;
        this.subselectRows = subselectRows != null ? subselectRows : new HashMap<>();
        this.alias = alias;
    }

    public String getRootTable() { return rootTable; }
    public List<String> getRootIds() { return rootIds; }
    public String getOutputDir() { return outputDir; }
    public Map<String, String> getSequenceMap() { return sequenceMap; }
    public String getNameColumn() { return nameColumn; }
    public String getTestSuffix() { return testSuffix; }
    public Map<String, List<ForeignKeyRelation>> getFkRelations() { return fkRelations; }
    public boolean isIncludeUpdate() { return includeUpdate; }
    public SubselectMappingStore getSubselectStore() { return subselectStore; }
    public Map<String, TableRow> getSubselectRows() { return subselectRows; }
    public String getAlias() { return alias; }
}
