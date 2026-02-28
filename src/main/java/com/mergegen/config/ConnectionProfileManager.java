package com.mergegen.config;

import java.io.*;
import java.util.*;

/**
 * Verwaltet gespeicherte Datenbankverbindungsprofile.
 *
 * Jedes Profil wird als einzelne .properties-Datei im Verzeichnis
 * "connections/" (relativ zum Arbeitsverzeichnis) abgelegt.
 * Es wird kein Registry-Eintrag und keine systemweite Konfiguration geschrieben.
 */
public class ConnectionProfileManager {

    private static final String CONNECTIONS_DIR = "config/mergegen/connections";

    private final File directory;

    public ConnectionProfileManager() {
        this.directory = new File(CONNECTIONS_DIR);
    }

    /** Gibt alle gespeicherten Profilnamen alphabetisch sortiert zurück. */
    public List<String> listProfiles() {
        if (!directory.exists()) return Collections.emptyList();

        File[] files = directory.listFiles((d, name) -> name.endsWith(".properties"));
        if (files == null) return Collections.emptyList();

        List<String> names = new ArrayList<>();
        for (File f : files) {
            names.add(f.getName().replace(".properties", ""));
        }
        Collections.sort(names);
        return names;
    }

    /** Lädt ein Profil und gibt dessen Properties zurück. */
    public Properties load(String profileName) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(profileFile(profileName))) {
            props.load(fis);
        }
        return props;
    }

    /**
     * Speichert ein Verbindungsprofil unter dem gegebenen Namen.
     * Erlaubte Zeichen im Namen: Buchstaben, Ziffern, Leerzeichen, - _ ( )
     */
    public void save(String profileName, Properties props) throws IOException {
        validateName(profileName);
        ensureDirectoryExists();
        try (FileOutputStream fos = new FileOutputStream(profileFile(profileName))) {
            props.store(fos, "Oracle Merge Script Generator - Verbindungsprofil: " + profileName);
        }
    }

    /** Löscht ein Profil. Gibt true zurück wenn die Datei existierte und gelöscht wurde. */
    public boolean delete(String profileName) {
        return profileFile(profileName).delete();
    }

    /** Prüft ob ein Profil mit diesem Namen bereits existiert. */
    public boolean exists(String profileName) {
        return profileFile(profileName).exists();
    }

    private File profileFile(String profileName) {
        return new File(directory, profileName + ".properties");
    }

    private void ensureDirectoryExists() throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Konnte Verzeichnis nicht anlegen: " + directory.getAbsolutePath());
        }
    }

    /**
     * Prüft, ob der Profilname als Dateiname verwendet werden darf.
     *
     * Erlaubt: Wortzeichen (\w = Buchstaben, Ziffern, _), Leerzeichen,
     * Bindestrich, Punkt, runde Klammern – alles andere (z.B. / \ : * ?)
     * wäre als Dateiname auf Windows ungültig.
     */
    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Profilname darf nicht leer sein.");
        }
        if (!name.matches("[\\w\\s\\-().]+")) {
            throw new IllegalArgumentException(
                "Ungültiger Profilname. Erlaubt: Buchstaben, Ziffern, Leerzeichen, - _ ( )");
        }
    }
}
