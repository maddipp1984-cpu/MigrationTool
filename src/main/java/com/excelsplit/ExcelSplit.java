package com.excelsplit;

import javax.swing.*;
import java.nio.file.*;

/**
 * Entry Point – setzt L&F, erkennt Basispfad, verdrahtet MVP-Komponenten.
 */
public class ExcelSplit {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }

        Path basePath = detectBasePath(args);

        SwingUtilities.invokeLater(() -> {
            AppConfig          config  = new AppConfig(basePath);
            ExcelSplitService  service = new ExcelSplitService();
            MainWindow         window  = new MainWindow();
            new MainPresenter(window, service, config, basePath);
            window.show();
        });
    }

    /**
     * Öffnet das ExcelSplit-Fenster mit dem angegebenen Basispfad.
     * Wird vom Launcher aufgerufen (ohne main()-Umweg).
     */
    public static void openWindow(Path basePath) {
        SwingUtilities.invokeLater(() -> {
            AppConfig         config  = new AppConfig(basePath);
            ExcelSplitService service = new ExcelSplitService();
            MainWindow        window  = new MainWindow();
            new MainPresenter(window, service, config, basePath);
            window.show();
        });
    }

    /**
     * Sucht den Basispfad (Verzeichnis, das "master/" enthält):
     * 1. Explizites Argument (bat-Datei)
     * 2. Vom JAR-Speicherort aufwärts
     * 3. Aktuelles Arbeitsverzeichnis als Fallback
     */
    static Path detectBasePath(String[] args) {
        if (args.length > 0) {
            Path p = Paths.get(args[0]).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) return p;
        }
        try {
            Path jar = Paths.get(
                ExcelSplit.class.getProtectionDomain().getCodeSource().getLocation().toURI()
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
