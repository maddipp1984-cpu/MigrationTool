package com.migrationtool.launcher;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Panel für die sequenzielle Ausführung aller Migrationswerkzeuge.
 * Jeder Schritt zeigt seinen aktuellen Status (Bereit / Läuft / OK / Fehler)
 * und kann einzeln oder alle zusammen gestartet werden.
 *
 * Reihenfolge anpassen: Zeile anklicken (wird hervorgehoben) → ▲/▼ nutzen.
 * Der onReorder-Callback wird nach jedem Verschieben aufgerufen.
 */
public class WorkflowPanel extends JPanel {

    /** Repräsentiert einen ausführbaren Schritt im Workflow. */
    public interface Step {
        String getName();
        String getDescription();
        /**
         * Führt den Schritt aus.
         * onComplete muss auf dem EDT mit true (Erfolg) oder false (Fehler) aufgerufen werden.
         */
        void execute(Consumer<Boolean> onComplete);
    }

    private enum Status {
        PENDING("○", Color.GRAY,              "Bereit"),
        RUNNING("◎", new Color(0, 100, 220),  "Läuft\u2026"),
        SUCCESS("✓", new Color(0, 140, 0),    "Erfolgreich"),
        ERROR  ("✗", new Color(180, 0, 0),    "Fehler");

        final String symbol;
        final Color  color;
        final String label;

        Status(String symbol, Color color, String label) {
            this.symbol = symbol;
            this.color  = color;
            this.label  = label;
        }
    }

    private static final Color COLOR_SELECTED = new Color(220, 235, 255);
    private static final Color COLOR_DEFAULT  = Color.WHITE;

    private final List<Step>     steps         = new ArrayList<>();
    private final List<RowPanel> rows          = new ArrayList<>();
    private final JButton        runAllBtn;
    private final JPanel         stepsBox      = new JPanel();
    private int                  selectedIndex = -1;

    private BiConsumer<Integer, Integer> onReorder;

    public WorkflowPanel() {
        setLayout(new BorderLayout(0, 14));
        setBorder(new EmptyBorder(20, 30, 20, 30));

        // ── Kopfzeile ─────────────────────────────────────────────────────────
        JLabel title = new JLabel("Ausführungsreihenfolge");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));

        runAllBtn = new JButton("▶  Alle ausführen");
        runAllBtn.setFont(runAllBtn.getFont().deriveFont(Font.BOLD));
        runAllBtn.addActionListener(e -> runAll());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        top.add(title);
        top.add(Box.createHorizontalStrut(20));
        top.add(runAllBtn);
        add(top, BorderLayout.NORTH);

        // ── Schritt-Liste ─────────────────────────────────────────────────────
        stepsBox.setLayout(new BoxLayout(stepsBox, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(stepsBox,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
    }

    /** Setzt den Callback, der nach jedem Verschieben mit (fromIndex, toIndex) aufgerufen wird. */
    public void setOnReorder(BiConsumer<Integer, Integer> onReorder) {
        this.onReorder = onReorder;
    }

    /** Fügt einen Schritt ans Ende der Liste an. */
    public void addStep(Step step) {
        steps.add(step);
        rebuildStepRows();
    }

    /**
     * Verschiebt Schritt von fromIndex nach toIndex (0-basiert).
     * Setzt alle Statusanzeigen auf „Bereit" zurück.
     */
    public void moveStep(int fromIndex, int toIndex) {
        if (fromIndex == toIndex
                || fromIndex < 0 || toIndex < 0
                || fromIndex >= steps.size() || toIndex >= steps.size()) return;
        Step step = steps.remove(fromIndex);
        steps.add(toIndex, step);
        rebuildStepRows();
    }

    private void rebuildStepRows() {
        stepsBox.removeAll();
        rows.clear();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) stepsBox.add(Box.createVerticalStrut(8));
            RowPanel row = new RowPanel(i + 1, steps.get(i));
            rows.add(row);
            stepsBox.add(row);
        }
        // Pfeil-Zustände und Selektion wiederherstellen
        updateArrowStates();
        if (selectedIndex >= 0 && selectedIndex < rows.size()) {
            rows.get(selectedIndex).setSelected(true);
        }
        stepsBox.revalidate();
        stepsBox.repaint();
    }

    /** Hebt die angeklickte Zeile hervor und deselektiert die anderen. */
    private void selectRow(int index) {
        selectedIndex = index;
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setSelected(i == index);
        }
    }

    /** Verschiebt die Zeile und selektiert sie an der neuen Position. */
    private void moveAndSelect(int from, int to) {
        if (to < 0 || to >= steps.size()) return;
        moveStep(from, to);   // baut rows neu auf
        selectedIndex = to;
        selectRow(to);
        if (onReorder != null) onReorder.accept(from, to);
    }

    /** Aktualisiert den enabled-Zustand der ▲/▼-Buttons für alle Zeilen. */
    private void updateArrowStates() {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).updateArrows(i == 0, i == rows.size() - 1);
        }
    }

    // ── Ausführungslogik ──────────────────────────────────────────────────────

    private void runAll() {
        rows.forEach(r -> { r.setStatus(Status.PENDING); r.runBtn.setEnabled(false); });
        runAllBtn.setEnabled(false);
        chainRun(0);
    }

    private void chainRun(int index) {
        if (index >= steps.size()) {
            finishRun();
            return;
        }
        rows.get(index).setStatus(Status.RUNNING);
        steps.get(index).execute(success -> {
            rows.get(index).setStatus(success ? Status.SUCCESS : Status.ERROR);
            if (success) chainRun(index + 1);
            else         finishRun();
        });
    }

    private void finishRun() {
        runAllBtn.setEnabled(true);
        rows.forEach(r -> r.runBtn.setEnabled(true));
    }

    // ── Zeilenpanel für jeden Schritt ─────────────────────────────────────────

    private class RowPanel extends JPanel {

        final  JButton runBtn;
        final  JButton upBtn;
        final  JButton downBtn;
        private final JLabel   iconLabel;
        private final JLabel   statusLabel;

        RowPanel(int number, Step step) {
            setLayout(new BorderLayout(10, 0));
            setBorder(new CompoundBorder(
                new LineBorder(new Color(210, 210, 210), 1, true),
                new EmptyBorder(10, 10, 10, 14)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 85));
            setBackground(COLOR_DEFAULT);

            // ── Links: Pfeile + Status-Symbol ─────────────────────────────────
            upBtn   = makeArrowButton("▲");
            downBtn = makeArrowButton("▼");

            upBtn.addActionListener(e -> {
                int idx = rows.indexOf(RowPanel.this);
                moveAndSelect(idx, idx - 1);
            });
            downBtn.addActionListener(e -> {
                int idx = rows.indexOf(RowPanel.this);
                moveAndSelect(idx, idx + 1);
            });

            JPanel arrowPanel = new JPanel(new GridLayout(2, 1, 0, 2));
            arrowPanel.setOpaque(false);
            arrowPanel.add(upBtn);
            arrowPanel.add(downBtn);

            iconLabel = new JLabel(Status.PENDING.symbol);
            iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, 20f));
            iconLabel.setForeground(Status.PENDING.color);
            iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
            iconLabel.setPreferredSize(new Dimension(28, 0));

            JPanel west = new JPanel();
            west.setLayout(new BoxLayout(west, BoxLayout.X_AXIS));
            west.setOpaque(false);
            west.add(arrowPanel);
            west.add(Box.createHorizontalStrut(8));
            west.add(iconLabel);
            add(west, BorderLayout.WEST);

            // ── Mitte: Name + Beschreibung + Statustext ────────────────────────
            JPanel center = new JPanel();
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            center.setOpaque(false);

            JLabel nameLabel = new JLabel(number + ".  " + step.getName());
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
            JLabel descLabel = new JLabel(step.getDescription());
            descLabel.setFont(descLabel.getFont().deriveFont(Font.PLAIN, 11f));
            descLabel.setForeground(Color.GRAY);
            statusLabel = new JLabel(Status.PENDING.label);
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 11f));
            statusLabel.setForeground(Status.PENDING.color);

            center.add(Box.createVerticalStrut(2));
            center.add(nameLabel);
            center.add(Box.createVerticalStrut(3));
            center.add(descLabel);
            center.add(Box.createVerticalStrut(3));
            center.add(statusLabel);
            center.add(Box.createVerticalStrut(2));
            add(center, BorderLayout.CENTER);

            // ── Rechts: Einzelausführung ───────────────────────────────────────
            runBtn = new JButton("Ausführen");
            runBtn.addActionListener(e -> {
                runBtn.setEnabled(false);
                runAllBtn.setEnabled(false);
                setStatus(Status.RUNNING);
                int idx = rows.indexOf(this);
                steps.get(idx).execute(success -> {
                    setStatus(success ? Status.SUCCESS : Status.ERROR);
                    runBtn.setEnabled(true);
                    runAllBtn.setEnabled(true);
                });
            });
            add(runBtn, BorderLayout.EAST);

            // ── Zeile selektierbar per Klick (nicht auf Buttons) ──────────────
            MouseAdapter selectAdapter = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    selectRow(rows.indexOf(RowPanel.this));
                }
            };
            addSelectListener(this, selectAdapter);
        }

        /** Registriert den Klick-Listener rekursiv auf alle Nicht-Button-Komponenten. */
        private void addSelectListener(Component comp, MouseAdapter adapter) {
            if (comp instanceof JButton) return;
            comp.addMouseListener(adapter);
            if (comp instanceof Container) {
                for (Component child : ((Container) comp).getComponents()) {
                    addSelectListener(child, adapter);
                }
            }
        }

        /** Setzt enabled-Zustand der Pfeil-Buttons. */
        void updateArrows(boolean isFirst, boolean isLast) {
            upBtn.setEnabled(!isFirst);
            downBtn.setEnabled(!isLast);
        }

        /** Hebt die Zeile hervor oder setzt sie zurück. */
        void setSelected(boolean selected) {
            setBackground(selected ? COLOR_SELECTED : COLOR_DEFAULT);
        }

        void setStatus(Status status) {
            iconLabel.setText(status.symbol);
            iconLabel.setForeground(status.color);
            statusLabel.setText(status.label);
            statusLabel.setForeground(status.color);
        }

        private JButton makeArrowButton(String symbol) {
            JButton btn = new JButton(symbol);
            btn.setFont(btn.getFont().deriveFont(9f));
            btn.setMargin(new Insets(1, 3, 1, 3));
            btn.setFocusable(false);
            return btn;
        }
    }
}
