# MigrationTool

Einzelnes Gradle-Projekt, das **MergeGen** (Oracle MERGE-Script-Generator) und **ExcelSplit** (Excel-zu-CSV-Konverter) in einem gemeinsamen Release mit geteilter JRE zusammenfasst. Single-Frame-Anwendung mit Seitenleiste – alle Tools werden im selben Fenster angezeigt.

## Projektstruktur

```
MigrationTool/
├── build.gradle                 – Gradle-Build (Shadow-JAR + jlink + JUnit 5)
├── settings.gradle              – rootProject.name = 'MigrationTool'
├── gradlew / gradlew.bat
├── lib/
│   └── ojdbc11.jar              – Oracle JDBC (lokal, nicht in Maven Central)
├── master/                      – Eingabe: .xlsx-Masterdateien (ExcelSplit)
└── src/
    ├── main/java/
    │   ├── com/mergegen/
    │   │   ├── gui/             (GeneratorPanel, SettingsPanel, VirtualFkPanel, SequenceMappingPanel, MainFrame, GuiApp)
    │   │   ├── analyzer/        (SchemaAnalyzer)
    │   │   ├── service/         (TraversalService)
    │   │   ├── generator/       (MergeScriptGenerator, ScriptWriter)
    │   │   ├── config/          (AppSettings, ConnectionProfileManager, VirtualFkStore, SequenceMappingStore, ConstantTableStore, QueryPresetStore, TableHistoryStore, DatabaseConfig)
    │   │   ├── db/              (DatabaseConnection)
    │   │   └── model/           (ColumnInfo, TableRow, DependencyNode, ForeignKeyRelation, SequenceMapping, TraversalResult, QueryPreset, TableHistoryEntry)
    │   ├── com/excelsplit/      (ExcelSplit, AppConfig, ExcelSplitService, MainPresenter, MainWindow)
    │   └── com/migrationtool/launcher/
    │       └── LauncherApp.java
    └── test/java/com/mergegen/  – 50 JUnit-5-Tests (keine DB nötig)
```

## Build-Kommandos

```bash
./gradlew test
# → 50 MergeGen-Tests; ExcelSplit hat keine Tests

./gradlew shadowJar
# → build/libs/MigrationTool.jar (Fat-JAR, ~25 MB)

./gradlew release -PjdkPath="C:/Users/maddi/.jdks/openjdk-25.0.2"
# → build/release/MigrationTool/
#     MigrationTool.jar   – Fat-JAR
#     runtime/            – minimale jlink-JRE
#     MigrationTool.bat   – Windows-Starter (kein JRE nötig)
```

## Launcher (LauncherApp)

- Main-Class: `com.migrationtool.launcher.LauncherApp`
- Single-Frame mit `BorderLayout`: Seitenleiste (WEST, 155 px) + Content-Bereich (CENTER, `CardLayout`)
- Navigationsbaum (JTree) mit fester Struktur:
  - `Alles ausführen` → `WorkflowPanel` (immer ganz oben, nicht verschiebbar)
  - Werkzeug-Kategorien (z.B. `Exceltools`, `Mergescripte`) → **per Drag & Drop umsortierbar**
  - `Einstellungen` → `SettingsPanel` (immer ganz unten, nicht verschiebbar)
- Cards: `workflow` (WorkflowPanel), `mergegen` (JTabbedPane), `excelsplit`, `settings`
- Globale DB-Einstellungen: `SettingsPanel`-Instanz einmalig erstellt, als Card und als Parameter an `GeneratorPanel` übergeben
- `MainFrame` wird im Launcher nicht verwendet – Panels direkt eingebettet

### WorkflowPanel
- Klasse: `com.migrationtool.launcher.WorkflowPanel`
- Zeigt alle Werkzeug-Schritte der Reihe nach mit Status (○ Bereit / ◎ Läuft / ✓ OK / ✗ Fehler)
- „Alle ausführen"-Button startet alle Schritte sequenziell; bei Fehler Abbruch
- Jeder Schritt hat eigenen „Ausführen"-Button für Einzelausführung
- `addStep(Step)` / `moveStep(int from, int to)` – bei DnD-Umsortierung synchron gehalten
- MergeGen-Schritt im Auto-Modus: nutzt letzte AppSettings (Tabelle/Spalte/Werte), Sequences aus SequenceMappingStore (kein Dialog)

### Drag & Drop (Navigationsbaum)
- Nur Kategorieknoten (zwischen den fixen Knoten) sind verschiebbar
- `TransferHandler` mit `DropMode.INSERT`: beim Drop werden Baummodell, `moveableNodes`-Liste und WorkflowPanel synchron aktualisiert
- Reihenfolge wird sofort in `launcher.properties` gespeichert und beim nächsten Start wiederhergestellt

---

## MergeGen – Designentscheidungen

### Traversal
- `traverse(table, column, value)` – wenn `column` leer/null, wird der erste PK auto-ermittelt
- Nach dem Laden der Root-Row wird stets der echte PK-Wert für den BFS-Traversal verwendet
- `TraversalService(SchemaAnalyzer, VirtualFkStore)` – beide Pflicht; `VirtualFkStore` kann null sein
- **Composite PKs**: `visited`-Key aus allen PK-Spalten zusammengesetzt (`buildVisitedKey()`)

### Virtuelle FKs (`VirtualFkStore`)
- Datei: `virtual-fks.txt`, Format: `CHILD|FK_COL|PARENT|PARENT_PK`
- BFS: echte DB-FKs + virtuelle FKs kombiniert
- **Auto-Bereinigung**: wenn virtueller FK inzwischen als echter Constraint in DB existiert (match auf childTable + fkColumn), wird er beim Traversal automatisch entfernt
- Shared Instance: in `LauncherApp` erstellt, an `GeneratorPanel` und `VirtualFkPanel` weitergereicht

### Konstantentabellen (`ConstantTableStore`)
- Datei: `constant-tables.txt`, Format: ein Tabellenname pro Zeile (uppercase)
- Tabellen mit fixen PKs (Stammdaten), die in der Zieldatenbank bereits existieren
- UI: Checkbox-Panel im CARD_TREE unterhalb des Abhängigkeitsbaums
- Beim Generieren: Zeilen der markierten Tabellen aus `orderedRows` gefiltert

### Analyse-Verlauf (`TableHistoryStore`)
- Datei: `table-history.txt`, Format: `TABLE|COLUMN|VAL1;VAL2|CONST1;CONST2|TIMESTAMP`
- UI: JList-Seitenleiste links neben Eingabeformular (JSplitPane in CARD_INPUT, 200 px)
- Duplikat-Prüfung: gleiche Tabelle + Spalte + Werte (caseignore, reihenfolgeunabhängig) → kein neuer Eintrag, Timestamp aktualisieren + Move-to-Front
- Speichern: beim Analysieren (Tabelle/Spalte/Werte), ergänzt beim Generieren (Konstantentabellen)

### Persistenz
- `app.properties`: output.dir, last.table, last.column
- Script-Ausgabe: `<outputDir>/<TABELLENNAME>/MERGE_<TABELLENNAME>.sql` – wird bei jeder Generierung überschrieben
- `connections/<name>.properties`: JDBC-Verbindungsprofile (url, user, password, schema)
- Alle Dateien im Arbeitsverzeichnis, kein Registry-Eintrag

### PL/SQL-Block bei Sequence-Mappings
- Wenn mind. eine Sequence konfiguriert: `ScriptWriter` erzeugt Oracle PL/SQL-Block (`DECLARE … BEGIN … END;`)
- Pro Sequence-PK: Variable `v_<PKCOL>_<N> NUMBER/VARCHAR2`; vor jedem MERGE: `SELECT SEQ.NEXTVAL INTO v_VAR FROM DUAL;`
- FK-Spalten in Child-Tabellen, die auf sequence-gemappten Parent-PK zeigen, erhalten ebenfalls die Variable
- Ohne Sequences + INSERT-only mit Children: PL/SQL-Block für Skip-Check (s.u.)

### Sequence-Mappings (`SequenceMappingStore`)
- Datei: `sequence-mappings.txt`, Format: `TABLE|PK_COL|SEQ_NAME`
- Beim Generieren: dreistufige Vorschlags-Logik: 1. Store-Eintrag → 2. Trigger-Erkennung → 3. leeres Feld
- Leere Eingabe = PK-Wert aus Quelle 1:1 übernehmen

### Testmodus
- Checkbox im Eingabe-Formular; Timestamp-Suffix (`_yyyyMMddHHmmss`) an SQL-Literal der Suchspalte
- MERGE matcht nie → immer INSERT → jeder Testlauf legt neues Objekt an
- Voraussetzung: Spaltenname muss ausgefüllt sein

### MERGE-Generierung & PK-Werte
- PK-Spalten mit Sequence: `SEQ.NEXTVAL` im USING-SELECT statt Quell-PK-Wert
- **Optional UPDATE**: Checkbox „Bei Übereinstimmung aktualisieren" → `WHEN MATCHED THEN UPDATE SET` für alle Nicht-PK-Spalten (Sequence/ColVar-Spalten ausgenommen)
- **Skip-Check bei INSERT-only**: wenn kein UPDATE + Child-Tabellen vorhanden → PL/SQL-Block mit `SQL%ROWCOUNT`-Prüfung nach Root-MERGEs; wenn kein Root-Datensatz eingefügt → `RETURN`

### Query-Presets (`QueryPresetStore`)
- Datei: `query-presets.txt`, Format: `NAME|TABLE|COLUMN|VALUE1;VALUE2|CONST1;CONST2`
- UI: Preset-Leiste (NORTH in CARD_INPUT) mit Dropdown + Löschen; „Als Preset speichern"-Button in CARD_TREE

### Eingabe-GUI (GeneratorPanel)
- Drei Felder: Führende Tabelle | Spaltenname (optional, leer = PK auto) | Wert
- Letzte Tabelle + Spalte werden in `app.properties` gespeichert und beim nächsten Start vorausgefüllt

### Unit-Tests (50 Tests, keine DB nötig)
- **MergeScriptGeneratorTest** (12): MERGE-SQL-Struktur, Sequence-Ersetzung, Prioritätskette (ColVar > Seq > Literal), Testmodus-Suffix, ON-Klausel, UPDATE-Block
- **ScriptWriterTest** (16): `buildVarName` (30-Zeichen-Limit), `buildColVarSubstitutions`, Plain vs. PL/SQL-Mode, Typ-Erkennung, 4-Ebenen-FK-Kette, 2-Root-Rows, Skip-Check
- **TraversalServiceTest** (12): `toSqlLiteral()` – Zahlen, Strings, Escaping, Null/Blank
- **TableHistoryStoreTest** (10): Duplikat-Erkennung, Move-to-Front, Timestamp-Update, Persistenz-Roundtrip
- Refactoring für Testbarkeit: `ScriptWriter.buildVarName()` + `buildColVarSubstitutions()` sind package-private; `TableHistoryStore` hat zweiten Konstruktor mit `baseDir`-Parameter

---

## ExcelSplit – Designentscheidungen

### Excel-Verarbeitungsregeln
- **Sheet 2, Zeile 1, Spalte A**: Template-Name → CSV-Dateiname
- **Sheet 2, Zeile 1, Spalte B**: Template-Wert
- **Sheet 2, Zeilen 2+**: werden ignoriert
- **Sheet 1, Spalte C**: Template-Name in jede Datenzeile eintragen (Zeile 1 = Header, bleibt unverändert)
- **Sheet 1, Spalte E, Zeile 2**: Wert aus Sheet 2, Spalte B
- **Trennzeichen**: Semikolon (`;`)
- CSV-Escape: Anführungszeichen für Werte mit `;`, `"`, Leerzeichen oder Zeilenumbrüchen
- Nach Verarbeitung: `validierung.log` im Ausgabeverzeichnis

### Architektur (MVP)
- `ExcelSplit` – Entry Point + `openWindow(Path)` für Standalone-Betrieb + `detectBasePath()`
- `AppConfig` – Einstellungen in `excel-split.properties` (masterDir, outputDir)
- `ExcelSplitService` – Business-Logik: Excel lesen (Apache POI), CSV schreiben
- `MainPresenter` – Koordination (SwingWorker für Hintergrundverarbeitung)
- `MainWindow` – Swing-GUI; `getContentPanel()` für Einbettung im Launcher

### Basispfad-Erkennung
- Priorität: 1. explizites Argument → 2. JAR-Pfad aufwärts (max. 6 Ebenen) nach `master/` → 3. Arbeitsverzeichnis
- Im Launcher: `LauncherApp.detectLauncherBasePath()` (gleiche Logik)

---

## Laufzeit-Konfigurationsdateien

Alle im Arbeitsverzeichnis (neben `.jar` / `.bat`), kein Registry-Zugriff:

| Datei | Tool | Inhalt |
|-------|------|--------|
| `app.properties` | MergeGen | output.dir, last.table, last.column |
| `connections/*.properties` | MergeGen | JDBC-Profile (url, user, password, schema) |
| `virtual-fks.txt` | MergeGen | Manuelle FK-Definitionen |
| `sequence-mappings.txt` | MergeGen | Sequence-Zuordnungen |
| `constant-tables.txt` | MergeGen | Stammdaten-Tabellen (fixe PKs) |
| `query-presets.txt` | MergeGen | Gespeicherte Abfragen |
| `table-history.txt` | MergeGen | Analyse-Verlauf |
| `excel-split.properties` | ExcelSplit | masterDir, outputDir |
| `launcher.properties`    | Launcher   | nav.order (Kategorie-Reihenfolge im Baum) |

## Nicht in Git
- `build/`, `.gradle/`
- Alle oben genannten Laufzeit-Konfigurationsdateien
