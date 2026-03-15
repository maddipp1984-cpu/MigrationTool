package com.mergegen.gui;

import com.mergegen.generator.LineDiff;
import com.mergegen.generator.LineDiff.Line;
import com.mergegen.generator.LineDiff.Type;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import com.mergegen.util.DialogUtils;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Side-by-Side Diff-Dialog fuer zwei SQL-Scripts.
 * Links: altes Script, Rechts: neues Script.
 * Unterschiede farblich markiert (rot = entfernt, gruen = neu).
 */
public class DiffDialog extends JDialog {

    private static final Color COLOR_ADDED   = new Color(200, 255, 200);
    private static final Color COLOR_REMOVED = new Color(255, 200, 200);

    public DiffDialog(Window owner, Path oldFile, Path newFile) {
        super(owner, "Script-Vergleich", ModalityType.APPLICATION_MODAL);

        String oldContent;
        String newContent;
        try {
            oldContent = Files.readString(oldFile);
            newContent = Files.readString(newFile);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(owner,
                "Fehler beim Lesen der Dateien:\n" + e.getMessage(),
                "Fehler", JOptionPane.ERROR_MESSAGE);
            dispose();
            return;
        }

        // Diff berechnen
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);
        List<Line> diffLines = LineDiff.diff(oldLines, newLines);

        // Texte fuer links/rechts aufbauen
        StringBuilder leftText = new StringBuilder();
        StringBuilder rightText = new StringBuilder();
        java.util.List<int[]> leftHighlights = new java.util.ArrayList<>();
        java.util.List<int[]> rightHighlights = new java.util.ArrayList<>();

        int leftPos = 0;
        int rightPos = 0;

        for (Line line : diffLines) {
            switch (line.getType()) {
                case SAME:
                    String sameLine = line.getText() + "\n";
                    leftText.append(sameLine);
                    rightText.append(sameLine);
                    leftPos += sameLine.length();
                    rightPos += sameLine.length();
                    break;
                case REMOVED:
                    String removedLine = line.getText() + "\n";
                    leftText.append(removedLine);
                    leftHighlights.add(new int[]{leftPos, leftPos + removedLine.length()});
                    leftPos += removedLine.length();
                    // Leerzeile rechts fuer Ausrichtung
                    String padR = "\n";
                    rightText.append(padR);
                    rightPos += padR.length();
                    break;
                case ADDED:
                    String addedLine = line.getText() + "\n";
                    rightText.append(addedLine);
                    rightHighlights.add(new int[]{rightPos, rightPos + addedLine.length()});
                    rightPos += addedLine.length();
                    // Leerzeile links fuer Ausrichtung
                    String padL = "\n";
                    leftText.append(padL);
                    leftPos += padL.length();
                    break;
            }
        }

        // RSyntaxTextAreas erstellen
        RSyntaxTextArea leftArea = createSqlArea(leftText.toString());
        RSyntaxTextArea rightArea = createSqlArea(rightText.toString());

        // Highlights setzen
        applyHighlights(leftArea, leftHighlights, COLOR_REMOVED);
        applyHighlights(rightArea, rightHighlights, COLOR_ADDED);

        // Synchrones Scrollen (mit Flag gegen Rekursion)
        RTextScrollPane leftScroll  = new RTextScrollPane(leftArea);
        RTextScrollPane rightScroll = new RTextScrollPane(rightArea);
        final boolean[] syncing = {false};

        leftScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!syncing[0]) {
                syncing[0] = true;
                rightScroll.getVerticalScrollBar().setValue(e.getValue());
                syncing[0] = false;
            }
        });
        rightScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!syncing[0]) {
                syncing[0] = true;
                leftScroll.getVerticalScrollBar().setValue(e.getValue());
                syncing[0] = false;
            }
        });

        // Header-Labels
        JPanel leftPanel = new JPanel(new BorderLayout());
        JLabel leftLabel = new JLabel("  Vorherige Version: " + oldFile.getFileName());
        leftLabel.setFont(leftLabel.getFont().deriveFont(Font.BOLD));
        leftLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        leftPanel.add(leftLabel, BorderLayout.NORTH);
        leftPanel.add(leftScroll, BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new BorderLayout());
        JLabel rightLabel = new JLabel("  Aktuelle Version: " + newFile.getFileName());
        rightLabel.setFont(rightLabel.getFont().deriveFont(Font.BOLD));
        rightLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        rightPanel.add(rightLabel, BorderLayout.NORTH);
        rightPanel.add(rightScroll, BorderLayout.CENTER);

        // Split-Pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setResizeWeight(0.5);

        // Legende + Schliessen
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel legendRemoved = new JLabel("  Entfernt  ");
        legendRemoved.setOpaque(true);
        legendRemoved.setBackground(COLOR_REMOVED);
        JLabel legendAdded = new JLabel("  Neu  ");
        legendAdded.setOpaque(true);
        legendAdded.setBackground(COLOR_ADDED);
        bottomPanel.add(legendRemoved);
        bottomPanel.add(Box.createHorizontalStrut(10));
        bottomPanel.add(legendAdded);

        // Statistik
        long added = diffLines.stream().filter(l -> l.getType() == LineDiff.Type.ADDED).count();
        long removed = diffLines.stream().filter(l -> l.getType() == LineDiff.Type.REMOVED).count();
        long same = diffLines.stream().filter(l -> l.getType() == LineDiff.Type.SAME).count();
        bottomPanel.add(Box.createHorizontalStrut(30));
        bottomPanel.add(new JLabel("+" + added + "  -" + removed + "  =" + same));

        bottomPanel.add(Box.createHorizontalGlue());
        JButton closeBtn = new JButton("Schließen");
        closeBtn.addActionListener(e -> dispose());
        bottomPanel.add(closeBtn);

        setLayout(new BorderLayout());
        add(splitPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
        setSize(1200, 700);
        setLocationRelativeTo(owner);

        DialogUtils.addEscapeClose(this);
    }

    private RSyntaxTextArea createSqlArea(String text) {
        RSyntaxTextArea area = new RSyntaxTextArea();
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        area.setEditable(false);
        area.setCodeFoldingEnabled(false);
        area.setText(text);
        area.setCaretPosition(0);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return area;
    }

    private void applyHighlights(RSyntaxTextArea area, java.util.List<int[]> ranges, Color color) {
        DefaultHighlighter.DefaultHighlightPainter painter =
            new DefaultHighlighter.DefaultHighlightPainter(color);
        for (int[] range : ranges) {
            try {
                area.getHighlighter().addHighlight(range[0], range[1], painter);
            } catch (BadLocationException ignored) { }
        }
    }
}
