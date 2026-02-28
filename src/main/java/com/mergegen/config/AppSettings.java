package com.mergegen.config;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Verwaltet anwendungsweite Einstellungen, die unabhängig von
 * Datenbankverbindungsprofilen sind (z.B. Ausgabeverzeichnis).
 *
 * Gespeichert in "app.properties" im Arbeitsverzeichnis.
 * Nicht in der Windows-Registry oder in Systempfaden.
 */
public class AppSettings {

    private static final String SETTINGS_FILE  = "config/mergegen/app.properties";
    private static final String KEY_OUTPUT_DIR = "output.dir";
    private static final String KEY_LAST_TABLE  = "last.table";
    private static final String KEY_LAST_COLUMN = "last.column";
    private static final String KEY_LAST_VALUES = "last.values";

    /** Standardmäßig das Benutzer-Home-Verzeichnis (z.B. C:\Users\maddi). */
    private static final String DEFAULT_OUTPUT_DIR =
        System.getProperty("user.home");

    private final Properties props = new Properties();

    public AppSettings() {
        load();
    }

    /**
     * Gibt das konfigurierte Ausgabeverzeichnis zurück.
     * Fällt auf das Benutzer-Home zurück, wenn kein Verzeichnis gesetzt ist.
     */
    public String getOutputDir() {
        return props.getProperty(KEY_OUTPUT_DIR, DEFAULT_OUTPUT_DIR);
    }

    /** Gibt den zuletzt erfolgreich analysierten Tabellennamen zurück (leer wenn keiner gesetzt). */
    public String getLastTable() {
        return props.getProperty(KEY_LAST_TABLE, "");
    }

    /** Gibt den zuletzt verwendeten Spaltennamen zurück (leer wenn keiner gesetzt). */
    public String getLastColumn() {
        return props.getProperty(KEY_LAST_COLUMN, "");
    }

    /** Gibt die zuletzt eingegebenen Werte zurück (Pipe-separiert gespeichert). */
    public List<String> getLastValues() {
        String raw = props.getProperty(KEY_LAST_VALUES, "");
        if (raw.isEmpty()) return Collections.emptyList();
        return Arrays.stream(raw.split("\\|"))
                .collect(Collectors.toList());
    }

    /** Speichert die Werteliste Pipe-separiert in app.properties. */
    public void setLastValues(List<String> values) {
        props.setProperty(KEY_LAST_VALUES, String.join("|", values));
        save();
    }

    /** Speichert den letzten Spaltennamen sofort in app.properties. */
    public void setLastColumn(String column) {
        props.setProperty(KEY_LAST_COLUMN, column);
        save();
    }

    /** Speichert den letzten Tabellennamen sofort in app.properties. */
    public void setLastTable(String table) {
        props.setProperty(KEY_LAST_TABLE, table);
        save();
    }

    /**
     * Setzt das Ausgabeverzeichnis und speichert es sofort in app.properties.
     */
    public void setOutputDir(String path) {
        props.setProperty(KEY_OUTPUT_DIR, path);
        save();
    }

    /** Lädt die Einstellungen aus app.properties (fehlende Datei ist kein Fehler). */
    private void load() {
        File file = new File(SETTINGS_FILE);
        if (!file.exists()) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        } catch (IOException ex) {
            System.err.println("app.properties konnte nicht geladen werden: " + ex.getMessage());
        }
    }

    /** Schreibt die aktuellen Einstellungen in app.properties. */
    private void save() {
        File file = new File(SETTINGS_FILE);
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "Oracle Merge Script Generator - Anwendungseinstellungen");
        } catch (IOException ex) {
            System.err.println("app.properties konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }
}
