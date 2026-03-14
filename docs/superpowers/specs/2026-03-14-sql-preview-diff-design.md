# SQL-Vorschau mit Syntax-Highlighting & Diff-Modus

## Problem

1. Nach der Script-Generierung sieht der User nur eine Zusammenfassung (Dateiname, Anzahl). Um das SQL zu pruefen, muss er die Datei extern oeffnen.
2. Scripts werden bei jeder Generierung ueberschrieben (`MERGE_<TABELLE>.sql`). Aenderungen zwischen Laeufen sind nicht nachvollziehbar.

## Loesung

### Feature 1: SQL-Vorschau mit Syntax-Highlighting

- **CARD_RESULT** erhaelt unterhalb der Zusammenfassung einen scrollbaren `RSyntaxTextArea`-Bereich
- SQL-Syntax-Highlighting (Keywords, Strings, Kommentare, Zeilennummern)
- Read-only, nicht editierbar
- Dependency: `com.fifesoft:rsyntaxtextarea` (ca. 300 KB)
- Nach Generierung: Script aus Datei lesen und in TextArea laden

### Feature 2: Timestamp-Dateinamen

- Neues Format: `MERGE_<TABELLE>_<yyyyMMdd_HHmmss>.sql`
- Alte Scripts bleiben erhalten (kein Ueberschreiben mehr)
- Aenderung in `ScriptWriter`

### Feature 3: Diff-Modus

- Nach Generierung: pruefen ob aelteres Script fuer dieselbe Root-Tabelle im Ausgabeverzeichnis existiert
- Falls ja: Button "Mit letzter Version vergleichen" im CARD_RESULT
- Diff-Dialog: separates Fenster mit Side-by-Side-Ansicht
  - Links: vorheriges Script (RSyntaxTextArea, read-only)
  - Rechts: neues Script (RSyntaxTextArea, read-only)
  - Zeilenweise farblich markiert (gruen = neu, rot = entfernt)
  - Einfacher zeilenweiser Diff-Algorithmus (keine externe Library)

## Betroffene Klassen

| Klasse | Aenderung |
|--------|-----------|
| `build.gradle` | RSyntaxTextArea-Dependency |
| `GeneratorPanel` | CARD_RESULT umbauen: RSyntaxTextArea + Diff-Button + Logik |
| `ScriptWriter` | Dateinamen mit Timestamp-Suffix |
| `DiffDialog` (NEU) | Side-by-Side Diff-Fenster mit zwei RSyntaxTextAreas |
| `LineDiff` (NEU) | Zeilenweiser Diff-Algorithmus (LCS-basiert) |

## Testplan

- **ScriptWriter**: Dateiname enthaelt Timestamp-Suffix, alte Dateien bleiben erhalten
- **LineDiff**: Identische Dateien, komplett verschieden, einzelne Zeilen eingefuegt/entfernt/geaendert
- **GeneratorPanel**: Diff-Button nur sichtbar wenn aelteres Script existiert
