package com.editora.csv;

import java.util.List;

import org.junit.jupiter.api.Test;

import static com.editora.csv.CsvColumns.ColumnType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvColumnsTest {

    @Test
    void infersPerColumnTypesWithHeader() {
        List<List<String>> rows = CsvParser.parse(
                "name,age,price,active,notes\n" + "Ann,30,9.99,true,\n" + "Bob,25,-1.5,no,hi\n" + "Cy,40,3,yes,", ',');
        List<ColumnType> types = CsvColumns.inferTypes(rows, true);
        assertEquals(
                List.of(
                        ColumnType.TEXT, // names
                        ColumnType.INTEGER, // 30/25/40
                        ColumnType.DECIMAL, // 9.99/-1.5/3
                        ColumnType.BOOLEAN, // true/no/yes
                        ColumnType.TEXT), // one non-blank "hi", rest blank -> TEXT
                types);
    }

    @Test
    void emptyColumnAndHeaderExclusion() {
        // Second column is blank in every data row -> EMPTY. The header cell "n" is ignored.
        List<List<String>> rows = CsvParser.parse("h1,h2\n1,\n2,\n3,", ',');
        List<ColumnType> types = CsvColumns.inferTypes(rows, true);
        assertEquals(List.of(ColumnType.INTEGER, ColumnType.EMPTY), types);
    }

    @Test
    void withoutHeaderCountsEveryRow() {
        // No header: a text first row makes column 0 TEXT even though the rest are ints.
        List<List<String>> rows = CsvParser.parse("id\n1\n2", ',');
        assertEquals(List.of(ColumnType.TEXT), CsvColumns.inferTypes(rows, false));
        assertEquals(List.of(ColumnType.INTEGER), CsvColumns.inferTypes(rows, true));
    }

    @Test
    void isNumericCoversIntAndDecimal() {
        assertTrue(CsvColumns.isNumeric(ColumnType.INTEGER));
        assertTrue(CsvColumns.isNumeric(ColumnType.DECIMAL));
        assertFalse(CsvColumns.isNumeric(ColumnType.TEXT));
        assertFalse(CsvColumns.isNumeric(ColumnType.BOOLEAN));
        assertFalse(CsvColumns.isNumeric(ColumnType.EMPTY));
    }

    @Test
    void decimalAcceptsScientificNotationIntegerDoesNot() {
        List<List<String>> sci = CsvParser.parse("1e3\n2.5E-2", ',');
        assertEquals(List.of(ColumnType.DECIMAL), CsvColumns.inferTypes(sci, false));
        // A lone "+" / "." is not a number.
        List<List<String>> notNum = CsvParser.parse("+\n.", ',');
        assertEquals(List.of(ColumnType.TEXT), CsvColumns.inferTypes(notNum, false));
    }
}
