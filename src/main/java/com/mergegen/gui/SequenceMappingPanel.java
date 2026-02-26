package com.mergegen.gui;

import com.mergegen.config.SequenceMappingStore;
import com.mergegen.model.SequenceMapping;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Tab zur Verwaltung von Sequence-Zuordnungen fuer PK-Spalten.
 *
 * Eintraege werden in sequence-mappings.txt gespeichert und beim
 * Generieren als Vorschlag im Sequence-Dialog angezeigt.
 */
public class SequenceMappingPanel extends JPanel {

    private static final String[] COLUMNS = {
        "Tabelle", "PK-Spalte", "Sequence-Name"
    };

    private final SequenceMappingStore store;
    private final DefaultTableModel    tableModel;
    private final JTable               table;

    public SequenceMappingPanel(SequenceMappingStore store) {
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
            "<html>Hier werden Sequence-Zuordnungen fuer PK-Spalten definiert.<br>" +
            "Beim Generieren wird der gespeicherte Sequence-Name als Vorschlag " +
            "im Dialog angezeigt. Leere Eingabe = PK-Wert aus Quelle uebernehmen.</html>");
        lbl.setForeground(Color.GRAY);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 11f));
        return lbl;
    }

    private JPanel buildTablePanel() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Definierte Sequence-Zuordnungen"));
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
        JTextField tableField = new JTextField(20);
        JTextField pkField    = new JTextField(20);
        JTextField seqField   = new JTextField(20);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(8, 8, 8, 8));

        GridBagConstraints lbl = new GridBagConstraints();
        lbl.gridx  = 0; lbl.anchor = GridBagConstraints.WEST;
        lbl.insets = new Insets(4, 4, 4, 8);

        GridBagConstraints fld = new GridBagConstraints();
        fld.gridx   = 1; fld.fill = GridBagConstraints.HORIZONTAL;
        fld.weightx = 1.0; fld.insets = new Insets(4, 0, 4, 4);

        int row = 0;
        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("Tabelle:"),       lbl); form.add(tableField, fld);
        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("PK-Spalte:"),     lbl); form.add(pkField,    fld);
        lbl.gridy = row; fld.gridy = row;
        form.add(new JLabel("Sequence-Name:"), lbl); form.add(seqField,   fld);

        int result = JOptionPane.showConfirmDialog(
            this, form, "Sequence-Zuordnung hinzufügen",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        String tableName = tableField.getText().trim().toUpperCase();
        String pkColumn  = pkField.getText().trim().toUpperCase();
        String seqName   = seqField.getText().trim().toUpperCase();

        if (tableName.isEmpty() || pkColumn.isEmpty() || seqName.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Alle drei Felder müssen ausgefüllt sein.",
                "Eingabe unvollständig", JOptionPane.WARNING_MESSAGE);
            return;
        }

        store.add(new SequenceMapping(tableName, pkColumn, seqName));
        reload();
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;

        String tableName = (String) tableModel.getValueAt(row, 0);
        String pkColumn  = (String) tableModel.getValueAt(row, 1);

        store.remove(tableName, pkColumn);
        reload();
    }

    private void reload() {
        tableModel.setRowCount(0);
        List<SequenceMapping> all = store.getAll();
        for (SequenceMapping m : all) {
            tableModel.addRow(new Object[]{
                m.getTableName(),
                m.getPkColumn(),
                m.getSequenceName()
            });
        }
    }
}
 