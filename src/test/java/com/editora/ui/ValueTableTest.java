package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueTableTest {

    private static DapModels.VariableInfo v(String name, String value, int ref) {
        return new DapModels.VariableInfo(name, value, "", ref, 0, 0, false, null, null);
    }

    private static List<String> names(List<DapModels.VariableInfo> vars) {
        return vars.stream().map(DapModels.VariableInfo::name).toList();
    }

    @Test
    void theElementsAreWithoutTheDebuggersOwnRows() {
        // debugpy's list
        assertEquals(
                List.of("0", "1"),
                names(ValueTable.elements(List.of(
                        v("special variables", "", 4),
                        v("function variables", "", 5),
                        v("0", "<Order>", 6),
                        v("1", "<Order>", 7),
                        v("len()", "2", 0)))));
        // js-debug's array: its length is not an element
        assertEquals(
                List.of("0", "1"),
                names(ValueTable.elements(
                        List.of(v("0", "{…}", 2), v("1", "{…}", 3), v("length", "2", 0), v("[[Prototype]]", "", 9)))));
        // Delve's map and debugpy's dict: keys, not indexes
        assertEquals(
                List.of("\"a\"", "\"b\""),
                names(ValueTable.elements(List.of(v("len()", "2", 0), v("\"a\"", "1", 0), v("\"b\"", "2", 0)))));
        assertEquals(List.of("[0]", "[1]"), names(ValueTable.elements(List.of(v("[0]", "1", 0), v("[1]", "2", 0)))));
    }

    @Test
    void anElementsFieldsAndAPointersPointee() {
        assertEquals(
                List.of("n", "who"),
                names(ValueTable.fields(List.of(v("special variables", "", 8), v("n", "1", 0), v("who", "'a'", 0)))));
        assertEquals(1003, ValueTable.pointee(List.of(v("", "main.Order {N: 1}", 1003))), "Delve's *o");
        assertEquals(0, ValueTable.pointee(List.of(v("N", "1", 0))));
        assertEquals(0, ValueTable.pointee(List.of(v("", "x", 5), v("y", "1", 0))));
    }

    private static Map<String, String> fields(String... nameValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameValues.length; i += 2) {
            m.put(nameValues[i], nameValues[i + 1]);
        }
        return m;
    }

    @Test
    void theColumnsAreTheKeyThenEachFieldFirstSeen() {
        ValueTable objects = ValueTable.of(List.of(
                new ValueTable.Row("0", "<Order>", fields("n", "1", "who", "ann")),
                new ValueTable.Row("1", "<Special>", fields("n", "2", "rush", "true"))));
        assertEquals(List.of("#", "n", "who", "rush"), objects.columns());
        assertEquals("", objects.rows().getFirst().cell("rush"), "a field this element has not");

        ValueTable numbers = ValueTable.of(List.of(new ValueTable.Row("[0]", "7", Map.of())));
        assertEquals(List.of("#", ValueTable.VALUE_COLUMN), numbers.columns(), "no fields: the value");
        assertEquals("7", numbers.rows().getFirst().cell(ValueTable.VALUE_COLUMN));
        ValueTable loading = ValueTable.of(List.of(new ValueTable.Row("0", "<Order>", null)));
        assertEquals(List.of("#", ValueTable.VALUE_COLUMN), loading.columns(), "fields still coming");

        Map<String, String> wide = new LinkedHashMap<>();
        for (int i = 0; i < ValueTable.MAX_COLUMNS + 5; i++) {
            wide.put("f" + i, "x");
        }
        assertEquals(
                ValueTable.MAX_COLUMNS + 1,
                ValueTable.of(List.of(new ValueTable.Row("0", "", wide)))
                        .columns()
                        .size());
    }

    @Test
    void aFilterMatchesAnyCellAndTheTableCopiesAsCsvAndTsv() {
        ValueTable t = ValueTable.of(List.of(
                new ValueTable.Row("0", "<Order>", fields("n", "1", "who", "Ann, \"A\"")),
                new ValueTable.Row("1", "<Order>", fields("n", "2", "who", "bob"))));
        assertTrue(t.matches(t.rows().getFirst(), "ANN"));
        assertFalse(t.matches(t.rows().get(1), "ann"));
        assertTrue(t.matches(t.rows().get(1), " "), "blank: every row");
        assertEquals("#,n,who\n0,1,\"Ann, \"\"A\"\"\"\n1,2,bob\n", t.csv(t.rows()));
        assertEquals("#\tn\twho\n1\t2\tbob\n", t.tsv(List.of(t.rows().get(1))));
        ValueTable values = ValueTable.of(List.of(new ValueTable.Row("[0]", "a\tb", Map.of())));
        assertEquals("#\tvalue\n[0]\ta b\n", values.tsv(values.rows()));
    }

    @Test
    void aCountPicksItsWords() {
        com.editora.i18n.Messages.init("en");
        assertEquals("1 element, as at this stop.", com.editora.i18n.Messages.tr("valuetable.count", 1));
        assertEquals("2 elements, as at this stop.", com.editora.i18n.Messages.tr("valuetable.count", 2));
        assertEquals("Loading fields: 0 of 1 element…", com.editora.i18n.Messages.tr("valuetable.loadingFields", 0, 1));
    }

    @Test
    void columnsSortNumbersByValueThenText() {
        List<String> cells = new ArrayList<>(List.of("[10]", "bob", "[9]", "Ann", "-3", "2.5", ""));
        cells.sort(ValueTable::compare);
        assertEquals(List.of("-3", "2.5", "[9]", "[10]", "", "Ann", "bob"), cells);
        assertEquals(0, ValueTable.compare(null, ""));
    }
}
