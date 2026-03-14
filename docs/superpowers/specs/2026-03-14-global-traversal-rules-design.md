# Global Traversal Rules & Sequence-Skip

## Problem

Beim Anlegen eines neuen Presets fuer dieselbe Tabelle muss der User den kompletten Traversal-Dialog erneut durchklicken, obwohl die Entscheidungen (Traverse/Skip/Subselect) fuer dieselbe Tabellenstruktur identisch sind. Ebenso werden Sequence-Mappings erneut abgefragt, obwohl sie sich nicht aendern.

## Loesung

### 1. Neuer GlobalTraversalRuleStore

- **Datei:** `config/mergegen/traversal-rules.txt`
- **Format:** Eine Zeile pro Root-Tabelle:
  ```
  ROOT_TABLE|PARENT>CHILD.FK=JA;PARENT>CHILD.FK=NEIN;PARENT>CHILD.FK=SUBSELECT
  ```
- **Persistenz:** Nach jeder erfolgreichen Analyse werden die Traversal-Entscheidungen automatisch fuer die Root-Tabelle gespeichert/aktualisiert
- **Laden:** Beim Start aus Datei gelesen (analog zu anderen Stores)

### 2. Ablauf bei "Weiter" (GeneratorPanel)

Wenn der User "Weiter" klickt und die Analyse startet:

1. Pruefen: Hat `GlobalTraversalRuleStore` Eintraege fuer die eingegebene Root-Tabelle?
2. **Falls ja** -> Dialog:
   - Text: "Fuer [TABELLE] existieren bereits gespeicherte Traversal-Regeln. Uebernehmen oder neu eingeben?"
   - Button "Uebernehmen": Regeln in `TraversalRuleStore` laden. Im Traversal-Dialog werden nur FKs abgefragt, die noch keine Regel haben (neue FKs seit letzter Analyse).
   - Button "Neu eingeben": `TraversalRuleStore` bleibt leer, kompletter Dialog wie bisher.
   - Dialog-X / Escape: Abbruch, zurueck zur Eingabe.
3. **Falls nein** -> normaler Ablauf ohne Dialog.

### 3. Sequence-Mappings still uebernehmen

Beim Generierungs-Dialog (Sequence-Eingabe):
- Wenn `SequenceMappingStore` bereits einen Eintrag fuer die aktuelle Tabelle+PK hat -> Eintrag direkt verwenden, Eingabefeld nicht anzeigen/ueberspringen.
- Nur fuer Tabellen ohne bekanntes Mapping wird der Sequence-Dialog angezeigt.

### 4. Preset-System unveraendert

- Presets speichern weiterhin eigene Traversal-Regeln (wie bisher)
- Beim Laden eines Presets gelten Preset-Regeln (wie bisher)
- GlobalTraversalRuleStore greift nur wenn KEIN Preset aktiv ist (oder "Neu")

## Betroffene Klassen

| Klasse | Aenderung |
|--------|-----------|
| `GlobalTraversalRuleStore` (NEU) | Neuer Store: load/save/get/set fuer Root-Tabelle -> Regeln |
| `GeneratorPanel` | "Weiter"-Handler: Pruefung + Dialog vor Analyse-Start |
| `TraversalRuleStore` | Ggf. `loadFrom()` wiederverwenden fuer globale Regeln |
| `MergeScriptGenerator` / Sequence-Dialog | Bereits bekannte Sequences ueberspringen |
| `LauncherApp` | GlobalTraversalRuleStore instanziieren + an GeneratorPanel uebergeben |

## Testplan

- **GlobalTraversalRuleStore**: Load/Save/Roundtrip, mehrere Root-Tabellen, Update bestehender Eintraege
- **GeneratorPanel-Logik**: Pruefung ob globale Regeln vorhanden, Uebernahme vs. Neu
- **Sequence-Skip**: Bereits bekanntes Mapping wird nicht erneut abgefragt
