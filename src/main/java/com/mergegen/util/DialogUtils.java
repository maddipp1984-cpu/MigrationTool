package com.mergegen.util;

import javax.swing.*;
import java.awt.event.KeyEvent;

/**
 * Gemeinsame Dialog-Hilfsmethoden.
 */
public final class DialogUtils {
    private DialogUtils() {}

    /** Registriert Escape-Taste zum Schliessen eines Dialogs. */
    public static void addEscapeClose(JDialog dialog) {
        dialog.getRootPane().registerKeyboardAction(
            e -> dialog.dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
    }
}
