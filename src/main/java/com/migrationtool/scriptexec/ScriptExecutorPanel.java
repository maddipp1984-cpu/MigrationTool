package com.migrationtool.scriptexec;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Panel zur Auswahl und Ausführung von MERGE-Scripts auf der Ziel-Datenbank.
 * Scripts werden rekursiv im konfigurierten Ausgabeverzeichnis gesucht (MERGE_*.sql).
 * Alle ausgewählten Scripts werden in einer Transaktion ausgeführt;
 * bei einem Fehler wird ein vollständiger Rollback durchgeführt.
 */
public class ScriptExecutorPanel extends JPanel {

    private static final Path PROPS_FILE = Paths.get("script-executor.properties");

    private final ZielDbPanel           zielDbPanel;
    private final ScriptExecutorService service = new ScriptExecutorService();

    private final JTextField      dirField;
    private final JPanel          scriptListPanel;
    private final List<JCheckBox> checkBoxes = new ArrayList<>();
    private final JTextArea       logArea;
    private final JButton         executeBtn;

    public ScriptExecutorPanel(ZielDbPanel zielDbPanel) {
        this.zielDbPanel = zielDbPanel;
        setLayout(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        dirField        = new JTextField();
        scriptListPanel = new JPanel();
        logArea         = new JTextArea();
        executeBtn      = new JButton("Scripts ausführen");

        JPanel topArea = new JPanel();
        topArea.setLayout(new BoxLayout(topArea, BoxLayout.Y_AXIS));
        topArea.add(buildDirPanel());
        topArea.add(Box.createVerticalStrut(6));
        topArea.add(buildScriptListPanel());
        topArea.add(Box.createVerticalStrut(6));
        topArea.add(buildExecutePanel());
        topArea.add(Box.createVerticalStrut(6));

        add(topArea,          BorderLayout.NORTH);
        add(buildLogPanel(),  BorderLayout.CENTER);

        loadDir();
    }

    // ── Panel-Aufbau ──────────────────────────────────────────────────────────

    private JPanel buildDirPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(new TitledBorder("Script-Verzeichnis"));

        dirField.setEditable(false);

        JButton browseBtn = new JButton("…");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(dirField.getText().isEmpty() ? "." : dirField.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Ausgabeverzeichnis wählen");
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                String dir = fc.getSelectedFile().getAbsolutePath();
                dirField.setText(dir);
                saveDir(dir);
                scan(Paths.get(dir));
            }
        });

        JButton refreshBtn = new JButton("Aktualisieren");
        refreshBtn.addActionListener(e -> {
            String dir = dirField.getText().trim();
            if (!dir.isEmpty()) scan(Paths.get(dir));
        });

        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.add(dirField,   BorderLayout.CENTER);
        row.add(browseBtn,  BorderLayout.EAST);
        panel.add(row,        BorderLayout.CENTER);
        panel.add(refreshBtn, BorderLayout.EAST);
        return panel;
    }

    private JPanel buildScriptListPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(new TitledBorder("Scripts"));

        scriptListPanel.setLayout(new BoxLayout(scriptListPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(scriptListPanel);
        scroll.setPreferredSize(new Dimension(0, 130));
        panel.add(scroll, BorderLayout.CENTER);

        JButton allOn  = new JButton("Alle an");
        JButton allOff = new JButton("Alle ab");
        allOn .addActionListener(e -> checkBoxes.forEach(cb -> cb.setSelected(true)));
        allOff.addActionListener(e -> checkBoxes.forEach(cb -> cb.setSelected(false)));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnRow.add(allOn);
        btnRow.add(allOff);
        panel.add(btnRow, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildExecutePanel() {
        executeBtn.setFont(executeBtn.getFont().deriveFont(Font.BOLD, 13f));
        executeBtn.addActionListener(e -> executeManual());

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panel.add(executeBtn);
        return panel;
    }

    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Log"));

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        JButton clearBtn = new JButton("Log leeren");
        clearBtn.addActionListener(e -> logArea.setText(""));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT));
        south.add(clearBtn);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    // ── Script-Suche ─────────────────────────────────────────────────────────

    /** Sucht rekursiv nach MERGE_*.sql im Verzeichnis und füllt die Checkbox-Liste. */
    private void scan(Path dir) {
        scriptListPanel.removeAll();
        checkBoxes.clear();

        if (!Files.isDirectory(dir)) {
            scriptListPanel.add(new JLabel("<html><i>Verzeichnis nicht gefunden.</i></html>"));
            scriptListPanel.revalidate();
            scriptListPanel.repaint();
            return;
        }

        List<Path> found;
        try {
            found = Files.walk(dir)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("MERGE_") && name.endsWith(".sql");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            scriptListPanel.add(new JLabel("<html><i>Fehler beim Lesen: " + e.getMessage() + "</i></html>"));
            scriptListPanel.revalidate();
            scriptListPanel.repaint();
            return;
        }

        if (found.isEmpty()) {
            scriptListPanel.add(new JLabel("<html><i>Keine MERGE_*.sql-Dateien gefunden.</i></html>"));
        } else {
            for (Path f : found) {
                String fname = f.getFileName().toString();
                String label = fname.substring("MERGE_".length(), fname.length() - ".sql".length());
                JCheckBox cb = new JCheckBox(label, true);
                cb.setActionCommand(f.toString());
                cb.setMaximumSize(new Dimension(Integer.MAX_VALUE, cb.getPreferredSize().height));
                checkBoxes.add(cb);
                scriptListPanel.add(cb);
            }
        }
        scriptListPanel.revalidate();
        scriptListPanel.repaint();
    }

    // ── Ausführung ────────────────────────────────────────────────────────────

    /** Manuelle Ausführung über „Scripts ausführen"-Button (mit Ziel-DB-Prüfung). */
    private void executeManual() {
        if (!checkPrerequisites()) return;
        runScripts(getSelectedScripts(), result -> { /* Ergebnis bereits im Log */ });
    }

    /**
     * Automatische Ausführung für den Workflow.
     * Scannt das Verzeichnis neu und führt alle gefundenen Scripts aus.
     */
    public void executeAll(Consumer<Boolean> onComplete) {
        if (checkBoxes.isEmpty()) {
            String dir = dirField.getText().trim();
            if (!dir.isEmpty()) scan(Paths.get(dir));
        }
        if (!checkPrerequisites()) { onComplete.accept(false); return; }
        runScripts(getSelectedScripts(), onComplete);
    }

    private boolean checkPrerequisites() {
        if (zielDbPanel.getUrl().isEmpty()) {
            appendLog("FEHLER: Keine Ziel-DB konfiguriert (Einstellungen → Ziel-DB).");
            return false;
        }
        if (getSelectedScripts().isEmpty()) {
            appendLog("FEHLER: Keine Scripts ausgewählt.");
            return false;
        }
        return true;
    }

    private List<Path> getSelectedScripts() {
        return checkBoxes.stream()
                .filter(JCheckBox::isSelected)
                .map(cb -> Paths.get(cb.getActionCommand()))
                .collect(Collectors.toList());
    }

    private void runScripts(List<Path> scripts, Consumer<Boolean> onComplete) {
        logArea.setText("");
        appendLog("Starte Ausführung von " + scripts.size() + " Script(s)…");
        appendLog("Ziel: " + zielDbPanel.getUrl());
        appendLog("─────────────────────────────────────────");

        setExecuting(true);

        new SwingWorker<Boolean, String>() {
            @Override protected Boolean doInBackground() {
                return service.execute(scripts,
                        zielDbPanel.getUrl(),
                        zielDbPanel.getUser(),
                        zielDbPanel.getPassword(),
                        this::publish);
            }
            @Override protected void process(List<String> chunks) {
                chunks.forEach(ScriptExecutorPanel.this::appendLog);
            }
            @Override protected void done() {
                setExecuting(false);
                boolean success = false;
                try { success = get(); } catch (Exception ignored) {}
                onComplete.accept(success);
            }
        }.execute();
    }

    private void setExecuting(boolean active) {
        executeBtn.setEnabled(!active);
    }

    private void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    // ── Persistenz des Verzeichnisses ─────────────────────────────────────────

    private void loadDir() {
        if (Files.exists(PROPS_FILE)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(PROPS_FILE)) {
                p.load(in);
                String dir = p.getProperty("script.dir", "").trim();
                if (!dir.isEmpty()) {
                    dirField.setText(dir);
                    scan(Paths.get(dir));
                    return;
                }
            } catch (IOException ignored) {}
        }
        // Fallback: MergeGen-Ausgabeverzeichnis aus app.properties
        Path appProps = Paths.get("config/mergegen/app.properties");
        if (Files.exists(appProps)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(appProps)) {
                p.load(in);
                String dir = p.getProperty("output.dir", "").trim();
                if (!dir.isEmpty()) {
                    dirField.setText(dir);
                    scan(Paths.get(dir));
                }
            } catch (IOException ignored) {}
        }
    }

    private void saveDir(String dir) {
        Properties p = new Properties();
        p.setProperty("script.dir", dir);
        try (var out = Files.newOutputStream(PROPS_FILE)) {
            p.store(out, null);
        } catch (IOException ignored) {}
    }
}
