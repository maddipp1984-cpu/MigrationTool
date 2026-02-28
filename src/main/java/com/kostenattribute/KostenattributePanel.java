package com.kostenattribute;

import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import com.mergegen.config.AppSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * Haupt-Panel für den Kostenattribute-Generator (Migration SUEWAG).
 * Wird vom Launcher als Card eingebettet.
 * Persistenz: kostenattribute-data.csv im Arbeitsverzeichnis (Semikolon-getrennt).
 * Spaltennamen sind dynamisch: über den +-Button ergänzbar,
 * per Doppelklick auf den Header umbenennbar.
 */
public class KostenattributePanel extends JPanel {

    private static final Color DARK_BLUE  = new Color(0, 51, 153);
    private static final Color COL_SELECT = new Color(184, 207, 229);

    private final KostenattributeService service = new KostenattributeService();

    private boolean dirty = false;
    private JButton saveBtn;

    public KostenattributePanel() {
        setLayout(new BorderLayout());

        // ── Daten laden ───────────────────────────────────────────────────────
        KostenattributeService.LoadResult loaded;
        try {
            loaded = service.load();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Fehler beim Laden: " + e.getMessage());
            loaded = new KostenattributeService.LoadResult(
                    KostenattributeService.DEFAULT_COLUMNS.clone(), new ArrayList<>());
        }

        DefaultTableModel model = new DefaultTableModel(loaded.columnNames, 0) {
            @Override public boolean isCellEditable(int row, int col) { return true; }
        };
        for (String[] row : loaded.rows) model.addRow(row);

        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionBackground(table.getBackground());
        table.setSelectionForeground(table.getForeground());

        // ── Spalten-Auswahl (Header-Klick) für Verschieben ───────────────────
        int[] selectedViewCol = {-1};

        JButton moveLeftBtn  = new JButton("◀");
        JButton moveRightBtn = new JButton("▶");
        moveLeftBtn.setEnabled(false);
        moveRightBtn.setEnabled(false);

        TableCellRenderer defaultHeaderRenderer = table.getTableHeader().getDefaultRenderer();
        table.getTableHeader().setDefaultRenderer((tbl, value, isSel, hasFocus, row, col) -> {
            Component c = defaultHeaderRenderer.getTableCellRendererComponent(
                    tbl, value, isSel, hasFocus, row, col);
            c.setBackground(col == selectedViewCol[0]
                    ? COL_SELECT
                    : UIManager.getColor("TableHeader.background"));
            return c;
        });

        table.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int col = table.columnAtPoint(e.getPoint());
                if (e.getClickCount() == 2) {
                    renameColumn(table, col);
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

        // ── Änderungen erkennen → dirty + Spalte anpassen ────────────────────
        model.addTableModelListener(e -> {
            if (e.getType() != TableModelEvent.UPDATE) return;
            if (!dirty) markDirty();
            int modelCol = e.getColumn();
            if (modelCol >= 0) {
                int viewCol = table.convertColumnIndexToView(modelCol);
                if (viewCol >= 0) resizeColumn(table, viewCol);
            }
        });

        // ── Ctrl+V: Excel-Inhalt einfügen ─────────────────────────────────────
        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_V)
                    pasteFromClipboard(table, model);
            }
        });

        // ── Toolbar ───────────────────────────────────────────────────────────
        saveBtn = new JButton("Speichern");
        saveBtn.addActionListener(e -> saveData(table));

        JButton addColBtn = new JButton("\u2795"); // ➕
        addColBtn.setFont(addColBtn.getFont().deriveFont(Font.BOLD, 13f));
        addColBtn.setToolTipText("Neue Spalte hinzufügen");
        addColBtn.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this,
                    "Name der neuen Spalte:", "Spalte hinzufügen",
                    JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.isBlank()) return;
            model.addColumn(name.trim());
            selectedViewCol[0] = -1;
            moveLeftBtn.setEnabled(false);
            moveRightBtn.setEnabled(false);
            autoResizeColumns(table);
            markDirty();
        });

        JButton clearAllBtn = new JButton("Tabelle leeren");
        clearAllBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Gesamten Tabelleninhalt löschen?", "Bestätigen",
                    JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                model.setRowCount(0);
                while (model.getRowCount() < KostenattributeService.ROW_COUNT)
                    model.addRow(new Object[model.getColumnCount()]);
                markDirty();
            }
        });

        JButton deleteRowsBtn = new JButton("Zeilen löschen");
        deleteRowsBtn.addActionListener(e -> {
            int[] selected = table.getSelectedRows();
            if (selected.length == 0) return;
            for (int i = selected.length - 1; i >= 0; i--)
                model.removeRow(selected[i]);
            markDirty();
        });

        JButton generateBtn = new JButton("Script generieren");
        generateBtn.addActionListener(e -> generateScript(table));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(saveBtn);
        toolbar.add(clearAllBtn);
        toolbar.add(deleteRowsBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(moveLeftBtn);
        toolbar.add(moveRightBtn);
        toolbar.add(addColBtn);
        toolbar.add(new JSeparator(SwingConstants.VERTICAL));
        toolbar.add(generateBtn);

        JScrollPane scrollPane = new JScrollPane(table,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(toolbar,    BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        autoResizeColumns(table);
    }

    // ── Spalte umbenennen (Doppelklick auf Header) ────────────────────────────

    private void renameColumn(JTable table, int viewCol) {
        String current = table.getColumnModel().getColumn(viewCol).getHeaderValue().toString();
        String newName = (String) JOptionPane.showInputDialog(this,
                "Neuer Spaltenname:", "Spalte umbenennen",
                JOptionPane.PLAIN_MESSAGE, null, null, current);
        if (newName == null || newName.isBlank() || newName.equals(current)) return;
        table.getColumnModel().getColumn(viewCol).setHeaderValue(newName.trim());
        table.getTableHeader().repaint();
        markDirty();
    }

    // ── Dirty-Zustand ─────────────────────────────────────────────────────────

    private void markDirty() {
        dirty = true;
        saveBtn.setBackground(DARK_BLUE);
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

    // ── Persistenz (delegiert an Service) ────────────────────────────────────

    /**
     * Speichert Spaltennamen und Daten in View-Reihenfolge.
     * Spaltennamen werden aus den TableColumn-HeaderValues gelesen,
     * damit Umbenennung per Doppelklick korrekt persistiert wird.
     */
    private void saveData(JTable table) {
        int colCount = table.getColumnCount();
        String[] colNames = new String[colCount];
        for (int c = 0; c < colCount; c++)
            colNames[c] = table.getColumnModel().getColumn(c).getHeaderValue().toString();

        List<String[]> rows = new ArrayList<>();
        for (int r = 0; r < table.getRowCount(); r++) {
            String[] row = new String[colCount];
            for (int c = 0; c < colCount; c++) {
                Object val = table.getValueAt(r, c);
                row[c] = val == null ? "" : val.toString();
            }
            rows.add(row);
        }
        try {
            service.save(colNames, rows);
            clearDirty();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Fehler beim Speichern: " + e.getMessage());
        }
    }

    // ── Paste ─────────────────────────────────────────────────────────────────

    private void pasteFromClipboard(JTable table, DefaultTableModel model) {
        try {
            String text = (String) Toolkit.getDefaultToolkit()
                    .getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (text == null) return;

            int startRow = Math.max(0, table.getSelectedRow());
            int startCol = Math.max(0, table.getSelectedColumn());

            String[] lines = text.split("\n");
            for (int r = 0; r < lines.length; r++) {
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
            autoResizeColumns(table);
        } catch (Exception ignored) { }
    }

    // ── Script-Generierung (delegiert an Service, View-Reihenfolge) ───────────

    private void generateScript(JTable table) {
        List<String> colNames = new ArrayList<>();
        for (int c = 0; c < table.getColumnCount(); c++)
            colNames.add(table.getColumnModel().getColumn(c).getHeaderValue().toString());

        List<String[]> rows = new ArrayList<>();
        for (int r = 0; r < table.getRowCount(); r++) {
            String[] row = new String[table.getColumnCount()];
            for (int c = 0; c < table.getColumnCount(); c++) {
                Object v = table.getValueAt(r, c);
                row[c] = v == null ? "" : v.toString();
            }
            rows.add(row);
        }

        KostenattributeService.ScriptResult result = service.buildScript(colNames, rows);

        if (result.insertCount == 0) {
            JOptionPane.showMessageDialog(this,
                    "Keine Daten vorhanden – Script enthält nur DELETE + COMMIT.",
                    "Hinweis", JOptionPane.INFORMATION_MESSAGE);
        }

        String outputDir = new AppSettings().getOutputDir();
        try {
            Path sqlFile = service.writeScript(result.sql, outputDir);
            JOptionPane.showMessageDialog(this,
                    result.insertCount + " INSERT(s) generiert.\nDatei: " + sqlFile.toAbsolutePath(),
                    "Script gespeichert", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Fehler beim Schreiben: " + ex.getMessage(),
                    "Fehler", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Spaltenbreite (View-Index) ────────────────────────────────────────────

    private void autoResizeColumns(JTable table) {
        for (int col = 0; col < table.getColumnCount(); col++)
            resizeColumn(table, col);
    }

    private void resizeColumn(JTable table, int viewCol) {
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
