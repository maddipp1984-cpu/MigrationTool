package com.mergegen.generator;

import com.mergegen.model.ColumnInfo;
import com.mergegen.model.ForeignKeyRelation;
import com.mergegen.model.TableRow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer ScriptWriter: Variablen-Erzeugung, FK-Substitution,
 * PL/SQL-Block vs. Plain Mode, mehrstufige FK-Ketten.
 */
class ScriptWriterTest {

    private ScriptWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ScriptWriter();
    }

    // ── Hilfsmethoden ────────────────────────────────────────────────────

    private TableRow buildRow(String table, Object... colDefs) {
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

    // ── buildVarName Tests ───────────────────────────────────────────────

    @Test
    void testBuildVarNameSimple() {
        assertEquals("v_ID_1", writer.buildVarName("ID", 1));
    }

    @Test
    void testBuildVarNameUppercase() {
        assertEquals("v_AUFTRAG_ID_2", writer.buildVarName("auftrag_id", 2));
    }

    @Test
    void testBuildVarNameTruncationAt30Chars() {
        String result = writer.buildVarName("SEHR_LANGER_SPALTENNAME_XYZ", 1);
        assertTrue(result.length() <= 30,
            "Variablenname darf max 30 Zeichen haben, war: " + result.length());
        assertTrue(result.startsWith("v_"), "Muss mit v_ beginnen");
        assertTrue(result.endsWith("_1"), "Muss mit _1 enden");
    }

    @Test
    void testBuildVarNameTruncationWithLargeCounter() {
        String result = writer.buildVarName("VERY_LONG_COLUMN_NAME", 999);
        assertTrue(result.length() <= 30,
            "Variablenname darf max 30 Zeichen haben, war: " + result.length());
        assertTrue(result.endsWith("_999"), "Muss mit _999 enden");
    }

    // ── buildColVarSubstitutions Tests ───────────────────────────────────

    @Test
    void testBuildColVarSubsOwnPk() {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");
        Map<String, String> sequenceMap = Map.of("AUFTRAG.ID", "SEQ_A");
        Map<String, String> varMap = Map.of("AUFTRAG.ID#42", "v_ID_1");
        Map<String, List<ForeignKeyRelation>> fkRels = new HashMap<>();

        Map<String, String> subs = writer.buildColVarSubstitutions(
            row, "AUFTRAG", sequenceMap, varMap, fkRels);

        assertEquals("v_ID_1", subs.get("ID"), "Eigener PK muss Variable bekommen");
        assertNull(subs.get("NAME"), "NAME ist kein PK und kein FK");
    }

    @Test
    void testBuildColVarSubsFkToSequencedParent() {
        TableRow row = buildRow("POSITION",
            pk("POS_ID"), "99", col("AUFTRAG_ID"), "42", col("MENGE"), "5");

        Map<String, String> sequenceMap = Map.of("AUFTRAG.ID", "SEQ_A");
        Map<String, String> varMap = Map.of("AUFTRAG.ID#42", "v_ID_1");
        Map<String, List<ForeignKeyRelation>> fkRels = new HashMap<>();
        fkRels.put("POSITION", List.of(
            new ForeignKeyRelation("POSITION", "AUFTRAG_ID", "AUFTRAG", "ID")));

        Map<String, String> subs = writer.buildColVarSubstitutions(
            row, "POSITION", sequenceMap, varMap, fkRels);

        assertEquals("v_ID_1", subs.get("AUFTRAG_ID"),
            "FK auf sequence-gemappten Parent muss Variable bekommen");
    }

    @Test
    void testBuildColVarSubsNoSequence() {
        TableRow row = buildRow("POSITION",
            pk("POS_ID"), "99", col("AUFTRAG_ID"), "42");

        Map<String, String> sequenceMap = new HashMap<>();
        Map<String, String> varMap = new HashMap<>();
        Map<String, List<ForeignKeyRelation>> fkRels = new HashMap<>();
        fkRels.put("POSITION", List.of(
            new ForeignKeyRelation("POSITION", "AUFTRAG_ID", "AUFTRAG", "ID")));

        Map<String, String> subs = writer.buildColVarSubstitutions(
            row, "POSITION", sequenceMap, varMap, fkRels);

        assertTrue(subs.isEmpty(), "Ohne Sequences darf keine Substitution erfolgen");
    }

    // ── write() Integrationstests ────────────────────────────────────────

    @Test
    void testWritePlainMode(@TempDir Path tempDir) throws IOException {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");

        String path = writer.write(
            List.of(row), Map.of("AUFTRAG", 1),
            "AUFTRAG", List.of("42"),
            tempDir.toString(),
            new HashMap<>(), null, null, null, false);

        String content = Files.readString(Path.of(path));
        assertTrue(content.contains("MERGE INTO AUFTRAG"), "MERGE fehlt");
        assertFalse(content.contains("DECLARE"), "DECLARE darf nicht im Plain Mode sein");
        assertFalse(content.contains("BEGIN"), "BEGIN darf nicht im Plain Mode sein");
    }

    @Test
    void testWritePlSqlBlockMode(@TempDir Path tempDir) throws IOException {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");

        Map<String, String> seqMap = new HashMap<>();
        seqMap.put("AUFTRAG.ID", "SEQ_AUFTRAG");

        String path = writer.write(
            List.of(row), Map.of("AUFTRAG", 1),
            "AUFTRAG", List.of("42"),
            tempDir.toString(),
            seqMap, null, null, new HashMap<>(), false);

        String content = Files.readString(Path.of(path));
        assertTrue(content.contains("DECLARE"), "DECLARE fehlt");
        assertTrue(content.contains("BEGIN"), "BEGIN fehlt");
        assertTrue(content.contains("END;"), "END fehlt");
        assertTrue(content.contains("SEQ_AUFTRAG.NEXTVAL INTO"), "NEXTVAL INTO fehlt");
        assertTrue(content.contains("NUMBER"), "Variablen-Typ NUMBER fehlt");
    }

    @Test
    void testVariableTypeDetection(@TempDir Path tempDir) throws IOException {
        TableRow numRow = buildRow("TAB_NUM", pk("ID"), "42", col("NAME"), "'X'");
        TableRow strRow = buildRow("TAB_STR", pk("CODE"), "'ABC'", col("NAME"), "'Y'");

        Map<String, String> seqMap = new HashMap<>();
        seqMap.put("TAB_NUM.ID", "SEQ_NUM");
        seqMap.put("TAB_STR.CODE", "SEQ_STR");

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("TAB_NUM", 1);
        counts.put("TAB_STR", 1);

        String path = writer.write(
            List.of(numRow, strRow), counts,
            "TAB_NUM", List.of("42"),
            tempDir.toString(),
            seqMap, null, null, new HashMap<>(), false);

        String content = Files.readString(Path.of(path));
        assertTrue(content.contains("NUMBER;"), "Numerischer PK muss NUMBER sein");
        assertTrue(content.contains("VARCHAR2(200);"), "String-PK muss VARCHAR2(200) sein");
    }

    @Test
    void testVierEbenenMitSequences(@TempDir Path tempDir) throws IOException {
        // 4-stufige FK-Kette: PROJEKT <- AUFTRAG <- POSITION <- DETAIL
        List<TableRow> rows = List.of(
            buildRow("PROJEKT",  pk("PROJEKT_ID"), "100", col("NAME"), "'Testprojekt'"),
            buildRow("AUFTRAG",  pk("AUFTRAG_ID"), "200", col("PROJEKT_ID"), "100", col("TITEL"), "'Auftrag A'"),
            buildRow("POSITION", pk("POSITION_ID"), "300", col("AUFTRAG_ID"), "200", col("MENGE"), "5"),
            buildRow("DETAIL",   pk("DETAIL_ID"), "400", col("POSITION_ID"), "300", col("BEMERKUNG"), "'Details'")
        );

        Map<String, String> seqMap = new LinkedHashMap<>();
        seqMap.put("PROJEKT.PROJEKT_ID", "PROJEKT_SEQ");
        seqMap.put("AUFTRAG.AUFTRAG_ID", "AUFTRAG_SEQ");
        seqMap.put("POSITION.POSITION_ID", "POSITION_SEQ");
        seqMap.put("DETAIL.DETAIL_ID", "DETAIL_SEQ");

        Map<String, List<ForeignKeyRelation>> fkRels = new HashMap<>();
        fkRels.put("AUFTRAG", List.of(new ForeignKeyRelation("AUFTRAG", "PROJEKT_ID", "PROJEKT", "PROJEKT_ID")));
        fkRels.put("POSITION", List.of(new ForeignKeyRelation("POSITION", "AUFTRAG_ID", "AUFTRAG", "AUFTRAG_ID")));
        fkRels.put("DETAIL", List.of(new ForeignKeyRelation("DETAIL", "POSITION_ID", "POSITION", "POSITION_ID")));

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("PROJEKT", 1); counts.put("AUFTRAG", 1);
        counts.put("POSITION", 1); counts.put("DETAIL", 1);

        String path = writer.write(rows, counts, "PROJEKT", List.of("100"),
            tempDir.toString(), seqMap, null, null, fkRels, false);

        String content = Files.readString(Path.of(path));

        // Variablen-Deklarationen
        assertTrue(content.contains("v_PROJEKT_ID_1 NUMBER"), "Variable Ebene 1");
        assertTrue(content.contains("v_AUFTRAG_ID_1 NUMBER"), "Variable Ebene 2");
        assertTrue(content.contains("v_POSITION_ID_1 NUMBER"), "Variable Ebene 3");
        assertTrue(content.contains("v_DETAIL_ID_1 NUMBER"), "Variable Ebene 4");

        // NEXTVAL-Assignments
        assertTrue(content.contains("PROJEKT_SEQ.NEXTVAL INTO v_PROJEKT_ID_1"), "NEXTVAL Ebene 1");
        assertTrue(content.contains("AUFTRAG_SEQ.NEXTVAL INTO v_AUFTRAG_ID_1"), "NEXTVAL Ebene 2");

        // FK-Werte muessen Variablen sein, nicht alte Literale
        assertFalse(content.contains("100 AS PROJEKT_ID"), "Alter FK-Wert 100 darf nicht in AUFTRAG-USING sein");
        assertFalse(content.contains("200 AS AUFTRAG_ID"), "Alter FK-Wert 200 darf nicht in POSITION-USING sein");
    }

    @Test
    void testZweiRootRows(@TempDir Path tempDir) throws IOException {
        List<TableRow> rows = List.of(
            buildRow("PROJEKT", pk("PROJEKT_ID"), "100", col("NAME"), "'Alpha'"),
            buildRow("PROJEKT", pk("PROJEKT_ID"), "101", col("NAME"), "'Beta'"),
            buildRow("AUFTRAG", pk("AUFTRAG_ID"), "200", col("PROJEKT_ID"), "100"),
            buildRow("AUFTRAG", pk("AUFTRAG_ID"), "201", col("PROJEKT_ID"), "101")
        );

        Map<String, String> seqMap = new LinkedHashMap<>();
        seqMap.put("PROJEKT.PROJEKT_ID", "PROJEKT_SEQ");
        seqMap.put("AUFTRAG.AUFTRAG_ID", "AUFTRAG_SEQ");

        Map<String, List<ForeignKeyRelation>> fkRels = new HashMap<>();
        fkRels.put("AUFTRAG", List.of(new ForeignKeyRelation("AUFTRAG", "PROJEKT_ID", "PROJEKT", "PROJEKT_ID")));

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("PROJEKT", 2); counts.put("AUFTRAG", 2);

        String path = writer.write(rows, counts, "PROJEKT", List.of("100", "101"),
            tempDir.toString(), seqMap, null, null, fkRels, false);

        String content = Files.readString(Path.of(path));

        // Beide PROJEKT-Variablen
        assertTrue(content.contains("v_PROJEKT_ID_1 NUMBER"), "Variable fuer PROJEKT 100");
        assertTrue(content.contains("v_PROJEKT_ID_2 NUMBER"), "Variable fuer PROJEKT 101");

        // Korrekte FK-Zuordnung
        assertTrue(content.contains("v_PROJEKT_ID_1 AS PROJEKT_ID"), "AUFTRAG 200 -> PROJEKT 100");
        assertTrue(content.contains("v_PROJEKT_ID_2 AS PROJEKT_ID"), "AUFTRAG 201 -> PROJEKT 101");
    }

    @Test
    void testWriteCreatesSubdirectory(@TempDir Path tempDir) throws IOException {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");

        String path = writer.write(
            List.of(row), Map.of("AUFTRAG", 1),
            "AUFTRAG", List.of("42"),
            tempDir.toString(),
            new HashMap<>(), null, null, null, false);

        assertTrue(path.contains("AUFTRAG"), "Unterordner muss Tabellenname enthalten");
        assertTrue(path.endsWith("MERGE_AUFTRAG.sql"), "Dateiname falsch");
        assertTrue(Files.exists(Path.of(path)), "Datei muss existieren");
    }

    // ── Skip-Check Tests (INSERT-only mit Children) ───────────────────────

    @Test
    void testInsertOnlyWithChildrenHasSkipCheck(@TempDir Path tempDir) throws IOException {
        List<TableRow> rows = List.of(
            buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'"),
            buildRow("POSITION", pk("POS_ID"), "99", col("AUFTRAG_ID"), "42", col("MENGE"), "5")
        );
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("AUFTRAG", 1);
        counts.put("POSITION", 1);

        String path = writer.write(rows, counts, "AUFTRAG", List.of("42"),
            tempDir.toString(), new HashMap<>(), null, null, null, false);

        String content = Files.readString(Path.of(path));
        assertTrue(content.contains("v_root_count NUMBER := 0"), "Skip-Variable fehlt");
        assertTrue(content.contains("v_root_count := v_root_count + SQL%ROWCOUNT"),
            "SQL%ROWCOUNT-Tracking fehlt");
        assertTrue(content.contains("IF v_root_count = 0 THEN"),
            "Skip-Check fehlt");
        assertTrue(content.contains("RETURN"), "RETURN fehlt");
        // Muss PL/SQL-Block sein
        assertTrue(content.contains("DECLARE"), "DECLARE fehlt");
        assertTrue(content.contains("BEGIN"), "BEGIN fehlt");
        assertTrue(content.contains("END;"), "END fehlt");
    }

    @Test
    void testInsertOnlyWithoutChildrenNoSkipCheck(@TempDir Path tempDir) throws IOException {
        TableRow row = buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'");

        String path = writer.write(
            List.of(row), Map.of("AUFTRAG", 1),
            "AUFTRAG", List.of("42"),
            tempDir.toString(),
            new HashMap<>(), null, null, null, false);

        String content = Files.readString(Path.of(path));
        assertFalse(content.contains("v_root_count"), "Ohne Children kein Skip-Check");
        assertFalse(content.contains("DECLARE"), "Ohne Children kein PL/SQL-Block");
    }

    @Test
    void testUpdateModeWithChildrenNoSkipCheck(@TempDir Path tempDir) throws IOException {
        List<TableRow> rows = List.of(
            buildRow("AUFTRAG", pk("ID"), "42", col("NAME"), "'Test'"),
            buildRow("POSITION", pk("POS_ID"), "99", col("AUFTRAG_ID"), "42", col("MENGE"), "5")
        );
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("AUFTRAG", 1);
        counts.put("POSITION", 1);

        String path = writer.write(rows, counts, "AUFTRAG", List.of("42"),
            tempDir.toString(), new HashMap<>(), null, null, null, true);

        String content = Files.readString(Path.of(path));
        assertFalse(content.contains("v_root_count"), "Bei UPDATE-Modus kein Skip-Check");
    }
}
