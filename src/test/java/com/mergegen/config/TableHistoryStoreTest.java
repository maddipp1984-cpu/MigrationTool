package com.mergegen.config;

import com.mergegen.model.TableHistoryEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests fuer TableHistoryStore – Duplikat-Erkennung, Move-to-front,
 * Timestamp-Update, Persistenz-Roundtrip.
 */
class TableHistoryStoreTest {

    private TableHistoryStore createStore(Path tempDir) {
        return new TableHistoryStore(tempDir.toString());
    }

    private TableHistoryEntry entry(String table, String column, List<String> values,
                                     List<String> constants, long timestamp) {
        TableHistoryEntry e = new TableHistoryEntry(table, column, values, constants);
        e.setTimestamp(timestamp);
        return e;
    }

    // ── Tests ────────────────────────────────────────────────────────────

    @Test
    void testAddNewEntry(@TempDir Path tempDir) {
        TableHistoryStore store = createStore(tempDir);
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("42"), List.of(), 1000L));

        assertEquals(1, store.getAll().size());
        assertEquals("AUFTRAG", store.getAll().get(0).getTable());
    }

    @Test
    void testDuplicateCaseInsensitive(@TempDir Path tempDir) {
        TableHistoryStore store = createStore(tempDir);
        store.addOrUpdate(entry("auftrag", "id", List.of("42"), List.of(), 1000L));
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("42"), List.of(), 2000L));

        assertEquals(1, store.getAll().size(), "Duplikat darf keinen neuen Eintrag erzeugen");
    }

    @Test
    void testDuplicateOrderIndependent(@TempDir Path tempDir) {
        TableHistoryStore store = createStore(tempDir);
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("A", "B"), List.of(), 1000L));
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("B", "A"), List.of(), 2000L));

        assertEquals(1, store.getAll().size(), "Werte-Reihenfolge darf keinen Unterschied machen");
    }

    @Test
    void testDuplicateMovesToFront(@TempDir Path tempDir) {
        TableHistoryStore store = createStore(tempDir);
        store.addOrUpdate(entry("ERSTE", "ID", List.of("1"), List.of(), 1000L));
        store.addOrUpdate(entry("ZWEITE", "ID", List.of("2"), List.of(), 2000L));
        // ERSTE erneut -> muss an Position 0
        store.addOrUpdate(entry("ERSTE", "ID", List.of("1"), List.of(), 3000L));

        assertEquals("ERSTE", store.getAll().get(0).getTable(),
            "Duplikat muss an den Anfang verschoben werden");
    }

    @Test
    void testDuplicateUpdatesTimestamp(@TempDir Path tempDir) {
        TableHistoryStore store = createStore(tempDir);
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("42"), List.of(), 1000L));
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("42"), List.of(), 9999L));

        assertEquals(9999L, store.getAll().get(0).getTimestamp(),
            "Timestamp muss aktualisiert werden");
    }

    @Test
    void testDuplicateKeepsConstantsIfNewEmpty(@TempDir Path tempDir) {
        TableHistoryStore store = createStore(tempDir);
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("42"), List.of("STATUS_TAB"), 1000L));
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("42"), List.of(), 2000L));

        List<String> constants = store.getAll().get(0).getConstantTables();
        assertTrue(constants.contains("STATUS_TAB"),
            "Alte Konstantentabellen muessen erhalten bleiben wenn neue leer");
    }

    @Test
    void testDuplicateOverwritesConstantsIfNewNonEmpty(@TempDir Path tempDir) {
        TableHistoryStore store = createStore(tempDir);
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("42"), List.of("OLD_TAB"), 1000L));
        store.addOrUpdate(entry("AUFTRAG", "ID", List.of("42"), List.of("NEW_TAB"), 2000L));

        List<String> constants = store.getAll().get(0).getConstantTables();
        assertTrue(constants.contains("NEW_TAB"), "Neue Constants muessen uebernommen werden");
        assertFalse(constants.contains("OLD_TAB"), "Alte Constants muessen ersetzt werden");
    }

    @Test
    void testRemoveEntry(@TempDir Path tempDir) {
        TableHistoryStore store = createStore(tempDir);
        TableHistoryEntry e = entry("AUFTRAG", "ID", List.of("42"), List.of(), 1000L);
        store.addOrUpdate(e);
        store.remove(store.getAll().get(0));

        assertTrue(store.getAll().isEmpty(), "Nach remove() muss die Liste leer sein");
    }

    @Test
    void testPersistenceRoundTrip(@TempDir Path tempDir) {
        TableHistoryStore store1 = createStore(tempDir);
        store1.addOrUpdate(entry("AUFTRAG", "NR", List.of("A-001", "A-002"),
            List.of("STATUS"), 12345L));

        // Neuen Store auf gleichem Pfad oeffnen -> muss Daten laden
        TableHistoryStore store2 = createStore(tempDir);
        assertEquals(1, store2.getAll().size(), "Roundtrip: Eintrag muss geladen werden");
        TableHistoryEntry loaded = store2.getAll().get(0);
        assertEquals("AUFTRAG", loaded.getTable());
        assertEquals("NR", loaded.getColumn());
        assertEquals(List.of("A-001", "A-002"), loaded.getValues());
        assertEquals(List.of("STATUS"), loaded.getConstantTables());
        assertEquals(12345L, loaded.getTimestamp());
    }

    @Test
    void testFindMatchReturnsEmptyForNoMatch(@TempDir Path tempDir) {
        TableHistoryStore store = createStore(tempDir);
        assertTrue(store.findMatch("NOPE", "COL", List.of("val")).isEmpty());
    }
}
