package com.mergegen.gui;

import com.mergegen.analyzer.SchemaAnalyzer;
import com.mergegen.config.AppSettings;
import com.mergegen.config.ConstantTableStore;
import com.mergegen.config.QueryPresetStore;
import com.mergegen.config.SequenceMappingStore;
import com.mergegen.config.SubselectMappingStore;
import com.mergegen.config.GlobalTraversalRuleStore;
import com.mergegen.config.TraversalRuleStore;
import com.mergegen.config.VirtualFkStore;
import com.mergegen.model.QueryPreset;
import com.mergegen.db.DatabaseConnection;
import com.mergegen.generator.ScriptWriteContext;
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
    private final JTextField aliasField      = new JTextField(20);
    private final JCheckBox  testModeCheck   = new JCheckBox("Testmodus (Timestamp-Suffix an Suchspalte)");
    private final JCheckBox  updateCheck     = new JCheckBox("Bei Übereinstimmung aktualisieren (UPDATE)");
    private final JButton    analyzeBtn      = new JButton("Abhängigkeiten analysieren");
    private final JButton    newInputBtn     = new JButton("Neu");
    private final JLabel     inputStatus     = new JLabel(" ");

    // Step 2 – Abhängigkeitsbaum
    private final JTree   depTree     = new JTree(new DefaultMutableTreeNode("(leer)"));
    private final JLabel  treeInfo    = new JLabel(" ");
    private final JButton generateBtn = new JButton("Merge Scripts erzeugen");
    private final JButton backBtn     = new JButton("← Zurück");
    // Konstantentabellen-Anzeige (Tree-Karte, nur Info – Verwaltung im eigenen Tab)
    private final JPanel  constPanel  = new JPanel();

    // Step 3 – Ergebnis
    private final JTextArea resultArea = new JTextArea(6, 50);
    private final JButton   newBtn     = new JButton("Neue Abfrage");
    private final JButton   diffBtn    = new JButton("Mit letzter Version vergleichen");
    private final JButton   diagramBtn = new JButton("Diagramm anzeigen");
    private org.fife.ui.rsyntaxtextarea.RSyntaxTextArea sqlPreviewArea;
    private String lastGeneratedFile;
    private String lastPreviousFile;

    // Zwischengespeichertes Traversal-Ergebnis: wird in startAnalysis() befüllt
    // und in startGeneration() verwendet, damit die DB nur einmal abgefragt wird.
    private TraversalResult lastResult;
    // Einzelergebnisse pro Objekt (für objektweise Script-Generierung)
    private List<TraversalResult> lastResultsPerObject;
    private String          lastTable;
    private String          lastColumn = "";
    private List<String>    lastIds;

    private final AppSettings  appSettings  = new AppSettings();
    private final VirtualFkStore virtualFkStore;
    private final TraversalRuleStore ruleStore;
    private final SequenceMappingStore seqStore;
    private final ConstantTableStore constTableStore;
    private final QueryPresetStore presetStore;
    private final SubselectMappingStore subselectStore;
    private final GlobalTraversalRuleStore globalRuleStore;

    // Preset-Steuerung (CARD_INPUT)
    private final JComboBox<String> presetCombo     = new JComboBox<>();
    private final JButton           deletePresetBtn = new JButton("Löschen");
    private final JButton           savePresetBtn   = new JButton("Als Preset speichern");

    // Referenz auf SequenceMappingPanel für Refresh nach Generierung
    private SequenceMappingPanel seqMappingPanel;


    public GeneratorPanel(SettingsPanel settingsPanel, VirtualFkStore virtualFkStore,
                          TraversalRuleStore ruleStore,
                          SequenceMappingStore seqStore, ConstantTableStore constTableStore,
                          QueryPresetStore presetStore,
                          SubselectMappingStore subselectStore,
                          GlobalTraversalRuleStore globalRuleStore) {
        this.settingsPanel   = settingsPanel;
        this.virtualFkStore  = virtualFkStore;
        this.ruleStore       = ruleStore;
        this.seqStore        = seqStore;
        this.constTableStore = constTableStore;
        this.presetStore     = presetStore;
        this.subselectStore  = subselectStore;
        this.globalRuleStore = globalRuleStore;
        setLayout(new BorderLayout());
        // Alle drei Karten registrieren; sichtbar ist anfangs nur CARD_INPUT
        cardPane.add(buildInputCard(),  CARD_INPUT);
        cardPane.add(buildTreeCard(),   CARD_TREE);
        cardPane.add(buildResultCard(), CARD_RESULT);
        add(cardPane, BorderLayout.CENTER);
        refreshPresetCombo();

        // Letzte Eingaben aus app.properties vorbelegen
        String savedTable  = appSettings.getLastTable();
        String savedColumn = appSettings.getLastColumn();
        if (savedTable != null && !savedTable.isEmpty()) tableField.setText(savedTable);
        if (savedColumn != null && !savedColumn.isEmpty()) columnField.setText(savedColumn);
    }

    /** Setzt die Referenz auf das SequenceMappingPanel für automatischen Refresh. */
    public void setSequenceMappingPanel(SequenceMappingPanel panel) {
        this.seqMappingPanel = panel;
    }

    // ── Card 1: Eingabe ───────────────────────────────────────────────────────

    /** Baut das Eingabe-Panel mit Tabellenname-, Werte-Feld und Analyse-Button. */
    private JPanel buildInputCard() {
        JPanel card = new JPanel(new BorderLayout());

        // ── Preset-Leiste (NORTH) ─────────────────────────────────────────────
        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        presetBar.add(new JLabel("Preset:"));
        presetBar.add(presetCombo);
        presetBar.add(newInputBtn);
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
                aliasField.setText(preset.getAlias());
                valueArea.setText(String.join("\n", preset.getValues()));
                ruleStore.loadFrom(preset.getTraversalRules());
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

        lbl.gridy = 3; fld.gridy = 3;
        p.add(new JLabel("Dateiname-Alias:"), lbl);
        aliasField.setToolTipText("Optionaler Alias fuer den Dateinamen (z.B. 'King_Export' statt Tabellenname)");
        p.add(aliasField, fld);

        GridBagConstraints aliasHintRow = gbc(1, 4, GridBagConstraints.WEST);
        JLabel aliasHintLabel = new JLabel("Leer = Tabellenname als Dateiname");
        aliasHintLabel.setForeground(Color.GRAY);
        aliasHintLabel.setFont(aliasHintLabel.getFont().deriveFont(Font.PLAIN, 11f));
        p.add(aliasHintLabel, aliasHintRow);

        GridBagConstraints testModeRow = gbc(1, 5, GridBagConstraints.WEST);
        testModeCheck.setToolTipText("Im Testmodus wird ein Timestamp-Suffix an die Suchspalte angehängt " +
            "→ Datensatz gilt als neu und wird immer per INSERT angelegt");
        p.add(testModeCheck, testModeRow);

        GridBagConstraints updateRow = gbc(1, 6, GridBagConstraints.WEST);
        updateCheck.setToolTipText("Fügt WHEN MATCHED THEN UPDATE hinzu – alle Nicht-PK-Spalten werden aktualisiert");
        p.add(updateCheck, updateRow);

        lbl.gridy = 7; fld.gridy = 7;
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

        GridBagConstraints btnRow = gbc(0, 8, GridBagConstraints.WEST);
        btnRow.gridwidth = 2;
        btnRow.insets    = new Insets(16, 0, 4, 0);
        p.add(analyzeBtn, btnRow);

        GridBagConstraints statusRow = gbc(0, 9, GridBagConstraints.WEST);
        statusRow.gridwidth = 2;
        inputStatus.setForeground(Color.RED);
        p.add(inputStatus, statusRow);

        analyzeBtn.addActionListener(e -> startAnalysis());
        newInputBtn.addActionListener(e -> resetInputCard());

        card.add(p, BorderLayout.CENTER);
        return card;
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

        // Traversal-Regeln zurücksetzen – nur wenn kein Preset aktiv ist,
        // denn Presets bringen eigene Regeln mit (beim Auswählen geladen)
        String activePreset = (String) presetCombo.getSelectedItem();
        if (activePreset == null || activePreset.equals("(kein Preset)")) {
            String rootTable = table;
            if (globalRuleStore != null && globalRuleStore.hasRulesFor(rootTable)) {
                int choice = JOptionPane.showOptionDialog(
                    this,
                    "Für " + rootTable + " existieren bereits gespeicherte Traversal-Regeln.\n"
                        + "Sollen diese übernommen werden?",
                    "Traversal-Regeln",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    new String[]{"Übernehmen", "Neu eingeben", "Abbrechen"},
                    "Übernehmen");

                if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                    return;  // Abbruch
                }
                if (choice == 0) {
                    ruleStore.loadFrom(globalRuleStore.getRulesFor(rootTable));
                } else {
                    ruleStore.clear();
                }
            } else {
                ruleStore.clear();
            }
        }

        setInputStatus("Analysiere...");
        analyzeBtn.setEnabled(false);

        SwingWorker<TraversalResult, String> worker = new SwingWorker<>() {
            @Override
            protected TraversalResult doInBackground() throws Exception {
                var config = settingsPanel.getCurrentConfig();
                try (DatabaseConnection conn = new DatabaseConnection(config)) {
                    SchemaAnalyzer   analyzer = new SchemaAnalyzer(conn.get(), config);

                    // Sequence-Mappings aus STB_TABDEF aktualisieren (batch)
                    publish("Lade Sequence-Mappings aus STB_TABDEF...");
                    Map<String, String> tabdefMappings = analyzer.loadAllSequencesFromTabdef();
                    if (!tabdefMappings.isEmpty()) {
                        for (Map.Entry<String, String> entry : tabdefMappings.entrySet()) {
                            String[] parts = entry.getKey().split("\\.", 2);
                            seqStore.removeWithoutSave(parts[0], parts[1]);
                            seqStore.addWithoutSave(new SequenceMapping(parts[0], parts[1], entry.getValue()));
                        }
                        seqStore.save();
                    }

                    TraversalService service  = new TraversalService(analyzer, virtualFkStore, ruleStore);
                    service.setLogger(this::publish);
                    service.setDecider(GeneratorPanel.this::askTraversalDecisionEnum);

                    List<TraversalResult> results = new java.util.ArrayList<>();
                    for (int i = 0; i < values.size(); i++) {
                        if (values.size() > 1) {
                            publish("Analysiere Wert " + (i + 1) + " von " + values.size() + "...");
                        }
                        results.add(service.traverse(table, column, values.get(i)));
                    }
                    lastResultsPerObject = results;
                    if (results.size() == 1) {
                        return results.get(0);
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
                    showTreeCard(lastResult);

                    // Traversal-Regeln global speichern
                    if (globalRuleStore != null) {
                        Map<String, TraversalRuleStore.TraversalRule> currentRules = ruleStore.getAll();
                        if (!currentRules.isEmpty()) {
                            globalRuleStore.saveRulesFor(table, currentRules);
                        }
                    }
                } catch (Exception ex) {
                    if (isCausedBy(ex, TraversalService.TraversalCancelledException.class)) {
                        setInputStatus("Analyse abgebrochen.");
                    } else {
                        setInputStatus("Fehler: " + rootCause(ex));
                    }
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

        JScrollPane treeScroll = new JScrollPane(depTree);
        treeScroll.setPreferredSize(new Dimension(500, 300));

        // Konstantentabellen-Anzeige (rechts neben dem Baum, nur Info)
        constPanel.setLayout(new BoxLayout(constPanel, BoxLayout.Y_AXIS));
        JPanel constWrapper = new JPanel(new BorderLayout());
        constWrapper.setBorder(javax.swing.BorderFactory.createTitledBorder("Kein MERGE für:"));
        constWrapper.add(new JScrollPane(constPanel), BorderLayout.CENTER);
        constWrapper.setPreferredSize(new Dimension(200, 0));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, constWrapper);
        splitPane.setResizeWeight(1.0);
        p.add(splitPane, BorderLayout.CENTER);

        diagramBtn.setEnabled(false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(backBtn);
        buttons.add(savePresetBtn);
        buttons.add(generateBtn);
        buttons.add(diagramBtn);
        p.add(buttons, BorderLayout.SOUTH);

        backBtn.addActionListener(e -> cards.show(cardPane, CARD_INPUT));
        generateBtn.addActionListener(e -> startGeneration());
        savePresetBtn.addActionListener(e -> saveCurrentPreset());
        diagramBtn.addActionListener(e -> {
            if (lastResult != null) {
                new DiagramDialog(
                    javax.swing.SwingUtilities.getWindowAncestor(this),
                    lastResult, ruleStore, constTableStore, seqStore
                ).setVisible(true);
            }
        });

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

        String alias = aliasField.getText().trim();
        presetStore.add(new QueryPreset(finalName, table, column, values, ruleStore.getAll(), alias));

        // 6. Combo aktualisieren und neues Preset auswählen
        refreshPresetCombo();
        presetCombo.setSelectedItem(finalName);
    }

    /**
     * Befüllt den Abhängigkeitsbaum mit dem TraversalResult und wechselt zur Tree-Karte.
     * Der Baum wird nach dem Befüllen vollständig aufgeklappt.
     * Anschließend wird die Info-Liste der Konstantentabellen aus dem Store aufgebaut.
     */
    private void showTreeCard(TraversalResult result) {
        DefaultMutableTreeNode root = buildTreeNodes(result.getRootNode());
        depTree.setModel(new DefaultTreeModel(root));
        expandAllNodes();
        diagramBtn.setEnabled(true);

        int total = result.getTotalRows();
        Map<String, Integer> counts = result.getTableCounts();
        treeInfo.setText("Gefunden: " + total + " Datensatz" + (total != 1 ? "e" : "")
                + " in " + counts.size() + " Tabelle" + (counts.size() != 1 ? "n" : ""));

        // Konstantentabellen-Info aufbauen (aus globalem Store)
        constPanel.removeAll();
        Set<String> constTables = constTableStore.getAsSet();
        String rootTable = result.getRootNode().getTableName();
        boolean anyExcluded = false;
        for (String table : counts.keySet()) {
            if (table.equalsIgnoreCase(rootTable)) continue;
            if (constTables.contains(table.toUpperCase())) {
                JLabel lbl = new JLabel("\u2717 " + table + " (" + counts.get(table) + ")");
                lbl.setForeground(new Color(180, 60, 60));
                lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
                constPanel.add(lbl);
                anyExcluded = true;
            }
        }
        if (!anyExcluded) {
            JLabel lbl = new JLabel("(keine)");
            lbl.setForeground(Color.GRAY);
            lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            constPanel.add(lbl);
        }
        constPanel.revalidate();
        constPanel.repaint();

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

        // Zusammenfassung oben
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultArea.setRows(6);
        JScrollPane summaryScroll = new JScrollPane(resultArea);
        summaryScroll.setPreferredSize(new Dimension(0, 120));

        // SQL-Vorschau mit Syntax-Highlighting
        sqlPreviewArea = new org.fife.ui.rsyntaxtextarea.RSyntaxTextArea();
        sqlPreviewArea.setSyntaxEditingStyle(org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_SQL);
        sqlPreviewArea.setEditable(false);
        sqlPreviewArea.setCodeFoldingEnabled(true);
        sqlPreviewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        org.fife.ui.rtextarea.RTextScrollPane sqlScroll = new org.fife.ui.rtextarea.RTextScrollPane(sqlPreviewArea);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, summaryScroll, sqlScroll);
        splitPane.setDividerLocation(120);
        splitPane.setResizeWeight(0.0);
        p.add(splitPane, BorderLayout.CENTER);

        // Buttons
        JButton backToTreeBtn = new JButton("← Zurück zum Baum");
        diffBtn.setVisible(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(backToTreeBtn);
        buttons.add(newBtn);
        buttons.add(diffBtn);
        p.add(buttons, BorderLayout.SOUTH);

        backToTreeBtn.addActionListener(e -> cards.show(cardPane, CARD_TREE));

        // "Neue Abfrage": Formular leeren, Ergebnis verwerfen, zurück zur Eingabe
        newBtn.addActionListener(e -> {
            resetInputCard();
            cards.show(cardPane, CARD_INPUT);
        });

        diffBtn.addActionListener(e -> {
            if (lastPreviousFile != null && lastGeneratedFile != null) {
                new DiffDialog(
                    SwingUtilities.getWindowAncestor(this),
                    java.nio.file.Paths.get(lastPreviousFile),
                    java.nio.file.Paths.get(lastGeneratedFile)
                ).setVisible(true);
            }
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

        // Konstantentabellen filtern
        Set<String> excludedTables = constTableStore.getAsSet();
        List<TableRow> filteredRows = filterConstantTables(lastResult.getOrderedRows(), excludedTables);
        Map<String, Integer> filteredCounts = countByTable(filteredRows);

        // Sequence-Mappings sammeln (Dialoge auf EDT)
        Map<String, String> sequenceMap = collectSequenceMappings(filteredRows);
        if (sequenceMap == null) return; // Benutzer hat abgebrochen

        generateBtn.setEnabled(false);
        backBtn.setEnabled(false);

        boolean finalIncludeUpdate = updateCheck.isSelected();

        // Per-Object-Daten vorbereiten
        List<List<TableRow>> perObjectRows = new ArrayList<>();
        List<Map<String, Integer>> perObjectCounts = new ArrayList<>();
        preparePerObjectData(excludedTables, perObjectRows, perObjectCounts);

        ScriptWriteContext ctx = new ScriptWriteContext(
            lastTable, lastIds, settingsPanel.getOutputDir(), sequenceMap,
            nameColumn, testSuffix, lastResult.getFkRelations(), finalIncludeUpdate,
            subselectStore, lastResult.getSubselectRows(), aliasField.getText().trim());

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
                ScriptWriter writer = new ScriptWriter();
                if (perObjectRows.size() > 1) {
                    return writer.writePerObject(perObjectRows, perObjectCounts, ctx);
                } else {
                    return writer.write(filteredRows, filteredCounts, ctx);
                }
            }

            @Override
            protected void done() {
                generateBtn.setEnabled(true);
                backBtn.setEnabled(true);
                try {
                    String filename = get();
                    int total = filteredRows.size();
                    String objInfo = perObjectRows.size() > 1
                        ? "Objekte:      " + perObjectRows.size() + "\n" : "";
                    resultArea.setText(
                        "Script erfolgreich erstellt!\n\n" +
                        "Datei:        " + filename + "\n" +
                        objInfo +
                        "Statements:   " + total + "\n" +
                        "Tabellen:     " + filteredCounts.size() + "\n\n" +
                        "Tabellenübersicht:\n" +
                        buildSummary(filteredCounts)
                    );

                    // SQL-Vorschau laden
                    try {
                        String sql = java.nio.file.Files.readString(
                            java.nio.file.Paths.get(filename));
                        sqlPreviewArea.setText(sql);
                        sqlPreviewArea.setCaretPosition(0);
                    } catch (Exception ignored) {
                        sqlPreviewArea.setText("(Script konnte nicht geladen werden)");
                    }

                    // Diff-Button: vorheriges Script suchen
                    lastGeneratedFile = filename;
                    lastPreviousFile = findPreviousScript(filename);
                    diffBtn.setVisible(lastPreviousFile != null);

                    cards.show(cardPane, CARD_RESULT);
                    if (seqMappingPanel != null) seqMappingPanel.reload();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(GeneratorPanel.this,
                        "Fehler bei der Generierung:\n" + rootCause(ex),
                        "Fehler", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    /** Filtert Zeilen von Konstantentabellen heraus. */
    private List<TableRow> filterConstantTables(List<TableRow> rows, Set<String> excludedTables) {
        List<TableRow> filtered = new ArrayList<>();
        for (TableRow row : rows) {
            if (!excludedTables.contains(row.getTableName().toUpperCase())) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    /** Zaehlt Zeilen pro Tabelle. */
    private Map<String, Integer> countByTable(List<TableRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TableRow row : rows) {
            counts.merge(row.getTableName(), 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Sammelt Sequence-Mappings fuer alle PK-Spalten.
     * Zeigt bei Bedarf Dialoge an. Gibt null zurueck wenn der Benutzer abbricht.
     */
    private Map<String, String> collectSequenceMappings(List<TableRow> filteredRows) {
        // Eindeutige Tabellen mit ihren PK-ColumnInfos sammeln
        Map<String, List<ColumnInfo>> tablePkMap = new LinkedHashMap<>();
        for (TableRow row : filteredRows) {
            String tbl = row.getTableName();
            if (!tablePkMap.containsKey(tbl)) {
                tablePkMap.put(tbl, row.getColumns().values().stream()
                    .filter(ColumnInfo::isPrimaryKey)
                    .collect(Collectors.toList()));
            }
        }

        Map<String, String> sequenceMap = new LinkedHashMap<>();

        // Trigger-Erkennung braucht DB-Verbindung
        SchemaAnalyzer triggerAnalyzer = null;
        DatabaseConnection triggerConn = null;
        java.io.PrintWriter triggerLogWriter = null;
        try {
            var config = settingsPanel.getCurrentConfig();
            triggerConn = new DatabaseConnection(config);
            triggerAnalyzer = new SchemaAnalyzer(triggerConn.get(), config);
            java.nio.file.Path logPath = java.nio.file.Paths.get("config", "mergegen", "traversal.log");
            java.nio.file.Files.createDirectories(logPath.getParent());
            triggerLogWriter = new java.io.PrintWriter(
                java.nio.file.Files.newBufferedWriter(logPath,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND));
            final java.io.PrintWriter logW = triggerLogWriter;
            triggerAnalyzer.setTriggerLog(msg -> { logW.println(msg); logW.flush(); });
        } catch (Exception ex) {
            // Trigger-Erkennung nicht moeglich – kein Abbruch
        }

        try {
            for (Map.Entry<String, List<ColumnInfo>> entry : tablePkMap.entrySet()) {
                String tbl = entry.getKey();
                for (ColumnInfo pkColInfo : entry.getValue()) {
                    String pkCol = pkColInfo.getName();

                    // FK-Spalte -> kein Sequence-Kandidat
                    boolean isFkColumn = lastResult.getFkRelations().values().stream()
                        .flatMap(List::stream)
                        .anyMatch(fk -> fk.getChildTable().equalsIgnoreCase(tbl)
                                     && fk.getFkColumn().equalsIgnoreCase(pkCol));
                    if (isFkColumn) continue;

                    // Datum/Timestamp -> kein Sequence-Kandidat
                    String dataType = pkColInfo.getDataType().toUpperCase();
                    if (dataType.equals("DATE") || dataType.startsWith("TIMESTAMP")) continue;

                    String key = tbl + "." + pkCol;

                    // 1. Im Store gespeichert? -> direkt uebernehmen
                    Optional<SequenceMapping> stored = seqStore.findByTable(tbl);
                    if (stored.isPresent() && stored.get().getPkColumn().equalsIgnoreCase(pkCol)
                            && !stored.get().getSequenceName().isEmpty()) {
                        sequenceMap.put(key, stored.get().getSequenceName());
                        continue;
                    }

                    // 2+3. STB_TABDEF / Trigger pruefen
                    String suggestion = "";
                    if (triggerAnalyzer != null) {
                        Optional<String> tabdefSeq = triggerAnalyzer.lookupSequenceFromTabdef(tbl);
                        if (tabdefSeq.isPresent()) suggestion = tabdefSeq.get();
                        if (suggestion.isEmpty()) {
                            Optional<String> triggerSeq = triggerAnalyzer.detectTriggerSequence(tbl);
                            if (triggerSeq.isPresent()) suggestion = triggerSeq.get();
                        }
                    }

                    // 4. Dialog anzeigen
                    String input = (String) JOptionPane.showInputDialog(this,
                        "Tabelle " + tbl + ", PK-Spalte " + pkCol +
                        "\n(leer = PK-Wert aus Quelle übernehmen)",
                        "Sequence-Name", JOptionPane.QUESTION_MESSAGE,
                        null, null, suggestion);

                    if (input == null) return null; // Abbruch

                    input = input.trim().toUpperCase();
                    if (!input.isEmpty()) {
                        sequenceMap.put(key, input);
                        seqStore.remove(tbl, pkCol);
                        seqStore.add(new SequenceMapping(tbl, pkCol, input));
                    }
                }
            }
        } finally {
            if (triggerConn != null) { try { triggerConn.close(); } catch (Exception ignored) {} }
            if (triggerLogWriter != null) triggerLogWriter.close();
        }
        return sequenceMap;
    }

    /** Bereitet die Per-Object-Daten fuer Multi-Objekt-Generierung vor. */
    private void preparePerObjectData(Set<String> excludedTables,
                                       List<List<TableRow>> perObjectRows,
                                       List<Map<String, Integer>> perObjectCounts) {
        if (lastResultsPerObject == null || lastResultsPerObject.size() <= 1) return;
        for (TraversalResult objResult : lastResultsPerObject) {
            List<TableRow> objFiltered = filterConstantTables(objResult.getOrderedRows(), excludedTables);
            perObjectRows.add(objFiltered);
            perObjectCounts.add(countByTable(objFiltered));
        }
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    /**
     * Sucht im selben Verzeichnis nach dem neuesten Script, das VOR dem aktuellen liegt.
     * @return Absoluter Pfad des vorherigen Scripts, oder null wenn keines existiert.
     */
    private String findPreviousScript(String currentFilePath) {
        try {
            java.nio.file.Path current = java.nio.file.Paths.get(currentFilePath);
            java.nio.file.Path dir = current.getParent();
            if (dir == null || !java.nio.file.Files.isDirectory(dir)) return null;

            String currentName = current.getFileName().toString();
            // Präfix bis zum Timestamp extrahieren (z.B. "MERGE_EMPLOYEES_")
            String prefix = currentName.replaceAll("\\d{8}_\\d{6}\\.sql$", "");

            return java.nio.file.Files.list(dir)
                .filter(p -> p.getFileName().toString().startsWith(prefix))
                .filter(p -> p.getFileName().toString().endsWith(".sql"))
                .filter(p -> !p.getFileName().toString().equals(currentName))
                .sorted(java.util.Comparator.comparing(
                    p -> ((java.nio.file.Path) p).getFileName().toString()).reversed())
                .findFirst()
                .map(p -> p.toAbsolutePath().toString())
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Leert alle Eingabefelder und setzt Preset- und Ergebnis-State zurück. */
    private void resetInputCard() {
        tableField.setText("");
        columnField.setText("");
        valueArea.setText("");
        testModeCheck.setSelected(false);
        updateCheck.setSelected(false);
        presetCombo.setSelectedIndex(0);
        lastResult = null;
        setInputStatus(" ");
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

    private static boolean isCausedBy(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return true;
        }
        return false;
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
                    TraversalService service  = new TraversalService(analyzer, virtualFkStore, ruleStore);
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
                    includeUpdate,
                    subselectStore,
                    lastResult.getSubselectRows(),
                    aliasField.getText().trim());
            }

            @Override
            protected void done() {
                try { get(); onComplete.accept(true); }
                catch (Exception ex) { onComplete.accept(false); }
            }
        }.execute();
    }

    /**
     * Wird aus dem Hintergrundthread aufgerufen (TraversalDecider-Callback).
     * Zeigt einen Dialog auf dem EDT mit 3 Optionen und wartet auf die Antwort.
     * Bei "Entscheidung merken" wird die Regel im Store gespeichert.
     */
    private TraversalService.TraversalDecision askTraversalDecisionEnum(
            String parentTable, String childTable, String fkColumn, int rowCount) {
        final TraversalService.TraversalDecision[] result = {TraversalService.TraversalDecision.TRAVERSE};
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                Object[] message = {
                    "FK-Beziehung gefunden:",
                    parentTable + " \u2192 " + childTable + "." + fkColumn,
                    rowCount + " Zeile(n) gefunden.",
                    " ",
                    "Wie soll diese Beziehung behandelt werden?",
                    "(Entscheidung wird beim Preset-Speichern gesichert)"
                };
                String[] options = {"Traversieren", "Ueberspringen", "Subselect"};
                int choice = JOptionPane.showOptionDialog(
                    GeneratorPanel.this, message,
                    "Traversal-Entscheidung",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);

                TraversalRuleStore.TraversalRule rule;
                if (choice == JOptionPane.CLOSED_OPTION) {
                    result[0] = TraversalService.TraversalDecision.CANCEL;
                    return;
                }
                if (choice == 2) {
                    // Subselect: Spaltenauswahl-Dialog anzeigen
                    boolean columnsSelected = askSubselectColumns(childTable);
                    if (columnsSelected) {
                        result[0] = TraversalService.TraversalDecision.SUBSELECT;
                        rule = TraversalRuleStore.TraversalRule.SUBSELECT;
                    } else {
                        // Cancel im Spalten-Dialog -> Fallback auf SKIP
                        result[0] = TraversalService.TraversalDecision.SKIP;
                        rule = TraversalRuleStore.TraversalRule.SKIP;
                    }
                } else if (choice == 1) {
                    result[0] = TraversalService.TraversalDecision.SKIP;
                    rule = TraversalRuleStore.TraversalRule.SKIP;
                } else {
                    result[0] = TraversalService.TraversalDecision.TRAVERSE;
                    rule = TraversalRuleStore.TraversalRule.TRAVERSE;
                }
                ruleStore.setRule(parentTable, childTable, fkColumn, rule);
            });
        } catch (Exception e) {
            // Bei Fehler: traversieren
        }
        return result[0];
    }

    /**
     * Zeigt einen Dialog zur Auswahl der Lookup-Spalten fuer das Subselect.
     * @return true wenn Spalten ausgewaehlt wurden, false bei Cancel/Fehler
     */
    private boolean askSubselectColumns(String table) {
        try {
            var config = settingsPanel.getCurrentConfig();
            try (DatabaseConnection conn = new DatabaseConnection(config)) {
                SchemaAnalyzer tempAnalyzer = new SchemaAnalyzer(conn.get(), config);
                List<String> pkCols = tempAnalyzer.getPrimaryKeyColumns(table);
                List<ColumnInfo> allCols = tempAnalyzer.getColumns(table, pkCols);

                List<String> candidates = new java.util.ArrayList<>();
                for (ColumnInfo col : allCols) {
                    if (!col.isPrimaryKey()) candidates.add(col.getName());
                }

                if (candidates.isEmpty()) {
                    JOptionPane.showMessageDialog(this,
                        "Keine Nicht-PK-Spalten in " + table + " gefunden.",
                        "Subselect", JOptionPane.WARNING_MESSAGE);
                    return false;
                }

                JList<String> colList = new JList<>(candidates.toArray(new String[0]));
                colList.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
                colList.setSelectedIndex(0);
                JScrollPane scrollPane = new JScrollPane(colList);
                scrollPane.setPreferredSize(new java.awt.Dimension(300, 200));

                Object[] dialogContent = {
                    "Lookup-Spalte(n) fuer Subselect auf " + table + " waehlen:",
                    "Diese Spalten werden im WHERE des Subselects verwendet.",
                    scrollPane
                };

                int ok = JOptionPane.showConfirmDialog(this, dialogContent,
                    "Subselect-Spalten", JOptionPane.OK_CANCEL_OPTION);

                if (ok == JOptionPane.OK_OPTION && !colList.isSelectionEmpty()) {
                    List<String> selectedCols = colList.getSelectedValuesList();
                    String pkCol = pkCols.isEmpty() ? "ID" : pkCols.get(0);
                    subselectStore.add(table, pkCol, selectedCols);
                    return true;
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Fehler beim Laden der Spalten: " + e.getMessage(),
                "Subselect", JOptionPane.ERROR_MESSAGE);
        }
        return false;
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
