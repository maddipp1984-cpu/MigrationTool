# MigrationTool

Einzelnes Gradle-Projekt, das **MergeGen** (Oracle MERGE-Script-Generator), **ExcelSplit** (Excel-zu-CSV-Konverter) und **INSERT Generator** (generischer INSERT-Script-Generator) in einem gemeinsamen Release mit geteilter JRE zusammenfasst. Single-Frame-Anwendung mit Seitenleiste – alle Tools werden im selben Fenster angezeigt.

## UX-Checkliste für neue Features
- Dialog-X / Escape = Abbruch (nicht stillschweigend weitermachen)
- Reset-/Neu-Button vorsehen
- Nur relevante Optionen im Dialog zeigen (vorher filtern)
- Keine doppelten Konzepte für dasselbe Problem
- Keine automatischen destruktiven/endgültigen Aktionen (User entscheidet)
- User-Entscheidungen bei Wiederholung persistieren (Presets, Config)
- Feature am User-Mental-Modell orientieren, nicht am Code-Modell
- Duplikat-Prüfung bei benannten Einträgen (Presets, Profile)
- Config-Dateien folgen dem Muster `config/<tool>/`
- Vor dem Bauen fragen: Löst das ein echtes Problem oder klingt es nur logisch?

## Package-Regeln

- Neue Klassen immer im fachlich passenden Package anlegen, **nicht** im Launcher-Package
- `com.migrationtool.launcher` enthält nur `LauncherApp` und `HelpDialog` – keine Tool-Klassen
- Jedes Tool bekommt ein eigenes Package (z.B. `com.kostenattribute`)
- Bei Unsicherheit: bestehendes Package-Layout als Orientierung nehmen

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
    │   │   ├── gui/             (GeneratorPanel, SettingsPanel, VirtualFkPanel, SequenceMappingPanel, ConstantTablePanel, DiffDialog, MainFrame, GuiApp)
    │   │   ├── analyzer/        (SchemaAnalyzer)
    │   │   ├── service/         (TraversalService)
    │   │   ├── generator/       (MergeScriptGenerator, ScriptWriter, LineDiff)
    │   │   ├── config/          (AppSettings, ConnectionProfileManager, VirtualFkStore, SequenceMappingStore, ConstantTableStore, QueryPresetStore, GlobalTraversalRuleStore, DatabaseConfig)
    │   │   ├── db/              (DatabaseConnection)
    │   │   └── model/           (ColumnInfo, TableRow, DependencyNode, ForeignKeyRelation, SequenceMapping, TraversalResult, QueryPreset)
    │   ├── com/excelsplit/      (ExcelSplit, AppConfig, ExcelSplitService, MainPresenter, MainWindow)
    │   ├── com/kostenattribute/ (InsertGenPanel, InsertGenService)
    │   └── com/migrationtool/launcher/  (LauncherApp, HelpDialog – NUR diese beiden)
    └── test/java/com/mergegen/  – 61 JUnit-5-Tests (keine DB nötig)
```

## Build-Kommandos

```bash
./gradlew test
# → 62 Unit-Tests (MergeGen + SubselectMapping + GlobalTraversalRuleStore); ExcelSplit hat keine Tests

./gradlew integrationTest
# → 18 Integration-Tests gegen Oracle XE (Docker)

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
  - Werkzeug-Kategorien: `Exceltools` → Excel Split, `Mergescripte` → MERGE Generator / INSERT Generator
  - `Einstellungen` → DB-Verbindung
- Cards: `mergegen` (JTabbedPane), `excelsplit`, `insertgen`, `settings`
- Globale DB-Einstellungen: `SettingsPanel`-Instanz einmalig erstellt, als Card und als Parameter an `GeneratorPanel` übergeben
- `MainFrame` wird im Launcher nicht verwendet – Panels direkt eingebettet
- **Look & Feel**: FlatLaf (com.formdev:flatlaf:3.5.4) – 4 Themes (Light, Dark, IntelliJ, Darcula)
- Theme-Umschalter: Menü „Ansicht" mit RadioButtons, Live-Wechsel via `FlatLaf.updateUI()`
- Theme-Persistenz: `config/launcher/theme.properties` (Key: `theme`)

---

## MergeGen – Designentscheidungen

### Traversal
- `traverse(table, column, value)` – wenn `column` leer/null, wird der erste PK auto-ermittelt
- Nach dem Laden der Root-Row wird stets der echte PK-Wert für den BFS-Traversal verwendet
- `TraversalService(SchemaAnalyzer, VirtualFkStore)` – beide Pflicht; `VirtualFkStore` kann null sein
- **Composite PKs**: `visited`-Key aus allen PK-Spalten zusammengesetzt (`buildVisitedKey()`)
- **Traversal-Entscheidung** (3 Optionen): `TraversalDecision.TRAVERSE` / `SKIP` / `SUBSELECT`
  - SUBSELECT: Tabelle wird nicht traversiert, aber FK-Werte im Script durch Subselect ersetzt
  - Referenz-Zeilen werden in `TraversalResult.subselectRows` gespeichert

### Virtuelle FKs (`VirtualFkStore`)
- Datei: `virtual-fks.txt`, Format: `CHILD|FK_COL|PARENT|PARENT_PK`
- BFS: echte DB-FKs + virtuelle FKs kombiniert
- **Auto-Bereinigung**: wenn virtueller FK inzwischen als echter Constraint in DB existiert (match auf childTable + fkColumn), wird er beim Traversal automatisch entfernt
- Shared Instance: in `LauncherApp` erstellt, an `GeneratorPanel` und `VirtualFkPanel` weitergereicht

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

### Konstantentabellen (`ConstantTableStore`)
- Datei: `constant-tables.txt`, Format: ein Tabellenname pro Zeile (uppercase)
- Global persistent – gelten für alle Analysen/Generierungen
- Eigener Tab im MergeGen-Bereich (Launcher) bzw. Standalone (MainFrame)
- In CARD_TREE: Info-Anzeige welche traversierten Tabellen als Konstantentabellen markiert sind
- Beim Generieren: Datensätze dieser Tabellen werden aus dem MERGE-Script gefiltert, FK-Werte bleiben als Literale

### Testmodus
- Checkbox im Eingabe-Formular; Timestamp-Suffix (`_yyyyMMddHHmmss`) an SQL-Literal der Suchspalte
- MERGE matcht nie → immer INSERT → jeder Testlauf legt neues Objekt an
- Voraussetzung: Spaltenname muss ausgefüllt sein

### Subselect-FK-Mapping (`SubselectMappingStore`)
- Datei: `subselect-mappings.txt`, Format: `TABLE|PK_COL|LOOKUP_COL1;LOOKUP_COL2`
- Ersetzt FK-Werte im MERGE-Script durch `(SELECT pk FROM tabelle WHERE key = wert)`
- Für Referenzdaten die auf der Ziel-DB bereits existieren aber andere IDs haben
- Prioritätskette im USING-SELECT: ColVar > Sequence > **Subselect** > Literal
- GUI: dritter Button "Subselect" im Traversal-Dialog + Spaltenauswahl-Dialog

### MERGE-Generierung & PK-Werte
- PK-Spalten mit Sequence: `SEQ.NEXTVAL` im USING-SELECT statt Quell-PK-Wert
- **Optional UPDATE**: Checkbox „Bei Übereinstimmung aktualisieren" → `WHEN MATCHED THEN UPDATE SET` für alle Nicht-PK-Spalten (Sequence/ColVar-Spalten ausgenommen)
- **Skip-Check bei INSERT-only**: wenn kein UPDATE + Child-Tabellen vorhanden → PL/SQL-Block mit `SQL%ROWCOUNT`-Prüfung nach Root-MERGEs; wenn kein Root-Datensatz eingefügt → `RETURN`
- **Per-Object-Generierung**: bei mehreren Objekten (Werten) erzeugt `ScriptWriter.writePerObject()` separate PL/SQL-Blöcke pro Objekt mit jeweils frischen DECLARE-Variablen; Konstantentabellen-Filter wird pro Objekt angewendet

### SQL-Vorschau & Diff-Modus
- **CARD_RESULT**: Zusammenfassung oben + RSyntaxTextArea (SQL-Highlighting) unten
- Dependency: `com.fifesoft:rsyntaxtextarea:3.5.3`
- **Timestamp-Dateinamen**: `MERGE_<TABELLE>_<yyyyMMdd_HHmmss>.sql` – alte Scripts bleiben erhalten
- **Diff-Button**: erscheint wenn älteres Script im selben Verzeichnis existiert
- **DiffDialog**: Side-by-Side mit synchronem Scrollen, Zeilenweise Highlights (grün/rot), Statistik
- **LineDiff**: LCS-basierter zeilenweiser Vergleich (keine externe Library)

### Globale Traversal-Regeln (`GlobalTraversalRuleStore`)
- Datei: `traversal-rules.txt`, Format: `ROOT_TABLE|PARENT>CHILD.FK=JA;PARENT>CHILD.FK=NEIN;...`
- Beim Start der Analyse: wenn Regeln für die Root-Tabelle existieren, Dialog „Übernehmen / Neu eingeben / Abbrechen"
- Nach erfolgreicher Analyse: aktuelle Regeln automatisch gespeichert/aktualisiert
- Greift nur wenn kein Preset aktiv ist (Presets bringen eigene Regeln mit)

### Sequence-Skip
- Wenn `SequenceMappingStore` bereits einen Eintrag für Tabelle+PK hat: Dialog überspringen, gespeicherten Wert direkt verwenden
- Nur für neue/unbekannte Tabellen wird der Sequence-Dialog angezeigt

### Query-Presets (`QueryPresetStore`)
- Datei: `query-presets.txt`, Format: `NAME|TABLE|COLUMN|VALUE1;VALUE2|CONST1;CONST2`
- UI: Preset-Leiste (NORTH in CARD_INPUT) mit Dropdown + Löschen; „Als Preset speichern"-Button in CARD_TREE

### Eingabe-GUI (GeneratorPanel)
- Drei Felder: Führende Tabelle | Spaltenname (optional, leer = PK auto) | Wert
- Letzte Tabelle + Spalte werden in `app.properties` gespeichert und beim nächsten Start vorausgefüllt

### Unit-Tests (69 Tests, keine DB nötig)
- **MergeScriptGeneratorTest** (12): MERGE-SQL-Struktur, Sequence-Ersetzung, Prioritätskette (ColVar > Seq > Literal), Testmodus-Suffix, ON-Klausel, UPDATE-Block
- **SubselectGeneratorTest** (3): Subselect-Ersetzung im USING-SELECT, Priorität ColVar > Subselect, Original-Literal ohne Subselect
- **ScriptWriterTest** (16): `buildVarName` (30-Zeichen-Limit), `buildColVarSubstitutions`, Plain vs. PL/SQL-Mode, Typ-Erkennung, 4-Ebenen-FK-Kette, 2-Root-Rows, Skip-Check
- **TraversalServiceTest** (12): `toSqlLiteral()` – Zahlen, Strings, Escaping, Null/Blank
- **SubselectMappingStoreTest** (11): Add/Get, Composite-Lookup, Case-Insensitiv, Remove, Persistenz-Roundtrip, buildSubselect (single/composite/null/missing/explicitNull)
- **GlobalTraversalRuleStoreTest** (8): Save/Get, Case-Insensitiv, Update, Multiple Tables, Persistenz-Roundtrip, Empty-Remove, Subselect
- **LineDiffTest** (7): Identisch, komplett verschieden, Zeile eingefügt/entfernt, leere Inputs
- Refactoring für Testbarkeit: `ScriptWriter.buildVarName()` + `buildColVarSubstitutions()` sind package-private

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

## INSERT Generator – Designentscheidungen

### Überblick
- Generischer INSERT-Script-Generator für beliebige Oracle-Zieltabellen
- Package: `com.kostenattribute` (historisch, Klassen: `InsertGenPanel`, `InsertGenService`)
- Preset-basiert: pro Zieltabelle ein Preset mit Spaltenstruktur + Daten

### Preset-System
- Jedes Preset als eigene CSV-Datei unter `config/insertgen/<name>.csv`
- CSV-Format: Metadaten als `#`-Kommentare, dann Header + Datenzeilen
- Metadaten: `#TABLE=`, `#PK=`, `#SEQUENCE=`, `#FK=SPALTE|subselect`
- UI: ComboBox + Speichern/Löschen-Buttons (analog MergeGen QueryPresets)

### PK + Sequence (optional)
- Dropdown für PK-Spalte (befüllt aus aktuellen Spalten)
- Textfeld für Sequence-Name
- Header-Markierung: gold + fett
- Script: `SEQ.NEXTVAL` statt Literalwert, WHERE NOT EXISTS ohne PK-Spalte

### FK-Subselects (optional)
- Rechtsklick auf Spaltenheader → "FK mit Subselect"
- Platzhalter `{WERT}` wird pro Zeile durch den Zellwert ersetzt
- Auto-Typ-Erkennung: Zahlen ohne Quotes, Strings mit Quotes
- Klammern werden automatisch ergänzt falls nicht vorhanden
- Header-Markierung: hellblau + kursiv

### Script-Generierung
- `DELETE FROM <tabelle>` + `INSERT INTO ... SELECT ... FROM DUAL WHERE NOT EXISTS (...)`
- WHERE NOT EXISTS prüft alle Nicht-PK/Nicht-Sequence-Spalten
- FK-Spalten: Subselect statt Literalwert

### Clipboard-Paste (Ctrl+V)
- Leere Tabelle: fragt ob erste Zeile Spaltenüberschrift ist, legt Spalten + Daten an
- Bestehende Tabelle: fügt ab Cursor-Position ein
- `KeyboardFocusManager` statt InputMap (CardLayout-kompatibel)

---

## Laufzeit-Konfigurationsdateien

Abgelegt unter `config/<tool>/` im Arbeitsverzeichnis, kein Registry-Zugriff. Verzeichnisse werden beim ersten Schreiben automatisch angelegt.

```
config/
├── mergegen/
│   ├── app.properties           – output.dir, last.table, last.column
│   ├── connections/             – JDBC-Profile (url, user, password, schema)
│   ├── virtual-fks.txt          – Manuelle FK-Definitionen
│   ├── sequence-mappings.txt    – Sequence-Zuordnungen
│   ├── constant-tables.txt      – Konstantentabellen (kein MERGE)
│   ├── subselect-mappings.txt   – Subselect-FK-Mappings (TABLE|PK|LOOKUP_COLS)
│   ├── query-presets.txt        – Gespeicherte Abfragen
│   └── traversal-rules.txt     – Globale Traversal-Regeln pro Root-Tabelle
├── excelsplit/
│   └── excel-split.properties   – masterDir, outputDir
├── insertgen/
│   └── <preset>.csv             – Pro Preset: #TABLE=<name>, Header, Datenzeilen
└── launcher/
    └── theme.properties         – Gewähltes FlatLaf-Theme (FlatLight/FlatDark/FlatIntelliJ/FlatDarcula)
```

## Nicht in Git
- `build/`, `.gradle/`
- `config/` (gesamter Ordner)

---

## Testumgebung (Oracle XE mit Docker)
- Container: `oracle-xe`, Port 1521, User hr/hr, Schema HR, SID XE
- Verbindungsprofil: `config/mergegen/connections/HR-local.properties`
- Testdaten: EMPLOYEES (7), JOB_HISTORY (5), EMPLOYEE_SKILLS (3), SKILL_ENDORSEMENTS (4), EMPLOYEE_CONTRACTS (2), CONTRACT_ITEMS (4)
- Trigger auf EMPLOYEES und DEPARTMENTS (für Sequence-Edge-Case), EMPLOYEE_SKILLS (mit EMPLOYEE_SKILLS_SEQ)
- King (employee_id=100) hat Abhängigkeiten in allen Kind-Tabellen → guter Testdatensatz
- MANAGER_ID ist bei allen Employees NULL (Self-Ref-FK bewusst deaktiviert für Tests)

## Originalprojekte (unberührt)
- `C:\projekte\MergeGen\` – MergeGen Standalone (Gradle)
- `C:\projekte\mig_template\` – ExcelSplit Standalone (Maven)

## Entwicklungsregeln
- **Config-Dateien verschieben, nicht löschen**: Beim Refactoring von Dateipfaden IMMER erst Zielordner anlegen, dann Dateien verschieben (`mv`). Config-Dateien sind nicht in Git → bei Löschung unwiederbringlich verloren.
