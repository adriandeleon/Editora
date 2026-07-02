package com.editora.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure column profiling for the CSV grid preview: infers each column's dominant value type from its data
 * cells. Toolkit-free and unit-tested; used by the grid's summary line ("5 columns · 3 text, 2 number").
 */
public final class CsvColumns {

    /** A column's inferred value type, from most to least specific. */
    public enum ColumnType {
        EMPTY,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        TEXT
    }

    private CsvColumns() {}

    /**
     * Infers a {@link ColumnType} per column across {@code rows}. When {@code hasHeader} the first row is a
     * header and excluded from inference. A column is {@code INTEGER}/{@code DECIMAL}/{@code BOOLEAN} only if
     * every non-blank cell parses as that type; {@code EMPTY} when the column has no non-blank cell; else
     * {@code TEXT}. The result has one entry per column (the widest row's width).
     */
    public static List<ColumnType> inferTypes(List<List<String>> rows, boolean hasHeader) {
        int cols = CsvParser.columnCount(rows);
        List<ColumnType> out = new ArrayList<>(cols);
        int start = hasHeader ? 1 : 0;
        for (int c = 0; c < cols; c++) {
            boolean any = false;
            boolean allInt = true;
            boolean allDec = true;
            boolean allBool = true;
            for (int r = start; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                String v = c < row.size() ? row.get(c).strip() : "";
                if (v.isEmpty()) {
                    continue;
                }
                any = true;
                if (!isInteger(v)) {
                    allInt = false;
                }
                if (!isDecimal(v)) {
                    allDec = false;
                }
                if (!isBoolean(v)) {
                    allBool = false;
                }
            }
            if (!any) {
                out.add(ColumnType.EMPTY);
            } else if (allInt) {
                out.add(ColumnType.INTEGER);
            } else if (allDec) {
                out.add(ColumnType.DECIMAL);
            } else if (allBool) {
                out.add(ColumnType.BOOLEAN);
            } else {
                out.add(ColumnType.TEXT);
            }
        }
        return out;
    }

    /** Whether {@code t} counts as a "number" column (integer or decimal) for the grid summary. */
    public static boolean isNumeric(ColumnType t) {
        return t == ColumnType.INTEGER || t == ColumnType.DECIMAL;
    }

    private static boolean isInteger(String v) {
        int i = (v.startsWith("+") || v.startsWith("-")) ? 1 : 0;
        if (i == v.length()) {
            return false;
        }
        for (; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDecimal(String v) {
        if (v.isEmpty()) {
            return false;
        }
        boolean digit = false;
        boolean dot = false;
        boolean exp = false;
        int i = 0;
        for (; i < v.length(); i++) {
            char ch = v.charAt(i);
            if ((ch == '+' || ch == '-') && i == 0) {
                continue;
            }
            if (Character.isDigit(ch)) {
                digit = true;
            } else if (ch == '.' && !dot && !exp) {
                dot = true;
            } else if ((ch == 'e' || ch == 'E') && digit && !exp) {
                exp = true;
                digit = false; // require a digit after the exponent
                if (i + 1 < v.length() && (v.charAt(i + 1) == '+' || v.charAt(i + 1) == '-')) {
                    i++;
                }
            } else {
                return false;
            }
        }
        return digit;
    }

    private static boolean isBoolean(String v) {
        return v.equalsIgnoreCase("true")
                || v.equalsIgnoreCase("false")
                || v.equalsIgnoreCase("yes")
                || v.equalsIgnoreCase("no");
    }
}
