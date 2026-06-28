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
        int first = li;
        while (first > 0 && !isBlank(lineText(text, lines.get(first - 1)))) {
            first--;
        }
        int last = li;
        while (last + 1 < lines.size() && !isBlank(lineText(text, lines.get(last + 1)))) {
            last++;
        }
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
            if (isBlank(lineText(text, lines.get(i)))) {
                out.append(lineText(text, lines.get(i))); // keep blank/whitespace lines verbatim
                if (i < lastLine) {
                    out.append('\n');
                }
                i++;
                continue;
            }
            int pStart = i;
            while (i + 1 <= lastLine && !isBlank(lineText(text, lines.get(i + 1)))) {
                i++;
            }
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
                cur = new StringBuilder(prefix).append(w);
            }
        }
        out.add(cur.toString());
        return String.join("\n", out);
    }

    /**
     * The fill prefix of {@code firstLine}: its leading whitespace, plus — if what follows begins with the
     * language line-comment token, a Markdown blockquote {@code >}, or a Javadoc {@code *} — that marker and
     * the whitespace after it. Pure (the controller supplies {@code lineComment}).
     */
    static String fillPrefix(String firstLine, String lineComment) {
        int ws = leadingWhitespace(firstLine);
        String rest = firstLine.substring(ws);
        for (String marker : markers(lineComment)) {
            if (rest.startsWith(marker)) {
                int after = ws + marker.length();
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
            if (rest.startsWith(marker)) {
                int after = ws + marker.length();
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
