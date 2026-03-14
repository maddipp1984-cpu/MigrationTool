package com.migrationtool.launcher;

import com.excelsplit.AppConfig;
import com.excelsplit.ExcelSplitService;
import com.excelsplit.MainPresenter;
import com.excelsplit.MainWindow;
import com.mergegen.config.ConstantTableStore;
import com.mergegen.config.GlobalTraversalRuleStore;
import com.mergegen.config.QueryPresetStore;
import com.mergegen.config.SequenceMappingStore;
import com.mergegen.config.SubselectMappingStore;
import com.mergegen.config.TraversalRuleStore;
import com.mergegen.config.VirtualFkStore;
import com.kostenattribute.InsertGenPanel;
import com.mergegen.gui.ConstantTablePanel;
import com.mergegen.gui.GeneratorPanel;
import com.mergegen.gui.SequenceMappingPanel;
import com.mergegen.gui.SettingsPanel;
import com.mergegen.gui.VirtualFkPanel;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Zentraler Einstiegspunkt für alle Migration-Tools.
 * Single-Frame-Anwendung mit Seitenleiste (CardLayout im Content-Bereich).
 *
 * Der Navigationsbaum ist statisch.
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
        TraversalRuleStore   ruleStore      = new TraversalRuleStore();
        SequenceMappingStore seqStore       = new SequenceMappingStore();
        QueryPresetStore     presetStore    = new QueryPresetStore();

        // ── MergeGen-Stores (Konstantentabellen + Subselect + globale Regeln)
        ConstantTableStore      constTableStore = new ConstantTableStore();
        SubselectMappingStore   subselectStore  = new SubselectMappingStore();
        GlobalTraversalRuleStore globalRuleStore = new GlobalTraversalRuleStore();

        // ── MergeGen-Panels ───────────────────────────────────────────────────
        GeneratorPanel       generatorPanel = new GeneratorPanel(settingsPanel, virtualFkStore, ruleStore, seqStore, constTableStore, presetStore, subselectStore, globalRuleStore);
        VirtualFkPanel       vfkPanel       = new VirtualFkPanel(virtualFkStore);
        SequenceMappingPanel seqPanel       = new SequenceMappingPanel(seqStore);
        ConstantTablePanel   constPanel     = new ConstantTablePanel(constTableStore);
        generatorPanel.setSequenceMappingPanel(seqPanel);

        JTabbedPane mergeGenPane = new JTabbedPane();
        mergeGenPane.addTab("Generator",            generatorPanel);
        mergeGenPane.addTab("Virtuelle FKs",        vfkPanel);
        mergeGenPane.addTab("Sequence-Mappings",    seqPanel);
        mergeGenPane.addTab("Konstantentabellen",   constPanel);

        // ── ExcelSplit-Panel ──────────────────────────────────────────────────
        Path          basePath      = detectLauncherBasePath();
        MainWindow    excelWindow   = new MainWindow();
        MainPresenter excelPresenter = new MainPresenter(excelWindow, new ExcelSplitService(), new AppConfig(basePath), basePath);
        JPanel        excelPanel    = excelWindow.getContentPanel();

        // ── INSERT-Generator-Panel ───────────────────────────────────────────
        InsertGenPanel insertGenPanel = new InsertGenPanel();

        // ── Content-Bereich (CardLayout) ──────────────────────────────────────
        JPanel contentArea = new JPanel(new CardLayout());
        contentArea.add(mergeGenPane,         "mergegen");
        contentArea.add(excelPanel,           "excelsplit");
        contentArea.add(settingsPanel,        "settings");
        contentArea.add(insertGenPanel, "insertgen");

        // ── Navigationsbaum (statisch, nur zur Navigation) ────────────────────
        JScrollPane treePanel = buildNavTree(contentArea);

        // ── Hauptfenster ──────────────────────────────────────────────────────
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, contentArea);
        splitPane.setDividerLocation(155);
        splitPane.setDividerSize(5);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(null);

        JFrame frame = new JFrame("Migration Tools");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(820, 560));
        frame.setLayout(new BorderLayout());
        frame.setJMenuBar(buildMenuBar(frame));
        frame.add(splitPane, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Baut den statischen Navigationsbaum auf.
     * Kategorieknoten: Exceltools, Mergescripte, Einstellungen.
     */
    private static JScrollPane buildNavTree(JPanel contentArea) {

        // ── Knoten ────────────────────────────────────────────────────────────
        DefaultMutableTreeNode root             = new DefaultMutableTreeNode("root");
        DefaultMutableTreeNode exceltools      = new DefaultMutableTreeNode("Exceltools");
        DefaultMutableTreeNode excelSplit      = new DefaultMutableTreeNode("Excel Split");
        DefaultMutableTreeNode mergescripte         = new DefaultMutableTreeNode("Mergescripte");
        DefaultMutableTreeNode mergeGen             = new DefaultMutableTreeNode("MERGE Generator");
        DefaultMutableTreeNode insertGen            = new DefaultMutableTreeNode("INSERT Generator");
        DefaultMutableTreeNode einstellungen   = new DefaultMutableTreeNode("Einstellungen");
        DefaultMutableTreeNode dbVerbindung    = new DefaultMutableTreeNode("DB-Verbindung");

        exceltools.add(excelSplit);
        mergescripte.add(mergeGen);
        mergescripte.add(insertGen);
        einstellungen.add(dbVerbindung);

        root.add(exceltools);
        root.add(mergescripte);
        root.add(einstellungen);

        // ── Node → Card-Mapping ───────────────────────────────────────────────
        Map<DefaultMutableTreeNode, String> nodeCards = new HashMap<>();
        nodeCards.put(excelSplit,       "excelsplit");
        nodeCards.put(mergeGen,         "mergegen");
        nodeCards.put(insertGen,        "insertgen");
        nodeCards.put(dbVerbindung,     "settings");

        // ── JTree ─────────────────────────────────────────────────────────────
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);

        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode selected =
                    (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (selected == null) return;
            String card = nodeCards.get(selected);
            if (card != null) ((CardLayout) contentArea.getLayout()).show(contentArea, card);
        });

        // ── Alle Kategorien aufgeklappt, MERGE Generator vorausgewählt ────────
        tree.expandPath(new TreePath(exceltools.getPath()));
        tree.expandPath(new TreePath(mergescripte.getPath()));
        tree.expandPath(new TreePath(einstellungen.getPath()));
        tree.setSelectionPath(new TreePath(mergeGen.getPath()));
        ((CardLayout) contentArea.getLayout()).show(contentArea, "mergegen");

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(null);
        return scrollPane;
    }

    // ── Menüleiste ────────────────────────────────────────────────────────────

    private static JMenuBar buildMenuBar(JFrame frame) {
        JMenuBar menuBar = new JMenuBar();

        JMenu helpMenu = new JMenu("Hilfe");
        JMenuItem mergeGenHelp = new JMenuItem("MERGE Generator");
        mergeGenHelp.addActionListener(e ->
            new HelpDialog(frame, "MERGE Generator", "/help/mergegen.html").setVisible(true)
        );
        helpMenu.add(mergeGenHelp);

        JMenuItem excelSplitHelp = new JMenuItem("Excel Split");
        excelSplitHelp.addActionListener(e ->
            new HelpDialog(frame, "Excel Split", "/help/excelsplit.html").setVisible(true)
        );
        helpMenu.add(excelSplitHelp);

        JMenuItem insertGenHelp = new JMenuItem("INSERT Generator");
        insertGenHelp.addActionListener(e ->
            new HelpDialog(frame, "INSERT Generator", "/help/insertgen.html").setVisible(true)
        );
        helpMenu.add(insertGenHelp);

        helpMenu.addSeparator();

        JMenuItem aboutItem = new JMenuItem("Über Migration Tools");
        aboutItem.addActionListener(e ->
            JOptionPane.showMessageDialog(frame,
                "Migration Tools\nVersion 1.0\n\nMergeGen · ExcelSplit · INSERT Generator",
                "Über Migration Tools",
                JOptionPane.INFORMATION_MESSAGE)
        );
        helpMenu.add(aboutItem);

        menuBar.add(helpMenu);
        return menuBar;
    }

    // ── Basispfad-Erkennung ───────────────────────────────────────────────────

    /**
     * Ermittelt den Basispfad für ExcelSplit: sucht vom JAR-Verzeichnis aufwärts
     * nach einem „master/"-Ordner, sonst aktuelles Arbeitsverzeichnis.
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
