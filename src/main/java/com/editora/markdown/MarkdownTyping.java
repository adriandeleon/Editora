package com.editora.markdown;

/**
 * What a typed character does in Markdown prose beyond inserting itself: {@code *} and {@code _} come in
 * pairs the way a backtick does, and — when smart typography is on — straight quotes, double hyphens and
 * three dots become their typographic forms. Pure: the decision is made from the caret's line alone, and
 * the caller only asks for a line that is prose (not a code block, front matter or raw HTML).
 *
 * <p>Nothing is ever changed inside a code span, an HTML tag or a URL.
 */
public final class MarkdownTyping {

    private MarkdownTyping() {}

    /** Whether typing {@code c} can do anything here at all — the cheap test to make before reading the line. */
    public static boolean interesting(char c, boolean smart) {
        return c == '*' || c == '_' || c == ' ' || (smart && (c == '"' || c == '\'' || c == '-' || c == '.'));
    }

    /**
     * The edit for typing {@code c} at column {@code col} of {@code line} (offsets in the line), or
     * {@code null} for ordinary typing.
     */
    public static MarkdownEdit typed(String line, int col, char c, boolean smart) {
        if (col < 0 || col > line.length() || verbatim(line, col)) {
            return null;
        }
        char prev = col > 0 ? line.charAt(col - 1) : 0;
        char next = col < line.length() ? line.charAt(col) : 0;
        if (c == '*' || c == '_') {
            return marker(line, col, c, prev, next);
        }
        if (c == ' ') {
            return spaceInEmptyPair(line, col, prev, next);
        }
        return smart ? typography(line, col, c, prev) : null;
    }

    private static MarkdownEdit marker(String line, int col, char c, char prev, char next) {
        if (next == c) {
            // Inside an empty pair (*|* with nothing in front that a marker could be closing): **|** from *|*.
            int run = col;
            while (run > 0 && line.charAt(run - 1) == c) {
                run--;
            }
            char lead = run > 0 ? line.charAt(run - 1) : 0;
            boolean emptyPair =
                    run < col && (lead == 0 || Character.isWhitespace(lead) || "([{\"'“‘—–".indexOf(lead) >= 0);
            return emptyPair ? insert(col, "" + c + c, col + 1) : insert(col, "", col + 1); // else: type over
        }
        boolean opens = prev == 0 || Character.isWhitespace(prev) || "([{\"'“‘—–".indexOf(prev) >= 0;
        boolean room = next == 0 || Character.isWhitespace(next) || ")]}.,;:!?\"'”’".indexOf(next) >= 0;
        boolean bullet = c == '*' && line.substring(0, col).isBlank(); // "* item": a list marker, not emphasis
        return opens && room && !bullet ? insert(col, "" + c + c, col + 1) : null;
    }

    /** A space typed into an empty pair ("2 *|*") means the marker was a plain character: its twin goes. */
    private static MarkdownEdit spaceInEmptyPair(String line, int col, char prev, char next) {
        if ((prev != '*' && prev != '_') || next != prev) {
            return null;
        }
        int before = 0;
        while (col - before > 0 && line.charAt(col - before - 1) == prev) {
            before++;
        }
        int after = 0;
        while (col + after < line.length() && line.charAt(col + after) == prev) {
            after++;
        }
        return before == after ? new MarkdownEdit(col, col + after, " ", col + 1, col + 1) : null;
    }

    private static MarkdownEdit typography(String line, int col, char c, char prev) {
        boolean opening = prev == 0 || Character.isWhitespace(prev) || "([{—–".indexOf(prev) >= 0;
        switch (c) {
            case '"':
                return insert(col, opening ? "“" : "”", col + 1);
            case '\'':
                return insert(col, opening ? "‘" : "’", col + 1);
            case '-':
                if (prev == '–') {
                    return new MarkdownEdit(col - 1, col, "—", col, col);
                }
                if (prev == '-' && (col < 2 || line.charAt(col - 2) != '-') && afterText(line, col - 1)) {
                    return new MarkdownEdit(col - 1, col, "–", col, col);
                }
                return null;
            case '.':
                boolean twoDots = col >= 2 && prev == '.' && line.charAt(col - 2) == '.';
                return twoDots && (col < 3 || line.charAt(col - 3) != '.')
                        ? new MarkdownEdit(col - 2, col, "…", col - 1, col - 1)
                        : null;
            default:
                return null;
        }
    }

    /**
     * Whether what stands before {@code end} is running text — so a double hyphen there is a dash, not the
     * start of a rule ({@code ---}), a table's delimiter row or a list marker.
     */
    private static boolean afterText(String line, int end) {
        String before = line.substring(0, end).stripTrailing();
        if (before.isEmpty() || line.stripLeading().startsWith("|")) {
            return false;
        }
        char last = before.charAt(before.length() - 1);
        return Character.isLetterOrDigit(last) || ")]\"'”’!?.,".indexOf(last) >= 0;
    }

    private static MarkdownEdit insert(int col, String text, int caret) {
        return new MarkdownEdit(col, col, text, caret, caret);
    }

    /** Whether {@code col} is inside a code span, an HTML tag or a URL: text that is typed as it is. */
    static boolean verbatim(String line, int col) {
        if (MarkdownBlocks.inRanges(MarkdownBlocks.codeSpans(line), col)) {
            return true;
        }
        String before = line.substring(0, col);
        if (before.lastIndexOf('<') > before.lastIndexOf('>')) {
            int lt = before.lastIndexOf('<');
            if (lt + 1 < before.length()
                    && (Character.isLetter(before.charAt(lt + 1)) || before.charAt(lt + 1) == '/')) {
                return true; // an open tag or autolink
            }
        }
        int token = col;
        while (token > 0 && !Character.isWhitespace(line.charAt(token - 1))) {
            token--;
        }
        String word = before.substring(token);
        return word.contains("://") || word.startsWith("www.") || word.contains("](");
    }
}
