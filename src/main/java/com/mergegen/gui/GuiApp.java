package com.mergegen.gui;

import javax.swing.*;

/**
 * Einstiegspunkt für die GUI-Version des Oracle Merge Script Generators.
 *
 * Starten: java -cp "bin;lib/*" com.mergegen.gui.GuiApp
 */
public class GuiApp {

    public static void main(String[] args) {
        // System-Look & Feel für natürliches Aussehen unter Windows
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
