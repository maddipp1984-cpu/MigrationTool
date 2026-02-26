package com.excelsplit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Business-Logik: Excel lesen, CSV schreiben, Log erstellen.
 * Keine UI-Abhängigkeiten.
 */
public class ExcelSplitService {

    /**
     * Gibt alle .xlsx-Dateien im angegebenen Verzeichnis sortiert zurück.
     */
    public List<Path> listMasterFiles(Path masterDir) {
        File[] files = masterDir.toFile().listFiles(
            (dir, name) -> name.toLowerCase().endsWith(".xlsx")
        );
        if (files == null || files.length == 0) return List.of();
        Arrays.sort(files);
        return Arrays.stream(files).map(File::toPath).collect(Collectors.toList());
    }

    /**
     * Verarbeitet alle übergebenen Dateien und gibt den Pfad zur Log-Datei zurück.
     *
     * @param files     zu verarbeitende .xlsx-Dateien
     * @param outputDir Ausgabeverzeichnis für CSV-Dateien
     * @param log       Callback für Fortschrittsmeldungen
     * @return Pfad zur erstellten validierung.log
     */
    public Path processFiles(List<Path> files, Path outputDir, Consumer<String> log) {
        if (!prepareOutputDir(outputDir, log)) return null;

        Path        logFile    = outputDir.resolve("validierung.log");
        List<String> logEntries = new ArrayList<>();
        int          total      = files.size();
        int          idx        = 0;

        for (Path xlsx : files) {
            idx++;
            log.accept("[" + idx + "/" + total + "] Verarbeite: " + xlsx.getFileName());
            processFile(xlsx, outputDir, log, logEntries);
        }

        writeLogFile(logFile, logEntries, log);
        return logFile;
    }

    // -------------------------------------------------------------------------
    // Private Hilfsmethoden
    // -------------------------------------------------------------------------

    private boolean prepareOutputDir(Path outputDir, Consumer<String> log) {
        try {
            Files.createDirectories(outputDir);
            try (var stream = Files.list(outputDir)) {
                stream.filter(p -> p.toString().endsWith(".csv"))
                      .forEach(p -> { try { Files.delete(p); } catch (IOException ex) { /* ignore */ } });
            }
            Files.deleteIfExists(outputDir.resolve("validierung.log"));
            return true;
        } catch (IOException e) {
            log.accept("FEHLER: Ausgabeverzeichnis konnte nicht vorbereitet werden: " + e.getMessage());
            return false;
        }
    }

    private void processFile(Path xlsx, Path outputDir, Consumer<String> log, List<String> logEntries) {
        try (Workbook wb = WorkbookFactory.create(xlsx.toFile(), null, true)) {

            Sheet  sheet2 = wb.getSheetAt(1);
            Row    row1   = (sheet2 != null) ? sheet2.getRow(0) : null;
            String templateName  = (row1 != null) ? getCellValue(row1.getCell(0), wb) : "";
            String templateValue = (row1 != null) ? getCellValue(row1.getCell(1), wb) : "";

            if (templateName.isEmpty()) {
                log.accept("  WARNUNG: Kein Template-Name in Sheet 2, Zeile 1 – übersprungen.");
                logEntries.add("WARNUNG " + xlsx.getFileName() + " - kein Template-Name");
                return;
            }

            String outName = templateName.endsWith(".csv") ? templateName : templateName + ".csv";
            log.accept("  Template : " + templateName);
            log.accept("  Datei    : " + outName);

            List<String> csvLines = buildCsv(wb.getSheetAt(0), templateName, templateValue);
            Files.write(outputDir.resolve(outName), csvLines, StandardCharsets.UTF_8);

            logEntries.add(validateResult(outName, wb.getSheetAt(0).getLastRowNum() + 1, csvLines, log));
            log.accept("  Erstellt: " + outputDir.resolve(outName).toAbsolutePath());

        } catch (Exception e) {
            log.accept("  FEHLER: " + e.getMessage());
            logEntries.add("FEHLER " + xlsx.getFileName() + " - " + e.getMessage());
        }
    }

    private List<String> buildCsv(Sheet sheet1, String templateName, String templateValue) {
        int maxCol = 0;
        for (Row row : sheet1) {
            if (row.getLastCellNum() > maxCol) maxCol = row.getLastCellNum();
        }

        List<String> lines = new ArrayList<>();
        Workbook wb = sheet1.getWorkbook();

        for (int r = 0; r <= sheet1.getLastRowNum(); r++) {
            Row      row   = sheet1.getRow(r);
            String[] cells = new String[maxCol];
            Arrays.fill(cells, "");

            if (row != null) {
                for (int c = 0; c < maxCol; c++) {
                    cells[c] = getCellValue(row.getCell(c), wb);
                }
            }

            // Spalte C (Index 2) = Template-Name in jeder Zeile außer der Kopfzeile
            if (maxCol > 2 && r > 0) cells[2] = templateName;
            // Spalte E (Index 4) = Template-Wert nur in Zeile 2
            if (r == 1 && maxCol > 4) cells[4] = templateValue;

            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < maxCol; c++) {
                if (c > 0) sb.append(';');
                sb.append(escapeCsv(cells[c]));
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    private String validateResult(String outName, int expectedRows, List<String> csvLines, Consumer<String> log) {
        int actualRows = csvLines.size();
        int cols       = csvLines.isEmpty() ? 0 : csvLines.get(0).split(";", -1).length;

        if (actualRows == expectedRows) {
            log.accept("  [OK] " + actualRows + " Zeilen, " + cols + " Spalten");
            return "OK " + outName + " - Zeilen: " + actualRows + ", Spalten: " + cols;
        } else {
            log.accept("  [WARNUNG] erwartet " + expectedRows + " Zeilen, erhalten " + actualRows);
            return "WARNUNG " + outName + " - Zeilen erwartet " + expectedRows + ", erhalten " + actualRows;
        }
    }

    private void writeLogFile(Path logFile, List<String> entries, Consumer<String> log) {
        try {
            Files.write(logFile, entries, StandardCharsets.UTF_8);
            log.accept("\nFertig. Log: " + logFile.toAbsolutePath());
        } catch (IOException e) {
            log.accept("FEHLER beim Schreiben des Logs: " + e.getMessage());
        }
    }

    private String getCellValue(Cell cell, Workbook wb) {
        if (cell == null) return "";

        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                type = wb.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(cell);
            } catch (Exception e) {
                return cell.toString();
            }
        }

        switch (type) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC: {
                double d = cell.getNumericCellValue();
                return (d == Math.floor(d) && !Double.isInfinite(d))
                        ? String.valueOf((long) d)
                        : String.valueOf(d);
            }
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            default:      return "";
        }
    }

    private String escapeCsv(String val) {
        if (val == null || val.isEmpty()) return "";
        if (val.contains(";") || val.contains("\"") || val.contains(" ")
                || val.contains("\n") || val.contains("\r")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
