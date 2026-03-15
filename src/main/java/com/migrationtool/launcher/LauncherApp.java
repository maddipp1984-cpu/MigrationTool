package com.migrationtool.launcher;

import com.excelsplit.AppConfig;
import com.excelsplit.ExcelSplitService;
import com.excelsplit.MainPresenter;
import com.excelsplit.MainWindow;
import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatLaf;
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
import com.mergegen.util.PathUtils;
import com.mergegen.gui.SequenceMappingPanel;
import com.mergegen.gui.SettingsPanel;
import com.mergegen.gui.VirtualFkPanel;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.io.IOException;
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

    private static final Path THEME_FILE = Paths.get("config", "launcher", "theme.properties");

    private static final String CARD_MERGEGEN   = "mergegen";
    private static final String CARD_EXCELSPLIT  = "excelsplit";
    private static final String CARD_SETTINGS    = "settings";
    private static final String CARD_INSERTGEN   = "insertgen";

    public static void main(String[] args) {
        setupLookAndFeel();
        SwingUtilities.invokeLater(LauncherApp::createAndShow);
    }

    // ── Look & Feel ─────────────────────────────────────────────────────────────

    private static void setupLookAndFeel() {
        String themeName = loadThemeName();
        applyTheme(themeName);
    }

    private static void applyTheme(String themeName) {
        try {
            switch (themeName) {
                case "FlatDark":     FlatDarkLaf.setup();     break;
                case "FlatIntelliJ": FlatIntelliJLaf.setup(); break;
                case "FlatDarcula":  FlatDarculaLaf.setup();  break;
                case "System":       UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); break;
                default:             FlatLightLaf.setup();    break;
            }
        } catch (Exception e) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
        }
    }

    private static String loadThemeName() {
        if (!Files.exists(THEME_FILE)) return "FlatLight";
        try {
            Properties p = new Properties();
            p.load(Files.newBufferedReader(THEME_FILE));
            return p.getProperty("theme", "FlatLight");
        } catch (IOException e) {
            return "FlatLight";
        }
    }

    private static void saveThemeName(String themeName) {
        try {
            Files.createDirectories(THEME_FILE.getParent());
            Properties p = new Properties();
            p.setProperty("theme", themeName);
            p.store(Files.newBufferedWriter(THEME_FILE), "Look & Feel");
        } catch (IOException ignored) { }
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
        contentArea.add(mergeGenPane,         CARD_MERGEGEN);
        contentArea.add(excelPanel,           CARD_EXCELSPLIT);
        contentArea.add(settingsPanel,        CARD_SETTINGS);
        contentArea.add(insertGenPanel,       CARD_INSERTGEN);

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
        DefaultMutableTreeNode root       = new DefaultMutableTreeNode("root");
        DefaultMutableTreeNode catExcel   = new DefaultMutableTreeNode("EXCEL");
        DefaultMutableTreeNode excelSplit = new DefaultMutableTreeNode("Excel Split");
        DefaultMutableTreeNode catScript  = new DefaultMutableTreeNode("SCRIPTE");
        DefaultMutableTreeNode mergeGen   = new DefaultMutableTreeNode("MERGE Generator");
        DefaultMutableTreeNode insertGen  = new DefaultMutableTreeNode("INSERT Generator");
        DefaultMutableTreeNode catConfig  = new DefaultMutableTreeNode("KONFIGURATION");
        DefaultMutableTreeNode dbVerb     = new DefaultMutableTreeNode("DB-Verbindung");

        catExcel.add(excelSplit);
        catScript.add(mergeGen);
        catScript.add(insertGen);
        catConfig.add(dbVerb);

        root.add(catExcel);
        root.add(catScript);
        root.add(catConfig);

        // ── Kategorie-Knoten (nicht selektierbar) ───────────────────────────
        Set<DefaultMutableTreeNode> categoryNodes = new HashSet<>(
                Arrays.asList(catExcel, catScript, catConfig));

        // ── Node → Card-Mapping ───────────────────────────────────────────────
        Map<DefaultMutableTreeNode, String> nodeCards = new HashMap<>();
        nodeCards.put(excelSplit, CARD_EXCELSPLIT);
        nodeCards.put(mergeGen,   CARD_MERGEGEN);
        nodeCards.put(insertGen,  CARD_INSERTGEN);
        nodeCards.put(dbVerb,     CARD_SETTINGS);

        // ── JTree ─────────────────────────────────────────────────────────────
        JTree tree = new JTree(root);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(false);
        tree.setToggleClickCount(0); // Kategorien nicht ein-/ausklappbar

        // ── Custom Renderer: Kategorie-Labels klein, grau, UPPERCASE ────────
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            {
                setLeafIcon(null);
                setOpenIcon(null);
                setClosedIcon(null);
            }

            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                if (categoryNodes.contains(node)) {
                    setFont(getFont().deriveFont(Font.PLAIN, 10f));
                    setForeground(UIManager.getColor("Label.disabledForeground") != null
                            ? UIManager.getColor("Label.disabledForeground")
                            : Color.GRAY);
                    setEnabled(false);
                    setBorder(BorderFactory.createEmptyBorder(8, 8, 2, 0));
                } else {
                    setFont(getFont().deriveFont(Font.PLAIN, 12f));
                    setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 0));
                }
                return this;
            }
        });

        // ── Selection: Kategorie-Klicks ignorieren ──────────────────────────
        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode selected =
                    (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (selected == null) return;
            if (categoryNodes.contains(selected)) {
                tree.clearSelection();
                return;
            }
            String card = nodeCards.get(selected);
            if (card != null) ((CardLayout) contentArea.getLayout()).show(contentArea, card);
        });

        // ── Alle Kategorien aufgeklappt, MERGE Generator vorausgewählt ────────
        tree.expandPath(new TreePath(catExcel.getPath()));
        tree.expandPath(new TreePath(catScript.getPath()));
        tree.expandPath(new TreePath(catConfig.getPath()));
        tree.setSelectionPath(new TreePath(mergeGen.getPath()));
        ((CardLayout) contentArea.getLayout()).show(contentArea, CARD_MERGEGEN);

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(null);
        return scrollPane;
    }

    // ── Menüleiste ────────────────────────────────────────────────────────────

    private static JMenuBar buildMenuBar(JFrame frame) {
        JMenuBar menuBar = new JMenuBar();

        // ── Ansicht-Menü (Theme-Umschalter) ─────────────────────────────────
        JMenu viewMenu = new JMenu("Ansicht");

        String currentTheme = loadThemeName();
        ButtonGroup themeGroup = new ButtonGroup();
        String[][] themes = {
            {"FlatLight",   "Hell (Light)"},
            {"FlatDark",    "Dunkel (Dark)"},
            {"FlatIntelliJ","IntelliJ"},
            {"FlatDarcula", "Darcula"},
            {"System",      "Windows Classic"}
        };

        for (String[] t : themes) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(t[1], t[0].equals(currentTheme));
            themeGroup.add(item);
            item.addActionListener(e -> {
                applyTheme(t[0]);
                FlatLaf.updateUI();
                saveThemeName(t[0]);
            });
            viewMenu.add(item);
        }

        menuBar.add(viewMenu);

        // ── Hilfe-Menü ──────────────────────────────────────────────────────
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
        return PathUtils.detectBasePath(LauncherApp.class);
    }
}
