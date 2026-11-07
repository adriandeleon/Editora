package com.editora.structured;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * JSON Lines ({@code .jsonl}, {@code .ndjson}): one JSON value per line. Reads a document line by line — the
 * values for the tree preview ({@link #values}) and the lines that are not one valid value ({@link #check}) —
 * so a broken record is reported where it is instead of as "end of file expected" on line 2, which is all a
 * whole-document JSON parser says. Blank lines are skipped (a trailing newline, a spacer line). Columns are
 * 1-based characters of the line, as the editor counts them. Pure; unit-tested; runs off the FX thread.
 */
public final class JsonLines {

    /** What is wrong with a line. */
    public enum Kind {
        /** The line is not a JSON value. */
        INVALID,
        /** The line has a JSON value and more after it (two records on one line). */
        TRAILING
    }

    /**
     * One bad line: 1-based {@code line}, the 1-based {@code column} and {@code length} of the stretch to mark,
     * where the parser stopped ({@code errorColumn}, 1-based) and its own message ({@code detail}, English, empty
     * for {@link Kind#TRAILING}).
     */
    public record Problem(int line, int column, int length, int errorColumn, Kind kind, String detail) {}

    /** A document past this size is not checked: the check runs after every pause in typing. */
    public static final int MAX_CHARS = 5_000_000;

    /** At most this many problems are reported: a file that is not JSON Lines at all would mark every line. */
    public static final int MAX_PROBLEMS = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonLines() {}

    /** The lines of {@code text} that are not exactly one JSON value, at most {@link #MAX_PROBLEMS}. */
    public static List<Problem> check(@Nullable String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_CHARS) {
            return List.of();
        }
        List<Problem> out = new ArrayList<>();
        int lineNo = 0;
        int start = 0;
        while (start <= text.length() && out.size() < MAX_PROBLEMS) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            lineNo++;
            Problem p = checkLine(lineNo, stripCr(text, start, end));
            if (p != null) {
                out.add(p);
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return List.copyOf(out);
    }

    /** The problem with one line (its {@code \r} already removed), or null when it is one value or blank. */
    static @Nullable Problem checkLine(int lineNo, String line) {
        try {
            parseLine(lineNo, line);
            return null;
        } catch (LineException e) {
            return e.problem();
        }
    }

    /**
     * The value of one line (its {@code \r} already removed), or null for a blank line.
     *
     * @throws LineException when the line is not exactly one JSON value
     */
    private static @Nullable JsonNode parseLine(int lineNo, String line) throws LineException {
        int first = firstNonBlank(line, 0);
        if (first < 0) {
            return null;
        }
        int last = lastNonBlank(line);
        try (JsonParser p = MAPPER.createParser(line)) {
            JsonNode value = MAPPER.readTree(p);
            int after = (int) Math.min(line.length(), p.currentLocation().getCharOffset());
            int rest = firstNonBlank(line, after);
            if (rest >= 0) {
                throw new LineException(new Problem(lineNo, rest + 1, last - rest + 1, rest + 1, Kind.TRAILING, ""));
            }
            return value;
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            int col = at == null || at.getColumnNr() < 1 ? first + 1 : at.getColumnNr();
            throw new LineException(new Problem(lineNo, first + 1, last - first + 1, col, Kind.INVALID, detail(e)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e); // a String source does no I/O
        }
    }

    /**
     * The values of the document's non-blank lines, in order, each with its 1-based line number.
     *
     * @throws LineException for the first line that is not exactly one JSON value
     */
    static List<Line> values(String text) throws LineException {
        List<Line> out = new ArrayList<>();
        int lineNo = 0;
        int start = 0;
        while (start <= text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl;
            lineNo++;
            JsonNode value = parseLine(lineNo, stripCr(text, start, end));
            if (value != null) {
                out.add(new Line(lineNo, value));
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        return out;
    }

    /** A non-blank line's value and its 1-based line number. */
    record Line(int number, JsonNode value) {}

    /** The first line that is not exactly one JSON value (for the preview's error message). */
    static final class LineException extends Exception {
        private final Problem problem;

        LineException(Problem problem) {
            super(null, null, false, false);
            this.problem = problem;
        }

        Problem problem() {
            return problem;
        }
    }

    /** Jackson's message without the location it appends (that is the mark's place) and only its first line. */
    private static String detail(JsonProcessingException e) {
        String msg = e.getOriginalMessage();
        if (msg == null || msg.isBlank()) {
            return "parse error";
        }
        int nl = msg.indexOf('\n');
        return (nl >= 0 ? msg.substring(0, nl) : msg).strip();
    }

    private static String stripCr(String text, int start, int end) {
        return end > start && text.charAt(end - 1) == '\r'
                ? text.substring(start, end - 1)
                : text.substring(start, end);
    }

    /** JSON's whitespace: space, tab, CR, LF. */
    private static boolean blank(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static int firstNonBlank(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (!blank(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int lastNonBlank(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (!blank(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
