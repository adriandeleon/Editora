package com.editora.office;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end file-integrity checks for the CSV spreadsheet exporters (POI xlsx + hand-rolled ods). */
class SpreadsheetWriterTest {

    @Test
    void xlsxRoundTrips(@TempDir Path dir) throws Exception {
        List<List<String>> rows = List.of(List.of("name", "age"), List.of("Ann", "34"), List.of("Bob", "07030"));
        Path out = dir.resolve("out.xlsx");
        XlsxWriter.write(rows, true, out);

        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(out))) {
            Sheet sheet = wb.getSheetAt(0);
            // Header row: strings.
            assertEquals(CellType.STRING, sheet.getRow(0).getCell(0).getCellType());
            assertEquals("name", sheet.getRow(0).getCell(0).getStringCellValue());
            // A plain number → numeric cell.
            assertEquals(CellType.NUMERIC, sheet.getRow(1).getCell(1).getCellType());
            assertEquals(34.0, sheet.getRow(1).getCell(1).getNumericCellValue());
            // A ZIP code stays a string (leading zero preserved).
            assertEquals(CellType.STRING, sheet.getRow(2).getCell(1).getCellType());
            assertEquals("07030", sheet.getRow(2).getCell(1).getStringCellValue());
        }
    }

    @Test
    void textThatOnlyLooksNumericStaysTextInBothFormats(@TempDir Path dir) throws Exception {
        List<List<String>> rows = List.of(
                List.of("part", "size", "order", "price"), List.of("12D", "5F", "1234567890123456789", "10.50"));
        Path xlsx = dir.resolve("ids.xlsx");
        XlsxWriter.write(rows, true, xlsx);
        try (Workbook wb = new XSSFWorkbook(Files.newInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int c = 0; c < 3; c++) {
                assertEquals(CellType.STRING, sheet.getRow(1).getCell(c).getCellType(), "column " + c);
                assertEquals(rows.get(1).get(c), sheet.getRow(1).getCell(c).getStringCellValue());
            }
            assertEquals(CellType.NUMERIC, sheet.getRow(1).getCell(3).getCellType());
            assertEquals(10.5, sheet.getRow(1).getCell(3).getNumericCellValue());
        }

        String ods = OdsWriter.contentXml(rows, true);
        assertTrue(ods.contains("office:value-type=\"string\"><text:p>12D</text:p>"), ods);
        assertTrue(ods.contains("office:value-type=\"string\"><text:p>5F</text:p>"), ods);
        assertTrue(ods.contains("office:value-type=\"string\"><text:p>1234567890123456789</text:p>"), ods);
        assertTrue(ods.contains("office:value-type=\"float\" office:value=\"10.5\"><text:p>10.50</text:p>"), ods);
        assertTrue(!ods.contains("E18"), "no rounded scientific-notation value: " + ods);
    }

    @Test
    void odsHasMimetypeStoredFirst(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("out.ods");
        OdsWriter.write(List.of(List.of("h"), List.of("1")), true, out);

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            ZipEntry first = zip.getNextEntry();
            assertEquals("mimetype", first.getName()); // ODF: mimetype must be the first entry
            assertEquals(ZipEntry.STORED, first.getMethod()); // and uncompressed
            byte[] body = zip.readAllBytes();
            assertEquals(OdsWriter.MIMETYPE, new String(body, StandardCharsets.US_ASCII));
        }
        // The whole zip is present + parseable and contains the content + manifest entries.
        boolean content = false;
        boolean manifest = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(out))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals("content.xml")) {
                    content = true;
                }
                if (e.getName().equals("META-INF/manifest.xml")) {
                    manifest = true;
                }
            }
        }
        assertTrue(content, "content.xml present");
        assertTrue(manifest, "manifest present");
    }
}
