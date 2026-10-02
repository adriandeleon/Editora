package com.editora.office;

import java.util.regex.Pattern;

/**
 * Pure value helpers for the CSV spreadsheet exporters ({@link XlsxWriter} / {@link OdsWriter}). Decides
 * whether a raw CSV cell should be written as a spreadsheet <em>number</em> or left as text.
 */
public final class CsvValues {

    /**
     * The only shape accepted as a number: an optional sign, decimal digits with an optional fraction, and an
     * optional exponent. {@link Double#parseDouble} alone is far more permissive — it takes Java float/double
     * suffixes ({@code 12D} → 12.0, {@code 5F} → 5.0, so a part number or a shoe size silently became a
     * number), hex floats, and the words {@code NaN}/{@code Infinity}.
     */
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /** A double holds 15 significant decimal digits exactly; more than that would be silently rounded. */
    private static final int MAX_SIGNIFICANT_DIGITS = 15;

    private CsvValues() {}

    /**
     * Returns the numeric value of {@code s} when it is safe to store as a number in a spreadsheet, else
     * {@code null} (keep it text). Deliberately conservative to preserve data: a value with a
     * <em>leading zero</em> followed by another digit (e.g. {@code "007"}, a ZIP like {@code "07030"}) is
     * treated as text so Excel/Calc don't silently drop the zero; thousands separators / currency symbols
     * ({@code "1,960"}, {@code "$5"}) and anything that is not a plain decimal ({@code "12D"}, {@code "0x1F"})
     * stay text; and a value with more than 15 significant digits — an order id, an IBAN fragment, a
     * 19-digit snowflake — stays text rather than being rounded to the nearest double
     * ({@code 1234567890123456789} would come back as {@code 1.2345678901234568E18}).
     */
    public static Double numericValue(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || !DECIMAL.matcher(t).matches()) {
            return null;
        }
        int i = (t.charAt(0) == '+' || t.charAt(0) == '-') ? 1 : 0;
        // Leading zero followed by another digit → an identifier/code, not a number.
        if (i + 1 < t.length() && t.charAt(i) == '0' && Character.isDigit(t.charAt(i + 1))) {
            return null;
        }
        if (significantDigits(t, i) > MAX_SIGNIFICANT_DIGITS) {
            return null;
        }
        double d = Double.parseDouble(t);
        return Double.isInfinite(d) ? null : d; // an exponent beyond the double range ("1e999")
    }

    /** Digits of the mantissa starting at {@code from}, not counting leading zeros or the decimal point. */
    private static int significantDigits(String t, int from) {
        int n = 0;
        boolean leading = true;
        for (int k = from; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == 'e' || c == 'E') {
                break;
            }
            if (c == '.') {
                continue;
            }
            if (leading && c == '0') {
                continue;
            }
            leading = false;
            n++;
        }
        return n;
    }
}
