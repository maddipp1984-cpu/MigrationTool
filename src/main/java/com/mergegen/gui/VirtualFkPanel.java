package com.mergegen.gui;

import com.mergegen.config.VirtualFkStore;
import com.mergegen.model.ForeignKeyRelation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Tab zur Verwaltung manuell definierter ("virtueller") FK-Beziehungen.
 *
 * Virtuelle FKs werden benötigt, wenn in der Datenbank kein FK-Constraint
 * hinterlegt ist, die Abhängigkeit aber dennoch existiert (unsauberes Schema).
 *
 * Einträge werden in virtual-fks.txt gespeichert und beim Traversal automatisch
 * berücksichtigt. Sobald ein Eintrag als echter Constraint in der DB erscheint,
 * wird er beim nächsten Traversal-Lauf automatisch entfernt.
 */
public class VirtualFkPanel extends JPanel {

    private static final String[] COLUMNS = {
        "Child-Tabelle", "FK-Spalte", "Parent-Tabelle", "Parent-PK-Spalte"
    };

    private final VirtualFkStore       store;
    private final DefaultTableModel    tableModel;
    private final JTable               table;

    public VirtualFkPanel(VirtualFkStore store) {
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

    // ── Info-Label ────────────────────────────────────────────────────────────

    private JLabel buildInfoLabel() {
        JLabel lbl = new JLabel(
            "<html>Hier können FK-Beziehungen definiert werden, die in der Datenbank " +
            "nicht als Constraint hinterlegt sind.<br>" +
            "Einträge werden beim nächsten Traversal automatisch entfernt, " +
            "sobald der echte FK-Constraint in der DB existiert.</html>");
        lbl.setForeground(Color.GRAY);
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN, 11f));
        return lbl;
    }

    // ── Tabelle ───────────────────────────────────────────────────────────────

    private JPanel buildTablePanel() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Definierte virtuelle FKs"));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private JPanel buildButtonPanel() {
        JButton addBtn    = new JButton("Hinzufügen...");
        JButton removeBtn = new JButton("Entfernen");

        // "Entfernen" nur aktiv wenn eine Zeile selektiert ist
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

    // ── Hinzufügen-Dialog ─────────────────────────────────────────────────────

    private void showAddDialog() {
        JTextField childTableField  = new JTextField(20);
        JTextField fkColumnField    = new JTextField(20);
        JTextField parentTableField = new JTextField(20);
        JTextField parentPkField    = new JTextField(20);

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
        form.add(new JLabel("Child-Tabelle:"),   lbl); form.add(childTableField,  fld);
        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("FK-Spalte:"),       lbl); form.add(fkColumnField,    fld);
        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("Parent-Tabelle:"),  lbl); form.add(parentTableField, fld);
        lbl.gridy = row; fld.gridy = row;
        form.add(new JLabel("Parent-PK-Spalte:"),lbl); form.add(parentPkField,    fld);

        int result = JOptionPane.showConfirmDialog(
            this, form, "Virtuellen FK hinzufügen",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return;

        String childTable  = childTableField.getText().trim().toUpperCase();
        String fkColumn    = fkColumnField.getText().trim().toUpperCase();
        String parentTable = parentTableField.getText().trim().toUpperCase();
        String parentPk    = parentPkField.getText().trim().toUpperCase();

        if (childTable.isEmpty() || fkColumn.isEmpty() ||
            parentTable.isEmpty() || parentPk.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Alle vier Felder müssen ausgefüllt sein.",
                "Eingabe unvollständig", JOptionPane.WARNING_MESSAGE);
            return;
        }

        store.add(new ForeignKeyRelation(childTable, fkColumn, parentTable, parentPk));
        reload();
    }

    // ── Entfernen ─────────────────────────────────────────────────────────────

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;

        String childTable  = (String) tableModel.getValueAt(row, 0);
        String fkColumn    = (String) tableModel.getValueAt(row, 1);
        String parentTable = (String) tableModel.getValueAt(row, 2);
        String parentPk    = (String) tableModel.getValueAt(row, 3);

        store.remove(new ForeignKeyRelation(childTable, fkColumn, parentTable, parentPk));
        reload();
    }

    // ── Tabelleninhalt neu laden ───────────────────────────────────────────────

    private void reload() {
        tableModel.setRowCount(0);
        List<ForeignKeyRelation> all = store.getAll();
        for (ForeignKeyRelation rel : all) {
            tableModel.addRow(new Object[]{
                rel.getChildTable(),
                rel.getFkColumn(),
                rel.getParentTable(),
                rel.getParentPkColumn()
            });
        }
    }
}
