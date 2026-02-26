package com.mergegen.gui;

import com.mergegen.config.AppSettings;
import com.mergegen.config.ConnectionProfileManager;
import com.mergegen.config.DatabaseConfig;
import com.mergegen.db.DatabaseConnection;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Properties;

/**
 * Tab zum Konfigurieren von Datenbankverbindungen und Ausgabeverzeichnis.
 *
 * Verbindungsprofile werden lokal im Ordner "connections/" gespeichert.
 * Das Ausgabeverzeichnis wird in "app.properties" gespeichert.
 * Kein Registry-Eintrag, keine systemweiten Pfade.
 */
public class SettingsPanel extends JPanel {

    private final ConnectionProfileManager profileManager = new ConnectionProfileManager();
    private final AppSettings              appSettings    = new AppSettings();

    // Profil-Auswahl
    private final DefaultComboBoxModel<String> profileModel = new DefaultComboBoxModel<>();
    private final JComboBox<String>  profileCombo  = new JComboBox<>(profileModel);
    private final JButton            loadBtn       = new JButton("Laden");
    private final JButton            deleteBtn     = new JButton("Löschen");

    // Verbindungsformular
    private final JTextField         nameField     = new JTextField(25);
    private final JTextField         hostField     = new JTextField(25);
    private final JTextField         portField     = new JTextField(6);
    private final JTextField         sidField      = new JTextField(15);
    private final JTextField         userField     = new JTextField(20);
    private final JPasswordField     passwordField = new JPasswordField(20);
    private final JTextField         schemaField   = new JTextField(20);

    // Ausgabeverzeichnis
    private final JTextField         outputDirField = new JTextField(40);

    // Statuszeile
    private final JLabel             statusLabel   = new JLabel(" ");

    public SettingsPanel() {
        setLayout(new BorderLayout(0, 12));
        setBorder(new EmptyBorder(16, 24, 16, 24));

        // CENTER: Verbindungsform + Ausgabeverzeichnis untereinander
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(buildForm());
        centerPanel.add(Box.createVerticalStrut(10));
        centerPanel.add(buildOutputDirSection());

        add(buildProfileBar(), BorderLayout.NORTH);
        add(centerPanel,       BorderLayout.CENTER);
        add(buildBottomArea(), BorderLayout.SOUTH);

        refreshProfileList();
        // Ausgabeverzeichnis aus app.properties laden
        outputDirField.setText(appSettings.getOutputDir());
    }

    // ── Profil-Auswahlleiste ──────────────────────────────────────────────────

    private JPanel buildProfileBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setBorder(new TitledBorder("Gespeicherte Verbindungen"));

        profileCombo.setPreferredSize(new Dimension(220, 26));
        bar.add(new JLabel("Profil:"));
        bar.add(profileCombo);
        bar.add(loadBtn);
        bar.add(deleteBtn);

        loadBtn.addActionListener(e -> loadSelectedProfile());
        deleteBtn.addActionListener(e -> deleteSelectedProfile());

        // Direkt beim Auswählen laden
        profileCombo.addActionListener(e -> {
            if (profileCombo.getSelectedItem() != null) loadSelectedProfile();
        });

        return bar;
    }

    private void loadSelectedProfile() {
        String selected = (String) profileCombo.getSelectedItem();
        if (selected == null) return;
        try {
            Properties props = profileManager.load(selected);
            nameField.setText(selected);
            // Neues Format (db.host/db.port/db.sid) oder Fallback auf db.url
            String h = props.getProperty("db.host");
            if (h != null && !h.isBlank()) {
                hostField.setText(h);
                portField.setText(props.getProperty("db.port", "1521"));
                sidField.setText(props.getProperty("db.sid", ""));
            } else {
                String url = props.getProperty("db.url", "");
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("@([^:]+):(\\d+)[:/](.+)").matcher(url);
                if (m.find()) {
                    hostField.setText(m.group(1));
                    portField.setText(m.group(2));
                    sidField.setText(m.group(3));
                } else {
                    hostField.setText("");
                    portField.setText("1521");
                    sidField.setText("");
                }
            }
            userField.setText(props.getProperty("db.user", ""));
            passwordField.setText(props.getProperty("db.password", ""));
            schemaField.setText(props.getProperty("db.schema", ""));
            setStatus("Profil geladen: " + selected, new Color(0, 130, 0));
        } catch (Exception ex) {
            setStatus("Fehler beim Laden: " + ex.getMessage(), Color.RED);
        }
    }

    private void deleteSelectedProfile() {
        String selected = (String) profileCombo.getSelectedItem();
        if (selected == null) return;

        int confirm = JOptionPane.showConfirmDialog(this,
            "Profil \"" + selected + "\" wirklich löschen?",
            "Profil löschen", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        if (profileManager.delete(selected)) {
            setStatus("Profil gelöscht: " + selected, new Color(0, 130, 0));
            refreshProfileList();
            clearForm();
        } else {
            setStatus("Profil konnte nicht gelöscht werden.", Color.RED);
        }
    }

    // ── Verbindungsformular ───────────────────────────────────────────────────

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new TitledBorder("Verbindungsdetails"));

        GridBagConstraints lbl = labelGbc();
        GridBagConstraints fld = fieldGbc();

        int row = 0;

        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("Name:"), lbl);
        form.add(nameField, fld);

        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("Host:"), lbl);
        form.add(hostField, fld);

        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("Port:"), lbl);
        portField.setText("1521");
        form.add(portField, fld);

        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("SID:"), lbl);
        form.add(sidField, fld);

        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("Benutzer:"), lbl);
        form.add(userField, fld);

        lbl.gridy = row; fld.gridy = row++;
        form.add(new JLabel("Passwort:"), lbl);
        form.add(passwordField, fld);

        lbl.gridy = row; fld.gridy = row;
        form.add(new JLabel("Schema:"), lbl);
        form.add(schemaField, fld);

        return form;
    }

    // ── Ausgabeverzeichnis ────────────────────────────────────────────────────

    /**
     * Baut die Sektion zur Auswahl des Ausgabeverzeichnisses.
     * Änderungen werden sofort in app.properties gespeichert.
     */
    private JPanel buildOutputDirSection() {
        JPanel section = new JPanel(new GridBagLayout());
        section.setBorder(new TitledBorder("Ausgabeverzeichnis für Merge Scripts"));

        JButton browseBtn = new JButton("Durchsuchen...");

        // Textfeld nicht direkt editierbar – Auswahl nur über Dialog
        outputDirField.setEditable(false);
        outputDirField.setBackground(UIManager.getColor("TextField.background"));

        GridBagConstraints fld = fieldGbc();
        fld.gridy = 0;
        section.add(outputDirField, fld);

        GridBagConstraints btn = new GridBagConstraints();
        btn.gridx  = 2;
        btn.gridy  = 0;
        btn.insets = new Insets(6, 6, 6, 8);
        section.add(browseBtn, btn);

        browseBtn.addActionListener(e -> chooseOutputDir());

        return section;
    }

    /**
     * Öffnet einen JFileChooser zur Verzeichnisauswahl.
     * Bei Bestätigung wird der Pfad gespeichert und sofort in app.properties geschrieben.
     */
    private void chooseOutputDir() {
        JFileChooser chooser = new JFileChooser(outputDirField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Ausgabeverzeichnis wählen");
        chooser.setAcceptAllFileFilterUsed(false);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            outputDirField.setText(selected.getAbsolutePath());
            // Sofort persistieren – kein separater Speichern-Button nötig
            appSettings.setOutputDir(selected.getAbsolutePath());
            setStatus("Ausgabeverzeichnis gesetzt: " + selected.getAbsolutePath(), new Color(0, 130, 0));
        }
    }

    // ── Unterer Bereich: Buttons + Status ─────────────────────────────────────

    private JPanel buildBottomArea() {
        JButton testBtn = new JButton("Verbindung testen");
        JButton saveBtn = new JButton("Speichern");

        testBtn.addActionListener(e -> testConnection());
        saveBtn.addActionListener(e -> saveProfile());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(testBtn);
        buttons.add(saveBtn);

        statusLabel.setForeground(Color.GRAY);

        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.add(buttons, BorderLayout.NORTH);
        bottom.add(statusLabel, BorderLayout.SOUTH);
        return bottom;
    }

    private void saveProfile() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            setStatus("Bitte einen Namen für das Profil eingeben.", Color.RED);
            return;
        }

        boolean overwrite = profileManager.exists(name);
        if (overwrite) {
            int confirm = JOptionPane.showConfirmDialog(this,
                "Profil \"" + name + "\" bereits vorhanden. Überschreiben?",
                "Profil überschreiben", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) return;
        }

        try {
            Properties props = buildProperties();
            profileManager.save(name, props);
            refreshProfileList();
            profileCombo.setSelectedItem(name);
            setStatus("Profil gespeichert: " + name, new Color(0, 130, 0));
        } catch (Exception ex) {
            setStatus("Fehler beim Speichern: " + ex.getMessage(), Color.RED);
        }
    }

    private void testConnection() {
        setStatus("Verbinde...", Color.GRAY);
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                try {
                    DatabaseConfig cfg = getCurrentConfig();
                    try (DatabaseConnection conn = new DatabaseConnection(cfg)) {
                        conn.get().isValid(5);
                    }
                    return "OK";
                } catch (Exception ex) {
                    return "FEHLER: " + ex.getMessage();
                }
            }
            @Override
            protected void done() {
                try {
                    String result = get();
                    if ("OK".equals(result)) setStatus("Verbindung erfolgreich.", new Color(0, 130, 0));
                    else                     setStatus(result, Color.RED);
                } catch (Exception ex) {
                    setStatus("FEHLER: " + ex.getMessage(), Color.RED);
                }
            }
        };
        worker.execute();
    }

    // ── Hilfsmethoden ─────────────────────────────────────────────────────────

    private void refreshProfileList() {
        String current = (String) profileCombo.getSelectedItem();
        profileModel.removeAllElements();
        List<String> profiles = profileManager.listProfiles();
        for (String p : profiles) profileModel.addElement(p);

        if (current != null && profileModel.getIndexOf(current) >= 0) {
            profileCombo.setSelectedItem(current);
        }
        deleteBtn.setEnabled(!profiles.isEmpty());
        loadBtn.setEnabled(!profiles.isEmpty());
    }

    private void clearForm() {
        nameField.setText("");
        hostField.setText("");
        portField.setText("1521");
        sidField.setText("");
        userField.setText("");
        passwordField.setText("");
        schemaField.setText("");
    }

    private Properties buildProperties() {
        Properties p = new Properties();
        p.setProperty("db.host",     hostField.getText().trim());
        p.setProperty("db.port",     portField.getText().trim());
        p.setProperty("db.sid",      sidField.getText().trim());
        p.setProperty("db.user",     userField.getText().trim());
        p.setProperty("db.password", new String(passwordField.getPassword()));
        p.setProperty("db.schema",   schemaField.getText().trim().toUpperCase());
        return p;
    }

    /** Für den Generator-Tab: liefert die aktuelle DB-Konfiguration aus den Feldern. */
    public DatabaseConfig getCurrentConfig() {
        return DatabaseConfig.fromProperties(buildProperties());
    }

    /** Für den Generator-Tab: liefert das gewählte Ausgabeverzeichnis. */
    public String getOutputDir() {
        return outputDirField.getText().trim();
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private static GridBagConstraints labelGbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx  = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(6, 8, 6, 10);
        return c;
    }

    private static GridBagConstraints fieldGbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx   = 1;
        c.fill    = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.insets  = new Insets(6, 0, 6, 8);
        return c;
    }
}
