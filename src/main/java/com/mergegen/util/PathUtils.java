package com.mergegen.util;

import java.nio.file.*;

/**
 * Gemeinsame Hilfsmethoden fuer Pfad-Erkennung.
 */
public final class PathUtils {
    private PathUtils() {}

    /**
     * Ermittelt den Basispfad: sucht vom JAR-Verzeichnis aufwaerts
     * nach einem "master/"-Ordner, sonst aktuelles Arbeitsverzeichnis.
     */
    public static Path detectBasePath(Class<?> referenceClass) {
        try {
            Path jar = Paths.get(
                referenceClass.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath();
            Path current = jar.getParent();
            for (int i = 0; i < 6; i++) {
                if (current == null) break;
                if (Files.isDirectory(current.resolve("master"))) return current;
                current = current.getParent();
            }
        } catch (Exception ignored) { }
        return Paths.get(".").toAbsolutePath().normalize();
    }
}
