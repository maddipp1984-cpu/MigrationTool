package com.mergegen.generator;

import com.mergegen.model.ColumnInfo;
import com.mergegen.model.TableRow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für MergeScriptGenerator.generate() – reine String-Erzeugung,
 * keine DB- oder Datei-Abhängigkeit.
 */
class MergeScriptGeneratorTest {

    private MergeScriptGenerator gen;

    @BeforeEach
    void setUp() {
        gen = new MergeScriptGenerator();
    }

    // ── Hilfsmethoden ────────────────────────────────────────────────────

    private TableRow buildRow(String table, Object... colDefs) {
        // colDefs: abwechselnd ColumnInfo, String (SQL-Literal)
        TableRow row = new TableRow("SCHEMA", table);
        for (int i = 0; i < colDefs.length; i += 2) {
            row.addValue((ColumnInfo) colDefs[i], (String) colDefs[i + 1]);
        }
        return row;
    }

    private ColumnInfo pk(String name) {
        return new ColumnInfo(name, "NUMBER", false, true);
    }

    private ColumnInfo col(String name) {
        return new ColumnInfo(name, "VARCHAR2", true, false);
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    void testSimpleMergeWithoutSequences() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");
        String sql = gen.generate(row, null, "AUFTRAG", null, null, null, false);

        assertTrue(sql.contains("MERGE INTO AUFTRAG tgt"), "MERGE INTO fehlt");
        assertTrue(sql.contains("42 AS ID"), "PK-Literal fehlt");
        assertTrue(sql.contains("'Test' AS NAME"), "Spalten-Literal fehlt");
        assertTrue(sql.contains("FROM DUAL"), "FROM DUAL fehlt");
        assertTrue(sql.contains("ON (tgt.ID = src.ID)"), "ON-Klausel fehlt");
        assertTrue(sql.contains("WHEN NOT MATCHED THEN"), "WHEN NOT MATCHED fehlt");
        assertTrue(sql.contains("INSERT (ID, NAME)"), "INSERT-Spaltenliste fehlt");
        assertTrue(sql.contains("VALUES (src.ID, src.NAME)"), "VALUES fehlt");
        assertFalse(sql.contains("WHEN MATCHED"), "Darf kein UPDATE enthalten");
    }

    @Test
    void testSequenceReplacesValue() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");
        Map<String, String> seqMap = Map.of("AUFTRAG.ID", "SEQ_AUFTRAG");

        String sql = gen.generate(row, seqMap, "AUFTRAG", null, null, null, false);

        assertTrue(sql.contains("SEQ_AUFTRAG.NEXTVAL AS ID"),
            "Sequence NEXTVAL fehlt im USING SELECT");
        assertFalse(sql.contains("42 AS ID"),
            "Altes Literal darf nicht mehr vorkommen");
    }

    @Test
    void testColVarSubstitutionOverridesSequence() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");
        Map<String, String> seqMap = Map.of("AUFTRAG.ID", "SEQ_A");
        Map<String, String> varSubs = Map.of("ID", "v_ID_1");

        String sql = gen.generate(row, seqMap, "AUFTRAG", null, null, varSubs, false);

        assertTrue(sql.contains("v_ID_1 AS ID"),
            "PL/SQL-Variable muss Sequence überschreiben");
        assertFalse(sql.contains("SEQ_A.NEXTVAL"),
            "NEXTVAL darf nicht vorkommen wenn Variable gesetzt");
        assertFalse(sql.contains("42 AS ID"),
            "Literal darf nicht vorkommen");
    }

    @Test
    void testTestSuffixOnRootNameColumn() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Testauftrag'");

        String sql = gen.generate(row, null, "AUFTRAG", "NAME", "_20260224", null, false);

        assertTrue(sql.contains("'Testauftrag_20260224' AS NAME"),
            "Suffix muss an Name-Spalte angehängt werden");
        // ON-Klausel sollte Name-Spalte verwenden
        assertTrue(sql.contains("ON (tgt.NAME = src.NAME)"),
            "ON muss nameColumn verwenden bei Root-Tabelle");
    }

    @Test
    void testTestSuffixNotAppliedToChildTable() {
        TableRow row = buildRow("POSITION", pk("ID"), "99", col("NAME"), "'PosName'");

        String sql = gen.generate(row, null, "AUFTRAG", "NAME", "_20260224", null, false);

        assertTrue(sql.contains("'PosName' AS NAME"),
            "Suffix darf nicht auf Child-Tabelle wirken");
        assertFalse(sql.contains("_20260224"),
            "Suffix darf nirgends in Child-SQL vorkommen");
    }

    @Test
    void testTestSuffixIgnoredOnNumericValue() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "99");

        String sql = gen.generate(row, null, "AUFTRAG", "NAME", "_20260224", null, false);

        // Numerischer Wert beginnt nicht mit ' → Suffix wird nicht angehängt
        assertTrue(sql.contains("99 AS NAME"),
            "Numerischer Wert soll unverändert bleiben");
        assertFalse(sql.contains("99_20260224"),
            "Suffix darf nicht an Zahlen angehängt werden");
    }

    @Test
    void testNameColumnUsedForOnClause() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("AUFTRAG_NR"), "'A-001'");

        String sql = gen.generate(row, null, "AUFTRAG", "AUFTRAG_NR", null, null, false);

        assertTrue(sql.contains("ON (tgt.AUFTRAG_NR = src.AUFTRAG_NR)"),
            "ON muss nameColumn statt PK verwenden");
        assertFalse(sql.contains("ON (tgt.ID = src.ID)"),
            "PK darf nicht in ON wenn nameColumn gesetzt");
    }

    @Test
    void testNoPkFallback() {
        // Keine PK-Spalten
        TableRow row = buildRow("AUFTRAG", col("ID"), "42", col("NAME"), "'Test'");

        String sql = gen.generate(row, null, "OTHER", null, null, null, false);

        assertTrue(sql.contains("WARNUNG: Kein PK"), "Warnung fehlt");
        assertTrue(sql.contains("ON (1=0)"), "Fallback ON-Klausel fehlt");
    }

    @Test
    void testMultiplePkColumns() {
        TableRow row = buildRow("AUFTRAG",
            pk("ID"), "1", pk("VERSION"), "3", col("NAME"), "'Test'");

        String sql = gen.generate(row, null, "OTHER", null, null, null, false);

        assertTrue(sql.contains("tgt.ID = src.ID"), "Erster PK fehlt in ON");
        assertTrue(sql.contains("tgt.VERSION = src.VERSION"), "Zweiter PK fehlt in ON");
        assertTrue(sql.contains(" AND "), "AND zwischen PKs fehlt");
    }

    // ── UPDATE-Tests ──────────────────────────────────────────────────────

    @Test
    void testIncludeUpdateAddsUpdateBlock() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'", col("STATUS"), "'AKTIV'");

        String sql = gen.generate(row, null, "AUFTRAG", null, null, null, true);

        assertTrue(sql.contains("WHEN MATCHED THEN"), "WHEN MATCHED fehlt");
        assertTrue(sql.contains("UPDATE SET"), "UPDATE SET fehlt");
        assertTrue(sql.contains("tgt.NAME = src.NAME"), "NAME muss im UPDATE sein");
        assertTrue(sql.contains("tgt.STATUS = src.STATUS"), "STATUS muss im UPDATE sein");
        // PK darf im UPDATE SET nicht vorkommen (nur in ON-Klausel)
        String updateBlock = sql.substring(sql.indexOf("UPDATE SET"));
        String updateBeforeInsert = updateBlock.substring(0, updateBlock.indexOf("WHEN NOT MATCHED"));
        assertFalse(updateBeforeInsert.contains("tgt.ID = src.ID"), "PK darf nicht im UPDATE SET sein");
        assertTrue(sql.contains("WHEN NOT MATCHED THEN"), "WHEN NOT MATCHED muss weiterhin vorhanden sein");
    }

    @Test
    void testIncludeUpdateExcludesSequenceColumns() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");
        Map<String, String> seqMap = Map.of("AUFTRAG.ID", "SEQ_AUFTRAG");

        String sql = gen.generate(row, seqMap, "AUFTRAG", null, null, null, true);

        assertTrue(sql.contains("WHEN MATCHED THEN"), "WHEN MATCHED fehlt");
        assertTrue(sql.contains("tgt.NAME = src.NAME"), "NAME muss im UPDATE sein");
    }

    @Test
    void testIncludeUpdateFalseNoUpdateBlock() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");

        String sql = gen.generate(row, null, "AUFTRAG", null, null, null, false);

        assertFalse(sql.contains("WHEN MATCHED"), "UPDATE-Block darf nicht vorhanden sein");
    }
}
