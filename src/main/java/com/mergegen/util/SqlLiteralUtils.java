package com.mergegen.util;

/**
 * Wandelt Benutzereingaben in Oracle-SQL-Literale um.
 */
public final class SqlLiteralUtils {
    private SqlLiteralUtils() {}

    /**
     * Wandelt einen Benutzereingabe-String in ein Oracle-SQL-Literal um.
     * Logik: Wenn der Wert als Long parsebar ist -> Zahl (kein Quoting).
     * Andernfalls -> String-Literal mit einfachen Hochkommata.
     */
    public static String toSqlLiteral(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Wert darf nicht leer sein.");
        }
        try {
            Long.parseLong(value.trim());
            return value.trim();
        } catch (NumberFormatException e) {
            return "'" + value.trim().replace("'", "''") + "'";
        }
    }
}
