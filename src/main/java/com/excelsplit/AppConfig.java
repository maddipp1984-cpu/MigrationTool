package com.excelsplit;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Lädt und speichert Benutzereinstellungen in einer .properties-Datei
 * (kein Registry-Zugriff).
 */
public class AppConfig {

    private static final String KEY_MASTER = "masterDir";
    private static final String KEY_OUTPUT = "outputDir";

    private final Path       configFile;
    private final Properties props = new Properties();

    public AppConfig(Path basePath) {
        this.configFile = basePath.resolve("config/excelsplit/excel-split.properties");
        load();
    }

    public String getMasterDir(String defaultValue) {
        return props.getProperty(KEY_MASTER, defaultValue);
    }

    public void setMasterDir(String value) {
        props.setProperty(KEY_MASTER, value);
        save();
    }

    public String getOutputDir(String defaultValue) {
        return props.getProperty(KEY_OUTPUT, defaultValue);
    }

    public void setOutputDir(String value) {
        props.setProperty(KEY_OUTPUT, value);
        save();
    }

    private void load() {
        if (!Files.exists(configFile)) return;
        try (InputStream in = Files.newInputStream(configFile)) {
            props.load(in);
        } catch (IOException ignored) { }
    }

    private void save() {
        try {
            Files.createDirectories(configFile.getParent());
        } catch (IOException ignored) {}
        try (OutputStream out = Files.newOutputStream(configFile)) {
            props.store(out, "Excel Split – gespeicherte Verzeichnisse");
        } catch (IOException ignored) { }
    }
}
