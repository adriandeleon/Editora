package com.editora.office;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OdsWriterTest {

    @Test
    void contentXmlEmitsSpreadsheetWithTypedCells() {
        List<List<String>> rows =
                List.of(List.of("name", "age"), List.of("Ann", "34"), List.of("Bob", "07030")); // ZIP stays text
        String xml = OdsWriter.contentXml(rows, true);
        assertTrue(xml.contains("<office:spreadsheet>"), xml);
        assertTrue(xml.contains("table:name=\"Sheet1\""), xml);
        assertTrue(xml.contains("table:number-columns-repeated=\"2\""), xml);
        // Header cell bold + string-typed.
        assertTrue(xml.contains("table:style-name=\"ceHeader\""), xml);
        assertTrue(xml.contains("<text:p>name</text:p>"), xml);
        // A plain number → float cell with the numeric value.
        assertTrue(xml.contains("office:value-type=\"float\" office:value=\"34.0\""), xml);
        // A leading-zero code stays a string cell.
        assertTrue(xml.contains("office:value-type=\"string\"><text:p>07030</text:p>"), xml);
    }

    @Test
    void contentXmlEscapesAndHandlesRaggedRowsAndNoHeader() {
        List<List<String>> rows = List.of(List.of("a<b&c", "1"), List.of("x")); // ragged second row
        String xml = OdsWriter.contentXml(rows, false);
        assertTrue(xml.contains("<text:p>a&lt;b&amp;c</text:p>"), xml);
        // No header → the first row's number is typed float too.
        assertTrue(xml.contains("office:value-type=\"float\" office:value=\"1.0\""), xml);
        // Ragged row is padded to the column count with an empty string cell.
        assertTrue(xml.contains("<text:p></text:p>"), xml);
        // No header style anywhere.
        assertTrue(!xml.contains("ceHeader") || xml.indexOf("table:style-name=\"ceHeader\"") < 0, xml);
    }

    @Test
    void emptyRowsStillValidSingleColumnTable() {
        String xml = OdsWriter.contentXml(List.of(), true);
        assertTrue(xml.contains("table:number-columns-repeated=\"1\""), xml);
        assertTrue(xml.contains("</office:document-content>"), xml);
    }
}
