package com.mergegen.generator;

import java.util.ArrayList;
import java.util.List;

/**
 * Zeilenweiser Diff-Algorithmus (LCS-basiert).
 * Vergleicht zwei Textdateien und liefert markierte Zeilen.
 */
public class LineDiff {

    public enum Type { SAME, ADDED, REMOVED }

    public static class Line {
        private final Type type;
        private final String text;

        public Line(Type type, String text) {
            this.type = type;
            this.text = text;
        }

        public Type getType() { return type; }
        public String getText() { return text; }
    }

    /**
     * Berechnet den zeilenweisen Diff zwischen zwei Texten.
     * @return Liste von Line-Objekten mit Type SAME/ADDED/REMOVED
     */
    public static List<Line> diff(String[] oldLines, String[] newLines) {
        // LCS-Laengen-Matrix
        int m = oldLines.length;
        int n = newLines.length;
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // Backtrack
        List<Line> result = new ArrayList<>();
        int i = m, j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                result.add(0, new Line(Type.SAME, oldLines[i - 1]));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                result.add(0, new Line(Type.ADDED, newLines[j - 1]));
                j--;
            } else {
                result.add(0, new Line(Type.REMOVED, oldLines[i - 1]));
                i--;
            }
        }

        return result;
    }
}
