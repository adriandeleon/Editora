package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

/**
 * View as Table (IntelliJ's and Visual Studio's table view of a collection): a list, array, slice, set or map of the
 * debugged program as rows, one per element, and columns, one per field the elements have. Pure: what the debugger
 * gave is turned into the table here, the window ({@link DebugTableWindow}) only shows it.
 *
 * <p>What a debugger lists under a collection is more than its elements: debugpy adds its {@code special variables},
 * {@code function variables} and {@code len()} rows, Delve a map's {@code len()}, js-debug an array's {@code length}
 * and {@code [[Prototype]]}. Those are not rows ({@link #elements}), nor columns ({@link #fields}). A pointer's fields
 * are a level down, under the one unnamed row of what it points to (Delve's {@code *o}): {@link #pointee}.
 */
record ValueTable(List<String> columns, List<Row> rows) {

    /** The most elements a table loads: a collection with more shows these, and says how many it has. */
    static final int MAX_ROWS = 1000;

    /** The most field columns a table has: an element with more fields shows these. */
    static final int MAX_COLUMNS = 60;

    /** The column naming each row: its index, or its key in a map. */
    static final String KEY_COLUMN = "#";

    /** The column of an element's own value, there when an element has no fields (a number, a string). */
    static final String VALUE_COLUMN = "";

    /**
     * One element: its index or key, its value as the debugger shows it, and its fields' values by name ({@code null}
     * while they load: the row is shown with its value meanwhile).
     */
    record Row(String key, String value, @Nullable Map<String, String> fields) {
        /** The text of {@code column} in this row; "" for a field it does not have. */
        String cell(String column) {
            if (KEY_COLUMN.equals(column)) {
                return key;
            }
            if (VALUE_COLUMN.equals(column)) {
                return value;
            }
            Map<String, String> f = fields;
            String v = f == null ? null : f.get(column);
            return v == null ? "" : v;
        }
    }

    /** Rows a debugger lists beside a collection's elements, and beside an object's fields, which are neither. */
    private static final Set<String> NOT_ELEMENTS = Set.of(
            "special variables",
            "function variables",
            "class variables",
            "protected variables",
            "len()",
            "__proto__",
            "[Raw View]");

    private static final Pattern INDEX = Pattern.compile("\\[?\\d+]?");

    /**
     * The elements among {@code children}: without the debugger's own rows ({@link #NOT_ELEMENTS}, js-debug's
     * {@code [[…]]}), and, when some are indexed ({@code 0}, {@code [0]}), only those — an array's {@code length} is
     * not one.
     */
    static List<DapModels.VariableInfo> elements(List<DapModels.VariableInfo> children) {
        List<DapModels.VariableInfo> kept = fields(children);
        boolean indexed = kept.stream().anyMatch(v -> INDEX.matcher(v.name()).matches());
        return indexed
                ? kept.stream().filter(v -> INDEX.matcher(v.name()).matches()).toList()
                : kept;
    }

    /** An element's fields among {@code children}: without the debugger's own rows. */
    static List<DapModels.VariableInfo> fields(List<DapModels.VariableInfo> children) {
        return children.stream()
                .filter(v -> !NOT_ELEMENTS.contains(v.name()) && !v.name().startsWith("[["))
                .toList();
    }

    /**
     * When {@code fields} is what a pointer lists — one unnamed row holding what it points to, which has fields —
     * that row's reference; else 0.
     */
    static int pointee(List<DapModels.VariableInfo> fields) {
        if (fields.size() == 1 && fields.getFirst().name().isEmpty()) {
            return fields.getFirst().variablesReference();
        }
        return 0;
    }

    /**
     * The table of {@code rows}: the key column, the value column when an element has no fields (or its fields are
     * still loading), then every field name in the order first seen, up to {@link #MAX_COLUMNS}.
     */
    static ValueTable of(List<Row> rows) {
        Set<String> fieldNames = new LinkedHashSet<>();
        boolean leaf = false;
        for (Row r : rows) {
            Map<String, String> f = r.fields();
            if (f == null || f.isEmpty()) {
                leaf = true;
                continue;
            }
            for (String name : f.keySet()) {
                if (fieldNames.size() < MAX_COLUMNS) {
                    fieldNames.add(name);
                }
            }
        }
        List<String> columns = new ArrayList<>();
        columns.add(KEY_COLUMN);
        if (leaf || fieldNames.isEmpty()) {
            columns.add(VALUE_COLUMN);
        }
        columns.addAll(fieldNames);
        return new ValueTable(List.copyOf(columns), List.copyOf(rows));
    }

    /** Whether {@code row} has {@code text} in one of its cells, ignoring case; every row matches "". */
    boolean matches(Row row, String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return true;
        }
        for (String c : columns) {
            if (row.cell(c).toLowerCase(Locale.ROOT).contains(t)) {
                return true;
            }
        }
        return false;
    }

    /** {@code rows} as CSV (RFC 4180), headed by the columns' names ({@code value} for the value column). */
    String csv(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        line(out, columns.stream().map(ValueTable::heading).toList(), ",", true);
        for (Row r : rows) {
            line(out, columns.stream().map(r::cell).toList(), ",", true);
        }
        return out.toString();
    }

    /** {@code rows} as tab-separated text, headed by the columns' names: what a spreadsheet takes from a paste. */
    String tsv(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        line(out, columns.stream().map(ValueTable::heading).toList(), "\t", false);
        for (Row r : rows) {
            line(out, columns.stream().map(r::cell).toList(), "\t", false);
        }
        return out.toString();
    }

    /** A column's name as its header says it. */
    static String heading(String column) {
        return VALUE_COLUMN.equals(column) ? "value" : column;
    }

    private static void line(StringBuilder out, List<String> cells, String separator, boolean quote) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(separator);
            }
            String c = cells.get(i);
            if (quote && (c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r"))) {
                out.append('"').append(c.replace("\"", "\"\"")).append('"');
            } else {
                out.append(quote ? c : c.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
            }
        }
        out.append('\n');
    }

    /**
     * Orders two cells as a column sorts them: numbers by value (an index {@code [10]} after {@code [9]}), then the
     * rest as text, ignoring case.
     */
    static int compare(@Nullable String a, @Nullable String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        Double nx = number(x);
        Double ny = number(y);
        if (nx != null && ny != null) {
            return Double.compare(nx, ny);
        }
        if (nx != null) {
            return -1; // numbers first
        }
        if (ny != null) {
            return 1;
        }
        int c = x.compareToIgnoreCase(y);
        return c != 0 ? c : x.compareTo(y);
    }

    private static @Nullable Double number(String s) {
        String t = s.strip();
        if (t.startsWith("[") && t.endsWith("]")) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.isEmpty() || !(Character.isDigit(t.charAt(0)) || t.charAt(0) == '-' || t.charAt(0) == '.')) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
