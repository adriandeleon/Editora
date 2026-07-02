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
