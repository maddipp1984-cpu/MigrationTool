package com.mergegen.gui;

import com.mergegen.config.ConstantTableStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel zur Verwaltung von Konstantentabellen (kein MERGE).
 *
 * Einträge werden in constant-tables.txt gespeichert und beim
 * Generieren automatisch vom MERGE ausgeschlossen.
 */
public class ConstantTablePanel extends JPanel {

    private static final String[] COLUMNS = { "Tabellenname" };

    private final ConstantTableStore store;
    private final DefaultTableModel  tableModel;
    private final JTable             table;

    public ConstantTablePanel(ConstantTableStore store) {
        this.store      = store;
        this.tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        this.table = new JTable(tableModel);

        setLayout(new BorderLayout(0, 10));
        setBorder(new EmptyBorder(16, 24, 16, 24));

        add(buildInfoLabel(),   BorderLayout.NORTH);
        add(buildTablePanel(),  BorderLayout.CENTER);
        add(buildButtonPanel(), BorderLayout.SOUTH);

        reload();
    }

    private JLabel buildInfoLabel() {
        JLabel lbl = new JLabel(
            "<html>Tabellen, die hier eingetragen sind, werden beim Generieren " +
            "vom MERGE ausgeschlossen.<br>" +
            "Typisch für Konstanten-/Lookup-Tabellen, deren Daten bereits in der " +
            "Ziel-DB vorhanden sind.<br>" +
            "Die FK-Werte bleiben als Literale im Script erhalten.</html>");
        lbl.setForeground(Color.GRAY);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 11f));
        return lbl;
    }

    private JPanel buildTablePanel() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Konstantentabellen (kein MERGE)"));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildButtonPanel() {
        JButton addBtn    = new JButton("Hinzufügen...");
        JButton removeBtn = new JButton("Entfernen");

        removeBtn.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e ->
            removeBtn.setEnabled(table.getSelectedRow() >= 0));

        addBtn.addActionListener(e -> showAddDialog());
        removeBtn.addActionListener(e -> removeSelected());

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.add(addBtn);
        panel.add(removeBtn);
        return panel;
    }

    private void showAddDialog() {
        String input = JOptionPane.showInputDialog(this,
            "Tabellenname (wird automatisch in Großbuchstaben umgewandelt):",
            "Konstantentabelle hinzufügen",
            JOptionPane.PLAIN_MESSAGE);

        if (input == null) return;
        String tableName = input.trim().toUpperCase();
        if (tableName.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Tabellenname darf nicht leer sein.",
                "Eingabe unvollständig", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (store.contains(tableName)) {
            JOptionPane.showMessageDialog(this,
                "Tabelle '" + tableName + "' ist bereits eingetragen.",
                "Duplikat", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        store.add(tableName);
        reload();
    }

    private void removeSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) return;

        List<String> toRemove = new ArrayList<>();
        for (int row : rows) {
            toRemove.add((String) tableModel.getValueAt(row, 0));
        }
        store.removeAll(toRemove);
        reload();
    }

    /** Lädt die Tabelle neu aus dem Store. Kann von außen aufgerufen werden. */
    public void reload() {
        tableModel.setRowCount(0);
        for (String entry : store.getAll()) {
            tableModel.addRow(new Object[]{ entry });
        }
    }
}
