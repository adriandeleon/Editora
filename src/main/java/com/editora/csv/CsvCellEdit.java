package com.editora.csv;

import java.util.List;

/**
 * Pure helpers for writing one grid cell back into CSV/TSV text: locate a record's physical line in the
 * whole document, and replace a single field of that line <em>in place</em>. Only the edited field's
 * characters change — every other field keeps its raw form (optional quotes, padding, text after a closing
 * quote), which re-serialising the whole row through {@link CsvParser#formatRow} did not.
 */
public final class CsvCellEdit {

    private CsvCellEdit() {}

    /**
     * The span {@code [start, end)} of 0-based {@code line} in LF-separated {@code text}, without its line
     * break; {@code null} when the text has no such line.
     */
    public static int[] lineSpan(String text, int line) {
        if (text == null || line < 0) {
            return null;
        }
        int start = 0;
        for (int i = 0; i < line; i++) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) {
                return null;
            }
            start = nl + 1;
        }
        int end = text.indexOf('\n', start);
        return new int[] {start, end < 0 ? text.length() : end};
    }

    /** The text of 0-based {@code line} in {@code text}, or {@code null} when there is no such line. */
    public static String lineText(String text, int line) {
        int[] span = lineSpan(text, line);
        return span == null ? null : text.substring(span[0], span[1]);
    }

    /**
     * {@code line} with its 0-based {@code field} set to {@code value} (RFC-4180 quoted when it needs to
     * be), touching nothing else. Returns {@code line} itself when the field already parses to
     * {@code value}; a field past the end of a short row is appended after the missing delimiters. Field
     * boundaries follow {@link CsvParser#parse(String, char)} exactly, so the cell the grid showed is the
     * cell that is replaced.
     */
    public static String replaceField(String line, char delim, int field, String value) {
        String text = line == null ? "" : line;
        String wanted = value == null ? "" : value;
        if (field < 0) {
            return text;
        }
        List<List<String>> parsed = CsvParser.parse(text, delim);
        List<String> fields = parsed.isEmpty() ? List.of() : parsed.get(0);
        if (field < fields.size() && fields.get(field).equals(wanted)) {
            return text;
        }
        String quoted = CsvParser.quoteField(wanted, delim);
        int index = 0;
        int fieldStart = 0;
        int taken = 0; // characters the parser has put into the current field (decides whether a quote opens)
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c != '"') {
                    taken++;
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    taken++;
                    i++;
                } else {
                    inQuotes = false;
                }
            } else if (c == '"' && taken == 0) {
                inQuotes = true;
            } else if (c == delim) {
                if (index == field) {
                    return text.substring(0, fieldStart) + quoted + text.substring(i);
                }
                index++;
                fieldStart = i + 1;
                taken = 0;
            } else {
                taken++;
            }
        }
        if (index == field) {
            return text.substring(0, fieldStart) + quoted;
        }
        return text + String.valueOf(delim).repeat(field - index) + quoted;
    }
}
