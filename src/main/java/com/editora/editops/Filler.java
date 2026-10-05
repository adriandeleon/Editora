package com.editora.editops;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, unit-tested implementation of the Emacs <em>fill</em> commands — {@code fill-paragraph} (`M-q`),
 * {@code fill-region}, and the {@code set-fill-column} target width — computing a minimal {@link Edit}
 * (a replacement span + the resulting caret) from the current text, or {@code null} for a no-op. No toolkit
 * dependency; the controller applies the {@code Edit} to the active {@code CodeArea}.
 *
 * <p>"Filling" re-wraps a paragraph so no line exceeds the fill column: it joins the paragraph's lines,
 * collapses runs of whitespace to single spaces, then greedily packs words back onto lines. A paragraph is
 * a maximal run of non-blank lines (blank lines separate paragraphs and are preserved). The paragraph's
 * <em>fill prefix</em> — leading indentation plus an optional comment/quote marker ({@code //}, {@code #},
 * {@code >}, {@code *}, …) — is detected from the first line and repeated on every wrapped line, so code
 * comments and quoted text fill correctly. A single word longer than the fill column is never broken.
 *
 * <p>Deferred (vs Emacs): Auto Fill mode (break-as-you-type), justification, sentence double-spacing, and
 * {@code fill-individual-paragraphs}.
 */
public final class Filler {

    /** Replace {@code [from, to)} with {@code replacement}, then place the caret at {@code caret}. */
    public record Edit(int from, int to, String replacement, int caret) {}

    /** Emacs's default {@code fill-column}. */
    public static final int DEFAULT_FILL_COLUMN = 70;

    private Filler() {}

    private static boolean isBlank(String line) {
        return line.strip().isEmpty();
    }

    /**
     * Emacs {@code fill-paragraph} (`M-q`): re-wraps the paragraph containing {@code caret} to
     * {@code fillColumn}. If the caret is on a blank line, the next paragraph is filled; {@code null} if
     * there is none. {@code lineComment} is the buffer language's line-comment token (e.g. {@code "//"}) or
     * {@code null} — used (with {@code >}/{@code *}) to detect a fill prefix.
     */
    public static Edit fillParagraph(String text, int caret, int fillColumn, String lineComment) {
        List<int[]> lines = lineSpans(text); // each = {start, end} (end excludes the newline)
        int li = lineIndexAt(lines, caret);
        // On a blank line, advance to the next non-blank line (fill the following paragraph).
        while (li < lines.size() && isBlank(lineText(text, lines.get(li)))) {
            li++;
        }
        if (li >= lines.size()) {
            return null;
        }
        int[] span = paragraphAt(text, lines, li, lineComment);
        if (span == null) {
            return null; // a comment delimiter or an empty marker line: nothing to fill
        }
        int first = span[0];
        int last = span[1];
        int from = lines.get(first)[0];
        int to = lines.get(last)[1];
        String filled = fillBlock(text, lines, first, last, fillColumn, lineComment);
        if (filled == null || filled.equals(text.substring(from, to))) {
            return null; // empty or already filled — no change
        }
        return new Edit(from, to, filled, from + filled.length());
    }

    /**
     * Emacs {@code fill-region}: fills every paragraph overlapping {@code [selStart, selEnd)}, preserving
     * the blank lines between them. {@code null} if the region holds no fillable text.
     */
    public static Edit fillRegion(String text, int selStart, int selEnd, int fillColumn, String lineComment) {
        List<int[]> lines = lineSpans(text);
        if (lines.isEmpty()) {
            return null;
        }
        int firstLine = lineIndexAt(lines, Math.min(selStart, selEnd));
        int lastLine = lineIndexAt(lines, Math.max(selStart, selEnd));
        int from = lines.get(firstLine)[0];
        int to = lines.get(lastLine)[1];
        StringBuilder out = new StringBuilder();
        int i = firstLine;
        while (i <= lastLine) {
            if (kind(lineText(text, lines.get(i)), lineComment) == null) {
                out.append(lineText(text, lines.get(i))); // keep blank and delimiter lines verbatim
                if (i < lastLine) {
                    out.append('\n');
                }
                i++;
                continue;
            }
            int pStart = i;
            i = paragraphEnd(text, lines, i, lastLine, lineComment);
            String filled = fillBlock(text, lines, pStart, i, fillColumn, lineComment);
            out.append(filled == null ? "" : filled);
            if (i < lastLine) {
                out.append('\n');
            }
            i++;
        }
        String result = out.toString();
        if (result.equals(text.substring(from, to))) {
            return null;
        }
        return new Edit(from, to, result, from + result.length());
    }

    /** Fills lines {@code [firstLine, lastLine]} (all non-blank) into a wrapped block; null if no words. */
    private static String fillBlock(
            String text, List<int[]> lines, int firstLine, int lastLine, int fillColumn, String lineComment) {
        String prefix = fillPrefix(lineText(text, lines.get(firstLine)), lineComment);
        // A list item hangs: its wrapped lines are indented under the text, not given a bullet each.
        String continuation = isBullet(text, lines, firstLine, lineComment) ? hangingIndent(prefix) : prefix;
        List<String> words = new ArrayList<>();
        for (int i = firstLine; i <= lastLine; i++) {
            String content = stripPrefix(lineText(text, lines.get(i)), prefix, lineComment);
            for (String w : content.strip().split("\\s+")) {
                if (!w.isEmpty()) {
                    words.add(w);
                }
            }
        }
        if (words.isEmpty()) {
            return null;
        }
        int column = Math.max(1, fillColumn);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean hasWord = false;
        for (String w : words) {
            if (!hasWord) {
                cur.append(prefix).append(w);
                hasWord = true;
            } else if (cur.length() + 1 + w.length() <= column) {
                cur.append(' ').append(w);
            } else {
                out.add(cur.toString());
                cur = new StringBuilder(continuation).append(w);
            }
        }
        out.add(cur.toString());
        return String.join("\n", out);
    }

    // --- paragraph boundaries --------------------------------------------------------------------

    /**
     * What kind of line this is for filling: {@code null} for a separator (a blank line, a block-comment
     * delimiter line such as {@code /**} or {@code *}{@code /}, or a marker with no text after it),
     * otherwise the fill marker it starts with — the line-comment token, {@code >} or {@code *} — or
     * {@code ""} for a plain line. A paragraph never mixes kinds, so a comment that touches code is
     * filled on its own and code is never pulled into it.
     */
    static String kind(String line, String lineComment) {
        String rest = line.strip();
        if (rest.isEmpty() || rest.startsWith("/*") || rest.startsWith("*/")) {
            return null;
        }
        int ws = leadingWhitespace(line);
        for (String marker : markers(lineComment)) {
            int end = markerEnd(line, ws, marker);
            if (end >= 0) {
                return line.substring(end).isBlank() ? null : marker;
            }
        }
        return "";
    }

    /**
     * End of {@code marker} at {@code at} in {@code line}, or -1 if it is not there. A doc-comment form of
     * the line-comment token ({@code ///}, {@code //!}, {@code ##}) counts as part of the marker, and a
     * {@code *} is a marker only when followed by whitespace (so {@code **bold**} is plain text).
     */
    private static int markerEnd(String line, int at, String marker) {
        if (!line.startsWith(marker, at)) {
            return -1;
        }
        int end = at + marker.length();
        if (marker.equals("*")) {
            return end == line.length() || line.charAt(end) == ' ' || line.charAt(end) == '\t' ? end : -1;
        }
        if (!marker.equals(">")) {
            char last = marker.charAt(marker.length() - 1);
            while (end < line.length() && line.charAt(end) == last) {
                end++;
            }
            if (end < line.length() && line.charAt(end) == '!') {
                end++;
            }
        }
        return end;
    }

    /**
     * Whether the {@code *} line at {@code index} is a list item rather than a block-comment
     * continuation: a comment's {@code *} lines run back to a {@code /*} opener.
     */
    private static boolean isBullet(String text, List<int[]> lines, int index, String lineComment) {
        if (!"*".equals(kind(lineText(text, lines.get(index)), lineComment))) {
            return false;
        }
        int j = index - 1;
        while (j >= 0) {
            String above = lineText(text, lines.get(j)).strip();
            if (!above.startsWith("*") || above.startsWith("*/")) {
                return !above.startsWith("/*");
            }
            j--;
        }
        return true;
    }

    private static String hangingIndent(String prefix) {
        int ws = leadingWhitespace(prefix);
        return prefix.substring(0, ws) + " ".repeat(prefix.length() - ws);
    }

    /** Last line of the paragraph that starts at {@code first}, not past {@code limit}. */
    private static int paragraphEnd(String text, List<int[]> lines, int first, int limit, String lineComment) {
        // A list item continues over the plain lines under it; anything else over lines of its own kind.
        String want =
                isBullet(text, lines, first, lineComment) ? "" : kind(lineText(text, lines.get(first)), lineComment);
        int last = first;
        while (last + 1 <= limit && want.equals(kind(lineText(text, lines.get(last + 1)), lineComment))) {
            last++;
        }
        return last;
    }

    /** {@code {first, last}} line indices of the paragraph holding line {@code li}; null on a separator. */
    private static int[] paragraphAt(String text, List<int[]> lines, int li, String lineComment) {
        String k = kind(lineText(text, lines.get(li)), lineComment);
        if (k == null) {
            return null;
        }
        int first = li;
        if (!isBullet(text, lines, li, lineComment)) {
            while (first > 0 && k.equals(kind(lineText(text, lines.get(first - 1)), lineComment))) {
                if (isBullet(text, lines, first - 1, lineComment)) {
                    break; // k is "*" here: the item above is its own paragraph
                }
                first--;
            }
            if (k.isEmpty() && first > 0 && isBullet(text, lines, first - 1, lineComment)) {
                first--; // plain lines under a list item belong to it
            }
        }
        return new int[] {first, paragraphEnd(text, lines, first, lines.size() - 1, lineComment)};
    }

    /**
     * The fill prefix of {@code firstLine}: its leading whitespace, plus — if what follows begins with the
     * language line-comment token, a Markdown blockquote {@code >}, or a Javadoc {@code *} — that marker and
     * the whitespace after it. Pure (the controller supplies {@code lineComment}).
     */
    /** The continuation prefix (leading indent, plus a comment/quote marker for a comment line). */
    public static String fillPrefix(String firstLine, String lineComment) {
        int ws = leadingWhitespace(firstLine);
        for (String marker : markers(lineComment)) {
            int after = markerEnd(firstLine, ws, marker);
            if (after >= 0) {
                int afterWs = after + leadingWhitespace(firstLine.substring(after));
                return firstLine.substring(0, afterWs);
            }
        }
        return firstLine.substring(0, ws);
    }

    /** Removes {@code prefix} from {@code line} if present, else its own leading whitespace + marker. */
    private static String stripPrefix(String line, String prefix, String lineComment) {
        if (!prefix.isEmpty() && line.startsWith(prefix)) {
            return line.substring(prefix.length());
        }
        // A continuation line may have a different indent; drop leading whitespace + an optional marker.
        int ws = leadingWhitespace(line);
        String rest = line.substring(ws);
        for (String marker : markers(lineComment)) {
            int after = markerEnd(line, ws, marker);
            if (after >= 0) {
                return line.substring(after + leadingWhitespace(line.substring(after)));
            }
        }
        return rest;
    }

    private static List<String> markers(String lineComment) {
        List<String> m = new ArrayList<>();
        if (lineComment != null && !lineComment.isBlank()) {
            m.add(lineComment.strip());
        }
        m.add(">"); // Markdown blockquote
        m.add("*"); // Javadoc / block-comment continuation
        return m;
    }

    private static int leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** Line spans {@code {start, end}} (end excludes the trailing newline). Always ≥ 1 entry. */
    private static List<int[]> lineSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        int start = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') {
                spans.add(new int[] {start, i});
                start = i + 1;
            }
        }
        spans.add(new int[] {start, n});
        return spans;
    }

    private static String lineText(String text, int[] span) {
        return text.substring(span[0], span[1]);
    }

    private static int lineIndexAt(List<int[]> lines, int pos) {
        for (int i = 0; i < lines.size(); i++) {
            if (pos <= lines.get(i)[1]) {
                return i;
            }
        }
        return lines.size() - 1;
    }
}
