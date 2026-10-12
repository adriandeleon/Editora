package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One pure pass over a Markdown document that says, for every line, what kind of block it belongs to — so the
 * linter, the fixer, folding, spell check, the outline and the table/list helpers all agree on what is code and
 * what is prose, instead of each carrying its own fence scan.
 *
 * <p>What it recognises:
 *
 * <ul>
 *   <li><b>Front matter</b> at the very top: {@code ---} … {@code ---} or {@code ...} (YAML), and {@code +++} …
 *       {@code +++} (TOML). An opener that is never closed is not front matter.
 *   <li><b>Fenced code</b> with CommonMark's rules: backtick or tilde runs of three or more, up to three columns
 *       of indentation <em>relative to the enclosing container</em> (a list item's content column, a
 *       {@code >} block quote), a closing fence of the same character, at least as long, with nothing after it,
 *       and no backtick in a backtick fence's info string. A fence ends with its container.
 *   <li><b>Indented code</b>: four or more columns past the container's content column, when no paragraph is
 *       open (an indented line right under paragraph text is a continuation, not code).
 *   <li><b>HTML comments</b> ({@code <!-- … -->}, single- or multi-line) and the verbatim HTML blocks
 *       {@code <pre>}, {@code <script>}, {@code <style>}, {@code <textarea>} up to their closing tag.
 *   <li><b>Code spans</b> inside a prose line, matched by backtick run length ({@code `` ` ``} is one span).
 * </ul>
 *
 * <p>Lines are 0-based; columns in the returned ranges are 0-based UTF-16 offsets into the line, end exclusive.
 * A trailing {@code \r} on a line is ignored. Tabs count to the next multiple of four when measuring indentation.
 *
 * <p>Deliberately not a full CommonMark parser: lazy continuation lines, block quotes nested inside list items
 * and code spans that run across lines are not modelled. Those cases classify as {@link Kind#TEXT}, the safe
 * side for a linter (a diagnostic too many) and the documented limit for the other callers.
 *
 * <p>Immutable once built, stateless otherwise, toolkit-free — unit-tested directly.
 */
public final class MarkdownBlocks {

    /** What a line is part of. */
    public enum Kind {
        /** Ordinary Markdown (paragraph, heading, list item, table row, blank line…). */
        TEXT,
        /** A line of the leading YAML/TOML front matter, delimiters included. */
        FRONT_MATTER,
        /** The opening fence line of a fenced code block. */
        FENCE_OPEN,
        /** A line inside a fenced code block. */
        FENCE_BODY,
        /** The closing fence line of a fenced code block. */
        FENCE_CLOSE,
        /** A line of an indented code block (blank lines between its lines included). */
        INDENTED_CODE,
        /** A line that starts with, or continues, an HTML comment. */
        HTML_COMMENT,
        /** A line of a {@code <pre>}/{@code <script>}/{@code <style>}/{@code <textarea>} block. */
        HTML_BLOCK
    }

    /**
     * A fenced code block.
     *
     * @param openLine the opening fence's line
     * @param closeLine the closing fence's line, or {@code -1} when the block runs to the end of its container
     *     (or of the document) without one
     * @param lastLine the last line that belongs to the block (the closing fence, or the last body line)
     * @param marker {@code '`'} or {@code '~'}
     * @param length the opening run's length (3 or more)
     * @param markerOffset the opening run's offset in its line
     * @param contentColumn the enclosing container's content column (0 at top level)
     * @param quoteDepth how many {@code >} block-quote markers enclose the fence
     * @param info the info string, trimmed ("" when there is none)
     */
    public record Fence(
            int openLine,
            int closeLine,
            int lastLine,
            char marker,
            int length,
            int markerOffset,
            int contentColumn,
            int quoteDepth,
            String info) {

        /** Whether the block has an explicit closing fence. */
        public boolean closed() {
            return closeLine >= 0;
        }
    }

    private static final int[] NO_RANGES = new int[0];
    private static final String[] VERBATIM_TAGS = {"pre", "script", "style", "textarea"};

    private final String[] lines;
    private final Kind[] kinds;
    private final int[] contentStart;
    private final int[] fenceOf;
    private final int[][] commentRanges;
    private final int[][] codeSpans;
    private final List<Fence> fences = new ArrayList<>();
    private int frontMatterEnd = -1;

    private MarkdownBlocks(String[] raw) {
        lines = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            lines[i] = stripCr(raw[i] == null ? "" : raw[i]);
        }
        kinds = new Kind[lines.length];
        contentStart = new int[lines.length];
        fenceOf = new int[lines.length];
        commentRanges = new int[lines.length][];
        codeSpans = new int[lines.length][];
        java.util.Arrays.fill(fenceOf, -1);
        scan();
    }

    /** Scans {@code text} (lines split on {@code \n}; {@code null} is the empty document). */
    public static MarkdownBlocks scan(String text) {
        return new MarkdownBlocks(text == null ? new String[] {""} : text.split("\n", -1));
    }

    /** Scans already-split {@code lines} (each may still end in {@code \r}). */
    public static MarkdownBlocks scan(String[] lines) {
        return new MarkdownBlocks(lines == null ? new String[0] : lines);
    }

    // --- queries --------------------------------------------------------------------------------------

    public int lineCount() {
        return lines.length;
    }

    /** The line's text without a trailing {@code \r}. */
    public String line(int line) {
        return lines[line];
    }

    /** What {@code line} is part of; {@link Kind#TEXT} for an out-of-range line. */
    public Kind kind(int line) {
        return line < 0 || line >= kinds.length ? Kind.TEXT : kinds[line];
    }

    /** Whether {@code line} is Markdown the caller should interpret (not code, front matter or raw HTML). */
    public boolean isText(int line) {
        return kind(line) == Kind.TEXT;
    }

    /** Whether {@code line} belongs to a fenced or indented code block (fence lines included). */
    public boolean isCode(int line) {
        Kind k = kind(line);
        return k == Kind.FENCE_OPEN || k == Kind.FENCE_BODY || k == Kind.FENCE_CLOSE || k == Kind.INDENTED_CODE;
    }

    /** Whether {@code line}'s characters must be left exactly as they are (anything but {@link Kind#TEXT}). */
    public boolean isVerbatim(int line) {
        return kind(line) != Kind.TEXT;
    }

    /**
     * The offset in {@code line} where its block content starts: after the {@code >} markers and the list
     * marker (or, on a continuation line, at the enclosing list item's content column). At top level this is 0,
     * so {@link #content} keeps a line's own leading spaces.
     */
    public int contentStart(int line) {
        return line < 0 || line >= contentStart.length ? 0 : contentStart[line];
    }

    /** {@code line} from {@link #contentStart} on — what a block-level rule should look at. */
    public String content(int line) {
        if (line < 0 || line >= lines.length) {
            return "";
        }
        return lines[line].substring(Math.min(contentStart[line], lines[line].length()));
    }

    /** Whether {@code line} has no content inside its container (out-of-range lines count as blank). */
    public boolean isBlank(int line) {
        return content(line).isBlank();
    }

    /** The last line of the leading front matter (its closing delimiter), or {@code -1} when there is none. */
    public int frontMatterEnd() {
        return frontMatterEnd;
    }

    /** Every fenced code block, in document order. */
    public List<Fence> fences() {
        return java.util.Collections.unmodifiableList(fences);
    }

    /** The fenced block {@code line} belongs to (its fence lines included), or {@code null}. */
    public Fence fenceAt(int line) {
        return line < 0 || line >= fenceOf.length || fenceOf[line] < 0 ? null : fences.get(fenceOf[line]);
    }

    /**
     * The parts of {@code line} inside an HTML comment, as {@code [start, end)} pairs laid flat
     * ({@code {s0, e0, s1, e1, …}}); empty when there are none.
     */
    public int[] htmlCommentRanges(int line) {
        int[] r = line < 0 || line >= commentRanges.length ? null : commentRanges[line];
        return r == null ? NO_RANGES : r.clone();
    }

    /** Whether offset {@code column} of {@code line} lies inside an HTML comment. */
    public boolean inHtmlComment(int line, int column) {
        int[] r = line < 0 || line >= commentRanges.length ? null : commentRanges[line];
        return r != null && inRanges(r, column);
    }

    /**
     * The code spans of a prose {@code line}, delimiters included, as flat {@code [start, end)} pairs; empty for
     * a line that is not {@link Kind#TEXT}.
     */
    public int[] codeSpans(int line) {
        return spansOf(line).clone();
    }

    /** Whether offset {@code column} of {@code line} lies inside a code span (its backticks included). */
    public boolean inCodeSpan(int line, int column) {
        return inRanges(spansOf(line), column);
    }

    private int[] spansOf(int line) {
        if (line < 0 || line >= lines.length || kinds[line] != Kind.TEXT) {
            return NO_RANGES;
        }
        if (codeSpans[line] == null) {
            codeSpans[line] = codeSpans(lines[line]);
        }
        return codeSpans[line];
    }

    /**
     * The code spans of one line of prose, delimiters included, as flat {@code [start, end)} pairs. A span opens
     * with a run of N backticks and closes at the next run of exactly N; a run with no partner is literal text,
     * and a backslash-escaped backtick does not open a span. Linear in the line's length.
     */
    public static int[] codeSpans(String line) {
        if (line == null || line.indexOf('`') < 0) {
            return NO_RANGES;
        }
        // Every backtick run, as (start, length).
        int n = line.length();
        List<int[]> runs = new ArrayList<>();
        for (int i = 0; i < n; ) {
            if (line.charAt(i) != '`') {
                i++;
                continue;
            }
            int j = i;
            while (j < n && line.charAt(j) == '`') {
                j++;
            }
            runs.add(new int[] {i, j - i});
            i = j;
        }
        // For each run, the next run of the same length (found right-to-left, so the whole pass is linear).
        int[] next = new int[runs.size()];
        java.util.Map<Integer, Integer> seen = new java.util.HashMap<>();
        for (int r = runs.size() - 1; r >= 0; r--) {
            Integer later = seen.get(runs.get(r)[1]);
            next[r] = later == null ? -1 : later;
            seen.put(runs.get(r)[1], r);
        }
        List<Integer> out = new ArrayList<>();
        for (int r = 0; r < runs.size(); r++) {
            int start = runs.get(r)[0];
            if (escaped(line, start) && runs.get(r)[1] == 1) {
                continue; // \` is a literal backtick
            }
            if (next[r] < 0) {
                continue; // no closing run of this length: literal backticks
            }
            int[] close = runs.get(next[r]);
            out.add(start);
            out.add(close[0] + close[1]);
            r = next[r];
        }
        int[] flat = new int[out.size()];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = out.get(i);
        }
        return flat;
    }

    /** Whether {@code offset} falls in one of the flat {@code [start, end)} {@code ranges}. */
    public static boolean inRanges(int[] ranges, int offset) {
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            if (offset >= ranges[i] && offset < ranges[i + 1]) {
                return true;
            }
        }
        return false;
    }

    // --- the scan -------------------------------------------------------------------------------------

    /** The fence being read. */
    private static final class OpenFence {
        int openLine;
        char marker;
        int length;
        int markerOffset;
        int contentColumn;
        int quoteDepth;
        String info;
    }

    private void scan() {
        frontMatterEnd = findFrontMatterEnd(lines);
        List<Integer> listColumns = new ArrayList<>(); // content columns of the open list items, outermost first
        OpenFence fence = null;
        String htmlBlockEnd = null; // the closing tag of the open <pre>/<script>/… block
        boolean inComment = false;
        boolean paragraphOpen = false;
        int quoteDepth = 0;
        int lastIndentedCode = -2;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i <= frontMatterEnd) {
                kinds[i] = Kind.FRONT_MATTER;
                continue;
            }

            // Block-quote prefix. Inside a fence only the fence's own markers are a prefix: a body line may
            // well start with '>' of its own.
            int limit = fence == null ? Integer.MAX_VALUE : fence.quoteDepth;
            int pos = 0;
            int depth = 0;
            while (depth < limit) {
                int p = pos;
                int spaces = 0;
                while (p < line.length() && line.charAt(p) == ' ' && spaces < 3) {
                    p++;
                    spaces++;
                }
                if (p < line.length() && line.charAt(p) == '>') {
                    depth++;
                    pos = p + 1;
                    if (pos < line.length() && (line.charAt(pos) == ' ' || line.charAt(pos) == '\t')) {
                        pos++;
                    }
                } else {
                    break;
                }
            }
            boolean blank = line.substring(pos).isBlank();
            int firstText = pos;
            int indent = 0; // columns of whitespace after the quote prefix
            while (firstText < line.length() && (line.charAt(firstText) == ' ' || line.charAt(firstText) == '\t')) {
                indent += line.charAt(firstText) == '\t' ? 4 - indent % 4 : 1;
                firstText++;
            }

            if (fence != null) {
                boolean containerClosed =
                        depth < fence.quoteDepth || (!blank && fence.contentColumn > 0 && indent < fence.contentColumn);
                if (containerClosed) {
                    endFence(fence, -1, i - 1);
                    fence = null;
                    i--; // read this line again, as ordinary content
                    continue;
                }
                contentStart[i] = offsetAtColumn(line, pos, fence.contentColumn);
                if (closesFence(line, firstText, indent - fence.contentColumn, fence)) {
                    kinds[i] = Kind.FENCE_CLOSE;
                    endFence(fence, i, i);
                    fence = null;
                } else {
                    kinds[i] = Kind.FENCE_BODY;
                }
                paragraphOpen = false;
                continue;
            }
            if (htmlBlockEnd != null) {
                kinds[i] = Kind.HTML_BLOCK;
                if (line.toLowerCase(Locale.ROOT).contains(htmlBlockEnd)) {
                    htmlBlockEnd = null;
                }
                continue;
            }
            if (inComment) {
                kinds[i] = Kind.HTML_COMMENT;
                int close = line.indexOf("-->");
                commentRanges[i] = new int[] {0, close < 0 ? line.length() : close + 3};
                inComment = close < 0;
                continue;
            }

            kinds[i] = Kind.TEXT;
            if (blank) {
                contentStart[i] = Math.min(pos, line.length());
                paragraphOpen = false;
                if (depth != quoteDepth) {
                    listColumns.clear();
                    quoteDepth = depth;
                }
                continue;
            }
            if (depth != quoteDepth) {
                listColumns.clear();
                if (depth > quoteDepth) {
                    paragraphOpen = false;
                }
                quoteDepth = depth;
            }
            while (!listColumns.isEmpty() && indent < listColumns.get(listColumns.size() - 1)) {
                listColumns.remove(listColumns.size() - 1);
            }
            int base = listColumns.isEmpty() ? 0 : listColumns.get(listColumns.size() - 1);
            contentStart[i] = offsetAtColumn(line, pos, base);
            if (indent - base >= 4) {
                if (!paragraphOpen) {
                    kinds[i] = Kind.INDENTED_CODE;
                    contentStart[i] = offsetAtColumn(line, pos, base + 4);
                    if (lastIndentedCode >= 0 && allBlankText(lastIndentedCode + 1, i)) {
                        for (int k = lastIndentedCode + 1; k < i; k++) {
                            kinds[k] = Kind.INDENTED_CODE; // blank lines inside the block are part of it
                        }
                    }
                    lastIndentedCode = i;
                }
                continue; // otherwise a continuation line of the open paragraph
            }

            // A block starts here. Peel list markers ("- 1. text" nests two items on one line).
            int at = firstText;
            int column = indent;
            boolean emptyItem = false;
            while (!thematicBreak(line, at)) {
                int markerEnd = listMarkerEnd(line, at, paragraphOpen);
                if (markerEnd < 0) {
                    break;
                }
                int markerColumns = markerEnd - at;
                int p = markerEnd;
                int gap = 0;
                while (p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) {
                    gap += line.charAt(p) == '\t' ? 4 - (column + markerColumns + gap) % 4 : 1;
                    p++;
                }
                paragraphOpen = false;
                if (p >= line.length()) {
                    listColumns.add(column + markerColumns + 1);
                    at = line.length();
                    emptyItem = true;
                    break;
                }
                if (gap >= 5) { // "-      code": the item's content starts one column after the marker
                    listColumns.add(column + markerColumns + 1);
                    at = markerEnd + 1;
                    column += markerColumns + 1;
                    break;
                }
                column += markerColumns + gap;
                listColumns.add(column);
                at = p;
            }
            if (at != firstText) {
                contentStart[i] = Math.min(at, line.length());
            }
            if (emptyItem) {
                continue;
            }

            OpenFence opened = opensFence(line, at);
            if (opened != null) {
                opened.openLine = i;
                opened.contentColumn = listColumns.isEmpty() ? 0 : listColumns.get(listColumns.size() - 1);
                opened.quoteDepth = depth;
                fence = opened;
                kinds[i] = Kind.FENCE_OPEN;
                paragraphOpen = false;
                continue;
            }
            if (line.startsWith("<!--", at)) {
                kinds[i] = Kind.HTML_COMMENT;
                inComment = recordComments(i, line, at);
                paragraphOpen = false;
                continue;
            }
            String verbatimEnd = verbatimHtmlEnd(line, at);
            if (verbatimEnd != null) {
                kinds[i] = Kind.HTML_BLOCK;
                String lower = line.toLowerCase(Locale.ROOT);
                htmlBlockEnd = lower.indexOf(verbatimEnd, at) >= 0 ? null : verbatimEnd;
                paragraphOpen = false;
                continue;
            }
            // Ordinary text. A comment may still open in the middle of it.
            inComment = recordComments(i, line, at);
            paragraphOpen = !atxHeading(line, at) && !thematicBreak(line, at) && !setextUnderline(line, at);
        }
        if (fence != null) {
            endFence(fence, -1, lines.length - 1);
        }
    }

    private void endFence(OpenFence f, int closeLine, int lastLine) {
        int index = fences.size();
        fences.add(new Fence(
                f.openLine,
                closeLine,
                lastLine,
                f.marker,
                f.length,
                f.markerOffset,
                f.contentColumn,
                f.quoteDepth,
                f.info));
        for (int k = f.openLine; k <= lastLine && k < fenceOf.length; k++) {
            fenceOf[k] = index;
            if (k > f.openLine && kinds[k] == null) {
                kinds[k] = Kind.FENCE_BODY;
            }
        }
    }

    private boolean allBlankText(int from, int to) {
        for (int k = from; k < to; k++) {
            if (kinds[k] != Kind.TEXT || !lines[k].isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** Records the HTML comments that start at or after {@code from}; true when the last one is still open. */
    private boolean recordComments(int lineNo, String line, int from) {
        int open = line.indexOf("<!--", from);
        if (open < 0) {
            return false;
        }
        int[] spans = codeSpans(line);
        List<Integer> out = new ArrayList<>();
        boolean unclosed = false;
        while (open >= 0) {
            if (inRanges(spans, open)) {
                open = line.indexOf("<!--", open + 4); // `<!--` shown as code
                continue;
            }
            int close = line.indexOf("-->", open + 4);
            out.add(open);
            if (close < 0) {
                out.add(line.length());
                unclosed = true;
                break;
            }
            out.add(close + 3);
            open = line.indexOf("<!--", close + 3);
        }
        if (!out.isEmpty()) {
            int[] flat = new int[out.size()];
            for (int k = 0; k < flat.length; k++) {
                flat[k] = out.get(k);
            }
            commentRanges[lineNo] = flat;
        }
        return unclosed;
    }

    /** The last line of a leading front-matter block in {@code lines}, or {@code -1}. */
    static int findFrontMatterEnd(String[] lines) {
        if (lines.length == 0) {
            return -1;
        }
        String first = stripCr(lines[0]).stripTrailing();
        boolean yaml = first.equals("---");
        if (!yaml && !first.equals("+++")) {
            return -1;
        }
        for (int j = 1; j < lines.length; j++) {
            String s = stripCr(lines[j]).stripTrailing();
            if (yaml ? s.equals("---") || s.equals("...") : s.equals("+++")) {
                return j;
            }
        }
        return -1;
    }

    private static OpenFence opensFence(String line, int at) {
        if (at >= line.length()) {
            return null;
        }
        char c = line.charAt(at);
        if (c != '`' && c != '~') {
            return null;
        }
        int end = at;
        while (end < line.length() && line.charAt(end) == c) {
            end++;
        }
        if (end - at < 3) {
            return null;
        }
        String info = line.substring(end).strip();
        if (c == '`' && info.indexOf('`') >= 0) {
            return null; // ```code``` at the start of a paragraph is a code span, not a fence
        }
        OpenFence f = new OpenFence();
        f.marker = c;
        f.length = end - at;
        f.markerOffset = at;
        f.info = info;
        return f;
    }

    private static boolean closesFence(String line, int at, int relativeIndent, OpenFence fence) {
        if (relativeIndent > 3 || at >= line.length() || line.charAt(at) != fence.marker) {
            return false;
        }
        int end = at;
        while (end < line.length() && line.charAt(end) == fence.marker) {
            end++;
        }
        return end - at >= fence.length && line.substring(end).isBlank();
    }

    /** The offset just past a list marker at {@code at} ({@code -}, {@code +}, {@code *}, {@code 1.}, {@code 1)}), or -1. */
    private static int listMarkerEnd(String line, int at, boolean paragraphOpen) {
        if (at >= line.length()) {
            return -1;
        }
        char c = line.charAt(at);
        int end;
        if (c == '-' || c == '+' || c == '*') {
            end = at + 1;
        } else if (c >= '0' && c <= '9') {
            int p = at;
            while (p < line.length() && p - at < 9 && line.charAt(p) >= '0' && line.charAt(p) <= '9') {
                p++;
            }
            if (p >= line.length() || (line.charAt(p) != '.' && line.charAt(p) != ')')) {
                return -1;
            }
            if (paragraphOpen && !line.substring(at, p).equals("1")) {
                return -1; // only a list starting at 1 can interrupt a paragraph
            }
            end = p + 1;
        } else {
            return -1;
        }
        if (end >= line.length()) {
            return paragraphOpen ? -1 : end; // an empty item cannot interrupt a paragraph
        }
        char after = line.charAt(end);
        return after == ' ' || after == '\t' ? end : -1;
    }

    /** {@code ***}, {@code ---}, {@code ___} (three or more, spaces allowed between) from {@code at} on. */
    private static boolean thematicBreak(String line, int at) {
        char mark = 0;
        int count = 0;
        for (int p = at; p < line.length(); p++) {
            char c = line.charAt(p);
            if (c == ' ' || c == '\t') {
                continue;
            }
            if (c != '-' && c != '*' && c != '_') {
                return false;
            }
            if (mark == 0) {
                mark = c;
            } else if (c != mark) {
                return false;
            }
            count++;
        }
        return count >= 3;
    }

    private static boolean setextUnderline(String line, int at) {
        String s = line.substring(Math.min(at, line.length())).strip();
        if (s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        if (c != '=' && c != '-') {
            return false;
        }
        for (int p = 1; p < s.length(); p++) {
            if (s.charAt(p) != c) {
                return false;
            }
        }
        return true;
    }

    private static boolean atxHeading(String line, int at) {
        int p = at;
        while (p < line.length() && line.charAt(p) == '#') {
            p++;
        }
        int hashes = p - at;
        return hashes >= 1 && hashes <= 6 && (p >= line.length() || line.charAt(p) == ' ' || line.charAt(p) == '\t');
    }

    /** The closing tag that ends the verbatim HTML block opening at {@code at}, or null when none opens there. */
    private static String verbatimHtmlEnd(String line, int at) {
        if (at >= line.length() || line.charAt(at) != '<') {
            return null;
        }
        for (String tag : VERBATIM_TAGS) {
            int end = at + 1 + tag.length();
            if (line.regionMatches(true, at + 1, tag, 0, tag.length())
                    && (end >= line.length() || line.charAt(end) == '>' || Character.isWhitespace(line.charAt(end)))) {
                return "</" + tag + ">";
            }
        }
        return null;
    }

    /** The offset in {@code line}, at or after {@code from}, where whitespace reaches {@code column}. */
    private static int offsetAtColumn(String line, int from, int column) {
        int p = from;
        int col = 0;
        while (p < line.length() && col < column && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) {
            col += line.charAt(p) == '\t' ? 4 - col % 4 : 1;
            p++;
        }
        return p;
    }

    private static boolean escaped(String line, int offset) {
        int slashes = 0;
        for (int p = offset - 1; p >= 0 && line.charAt(p) == '\\'; p--) {
            slashes++;
        }
        return (slashes & 1) == 1;
    }

    private static String stripCr(String line) {
        return !line.isEmpty() && line.charAt(line.length() - 1) == '\r' ? line.substring(0, line.length() - 1) : line;
    }
}
