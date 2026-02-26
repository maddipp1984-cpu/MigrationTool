package com.migrationtool.launcher;

import com.excelsplit.AppConfig;
import com.excelsplit.ExcelSplitService;
import com.excelsplit.MainPresenter;
import com.excelsplit.MainWindow;
import com.mergegen.config.ConstantTableStore;
import com.mergegen.config.QueryPresetStore;
import com.mergegen.config.SequenceMappingStore;
import com.mergegen.config.TableHistoryStore;
import com.mergegen.config.VirtualFkStore;
import com.mergegen.gui.GeneratorPanel;
import com.mergegen.gui.SequenceMappingPanel;
import com.mergegen.gui.SettingsPanel;
import com.mergegen.gui.VirtualFkPanel;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Zentraler Einstiegspunkt für alle Migration-Tools.
 * Single-Frame-Anwendung mit Seitenleiste (CardLayout im Content-Bereich).
 */
public class LauncherApp {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }

        SwingUtilities.invokeLater(LauncherApp::createAndShow);
    }

    private static void createAndShow() {

        // ── Globale DB-Einstellungen (geteilt mit GeneratorPanel) ─────────────
        SettingsPanel settingsPanel = new SettingsPanel();

        // ── MergeGen-Stores ───────────────────────────────────────────────────
        VirtualFkStore       virtualFkStore = new VirtualFkStore();
        SequenceMappingStore seqStore       = new SequenceMappingStore();
        ConstantTableStore   constStore     = new ConstantTableStore();
        QueryPresetStore     presetStore    = new QueryPresetStore();
        TableHistoryStore    historyStore   = new TableHistoryStore();

        // ── MergeGen-Panels ───────────────────────────────────────────────────
        GeneratorPanel       generatorPanel = new GeneratorPanel(settingsPanel, virtualFkStore, seqStore, constStore, presetStore, historyStore);
        VirtualFkPanel       vfkPanel       = new VirtualFkPanel(virtualFkStore);
        SequenceMappingPanel seqPanel       = new SequenceMappingPanel(seqStore);

        JTabbedPane mergeGenPane = new JTabbedPane();
        mergeGenPane.addTab("Generator",         generatorPanel);
        mergeGenPane.addTab("Virtuelle FKs",     vfkPanel);
        mergeGenPane.addTab("Sequence-Mappings", seqPanel);

        // ── ExcelSplit-Panel ──────────────────────────────────────────────────
        Path       basePath    = detectLauncherBasePath();
        MainWindow excelWindow = new MainWindow();
        new MainPresenter(excelWindow, new ExcelSplitService(), new AppConfig(basePath), basePath);
        JPanel excelPanel = excelWindow.getContentPanel();

        // ── Content-Bereich (CardLayout) ──────────────────────────────────────
        JPanel contentArea = new JPanel(new CardLayout());
        contentArea.add(mergeGenPane,  "mergegen");
        contentArea.add(excelPanel,    "excelsplit");
        contentArea.add(settingsPanel, "settings");

        // ── Navigationsbaum ───────────────────────────────────────────────────
        JScrollPane treePanel = buildNavTree(contentArea);

        // ── Hauptfenster ──────────────────────────────────────────────────────
        JFrame frame = new JFrame("Migration Tools");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(820, 560));
        frame.setLayout(new BorderLayout());
        frame.add(treePanel,   BorderLayout.WEST);
        frame.add(contentArea, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JScrollPane buildNavTree(JPanel contentArea) {

        // ── Tree-Struktur aufbauen ────────────────────────────────────────────
        DefaultMutableTreeNode root         = new DefaultMutableTreeNode("root");
        DefaultMutableTreeNode exceltools   = new DefaultMutableTreeNode("Exceltools");
        DefaultMutableTreeNode excelSplit   = new DefaultMutableTreeNode("Excel Split");
        DefaultMutableTreeNode mergescripte = new DefaultMutableTreeNode("Mergescripte");
        DefaultMutableTreeNode mergeGen     = new DefaultMutableTreeNode("MERGE Generator");
        DefaultMutableTreeNode einstellungen = new DefaultMutableTreeNode("Einstellungen");
        DefaultMutableTreeNode dbVerbindung  = new DefaultMutableTreeNode("DB-Verbindung");

        exceltools.add(excelSplit);
        mergescripte.add(mergeGen);
        einstellungen.add(dbVerbindung);
        root.add(exceltools);
        root.add(mergescripte);
        root.add(einstellungen);

        // ── Node → Card-Mapping ───────────────────────────────────────────────
        Map<DefaultMutableTreeNode, String> nodeCards = new HashMap<>();
        nodeCards.put(excelSplit,   "excelsplit");
        nodeCards.put(mergeGen,     "mergegen");
        nodeCards.put(dbVerbindung, "settings");

        // ── JTree ─────────────────────────────────────────────────────────────
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);

        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode selected =
                (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (selected == null) return;
            String card = nodeCards.get(selected);
            if (card != null) {
                ((CardLayout) contentArea.getLayout()).show(contentArea, card);
            }
        });

        // Alle Kategorien aufgeklappt, MERGE Generator vorausgewählt
        tree.expandPath(new TreePath(exceltools.getPath()));
        tree.expandPath(new TreePath(mergescripte.getPath()));
        tree.expandPath(new TreePath(einstellungen.getPath()));
        tree.setSelectionPath(new TreePath(mergeGen.getPath()));
        ((CardLayout) contentArea.getLayout()).show(contentArea, "mergegen");

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setPreferredSize(new Dimension(155, 0));
        scrollPane.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Color.LIGHT_GRAY));
        return scrollPane;
    }

    /**
     * Ermittelt den Basispfad für ExcelSplit: sucht vom JAR-Verzeichnis aufwärts
     * nach einem "master/"-Ordner, sonst aktuelles Arbeitsverzeichnis.
     */
    private static Path detectLauncherBasePath() {
        try {
            Path jar = Paths.get(
                LauncherApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath();
            Path current = jar.getParent();
            for (int i = 0; i < 6; i++) {
                if (current == null) break;
                if (Files.isDirectory(current.resolve("master"))) return current;
                current = current.getParent();
            }
        } catch (Exception ignored) { }
        return Paths.get(".").toAbsolutePath().normalize();
    }
}
