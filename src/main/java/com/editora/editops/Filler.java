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
 * Width is measured in display columns (a tab runs to its tab stop, a wide CJK character is two).
 *
 * <p>What counts as a paragraph depends on the buffer ({@link Mode}): in a programming language only
 * comments are filled, so a slip of {@code M-q} never joins statements; in Markdown the lines that carry
 * structure — headings, table rows, fences and the code inside them — are left alone; and in any prose a
 * list item ({@code -}, {@code +}, {@code *}, {@code 1.}) is its own paragraph, wrapped under its text.
 *
 * <p>Deferred (vs Emacs): Auto Fill mode (break-as-you-type), justification, sentence double-spacing, and
 * {@code fill-individual-paragraphs}.
 */
public final class Filler {

    /** Replace {@code [from, to)} with {@code replacement}, then place the caret at {@code caret}. */
    public record Edit(int from, int to, String replacement, int caret) {}

    /** Emacs's default {@code fill-column}. */
    public static final int DEFAULT_FILL_COLUMN = 70;

    /** How a buffer's lines are read for filling. */
    public enum Mode {
        /** Plain prose: every non-blank line is text. */
        TEXT,
        /** As {@link #TEXT}, but headings, table rows, rules and fenced code are never filled or merged. */
        MARKDOWN,
        /** A programming language: only comments are filled, code lines are separators. */
        CODE;

        /**
         * How a buffer of {@code language} is filled: Markdown by its structure; a programming or
         * configuration language (anything indented as code, or with a line comment) comments-only; prose
         * and markup — plain text, Typst, HTML/XML — as text.
         */
        public static Mode forLanguage(String language) {
            if ("markdown".equals(language)) {
                return MARKDOWN;
            }
            Indenter.Style style = Indenter.styleFor(language);
            if ("typst".equals(language) || style == Indenter.Style.XML) {
                return TEXT;
            }
            return style != Indenter.Style.PLAIN || Commenter.styleFor(language).hasLine() ? CODE : TEXT;
        }
    }

    /** The kind of a list-item line ({@code -}, {@code +}, {@code 1.}, {@code 1)}), whatever its marker. */
    private static final String LIST = "-";

    private static final int DEFAULT_TAB_SIZE = 8;

    private final String text;
    private final List<int[]> lines; // each = {start, end} (end excludes the newline)
    private final String[] kinds;
    private final String lineComment;
    private final Mode mode;
    private final int tabSize;

    private Filler(String text, String lineComment, Mode mode, int tabSize) {
        this.text = text;
        this.lines = lineSpans(text);
        this.lineComment = lineComment;
        this.mode = mode == null ? Mode.TEXT : mode;
        this.tabSize = Math.max(1, tabSize);
        this.kinds = classify();
    }

    /**
     * Emacs {@code fill-paragraph} (`M-q`): re-wraps the paragraph containing {@code caret} to
     * {@code fillColumn}. If the caret is on a blank line, the next paragraph is filled; {@code null} if
     * there is none. {@code lineComment} is the buffer language's line-comment token (e.g. {@code "//"}) or
     * {@code null} — used (with {@code >}/{@code *}) to detect a fill prefix. Reads the buffer as
     * {@link Mode#TEXT}.
     */
    public static Edit fillParagraph(String text, int caret, int fillColumn, String lineComment) {
        return fillParagraph(text, caret, fillColumn, lineComment, Mode.TEXT, DEFAULT_TAB_SIZE);
    }

    /** As {@link #fillParagraph(String, int, int, String)}, reading the buffer as {@code mode}. */
    public static Edit fillParagraph(
            String text, int caret, int fillColumn, String lineComment, Mode mode, int tabSize) {
        return new Filler(text, lineComment, mode, tabSize).paragraph(caret, fillColumn);
    }

    /**
     * Emacs {@code fill-region}: fills every paragraph overlapping {@code [selStart, selEnd)}, preserving
     * the blank lines between them. {@code null} if the region holds no fillable text. Reads the buffer as
     * {@link Mode#TEXT}.
     */
    public static Edit fillRegion(String text, int selStart, int selEnd, int fillColumn, String lineComment) {
        return fillRegion(text, selStart, selEnd, fillColumn, lineComment, Mode.TEXT, DEFAULT_TAB_SIZE);
    }

    /** As {@link #fillRegion(String, int, int, int, String)}, reading the buffer as {@code mode}. */
    public static Edit fillRegion(
            String text, int selStart, int selEnd, int fillColumn, String lineComment, Mode mode, int tabSize) {
        return new Filler(text, lineComment, mode, tabSize).region(selStart, selEnd, fillColumn);
    }

    private Edit paragraph(int caret, int fillColumn) {
        int li = lineIndexAt(lines, caret);
        // On a blank line, advance to the next non-blank line (fill the following paragraph).
        while (li < lines.size() && line(li).isBlank()) {
            li++;
        }
        if (li >= lines.size()) {
            return null;
        }
        int[] span = paragraphAt(li);
        if (span == null) {
            return null; // a comment delimiter, an empty marker line, code, a heading: nothing to fill
        }
        int first = span[0];
        int last = span[1];
        int from = lines.get(first)[0];
        int to = lines.get(last)[1];
        String filled = fillBlock(first, last, fillColumn);
        if (filled == null || filled.equals(text.substring(from, to))) {
            return null; // empty or already filled — no change
        }
        return new Edit(from, to, filled, from + filled.length());
    }

    private Edit region(int selStart, int selEnd, int fillColumn) {
        int start = Math.min(selStart, selEnd);
        int end = Math.max(selStart, selEnd);
        if (end > start && text.charAt(end - 1) == '\n') {
            end--; // a selection ending exactly at a line start doesn't include that trailing line
        }
        int firstLine = lineIndexAt(lines, start);
        int lastLine = lineIndexAt(lines, end);
        int from = lines.get(firstLine)[0];
        int to = lines.get(lastLine)[1];
        StringBuilder out = new StringBuilder();
        int i = firstLine;
        while (i <= lastLine) {
            if (kinds[i] == null) {
                out.append(line(i)); // keep blank, delimiter and structural lines verbatim
                if (i < lastLine) {
                    out.append('\n');
                }
                i++;
                continue;
            }
            int pStart = i;
            i = paragraphEnd(i, lastLine);
            String filled = fillBlock(pStart, i, fillColumn);
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
    private String fillBlock(int firstLine, int lastLine, int fillColumn) {
        String prefix = prefixOf(firstLine);
        // A list item hangs: its wrapped lines are indented under the text, not given a bullet each.
        String continuation = isBullet(firstLine) ? hangingIndent(prefix) : prefix;
        List<String> words = new ArrayList<>();
        for (int i = firstLine; i <= lastLine; i++) {
            String content = stripPrefix(line(i), prefix, lineComment);
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
        int continuationWidth = width(continuation, 0, tabSize);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int col = 0;
        boolean hasWord = false;
        for (String w : words) {
            if (!hasWord) {
                cur.append(prefix).append(w);
                col = width(w, width(prefix, 0, tabSize), tabSize);
                hasWord = true;
            } else if (width(w, col + 1, tabSize) <= column) {
                cur.append(' ').append(w);
                col = width(w, col + 1, tabSize);
            } else {
                out.add(cur.toString());
                cur = new StringBuilder(continuation).append(w);
                col = width(w, continuationWidth, tabSize);
            }
        }
        out.add(cur.toString());
        return String.join("\n", out);
    }

    /**
     * The display column reached after {@code s} is written starting at column {@code col}: a tab runs to
     * the next tab stop and a wide (East Asian, emoji) character takes two columns.
     */
    static int width(String s, int col, int tabSize) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\t') {
                col += tabSize - (col % tabSize);
            } else {
                col += isWide(cp) ? 2 : 1;
            }
        }
        return col;
    }

    private static boolean isWide(int cp) {
        return cp >= 0x1100
                && (cp <= 0x115F
                        || (cp >= 0x2E80 && cp <= 0xA4CF)
                        || (cp >= 0xAC00 && cp <= 0xD7A3)
                        || (cp >= 0xF900 && cp <= 0xFAFF)
                        || (cp >= 0xFE30 && cp <= 0xFE4F)
                        || (cp >= 0xFF00 && cp <= 0xFF60)
                        || (cp >= 0xFFE0 && cp <= 0xFFE6)
                        || (cp >= 0x1F300 && cp <= 0x1FAFF)
                        || (cp >= 0x20000 && cp <= 0x3FFFD));
    }

    // --- line kinds ------------------------------------------------------------------------------

    private String line(int index) {
        return lineText(text, lines.get(index));
    }

    /**
     * The {@link #kind} of every line, adjusted for what a single line cannot tell: Markdown fences and the
     * code between them, the heading above a setext underline, and — in {@link Mode#CODE} — whether a line
     * is comment text at all.
     */
    private String[] classify() {
        String[] out = new String[lines.size()];
        String marker = lineComment == null || lineComment.isBlank() ? null : lineComment.strip();
        String fence = null; // the open Markdown fence's marker
        boolean inBlockComment = false;
        for (int i = 0; i < out.length; i++) {
            String line = line(i);
            String rest = line.strip();
            String k = kind(line, lineComment);
            if (mode == Mode.MARKDOWN) {
                if (fence != null) {
                    if (rest.startsWith(fence)) {
                        fence = null;
                    }
                    continue; // code, and the fence that closes it
                }
                if (rest.startsWith("```") || rest.startsWith("~~~")) {
                    fence = rest.substring(0, 3);
                    continue;
                }
                if (isHeading(rest) || rest.startsWith("|")) {
                    continue;
                }
                if (isRule(rest)) {
                    char c = rest.charAt(0);
                    if ((c == '=' || c == '-') && i > 0 && "".equals(out[i - 1])) {
                        out[i - 1] = null; // a setext heading: the text line above its underline
                    }
                    continue;
                }
            }
            if (mode == Mode.CODE) {
                boolean comment = marker != null && marker.equals(k);
                boolean inside = inBlockComment;
                if (!comment) {
                    if (rest.contains("*/")) {
                        inBlockComment = false;
                    } else if (rest.startsWith("/*")) {
                        inBlockComment = true;
                    }
                }
                // Code is never filled: only a line comment, or the text of a block comment that starts a line.
                out[i] = comment || (inside && ("".equals(k) || "*".equals(k))) ? k : null;
                continue;
            }
            if ("".equals(k) && listMarkerEnd(line, leadingWhitespace(line)) >= 0) {
                int end = listMarkerEnd(line, leadingWhitespace(line));
                k = line.substring(end).isBlank() ? null : LIST;
            }
            out[i] = k;
        }
        return out;
    }

    /** An ATX heading: one to six {@code #} followed by whitespace or the end of the line. */
    private static boolean isHeading(String rest) {
        int n = 0;
        while (n < rest.length() && rest.charAt(n) == '#') {
            n++;
        }
        return n >= 1 && n <= 6 && (n == rest.length() || rest.charAt(n) == ' ' || rest.charAt(n) == '\t');
    }

    /** A setext underline ({@code ===}, {@code ---}) or a thematic break ({@code ***}, {@code - - -}, {@code ___}). */
    private static boolean isRule(String rest) {
        if (rest.isEmpty()) {
            return false;
        }
        char c = rest.charAt(0);
        if (c != '=' && c != '-' && c != '*' && c != '_') {
            return false;
        }
        int count = 0;
        for (int i = 0; i < rest.length(); i++) {
            char d = rest.charAt(i);
            if (d == c) {
                count++;
            } else if (d != ' ' && d != '\t' || c == '=') {
                return false;
            }
        }
        return count >= (c == '=' ? 1 : c == '-' && count == rest.length() ? 2 : 3);
    }

    /**
     * End of the list marker at {@code at} in {@code line} — {@code -}, {@code +}, or a number followed by
     * {@code .} or {@code )} — when whitespace or the end of the line follows it, else -1. ({@code *} is a
     * marker of its own: it is also how a block comment continues.)
     */
    private static int listMarkerEnd(String line, int at) {
        int end = at;
        if (end < line.length() && (line.charAt(end) == '-' || line.charAt(end) == '+')) {
            end++;
        } else {
            while (end < line.length() && end - at < 9 && Character.isDigit(line.charAt(end))) {
                end++;
            }
            if (end == at || end >= line.length() || (line.charAt(end) != '.' && line.charAt(end) != ')')) {
                return -1;
            }
            end++;
        }
        return end == line.length() || line.charAt(end) == ' ' || line.charAt(end) == '\t' ? end : -1;
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
            // A word token (batch REM) matches in any case — and only as a whole word, below.
            if (!Character.isLetter(marker.charAt(marker.length() - 1))
                    || !line.regionMatches(true, at, marker, 0, marker.length())) {
                return -1;
            }
        }
        int end = at + marker.length();
        if (marker.equals("*")) {
            return end == line.length() || line.charAt(end) == ' ' || line.charAt(end) == '\t' ? end : -1;
        }
        if (Character.isLetter(marker.charAt(marker.length() - 1))) {
            // `REMOTE=1` is not a REM comment (as in Commenter.startsWithToken).
            return Commenter.startsWithToken(line.substring(at), marker) ? end : -1;
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
     * Whether the line at {@code index} is a list item: any {@code -}/{@code +}/numbered item, or a
     * {@code *} line that is not a block-comment continuation — a comment's {@code *} lines run back to a
     * {@code /*} opener.
     */
    private boolean isBullet(int index) {
        if (LIST.equals(kinds[index])) {
            return true;
        }
        if (!"*".equals(kinds[index])) {
            return false;
        }
        int j = index - 1;
        while (j >= 0) {
            String above = line(j).strip();
            if (!above.startsWith("*") || above.startsWith("*/")) {
                return !above.startsWith("/*");
            }
            j--;
        }
        return true;
    }

    /** A Javadoc block tag ({@code * @param x …}): it starts a paragraph of its own inside the comment. */
    private boolean isBlockTag(int index) {
        if (!"*".equals(kinds[index]) || isBullet(index)) {
            return false;
        }
        String line = line(index);
        return line.startsWith("@", fillPrefix(line, lineComment).length());
    }

    private static String hangingIndent(String prefix) {
        int ws = leadingWhitespace(prefix);
        return prefix.substring(0, ws) + " ".repeat(prefix.length() - ws);
    }

    /** Last line of the paragraph that starts at {@code first}, not past {@code limit}. */
    private int paragraphEnd(int first, int limit) {
        // A list item continues over the plain lines under it; anything else over lines of its own kind.
        String want = isBullet(first) ? "" : kinds[first];
        int last = first;
        while (last + 1 <= limit && want.equals(kinds[last + 1]) && !isBlockTag(last + 1)) {
            last++;
        }
        return last;
    }

    /** {@code {first, last}} line indices of the paragraph holding line {@code li}; null on a separator. */
    private int[] paragraphAt(int li) {
        String k = kinds[li];
        if (k == null) {
            return null;
        }
        int first = li;
        if (!isBullet(li)) {
            while (first > 0 && !isBlockTag(first) && k.equals(kinds[first - 1])) {
                if (isBullet(first - 1)) {
                    break; // k is "*" here: the item above is its own paragraph
                }
                first--;
            }
            if (k.isEmpty() && first > 0 && isBullet(first - 1)) {
                first--; // plain lines under a list item belong to it
            }
        }
        return new int[] {first, paragraphEnd(first, lines.size() - 1)};
    }

    /** The fill prefix of line {@code index}: a list item's indent and marker, else its {@link #fillPrefix}. */
    private String prefixOf(int index) {
        String line = line(index);
        return LIST.equals(kinds[index]) ? listPrefix(line) : fillPrefix(line, lineComment);
    }

    /** {@code line}'s leading whitespace, list marker and the whitespace after it; null when it is no list item. */
    private static String listPrefix(String line) {
        int ws = leadingWhitespace(line);
        int end = listMarkerEnd(line, ws);
        if (end < 0 && line.startsWith("*", ws)) {
            end = markerEnd(line, ws, "*");
        }
        return end < 0 ? null : line.substring(0, end + leadingWhitespace(line.substring(end)));
    }

    /**
     * The two prefixes Auto Fill needs for a prose line: the line's own (never broken inside) and the one
     * its wrapped tail starts with. They differ for a list item, whose tail hangs under the item's text
     * instead of becoming a second item.
     */
    public static String[] proseBreakPrefixes(String line, String lineComment) {
        String list = listPrefix(line);
        if (list != null && !line.substring(list.length()).isBlank()) {
            return new String[] {list, hangingIndent(list)};
        }
        String prefix = fillPrefix(line, lineComment);
        return new String[] {prefix, prefix};
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
