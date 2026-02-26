package com.migrationtool.launcher;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;

/**
 * Einstellungen für die Ziel-Datenbankverbindung.
 * Gespeichert in ziel-db.properties im Arbeitsverzeichnis.
 */
public class ZielDbPanel extends JPanel {

    private static final Path PROPS_FILE = Paths.get("ziel-db.properties");

    private final JTextField     urlField;
    private final JTextField     userField;
    private final JPasswordField passField;
    private final JLabel         statusLabel;

    public ZielDbPanel() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(20, 30, 20, 30));

        urlField  = new JTextField();
        userField = new JTextField();
        passField = new JPasswordField();

        // ── Formular ──────────────────────────────────────────────────────────
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0;
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(6, 0, 6, 12);
        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1;
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.weightx = 1.0;
        fc.insets = new Insets(6, 0, 6, 0);

        lc.gridy = 0; fc.gridy = 0;
        form.add(new JLabel("JDBC-URL:"), lc);
        form.add(urlField, fc);

        lc.gridy = 1; fc.gridy = 1;
        form.add(new JLabel("Benutzer:"), lc);
        form.add(userField, fc);

        lc.gridy = 2; fc.gridy = 2;
        form.add(new JLabel("Passwort:"), lc);
        form.add(passField, fc);

        // URL-Hinweis
        JLabel hint = new JLabel("Beispiel: jdbc:oracle:thin:@//host:1521/service");
        hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 10f));
        hint.setForeground(Color.GRAY);
        lc.gridy = 3; fc.gridy = 3;
        form.add(new JLabel(), lc);
        form.add(hint, fc);

        // ── Buttons ───────────────────────────────────────────────────────────
        JButton saveBtn = new JButton("Speichern");
        JButton testBtn = new JButton("Verbindung testen");
        statusLabel = new JLabel(" ");

        saveBtn.addActionListener(e -> save());
        testBtn.addActionListener(e -> testConnection());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btnRow.add(saveBtn);
        btnRow.add(Box.createHorizontalStrut(10));
        btnRow.add(testBtn);
        btnRow.add(Box.createHorizontalStrut(16));
        btnRow.add(statusLabel);

        lc.gridy = 4; fc.gridy = 4;
        form.add(new JLabel(), lc);
        form.add(btnRow, fc);

        // ── Wrapper mit Rahmen ────────────────────────────────────────────────
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new TitledBorder("Ziel-Datenbankverbindung"));
        wrapper.add(form, BorderLayout.NORTH);
        add(wrapper, BorderLayout.NORTH);

        load();
    }

    // ── Persistenz ────────────────────────────────────────────────────────────

    private void load() {
        urlField.setText("jdbc:oracle:thin:@//host:1521/service");
        if (!Files.exists(PROPS_FILE)) return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(PROPS_FILE)) {
            p.load(in);
        } catch (IOException ignored) { return; }
        urlField.setText(p.getProperty("url",      "jdbc:oracle:thin:@//host:1521/service"));
        userField.setText(p.getProperty("user",    ""));
        passField.setText(p.getProperty("password",""));
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("url",      getUrl());
        p.setProperty("user",     getUser());
        p.setProperty("password", getPassword());
        try (var out = Files.newOutputStream(PROPS_FILE)) {
            p.store(out, null);
            setStatus("Gespeichert", new Color(0, 140, 0));
        } catch (IOException e) {
            setStatus("Fehler beim Speichern", new Color(180, 0, 0));
        }
    }

    // ── Verbindungstest ───────────────────────────────────────────────────────

    private void testConnection() {
        String url  = getUrl();
        String user = getUser();
        String pass = getPassword();
        if (url.isEmpty()) { setStatus("Bitte URL eingeben", new Color(180, 0, 0)); return; }

        setStatus("Verbinde…", Color.GRAY);

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() {
                try {
                    Class.forName("oracle.jdbc.OracleDriver");
                    try (Connection c = DriverManager.getConnection(url, user, pass)) {
                        String ver = c.getMetaData().getDatabaseProductVersion();
                        return "OK – " + ver.lines().findFirst().orElse(ver);
                    }
                } catch (Exception e) {
                    return "Fehler: " + e.getMessage();
                }
            }
            @Override protected void done() {
                try {
                    String result = get();
                    setStatus(result, result.startsWith("OK")
                            ? new Color(0, 140, 0) : new Color(180, 0, 0));
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    // ── Getter für andere Panels ──────────────────────────────────────────────

    public String getUrl()      { return urlField.getText().trim(); }
    public String getUser()     { return userField.getText().trim(); }
    public String getPassword() { return new String(passField.getPassword()); }
}
