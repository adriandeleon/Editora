package com.editora.office;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Writes parsed CSV/TSV rows to an Excel {@code .xlsx} workbook via Apache POI (XSSF). One sheet, one row
 * per record; a cell that {@link CsvValues#numericValue} judges safe becomes a numeric cell (so sums/charts
 * work in Excel), everything else stays text. When {@code hasHeader}, the first row is bold.
 */
public final class XlsxWriter {

    private XlsxWriter() {}

    public static void write(List<List<String>> rows, boolean hasHeader, Path out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                List<String> cells = rows.get(r);
                boolean header = r == 0 && hasHeader;
                for (int c = 0; c < cells.size(); c++) {
                    Cell cell = row.createCell(c);
                    String v = cells.get(c);
                    Double num = header ? null : CsvValues.numericValue(v);
                    if (num != null) {
                        cell.setCellValue(num);
                    } else {
                        cell.setCellValue(v);
                        if (header) {
                            cell.setCellStyle(headerStyle);
                        }
                    }
                }
            }
            try (OutputStream os = Files.newOutputStream(out)) {
                wb.write(os);
            }
        }
    }
}
