package com.mergegen.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hält die Verbindungsparameter für eine Oracle-Datenbankverbindung.
 *
 * Instanzen sind unveränderlich (immutable): alle Felder sind final,
 * es gibt keinen öffentlichen Konstruktor. Erzeugung erfolgt über
 * die statischen Factory-Methoden load() oder fromProperties().
 */
public class DatabaseConfig {

    private static final Pattern URL_PATTERN = Pattern.compile("@([^:]+):(\\d+)[:/](.+)");

    private final String host;
    private final String port;
    private final String sid;
    private final String user;
    private final String password;
    private final String schema;

    private DatabaseConfig(String host, String port, String sid,
                           String user, String password, String schema) {
        this.host = host;
        this.port = port;
        this.sid = sid;
        this.user = user;
        this.password = password;
        this.schema = schema;
    }

    /** Erstellt eine DatabaseConfig direkt aus einem Properties-Objekt (für die GUI). */
    public static DatabaseConfig fromProperties(Properties props) {
        String host;
        String port;
        String sid;

        // Neues Format: db.host / db.port / db.sid
        String h = props.getProperty("db.host");
        if (h != null && !h.isBlank()) {
            host = h.trim();
            port = props.getProperty("db.port", "1521").trim();
            sid  = requireProperty(props, "db.sid");
        } else {
            // Abwärtskompatibilität: altes db.url parsen
            String url = requireProperty(props, "db.url");
            String[] parts = parseUrl(url);
            host = parts[0];
            port = parts[1];
            sid  = parts[2];
        }

        String user     = requireProperty(props, "db.user");
        String password  = requireProperty(props, "db.password");
        String schema    = requireProperty(props, "db.schema");
        return new DatabaseConfig(host, port, sid, user, password, schema.toUpperCase());
    }

    /**
     * Liest die Konfiguration aus einer .properties-Datei (z.B. db.properties).
     * Wird vom CLI (App.java) verwendet.
     */
    public static DatabaseConfig load(String propertiesPath) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(propertiesPath)) {
            props.load(fis);
        }
        return fromProperties(props);
    }

    /**
     * Parst eine JDBC-URL im Format jdbc:oracle:thin:@host:port:sid
     * oder jdbc:oracle:thin:@host:port/service.
     */
    private static String[] parseUrl(String url) {
        Matcher m = URL_PATTERN.matcher(url);
        if (!m.find()) {
            throw new IllegalArgumentException(
                "JDBC-URL konnte nicht geparst werden: " + url);
        }
        return new String[] { m.group(1), m.group(2), m.group(3) };
    }

    /**
     * Liest einen Pflicht-Wert aus den Properties und wirft eine aussagekräftige
     * Exception, wenn der Schlüssel fehlt oder leer ist.
     */
    private static String requireProperty(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Pflichtfeld fehlt in db.properties: " + key);
        }
        return value.trim();
    }

    /** Baut die JDBC-URL aus Host, Port und SID zusammen. */
    public String getUrl() {
        return "jdbc:oracle:thin:@" + host + ":" + port + ":" + sid;
    }

    public String getHost()     { return host; }
    public String getPort()     { return port; }
    public String getSid()      { return sid; }
    public String getUser()     { return user; }
    public String getPassword() { return password; }
    public String getSchema()   { return schema; }
}
