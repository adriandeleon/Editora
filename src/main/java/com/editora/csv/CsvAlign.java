package com.editora.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, toolkit-free CSV/TSV column <b>alignment</b> — Rainbow-CSV's "Align" (pad fields so a column's
 * delimiters line up in a monospace editor) and "Shrink" (strip that padding), each reversible by the
 * other. Both operate <em>line by line</em>: they split each record into raw (quote-preserving) fields,
 * trim surrounding whitespace, and rebuild the line. So {@code shrink(align(t)) == shrink(t)} and
 * {@code align(align(t)) == align(t)} for any file without a multi-line quoted field (the caller must gate
 * on {@link CsvParser#hasMultilineField}, since padding a wrapped field would split a record across lines).
 *
 * <p>Trimming is the price of reversibility: fields with intentional leading/trailing spaces lose them, the
 * same trade-off Rainbow CSV makes. Widths are measured in characters (monospace); the delimiter follows a
 * field's padding directly, so the last field of a row is never padded. Everything is static/pure — no I/O,
 * no JavaFX — and unit-tested.
 */
public final class CsvAlign {

    private CsvAlign() {}

    /**
     * Pads each field with trailing spaces so every column's delimiter lines up. Blank lines are left
     * untouched; line endings are {@code \n} (the editor's normalized form) and preserved (incl. a trailing
     * newline). Idempotent and reversible via {@link #shrink}.
     */
    public static String align(String text, char delim) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        List<List<String>> fieldsPerLine = new ArrayList<>(lines.length);
        List<Integer> colMax = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                fieldsPerLine.add(null);
                continue;
            }
            List<String> raw = splitFieldsRaw(line, delim);
            List<String> trimmed = new ArrayList<>(raw.size());
            for (int c = 0; c < raw.size(); c++) {
                String v = raw.get(c).trim();
                trimmed.add(v);
                while (colMax.size() <= c) {
                    colMax.add(0);
                }
                colMax.set(c, Math.max(colMax.get(c), v.length()));
            }
            fieldsPerLine.add(trimmed);
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            List<String> fields = fieldsPerLine.get(i);
            if (fields == null) {
                continue; // blank line kept blank
            }
            int last = fields.size() - 1;
            for (int c = 0; c <= last; c++) {
                String v = fields.get(c);
                out.append(v);
                if (c < last) {
                    for (int p = v.length(); p < colMax.get(c); p++) {
                        out.append(' ');
                    }
                    out.append(delim);
                }
            }
        }
        return out.toString();
    }

    /**
     * Removes column-alignment padding: trims surrounding whitespace from every field, rejoining with a bare
     * delimiter. Blank lines and line endings are preserved. Inverse of {@link #align} (both trim, so this is
     * also the canonical "unaligned" form).
     */
    public static String shrink(String text, char delim) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            if (lines[i].isEmpty()) {
                continue;
            }
            List<String> raw = splitFieldsRaw(lines[i], delim);
            for (int c = 0; c < raw.size(); c++) {
                if (c > 0) {
                    out.append(delim);
                }
                out.append(raw.get(c).trim());
            }
        }
        return out.toString();
    }

    /**
     * Splits one record {@code line} into its raw field substrings on unquoted {@code delim}, <b>preserving
     * each field's exact text</b> (including any surrounding quotes and {@code ""} escapes) — unlike
     * {@link CsvParser#parse}, which unquotes. Always returns at least one element.
     */
    static List<String> splitFieldsRaw(String line, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                sb.append(c);
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++; // an escaped "" — stay in the quoted field
                    } else {
                        inQuotes = false;
                    }
                }
            } else if (c == '"') {
                inQuotes = true;
                sb.append(c);
            } else if (c == delim) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }
}
