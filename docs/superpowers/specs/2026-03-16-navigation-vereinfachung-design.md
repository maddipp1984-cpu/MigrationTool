# Navigation-Vereinfachung: Flache Sidebar mit UPPERCASE-Labels

## Ziel

Den Navigationsbaum von verschachtelten Kategorieknoten (Exceltools > Excel Split) auf eine flache Liste mit dezenten Kategorie-Labels umstellen (IDE-Stil wie VS Code/IntelliJ).

## Vorher / Nachher

```
VORHER:                          NACHHER:
▶ Exceltools                     EXCEL
    Excel Split                    Excel Split
▶ Mergescripte                   SCRIPTE
    MERGE Generator                MERGE Generator
    INSERT Generator               INSERT Generator
▶ Einstellungen                  KONFIGURATION
    DB-Verbindung                  DB-Verbindung
```

## Verhalten

- Kategorie-Labels (EXCEL, SCRIPTE, KONFIGURATION): grau, klein, UPPERCASE, letter-spacing, **nicht klickbar/selektierbar**
- Tool-Eintraege: direkt klickbar, wechseln Card im Content-Bereich
- MERGE Generator vorausgewaehlt beim Start
- Theme-Umschalter bleibt im Menue "Ansicht" (keine Aenderung)

## Technische Umsetzung

### Betroffene Datei
- `LauncherApp.java` – nur Methode `buildNavTree()`

### Ansatz
- JTree bleibt als Komponente
- Kategorieknoten als nicht-selektierbare Knoten mit Custom `TreeCellRenderer`
- Renderer: Kategorie-Labels in kleiner, grauer Schrift mit UPPERCASE und letter-spacing
- Tool-Knoten: normales Rendering mit Highlight bei Selektion
- Im `TreeSelectionListener`: Klicks auf Kategorie-Knoten ignorieren (kein Card-Wechsel)
- Expand/Collapse fuer Kategorien deaktivieren (alle permanent aufgeklappt)

### Nicht betroffen
- Panels, Cards, Content-Bereich, CardLayout
- Menueleiste, Theme-Logik
- Alle anderen Klassen
