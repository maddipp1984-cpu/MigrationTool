package com.mergegen.config;

import com.mergegen.config.TraversalRuleStore.TraversalRule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GlobalTraversalRuleStoreTest {

    @TempDir
    Path tempDir;

    private GlobalTraversalRuleStore store;
    private Path storeFile;

    @BeforeEach
    void setUp() {
        storeFile = tempDir.resolve("traversal-rules.txt");
        store = new GlobalTraversalRuleStore(storeFile);
    }

    @Test
    void emptyStoreHasNoRules() {
        assertFalse(store.hasRulesFor("EMPLOYEES"));
        assertTrue(store.getRulesFor("EMPLOYEES").isEmpty());
    }

    @Test
    void saveAndRetrieveRules() {
        Map<String, TraversalRule> rules = new LinkedHashMap<>();
        rules.put("EMPLOYEES>JOB_HISTORY.EMPLOYEE_ID", TraversalRule.TRAVERSE);
        rules.put("EMPLOYEES>EMPLOYEE_SKILLS.EMPLOYEE_ID", TraversalRule.SKIP);

        store.saveRulesFor("EMPLOYEES", rules);

        assertTrue(store.hasRulesFor("EMPLOYEES"));
        assertEquals(rules, store.getRulesFor("EMPLOYEES"));
    }

    @Test
    void tableNameIsCaseInsensitive() {
        Map<String, TraversalRule> rules = Map.of(
            "A>B.FK", TraversalRule.TRAVERSE);
        store.saveRulesFor("employees", rules);

        assertTrue(store.hasRulesFor("EMPLOYEES"));
        assertTrue(store.hasRulesFor("Employees"));
    }

    @Test
    void updateExistingRules() {
        store.saveRulesFor("EMP", Map.of("A>B.FK", TraversalRule.TRAVERSE));
        store.saveRulesFor("EMP", Map.of("A>B.FK", TraversalRule.SKIP));

        assertEquals(TraversalRule.SKIP, store.getRulesFor("EMP").get("A>B.FK"));
    }

    @Test
    void multipleTablesIndependent() {
        store.saveRulesFor("T1", Map.of("A>B.FK", TraversalRule.TRAVERSE));
        store.saveRulesFor("T2", Map.of("X>Y.FK", TraversalRule.SUBSELECT));

        assertEquals(1, store.getRulesFor("T1").size());
        assertEquals(1, store.getRulesFor("T2").size());
        assertFalse(store.hasRulesFor("T3"));
    }

    @Test
    void persistenceRoundtrip() throws IOException {
        Map<String, TraversalRule> rules = new LinkedHashMap<>();
        rules.put("E>JH.EID", TraversalRule.TRAVERSE);
        rules.put("E>ES.EID", TraversalRule.SUBSELECT);
        store.saveRulesFor("EMPLOYEES", rules);

        // Neuer Store aus gleicher Datei
        GlobalTraversalRuleStore store2 = new GlobalTraversalRuleStore(storeFile);
        assertTrue(store2.hasRulesFor("EMPLOYEES"));
        assertEquals(rules, store2.getRulesFor("EMPLOYEES"));
    }

    @Test
    void emptyRulesMapRemovesEntry() {
        store.saveRulesFor("T1", Map.of("A>B.FK", TraversalRule.TRAVERSE));
        store.saveRulesFor("T1", Collections.emptyMap());

        assertFalse(store.hasRulesFor("T1"));
    }

    @Test
    void subselectRulePersisted() {
        store.saveRulesFor("T1", Map.of("P>C.FK", TraversalRule.SUBSELECT));

        GlobalTraversalRuleStore store2 = new GlobalTraversalRuleStore(storeFile);
        assertEquals(TraversalRule.SUBSELECT, store2.getRulesFor("T1").get("P>C.FK"));
    }
}
