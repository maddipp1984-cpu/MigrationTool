package com.excelsplit;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

/**
 * View-Implementierung (MVP).
 * Ausschließlich Swing-UI – keine Business-Logik.
 */
public class MainWindow {

    private JFrame     frame;
    private JPanel     masterListPanel;
    private JTextField masterDirField;
    private JTextField outputDirField;
    private JTextArea  logArea;
    private JButton    processButton;

    private final List<JCheckBox> checkBoxes = new ArrayList<>();

    // Handlers – werden vom Presenter registriert
    private Consumer<Path>   masterDirHandler;
    private Consumer<String> outputDirHandler;
    private Runnable         refreshHandler;
    private Runnable         processHandler;

    public MainWindow() {
        buildFrame();
    }

    // -------------------------------------------------------------------------
    // Frame-Aufbau
    // -------------------------------------------------------------------------

    private void buildFrame() {
        frame = new JFrame("Excel Split");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(720, 620);
        frame.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel topArea = new JPanel();
        topArea.setLayout(new BoxLayout(topArea, BoxLayout.Y_AXIS));
        topArea.add(buildMasterPanel());
        topArea.add(Box.createVerticalStrut(6));
        topArea.add(buildOutputPanel());
        topArea.add(Box.createVerticalStrut(6));
        topArea.add(buildProcessButtonPanel());
        topArea.add(Box.createVerticalStrut(6));

        root.add(topArea,        BorderLayout.NORTH);
        root.add(buildLogPanel(), BorderLayout.CENTER);

        frame.setContentPane(root);
    }

    private JPanel buildMasterPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(new TitledBorder("Masterdateien"));

        masterDirField = new JTextField();
        masterDirField.setEditable(false);

        JButton browseBtn = new JButton("...");
        browseBtn.setToolTipText("Anderen Ordner wählen");
        browseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(masterDirField.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Masterdatei-Ordner wählen");
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION && masterDirHandler != null) {
                masterDirHandler.accept(chooser.getSelectedFile().toPath());
            }
        });

        JPanel dirRow = new JPanel(new BorderLayout(4, 0));
        dirRow.add(new JLabel("Ordner: "), BorderLayout.WEST);
        dirRow.add(masterDirField,          BorderLayout.CENTER);
        dirRow.add(browseBtn,               BorderLayout.EAST);
        panel.add(dirRow, BorderLayout.NORTH);

        masterListPanel = new JPanel();
        masterListPanel.setLayout(new BoxLayout(masterListPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(masterListPanel);
        scroll.setPreferredSize(new Dimension(0, 150));
        panel.add(scroll, BorderLayout.CENTER);

        JButton allOn  = new JButton("Alle an");
        JButton allOff = new JButton("Alle ab");
        JButton reload = new JButton("Aktualisieren");
        allOn .addActionListener(e -> checkBoxes.forEach(cb -> cb.setSelected(true)));
        allOff.addActionListener(e -> checkBoxes.forEach(cb -> cb.setSelected(false)));
        reload.addActionListener(e -> { if (refreshHandler != null) refreshHandler.run(); });

        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttonsRow.add(allOn);
        buttonsRow.add(allOff);
        buttonsRow.add(Box.createHorizontalStrut(20));
        buttonsRow.add(reload);
        panel.add(buttonsRow, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(new TitledBorder("Ausgabeverzeichnis"));

        outputDirField = new JTextField();

        JButton browseBtn = new JButton("...");
        browseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(outputDirField.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Ausgabeverzeichnis wählen");
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                String selected = chooser.getSelectedFile().getAbsolutePath();
                outputDirField.setText(selected);
                if (outputDirHandler != null) outputDirHandler.accept(selected);
            }
        });

        panel.add(outputDirField, BorderLayout.CENTER);
        panel.add(browseBtn,      BorderLayout.EAST);
        return panel;
    }

    private JPanel buildProcessButtonPanel() {
        processButton = new JButton("Verarbeiten");
        processButton.setFont(processButton.getFont().deriveFont(Font.BOLD, 13f));
        processButton.addActionListener(e -> { if (processHandler != null) processHandler.run(); });

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        panel.add(processButton);
        return panel;
    }

    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Log"));

        logArea = new JTextArea();
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

    // -------------------------------------------------------------------------
    // MainView-Implementierung
    // -------------------------------------------------------------------------


    public void setMasterFiles(Path masterDir, List<Path> files) {
        masterDirField.setText(masterDir.toString());
        masterListPanel.removeAll();
        checkBoxes.clear();

        if (files.isEmpty()) {
            masterListPanel.add(new JLabel("<html><i>Keine .xlsx-Dateien gefunden.</i></html>"));
        } else {
            for (Path f : files) {
                JCheckBox cb = new JCheckBox(f.getFileName().toString(), true);
                cb.setActionCommand(f.toString());
                cb.setMaximumSize(new Dimension(Integer.MAX_VALUE, cb.getPreferredSize().height));
                checkBoxes.add(cb);
                masterListPanel.add(cb);
            }
        }
        masterListPanel.revalidate();
        masterListPanel.repaint();
    }


    public void setOutputDir(String dir) {
        outputDirField.setText(dir);
    }


    public void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }


    public void clearLog() {
        logArea.setText("");
    }


    public void setProcessingActive(boolean active) {
        processButton.setEnabled(!active);
    }


    public void showWarning(String message) {
        JOptionPane.showMessageDialog(frame, message, "Hinweis", JOptionPane.WARNING_MESSAGE);
    }


    public String getOutputDir() {
        return outputDirField.getText();
    }


    public List<Path> getSelectedFiles() {
        return checkBoxes.stream()
                .filter(JCheckBox::isSelected)
                .map(cb -> Path.of(cb.getActionCommand()))
                .collect(Collectors.toList());
    }


    public void onMasterDirSelected(Consumer<Path> handler)  { this.masterDirHandler = handler; }


    public void onOutputDirSelected(Consumer<String> handler) { this.outputDirHandler = handler; }


    public void onRefreshRequested(Runnable handler)          { this.refreshHandler   = handler; }


    public void onProcessRequested(Runnable handler)          { this.processHandler   = handler; }


    public void show() {
        frame.setVisible(true);
    }

    public JPanel getContentPanel() {
        return (JPanel) frame.getContentPane();
    }
}
