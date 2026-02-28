package com.kostenattribute;

import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.KeyboardFocusManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import com.mergegen.config.AppSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * INSERT-Generator-Panel: generisches Tool fuer beliebige Zieltabellen.
 * Preset-basierte Persistenz unter config/insertgen/.
 */
public class InsertGenPanel extends JPanel {

    private static final Color DIRTY_COLOR = new Color(70, 130, 210);
    private static final Color COL_SELECT  = new Color(184, 207, 229);
    private static final Color PK_COLOR    = new Color(255, 223, 120);
    private static final Color FK_COLOR    = new Color(180, 220, 255);

    private final InsertGenService service = new InsertGenService();

    private DefaultTableModel model;
    private JTable table;
    private JTable rowHeader;
    private JScrollPane scrollPane;
    private JLabel rowCountLabel;

    private JComboBox<String> presetCombo;
    private JTextField tableNameField;
    private JComboBox<String> pkCombo;
    private JTextField sequenceField;
    private JButton savePresetBtn;
    private JButton deletePresetBtn;
    private JButton saveBtn;

    private boolean dirty = false;
    private int[] selectedViewCol = {-1};
    private final java.util.Map<String, String> fkSubselects = new java.util.LinkedHashMap<>();

    private Runnable resizeRowHeader;
    private Runnable updateRowCount;

    public InsertGenPanel() {
        setLayout(new BorderLayout());

        // ── Tabelle (initial leer, keine Spalten) ───────────────────────────
        model = new DefaultTableModel(new String[0], 0) {
            @Override public boolean isCellEditable(int row, int col) { return true; }
        };

        table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionBackground(table.getBackground());
        table.setSelectionForeground(table.getForeground());

        // ── Spalten-Auswahl (Header-Klick) fuer Verschieben ─────────────────
        JButton moveLeftBtn  = new JButton("\u25C0");
        JButton moveRightBtn = new JButton("\u25B6");
        moveLeftBtn.setEnabled(false);
        moveRightBtn.setEnabled(false);

        TableCellRenderer defaultHeaderRenderer = table.getTableHeader().getDefaultRenderer();
        table.getTableHeader().setDefaultRenderer((tbl, value, isSel, hasFocus, row, col) -> {
            Component c = defaultHeaderRenderer.getTableCellRendererComponent(
                    tbl, value, isSel, hasFocus, row, col);
            String headerName = value != null ? value.toString() : "";
            String pkCol = (String) pkCombo.getSelectedItem();
            boolean isPk = pkCol != null && !"(keine)".equals(pkCol) && pkCol.equals(headerName);
            boolean isFk = fkSubselects.containsKey(headerName);
            if (col == selectedViewCol[0]) {
                c.setBackground(COL_SELECT);
            } else if (isPk) {
                c.setBackground(PK_COLOR);
                c.setFont(c.getFont().deriveFont(Font.BOLD));
            } else if (isFk) {
                c.setBackground(FK_COLOR);
                c.setFont(c.getFont().deriveFont(Font.ITALIC));
            } else {
                c.setBackground(UIManager.getColor("TableHeader.background"));
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
            }
            return c;
        });

        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int col = table.columnAtPoint(e.getPoint());
                if (col < 0) return;
                String colName = table.getColumnModel().getColumn(col).getHeaderValue().toString();
                JPopupMenu popup = new JPopupMenu();
                boolean hasFk = fkSubselects.containsKey(colName);
                JMenuItem fkItem = new JMenuItem(hasFk ? "FK-Subselect bearbeiten" : "FK mit Subselect");
                fkItem.addActionListener(a -> showFkDialog(colName));
                popup.add(fkItem);
                if (hasFk) {
                    JMenuItem removeItem = new JMenuItem("FK-Subselect entfernen");
                    removeItem.addActionListener(a -> {
                        fkSubselects.remove(colName);
                        table.getTableHeader().repaint();
                        markDirty();
                    });
                    popup.add(removeItem);
                }
                popup.show(table.getTableHeader(), e.getX(), e.getY());
            }
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.isPopupTrigger()) return;
                int col = table.columnAtPoint(e.getPoint());
                if (e.getClickCount() == 2) {
                    renameColumn(col);
                    return;
                }
                selectedViewCol[0] = col;
                moveLeftBtn.setEnabled(col > 0);
                moveRightBtn.setEnabled(col < table.getColumnCount() - 1);
                table.getTableHeader().repaint();
            }
        });

        moveLeftBtn.addActionListener(e -> {
            int col = selectedViewCol[0];
            if (col <= 0) return;
            table.getColumnModel().moveColumn(col, col - 1);
            selectedViewCol[0] = col - 1;
            moveLeftBtn.setEnabled(col - 1 > 0);
            moveRightBtn.setEnabled(true);
            table.getTableHeader().repaint();
        });

        moveRightBtn.addActionListener(e -> {
            int col = selectedViewCol[0];
            if (col < 0 || col >= table.getColumnCount() - 1) return;
            table.getColumnModel().moveColumn(col, col + 1);
            selectedViewCol[0] = col + 1;
            moveLeftBtn.setEnabled(true);
            moveRightBtn.setEnabled(col + 1 < table.getColumnCount() - 1);
            table.getTableHeader().repaint();
        });

        // ── Aenderungen erkennen ────────────────────────────────────────────
        model.addTableModelListener(e -> {
            if (e.getType() != TableModelEvent.UPDATE) return;
            if (!dirty) markDirty();
            int modelCol = e.getColumn();
            if (modelCol >= 0) {
                int viewCol = table.convertColumnIndexToView(modelCol);
                if (viewCol >= 0) resizeColumn(viewCol);
            }
        });

        // ── Ctrl+V: Excel-Inhalt einfuegen ──────────────────────────────────
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED
                    && e.getKeyCode() == KeyEvent.VK_V
                    && e.isControlDown()
                    && isShowing()) {
                // Nicht abfangen wenn Cursor in einem Textfeld steht
                Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focused instanceof JTextField) return false;
                pasteFromClipboard();
                return true;
            }
            return false;
        });

        // ── Preset-Leiste ───────────────────────────────────────────────────
        presetCombo = new JComboBox<>();
        presetCombo.setPreferredSize(new Dimension(200, 26));
        refreshPresetCombo();
        presetCombo.addItemListener(e -> {
            if (e.getStateChange() != java.awt.event.ItemEvent.SELECTED) return;
            String selected = (String) presetCombo.getSelectedItem();
            if (selected == null || "(kein Preset)".equals(selected)) {
                clearTable();
                tableNameField.setText("");
                return;
            }
            loadPreset(selected);
        });

        savePresetBtn = new JButton("Preset speichern");
        savePresetBtn.addActionListener(e -> saveCurrentPreset());

        deletePresetBtn = new JButton("Preset l\u00F6schen");
        deletePresetBtn.addActionListener(e -> deleteCurrentPreset());

        tableNameField = new JTextField(20);
        tableNameField.setToolTipText("Name der Zieltabelle (z.B. MY_TABLE)");

        pkCombo = new JComboBox<>();
        pkCombo.setPreferredSize(new Dimension(150, 26));
        pkCombo.addItem("(keine)");
        pkCombo.addItemListener(e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED)
                table.getTableHeader().repaint();
        });

        sequenceField = new JTextField(15);
        sequenceField.setToolTipText("Sequence-Name (z.B. MY_SEQ)");

        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        presetBar.add(new JLabel("Preset:"));
        presetBar.add(presetCombo);
        presetBar.add(savePresetBtn);
        presetBar.add(deletePresetBtn);
        presetBar.add(Box.createHorizontalStrut(12));
        presetBar.add(new JLabel("Zieltabelle:"));
        presetBar.add(tableNameField);
        presetBar.add(Box.createHorizontalStrut(12));
        presetBar.add(new JLabel("PK-Spalte:"));
        presetBar.add(pkCombo);
        presetBar.add(new JLabel("Sequence:"));
        presetBar.add(sequenceField);

        // ── Toolbar ─────────────────────────────────────────────────────────
        saveBtn = new JButton("Speichern");
        saveBtn.addActionListener(e -> saveData());

        JButton addColBtn = new JButton("\u2795");
        addColBtn.setFont(addColBtn.getFont().deriveFont(Font.BOLD, 13f));
        addColBtn.setToolTipText("Neue Spalte hinzuf\u00FCgen");
        addColBtn.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this,
                    "Name der neuen Spalte:", "Spalte hinzuf\u00FCgen",
                    JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.isBlank()) return;
            model.addColumn(name.trim());
            selectedViewCol[0] = -1;
            moveLeftBtn.setEnabled(false);
            moveRightBtn.setEnabled(false);
            autoResizeColumns();
            refreshPkCombo();
            markDirty();
        });

        JButton addRowBtn = new JButton("+ Zeile");
        addRowBtn.addActionListener(e -> {
            model.addRow(new Object[model.getColumnCount()]);
            markDirty();
        });

        JButton clearAllBtn = new JButton("Tabelle leeren");
        clearAllBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Gesamten Tabelleninhalt l\u00F6schen?", "Best\u00E4tigen",
                    JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                model.setRowCount(0);
                markDirty();
            }
        });

        JButton deleteRowsBtn = new JButton("Zeilen l\u00F6schen");
        deleteRowsBtn.addActionListener(e -> {
            int[] selected = table.getSelectedRows();
            if (selected.length == 0) return;
            for (int i = selected.length - 1; i >= 0; i--)
                model.removeRow(selected[i]);
            markDirty();
        });

        JButton deleteColBtn = new JButton("Spalte l\u00F6schen");
        deleteColBtn.addActionListener(e -> {
            if (selectedViewCol[0] < 0 || selectedViewCol[0] >= table.getColumnCount()) return;
            String colName = table.getColumnModel().getColumn(selectedViewCol[0])
                    .getHeaderValue().toString();
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Spalte \"" + colName + "\" l\u00F6schen?", "Best\u00E4tigen",
                    JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
            fkSubselects.remove(colName);
            int modelCol = table.convertColumnIndexToModel(selectedViewCol[0]);
            removeColumn(modelCol);
            selectedViewCol[0] = -1;
            moveLeftBtn.setEnabled(false);
            moveRightBtn.setEnabled(false);
            refreshPkCombo();
            markDirty();
        });

        JButton generateBtn = new JButton("Script generieren");
        generateBtn.addActionListener(e -> generateScript());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(saveBtn);
        toolbar.add(clearAllBtn);
        toolbar.add(addRowBtn);
        toolbar.add(deleteRowsBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(moveLeftBtn);
        toolbar.add(moveRightBtn);
        toolbar.add(addColBtn);
        toolbar.add(deleteColBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(generateBtn);

        // ── Top-Panel (Preset + Toolbar) ────────────────────────────────────
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(presetBar);
        topPanel.add(toolbar);

        // ── ScrollPane ──────────────────────────────────────────────────────
        scrollPane = new JScrollPane(table,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);

        // ── Zeilennummerierung (Row-Header) ─────────────────────────────────
        rowHeader = new JTable(new AbstractTableModel() {
            @Override public int getRowCount() { return model.getRowCount(); }
            @Override public int getColumnCount() { return 1; }
            @Override public Object getValueAt(int row, int col) { return row + 1; }
            @Override public String getColumnName(int col) { return "#"; }
            @Override public boolean isCellEditable(int row, int col) { return false; }
        });
        rowHeader.setSelectionModel(table.getSelectionModel());
        rowHeader.setEnabled(false);
        rowHeader.setFont(rowHeader.getFont().deriveFont(Font.PLAIN, 11f));
        rowHeader.setBackground(UIManager.getColor("TableHeader.background"));

        resizeRowHeader = () -> {
            String maxLabel = String.valueOf(Math.max(1, model.getRowCount()));
            FontMetrics fm = rowHeader.getFontMetrics(rowHeader.getFont());
            int w = fm.stringWidth(maxLabel) + 16;
            rowHeader.getColumnModel().getColumn(0).setPreferredWidth(w);
            rowHeader.setPreferredScrollableViewportSize(new Dimension(w, 0));
        };
        resizeRowHeader.run();

        model.addTableModelListener(e -> { rowHeader.revalidate(); resizeRowHeader.run(); });
        scrollPane.setRowHeaderView(rowHeader);

        // ── Zeilenanzahl-Anzeige (nur gefuellte Zeilen) ─────────────────────
        rowCountLabel = new JLabel();
        updateRowCount = () -> {
            int filled = 0;
            for (int r = 0; r < model.getRowCount(); r++) {
                for (int c = 0; c < model.getColumnCount(); c++) {
                    Object v = model.getValueAt(r, c);
                    if (v != null && !v.toString().isEmpty()) { filled++; break; }
                }
            }
            rowCountLabel.setText("  Zeilen: " + filled);
        };
        updateRowCount.run();
        model.addTableModelListener(e -> updateRowCount.run());

        // Legende
        JPanel pkLegend = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JLabel pkColorBox = new JLabel("  ") {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(PK_COLOR);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.GRAY);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
        };
        pkColorBox.setPreferredSize(new Dimension(14, 14));
        pkLegend.add(pkColorBox);
        pkLegend.add(new JLabel("PK-Spalte"));

        JPanel fkLegend = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JLabel fkColorBox = new JLabel("  ") {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(FK_COLOR);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.GRAY);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
        };
        fkColorBox.setPreferredSize(new Dimension(14, 14));
        fkLegend.add(fkColorBox);
        fkLegend.add(new JLabel("FK-Subselect"));

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        statusBar.add(rowCountLabel);
        statusBar.add(pkLegend);
        statusBar.add(fkLegend);

        // ── Layout ──────────────────────────────────────────────────────────
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(topPanel,   BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusBar,  BorderLayout.SOUTH);
    }

    // ── Preset-Verwaltung ───────────────────────────────────────────────────

    private void refreshPresetCombo() {
        java.awt.event.ItemListener[] listeners = presetCombo.getItemListeners();
        for (java.awt.event.ItemListener l : listeners) presetCombo.removeItemListener(l);

        presetCombo.removeAllItems();
        presetCombo.addItem("(kein Preset)");
        service.listPresets().forEach(presetCombo::addItem);

        for (java.awt.event.ItemListener l : listeners) presetCombo.addItemListener(l);
    }

    private void loadPreset(String presetName) {
        try {
            InsertGenService.PresetData data = service.loadPreset(presetName);
            if (data == null) return;

            tableNameField.setText(data.tableName);

            // Tabelle komplett neu aufbauen
            model.setRowCount(0);
            model.setColumnCount(0);
            for (String col : data.columnNames) model.addColumn(col);
            for (String[] row : data.rows) model.addRow(row);

            refreshPkCombo();
            if (data.pkColumn != null && !data.pkColumn.isEmpty()) {
                pkCombo.setSelectedItem(data.pkColumn);
            } else {
                pkCombo.setSelectedIndex(0);
            }
            sequenceField.setText(data.sequenceName != null ? data.sequenceName : "");

            fkSubselects.clear();
            fkSubselects.putAll(data.fkSubselects);

            selectedViewCol[0] = -1;
            autoResizeColumns();
            clearDirty();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Fehler beim Laden: " + e.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void clearTable() {
        model.setRowCount(0);
        model.setColumnCount(0);
        selectedViewCol[0] = -1;
        refreshPkCombo();
        sequenceField.setText("");
        fkSubselects.clear();
        clearDirty();
    }

    private void refreshPkCombo() {
        String current = (String) pkCombo.getSelectedItem();
        java.awt.event.ItemListener[] listeners = pkCombo.getItemListeners();
        for (java.awt.event.ItemListener l : listeners) pkCombo.removeItemListener(l);

        pkCombo.removeAllItems();
        pkCombo.addItem("(keine)");
        for (int c = 0; c < table.getColumnCount(); c++)
            pkCombo.addItem(table.getColumnModel().getColumn(c).getHeaderValue().toString());

        if (current != null) pkCombo.setSelectedItem(current);
        if (pkCombo.getSelectedIndex() < 0) pkCombo.setSelectedIndex(0);

        for (java.awt.event.ItemListener l : listeners) pkCombo.addItemListener(l);
        table.getTableHeader().repaint();
    }

    private void saveCurrentPreset() {
        if (tableNameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Bitte Zieltabelle eingeben.",
                    "Fehler", JOptionPane.WARNING_MESSAGE);
            tableNameField.requestFocus();
            return;
        }
        String currentPreset = (String) presetCombo.getSelectedItem();
        String suggestion = (currentPreset != null && !"(kein Preset)".equals(currentPreset))
                ? currentPreset
                : tableNameField.getText().trim();

        String name = (String) JOptionPane.showInputDialog(this,
                "Preset-Name:", "Preset speichern",
                JOptionPane.PLAIN_MESSAGE, null, null, suggestion);
        if (name == null || name.isBlank()) return;
        final String presetName = name.trim();

        // Ueberschreiben?
        if (service.listPresets().stream().anyMatch(p -> p.equalsIgnoreCase(presetName))) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Preset \"" + presetName + "\" existiert bereits. \u00DCberschreiben?",
                    "Best\u00E4tigen", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
        }

        String tableName = tableNameField.getText().trim();
        String[] colNames = getViewColumnNames();
        List<String[]> rows = getViewRows();
        String pk = getSelectedPk();
        String seq = sequenceField.getText().trim();

        try {
            service.savePreset(presetName, tableName, colNames, rows, pk, seq, fkSubselects);
            refreshPresetCombo();
            presetCombo.setSelectedItem(presetName);
            clearDirty();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Fehler beim Speichern: " + e.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteCurrentPreset() {
        String selected = (String) presetCombo.getSelectedItem();
        if (selected == null || "(kein Preset)".equals(selected)) return;

        int confirm = JOptionPane.showConfirmDialog(this,
                "Preset \"" + selected + "\" l\u00F6schen?",
                "Best\u00E4tigen", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        try {
            service.deletePreset(selected);
            refreshPresetCombo();
            clearTable();
            tableNameField.setText("");
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Fehler beim L\u00F6schen: " + e.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Spalte umbenennen (Doppelklick auf Header) ──────────────────────────

    private void renameColumn(int viewCol) {
        String current = table.getColumnModel().getColumn(viewCol).getHeaderValue().toString();
        String newName = (String) JOptionPane.showInputDialog(this,
                "Neuer Spaltenname:", "Spalte umbenennen",
                JOptionPane.PLAIN_MESSAGE, null, null, current);
        if (newName == null || newName.isBlank() || newName.equals(current)) return;
        String trimmed = newName.trim();
        table.getColumnModel().getColumn(viewCol).setHeaderValue(trimmed);
        // FK-Subselect unter neuem Key speichern
        String fk = fkSubselects.remove(current);
        if (fk != null) fkSubselects.put(trimmed, fk);
        // PK-Combo aktualisieren
        refreshPkCombo();
        table.getTableHeader().repaint();
        markDirty();
    }

    // ── FK-Subselect-Dialog ────────────────────────────────────────────────

    private void showFkDialog(String colName) {
        String current = fkSubselects.getOrDefault(colName, "");
        JTextArea textArea = new JTextArea(current, 5, 40);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(textArea);
        sp.setPreferredSize(new Dimension(450, 120));

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("Subselect f\u00FCr Spalte \"" + colName + "\":"), BorderLayout.NORTH);
        panel.add(sp, BorderLayout.CENTER);
        panel.add(new JLabel("<html>Platzhalter <b>{WERT}</b> wird durch den Zellwert ersetzt (automatisch gequotet).<br>z.B.: SELECT ID FROM OTHER_TABLE WHERE NAME = {WERT}</html>"), BorderLayout.SOUTH);

        int result = JOptionPane.showConfirmDialog(this, panel,
                "FK mit Subselect", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String subselect = textArea.getText().trim();
        if (subselect.isEmpty()) {
            fkSubselects.remove(colName);
        } else {
            fkSubselects.put(colName, subselect);
        }
        table.getTableHeader().repaint();
        markDirty();
    }

    // ── Spalte entfernen ────────────────────────────────────────────────────

    private void removeColumn(int modelCol) {
        int colCount = model.getColumnCount();
        int rowCount = model.getRowCount();

        // Spaltennamen und Daten ohne die geloeschte Spalte sammeln
        String[] newCols = new String[colCount - 1];
        int idx = 0;
        for (int c = 0; c < colCount; c++) {
            if (c == modelCol) continue;
            newCols[idx++] = model.getColumnName(c);
        }

        List<String[]> rows = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            String[] row = new String[colCount - 1];
            idx = 0;
            for (int c = 0; c < colCount; c++) {
                if (c == modelCol) continue;
                Object v = model.getValueAt(r, c);
                row[idx++] = v == null ? "" : v.toString();
            }
            rows.add(row);
        }

        model.setRowCount(0);
        model.setColumnCount(0);
        for (String col : newCols) model.addColumn(col);
        for (String[] row : rows) model.addRow(row);
        autoResizeColumns();
    }

    // ── Dirty-Zustand ───────────────────────────────────────────────────────

    private void markDirty() {
        dirty = true;
        saveBtn.setBackground(DIRTY_COLOR);
        saveBtn.setForeground(Color.BLACK);
        saveBtn.setOpaque(true);
        saveBtn.setBorderPainted(false);
    }

    private void clearDirty() {
        dirty = false;
        saveBtn.setBackground(null);
        saveBtn.setForeground(null);
        saveBtn.setOpaque(false);
        saveBtn.setBorderPainted(true);
    }

    // ── Persistenz (Speichern = aktuelles Preset ueberschreiben) ────────────

    private void saveData() {
        if (tableNameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Bitte Zieltabelle eingeben.",
                    "Fehler", JOptionPane.WARNING_MESSAGE);
            tableNameField.requestFocus();
            return;
        }
        String currentPreset = (String) presetCombo.getSelectedItem();
        if (currentPreset == null || "(kein Preset)".equals(currentPreset)) {
            saveCurrentPreset();
            return;
        }
        String tableName = tableNameField.getText().trim();
        String[] colNames = getViewColumnNames();
        List<String[]> rows = getViewRows();
        String pk = getSelectedPk();
        String seq = sequenceField.getText().trim();

        try {
            service.savePreset(currentPreset, tableName, colNames, rows, pk, seq, fkSubselects);
            clearDirty();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Fehler beim Speichern: " + e.getMessage());
        }
    }

    // ── Paste ───────────────────────────────────────────────────────────────

    private void pasteFromClipboard() {
        try {
            String text = (String) Toolkit.getDefaultToolkit()
                    .getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (text == null || text.isBlank()) return;

            String[] lines = text.split("\n");
            // Leere Trailing-Zeilen entfernen
            int lineCount = lines.length;
            while (lineCount > 0 && lines[lineCount - 1].isBlank()) lineCount--;
            if (lineCount == 0) return;

            // Spaltenanzahl aus Clipboard ermitteln
            int clipCols = 0;
            for (int i = 0; i < lineCount; i++) {
                int cols = lines[i].split("\t", -1).length;
                clipCols = Math.max(clipCols, cols);
            }

            // Wenn Tabelle leer: kompletten Inhalt einfuegen mit Header-Frage
            if (model.getColumnCount() == 0 || (model.getRowCount() == 0 && table.getSelectedRow() < 0)) {
                boolean firstIsHeader = JOptionPane.showConfirmDialog(this,
                        "Ist die erste Zeile die Spalten\u00FCberschrift?",
                        "Einf\u00FCgen", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;

                model.setRowCount(0);
                model.setColumnCount(0);

                int dataStart;
                if (firstIsHeader) {
                    String[] headerCells = lines[0].split("\t", -1);
                    for (String h : headerCells) model.addColumn(h.stripTrailing());
                    dataStart = 1;
                } else {
                    for (int c = 0; c < clipCols; c++) model.addColumn("Spalte_" + (c + 1));
                    dataStart = 0;
                }

                for (int r = dataStart; r < lineCount; r++) {
                    String[] cells = lines[r].split("\t", -1);
                    Object[] row = new Object[model.getColumnCount()];
                    for (int c = 0; c < Math.min(cells.length, row.length); c++)
                        row[c] = cells[c].stripTrailing();
                    model.addRow(row);
                }

                selectedViewCol[0] = -1;
                autoResizeColumns();
                refreshPkCombo();
                markDirty();
                return;
            }

            // Sonst: in bestehende Tabelle ab Cursor einfuegen
            int startRow = Math.max(0, table.getSelectedRow());
            int startCol = Math.max(0, table.getSelectedColumn());

            for (int r = 0; r < lineCount; r++) {
                String[] cells = lines[r].split("\t", -1);
                int targetRow = startRow + r;
                while (targetRow >= model.getRowCount())
                    model.addRow(new Object[model.getColumnCount()]);
                for (int c = 0; c < cells.length; c++) {
                    int targetCol = startCol + c;
                    if (targetCol < model.getColumnCount())
                        model.setValueAt(cells[c].stripTrailing(), targetRow, targetCol);
                }
            }
            autoResizeColumns();
            markDirty();
        } catch (Exception ignored) { }
    }

    // ── Script-Generierung ──────────────────────────────────────────────────

    private void generateScript() {
        String tableName = tableNameField.getText().trim();
        if (tableName.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Bitte Zieltabelle eingeben.",
                    "Fehler", JOptionPane.WARNING_MESSAGE);
            tableNameField.requestFocus();
            return;
        }
        if (table.getColumnCount() == 0) {
            JOptionPane.showMessageDialog(this,
                    "Keine Spalten vorhanden.",
                    "Fehler", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> colNames = new ArrayList<>();
        for (int c = 0; c < table.getColumnCount(); c++)
            colNames.add(table.getColumnModel().getColumn(c).getHeaderValue().toString());

        List<String[]> rows = getViewRows();

        String pk = getSelectedPk();
        String seq = sequenceField.getText().trim();
        InsertGenService.ScriptResult result = service.buildScript(tableName, colNames, rows, pk, seq, fkSubselects);

        if (result.insertCount == 0) {
            JOptionPane.showMessageDialog(this,
                    "Keine Daten vorhanden \u2013 Script enth\u00E4lt nur DELETE + COMMIT.",
                    "Hinweis", JOptionPane.INFORMATION_MESSAGE);
        }

        String outputDir = new AppSettings().getOutputDir();
        try {
            Path sqlFile = service.writeScript(result.sql, outputDir, tableName);
            JOptionPane.showMessageDialog(this,
                    result.insertCount + " INSERT(s) generiert.\nDatei: " + sqlFile.toAbsolutePath(),
                    "Script gespeichert", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Fehler beim Schreiben: " + ex.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Hilfsmethoden ───────────────────────────────────────────────────────

    private String[] getViewColumnNames() {
        int colCount = table.getColumnCount();
        String[] names = new String[colCount];
        for (int c = 0; c < colCount; c++)
            names[c] = table.getColumnModel().getColumn(c).getHeaderValue().toString();
        return names;
    }

    private String getSelectedPk() {
        String pk = (String) pkCombo.getSelectedItem();
        return (pk != null && !"(keine)".equals(pk)) ? pk : "";
    }

    private List<String[]> getViewRows() {
        List<String[]> rows = new ArrayList<>();
        int colCount = table.getColumnCount();
        for (int r = 0; r < table.getRowCount(); r++) {
            String[] row = new String[colCount];
            for (int c = 0; c < colCount; c++) {
                Object val = table.getValueAt(r, c);
                row[c] = val == null ? "" : val.toString();
            }
            rows.add(row);
        }
        return rows;
    }

    private void autoResizeColumns() {
        for (int col = 0; col < table.getColumnCount(); col++)
            resizeColumn(col);
    }

    private void resizeColumn(int viewCol) {
        TableColumn column = table.getColumnModel().getColumn(viewCol);
        int width = table.getTableHeader().getDefaultRenderer()
                .getTableCellRendererComponent(table,
                        column.getHeaderValue(), false, false, -1, viewCol)
                .getPreferredSize().width + 8;
        for (int row = 0; row < table.getRowCount(); row++) {
            Object value = table.getValueAt(row, viewCol);
            if (value == null || value.toString().isEmpty()) continue;
            int cellWidth = table.getDefaultRenderer(Object.class)
                    .getTableCellRendererComponent(table, value, false, false, row, viewCol)
                    .getPreferredSize().width + 8;
            width = Math.max(width, cellWidth);
        }
        column.setPreferredWidth(width);
    }
}
