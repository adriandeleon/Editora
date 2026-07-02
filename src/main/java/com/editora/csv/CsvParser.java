package com.editora.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, toolkit-free CSV/TSV parsing helpers shared by the CSV editor features (the status-bar column
 * readout, "Copy as Markdown table", and the future grid preview).
 *
 * <p>Parsing follows RFC-4180: a field may be wrapped in double quotes, {@code ""} is a literal quote,
 * and a quoted field may contain the delimiter or a newline. The delimiter is one of {@code , ; \t |}
 * (auto-detected from the first non-blank line, comma the default). Everything here is static and pure —
 * no I/O, no JavaFX — so it unit-tests directly and is safe to call off the FX thread.
 */
public final class CsvParser {

    /** Delimiters we auto-detect, in preference order for ties (comma wins). */
    static final char[] CANDIDATES = {',', ';', '\t', '|'};

    private CsvParser() {}

    /**
     * Picks the delimiter by counting unquoted occurrences of each candidate in the first non-blank line.
     * Tab wins ties over comma/semicolon (a {@code .tsv} rarely has more commas than tabs); pipe only wins
     * when it strictly dominates. Comma is the fallback when nothing is found.
     */
    public static char detectDelimiter(String text) {
        if (text == null) {
            return ',';
        }
        String head = firstNonBlankLine(text);
        int[] counts = new int[CANDIDATES.length];
        boolean q = false;
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c == '"') {
                q = !q;
            } else if (!q) {
                for (int k = 0; k < CANDIDATES.length; k++) {
                    if (c == CANDIDATES[k]) {
                        counts[k]++;
                    }
                }
            }
        }
        int comma = counts[0];
        int semi = counts[1];
        int tab = counts[2];
        int pipe = counts[3];
        if (tab > 0 && tab >= comma && tab >= semi && tab >= pipe) {
            return '\t';
        }
        if (pipe > 0 && pipe > comma && pipe > semi) {
            return '|';
        }
        if (semi > comma) {
            return ';';
        }
        return ',';
    }

    /** The number of fields in a single record {@code line}, honoring RFC-4180 quotes (min 1). */
    public static int fieldCount(String line, char delim) {
        if (line == null) {
            return 1;
        }
        return separatorsBefore(line, delim, line.length()) + 1;
    }

    /**
     * The 1-based field index the caret sits in, where {@code caretCol} is the caret's char offset within
     * {@code line}. Counts the unquoted delimiters strictly before the caret, +1 (so a caret before any
     * delimiter is field 1, and a caret just after the first delimiter is field 2).
     */
    public static int fieldIndexAt(String line, char delim, int caretCol) {
        if (line == null) {
            return 1;
        }
        int col = Math.max(0, Math.min(caretCol, line.length()));
        return separatorsBefore(line, delim, col) + 1;
    }

    /** Counts unquoted {@code delim} characters in {@code line[0, end)} (an RFC-4180 field-boundary scan). */
    /**
     * The char offset within {@code line} where field {@code fieldIndex} (0-based) starts, honoring
     * RFC-4180 quotes — i.e. the position just after the {@code fieldIndex}-th unquoted delimiter (0 for the
     * first field). If the line has fewer fields, returns {@code line.length()}. Used to place the caret at a
     * clicked grid cell's field.
     */
    public static int fieldStartOffset(String line, char delim, int fieldIndex) {
        if (line == null || fieldIndex <= 0) {
            return 0;
        }
        int seps = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        inQuotes = false;
                    }
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delim) {
                seps++;
                if (seps == fieldIndex) {
                    return i + 1;
                }
            }
        }
        return line.length();
    }

    private static int separatorsBefore(String line, char delim, int end) {
        int seps = 0;
        boolean inQuotes = false;
        for (int i = 0; i < end; i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        i++; // an escaped "" — stay in the quoted field
                    } else {
                        inQuotes = false;
                    }
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delim) {
                seps++;
            }
        }
        return seps;
    }

    /** Parses {@code text} into rows of cells with the auto-detected delimiter. */
    public static List<List<String>> parse(String text) {
        return parse(text, detectDelimiter(text));
    }

    /**
     * RFC-4180 record/field parser: quoted fields ({@code ""} escape) may contain the delimiter or a
     * newline. Returns one {@code List<String>} per record; ragged rows are returned as-is (callers pad).
     */
    public static List<List<String>> parse(String text, char delim) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean inQuotes = false;
        boolean pending = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    f.append(c);
                }
            } else if (c == '"' && f.length() == 0) {
                inQuotes = true;
                pending = true;
            } else if (c == delim) {
                row.add(f.toString());
                f.setLength(0);
                pending = true;
            } else if (c == '\n') {
                row.add(f.toString());
                rows.add(row);
                row = new ArrayList<>();
                f.setLength(0);
                pending = false;
            } else if (c != '\r') {
                f.append(c);
                pending = true;
            }
        }
        if (pending || f.length() > 0 || !row.isEmpty()) {
            row.add(f.toString());
            rows.add(row);
        }
        return rows;
    }

    /** The widest row's cell count across {@code rows} (the table's effective column count; 0 when empty). */
    public static int columnCount(List<List<String>> rows) {
        int max = 0;
        for (List<String> r : rows) {
            max = Math.max(max, r.size());
        }
        return max;
    }

    private static String firstNonBlankLine(String text) {
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            String line = (nl < 0 ? text.substring(start) : text.substring(start, nl)).stripTrailing();
            if (!line.isBlank()) {
                return line;
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return "";
    }
}
