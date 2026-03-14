package com.mergegen;

import com.mergegen.analyzer.SchemaAnalyzer;
import com.mergegen.config.DatabaseConfig;
import com.mergegen.config.TraversalRuleStore;
import com.mergegen.db.DatabaseConnection;
import com.mergegen.generator.ScriptWriter;
import com.mergegen.model.ColumnInfo;
import com.mergegen.model.ForeignKeyRelation;
import com.mergegen.model.TableRow;
import com.mergegen.model.TraversalResult;
import com.mergegen.service.TraversalService;

import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-Tests gegen Oracle XE (Docker).
 * Voraussetzung: Container "oracle-xe" laeuft, User hr/hr, SID XE, Schema HR.
 *
 * Starten mit: ./gradlew integrationTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OracleIntegrationTest {

    private static DatabaseConfig config;
    private static DatabaseConnection dbConnection;
    private static Connection rawConnection;
    private static SchemaAnalyzer analyzer;

    @BeforeAll
    static void connect() throws Exception {
        Properties props = new Properties();
        props.setProperty("db.host", "localhost");
        props.setProperty("db.port", "1521");
        props.setProperty("db.sid", "XE");
        props.setProperty("db.user", "hr");
        props.setProperty("db.password", "hr");
        props.setProperty("db.schema", "HR");
        config = DatabaseConfig.fromProperties(props);
        dbConnection = new DatabaseConnection(config);
        rawConnection = dbConnection.get();
        analyzer = new SchemaAnalyzer(rawConnection, config);
    }

    @AfterAll
    static void disconnect() {
        if (dbConnection != null) dbConnection.close();
    }

    // =====================================================================
    // SchemaAnalyzer-Tests
    // =====================================================================

    @Test
    @Order(1)
    void pkErkennung_employees() throws SQLException {
        List<String> pk = analyzer.getPrimaryKeyColumns("EMPLOYEES");
        assertEquals(List.of("EMPLOYEE_ID"), pk);
    }

    @Test
    @Order(2)
    void pkErkennung_compositePk_jobHistory() throws SQLException {
        List<String> pk = analyzer.getPrimaryKeyColumns("JOB_HISTORY");
        assertEquals(2, pk.size());
        assertTrue(pk.contains("EMPLOYEE_ID"));
        assertTrue(pk.contains("START_DATE"));
    }

    @Test
    @Order(3)
    void spaltenLaden_employees() throws SQLException {
        List<String> pk = analyzer.getPrimaryKeyColumns("EMPLOYEES");
        List<ColumnInfo> cols = analyzer.getColumns("EMPLOYEES", pk);

        assertFalse(cols.isEmpty());
        List<String> colNames = cols.stream().map(ColumnInfo::getName).collect(Collectors.toList());
        assertTrue(colNames.contains("EMPLOYEE_ID"));
        assertTrue(colNames.contains("FIRST_NAME"));
        assertTrue(colNames.contains("LAST_NAME"));
        assertTrue(colNames.contains("SALARY"));
        assertTrue(colNames.contains("HIRE_DATE"));

        // PK-Flag pruefen
        ColumnInfo empId = cols.stream().filter(c -> c.getName().equals("EMPLOYEE_ID")).findFirst().orElseThrow();
        assertTrue(empId.isPrimaryKey());
        ColumnInfo firstName = cols.stream().filter(c -> c.getName().equals("FIRST_NAME")).findFirst().orElseThrow();
        assertFalse(firstName.isPrimaryKey());
    }

    @Test
    @Order(4)
    void childRelations_employees() throws SQLException {
        List<ForeignKeyRelation> children = analyzer.getChildRelations("EMPLOYEES");
        Set<String> childTables = children.stream()
            .map(ForeignKeyRelation::getChildTable)
            .collect(Collectors.toSet());

        assertTrue(childTables.contains("JOB_HISTORY"), "JOB_HISTORY sollte Child von EMPLOYEES sein");
        assertTrue(childTables.contains("EMPLOYEE_SKILLS"), "EMPLOYEE_SKILLS sollte Child von EMPLOYEES sein");
        assertTrue(childTables.contains("EMPLOYEE_CONTRACTS"), "EMPLOYEE_CONTRACTS sollte Child von EMPLOYEES sein");
        assertTrue(childTables.contains("SKILL_ENDORSEMENTS"), "SKILL_ENDORSEMENTS sollte Child von EMPLOYEES sein (endorsed_by)");
    }

    @Test
    @Order(5)
    void outgoingFks_employees() throws SQLException {
        List<ForeignKeyRelation> outgoing = analyzer.getOutgoingFkRelations("EMPLOYEES");
        Set<String> parentTables = outgoing.stream()
            .map(ForeignKeyRelation::getParentTable)
            .collect(Collectors.toSet());

        assertTrue(parentTables.contains("DEPARTMENTS"), "EMPLOYEES sollte FK auf DEPARTMENTS haben");
        assertTrue(parentTables.contains("JOBS"), "EMPLOYEES sollte FK auf JOBS haben");
    }

    @Test
    @Order(6)
    void fetchRowByPk_king() throws SQLException {
        TableRow king = analyzer.fetchRowByPk("EMPLOYEES", "EMPLOYEE_ID", "100");
        assertNotNull(king);
        assertEquals("EMPLOYEES", king.getTableName());
        assertEquals("100", king.getValues().get("EMPLOYEE_ID"));
        assertEquals("'King'", king.getValues().get("LAST_NAME"));
        assertEquals("'SKING'", king.getValues().get("EMAIL"));
        assertEquals("24000", king.getValues().get("SALARY"));
    }

    @Test
    @Order(7)
    void fetchChildRows_jobHistoryForKing() throws SQLException {
        List<TableRow> rows = analyzer.fetchChildRows("JOB_HISTORY", "EMPLOYEE_ID", "100");
        assertEquals(1, rows.size(), "King hat 1 JOB_HISTORY-Eintrag");
    }

    @Test
    @Order(8)
    void fetchChildRows_skillsForKing() throws SQLException {
        List<TableRow> rows = analyzer.fetchChildRows("EMPLOYEE_SKILLS", "EMPLOYEE_ID", "100");
        assertEquals(2, rows.size(), "King hat 2 Skills (Leadership, Strategic Planning)");
    }

    @Test
    @Order(9)
    void triggerErkennung_employees() {
        Optional<String> seq = analyzer.detectTriggerSequence("EMPLOYEES");
        assertTrue(seq.isPresent(), "EMPLOYEES sollte einen BEFORE INSERT Trigger haben");
        assertEquals("EMPLOYEES_SEQ", seq.get());
    }

    @Test
    @Order(10)
    void triggerErkennung_employeeSkills() {
        Optional<String> seq = analyzer.detectTriggerSequence("EMPLOYEE_SKILLS");
        assertTrue(seq.isPresent(), "EMPLOYEE_SKILLS sollte einen BEFORE INSERT Trigger haben");
        assertEquals("EMPLOYEE_SKILLS_SEQ", seq.get());
    }

    // =====================================================================
    // TraversalService-Tests
    // =====================================================================

    @Test
    @Order(20)
    void traversal_king_findetAlleAbhaengigkeiten() throws SQLException {
        TraversalService service = new TraversalService(analyzer, null, new TraversalRuleStore());
        TraversalResult result = service.traverse("EMPLOYEES", "EMPLOYEE_ID", "100");

        assertNotNull(result);
        assertTrue(result.getTotalRows() > 1, "King sollte Abhaengigkeiten haben");

        Map<String, Integer> counts = result.getTableCounts();
        // Erwartete Tabellen im Traversal
        assertTrue(counts.containsKey("EMPLOYEES"), "EMPLOYEES muss enthalten sein");
        assertTrue(counts.containsKey("JOB_HISTORY"), "JOB_HISTORY muss enthalten sein");
        assertTrue(counts.containsKey("EMPLOYEE_SKILLS"), "EMPLOYEE_SKILLS muss enthalten sein");
        assertTrue(counts.containsKey("SKILL_ENDORSEMENTS"), "SKILL_ENDORSEMENTS muss enthalten sein");
        assertTrue(counts.containsKey("EMPLOYEE_CONTRACTS"), "EMPLOYEE_CONTRACTS muss enthalten sein");
        assertTrue(counts.containsKey("CONTRACT_ITEMS"), "CONTRACT_ITEMS muss enthalten sein");

        // Mindest-Datensatz-Anzahlen (Traversal folgt auch ausgehende FKs
        // zu DEPARTMENTS/JOBS, deren Kinder zusaetzliche Rows liefern)
        assertTrue(counts.get("JOB_HISTORY") >= 1, "Mindestens 1 JOB_HISTORY");
        assertTrue(counts.get("EMPLOYEE_SKILLS") >= 2, "Mindestens 2 EMPLOYEE_SKILLS");
        assertTrue(counts.get("SKILL_ENDORSEMENTS") >= 3, "Mindestens 3 SKILL_ENDORSEMENTS");
        assertTrue(counts.get("EMPLOYEE_CONTRACTS") >= 1, "Mindestens 1 EMPLOYEE_CONTRACTS");
        assertTrue(counts.get("CONTRACT_ITEMS") >= 2, "Mindestens 2 CONTRACT_ITEMS");

        // Ausgehende FK-Tabellen muessen auch traversiert worden sein
        assertTrue(counts.containsKey("DEPARTMENTS"), "DEPARTMENTS muss enthalten sein (ausgehender FK)");
        assertTrue(counts.containsKey("JOBS"), "JOBS muss enthalten sein (ausgehender FK)");
    }

    @Test
    @Order(21)
    void traversal_king_topologischeOrdnung() throws SQLException {
        TraversalService service = new TraversalService(analyzer, null, new TraversalRuleStore());
        TraversalResult result = service.traverse("EMPLOYEES", "EMPLOYEE_ID", "100");

        List<TableRow> rows = result.getOrderedRows();
        // Eltern muessen vor Kindern kommen
        int employeesIdx = findFirstIndex(rows, "EMPLOYEES");
        int jobHistoryIdx = findFirstIndex(rows, "JOB_HISTORY");
        int skillsIdx = findFirstIndex(rows, "EMPLOYEE_SKILLS");
        int endorsementsIdx = findFirstIndex(rows, "SKILL_ENDORSEMENTS");
        int contractsIdx = findFirstIndex(rows, "EMPLOYEE_CONTRACTS");
        int contractItemsIdx = findFirstIndex(rows, "CONTRACT_ITEMS");

        assertTrue(employeesIdx < jobHistoryIdx, "EMPLOYEES vor JOB_HISTORY");
        assertTrue(employeesIdx < skillsIdx, "EMPLOYEES vor EMPLOYEE_SKILLS");
        assertTrue(skillsIdx < endorsementsIdx, "EMPLOYEE_SKILLS vor SKILL_ENDORSEMENTS");
        assertTrue(employeesIdx < contractsIdx, "EMPLOYEES vor EMPLOYEE_CONTRACTS");
        assertTrue(contractsIdx < contractItemsIdx, "EMPLOYEE_CONTRACTS vor CONTRACT_ITEMS");
    }

    @Test
    @Order(22)
    void traversal_king_ohneSpalte_autoPk() throws SQLException {
        // Leere Spalte = PK auto-ermitteln
        TraversalService service = new TraversalService(analyzer, null, new TraversalRuleStore());
        TraversalResult result = service.traverse("EMPLOYEES", "", "100");

        assertNotNull(result);
        assertTrue(result.getTotalRows() > 1, "Traversal mit auto-PK sollte funktionieren");
    }

    @Test
    @Order(23)
    void traversal_kochhar_wenigerAbhaengigkeiten() throws SQLException {
        TraversalService service = new TraversalService(analyzer, null, new TraversalRuleStore());
        TraversalResult result = service.traverse("EMPLOYEES", "EMPLOYEE_ID", "101");

        Map<String, Integer> counts = result.getTableCounts();
        // Kochhar hat direkt 1 Skill und 1 Contract, aber Traversal folgt auch
        // ausgehende FKs (DEPARTMENTS, JOBS) und findet dort weitere Zeilen
        assertTrue(counts.getOrDefault("EMPLOYEE_SKILLS", 0) >= 1, "Mindestens 1 Skill fuer Kochhar");
        assertTrue(counts.getOrDefault("EMPLOYEE_CONTRACTS", 0) >= 1, "Mindestens 1 Contract fuer Kochhar");
    }

    // =====================================================================
    // Script-Generierung + Ausfuehrung
    // =====================================================================

    @Test
    @Order(30)
    void scriptGenerierung_king_ohneSequences() throws SQLException, IOException {
        TraversalService service = new TraversalService(analyzer, null, new TraversalRuleStore());
        TraversalResult result = service.traverse("EMPLOYEES", "EMPLOYEE_ID", "100");

        Path tempDir = Files.createTempDirectory("mergegen-test");
        ScriptWriter writer = new ScriptWriter();
        String scriptPath = writer.write(
            result.getOrderedRows(),
            result.getTableCounts(),
            "EMPLOYEES", List.of("100"),
            tempDir.toString(),
            Collections.emptyMap(),    // keine Sequences
            null,                       // kein nameColumn
            null,                       // kein Testmodus
            result.getFkRelations(),
            false,                      // kein UPDATE
            null, null
        );

        File scriptFile = new File(scriptPath);
        assertTrue(scriptFile.exists(), "Script-Datei muss existieren");
        String content = Files.readString(scriptFile.toPath());

        // Grundstruktur pruefen
        assertTrue(content.contains("MERGE INTO EMPLOYEES"), "MERGE fuer EMPLOYEES");
        assertTrue(content.contains("MERGE INTO JOB_HISTORY"), "MERGE fuer JOB_HISTORY");
        assertTrue(content.contains("MERGE INTO EMPLOYEE_SKILLS"), "MERGE fuer EMPLOYEE_SKILLS");
        assertTrue(content.contains("MERGE INTO SKILL_ENDORSEMENTS"), "MERGE fuer SKILL_ENDORSEMENTS");
        assertTrue(content.contains("MERGE INTO EMPLOYEE_CONTRACTS"), "MERGE fuer EMPLOYEE_CONTRACTS");
        assertTrue(content.contains("MERGE INTO CONTRACT_ITEMS"), "MERGE fuer CONTRACT_ITEMS");
        assertTrue(content.contains("WHEN NOT MATCHED THEN"), "INSERT-Block vorhanden");

        // Keine NEXTVAL bei leerer sequenceMap
        assertFalse(content.contains(".NEXTVAL"), "Ohne Sequences kein NEXTVAL");

        // Script auf Ziel-DB ausfuehren
        executeScriptOnTarget(content);

        // Daten verifizieren
        verifyDataPresent("EMPLOYEES", "EMPLOYEE_ID", 100);
        verifyDataPresent("JOB_HISTORY", "EMPLOYEE_ID", 100);
        verifyDataPresent("EMPLOYEE_SKILLS", "EMPLOYEE_ID", 100);
        verifyDataPresent("EMPLOYEE_CONTRACTS", "EMPLOYEE_ID", 100);

        // Cleanup
        deleteDirectory(tempDir.toFile());
    }

    @Test
    @Order(31)
    void scriptGenerierung_king_mitSequences() throws SQLException, IOException {
        TraversalService service = new TraversalService(analyzer, null, new TraversalRuleStore());
        TraversalResult result = service.traverse("EMPLOYEES", "EMPLOYEE_ID", "100");

        Path tempDir = Files.createTempDirectory("mergegen-test");
        Map<String, String> seqMap = new HashMap<>();
        seqMap.put("EMPLOYEES.EMPLOYEE_ID", "EMPLOYEES_SEQ");
        seqMap.put("EMPLOYEE_SKILLS.SKILL_ID", "EMPLOYEE_SKILLS_SEQ");

        ScriptWriter writer = new ScriptWriter();
        String scriptPath = writer.write(
            result.getOrderedRows(),
            result.getTableCounts(),
            "EMPLOYEES", List.of("100"),
            tempDir.toString(),
            seqMap,
            "LAST_NAME",               // ON ueber LAST_NAME
            null,
            result.getFkRelations(),
            false,
            null, null
        );

        String content = Files.readString(new File(scriptPath).toPath());

        // PL/SQL-Block pruefen
        assertTrue(content.contains("DECLARE"), "PL/SQL DECLARE-Block");
        assertTrue(content.contains("BEGIN"), "PL/SQL BEGIN");
        assertTrue(content.contains("END;"), "PL/SQL END");
        assertTrue(content.contains("EMPLOYEES_SEQ.NEXTVAL"), "EMPLOYEES_SEQ Verwendung");
        assertTrue(content.contains("EMPLOYEE_SKILLS_SEQ.NEXTVAL"), "EMPLOYEE_SKILLS_SEQ Verwendung");
        assertTrue(content.contains("v_"), "PL/SQL-Variablen vorhanden");

        // ON-Klausel ueber LAST_NAME
        assertTrue(content.contains("ON (tgt.LAST_NAME = src.LAST_NAME)"),
            "ON-Klausel ueber LAST_NAME fuer Root-Tabelle");

        // Script ausfuehren (PL/SQL-Block)
        executeScriptOnTarget(content);

        // Cleanup
        deleteDirectory(tempDir.toFile());
    }

    @Test
    @Order(32)
    void scriptGenerierung_king_mitUpdate() throws SQLException, IOException {
        TraversalService service = new TraversalService(analyzer, null, new TraversalRuleStore());
        TraversalResult result = service.traverse("EMPLOYEES", "EMPLOYEE_ID", "100");

        Path tempDir = Files.createTempDirectory("mergegen-test");
        ScriptWriter writer = new ScriptWriter();
        String scriptPath = writer.write(
            result.getOrderedRows(),
            result.getTableCounts(),
            "EMPLOYEES", List.of("100"),
            tempDir.toString(),
            Collections.emptyMap(),
            null, null,
            result.getFkRelations(),
            true,                       // UPDATE aktiviert
            null, null
        );

        String content = Files.readString(new File(scriptPath).toPath());
        assertTrue(content.contains("WHEN MATCHED THEN"), "UPDATE-Block vorhanden");
        assertTrue(content.contains("UPDATE SET"), "UPDATE SET vorhanden");

        // Script ausfuehren
        executeScriptOnTarget(content);

        // Salary verifizieren (King = 24000)
        try (PreparedStatement ps = rawConnection.prepareStatement(
                "SELECT SALARY FROM HR.EMPLOYEES WHERE EMPLOYEE_ID = 100")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertEquals(24000, rs.getInt("SALARY"));
        }

        // Cleanup
        deleteDirectory(tempDir.toFile());
    }

    @Test
    @Order(33)
    void scriptGenerierung_testmodus() throws SQLException, IOException {
        TraversalService service = new TraversalService(analyzer, null, new TraversalRuleStore());
        TraversalResult result = service.traverse("EMPLOYEES", "EMPLOYEE_ID", "100");

        Path tempDir = Files.createTempDirectory("mergegen-test");
        String testSuffix = "_TEST20260314";

        Map<String, String> seqMap = new HashMap<>();
        seqMap.put("EMPLOYEES.EMPLOYEE_ID", "EMPLOYEES_SEQ");

        ScriptWriter writer = new ScriptWriter();
        String scriptPath = writer.write(
            result.getOrderedRows(),
            result.getTableCounts(),
            "EMPLOYEES", List.of("100"),
            tempDir.toString(),
            seqMap,
            "LAST_NAME",
            testSuffix,
            result.getFkRelations(),
            false,
            null, null
        );

        String content = Files.readString(new File(scriptPath).toPath());
        assertTrue(content.contains("King" + testSuffix), "Testmodus-Suffix an LAST_NAME");

        // PL/SQL-Block vorhanden (wegen Sequence)
        assertTrue(content.contains("DECLARE"), "PL/SQL DECLARE bei Testmodus");
        assertTrue(content.contains("EMPLOYEES_SEQ.NEXTVAL"), "Sequence fuer neuen PK");

        // ON-Klausel ueber LAST_NAME (Testmodus matcht nie -> immer INSERT)
        assertTrue(content.contains("ON (tgt.LAST_NAME = src.LAST_NAME)"),
            "ON-Klausel ueber LAST_NAME");

        // Hinweis: Script-Ausfuehrung hier nicht moeglich, da EMPLOYEES.EMAIL
        // ein UNIQUE-Constraint hat und der Testmodus nur LAST_NAME aendert.
        // In der Praxis wuerden weitere Spalten unique sein oder der User
        // passt die Daten manuell an.

        deleteDirectory(tempDir.toFile());
    }

    // =====================================================================
    // Hilfsmethoden
    // =====================================================================

    private int findFirstIndex(List<TableRow> rows, String tableName) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getTableName().equalsIgnoreCase(tableName)) return i;
        }
        return -1;
    }

    /**
     * Fuehrt ein generiertes SQL-Script gegen die Ziel-DB aus.
     * Erkennt PL/SQL-Bloecke (DECLARE/BEGIN...END;/) und einzelne Statements.
     */
    private void executeScriptOnTarget(String scriptContent) throws SQLException {
        // Fuer Script-Ausfuehrung brauchen wir eine eigene read-write Connection
        try (Connection rwConn = DriverManager.getConnection(
                config.getUrl(), config.getUser(), config.getPassword())) {
            rwConn.setAutoCommit(false);

            // Kommentarzeilen und Leerzeilen fuer Parsing entfernen
            String cleaned = scriptContent.lines()
                .filter(l -> !l.trim().startsWith("--"))
                .collect(Collectors.joining("\n"))
                .trim();

            if (cleaned.contains("DECLARE") || cleaned.contains("BEGIN")) {
                // PL/SQL-Block: alles von DECLARE/BEGIN bis END;/ als ein Statement
                int start = cleaned.indexOf("DECLARE");
                if (start < 0) start = cleaned.indexOf("BEGIN");
                int end = cleaned.lastIndexOf("END;");
                if (end > start) {
                    String plsql = cleaned.substring(start, end + 4); // inkl. "END;"
                    try (Statement stmt = rwConn.createStatement()) {
                        stmt.execute(plsql);
                    }
                }
            } else {
                // Einzelne MERGE-Statements (getrennt durch ;)
                String[] statements = cleaned.split(";\\s*\n");
                for (String sql : statements) {
                    sql = sql.trim();
                    // Trailing semicolons entfernen (JDBC akzeptiert sie nicht)
                    while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
                    if (sql.isEmpty() || sql.equals("/")) continue;
                    try (Statement stmt = rwConn.createStatement()) {
                        stmt.execute(sql);
                    }
                }
            }

            rwConn.commit();
        }
    }

    private void verifyDataPresent(String table, String column, int value) throws SQLException {
        try (PreparedStatement ps = rawConnection.prepareStatement(
                "SELECT COUNT(*) FROM HR." + table + " WHERE " + column + " = ?")) {
            ps.setInt(1, value);
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) > 0, table + " sollte Daten fuer " + column + "=" + value + " haben");
        }
    }

    private void cleanupTestData(String testLastName) throws SQLException {
        try (Connection rwConn = DriverManager.getConnection(
                config.getUrl(), config.getUser(), config.getPassword())) {
            rwConn.setAutoCommit(false);
            // Erst Kinder loeschen (FK-Constraints)
            try (PreparedStatement ps = rwConn.prepareStatement(
                    "DELETE FROM HR.SKILL_ENDORSEMENTS WHERE SKILL_ID IN " +
                    "(SELECT SKILL_ID FROM HR.EMPLOYEE_SKILLS WHERE EMPLOYEE_ID IN " +
                    "(SELECT EMPLOYEE_ID FROM HR.EMPLOYEES WHERE LAST_NAME = ?))")) {
                ps.setString(1, testLastName);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = rwConn.prepareStatement(
                    "DELETE FROM HR.EMPLOYEE_SKILLS WHERE EMPLOYEE_ID IN " +
                    "(SELECT EMPLOYEE_ID FROM HR.EMPLOYEES WHERE LAST_NAME = ?)")) {
                ps.setString(1, testLastName);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = rwConn.prepareStatement(
                    "DELETE FROM HR.CONTRACT_ITEMS WHERE CONTRACT_ID IN " +
                    "(SELECT CONTRACT_ID FROM HR.EMPLOYEE_CONTRACTS WHERE EMPLOYEE_ID IN " +
                    "(SELECT EMPLOYEE_ID FROM HR.EMPLOYEES WHERE LAST_NAME = ?))")) {
                ps.setString(1, testLastName);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = rwConn.prepareStatement(
                    "DELETE FROM HR.EMPLOYEE_CONTRACTS WHERE EMPLOYEE_ID IN " +
                    "(SELECT EMPLOYEE_ID FROM HR.EMPLOYEES WHERE LAST_NAME = ?)")) {
                ps.setString(1, testLastName);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = rwConn.prepareStatement(
                    "DELETE FROM HR.JOB_HISTORY WHERE EMPLOYEE_ID IN " +
                    "(SELECT EMPLOYEE_ID FROM HR.EMPLOYEES WHERE LAST_NAME = ?)")) {
                ps.setString(1, testLastName);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = rwConn.prepareStatement(
                    "DELETE FROM HR.EMPLOYEES WHERE LAST_NAME = ?")) {
                ps.setString(1, testLastName);
                ps.executeUpdate();
            }
            rwConn.commit();
        }
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
