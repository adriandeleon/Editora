package com.editora.office;

/**
 * Pure value helpers for the CSV spreadsheet exporters ({@link XlsxWriter} / {@link OdsWriter}). Decides
 * whether a raw CSV cell should be written as a spreadsheet <em>number</em> or left as text.
 */
public final class CsvValues {

    private CsvValues() {}

    /**
     * Returns the numeric value of {@code s} when it is safe to store as a number in a spreadsheet, else
     * {@code null} (keep it text). Deliberately conservative to preserve data: a value with a
     * <em>leading zero</em> followed by another digit (e.g. {@code "007"}, a ZIP like {@code "07030"}) is
     * treated as text so Excel/Calc don't silently drop the zero; thousands separators / currency symbols
     * ({@code "1,960"}, {@code "$5"}) don't parse and also stay text.
     */
    public static Double numericValue(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        int i = (t.charAt(0) == '+' || t.charAt(0) == '-') ? 1 : 0;
        // Leading zero followed by another digit → an identifier/code, not a number.
        if (i + 1 < t.length() && t.charAt(i) == '0' && Character.isDigit(t.charAt(i + 1))) {
            return null;
        }
        try {
            double d = Double.parseDouble(t);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null; // "NaN"/"Infinity" parse but aren't spreadsheet numbers
            }
            return d;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
