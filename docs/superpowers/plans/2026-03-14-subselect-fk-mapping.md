# Subselect-FK-Mapping Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bei der Traversal-Entscheidung eine dritte Option "Subselect" anbieten, die FK-Werte im generierten MERGE-Script durch Subselects ersetzt, damit Referenzdaten mit abweichenden IDs auf der Ziel-DB korrekt aufgeloest werden.

**Architektur:** Neuer `SubselectMappingStore` speichert pro Tabelle die Lookup-Spalten. Der `TraversalService.TraversalDecider` wird um eine dritte Antwortmoeglichkeit erweitert (Enum statt boolean). `MergeScriptGenerator` erhaelt die Subselect-Map und ersetzt FK-Werte durch `(SELECT pk FROM tabelle WHERE key=wert)`. Die GUI bekommt einen erweiterten Dialog mit 3 Buttons + Spaltenauswahl.

**Tech Stack:** Java 11, Swing, JUnit 5, Oracle JDBC

---

## Dateiuebersicht

| Aktion | Datei | Verantwortung |
|--------|-------|---------------|
| Create | `src/main/java/com/mergegen/config/SubselectMappingStore.java` | Persistenz: Tabelle -> Lookup-Spalten |
| Modify | `src/main/java/com/mergegen/service/TraversalService.java` | Enum statt boolean im Decider, Subselect-Pfad |
| Modify | `src/main/java/com/mergegen/generator/MergeScriptGenerator.java` | Subselect-Ersetzung im USING-SELECT |
| Modify | `src/main/java/com/mergegen/generator/ScriptWriter.java` | Subselect-Map durchreichen |
| Modify | `src/main/java/com/mergegen/gui/GeneratorPanel.java` | 3-Optionen-Dialog + Spaltenauswahl |
| Modify | `src/main/java/com/mergegen/config/TraversalRuleStore.java` | Dritten Zustand speichern (SUBSELECT) |
| Create | `src/test/java/com/mergegen/config/SubselectMappingStoreTest.java` | Unit-Tests fuer Store |
| Create | `src/test/java/com/mergegen/generator/SubselectGeneratorTest.java` | Unit-Tests fuer Subselect-Generierung |
| Create | `src/integrationTest/java/com/mergegen/SubselectIntegrationTest.java` | Integration-Test gegen Oracle |

---

## Chunk 1: SubselectMappingStore + TraversalRuleStore-Erweiterung

### Task 1: SubselectMappingStore – Tests

**Files:**
- Create: `src/test/java/com/mergegen/config/SubselectMappingStoreTest.java`

- [ ] **Step 1: Test-Klasse anlegen**

```java
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
}
```

- [ ] **Step 2: Tests ausfuehren – muessen fehlschlagen (Klasse existiert nicht)**

Run: `./gradlew test --tests "*SubselectMappingStoreTest*" 2>&1 | tail -5`
Expected: FAIL (Kompilierfehler)

---

### Task 2: SubselectMappingStore – Implementierung

**Files:**
- Create: `src/main/java/com/mergegen/config/SubselectMappingStore.java`

- [ ] **Step 3: Store-Klasse anlegen**

```java
package com.mergegen.config;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Speichert Subselect-Mappings: Tabelle -> PK-Spalte + Lookup-Spalten.
 * Datei: config/mergegen/subselect-mappings.txt
 * Format: TABLE|PK_COL|LOOKUP_COL1;LOOKUP_COL2
 */
public class SubselectMappingStore {

    private static final String FILE_NAME = "subselect-mappings.txt";

    private final Path filePath;
    // Key: TABLE (uppercase), Value: [0]=PK_COL, [1..n]=LOOKUP_COLS
    private final Map<String, String[]> mappings = new LinkedHashMap<>();

    public SubselectMappingStore() {
        this(Paths.get("config", "mergegen"));
    }

    /** Test-Konstruktor mit explizitem Basispfad. */
    public SubselectMappingStore(Path baseDir) {
        this.filePath = baseDir.resolve(FILE_NAME);
        load();
    }

    public boolean hasMapping(String table) {
        return mappings.containsKey(table.toUpperCase());
    }

    public String getPkColumn(String table) {
        String[] entry = mappings.get(table.toUpperCase());
        return entry != null ? entry[0] : null;
    }

    public List<String> getLookupColumns(String table) {
        String[] entry = mappings.get(table.toUpperCase());
        if (entry == null) return null;
        return List.of(Arrays.copyOfRange(entry, 1, entry.length));
    }

    public void add(String table, String pkColumn, List<String> lookupColumns) {
        String[] entry = new String[1 + lookupColumns.size()];
        entry[0] = pkColumn.toUpperCase();
        for (int i = 0; i < lookupColumns.size(); i++) {
            entry[i + 1] = lookupColumns.get(i).toUpperCase();
        }
        mappings.put(table.toUpperCase(), entry);
        save();
    }

    public void remove(String table) {
        mappings.remove(table.toUpperCase());
        save();
    }

    /**
     * Baut einen Subselect-Ausdruck fuer eine FK-Spalte.
     * @param table      Zieltabelle (z.B. DEPARTMENTS)
     * @param rowValues  Map aller Spaltenwerte der referenzierten Zeile (SQL-Literale)
     * @return "(SELECT PK FROM TABLE WHERE COL1 = val1 AND COL2 = val2)"
     */
    public String buildSubselect(String table, Map<String, String> rowValues) {
        String[] entry = mappings.get(table.toUpperCase());
        if (entry == null) return null;

        String pkCol = entry[0];
        List<String> lookupCols = List.of(Arrays.copyOfRange(entry, 1, entry.length));

        String whereClause = lookupCols.stream()
            .map(col -> col + " = " + rowValues.get(col))
            .collect(Collectors.joining(" AND "));

        return "(SELECT " + pkCol + " FROM " + table.toUpperCase()
             + " WHERE " + whereClause + ")";
    }

    public Map<String, String[]> getAll() {
        return new LinkedHashMap<>(mappings);
    }

    private void load() {
        if (!Files.exists(filePath)) return;
        try {
            for (String line : Files.readAllLines(filePath)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 3) continue;
                String table = parts[0].toUpperCase();
                String pkCol = parts[1].toUpperCase();
                String[] lookups = parts[2].toUpperCase().split(";");
                String[] entry = new String[1 + lookups.length];
                entry[0] = pkCol;
                System.arraycopy(lookups, 0, entry, 1, lookups.length);
                mappings.put(table, entry);
            }
        } catch (IOException e) {
            System.err.println("Subselect-Mappings laden fehlgeschlagen: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(filePath.getParent());
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String[]> e : mappings.entrySet()) {
                String[] entry = e.getValue();
                String lookups = String.join(";",
                    Arrays.copyOfRange(entry, 1, entry.length));
                lines.add(e.getKey() + "|" + entry[0] + "|" + lookups);
            }
            Files.write(filePath, lines);
        } catch (IOException e) {
            System.err.println("Subselect-Mappings speichern fehlgeschlagen: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Tests ausfuehren – muessen alle bestehen**

Run: `./gradlew test --tests "*SubselectMappingStoreTest*" -i 2>&1 | tail -15`
Expected: 8 Tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mergegen/config/SubselectMappingStore.java \
        src/test/java/com/mergegen/config/SubselectMappingStoreTest.java
git commit -m "feat: SubselectMappingStore mit Persistenz und buildSubselect()"
```

---

### Task 3: TraversalRuleStore – Dritten Zustand SUBSELECT

**Files:**
- Modify: `src/main/java/com/mergegen/config/TraversalRuleStore.java`

- [ ] **Step 6: Enum + Anpassungen**

Aktuellen Map-Typ von `Map<String, Boolean>` auf `Map<String, TraversalRule>` aendern:

```java
public enum TraversalRule {
    TRAVERSE,    // ja, traversieren
    SKIP,        // nein, ueberspringen
    SUBSELECT    // nicht traversieren, aber FK durch Subselect ersetzen
}
```

Aenderungen in `TraversalRuleStore`:
- `rules` wird `Map<String, TraversalRule>` statt `Map<String, Boolean>`
- `shouldTraverse()` gibt `true` zurueck bei `TRAVERSE`, sonst `false`
- Neue Methode: `getRule(parent, child, fkCol)` gibt `TraversalRule` zurueck
- `setRule()` nimmt `TraversalRule` statt `boolean`
- `getAll()` gibt `Map<String, TraversalRule>` zurueck
- `loadFrom()` nimmt `Map<String, TraversalRule>`
- Abwaertskompatibilitaet: `setRule(parent, child, fkCol, boolean)` bleibt als Convenience (mappt true->TRAVERSE, false->SKIP)

- [ ] **Step 7: Tests ausfuehren – bestehende Tests muessen weiterhin bestehen**

Run: `./gradlew test 2>&1 | tail -5`
Expected: Alle 50 Tests PASS (Kompilierfehler moeglich wenn GeneratorPanel.java die alte setRule-Signatur nutzt – deshalb boolean-Convenience-Methode beibehalten)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mergegen/config/TraversalRuleStore.java
git commit -m "feat: TraversalRuleStore um SUBSELECT-Zustand erweitert"
```

---

## Chunk 2: TraversalService + MergeScriptGenerator

### Task 4: TraversalService – Decider-Erweiterung

**Files:**
- Modify: `src/main/java/com/mergegen/service/TraversalService.java`

- [ ] **Step 9: TraversalDecision-Enum im TraversalService**

Neues Enum (innerhalb TraversalService oder als eigene Datei):

```java
public enum TraversalDecision {
    TRAVERSE,
    SKIP,
    SUBSELECT
}
```

- [ ] **Step 10: Neues Decider-Interface**

```java
@FunctionalInterface
public interface TraversalDecider {
    TraversalDecision decide(String sourceTable, String targetTable,
                             String fkColumn, int rowCount);
}
```

Altes boolean-Interface entfernen. `shouldTraverseRelation()` anpassen:
- Gibt jetzt `TraversalDecision` zurueck statt `boolean`
- Bei `TraversalRule.SUBSELECT` im RuleStore -> `SUBSELECT` zurueckgeben
- Bei `SUBSELECT`: Tabelle wird NICHT traversiert, aber die FK-Relation wird trotzdem in `fkRelations` aufgenommen (fuer die spaetere Subselect-Generierung)

- [ ] **Step 11: Subselect-Daten im TraversalResult verfuegbar machen**

In `doTraverse()` bei den Stellen wo `shouldTraverseRelation()` aufgerufen wird:
- Bei `SKIP`: wie bisher (continue)
- Bei `SUBSELECT`: continue (nicht traversieren), aber die **Referenz-Zeile** (Parent-Row) laden und in einer neuen Map `subselectRows` speichern: `Map<String, TableRow>` mit Key `TABLE#PK_VALUE`
- `TraversalResult` um Feld `subselectRows` erweitern

- [ ] **Step 12: Tests ausfuehren**

Run: `./gradlew test 2>&1 | tail -5`
Expected: Alle Tests PASS. Ggf. `TraversalServiceTest` anpassen falls der `toSqlLiteral`-Test betroffen ist (unwahrscheinlich, da statische Methode).

- [ ] **Step 13: Commit**

```bash
git add src/main/java/com/mergegen/service/TraversalService.java \
        src/main/java/com/mergegen/model/TraversalResult.java
git commit -m "feat: TraversalDecision-Enum mit SUBSELECT-Option"
```

---

### Task 5: MergeScriptGenerator – Subselect-Ersetzung

**Files:**
- Modify: `src/main/java/com/mergegen/generator/MergeScriptGenerator.java`
- Create: `src/test/java/com/mergegen/generator/SubselectGeneratorTest.java`

- [ ] **Step 14: Tests fuer Subselect-Generierung anlegen**

```java
package com.mergegen.generator;

import com.mergegen.model.ColumnInfo;
import com.mergegen.model.TableRow;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SubselectGeneratorTest {

    private MergeScriptGenerator gen = new MergeScriptGenerator();

    private TableRow buildEmployeeRow() {
        TableRow row = new TableRow("HR", "EMPLOYEES");
        row.addValue(new ColumnInfo("EMPLOYEE_ID", "NUMBER", false, true), "100");
        row.addValue(new ColumnInfo("LAST_NAME", "VARCHAR2", false, false), "'King'");
        row.addValue(new ColumnInfo("DEPARTMENT_ID", "NUMBER", true, false), "90");
        return row;
    }

    @Test
    void subselectErsetztFkWert() {
        TableRow row = buildEmployeeRow();
        // subselectMap: FK-Spalte -> Subselect-Ausdruck
        Map<String, String> subselectMap = Map.of(
            "DEPARTMENT_ID",
            "(SELECT DEPARTMENT_ID FROM DEPARTMENTS WHERE DEPARTMENT_NAME = 'Executive')"
        );
        String sql = gen.generate(row, null, "EMPLOYEES", null, null, subselectMap, false);
        assertTrue(sql.contains(
            "(SELECT DEPARTMENT_ID FROM DEPARTMENTS WHERE DEPARTMENT_NAME = 'Executive') AS DEPARTMENT_ID"),
            "Subselect muss im USING-SELECT stehen");
        assertFalse(sql.contains("90 AS DEPARTMENT_ID"),
            "Originalwert 90 darf nicht mehr vorkommen");
    }

    @Test
    void subselectPrioritaet_colVarVorSubselect() {
        TableRow row = buildEmployeeRow();
        // colVar hat Prioritaet ueber Subselect
        Map<String, String> colVarSubs = Map.of("DEPARTMENT_ID", "v_DEPT_1");
        Map<String, String> subselectMap = Map.of(
            "DEPARTMENT_ID",
            "(SELECT DEPARTMENT_ID FROM DEPARTMENTS WHERE DEPARTMENT_NAME = 'Executive')"
        );
        String sql = gen.generate(row, null, "EMPLOYEES", null, null, colVarSubs, false);
        assertTrue(sql.contains("v_DEPT_1 AS DEPARTMENT_ID"));
    }

    @Test
    void ohneSubselect_originalwertBleibt() {
        TableRow row = buildEmployeeRow();
        String sql = gen.generate(row, null, "EMPLOYEES", null, null, null, false);
        assertTrue(sql.contains("90 AS DEPARTMENT_ID"));
    }
}
```

- [ ] **Step 15: Tests ausfuehren – muessen fehlschlagen**

Run: `./gradlew test --tests "*SubselectGeneratorTest*" 2>&1 | tail -5`
Expected: FAIL (Subselect-Logik fehlt noch)

Hinweis: Der Test nutzt die bestehende `colVarSubstitutions`-Map fuer Subselects, da die Prioritaet `colVar > sequence > subselect > literal` ist. Das colVarSubstitutions-Konzept wird fuer Subselects wiederverwendet – die Subselect-Ausdruecke werden einfach als Werte in diese Map eingesetzt (kein neuer Parameter noetig).

- [ ] **Step 16: MergeScriptGenerator anpassen**

Die `colVarSubstitutions`-Map akzeptiert bereits beliebige Strings als Ersetzung. Ein Subselect-Ausdruck wie `(SELECT ...)` funktioniert dort genauso wie eine PL/SQL-Variable. **Kein neuer Parameter noetig** – die Subselect-Ausdruecke werden vor dem Aufruf von `generate()` in die `colVarSubstitutions` eingesetzt.

Die Prioritaetskette wird damit:
1. `colVarSubstitutions` (PL/SQL-Variable ODER Subselect)
2. `sequenceMap` (NEXTVAL)
3. SQL-Literal (Originalwert)

Falls die Tests trotzdem fehlschlagen: pruefen ob `generate()` die Map korrekt durchreicht.

- [ ] **Step 17: Tests ausfuehren – muessen bestehen**

Run: `./gradlew test --tests "*SubselectGeneratorTest*" -i 2>&1 | tail -10`
Expected: 3 Tests PASS

- [ ] **Step 18: Commit**

```bash
git add src/test/java/com/mergegen/generator/SubselectGeneratorTest.java
git commit -m "test: Subselect-Generierung in MergeScriptGenerator verifiziert"
```

---

### Task 6: ScriptWriter – Subselect-Map aufbauen und durchreichen

**Files:**
- Modify: `src/main/java/com/mergegen/generator/ScriptWriter.java`

- [ ] **Step 19: buildColVarSubstitutions() erweitern**

Neuer Parameter `SubselectMappingStore subselectStore` und `Map<String, TableRow> subselectRows` in `write()` und `writePerObject()`.

In `buildColVarSubstitutions()`: nach der bestehenden FK-Logik (Sequence-Variable) einen zusaetzlichen Block:

```java
// Subselect-Ersetzung: FK-Spalte zeigt auf Tabelle mit Subselect-Mapping
if (subselectStore != null && subselectStore.hasMapping(parentTable)) {
    // Referenz-Zeile aus subselectRows laden
    TableRow refRow = subselectRows.get(parentTable + "#" + colVal);
    if (refRow != null) {
        String subselect = subselectStore.buildSubselect(parentTable, refRow.getValues());
        if (subselect != null) subs.put(colName, subselect);
    }
}
```

- [ ] **Step 20: write() und writePerObject() Signatur erweitern**

Neue Parameter am Ende:
```java
SubselectMappingStore subselectStore,
Map<String, TableRow> subselectRows
```

An allen Aufrufstellen (GeneratorPanel) `null, null` oder die echten Werte uebergeben.

- [ ] **Step 21: Tests ausfuehren**

Run: `./gradlew test 2>&1 | tail -5`
Expected: Alle Tests PASS (bestehende ScriptWriter-Tests uebergeben null fuer die neuen Parameter)

- [ ] **Step 22: Commit**

```bash
git add src/main/java/com/mergegen/generator/ScriptWriter.java
git commit -m "feat: ScriptWriter baut Subselect-Ausdruecke in colVarSubstitutions ein"
```

---

## Chunk 3: GUI + Integration

### Task 7: GUI – Traversal-Dialog mit 3 Optionen

**Files:**
- Modify: `src/main/java/com/mergegen/gui/GeneratorPanel.java`

- [ ] **Step 23: askTraversalDecision() auf 3 Optionen umbauen**

Statt `JOptionPane.showConfirmDialog` (Ja/Nein) einen `JOptionPane.showOptionDialog` mit 3 Buttons:

```java
String[] options = {"Traversieren", "Ueberspringen", "Subselect"};
int choice = JOptionPane.showOptionDialog(
    GeneratorPanel.this, message,
    "Traversal-Entscheidung",
    JOptionPane.DEFAULT_OPTION,
    JOptionPane.QUESTION_MESSAGE,
    null, options, options[0]);
```

Rueckgabewert: `TraversalDecision.TRAVERSE / SKIP / SUBSELECT`

- [ ] **Step 24: Bei SUBSELECT – Spaltenauswahl-Dialog**

Wenn SUBSELECT gewaehlt: zweiten Dialog anzeigen der die Spalten der Zieltabelle auflistet (per `SchemaAnalyzer.getColumns()`). JList mit Mehrfachauswahl fuer die Lookup-Spalten.

Dafuer muss `askTraversalDecision` Zugriff auf den `SchemaAnalyzer` haben (ueber den SwingWorker-Kontext oder als Feld).

Die ausgewaehlten Spalten + PK-Spalte werden in den `SubselectMappingStore` gespeichert.

- [ ] **Step 25: SubselectMappingStore in GeneratorPanel einbinden**

- Store als Feld in `GeneratorPanel` anlegen (analog zu `VirtualFkStore`, `ConstantTableStore`)
- Im `LauncherApp` erstellen und an `GeneratorPanel` uebergeben
- An `ScriptWriter.write()` und `writePerObject()` durchreichen

- [ ] **Step 26: TraversalResult.subselectRows an ScriptWriter uebergeben**

In `startGeneration()` die `subselectRows` aus dem `TraversalResult` an die `write()`/`writePerObject()`-Aufrufe uebergeben.

- [ ] **Step 27: Manuell testen**

1. App starten: `./gradlew shadowJar && java -jar build/libs/MigrationTool.jar`
2. Verbindung zu Oracle XE herstellen
3. EMPLOYEES mit EMPLOYEE_ID=100 analysieren
4. Bei DEPARTMENTS -> "Subselect" waehlen -> DEPARTMENT_NAME als Lookup-Spalte
5. Script generieren
6. Pruefen: `DEPARTMENT_ID`-Wert im MERGE sollte `(SELECT DEPARTMENT_ID FROM DEPARTMENTS WHERE DEPARTMENT_NAME = 'Executive')` sein

- [ ] **Step 28: Commit**

```bash
git add src/main/java/com/mergegen/gui/GeneratorPanel.java \
        src/main/java/com/migrationtool/launcher/LauncherApp.java
git commit -m "feat: GUI-Dialog mit Subselect-Option und Spaltenauswahl"
```

---

### Task 8: Integration-Test

**Files:**
- Create: `src/integrationTest/java/com/mergegen/SubselectIntegrationTest.java`

- [ ] **Step 29: Integration-Test anlegen**

Test-Szenario: EMPLOYEES traversieren, DEPARTMENTS als Subselect markieren (Lookup ueber DEPARTMENT_NAME). Script generieren und pruefen ob der Subselect korrekt im SQL steht.

```java
// Kernlogik des Tests:
// 1. SubselectMappingStore mit DEPARTMENTS -> DEPARTMENT_NAME befuellen
// 2. TraversalService mit Decider der bei DEPARTMENTS -> SUBSELECT zurueckgibt
// 3. Script generieren
// 4. Pruefen: "(SELECT DEPARTMENT_ID FROM DEPARTMENTS WHERE DEPARTMENT_NAME = 'Executive')"
//    muss im Script vorkommen statt des Literal-Werts "90"
```

- [ ] **Step 30: Integration-Test ausfuehren**

Run: `./gradlew integrationTest --tests "*SubselectIntegrationTest*" -i 2>&1 | tail -15`
Expected: PASS

- [ ] **Step 31: Commit**

```bash
git add src/integrationTest/java/com/mergegen/SubselectIntegrationTest.java
git commit -m "test: Integration-Test fuer Subselect-FK-Mapping gegen Oracle XE"
```

---

### Task 9: CLAUDE.md + Persistenz-Doku aktualisieren

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 32: Doku aktualisieren**

Folgende Abschnitte in CLAUDE.md ergaenzen:
- Unter "Persistenz": `subselect-mappings.txt` mit Format `TABLE|PK_COL|LOOKUP_COL1;LOOKUP_COL2`
- Unter "Traversal": dritte Option SUBSELECT dokumentieren
- Unter "MERGE-Generierung": Subselect-Ersetzung in Prioritaetskette (colVar > seq > subselect > literal)
- Config-Dateibaum um `subselect-mappings.txt` ergaenzen
- Unit-Test-Zaehler aktualisieren

- [ ] **Step 33: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: Subselect-FK-Mapping in CLAUDE.md dokumentiert"
```
