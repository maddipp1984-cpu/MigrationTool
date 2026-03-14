package com.mergegen.config;

import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SubselectMappingStoreTest {

    private Path tempDir;
    private SubselectMappingStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("subselect-test");
        store = new SubselectMappingStore(tempDir);
    }

    @AfterEach
    void tearDown() {
        try { Files.walk(tempDir).sorted(Comparator.reverseOrder())
                  .map(Path::toFile).forEach(File::delete); }
        catch (IOException ignored) {}
    }

    @Test
    void addAndGet_singleLookupColumn() {
        store.add("DEPARTMENTS", "DEPARTMENT_ID", List.of("DEPARTMENT_NAME"));
        assertTrue(store.hasMapping("DEPARTMENTS"));
        assertEquals("DEPARTMENT_ID", store.getPkColumn("DEPARTMENTS"));
        assertEquals(List.of("DEPARTMENT_NAME"), store.getLookupColumns("DEPARTMENTS"));
    }

    @Test
    void addAndGet_compositeLookupColumns() {
        store.add("REGIONS", "REGION_ID", List.of("REGION_NAME", "COUNTRY_CODE"));
        assertEquals(List.of("REGION_NAME", "COUNTRY_CODE"),
            store.getLookupColumns("REGIONS"));
    }

    @Test
    void caseInsensitive() {
        store.add("departments", "department_id", List.of("department_name"));
        assertTrue(store.hasMapping("DEPARTMENTS"));
        assertEquals("DEPARTMENT_ID", store.getPkColumn("DEPARTMENTS"));
    }

    @Test
    void remove() {
        store.add("DEPARTMENTS", "DEPARTMENT_ID", List.of("DEPARTMENT_NAME"));
        store.remove("DEPARTMENTS");
        assertFalse(store.hasMapping("DEPARTMENTS"));
    }

    @Test
    void persistenzRoundtrip() {
        store.add("DEPARTMENTS", "DEPARTMENT_ID", List.of("DEPARTMENT_NAME"));
        store.add("REGIONS", "REGION_ID", List.of("REGION_NAME", "COUNTRY_CODE"));

        SubselectMappingStore store2 = new SubselectMappingStore(tempDir);
        assertTrue(store2.hasMapping("DEPARTMENTS"));
        assertTrue(store2.hasMapping("REGIONS"));
        assertEquals(List.of("REGION_NAME", "COUNTRY_CODE"),
            store2.getLookupColumns("REGIONS"));
    }

    @Test
    void buildSubselect_singleColumn() {
        store.add("DEPARTMENTS", "DEPARTMENT_ID", List.of("DEPARTMENT_NAME"));
        String result = store.buildSubselect("DEPARTMENTS",
            Map.of("DEPARTMENT_NAME", "'Executive'"));
        assertEquals(
            "(SELECT DEPARTMENT_ID FROM DEPARTMENTS WHERE DEPARTMENT_NAME = 'Executive')",
            result);
    }

    @Test
    void buildSubselect_compositeColumns() {
        store.add("REGIONS", "REGION_ID", List.of("REGION_NAME", "COUNTRY_CODE"));
        String result = store.buildSubselect("REGIONS",
            Map.of("REGION_NAME", "'Europe'", "COUNTRY_CODE", "'DE'"));
        assertTrue(result.contains("REGION_NAME = 'Europe'"));
        assertTrue(result.contains("COUNTRY_CODE = 'DE'"));
        assertTrue(result.startsWith("(SELECT REGION_ID FROM REGIONS WHERE "));
    }

    @Test
    void noMapping_returnsFalse() {
        assertFalse(store.hasMapping("NONEXISTENT"));
        assertNull(store.getLookupColumns("NONEXISTENT"));
    }

    @Test
    void buildSubselect_nullLookupValue() {
        store.add("DEPARTMENTS", "DEPARTMENT_ID", List.of("DEPARTMENT_NAME"));
        Map<String, String> rowValues = new HashMap<>();
        rowValues.put("DEPARTMENT_NAME", null);
        String result = store.buildSubselect("DEPARTMENTS", rowValues);
        assertTrue(result.contains("DEPARTMENT_NAME IS NULL"),
            "NULL-Wert muss IS NULL erzeugen");
    }

    @Test
    void buildSubselect_explicitNullLiteral() {
        store.add("DEPARTMENTS", "DEPARTMENT_ID", List.of("DEPARTMENT_NAME"));
        String result = store.buildSubselect("DEPARTMENTS",
            Map.of("DEPARTMENT_NAME", "NULL"));
        assertTrue(result.contains("DEPARTMENT_NAME IS NULL"),
            "SQL-Literal NULL muss IS NULL erzeugen");
    }

    @Test
    void buildSubselect_missingLookupColumn() {
        store.add("DEPARTMENTS", "DEPARTMENT_ID", List.of("DEPARTMENT_NAME"));
        // rowValues enthaelt die Lookup-Spalte nicht
        String result = store.buildSubselect("DEPARTMENTS", Map.of());
        assertTrue(result.contains("DEPARTMENT_NAME IS NULL"),
            "Fehlende Spalte muss IS NULL erzeugen");
    }
}
