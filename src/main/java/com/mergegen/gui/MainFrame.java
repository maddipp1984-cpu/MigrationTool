package com.mergegen.gui;

import com.mergegen.config.QueryPresetStore;
import com.mergegen.config.SequenceMappingStore;
import com.mergegen.config.TableHistoryStore;
import com.mergegen.config.VirtualFkStore;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;

public class MainFrame extends JFrame {

    public MainFrame() {
        super("Oracle Merge Script Generator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(650, 480));
        setIconImages(createAppIcons());

        VirtualFkStore        virtualFkStore = new VirtualFkStore();
        SequenceMappingStore  seqStore       = new SequenceMappingStore();
        QueryPresetStore      presetStore    = new QueryPresetStore();
        TableHistoryStore     historyStore   = new TableHistoryStore();
        SettingsPanel         settingsPanel  = new SettingsPanel();
        GeneratorPanel        generatorPanel = new GeneratorPanel(settingsPanel, virtualFkStore, seqStore, presetStore, historyStore);
        VirtualFkPanel        virtualFkPanel = new VirtualFkPanel(virtualFkStore);
        SequenceMappingPanel  seqPanel       = new SequenceMappingPanel(seqStore);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Generator",          new ImageIcon(), generatorPanel, "Merge Scripts erzeugen");
        tabs.addTab("Einstellungen",      new ImageIcon(), settingsPanel,  "Datenbankverbindung konfigurieren");
        tabs.addTab("Virtuelle FKs",      new ImageIcon(), virtualFkPanel, "Manuelle FK-Definitionen verwalten");
        tabs.addTab("Sequence-Mappings",  new ImageIcon(), seqPanel,       "Sequence-Zuordnungen verwalten");

        add(tabs, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(null);
    }

    /**
     * Erzeugt App-Icons in mehreren Größen für Titelleiste und Taskleiste.
     * Design: Zwei zusammenlaufende Pfeile (Merge-Symbol) auf dunkelblauem
     * abgerundetem Hintergrund – passend zum Thema "Merge Script Generator".
     */
    private static java.util.List<Image> createAppIcons() {
        java.util.List<Image> icons = new ArrayList<>();
        for (int size : new int[] { 16, 24, 32, 48, 64 }) {
            icons.add(renderIcon(size));
        }
        return icons;
    }

    private static BufferedImage renderIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        float s = size;

        // Hintergrund: abgerundetes Rechteck, dunkelblauer Gradient
        RoundRectangle2D bg = new RoundRectangle2D.Float(0, 0, s, s, s * 0.22f, s * 0.22f);
        g.setPaint(new GradientPaint(0, 0, new Color(30, 70, 140), s, s, new Color(20, 50, 110)));
        g.fill(bg);

        // Merge-Symbol: zwei Linien von oben links/rechts → Mitte → unten Mitte
        g.setColor(Color.WHITE);
        float strokeW = Math.max(1.5f, s * 0.09f);
        g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        float margin = s * 0.22f;
        float midX   = s * 0.5f;
        float midY   = s * 0.45f;
        float top    = margin;
        float bottom = s - margin;

        // Linker Zweig
        Path2D left = new Path2D.Float();
        left.moveTo(margin, top);
        left.quadTo(margin, midY, midX, midY);
        g.draw(left);

        // Rechter Zweig
        Path2D right = new Path2D.Float();
        right.moveTo(s - margin, top);
        right.quadTo(s - margin, midY, midX, midY);
        g.draw(right);

        // Stamm nach unten
        g.drawLine(Math.round(midX), Math.round(midY), Math.round(midX), Math.round(bottom));

        // Pfeilspitze unten
        float arrowSize = s * 0.13f;
        Path2D arrow = new Path2D.Float();
        arrow.moveTo(midX - arrowSize, bottom - arrowSize);
        arrow.lineTo(midX, bottom);
        arrow.lineTo(midX + arrowSize, bottom - arrowSize);
        g.draw(arrow);

        // Kleine Punkte oben an den Zweig-Enden (Quell-Tabellen-Symbol)
        float dotR = Math.max(1.5f, s * 0.06f);
        g.fill(new java.awt.geom.Ellipse2D.Float(margin - dotR, top - dotR, dotR * 2, dotR * 2));
        g.fill(new java.awt.geom.Ellipse2D.Float(s - margin - dotR, top - dotR, dotR * 2, dotR * 2));

        g.dispose();
        return img;
    }
}
