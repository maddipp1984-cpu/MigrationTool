package com.migrationtool.launcher;

import com.excelsplit.AppConfig;
import com.excelsplit.ExcelSplitService;
import com.excelsplit.MainPresenter;
import com.excelsplit.MainWindow;
import com.mergegen.config.QueryPresetStore;
import com.mergegen.config.SequenceMappingStore;
import com.mergegen.config.TableHistoryStore;
import com.mergegen.config.VirtualFkStore;
import com.kostenattribute.KostenattributePanel;
import com.migrationtool.scriptexec.ScriptExecutorPanel;
import com.migrationtool.scriptexec.ZielDbPanel;
import com.mergegen.gui.GeneratorPanel;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Zentraler Einstiegspunkt für alle Migration-Tools.
 * Single-Frame-Anwendung mit Seitenleiste (CardLayout im Content-Bereich).
 *
 * Der Navigationsbaum ist statisch (kein DnD).
 * Die Ausführungsreihenfolge wird per Drag & Drop im WorkflowPanel festgelegt
 * und in launcher.properties gespeichert.
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
        QueryPresetStore     presetStore    = new QueryPresetStore();
        TableHistoryStore    historyStore   = new TableHistoryStore();

        // ── MergeGen-Panels ───────────────────────────────────────────────────
        GeneratorPanel       generatorPanel = new GeneratorPanel(settingsPanel, virtualFkStore, seqStore, presetStore, historyStore);
        VirtualFkPanel       vfkPanel       = new VirtualFkPanel(virtualFkStore);
        SequenceMappingPanel seqPanel       = new SequenceMappingPanel(seqStore);

        JTabbedPane mergeGenPane = new JTabbedPane();
        mergeGenPane.addTab("Generator",         generatorPanel);
        mergeGenPane.addTab("Virtuelle FKs",     vfkPanel);
        mergeGenPane.addTab("Sequence-Mappings", seqPanel);

        // ── ExcelSplit-Panel ──────────────────────────────────────────────────
        Path          basePath      = detectLauncherBasePath();
        MainWindow    excelWindow   = new MainWindow();
        MainPresenter excelPresenter = new MainPresenter(excelWindow, new ExcelSplitService(), new AppConfig(basePath), basePath);
        JPanel        excelPanel    = excelWindow.getContentPanel();

        // ── Ziel-DB + Script-Ausführung ───────────────────────────────────────
        ZielDbPanel          zielDbPanel  = new ZielDbPanel();
        ScriptExecutorPanel  scriptPanel  = new ScriptExecutorPanel(zielDbPanel);

        // ── Schritte und ihre Namen ───────────────────────────────────────────
        Map<String, WorkflowPanel.Step> availableSteps = new LinkedHashMap<>();
        availableSteps.put("Exceltools", new WorkflowPanel.Step() {
            @Override public String getName()        { return "Excel Split"; }
            @Override public String getDescription() { return "Wandelt Masterdateien in CSV-Templates um"; }
            @Override public void execute(Consumer<Boolean> onComplete) {
                excelPresenter.processAllFiles(onComplete);
            }
        });
        availableSteps.put("Mergescripte", new WorkflowPanel.Step() {
            @Override public String getName()        { return "MERGE Generator"; }
            @Override public String getDescription() { return "Erstellt Oracle MERGE-Scripts (letzte Einstellungen)"; }
            @Override public void execute(Consumer<Boolean> onComplete) {
                generatorPanel.runWithLastSettings(onComplete);
            }
        });
        availableSteps.put("ScriptAusfuehren", new WorkflowPanel.Step() {
            @Override public String getName()        { return "Script ausführen"; }
            @Override public String getDescription() { return "Führt MERGE-Scripts auf der Ziel-DB aus"; }
            @Override public void execute(Consumer<Boolean> onComplete) {
                scriptPanel.executeAll(onComplete);
            }
        });

        // ── Gespeicherte Reihenfolge laden ────────────────────────────────────
        List<String> stepOrder = loadNavOrder();
        if (stepOrder.isEmpty()) {
            stepOrder = new ArrayList<>(availableSteps.keySet());
        } else {
            // Bekannte Keys in gespeicherter Reihenfolge, unbekannte hinten anhängen
            List<String> ordered = new ArrayList<>();
            for (String key : stepOrder) {
                if (availableSteps.containsKey(key)) ordered.add(key);
            }
            for (String key : availableSteps.keySet()) {
                if (!ordered.contains(key)) ordered.add(key);
            }
            stepOrder = ordered;
        }
        final List<String> finalStepOrder = stepOrder;

        // ── WorkflowPanel mit Schritten in gespeicherter Reihenfolge ─────────
        WorkflowPanel workflowPanel = new WorkflowPanel();
        for (String key : finalStepOrder) {
            workflowPanel.addStep(availableSteps.get(key));
        }

        // Reihenfolge nach DnD speichern
        workflowPanel.setOnReorder((from, to) -> {
            String moved = finalStepOrder.remove((int) from);
            finalStepOrder.add(to, moved);
            saveNavOrder(finalStepOrder);
        });

        // ── Kostenattribute-Panel ─────────────────────────────────────────────
        KostenattributePanel kostenattributePanel = new KostenattributePanel();

        // ── Content-Bereich (CardLayout) ──────────────────────────────────────
        JPanel contentArea = new JPanel(new CardLayout());
        contentArea.add(workflowPanel,        "workflow");
        contentArea.add(mergeGenPane,         "mergegen");
        contentArea.add(excelPanel,           "excelsplit");
        contentArea.add(scriptPanel,          "scriptexec");
        contentArea.add(settingsPanel,        "settings");
        contentArea.add(zielDbPanel,          "zieldb");
        contentArea.add(kostenattributePanel, "kostenattribute");

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
        frame.add(splitPane, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /**
     * Baut den statischen Navigationsbaum auf.
     * Fixe Knoten: „Alles ausführen" (oben) und „Einstellungen" (unten).
     * Kategorieknoten: Exceltools → Excel Split, Mergescripte → MERGE Generator.
     */
    private static JScrollPane buildNavTree(JPanel contentArea) {

        // ── Knoten ────────────────────────────────────────────────────────────
        DefaultMutableTreeNode root             = new DefaultMutableTreeNode("root");
        DefaultMutableTreeNode workflowNode    = new DefaultMutableTreeNode("Alles ausführen");
        DefaultMutableTreeNode exceltools      = new DefaultMutableTreeNode("Exceltools");
        DefaultMutableTreeNode excelSplit      = new DefaultMutableTreeNode("Excel Split");
        DefaultMutableTreeNode mergescripte         = new DefaultMutableTreeNode("Mergescripte");
        DefaultMutableTreeNode mergeGen             = new DefaultMutableTreeNode("MERGE Generator");
        DefaultMutableTreeNode kostenattribute      = new DefaultMutableTreeNode("Kostenattribute (Migration SUEWAG)");
        DefaultMutableTreeNode scriptAusfuehren = new DefaultMutableTreeNode("Script ausführen");
        DefaultMutableTreeNode zielDbAusfuehren = new DefaultMutableTreeNode("Ziel-DB ausführen");
        DefaultMutableTreeNode einstellungen   = new DefaultMutableTreeNode("Einstellungen");
        DefaultMutableTreeNode dbVerbindung    = new DefaultMutableTreeNode("DB-Verbindung");
        DefaultMutableTreeNode zielDbSettings  = new DefaultMutableTreeNode("Ziel-DB");

        exceltools.add(excelSplit);
        mergescripte.add(mergeGen);
        mergescripte.add(kostenattribute);
        scriptAusfuehren.add(zielDbAusfuehren);
        einstellungen.add(dbVerbindung);
        einstellungen.add(zielDbSettings);

        root.add(workflowNode);
        root.add(exceltools);
        root.add(mergescripte);
        root.add(scriptAusfuehren);
        root.add(einstellungen);

        // ── Node → Card-Mapping ───────────────────────────────────────────────
        Map<DefaultMutableTreeNode, String> nodeCards = new HashMap<>();
        nodeCards.put(workflowNode,     "workflow");
        nodeCards.put(excelSplit,       "excelsplit");
        nodeCards.put(mergeGen,         "mergegen");
        nodeCards.put(kostenattribute,  "kostenattribute");
        nodeCards.put(zielDbAusfuehren, "scriptexec");
        nodeCards.put(dbVerbindung,     "settings");
        nodeCards.put(zielDbSettings,   "zieldb");

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
        tree.expandPath(new TreePath(scriptAusfuehren.getPath()));
        tree.expandPath(new TreePath(einstellungen.getPath()));
        tree.setSelectionPath(new TreePath(mergeGen.getPath()));
        ((CardLayout) contentArea.getLayout()).show(contentArea, "mergegen");

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(null);
        return scrollPane;
    }

    // ── Persistenz der Schrittfolge ───────────────────────────────────────────

    private static final Path NAV_PROPS = Paths.get("launcher.properties");

    private static List<String> loadNavOrder() {
        if (!Files.exists(NAV_PROPS)) return Collections.emptyList();
        Properties props = new Properties();
        try (var in = Files.newInputStream(NAV_PROPS)) {
            props.load(in);
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        String order = props.getProperty("nav.order", "").trim();
        return order.isEmpty() ? Collections.emptyList() : new ArrayList<>(Arrays.asList(order.split(",")));
    }

    private static void saveNavOrder(List<String> order) {
        Properties props = new Properties();
        props.setProperty("nav.order", String.join(",", order));
        try (var out = Files.newOutputStream(NAV_PROPS)) {
            props.store(out, null);
        } catch (IOException ignored) {}
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
