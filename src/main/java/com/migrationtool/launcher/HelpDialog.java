package com.migrationtool.launcher;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Hilfedialog mit Navigationsbaum (links) und HTML-Inhalt (rechts).
 * Lädt Hilfetexte aus Ressourcen-Dateien im Format:
 *   ## Abschnittsname   (wird zum Baumknoten)
 *   HTML-Inhalt          (wird rechts angezeigt)
 */
public class HelpDialog extends JDialog {

    private final JEditorPane contentPane;
    private final Map<String, String> sections = new LinkedHashMap<>();

    public HelpDialog(JFrame owner, String title, String resourcePath) {
        super(owner, title, true);

        loadSections(resourcePath);

        // ── Baum (links) ────────────────────────────────────────────────────
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(title);
        for (String name : sections.keySet()) {
            root.add(new DefaultMutableTreeNode(name));
        }

        JTree tree = new JTree(root);
        tree.setRootVisible(true);
        tree.expandRow(0);

        // ── Inhalt (rechts) ─────────────────────────────────────────────────
        contentPane = new JEditorPane();
        contentPane.setContentType("text/html");
        contentPane.setEditable(false);

        tree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
            if (node == null) return;

            String key = node.getUserObject().toString();
            String html = sections.get(key);
            if (html != null) {
                contentPane.setText(html);
            } else {
                // Root: alle Abschnitte zusammen anzeigen
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> entry : sections.entrySet()) {
                    sb.append("<h3>").append(entry.getKey()).append("</h3>");
                    sb.append(stripHtmlWrapper(entry.getValue()));
                }
                contentPane.setText(wrapHtml(sb.toString()));
            }
            contentPane.setCaretPosition(0);
        });

        // Ersten Abschnitt vorauswählen
        if (root.getChildCount() > 0) {
            tree.setSelectionPath(new TreePath(
                    ((DefaultMutableTreeNode) root.getFirstChild()).getPath()));
        }

        // ── Layout ──────────────────────────────────────────────────────────
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new Dimension(200, 400));

        JScrollPane contentScroll = new JScrollPane(contentPane);
        contentScroll.setPreferredSize(new Dimension(500, 400));

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, treeScroll, contentScroll);
        split.setDividerLocation(200);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);

        setSize(750, 500);
        setLocationRelativeTo(owner);
    }

    // ── Datei laden und in Abschnitte aufteilen ─────────────────────────────

    private void loadSections(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                sections.put("Fehler", wrapHtml(
                        "<p>Hilfedatei nicht gefunden: " + resourcePath + "</p>"));
                return;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));

            String line;
            String currentSection = null;
            StringBuilder content = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("## ")) {
                    if (currentSection != null) {
                        sections.put(currentSection, wrapHtml(content.toString()));
                    }
                    currentSection = line.substring(3).trim();
                    content = new StringBuilder();
                } else {
                    content.append(line).append("\n");
                }
            }
            if (currentSection != null) {
                sections.put(currentSection, wrapHtml(content.toString()));
            }
        } catch (IOException e) {
            sections.put("Fehler", wrapHtml(
                    "<p>Hilfe konnte nicht geladen werden: " + e.getMessage() + "</p>"));
        }
    }

    private static String wrapHtml(String body) {
        return "<html><body style='font-family:SansSerif; padding:8px;'>"
                + body + "</body></html>";
    }

    private static String stripHtmlWrapper(String html) {
        return html.replaceFirst("(?s)<html><body[^>]*>", "")
                   .replaceFirst("(?s)</body></html>", "");
    }
}
