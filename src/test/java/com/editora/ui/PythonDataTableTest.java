package com.editora.ui;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonDataTableTest {

    @Test
    void aPythonStringLiteralIsReadBackAsItsText() {
        String text = "{\"name\": \"it's\", \"path\": \"C:\\\\x\"}\n\ttab";
        assertEquals(text, PythonDataTable.unrepr(PythonDataTable.pythonString(text)));
        // As debugpy writes the helper's JSON: single quotes, a quote and backslashes escaped.
        assertEquals("{\"a\": \"it's\\n\"}", PythonDataTable.unrepr("'{\"a\": \"it\\'s\\\\n\"}'"));
        assertEquals("é€😀", PythonDataTable.unrepr("'\\xe9\\u20ac\\U0001f600'"));
        assertEquals("\\q", PythonDataTable.unrepr("'\\q'"), "an unknown escape keeps its letter");
        assertEquals("plain text", PythonDataTable.unrepr("plain text"), "not a literal: as it is");
    }

    @Test
    void theExpressionDefinesTheHelperApartAndCallsItOnTheValue() {
        String e = PythonDataTable.expression("frames['sales']", 1000, 1000);
        assertFalse(e.contains("\n"), "one line: an expression debugpy evaluates");
        assertTrue(e.startsWith("(lambda g: (exec('def _editora_table(o, s, n, mc):\\n"), e);
        assertTrue(
                e.endsWith(
                        "g['_editora_table']((frames['sales']), 1000, 1000, " + ValueTable.MAX_COLUMNS + "))[1])({})"),
                e);
    }

    @Test
    void aDataFramesPageBecomesRowsByColumn() {
        PythonDataTable.Page page = PythonDataTable.parse(PythonDataTable.pythonString(
                "{\"kind\": \"DataFrame\", \"shape\": [5000, 4], \"columns\": [\"id\", \"#\", \"id\", \"\"], "
                        + "\"dtypes\": [\"int64\", \"object\", \"int64\", \"bool\"], \"index\": [\"a\", \"b\"], "
                        + "\"rows\": [[\"1\", \"x\", \"2\", \"True\"], [\"3\", null, \"4\", \"False\"]], "
                        + "\"total\": 5000, \"width\": 70}"));
        assertNull(page.error());
        assertEquals("DataFrame", page.kind());
        assertEquals("5000 × 4", page.shapeText());
        assertEquals(5000, page.total());
        assertEquals(70, page.width());
        assertEquals(List.of("id", "# (2)", "id (2)", "(3)"), PythonDataTable.columnNames(page.columns()));
        List<ValueTable.Row> rows = PythonDataTable.rows(page);
        assertEquals(2, rows.size());
        assertEquals("a", rows.getFirst().key());
        assertEquals("x", rows.getFirst().cell("# (2)"));
        assertEquals("", rows.get(1).cell("# (2)"), "None is an empty cell");
        assertEquals(
                List.of("#", "id", "# (2)", "id (2)", "(3)"),
                ValueTable.of(rows).columns());
        assertEquals(
                Map.of("id", "int64", "# (2)", "object", "id (2)", "int64", "(3)", "bool"),
                PythonDataTable.dtypes(page));
    }

    @Test
    void aSeriesOrA1DArrayIsAColumnOfValues() {
        PythonDataTable.Page page = PythonDataTable.parse(PythonDataTable.pythonString(
                "{\"kind\": \"Series\", \"shape\": [2], \"leaf\": true, \"columns\": [\"price\"], "
                        + "\"dtypes\": [\"float64\"], \"index\": [\"0\", \"1\"], \"rows\": [[\"1.5\"], [\"nan\"]], "
                        + "\"total\": 2, \"width\": 1}"));
        List<ValueTable.Row> rows = PythonDataTable.rows(page);
        assertEquals(List.of("#", ValueTable.VALUE_COLUMN), ValueTable.of(rows).columns());
        assertEquals("nan", rows.get(1).value());
        assertEquals("2", page.shapeText());
    }

    @Test
    void whatTheHelperCannotShowSaysWhy() {
        assertEquals(
                "unsupported",
                PythonDataTable.parse("'{\"kind\": \"list\", \"error\": \"unsupported\"}'")
                        .error());
        assertEquals(
                "NameError: name 'df' is not defined",
                PythonDataTable.parse("NameError: name 'df' is not defined").error());
        assertEquals("", PythonDataTable.parse(null).error());
    }

    @Test
    void onlyAPythonSessionsDataIsShownAsData() {
        assertTrue(PythonDataTable.viewable("python", "DataFrame"));
        assertTrue(PythonDataTable.viewable("python", "GeoDataFrame"));
        assertTrue(PythonDataTable.viewable("python", "Series"));
        assertTrue(PythonDataTable.viewable("python", "ndarray"));
        assertFalse(PythonDataTable.viewable("python", "list"));
        assertFalse(PythonDataTable.viewable("java", "DataFrame"));
        assertFalse(PythonDataTable.viewable("python", null));
        assertTrue(PythonDataTable.frame("DataFrame"));
        assertTrue(PythonDataTable.frame("Series"));
        assertFalse(PythonDataTable.frame("ndarray"));
    }
}
