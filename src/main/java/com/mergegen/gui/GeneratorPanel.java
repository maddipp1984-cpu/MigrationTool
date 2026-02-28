package com.mergegen.gui;

import com.mergegen.analyzer.SchemaAnalyzer;
import com.mergegen.config.AppSettings;
import com.mergegen.config.QueryPresetStore;
import com.mergegen.config.SequenceMappingStore;
import com.mergegen.config.TableHistoryStore;
import com.mergegen.config.VirtualFkStore;
import com.mergegen.model.QueryPreset;
import com.mergegen.model.TableHistoryEntry;
import com.mergegen.db.DatabaseConnection;
import com.mergegen.generator.ScriptWriter;
import com.mergegen.model.ColumnInfo;
import com.mergegen.model.DependencyNode;
import com.mergegen.model.SequenceMapping;
import com.mergegen.model.TableRow;
import com.mergegen.model.TraversalResult;
import com.mergegen.service.TraversalService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Hauptpanel des Generators. Steuert den dreistufigen Arbeitsablauf
 * über ein CardLayout (unsichtbares Seitenwechsel-System):
 *
 *   CARD_INPUT  → Benutzer gibt Tabellennamen und Werte ein
 *   CARD_TREE   → Abhängigkeitsbaum wird angezeigt, Benutzer bestätigt
 *   CARD_RESULT → Ergebnis (Dateiname, Zeilenzahl) wird angezeigt
 *
 * Alle Datenbankoperationen laufen in einem SwingWorker (Hintergrundthread),
 * damit die Oberfläche während langer Abfragen nicht einfriert.
 */
public class GeneratorPanel extends JPanel {

    private static final String CARD_INPUT  = "INPUT";
    private static final String CARD_TREE   = "TREE";
    private static final String CARD_RESULT = "RESULT";

    private final CardLayout    cards    = new CardLayout();
    private final JPanel        cardPane = new JPanel(cards);
    private final SettingsPanel settingsPanel;

    // Step 1 – Eingabe
    private final JTextField tableField  = new JTextField(25);
    private final JTextField columnField = new JTextField(20);
    private final JTextArea  valueArea   = new JTextArea(5, 20);
    private final JCheckBox  testModeCheck   = new JCheckBox("Testmodus (Timestamp-Suffix an Suchspalte)");
    private final JCheckBox  updateCheck     = new JCheckBox("Bei Übereinstimmung aktualisieren (UPDATE)");
    private final JButton    analyzeBtn      = new JButton("Abhängigkeiten analysieren");
    private final JLabel     inputStatus     = new JLabel(" ");

    // Step 2 – Abhängigkeitsbaum
    private final JTree   depTree     = new JTree(new DefaultMutableTreeNode("(leer)"));
    private final JLabel  treeInfo    = new JLabel(" ");
    private final JButton generateBtn = new JButton("Merge Scripts erzeugen");
    private final JButton backBtn     = new JButton("← Zurück");

    // Step 3 – Ergebnis
    private final JTextArea resultArea = new JTextArea(6, 50);
    private final JButton   newBtn     = new JButton("Neue Abfrage");

    // Zwischengespeichertes Traversal-Ergebnis: wird in startAnalysis() befüllt
    // und in startGeneration() verwendet, damit die DB nur einmal abgefragt wird.
    private TraversalResult lastResult;
    private String          lastTable;
    private String          lastColumn = "";
    private List<String>    lastIds;

    private final AppSettings  appSettings  = new AppSettings();
    private final VirtualFkStore virtualFkStore;
    private final SequenceMappingStore seqStore;
    private final QueryPresetStore presetStore;
    private final TableHistoryStore historyStore;

    // Verlauf-Seitenleiste (CARD_INPUT)
    private final JList<TableHistoryEntry> historyList      = new JList<>(new DefaultListModel<>());
    // Zuletzt selektierter Verlaufseintrag – null wenn nichts selektiert oder Tabelle/Spalte geändert
    private       TableHistoryEntry        activeHistoryEntry = null;

    // Preset-Steuerung (CARD_INPUT)
    private final JComboBox<String> presetCombo     = new JComboBox<>();
    private final JButton           deletePresetBtn = new JButton("Löschen");
    private final JButton           savePresetBtn   = new JButton("Als Preset speichern");


    public GeneratorPanel(SettingsPanel settingsPanel, VirtualFkStore virtualFkStore,
                          SequenceMappingStore seqStore, QueryPresetStore presetStore,
                          TableHistoryStore historyStore) {
        this.settingsPanel  = settingsPanel;
        this.virtualFkStore = virtualFkStore;
        this.seqStore       = seqStore;
        this.presetStore    = presetStore;
        this.historyStore        = historyStore;
        setLayout(new BorderLayout());
        // Alle drei Karten registrieren; sichtbar ist anfangs nur CARD_INPUT
        cardPane.add(buildInputCard(),  CARD_INPUT);
        cardPane.add(buildTreeCard(),   CARD_TREE);
        cardPane.add(buildResultCard(), CARD_RESULT);
        add(cardPane, BorderLayout.CENTER);
        tableField.setText(appSettings.getLastTable());
        columnField.setText(appSettings.getLastColumn());
        // Gespeicherte Werte wiederherstellen
        List<String> savedValues = appSettings.getLastValues();
        if (!savedValues.isEmpty()) {
            valueArea.setText(String.join("\n", savedValues));
        }
        refreshPresetCombo();
        refreshHistoryList();
    }

    // ── Card 1: Eingabe ───────────────────────────────────────────────────────

    /** Baut das Eingabe-Panel mit Tabellenname-, Werte-Feld und Analyse-Button. */
    private JPanel buildInputCard() {
        JPanel card = new JPanel(new BorderLayout());

        // ── Preset-Leiste (NORTH) ─────────────────────────────────────────────
        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        presetBar.add(new JLabel("Preset:"));
        presetBar.add(presetCombo);
        presetBar.add(deletePresetBtn);
        card.add(presetBar, BorderLayout.NORTH);

        // Preset auswählen → Felder befüllen
        presetCombo.addItemListener(e -> {
            if (e.getStateChange() != java.awt.event.ItemEvent.SELECTED) return;
            String selected = (String) presetCombo.getSelectedItem();
            if (selected == null || selected.equals("(kein Preset)")) return;
            presetStore.findByName(selected).ifPresent(preset -> {
                tableField.setText(preset.getTable());
                columnField.setText(preset.getColumn());
                valueArea.setText(String.join("\n", preset.getValues()));
            });
        });

        // Preset löschen
        deletePresetBtn.addActionListener(e -> {
            String selected = (String) presetCombo.getSelectedItem();
            if (selected == null || selected.equals("(kein Preset)")) return;
            int choice = JOptionPane.showConfirmDialog(this,
                "Preset \"" + selected + "\" wirklich löschen?",
                "Preset löschen", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) {
                presetStore.remove(selected);
                refreshPresetCombo();
            }
        });

        // ── Eingabe-Formular (CENTER) ─────────────────────────────────────────
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new EmptyBorder(10, 40, 20, 40));

        GridBagConstraints lbl = gbc(0, 0, GridBagConstraints.NORTHEAST);
        GridBagConstraints fld = gbc(1, 0, GridBagConstraints.WEST);
        fld.fill    = GridBagConstraints.HORIZONTAL;
        fld.weightx = 1.0;

        lbl.gridy = 0; fld.gridy = 0;
        p.add(new JLabel("Führende Tabelle:"), lbl);
        p.add(tableField, fld);

        lbl.gridy = 1; fld.gridy = 1;
        p.add(new JLabel("Spaltenname:"), lbl);
        p.add(columnField, fld);

        GridBagConstraints hintRow = gbc(1, 2, GridBagConstraints.WEST);
        JLabel hintLabel = new JLabel("Leer lassen für automatische PK-Erkennung");
        hintLabel.setForeground(Color.GRAY);
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.PLAIN, 11f));
        p.add(hintLabel, hintRow);

        GridBagConstraints testModeRow = gbc(1, 3, GridBagConstraints.WEST);
        testModeCheck.setToolTipText("Im Testmodus wird ein Timestamp-Suffix an die Suchspalte angehängt " +
            "→ Datensatz gilt als neu und wird immer per INSERT angelegt");
        p.add(testModeCheck, testModeRow);

        GridBagConstraints updateRow = gbc(1, 4, GridBagConstraints.WEST);
        updateCheck.setToolTipText("Fügt WHEN MATCHED THEN UPDATE hinzu – alle Nicht-PK-Spalten werden aktualisiert");
        p.add(updateCheck, updateRow);

        lbl.gridy = 5; fld.gridy = 5;
        lbl.anchor = GridBagConstraints.NORTHEAST;
        p.add(new JLabel("Werte (ein Wert pro Zeile):"), lbl);
        // TextArea mit Scrollbar
        JScrollPane valueScroll = new JScrollPane(valueArea);
        valueScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        fld.fill   = GridBagConstraints.BOTH;
        fld.weighty = 1.0;
        p.add(valueScroll, fld);
        fld.fill    = GridBagConstraints.HORIZONTAL;
        fld.weighty = 0;

        GridBagConstraints btnRow = gbc(0, 6, GridBagConstraints.WEST);
        btnRow.gridwidth = 2;
        btnRow.insets    = new Insets(16, 0, 4, 0);
        p.add(analyzeBtn, btnRow);

        GridBagConstraints statusRow = gbc(0, 7, GridBagConstraints.WEST);
        statusRow.gridwidth = 2;
        inputStatus.setForeground(Color.RED);
        p.add(inputStatus, statusRow);

        analyzeBtn.addActionListener(e -> startAnalysis());

        // ── Verlauf-Seitenleiste (WEST im SplitPane) ─────────────────────────
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setCellRenderer(new HistoryListRenderer());
        historyList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            TableHistoryEntry selected = historyList.getSelectedValue();
            if (selected == null) return;
            // Felder mit Verlaufseintrag befüllen
            tableField.setText(selected.getTable());
            columnField.setText(selected.getColumn());
            valueArea.setText(String.join("\n", selected.getValues()));
            // Aktiven Eintrag merken – bei erneuter Analyse wird dieser aktualisiert
            activeHistoryEntry = selected;
        });

        JButton removeHistoryBtn = new JButton("Entfernen");
        removeHistoryBtn.addActionListener(e -> {
            TableHistoryEntry selected = historyList.getSelectedValue();
            if (selected == null) return;
            historyStore.remove(selected);
            refreshHistoryList();
        });

        JPanel historyPanel = new JPanel(new BorderLayout(0, 4));
        historyPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Verlauf"));
        historyPanel.add(new JScrollPane(historyList), BorderLayout.CENTER);
        JPanel removeBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        removeBtnRow.add(removeHistoryBtn);
        historyPanel.add(removeBtnRow, BorderLayout.SOUTH);
        historyPanel.setPreferredSize(new Dimension(200, 0));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, historyPanel, p);
        splitPane.setDividerLocation(200);
        splitPane.setResizeWeight(0.0);

        card.add(splitPane, BorderLayout.CENTER);
        return card;
    }

    /** Zweizeiliger Renderer für Verlauf-Einträge. */
    private static class HistoryListRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof TableHistoryEntry) {
                TableHistoryEntry e = (TableHistoryEntry) value;
                String valStr = String.join(", ", e.getValues());
                if (valStr.length() > 40) valStr = valStr.substring(0, 38) + "…";
                String color = isSelected ? "white" : "gray";
                setText("<html><b>" + e.getTable() + "</b><br>"
                    + "<font color='" + color + "'>" + valStr + "</font></html>");
            }
            return this;
        }
    }

    /** Befüllt die Preset-ComboBox neu aus dem Store. */
    private void refreshPresetCombo() {
        // ItemListener vorübergehend stumm schalten, um ungewollte Feldüberschreibung zu verhindern
        java.awt.event.ItemListener[] listeners = presetCombo.getItemListeners();
        for (java.awt.event.ItemListener l : listeners) presetCombo.removeItemListener(l);

        presetCombo.removeAllItems();
        presetCombo.addItem("(kein Preset)");
        presetStore.getAll().forEach(p -> presetCombo.addItem(p.getName()));

        for (java.awt.event.ItemListener l : listeners) presetCombo.addItemListener(l);
    }

    /**
     * Startet die Datenbankanalyse im Hintergrundthread (SwingWorker).
     *
     * Bei mehreren Werten wird jeder einzeln traversiert und die
     * Ergebnisse anschließend mit TraversalResult.merge() zusammengeführt.
     */
    private void startAnalysis() {
        // Passwort-Check: falls leer, Dialog zur Profil-Auswahl anzeigen
        if (!settingsPanel.isPasswordSet() && !showPasswordDialog()) return;

        String table  = tableField.getText().trim().toUpperCase();
        String column = columnField.getText().trim().toUpperCase();

        String[] lines = valueArea.getText().split("\\n");
        List<String> values = Arrays.stream(lines)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        if (table.isEmpty())  { setInputStatus("Bitte Tabellenname eingeben."); return; }
        if (values.isEmpty()) { setInputStatus("Bitte mindestens einen Wert eingeben."); return; }

        setInputStatus("Analysiere...");
        analyzeBtn.setEnabled(false);

        SwingWorker<TraversalResult, String> worker = new SwingWorker<>() {
            @Override
            protected TraversalResult doInBackground() throws Exception {
                var config = settingsPanel.getCurrentConfig();
                try (DatabaseConnection conn = new DatabaseConnection(config)) {
                    SchemaAnalyzer   analyzer = new SchemaAnalyzer(conn.get(), config);
                    TraversalService service  = new TraversalService(analyzer, virtualFkStore);

                    if (values.size() == 1) {
                        return service.traverse(table, column, values.get(0));
                    }

                    List<TraversalResult> results = new java.util.ArrayList<>();
                    for (int i = 0; i < values.size(); i++) {
                        publish("Analysiere Wert " + (i + 1) + " von " + values.size() + "...");
                        results.add(service.traverse(table, column, values.get(i)));
                    }
                    return TraversalResult.merge(results);
                }
            }

            @Override
            protected void process(List<String> chunks) {
                // Zeige den letzten Status-Text an
                setInputStatus(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                analyzeBtn.setEnabled(true);
                try {
                    lastResult = get();
                    lastTable  = table;
                    lastColumn = column;
                    lastIds    = values;
                    appSettings.setLastTable(table);
                    appSettings.setLastColumn(column);
                    appSettings.setLastValues(values);
                    // Aktiven Verlaufseintrag aktualisieren (gleiche Tabelle/Spalte, ggf. neue Werte)
                    // oder neuen Eintrag anlegen
                    if (activeHistoryEntry != null
                            && activeHistoryEntry.getTable().equalsIgnoreCase(table)
                            && activeHistoryEntry.getColumn().equalsIgnoreCase(column)) {
                        historyStore.updateEntry(activeHistoryEntry, table, column, values);
                    } else {
                        historyStore.addOrUpdate(new TableHistoryEntry(table, column, values));
                    }
                    activeHistoryEntry = null;
                    refreshHistoryList();
                    showTreeCard(lastResult);
                } catch (Exception ex) {
                    setInputStatus("Fehler: " + rootCause(ex));
                }
            }
        };
        worker.execute();
    }

    // ── Card 2: Abhängigkeitsbaum ─────────────────────────────────────────────

    /** Baut das Panel mit dem Abhängigkeitsbaum und den Aktions-Buttons. */
    private JPanel buildTreeCard() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(new EmptyBorder(20, 30, 20, 30));

        treeInfo.setFont(treeInfo.getFont().deriveFont(Font.BOLD));
        p.add(treeInfo, BorderLayout.NORTH);

        depTree.setRootVisible(true);
        javax.swing.tree.DefaultTreeCellRenderer renderer = new javax.swing.tree.DefaultTreeCellRenderer();
        renderer.setLeafIcon(renderer.getClosedIcon());
        depTree.setCellRenderer(renderer);

        JScrollPane scroll = new JScrollPane(depTree);
        scroll.setPreferredSize(new Dimension(500, 300));
        p.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(backBtn);
        buttons.add(savePresetBtn);
        buttons.add(generateBtn);
        p.add(buttons, BorderLayout.SOUTH);

        backBtn.addActionListener(e -> cards.show(cardPane, CARD_INPUT));
        generateBtn.addActionListener(e -> startGeneration());
        savePresetBtn.addActionListener(e -> saveCurrentPreset());

        return p;
    }

    /** Speichert die aktuelle Abfrage als benanntes Preset. */
    private void saveCurrentPreset() {
        // 1. Vorschlag = aktuell gewählter Preset-Name (falls "(kein Preset)" → leer)
        String currentPreset = (String) presetCombo.getSelectedItem();
        String suggestion = (currentPreset != null && !currentPreset.equals("(kein Preset)"))
            ? currentPreset : "";

        // 3. Namens-Dialog
        String name = (String) JOptionPane.showInputDialog(
            this,
            "Name für das Preset:",
            "Preset speichern",
            JOptionPane.PLAIN_MESSAGE,
            null, null,
            suggestion);

        if (name == null) return;  // Abbruch
        name = name.trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Bitte einen Namen eingeben.", "Preset speichern", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 4. Überschreiben-Prüfung
        final String finalName = name;
        if (presetStore.findByName(finalName).isPresent()) {
            int choice = JOptionPane.showConfirmDialog(this,
                "Preset \"" + finalName + "\" existiert bereits. Überschreiben?",
                "Preset speichern", JOptionPane.YES_NO_OPTION);
            if (choice != JOptionPane.YES_OPTION) return;
            presetStore.remove(finalName);
        }

        // 5. Aktuellen Stand aus Eingabefeldern lesen
        String table  = tableField.getText().trim().toUpperCase();
        String column = columnField.getText().trim().toUpperCase();
        List<String> values = Arrays.stream(valueArea.getText().split("\\n"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());

        presetStore.add(new QueryPreset(finalName, table, column, values));

        // 6. Combo aktualisieren und neues Preset auswählen
        refreshPresetCombo();
        presetCombo.setSelectedItem(finalName);
    }

    /**
     * Befüllt den Abhängigkeitsbaum mit dem TraversalResult und wechselt zur Tree-Karte.
     * Der Baum wird nach dem Befüllen vollständig aufgeklappt.
     * Anschließend wird die Checkbox-Liste der Konstantentabellen neu aufgebaut.
     */
    private void showTreeCard(TraversalResult result) {
        DefaultMutableTreeNode root = buildTreeNodes(result.getRootNode());
        depTree.setModel(new DefaultTreeModel(root));
        expandAllNodes();

        int total = result.getTotalRows();
        Map<String, Integer> counts = result.getTableCounts();
        treeInfo.setText("Gefunden: " + total + " Datensatz" + (total != 1 ? "e" : "")
                + " in " + counts.size() + " Tabelle" + (counts.size() != 1 ? "n" : ""));

        cards.show(cardPane, CARD_TREE);
    }

    /**
     * Wandelt einen DependencyNode-Baum rekursiv in einen JTree-Knoten-Baum um.
     * DependencyNode.toString() liefert den angezeigten Text (Tabellenname + Zeilenzahl).
     */
    private DefaultMutableTreeNode buildTreeNodes(DependencyNode node) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node.toString());
        for (DependencyNode child : node.getChildren()) {
            treeNode.add(buildTreeNodes(child));
        }
        return treeNode;
    }

    /**
     * Klappt alle Knoten des JTree auf.
     *
     * expandRow() muss in einer Schleife aufgerufen werden, weil JTree
     * die Zeilen beim Aufklappen dynamisch neu nummeriert – nach jedem
     * expandRow() gibt es mehr sichtbare Zeilen als vorher.
     */
    private void expandAllNodes() {
        for (int i = 0; i < depTree.getRowCount(); i++) {
            depTree.expandRow(i);
        }
    }

    // ── Card 3: Ergebnis ──────────────────────────────────────────────────────

    /** Baut das Ergebnis-Panel mit dem Textbereich für die Ausgabe und dem "Neu"-Button. */
    private JPanel buildResultCard() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(new EmptyBorder(20, 30, 20, 30));

        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        p.add(new JScrollPane(resultArea), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(newBtn);
        p.add(buttons, BorderLayout.SOUTH);

        // "Neue Abfrage": Formular leeren, Ergebnis verwerfen, zurück zur Eingabe
        newBtn.addActionListener(e -> {
            tableField.setText("");
            columnField.setText("");
            valueArea.setText("");
            setInputStatus(" ");
            lastResult = null;
            cards.show(cardPane, CARD_INPUT);
        });

        return p;
    }

    /**
     * Startet die Script-Generierung im Hintergrundthread.
     * Nutzt das in startAnalysis() gespeicherte lastResult,
     * sodass die Datenbank nicht erneut abgefragt werden muss.
     *
     * Vor dem eigentlichen Generieren wird pro Tabelle ein Sequence-Dialog
     * angezeigt (dreistufige Vorschlags-Logik: Store → Trigger → leer).
     */
    private void startGeneration() {
        if (lastResult == null) return;
        String nameColumn = columnField.getText().trim().toUpperCase();
        String testSuffix = testModeCheck.isSelected()
            ? "_" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            : "";

        List<TableRow> filteredRows = new ArrayList<>(lastResult.getOrderedRows());

        Map<String, Integer> filteredCounts = new LinkedHashMap<>();
        for (TableRow row : filteredRows) {
            filteredCounts.merge(row.getTableName(), 1, Integer::sum);
        }

        // 3. Eindeutige Tabellen mit ihren PK-ColumnInfos sammeln (nur gefilterte)
        Map<String, List<ColumnInfo>> tablePkMap = new LinkedHashMap<>();
        for (TableRow row : filteredRows) {
            String tbl = row.getTableName();
            if (!tablePkMap.containsKey(tbl)) {
                List<ColumnInfo> pkCols = row.getColumns().values().stream()
                    .filter(ColumnInfo::isPrimaryKey)
                    .collect(Collectors.toList());
                tablePkMap.put(tbl, pkCols);
            }
        }

        // Sequence-Dialog pro Tabelle/PK-Spalte
        Map<String, String> sequenceMap = new LinkedHashMap<>();

        // Trigger-Erkennung braucht DB-Verbindung – einmal öffnen
        SchemaAnalyzer triggerAnalyzer = null;
        DatabaseConnection triggerConn = null;
        try {
            var config = settingsPanel.getCurrentConfig();
            triggerConn = new DatabaseConnection(config);
            triggerAnalyzer = new SchemaAnalyzer(triggerConn.get(), config);
        } catch (Exception ex) {
            // Trigger-Erkennung nicht möglich – kein Abbruch, nur kein Vorschlag
        }

        try {
            for (Map.Entry<String, List<ColumnInfo>> entry : tablePkMap.entrySet()) {
                String tbl = entry.getKey();
                List<ColumnInfo> pkCols = entry.getValue();

                for (ColumnInfo pkColInfo : pkCols) {
                    String pkCol = pkColInfo.getName();

                    // FK-Spalte → Wert kommt vom Parent, nie eine Sequence
                    boolean isFkColumn = lastResult.getFkRelations().values().stream()
                        .flatMap(List::stream)
                        .anyMatch(fk -> fk.getChildTable().equalsIgnoreCase(tbl)
                                     && fk.getFkColumn().equalsIgnoreCase(pkCol));
                    if (isFkColumn) continue;

                    // Datum/Timestamp → kein Sequence-Kandidat
                    String dataType = pkColInfo.getDataType().toUpperCase();
                    if (dataType.equals("DATE") || dataType.startsWith("TIMESTAMP")) continue;

                    String key = tbl + "." + pkCol;

                    // Dreistufige Vorschlags-Logik
                    String suggestion = "";

                    // 1. Im Store gespeichert?
                    Optional<SequenceMapping> stored = seqStore.findByTable(tbl);
                    if (stored.isPresent() && stored.get().getPkColumn().equalsIgnoreCase(pkCol)) {
                        suggestion = stored.get().getSequenceName();
                    }

                    // 2. Kein Store-Eintrag → Trigger prüfen
                    if (suggestion.isEmpty() && triggerAnalyzer != null) {
                        Optional<String> triggerSeq = triggerAnalyzer.detectTriggerSequence(tbl);
                        if (triggerSeq.isPresent()) {
                            suggestion = triggerSeq.get();
                        }
                    }

                    // 3. Dialog anzeigen
                    String input = (String) JOptionPane.showInputDialog(
                        this,
                        "Tabelle " + tbl + ", PK-Spalte " + pkCol +
                        "\n(leer = PK-Wert aus Quelle übernehmen)",
                        "Sequence-Name",
                        JOptionPane.QUESTION_MESSAGE,
                        null, null,
                        suggestion);

                    // Abbruch → gesamte Generierung abbrechen
                    if (input == null) return;

                    input = input.trim().toUpperCase();
                    if (!input.isEmpty()) {
                        sequenceMap.put(key, input);
                        // Im Store speichern/aktualisieren
                        seqStore.remove(tbl, pkCol);
                        seqStore.add(new SequenceMapping(tbl, pkCol, input));
                    }
                }
            }
        } finally {
            if (triggerConn != null) {
                try { triggerConn.close(); } catch (Exception ignored) {}
            }
        }

        generateBtn.setEnabled(false);
        backBtn.setEnabled(false);

        Map<String, String> finalSeqMap = sequenceMap;
        List<TableRow> finalFilteredRows = filteredRows;
        Map<String, Integer> finalFilteredCounts = filteredCounts;
        String finalNameColumn = nameColumn;
        String finalTestSuffix = testSuffix;
        boolean finalIncludeUpdate = updateCheck.isSelected();
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                ScriptWriter writer = new ScriptWriter();
                return writer.write(
                    finalFilteredRows,
                    finalFilteredCounts,
                    lastTable, lastIds,
                    settingsPanel.getOutputDir(),
                    finalSeqMap,
                    finalNameColumn,
                    finalTestSuffix,
                    lastResult.getFkRelations(),
                    finalIncludeUpdate);
            }

            @Override
            protected void done() {
                generateBtn.setEnabled(true);
                backBtn.setEnabled(true);
                try {
                    String filename = get();
                    int total = finalFilteredRows.size();
                    resultArea.setText(
                        "Script erfolgreich erstellt!\n\n" +
                        "Datei:        " + filename + "\n" +
                        "Statements:   " + total + "\n" +
                        "Tabellen:     " + finalFilteredCounts.size() + "\n\n" +
                        "Tabellenübersicht:\n" +
                        buildSummary(finalFilteredCounts)
                    );
                    cards.show(cardPane, CARD_RESULT);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(GeneratorPanel.this,
                        "Fehler bei der Generierung:\n" + rootCause(ex),
                        "Fehler", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    /** Befüllt die Verlauf-JList neu aus dem Store. */
    private void refreshHistoryList() {
        DefaultListModel<TableHistoryEntry> model = new DefaultListModel<>();
        historyStore.getAll().forEach(model::addElement);
        historyList.setModel(model);
    }

    /**
     * Zeigt einen Dialog zur Profil-Auswahl und Passwort-Eingabe.
     * Gibt true zurück wenn der User bestätigt hat, false bei Abbruch.
     */
    private boolean showPasswordDialog() {
        List<String> profiles = settingsPanel.getProfileNames();

        JComboBox<String> profileCombo = new JComboBox<>();
        if (profiles.isEmpty()) {
            profileCombo.addItem("(kein Profil gespeichert)");
            profileCombo.setEnabled(false);
        } else {
            profiles.forEach(profileCombo::addItem);
        }

        JPasswordField pwField = new JPasswordField(20);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints lbl = new GridBagConstraints();
        lbl.anchor = GridBagConstraints.WEST;
        lbl.insets = new Insets(6, 4, 6, 8);
        lbl.gridx = 0;
        GridBagConstraints fld = new GridBagConstraints();
        fld.fill   = GridBagConstraints.HORIZONTAL;
        fld.weightx = 1.0;
        fld.insets = new Insets(6, 0, 6, 4);
        fld.gridx  = 1;

        lbl.gridy = 0; fld.gridy = 0;
        panel.add(new JLabel("Verbindungsprofil:"), lbl);
        panel.add(profileCombo, fld);

        lbl.gridy = 1; fld.gridy = 1;
        panel.add(new JLabel("Passwort:"), lbl);
        panel.add(pwField, fld);

        // Fokus direkt ins Passwortfeld
        SwingUtilities.invokeLater(pwField::requestFocusInWindow);

        int result = JOptionPane.showConfirmDialog(
            this, panel, "Datenbankverbindung",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return false;
        if (pwField.getPassword().length == 0) {
            setInputStatus("Bitte Passwort eingeben.");
            return false;
        }

        String selected = (String) profileCombo.getSelectedItem();
        if (selected != null && profileCombo.isEnabled()) {
            settingsPanel.applyProfileWithPassword(selected, pwField.getPassword());
        } else {
            // Kein Profil – nur Passwort setzen nicht möglich ohne Verbindungsdaten
            setInputStatus("Bitte zuerst ein Verbindungsprofil unter DB-Verbindung anlegen.");
            return false;
        }
        return true;
    }

    private void setInputStatus(String msg) {
        inputStatus.setText(msg);
    }

    /** Formatiert die Tabellen-Zeilenzahl als mehrzeiligen String für die Ergebnisanzeige. */
    private static String buildSummary(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        counts.forEach((t, c) ->
            sb.append("  ").append(t).append(": ")
              .append(c).append(" Datensatz").append(c != 1 ? "e" : "").append("\n"));
        return sb.toString();
    }

    /**
     * Extrahiert die eigentliche Fehlerursache aus einer Exception-Kette.
     *
     * SwingWorker.get() wirft ExecutionException, die den eigentlichen Fehler
     * als Cause enthält. Diese Methode traversiert die Cause-Kette bis zur
     * innersten Exception, deren Meldung für den Benutzer relevant ist.
     */
    private static String rootCause(Exception ex) {
        Throwable t = ex;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    // ── Workflow-Unterstützung ────────────────────────────────────────────────

    /**
     * Führt Analyse + Generierung mit den zuletzt gespeicherten Einstellungen aus.
     * Wird vom Workflow-Panel für die automatisierte Gesamtausführung genutzt.
     * Für Sequences werden ausschließlich gespeicherte Werte verwendet (kein Dialog).
     *
     * @param onComplete wird auf dem EDT mit true (Erfolg) oder false (Fehler/keine Einstellungen) aufgerufen
     */
    public void runWithLastSettings(Consumer<Boolean> onComplete) {
        String       table  = appSettings.getLastTable();
        String       column = appSettings.getLastColumn();
        List<String> values = appSettings.getLastValues();

        if (table.isEmpty() || values.isEmpty()) {
            onComplete.accept(false);
            return;
        }

        new SwingWorker<TraversalResult, Void>() {
            @Override
            protected TraversalResult doInBackground() throws Exception {
                var config = settingsPanel.getCurrentConfig();
                try (DatabaseConnection conn = new DatabaseConnection(config)) {
                    SchemaAnalyzer   analyzer = new SchemaAnalyzer(conn.get(), config);
                    TraversalService service  = new TraversalService(analyzer, virtualFkStore);
                    if (values.size() == 1) {
                        return service.traverse(table, column, values.get(0));
                    }
                    List<TraversalResult> results = new ArrayList<>();
                    for (String v : values) results.add(service.traverse(table, column, v));
                    return TraversalResult.merge(results);
                }
            }

            @Override
            protected void done() {
                try {
                    lastResult = get();
                    lastTable  = table;
                    lastColumn = column;
                    lastIds    = values;
                    executeGenerationAuto(onComplete);
                } catch (Exception ex) {
                    onComplete.accept(false);
                }
            }
        }.execute();
    }

    /**
     * Führt die Script-Generierung ohne Sequence-Dialoge durch (für den Workflow-Modus).
     * Verwendet ausschließlich gespeicherte Sequence-Mappings aus dem Store.
     */
    private void executeGenerationAuto(Consumer<Boolean> onComplete) {
        List<TableRow> filteredRows = new ArrayList<>(lastResult.getOrderedRows());

        Map<String, Integer> filteredCounts = new LinkedHashMap<>();
        for (TableRow row : filteredRows) filteredCounts.merge(row.getTableName(), 1, Integer::sum);

        // PK-Spalten pro Tabelle
        Map<String, List<String>> tablePkMap = new LinkedHashMap<>();
        for (TableRow row : filteredRows) {
            String tbl = row.getTableName();
            if (!tablePkMap.containsKey(tbl)) {
                tablePkMap.put(tbl, row.getColumns().values().stream()
                    .filter(ColumnInfo::isPrimaryKey)
                    .map(ColumnInfo::getName)
                    .collect(Collectors.toList()));
            }
        }

        // Sequences aus Store – kein Dialog, unbekannte Tabellen → Quell-PK-Wert
        Map<String, String> seqMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : tablePkMap.entrySet()) {
            String tbl = e.getKey();
            for (String pkCol : e.getValue()) {
                seqStore.findByTable(tbl).ifPresent(sm -> {
                    if (sm.getPkColumn().equalsIgnoreCase(pkCol) && !sm.getSequenceName().isEmpty()) {
                        seqMap.put(tbl + "." + pkCol, sm.getSequenceName());
                    }
                });
            }
        }

        boolean includeUpdate = updateCheck.isSelected();

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return new ScriptWriter().write(
                    filteredRows, filteredCounts,
                    lastTable, lastIds,
                    settingsPanel.getOutputDir(),
                    seqMap,
                    lastColumn, "",
                    lastResult.getFkRelations(),
                    includeUpdate);
            }

            @Override
            protected void done() {
                try { get(); onComplete.accept(true); }
                catch (Exception ex) { onComplete.accept(false); }
            }
        }.execute();
    }

    /** Erstellt einen GridBagConstraints-Helfer mit voreingestellten Abständen. */
    private static GridBagConstraints gbc(int x, int y, int anchor) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx  = x;
        c.gridy  = y;
        c.anchor = anchor;
        c.insets = new Insets(6, 4, 6, 4);
        return c;
    }

}
