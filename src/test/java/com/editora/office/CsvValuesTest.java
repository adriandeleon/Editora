package com.editora.office;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CsvValuesTest {

    @Test
    void plainNumbersParse() {
        assertEquals(42.0, CsvValues.numericValue("42"));
        assertEquals(-1.5, CsvValues.numericValue("-1.5"));
        assertEquals(0.0, CsvValues.numericValue("0"));
        assertEquals(0.5, CsvValues.numericValue("0.5"));
        assertEquals(1000.0, CsvValues.numericValue("1e3"));
        assertEquals(98500.5, CsvValues.numericValue(" 98500.50 ")); // trimmed
    }

    @Test
    void leadingZeroCodesStayText() {
        assertNull(CsvValues.numericValue("007"));
        assertNull(CsvValues.numericValue("07030")); // ZIP code
        assertNull(CsvValues.numericValue("-0042"));
    }

    @Test
    void nonNumbersStayText() {
        assertNull(CsvValues.numericValue("1,960")); // thousands separator
        assertNull(CsvValues.numericValue("$5"));
        assertNull(CsvValues.numericValue("hello"));
        assertNull(CsvValues.numericValue(""));
        assertNull(CsvValues.numericValue("   "));
        assertNull(CsvValues.numericValue(null));
        assertNull(CsvValues.numericValue("NaN"));
        assertNull(CsvValues.numericValue("Infinity"));
    }

    @Test
    void javaNumberSyntaxIsNotASpreadsheetNumber() {
        // Double.parseDouble accepts all of these; a spreadsheet user wrote text.
        assertNull(CsvValues.numericValue("12D"));
        assertNull(CsvValues.numericValue("5F"));
        assertNull(CsvValues.numericValue("3.5d"));
        assertNull(CsvValues.numericValue("1e3f"));
        assertNull(CsvValues.numericValue("0x1.8p1"));
        assertNull(CsvValues.numericValue("0x1F"));
        assertNull(CsvValues.numericValue("-Infinity"));
        assertNull(CsvValues.numericValue("1e"));
        assertNull(CsvValues.numericValue("."));
        assertNull(CsvValues.numericValue("1 2"));
        assertNull(CsvValues.numericValue("1e999")); // overflows to Infinity
        // …while every plain decimal form still is one.
        assertEquals(0.5, CsvValues.numericValue(".5"));
        assertEquals(5.0, CsvValues.numericValue("5."));
        assertEquals(12.0, CsvValues.numericValue("+12"));
        assertEquals(-0.0012, CsvValues.numericValue("-1.2E-3"));
    }

    @Test
    void valuesTooPreciseForADoubleStayText() {
        assertNull(CsvValues.numericValue("1234567890123456789")); // was 1.2345678901234568E18
        assertNull(CsvValues.numericValue("9007199254740993")); // 16 digits: 2^53 + 1 is not a double
        assertNull(CsvValues.numericValue("0.1234567890123456"));
        assertNull(CsvValues.numericValue("-1234567890.1234567"));
        assertEquals(123456789012345.0, CsvValues.numericValue("123456789012345")); // 15 digits: exact
        assertEquals(0.000123456789012345, CsvValues.numericValue("0.000123456789012345")); // leading zeros are free
        assertEquals(1.5e300, CsvValues.numericValue("1.5e300"));
    }
}
