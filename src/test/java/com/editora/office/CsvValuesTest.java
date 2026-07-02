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
}
