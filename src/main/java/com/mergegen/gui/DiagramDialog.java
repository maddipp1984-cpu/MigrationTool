package com.mergegen.gui;

import com.mergegen.config.ConstantTableStore;
import com.mergegen.config.TraversalRuleStore;
import com.mergegen.config.TraversalRuleStore.TraversalRule;
import com.mergegen.model.DependencyNode;
import com.mergegen.model.ForeignKeyRelation;
import com.mergegen.model.TraversalResult;
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.model.mxCell;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.util.mxConstants;
import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxStylesheet;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;

/**
 * Zeigt die Abhaengigkeiten als ER-aehnliches Diagramm.
 * Tabellen als Boxen mit PK/FK-Spalten, Verbindungslinien mit Traversal-Status.
 */
public class DiagramDialog extends JDialog {

    // Farben
    private static final String COLOR_ROOT       = "#4A90D9";
    private static final String COLOR_TABLE      = "#5BA85B";
    private static final String COLOR_CONSTANT   = "#CCCCCC";
    private static final String COLOR_TRAVERSE   = "#2E7D32";
    private static final String COLOR_SKIP       = "#9E9E9E";
    private static final String COLOR_SUBSELECT  = "#1565C0";
    private static final String FONT_COLOR_LIGHT = "#FFFFFF";
    private static final String FONT_COLOR_DARK  = "#666666";

    public DiagramDialog(Window owner, TraversalResult result,
                         TraversalRuleStore ruleStore, ConstantTableStore constTableStore) {
        super(owner, "Abhängigkeitsdiagramm", ModalityType.MODELESS);

        mxGraph graph = new mxGraph();
        graph.setAllowDanglingEdges(false);
        graph.setCellsEditable(false);
        graph.setCellsResizable(false);
        graph.setConnectableEdges(false);
        graph.setCellsDisconnectable(false);

        setupStyles(graph);

        Object parent = graph.getDefaultParent();
        graph.getModel().beginUpdate();

        try {
            DependencyNode rootNode = result.getRootNode();
            Map<String, List<ForeignKeyRelation>> fkRelations = result.getFkRelations();
            Set<String> constTables = constTableStore != null ? constTableStore.getAsSet() : Collections.emptySet();

            // Tabellen sammeln (unique)
            Set<String> allTables = new LinkedHashSet<>();
            collectTables(rootNode, allTables);

            // PK- und FK-Spalten pro Tabelle sammeln
            Map<String, String> pkColumns = new LinkedHashMap<>();
            Map<String, Set<String>> fkColumns = new LinkedHashMap<>();
            collectColumns(rootNode, pkColumns);
            for (List<ForeignKeyRelation> fks : fkRelations.values()) {
                for (ForeignKeyRelation fk : fks) {
                    fkColumns.computeIfAbsent(fk.getChildTable().toUpperCase(), k -> new LinkedHashSet<>())
                        .add(fk.getFkColumn().toUpperCase());
                }
            }

            // Zeilenanzahl pro Tabelle
            Map<String, Integer> tableCounts = result.getTableCounts();

            // Vertices erstellen
            Map<String, Object> vertices = new LinkedHashMap<>();
            for (String table : allTables) {
                boolean isRoot = table.equalsIgnoreCase(rootNode.getTableName());
                boolean isConst = constTables.contains(table.toUpperCase());

                String label = buildLabel(table, tableCounts.getOrDefault(table, 0),
                    pkColumns.get(table), fkColumns.getOrDefault(table, Collections.emptySet()));

                String style = isConst ? "constantTable" : (isRoot ? "rootTable" : "normalTable");

                Object v = graph.insertVertex(parent, null, label,
                    0, 0, 180, 0, style);

                // Höhe dynamisch berechnen
                int lineCount = label.split("\n").length;
                int height = 30 + lineCount * 16;
                graph.getModel().getGeometry(v).setHeight(height);

                vertices.put(table.toUpperCase(), v);
            }

            // Edges erstellen (aus FK-Relationen)
            Set<String> edgeKeys = new HashSet<>();
            for (Map.Entry<String, List<ForeignKeyRelation>> entry : fkRelations.entrySet()) {
                for (ForeignKeyRelation fk : entry.getValue()) {
                    String parentTable = fk.getParentTable().toUpperCase();
                    String childTable = fk.getChildTable().toUpperCase();
                    String fkCol = fk.getFkColumn().toUpperCase();
                    String edgeKey = parentTable + ">" + childTable + "." + fkCol;

                    if (edgeKeys.contains(edgeKey)) continue;
                    edgeKeys.add(edgeKey);

                    Object from = vertices.get(parentTable);
                    Object to = vertices.get(childTable);
                    if (from == null || to == null) continue;

                    // Traversal-Status ermitteln
                    TraversalRule rule = ruleStore.getRule(parentTable, childTable, fkCol);
                    String statusText;
                    String edgeStyle;
                    if (rule == null) {
                        statusText = fkCol;
                        edgeStyle = "traverseEdge";
                    } else {
                        switch (rule) {
                            case TRAVERSE:  statusText = fkCol + " (JA)";       edgeStyle = "traverseEdge";  break;
                            case SUBSELECT: statusText = fkCol + " (SUBSELECT)"; edgeStyle = "subselectEdge"; break;
                            default:        statusText = fkCol + " (SKIP)";      edgeStyle = "skipEdge";      break;
                        }
                    }

                    boolean isConst = constTables.contains(childTable);
                    if (isConst) edgeStyle = "constantEdge";

                    graph.insertEdge(parent, null, statusText, from, to, edgeStyle);
                }
            }

            // Hierarchisches Layout
            mxHierarchicalLayout layout = new mxHierarchicalLayout(graph);
            layout.setInterRankCellSpacing(60);
            layout.setIntraCellSpacing(30);
            layout.setOrientation(SwingConstants.NORTH);
            layout.execute(parent);

        } finally {
            graph.getModel().endUpdate();
        }

        // Graph-Komponente
        mxGraphComponent graphComponent = new mxGraphComponent(graph);
        graphComponent.setConnectable(false);
        graphComponent.getViewport().setBackground(Color.WHITE);
        graphComponent.setWheelScrollingEnabled(false);
        graphComponent.setAutoScroll(false);

        // Zoom mit Mausrad
        graphComponent.addMouseWheelListener(e -> {
            if (e.getWheelRotation() < 0) {
                graphComponent.zoomIn();
            } else {
                graphComponent.zoomOut();
            }
        });

        // Legende
        JPanel legendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        legendPanel.add(createLegendItem(COLOR_ROOT, "Root-Tabelle"));
        legendPanel.add(createLegendItem(COLOR_TABLE, "Tabelle"));
        legendPanel.add(createLegendItem(COLOR_CONSTANT, "Konstantentabelle"));
        legendPanel.add(new JLabel("  Linien:"));
        legendPanel.add(createLineLegend(COLOR_TRAVERSE, "Traversiert"));
        legendPanel.add(createLineLegend(COLOR_SKIP, "Skip"));
        legendPanel.add(createLineLegend(COLOR_SUBSELECT, "Subselect"));

        JButton closeBtn = new JButton("Schließen");
        closeBtn.addActionListener(e -> dispose());
        legendPanel.add(Box.createHorizontalGlue());
        legendPanel.add(closeBtn);

        setLayout(new BorderLayout());
        add(graphComponent, BorderLayout.CENTER);
        add(legendPanel, BorderLayout.SOUTH);
        setSize(1000, 700);
        setLocationRelativeTo(owner);

        // Escape schließt den Dialog
        getRootPane().registerKeyboardAction(
            e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    private void setupStyles(mxGraph graph) {
        mxStylesheet stylesheet = graph.getStylesheet();

        // Root-Tabelle
        Map<String, Object> rootStyle = new HashMap<>();
        rootStyle.put(mxConstants.STYLE_SHAPE, mxConstants.SHAPE_RECTANGLE);
        rootStyle.put(mxConstants.STYLE_FILLCOLOR, COLOR_ROOT);
        rootStyle.put(mxConstants.STYLE_FONTCOLOR, FONT_COLOR_LIGHT);
        rootStyle.put(mxConstants.STYLE_FONTSIZE, 11);
        rootStyle.put(mxConstants.STYLE_FONTSTYLE, mxConstants.FONT_BOLD);
        rootStyle.put(mxConstants.STYLE_ROUNDED, true);
        rootStyle.put(mxConstants.STYLE_ARCSIZE, 8);
        rootStyle.put(mxConstants.STYLE_STROKECOLOR, "#3A70B0");
        rootStyle.put(mxConstants.STYLE_STROKEWIDTH, 2);
        rootStyle.put(mxConstants.STYLE_ALIGN, mxConstants.ALIGN_LEFT);
        rootStyle.put(mxConstants.STYLE_VERTICAL_ALIGN, mxConstants.ALIGN_TOP);
        rootStyle.put(mxConstants.STYLE_SPACING_LEFT, 8);
        rootStyle.put(mxConstants.STYLE_SPACING_TOP, 5);
        rootStyle.put(mxConstants.STYLE_WHITE_SPACE, "wrap");
        stylesheet.putCellStyle("rootTable", rootStyle);

        // Normale Tabelle
        Map<String, Object> normalStyle = new HashMap<>(rootStyle);
        normalStyle.put(mxConstants.STYLE_FILLCOLOR, COLOR_TABLE);
        normalStyle.put(mxConstants.STYLE_STROKECOLOR, "#4A8A4A");
        normalStyle.put(mxConstants.STYLE_FONTSTYLE, 0);
        stylesheet.putCellStyle("normalTable", normalStyle);

        // Konstantentabelle
        Map<String, Object> constStyle = new HashMap<>(rootStyle);
        constStyle.put(mxConstants.STYLE_FILLCOLOR, COLOR_CONSTANT);
        constStyle.put(mxConstants.STYLE_FONTCOLOR, FONT_COLOR_DARK);
        constStyle.put(mxConstants.STYLE_STROKECOLOR, "#AAAAAA");
        constStyle.put(mxConstants.STYLE_DASHED, true);
        constStyle.put(mxConstants.STYLE_FONTSTYLE, mxConstants.FONT_ITALIC);
        stylesheet.putCellStyle("constantTable", constStyle);

        // Edge: Traversiert
        Map<String, Object> traverseEdge = new HashMap<>();
        traverseEdge.put(mxConstants.STYLE_STROKECOLOR, COLOR_TRAVERSE);
        traverseEdge.put(mxConstants.STYLE_STROKEWIDTH, 2);
        traverseEdge.put(mxConstants.STYLE_FONTSIZE, 9);
        traverseEdge.put(mxConstants.STYLE_FONTCOLOR, COLOR_TRAVERSE);
        traverseEdge.put(mxConstants.STYLE_ENDARROW, mxConstants.ARROW_BLOCK);
        traverseEdge.put(mxConstants.STYLE_ENDSIZE, 8);
        stylesheet.putCellStyle("traverseEdge", traverseEdge);

        // Edge: Skip
        Map<String, Object> skipEdge = new HashMap<>(traverseEdge);
        skipEdge.put(mxConstants.STYLE_STROKECOLOR, COLOR_SKIP);
        skipEdge.put(mxConstants.STYLE_FONTCOLOR, COLOR_SKIP);
        skipEdge.put(mxConstants.STYLE_DASHED, true);
        stylesheet.putCellStyle("skipEdge", skipEdge);

        // Edge: Subselect
        Map<String, Object> subselectEdge = new HashMap<>(traverseEdge);
        subselectEdge.put(mxConstants.STYLE_STROKECOLOR, COLOR_SUBSELECT);
        subselectEdge.put(mxConstants.STYLE_FONTCOLOR, COLOR_SUBSELECT);
        subselectEdge.put(mxConstants.STYLE_STROKE_OPACITY, 80);
        stylesheet.putCellStyle("subselectEdge", subselectEdge);

        // Edge: Konstantentabelle
        Map<String, Object> constEdge = new HashMap<>(traverseEdge);
        constEdge.put(mxConstants.STYLE_STROKECOLOR, COLOR_CONSTANT);
        constEdge.put(mxConstants.STYLE_FONTCOLOR, FONT_COLOR_DARK);
        constEdge.put(mxConstants.STYLE_DASHED, true);
        stylesheet.putCellStyle("constantEdge", constEdge);
    }

    private String buildLabel(String table, int count, String pkCol, Set<String> fkCols) {
        StringBuilder sb = new StringBuilder();
        sb.append(table).append("  (").append(count).append(")\n");
        if (pkCol != null) {
            sb.append("PK  ").append(pkCol).append("\n");
        }
        for (String fk : fkCols) {
            sb.append("FK  ").append(fk).append("\n");
        }
        return sb.toString().trim();
    }

    private void collectTables(DependencyNode node, Set<String> tables) {
        tables.add(node.getTableName().toUpperCase());
        for (DependencyNode child : node.getChildren()) {
            collectTables(child, tables);
        }
    }

    private void collectColumns(DependencyNode node, Map<String, String> pkColumns) {
        String table = node.getTableName().toUpperCase();
        if (!pkColumns.containsKey(table) && node.getPkColumn() != null) {
            pkColumns.put(table, node.getPkColumn().toUpperCase());
        }
        for (DependencyNode child : node.getChildren()) {
            collectColumns(child, pkColumns);
        }
    }

    private JLabel createLegendItem(String hexColor, String text) {
        JLabel label = new JLabel("  " + text + "  ");
        label.setOpaque(true);
        label.setBackground(Color.decode(hexColor));
        label.setForeground(hexColor.equals(COLOR_CONSTANT) ? Color.DARK_GRAY : Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11));
        return label;
    }

    private JLabel createLineLegend(String hexColor, String text) {
        JLabel label = new JLabel("— " + text);
        label.setForeground(Color.decode(hexColor));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11));
        return label;
    }
}
