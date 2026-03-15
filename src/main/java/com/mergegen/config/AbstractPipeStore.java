package com.mergegen.config;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Abstrakte Basisklasse fuer Pipe-delimitierte Dateispeicher.
 * Gemeinsames Load/Save-Muster: Zeilen lesen, an | splitten, parsen/formatieren.
 *
 * @param <T> Typ der gespeicherten Eintraege
 */
public abstract class AbstractPipeStore<T> {

    private final File file;
    private final String[] headerComments;
    protected final List<T> entries = new ArrayList<>();

    protected AbstractPipeStore(String filePath, String... headerComments) {
        this.file = new File(filePath);
        this.headerComments = headerComments;
        load();
    }

    /** Parst eine aufgesplittete Zeile in ein Objekt. Null = Zeile ueberspringen. */
    protected abstract T parseLine(String[] parts);

    /** Formatiert ein Objekt als Pipe-delimitierte Zeile. */
    protected abstract String formatEntry(T entry);

    /** Minimale Anzahl Felder pro Zeile (Zeilen mit weniger werden uebersprungen). */
    protected abstract int minFieldCount();

    /** Gibt eine unveraenderliche Kopie aller Eintraege zurueck. */
    public List<T> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Laedt die Eintraege aus der Datei (fehlende Datei ist kein Fehler). */
    protected void load() {
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length < minFieldCount()) continue;
                T entry = parseLine(parts);
                if (entry != null) entries.add(entry);
            }
        } catch (IOException ex) {
            System.err.println(file.getName() + " konnte nicht geladen werden: " + ex.getMessage());
        }
    }

    /** Schreibt alle Eintraege in die Datei. */
    public void save() {
        file.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            for (String comment : headerComments) {
                writer.println("# " + comment);
            }
            for (T entry : entries) {
                writer.println(formatEntry(entry));
            }
        } catch (IOException ex) {
            System.err.println(file.getName() + " konnte nicht gespeichert werden: " + ex.getMessage());
        }
    }
}
