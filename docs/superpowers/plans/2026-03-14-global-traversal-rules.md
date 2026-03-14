# Global Traversal Rules & Sequence-Skip – Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Traversal-Entscheidungen global pro Root-Tabelle speichern und bei erneuter Analyse anbieten; bereits bekannte Sequence-Mappings ohne Dialog uebernehmen.

**Architecture:** Neuer `GlobalTraversalRuleStore` persistiert Traversal-Regeln pro Root-Tabelle in `config/mergegen/traversal-rules.txt`. `GeneratorPanel.startAnalysis()` prueft vor dem Traversal ob globale Regeln vorliegen und bietet Uebernahme an. Sequence-Dialog wird uebersprungen wenn `SequenceMappingStore` bereits einen Eintrag hat.

**Tech Stack:** Java 11, Swing, JUnit 5

---

## File Structure

| Aktion | Datei | Verantwortung |
|--------|-------|---------------|
| Create | `src/main/java/com/mergegen/config/GlobalTraversalRuleStore.java` | Persistenz: Root-Tabelle → Traversal-Regeln |
| Create | `src/test/java/com/mergegen/config/GlobalTraversalRuleStoreTest.java` | Unit-Tests fuer den neuen Store |
| Modify | `src/main/java/com/mergegen/gui/GeneratorPanel.java` | Dialog vor Analyse + Sequence-Skip |
| Modify | `src/main/java/com/migrationtool/launcher/LauncherApp.java` | Store instanziieren + uebergeben |

---

## Task 1: GlobalTraversalRuleStore – Tests + Implementierung

**Files:**
- Create: `src/test/java/com/mergegen/config/GlobalTraversalRuleStoreTest.java`
- Create: `src/main/java/com/mergegen/config/GlobalTraversalRuleStore.java`

### Kontext

Der Store speichert pro Root-Tabelle (uppercase) eine Map von FK-Keys (`PARENT>CHILD.FK_COL`) auf `TraversalRule` (TRAVERSE/SKIP/SUBSELECT). Dateiformat analog zu `QueryPresetStore.parseRules()`/`formatRules()`:

```
# Globale Traversal-Regeln pro Root-Tabelle
# Format: ROOT_TABLE|PARENT>CHILD.FK=JA;PARENT>CHILD.FK=NEIN;...
EMPLOYEES|EMPLOYEES>JOB_HISTORY.EMPLOYEE_ID=JA;EMPLOYEES>EMPLOYEE_SKILLS.EMPLOYEE_ID=NEIN
DEPARTMENTS|DEPARTMENTS>EMPLOYEES.DEPARTMENT_ID=SUBSELECT
```

- [ ] **Step 1: Test-Klasse anlegen**

```java
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
```

- [ ] **Step 2: Tests ausfuehren – muessen fehlschlagen**

Run: `./gradlew test --tests "*GlobalTraversalRuleStoreTest*" --info`
Expected: Kompilierfehler (Klasse existiert noch nicht)

- [ ] **Step 3: GlobalTraversalRuleStore implementieren**

```java
package com.mergegen.config;

import com.mergegen.config.TraversalRuleStore.TraversalRule;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persistiert Traversal-Regeln global pro Root-Tabelle.
 * Datei: config/mergegen/traversal-rules.txt
 */
public class GlobalTraversalRuleStore {

    private static final String LIST_SEP = ";";
    private static final String DEFAULT_PATH = "config/mergegen/traversal-rules.txt";

    private final Path filePath;
    private final Map<String, Map<String, TraversalRule>> rulesByTable = new LinkedHashMap<>();

    public GlobalTraversalRuleStore() {
        this(Paths.get(DEFAULT_PATH));
    }

    public GlobalTraversalRuleStore(Path filePath) {
        this.filePath = filePath;
        load();
    }

    public boolean hasRulesFor(String rootTable) {
        return rulesByTable.containsKey(rootTable.toUpperCase());
    }

    public Map<String, TraversalRule> getRulesFor(String rootTable) {
        Map<String, TraversalRule> rules = rulesByTable.get(rootTable.toUpperCase());
        return rules != null ? new LinkedHashMap<>(rules) : Collections.emptyMap();
    }

    public void saveRulesFor(String rootTable, Map<String, TraversalRule> rules) {
        String key = rootTable.toUpperCase();
        if (rules == null || rules.isEmpty()) {
            rulesByTable.remove(key);
        } else {
            rulesByTable.put(key, new LinkedHashMap<>(rules));
        }
        save();
    }

    private void load() {
        if (!Files.exists(filePath)) return;
        try {
            for (String line : Files.readAllLines(filePath)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length < 2) continue;
                String table = parts[0].trim().toUpperCase();
                Map<String, TraversalRule> rules = parseRules(parts[1]);
                if (!rules.isEmpty()) {
                    rulesByTable.put(table, rules);
                }
            }
        } catch (IOException e) {
            // Datei nicht lesbar – leerer Store
        }
    }

    private void save() {
        try {
            Files.createDirectories(filePath.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Globale Traversal-Regeln pro Root-Tabelle");
            lines.add("# Format: ROOT_TABLE|PARENT>CHILD.FK=JA/NEIN/SUBSELECT;...");
            for (Map.Entry<String, Map<String, TraversalRule>> entry : rulesByTable.entrySet()) {
                lines.add(entry.getKey() + "|" + formatRules(entry.getValue()));
            }
            Files.write(filePath, lines);
        } catch (IOException e) {
            // Silent fail – analog zu anderen Stores
        }
    }

    private static Map<String, TraversalRule> parseRules(String s) {
        Map<String, TraversalRule> rules = new LinkedHashMap<>();
        for (String part : s.split(LIST_SEP, -1)) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int eq = part.lastIndexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq).trim();
            String val = part.substring(eq + 1).trim().toUpperCase();
            TraversalRule rule;
            switch (val) {
                case "JA":        rule = TraversalRule.TRAVERSE;  break;
                case "SUBSELECT": rule = TraversalRule.SUBSELECT; break;
                default:          rule = TraversalRule.SKIP;      break;
            }
            rules.put(key, rule);
        }
        return rules;
    }

    private static String formatRules(Map<String, TraversalRule> rules) {
        return rules.entrySet().stream()
            .map(e -> {
                String val;
                switch (e.getValue()) {
                    case TRAVERSE:  val = "JA";        break;
                    case SUBSELECT: val = "SUBSELECT"; break;
                    default:        val = "NEIN";      break;
                }
                return e.getKey() + "=" + val;
            })
            .collect(Collectors.joining(LIST_SEP));
    }
}
```

- [ ] **Step 4: Tests ausfuehren – muessen alle gruen sein**

Run: `./gradlew test --tests "*GlobalTraversalRuleStoreTest*" --info`
Expected: 8 Tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mergegen/config/GlobalTraversalRuleStore.java \
        src/test/java/com/mergegen/config/GlobalTraversalRuleStoreTest.java
git commit -m "feat: GlobalTraversalRuleStore mit Tests"
```

---

## Task 2: GeneratorPanel – Uebernahme-Dialog + Auto-Save

**Files:**
- Modify: `src/main/java/com/mergegen/gui/GeneratorPanel.java`
- Modify: `src/main/java/com/migrationtool/launcher/LauncherApp.java`

### Kontext

In `GeneratorPanel.startAnalysis()` (ca. Zeile 265) wird aktuell nur geprueft ob ein Preset aktiv ist. Hier muss VORHER der neue Dialog eingefuegt werden. Nach erfolgreicher Analyse muessen die Regeln global gespeichert werden.

- [ ] **Step 1: LauncherApp – Store erstellen und uebergeben**

In `LauncherApp` (Zeile 48-56): Neues Feld `GlobalTraversalRuleStore` anlegen und an `GeneratorPanel` uebergeben.

```java
// Nach den bestehenden Store-Initialisierungen:
GlobalTraversalRuleStore globalRuleStore = new GlobalTraversalRuleStore();

// GeneratorPanel-Konstruktor erweitern:
GeneratorPanel generatorPanel = new GeneratorPanel(settingsPanel, virtualFkStore,
    ruleStore, seqStore, constTableStore, presetStore, subselectStore, globalRuleStore);
```

Import hinzufuegen: `import com.mergegen.config.GlobalTraversalRuleStore;`

- [ ] **Step 2: GeneratorPanel – Konstruktor erweitern**

Neues Feld + Konstruktor-Parameter:

```java
// Neues Feld (bei den anderen Store-Feldern):
private final GlobalTraversalRuleStore globalRuleStore;

// Konstruktor-Signatur erweitern:
public GeneratorPanel(SettingsPanel settingsPanel, VirtualFkStore virtualFkStore,
                      TraversalRuleStore ruleStore,
                      SequenceMappingStore seqStore, ConstantTableStore constTableStore,
                      QueryPresetStore presetStore,
                      SubselectMappingStore subselectStore,
                      GlobalTraversalRuleStore globalRuleStore) {
    // ... bestehende Zuweisungen ...
    this.globalRuleStore = globalRuleStore;
```

Import hinzufuegen: `import com.mergegen.config.GlobalTraversalRuleStore;`

- [ ] **Step 3: Uebernahme-Dialog in startAnalysis() einfuegen**

In `startAnalysis()`, NACH der Validierung aber VOR dem `ruleStore.clear()` Block (ca. Zeile 281):

```java
// --- Globale Traversal-Regeln pruefen (nur wenn kein Preset aktiv) ---
String activePreset = (String) presetCombo.getSelectedItem();
if (activePreset == null || activePreset.equals("(kein Preset)")) {
    String rootTable = tableField.getText().trim().toUpperCase();
    if (globalRuleStore.hasRulesFor(rootTable)) {
        int choice = JOptionPane.showOptionDialog(
            this,
            "Fuer " + rootTable + " existieren bereits gespeicherte Traversal-Regeln.\n"
                + "Sollen diese uebernommen werden?",
            "Traversal-Regeln",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            new String[]{"Uebernehmen", "Neu eingeben", "Abbrechen"},
            "Uebernehmen");

        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
            return;  // Abbruch
        }
        if (choice == 0) {
            // Uebernehmen: globale Regeln laden
            ruleStore.loadFrom(globalRuleStore.getRulesFor(rootTable));
        } else {
            // Neu eingeben: leer starten
            ruleStore.clear();
        }
    } else {
        ruleStore.clear();
    }
}
```

Den bestehenden Block (Zeile 281-286) entfernen, da er durch den neuen Code ersetzt wird:
```java
// ENTFERNEN:
// String activePreset = (String) presetCombo.getSelectedItem();
// if (activePreset == null || activePreset.equals("(kein Preset)")) {
//     ruleStore.clear();
// }
```

- [ ] **Step 4: Auto-Save nach erfolgreicher Analyse**

Im `SwingWorker.done()` der Analyse (nach erfolgreichem Traversal), die Regeln global speichern:

```java
// Nach dem erfolgreichen Traversal (im done()-Block, nach showTreeCard()):
String rootTable = tableField.getText().trim().toUpperCase();
Map<String, TraversalRuleStore.TraversalRule> currentRules = ruleStore.getAll();
if (!currentRules.isEmpty()) {
    globalRuleStore.saveRulesFor(rootTable, currentRules);
}
```

- [ ] **Step 5: Kompilieren und manuell testen**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Bestehende Tests ausfuehren**

Run: `./gradlew test`
Expected: Alle 54 Tests PASS (keine Regression)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/mergegen/gui/GeneratorPanel.java \
        src/main/java/com/migrationtool/launcher/LauncherApp.java
git commit -m "feat: Uebernahme-Dialog fuer globale Traversal-Regeln"
```

---

## Task 3: Sequence-Skip bei bekannten Mappings

**Files:**
- Modify: `src/main/java/com/mergegen/gui/GeneratorPanel.java`

### Kontext

Der Sequence-Dialog (Zeile 607-700) fragt aktuell IMMER fuer jede PK-Spalte, auch wenn der `SequenceMappingStore` bereits einen Eintrag hat. Die Logik muss so geaendert werden, dass bei vorhandenem Store-Eintrag der Dialog uebersprungen und der gespeicherte Wert direkt verwendet wird.

- [ ] **Step 1: Sequence-Dialog-Schleife anpassen**

In der Schleife ab Zeile 658: Wenn `seqStore.findByTable(tbl)` einen Treffer hat, direkt in `sequenceMap` eintragen und `continue` (kein Dialog).

Aktueller Code (Zeile 654-698) – ersetzen durch:

```java
                    String key = tbl + "." + pkCol;

                    // Vierstufige Vorschlags-Logik
                    String suggestion = "";

                    // 1. Im Store gespeichert? -> direkt uebernehmen, kein Dialog
                    Optional<SequenceMapping> stored = seqStore.findByTable(tbl);
                    if (stored.isPresent() && stored.get().getPkColumn().equalsIgnoreCase(pkCol)
                            && !stored.get().getSequenceName().isEmpty()) {
                        sequenceMap.put(key, stored.get().getSequenceName());
                        continue;  // <<< NEU: Dialog ueberspringen
                    }

                    // 2. STB_TABDEF nachschlagen
                    if (triggerAnalyzer != null) {
                        Optional<String> tabdefSeq = triggerAnalyzer.lookupSequenceFromTabdef(tbl);
                        if (tabdefSeq.isPresent()) {
                            suggestion = tabdefSeq.get();
                        }
                    }

                    // 3. Trigger pruefen
                    if (suggestion.isEmpty() && triggerAnalyzer != null) {
                        Optional<String> triggerSeq = triggerAnalyzer.detectTriggerSequence(tbl);
                        if (triggerSeq.isPresent()) {
                            suggestion = triggerSeq.get();
                        }
                    }

                    // 4. Dialog anzeigen
                    String input = (String) JOptionPane.showInputDialog(
                        this,
                        "Tabelle " + tbl + ", PK-Spalte " + pkCol +
                        "\n(leer = PK-Wert aus Quelle uebernehmen)",
                        "Sequence-Name",
                        JOptionPane.QUESTION_MESSAGE,
                        null, null,
                        suggestion);

                    // Abbruch -> gesamte Generierung abbrechen
                    if (input == null) return;

                    input = input.trim().toUpperCase();
                    if (!input.isEmpty()) {
                        sequenceMap.put(key, input);
                        // Im Store speichern/aktualisieren
                        seqStore.remove(tbl, pkCol);
                        seqStore.add(new SequenceMapping(tbl, pkCol, input));
                    }
```

- [ ] **Step 2: Kompilieren und Tests**

Run: `./gradlew test`
Expected: Alle Tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/mergegen/gui/GeneratorPanel.java
git commit -m "feat: Sequence-Skip bei bereits bekannten Mappings"
```

---

## Task 4: Gesamttest + CLAUDE.md aktualisieren

- [ ] **Step 1: Alle Unit-Tests ausfuehren**

Run: `./gradlew test`
Expected: Alle Tests PASS (mindestens 62 Tests: 54 bestehende + 8 neue)

- [ ] **Step 2: CLAUDE.md aktualisieren**

In der Projektstruktur den neuen Store dokumentieren:
- `GlobalTraversalRuleStore` unter `com/mergegen/config/`
- `config/mergegen/traversal-rules.txt` bei den Laufzeit-Konfigurationsdateien
- Testanzahl aktualisieren

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: GlobalTraversalRuleStore + Sequence-Skip dokumentiert"
```
