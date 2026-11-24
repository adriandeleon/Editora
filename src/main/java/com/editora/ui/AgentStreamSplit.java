package com.editora.ui;

/**
 * Pure: how much of a Markdown reply that is still streaming in can be rendered once and left alone.
 *
 * <p>The agent panel re-renders the reply it is receiving several times a second. Parsing the whole reply
 * each time makes every render cost as much as the reply is long; nearly all of that is spent rebuilding
 * blocks that will never change again. {@link #settledEnd} finds the last place where the text before it is
 * a run of finished top-level blocks, so only what follows has to be parsed again.
 *
 * <p>The rule is deliberately narrower than CommonMark's notion of a block boundary: a boundary is taken
 * only before a complete, unindented line that follows a blank line outside a fenced code block, and that
 * does not continue the list or the quote before it. Rendering the two sides separately then gives the same blocks as
 * rendering them together. The constructs this cannot see (a reference definition that arrives later, an
 * HTML block with blank lines in it, a footnote, display math) make the panel render a finished reply that
 * holds them once as a whole ({@link #needsWholeRender}); any other finished reply keeps its settled blocks.
 */
final class AgentStreamSplit {

    private AgentStreamSplit() {}

    /**
     * The largest offset {@code p >= from} such that {@code markdown[from, p)} can be rendered apart from
     * the rest, or {@code from} when there is none yet. {@code from} must be 0 or an offset this method
     * returned earlier (a line start outside any code fence).
     */
    static int settledEnd(CharSequence markdown, int from) {
        int length = markdown.length();
        int best = from;
        boolean previousBlank = false;
        int container = PLAIN; // what the block before the line under test is: a list, a quote, or neither
        char fence = 0; // the open code fence's character, or 0 outside one
        int fenceLength = 0;
        int lineStart = from;
        while (lineStart < length) {
            int lineEnd = indexOfNewline(markdown, lineStart);
            if (lineEnd < 0) {
                break; // the last line is still arriving: it may yet turn into a list item or a fence
            }
            boolean blank = isBlank(markdown, lineStart, lineEnd);
            if (fence == 0 && !blank) {
                int kind = kindOf(markdown, lineStart);
                // A list item or quote line after a blank line continues the list or quote above it (a
                // loose list, and its numbering); after anything else it starts a new block.
                boolean starts = kind == PLAIN || (kind != OPAQUE && kind != container);
                if (previousBlank && starts && lineStart > from) {
                    best = lineStart;
                    container = kind;
                } else if (kind == LIST || kind == QUOTE) {
                    container = kind;
                }
            }
            int run = fenceRun(markdown, lineStart, lineEnd);
            if (run > 0) {
                char c = markdown.charAt(firstNonSpace(markdown, lineStart, lineEnd));
                if (fence == 0) {
                    fence = c;
                    fenceLength = run;
                } else if (c == fence && run >= fenceLength && closesFence(markdown, lineStart, lineEnd)) {
                    fence = 0;
                }
            }
            previousBlank = blank;
            lineStart = lineEnd + 1;
        }
        return best;
    }

    /**
     * Whether a finished reply must be rendered whole rather than keep its blocks before {@code settled} (an
     * offset {@link #settledEnd} returned) and render only the rest: when it holds what spans the split — a
     * link reference definition (which any block may use), a footnote (numbered across the reply), display
     * math ({@code $$}, which may hold blank lines) — or an HTML line before the split (an HTML block may run
     * across blank lines). Cautious: a false alarm only costs the whole render the reply always had.
     */
    static boolean needsWholeRender(CharSequence markdown, int settled) {
        if (settled <= 0) {
            return false; // nothing is kept: the rest is the whole reply
        }
        int length = markdown.length();
        for (int i = 0; i + 1 < length; i++) {
            char c = markdown.charAt(i);
            char next = markdown.charAt(i + 1);
            if ((c == '[' && next == '^') || (c == '^' && next == '[') || (c == '$' && next == '$')) {
                return true;
            }
        }
        int lineStart = 0;
        while (lineStart < length) {
            int lineEnd = indexOfNewline(markdown, lineStart);
            int end = lineEnd < 0 ? length : lineEnd;
            int i = firstNonSpace(markdown, lineStart, end);
            if (i - lineStart <= 3 && i < end) {
                char c = markdown.charAt(i);
                if (c == '<' && lineStart < settled) {
                    return true;
                }
                if (c == '[' && isReferenceDefinition(markdown, i, end)) {
                    return true;
                }
            }
            if (lineEnd < 0) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return false;
    }

    /** Whether the line from {@code open} (a {@code [}) to {@code end} starts like {@code [label]:}. */
    private static boolean isReferenceDefinition(CharSequence s, int open, int end) {
        for (int i = open + 1; i + 1 < end; i++) {
            char c = s.charAt(i);
            if (c == ']') {
                return i > open + 1 && s.charAt(i + 1) == ':';
            }
            if (c == '[') {
                return false;
            }
        }
        return false;
    }

    private static final int PLAIN = 0;
    private static final int LIST = 1;
    private static final int QUOTE = 2;
    /** Never a boundary: an indented continuation or code line, or what may be an HTML block. */
    private static final int OPAQUE = 3;

    /** What the non-blank line at {@code start} is, as far as splitting is concerned. */
    private static int kindOf(CharSequence s, int start) {
        char c = s.charAt(start);
        if (Character.isWhitespace(c) || c == '<') {
            return OPAQUE;
        }
        if (c == '>') {
            return QUOTE;
        }
        if (c == '-' || c == '*' || c == '+') {
            // A bullet needs a space after it; "**bold**" and "---" are ordinary block starts.
            return start + 1 < s.length() && Character.isWhitespace(s.charAt(start + 1)) ? LIST : PLAIN;
        }
        int i = start;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        boolean numbered = i > start && i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == ')');
        return numbered ? LIST : PLAIN;
    }

    /** The length of the code-fence marker ({@code ```} or {@code ~~~}, at least three) the line starts with. */
    private static int fenceRun(CharSequence s, int start, int end) {
        int i = firstNonSpace(s, start, end);
        if (i - start > 3 || i >= end) {
            return 0; // four or more spaces is indented code, not a fence
        }
        char c = s.charAt(i);
        if (c != '`' && c != '~') {
            return 0;
        }
        int run = 0;
        while (i + run < end && s.charAt(i + run) == c) {
            run++;
        }
        return run >= 3 ? run : 0;
    }

    /** A closing fence carries nothing after its marker. */
    private static boolean closesFence(CharSequence s, int start, int end) {
        int i = firstNonSpace(s, start, end);
        char c = s.charAt(i);
        while (i < end && s.charAt(i) == c) {
            i++;
        }
        return isBlank(s, i, end);
    }

    private static int firstNonSpace(CharSequence s, int start, int end) {
        int i = start;
        while (i < end && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static boolean isBlank(CharSequence s, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfNewline(CharSequence s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                return i;
            }
        }
        return -1;
    }
}
