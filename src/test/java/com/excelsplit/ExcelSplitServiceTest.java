package com.excelsplit;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelSplitServiceTest {

    private ExcelSplitService service;
    private final List<Workbook> openWorkbooks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new ExcelSplitService();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Workbook wb : openWorkbooks) {
            wb.close();
        }
        openWorkbooks.clear();
    }

    // ── buildCsv ─────────────────────────────────────────────────────────────

    @Test
    void buildCsv_setzt_templateName_in_spalteC_fuer_alle_datenzeilen() {
        Workbook wb = createWorkbook(
                new String[][]{{"H1", "H2", "H3", "H4", "H5"}, {"A1", "B1", "alt", "D1", "E1"}, {"A2", "B2", "alt", "D2", "E2"}},
                new String[][]{{"TEMPLATE_X", "Wert1"}}
        );
        List<String> lines = service.buildCsv(wb.getSheetAt(0), "TEMPLATE_X", "Wert1");

        assertEquals(3, lines.size());
        // Kopfzeile: Spalte C unveraendert
        assertEquals("H3", lines.get(0).split(";", -1)[2]);
        // Datenzeilen: Spalte C = Template-Name
        assertEquals("TEMPLATE_X", lines.get(1).split(";", -1)[2]);
        assertEquals("TEMPLATE_X", lines.get(2).split(";", -1)[2]);
    }

    @Test
    void buildCsv_setzt_templateValue_nur_in_zeile2_spalteE() {
        Workbook wb = createWorkbook(
                new String[][]{{"H1", "H2", "H3", "H4", "H5"}, {"A1", "B1", "C1", "D1", "alt"}, {"A2", "B2", "C2", "D2", "alt"}},
                new String[][]{{"TPL", "W1"}}
        );
        List<String> lines = service.buildCsv(wb.getSheetAt(0), "TPL", "W1!W2!W3");

        // Zeile 2 (Index 1): Spalte E = verketteter Wert
        assertEquals("W1!W2!W3", lines.get(1).split(";", -1)[4]);
        // Zeile 3 (Index 2): Spalte E = Originalwert
        assertEquals("alt", lines.get(2).split(";", -1)[4]);
        // Kopfzeile: Spalte E = Originalwert
        assertEquals("H5", lines.get(0).split(";", -1)[4]);
    }

    @Test
    void buildCsv_kopfzeile_bleibt_unveraendert() {
        Workbook wb = createWorkbook(
                new String[][]{{"Kopf1", "Kopf2", "Kopf3"}},
                new String[][]{{"TPL", "V"}}
        );
        List<String> lines = service.buildCsv(wb.getSheetAt(0), "TPL", "V");

        assertEquals(1, lines.size());
        assertEquals("Kopf1;Kopf2;Kopf3", lines.get(0));
    }

    @Test
    void buildCsv_weniger_als_3_spalten_kein_spalteC_ersatz() {
        Workbook wb = createWorkbook(
                new String[][]{{"H1", "H2"}, {"A1", "B1"}},
                new String[][]{{"TPL", "V"}}
        );
        List<String> lines = service.buildCsv(wb.getSheetAt(0), "TPL", "V");

        assertEquals(2, lines.size());
        // Nur 2 Spalten – Spalte C wird nicht beschrieben
        assertEquals(2, lines.get(1).split(";", -1).length);
        assertEquals("A1", lines.get(1).split(";", -1)[0]);
    }

    @Test
    void buildCsv_weniger_als_5_spalten_kein_spalteE_ersatz() {
        Workbook wb = createWorkbook(
                new String[][]{{"H1", "H2", "H3", "H4"}, {"A1", "B1", "C1", "D1"}},
                new String[][]{{"TPL", "V"}}
        );
        List<String> lines = service.buildCsv(wb.getSheetAt(0), "TPL", "V");

        assertEquals(2, lines.size());
        // 4 Spalten – Spalte C wird ersetzt, Spalte E nicht vorhanden
        assertEquals("TPL", lines.get(1).split(";", -1)[2]);
        assertEquals(4, lines.get(1).split(";", -1).length);
    }

    // ── Sheet-2-Logik (processFiles Integration) ─────────────────────────────

    @Test
    void processFiles_erzeugt_csv_mit_verketteten_werten(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("output");

        Workbook wb = createWorkbook(
                new String[][]{{"H1", "H2", "H3", "H4", "H5"}, {"A", "B", "C", "D", "E"}},
                new String[][]{{"OBJEKT_X", "Wert1"}, {"OBJEKT_X", "Wert2"}, {"OBJEKT_X", "Wert3"}}
        );
        Path xlsxFile = writeWorkbook(wb, tempDir, "test.xlsx");

        service.processFiles(List.of(xlsxFile), outputDir, msg -> {});

        Path csv = outputDir.resolve("OBJEKT_X.csv");
        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertEquals("Wert1!Wert2!Wert3", lines.get(1).split(";", -1)[4]);
        assertEquals("OBJEKT_X", lines.get(1).split(";", -1)[2]);
    }

    @Test
    void processFiles_einzelner_wert_in_sheet2(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("output");

        Workbook wb = createWorkbook(
                new String[][]{{"H1", "H2", "H3", "H4", "H5"}, {"A", "B", "C", "D", "E"}},
                new String[][]{{"SINGLE", "NurEinWert"}}
        );
        Path xlsxFile = writeWorkbook(wb, tempDir, "single.xlsx");

        service.processFiles(List.of(xlsxFile), outputDir, msg -> {});

        Path csv = outputDir.resolve("SINGLE.csv");
        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals("NurEinWert", lines.get(1).split(";", -1)[4]);
    }

    @Test
    void processFiles_erzeugt_validierung_log(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("output");

        Workbook wb = createWorkbook(
                new String[][]{{"H1", "H2", "H3"}, {"D1", "D2", "D3"}},
                new String[][]{{"LOG_TEST", "V"}}
        );
        Path xlsxFile = writeWorkbook(wb, tempDir, "logtest.xlsx");

        Path logFile = service.processFiles(List.of(xlsxFile), outputDir, msg -> {});

        assertNotNull(logFile);
        assertTrue(Files.exists(logFile));
        String logContent = Files.readString(logFile, StandardCharsets.UTF_8);
        assertTrue(logContent.contains("LOG_TEST.csv"));
    }

    @Test
    void processFiles_sheet2_mit_leeren_zeilen_dazwischen(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("output");

        // Sheet 2: Zeile 1 + 3 haben Daten, Zeile 2 ist leer
        Workbook wb = new XSSFWorkbook();
        openWorkbooks.add(wb);
        Sheet s1 = wb.createSheet("Sheet1");
        Row h = s1.createRow(0); h.createCell(0).setCellValue("H1"); h.createCell(1).setCellValue("H2");
        h.createCell(2).setCellValue("H3"); h.createCell(3).setCellValue("H4"); h.createCell(4).setCellValue("H5");
        Row d = s1.createRow(1); d.createCell(0).setCellValue("A"); d.createCell(1).setCellValue("B");
        d.createCell(2).setCellValue("C"); d.createCell(3).setCellValue("D"); d.createCell(4).setCellValue("E");

        Sheet s2 = wb.createSheet("Sheet2");
        Row r0 = s2.createRow(0); r0.createCell(0).setCellValue("LUECKE"); r0.createCell(1).setCellValue("W1");
        s2.createRow(1); // leere Zeile
        Row r2 = s2.createRow(2); r2.createCell(0).setCellValue("LUECKE"); r2.createCell(1).setCellValue("W2");

        Path xlsxFile = writeWorkbook(wb, tempDir, "luecke.xlsx");
        service.processFiles(List.of(xlsxFile), outputDir, msg -> {});

        Path csv = outputDir.resolve("LUECKE.csv");
        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        // Leere Zeile wird uebersprungen, beide Werte gesammelt
        assertEquals("W1!W2", lines.get(1).split(";", -1)[4]);
    }

    @Test
    void processFiles_verschiedene_spalteA_nimmt_ersten_wert(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("output");

        Workbook wb = createWorkbook(
                new String[][]{{"H1", "H2", "H3", "H4", "H5"}, {"A", "B", "C", "D", "E"}},
                new String[][]{{"ERSTER", "W1"}, {"ANDERER", "W2"}}
        );
        Path xlsxFile = writeWorkbook(wb, tempDir, "mixed.xlsx");

        service.processFiles(List.of(xlsxFile), outputDir, msg -> {});

        // CSV-Dateiname basiert auf dem ersten Template-Namen
        Path csv = outputDir.resolve("ERSTER.csv");
        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        // Spalte C = erster Template-Name
        assertEquals("ERSTER", lines.get(1).split(";", -1)[2]);
        // Beide Spalte-B-Werte werden gesammelt
        assertEquals("W1!W2", lines.get(1).split(";", -1)[4]);
    }

    @Test
    void processFiles_leere_spalteB_ergibt_leeren_templateValue(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("output");

        Workbook wb = new XSSFWorkbook();
        openWorkbooks.add(wb);
        Sheet s1 = wb.createSheet("Sheet1");
        Row h = s1.createRow(0); h.createCell(0).setCellValue("H1"); h.createCell(1).setCellValue("H2");
        h.createCell(2).setCellValue("H3"); h.createCell(3).setCellValue("H4"); h.createCell(4).setCellValue("H5");
        Row d = s1.createRow(1); d.createCell(0).setCellValue("A"); d.createCell(1).setCellValue("B");
        d.createCell(2).setCellValue("C"); d.createCell(3).setCellValue("D"); d.createCell(4).setCellValue("alt");

        Sheet s2 = wb.createSheet("Sheet2");
        Row r0 = s2.createRow(0); r0.createCell(0).setCellValue("LEER_B");
        // Spalte B leer

        Path xlsxFile = writeWorkbook(wb, tempDir, "leerb.xlsx");
        service.processFiles(List.of(xlsxFile), outputDir, msg -> {});

        Path csv = outputDir.resolve("LEER_B.csv");
        assertTrue(Files.exists(csv));
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        // Spalte E Zeile 2 = leer (kein Wert in Spalte B)
        assertEquals("", lines.get(1).split(";", -1)[4]);
    }

    @Test
    void processFiles_leeres_sheet2_wird_uebersprungen(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("output");

        Workbook wb = new XSSFWorkbook();
        openWorkbooks.add(wb);
        wb.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("H1");
        wb.createSheet("Sheet2"); // leer

        Path xlsxFile = writeWorkbook(wb, tempDir, "empty_s2.xlsx");

        List<String> logMessages = new ArrayList<>();
        service.processFiles(List.of(xlsxFile), outputDir, logMessages::add);

        // Keine CSV erzeugt
        assertTrue(Files.list(outputDir).noneMatch(p -> p.toString().endsWith(".csv")));
        // Warnung geloggt
        assertTrue(logMessages.stream().anyMatch(m -> m.contains("WARNUNG")));
    }

    // ── escapeCsv ────────────────────────────────────────────────────────────

    @Test
    void escapeCsv_einfacher_wert() {
        assertEquals("Hallo", service.escapeCsv("Hallo"));
    }

    @Test
    void escapeCsv_mit_semikolon() {
        assertEquals("\"A;B\"", service.escapeCsv("A;B"));
    }

    @Test
    void escapeCsv_mit_anfuehrungszeichen() {
        assertEquals("\"A\"\"B\"", service.escapeCsv("A\"B"));
    }

    @Test
    void escapeCsv_mit_leerzeichen() {
        assertEquals("\"Hallo Welt\"", service.escapeCsv("Hallo Welt"));
    }

    @Test
    void escapeCsv_mit_zeilenumbruch() {
        assertEquals("\"Zeile1\nZeile2\"", service.escapeCsv("Zeile1\nZeile2"));
        assertEquals("\"Zeile1\rZeile2\"", service.escapeCsv("Zeile1\rZeile2"));
    }

    @Test
    void escapeCsv_formel_injection_alle_zeichen() {
        assertEquals("'=cmd", service.escapeCsv("=cmd"));
        assertEquals("'+test", service.escapeCsv("+test"));
        assertEquals("'-calc", service.escapeCsv("-calc"));
        assertEquals("'@sum", service.escapeCsv("@sum"));
    }

    @Test
    void escapeCsv_null_und_leer() {
        assertEquals("", service.escapeCsv(null));
        assertEquals("", service.escapeCsv(""));
    }

    // ── getCellValue ─────────────────────────────────────────────────────────

    @Test
    void getCellValue_string() {
        Workbook wb = new XSSFWorkbook();
        openWorkbooks.add(wb);
        Sheet s = wb.createSheet();
        Row r = s.createRow(0);
        r.createCell(0).setCellValue("Text");
        assertEquals("Text", service.getCellValue(r.getCell(0), wb));
    }

    @Test
    void getCellValue_ganzzahl() {
        Workbook wb = new XSSFWorkbook();
        openWorkbooks.add(wb);
        Sheet s = wb.createSheet();
        Row r = s.createRow(0);
        r.createCell(0).setCellValue(42.0);
        assertEquals("42", service.getCellValue(r.getCell(0), wb));
    }

    @Test
    void getCellValue_dezimalzahl() {
        Workbook wb = new XSSFWorkbook();
        openWorkbooks.add(wb);
        Sheet s = wb.createSheet();
        Row r = s.createRow(0);
        r.createCell(0).setCellValue(3.14);
        assertEquals("3.14", service.getCellValue(r.getCell(0), wb));
    }

    @Test
    void getCellValue_boolean() {
        Workbook wb = new XSSFWorkbook();
        openWorkbooks.add(wb);
        Sheet s = wb.createSheet();
        Row r = s.createRow(0);
        r.createCell(0).setCellValue(true);
        assertEquals("true", service.getCellValue(r.getCell(0), wb));
    }

    @Test
    void getCellValue_null_cell() {
        Workbook wb = new XSSFWorkbook();
        openWorkbooks.add(wb);
        assertEquals("", service.getCellValue(null, wb));
    }

    // ── listMasterFiles ──────────────────────────────────────────────────────

    @Test
    void listMasterFiles_findet_xlsx_und_xls(@TempDir Path tempDir) throws Exception {
        Files.createFile(tempDir.resolve("a.xlsx"));
        Files.createFile(tempDir.resolve("b.xls"));
        Files.createFile(tempDir.resolve("c.txt"));

        List<Path> result = service.listMasterFiles(tempDir);
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(p -> p.getFileName().toString().equals("a.xlsx")));
        assertTrue(result.stream().anyMatch(p -> p.getFileName().toString().equals("b.xls")));
    }

    @Test
    void listMasterFiles_sortiert_alphabetisch(@TempDir Path tempDir) throws Exception {
        Files.createFile(tempDir.resolve("c.xlsx"));
        Files.createFile(tempDir.resolve("a.xlsx"));
        Files.createFile(tempDir.resolve("b.xlsx"));

        List<Path> result = service.listMasterFiles(tempDir);
        assertEquals(3, result.size());
        assertEquals("a.xlsx", result.get(0).getFileName().toString());
        assertEquals("b.xlsx", result.get(1).getFileName().toString());
        assertEquals("c.xlsx", result.get(2).getFileName().toString());
    }

    @Test
    void listMasterFiles_leeres_verzeichnis(@TempDir Path tempDir) {
        List<Path> result = service.listMasterFiles(tempDir);
        assertTrue(result.isEmpty());
    }

    // ── Hilfsmethoden ────────────────────────────────────────────────────────

    private Workbook createWorkbook(String[][] sheet1Data, String[][] sheet2Data) {
        Workbook wb = new XSSFWorkbook();
        openWorkbooks.add(wb);
        Sheet s1 = wb.createSheet("Sheet1");
        for (int r = 0; r < sheet1Data.length; r++) {
            Row row = s1.createRow(r);
            for (int c = 0; c < sheet1Data[r].length; c++) {
                row.createCell(c).setCellValue(sheet1Data[r][c]);
            }
        }
        Sheet s2 = wb.createSheet("Sheet2");
        for (int r = 0; r < sheet2Data.length; r++) {
            Row row = s2.createRow(r);
            for (int c = 0; c < sheet2Data[r].length; c++) {
                row.createCell(c).setCellValue(sheet2Data[r][c]);
            }
        }
        return wb;
    }

    private Path writeWorkbook(Workbook wb, Path dir, String filename) throws Exception {
        Path file = dir.resolve(filename);
        try (var out = Files.newOutputStream(file)) {
            wb.write(out);
        }
        return file;
    }
}
