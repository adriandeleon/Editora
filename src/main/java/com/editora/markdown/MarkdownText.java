package com.editora.markdown;

/**
 * The plain text of a line of inline Markdown — what a reader sees once it is rendered: emphasis and
 * strikethrough markers gone, a link or image reduced to its text, inline code without its backticks, HTML
 * tags dropped, backslash escapes resolved. Pure.
 *
 * <p>A heading's plain text is what its anchor is made from and what a table of contents or an outline
 * should show: {@code ## The `foo` [API](x.md)} is the entry "The foo API" with the anchor
 * {@code the-foo-api}, not a link nested inside the entry's own link.
 */
public final class MarkdownText {

    private MarkdownText() {}

    /** The plain text of {@code inline} (one line of inline Markdown), stripped. */
    public static String plain(String inline) {
        if (inline == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(inline.length());
        append(inline, out);
        return out.toString().strip();
    }

    private static void append(String s, StringBuilder out) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && isPunctuation(s.charAt(i + 1))) {
                out.append(s.charAt(++i));
            } else if (c == '`') {
                int run = runLength(s, i, '`');
                int close = closingRun(s, i + run, run);
                if (close < 0) {
                    out.append(s, i, i + run);
                    i += run - 1;
                } else {
                    String code = s.substring(i + run, close);
                    boolean padded = code.length() > 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank();
                    out.append(padded ? code.substring(1, code.length() - 1) : code);
                    i = close + run - 1;
                }
            } else if (c == '[' || (c == '!' && i + 1 < n && s.charAt(i + 1) == '[')) {
                int open = c == '!' ? i + 1 : i;
                int close = matching(s, open, '[', ']');
                int end = close < 0 ? -1 : targetEnd(s, close + 1);
                if (end < 0) {
                    out.append(c);
                } else {
                    append(s.substring(open + 1, close), out);
                    i = end - 1;
                }
            } else if (c == '<') {
                int close = s.indexOf('>', i + 1);
                String inner = close < 0 ? "" : s.substring(i + 1, close);
                if (inner.matches("(?i)[a-z][a-z0-9+.-]*:[^\\s<>]*") || inner.matches("[^\\s<>@]+@[^\\s<>@]+")) {
                    out.append(inner); // an autolink shows its address
                    i = close;
                } else if (inner.matches("/?[A-Za-z][A-Za-z0-9-]*(\\s[^<>]*)?/?")) {
                    i = close; // an HTML tag shows nothing
                } else {
                    out.append(c);
                }
            } else if (c == '*' || c == '_' || c == '~') {
                int run = runLength(s, i, c);
                char before = i > 0 ? s.charAt(i - 1) : ' ';
                char after = i + run < n ? s.charAt(i + run) : ' ';
                boolean alone = Character.isWhitespace(before) && Character.isWhitespace(after);
                boolean insideWord = c == '_' && Character.isLetterOrDigit(before) && Character.isLetterOrDigit(after);
                if (alone || insideWord || (c == '~' && run < 2)) {
                    out.append(s, i, i + run); // "2 * 3", snake_case, "~5": text, not markers
                }
                i += run - 1;
            } else {
                out.append(c);
            }
        }
    }

    private static boolean isPunctuation(char c) {
        return c < 0x80 && !Character.isLetterOrDigit(c) && !Character.isWhitespace(c);
    }

    private static int runLength(String s, int at, char c) {
        int n = 0;
        while (at + n < s.length() && s.charAt(at + n) == c) {
            n++;
        }
        return n;
    }

    /** Start of the next run of exactly {@code run} backticks at or after {@code from}; -1 if none. */
    private static int closingRun(String s, int from, int run) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '`') {
                int len = runLength(s, i, '`');
                if (len == run) {
                    return i;
                }
                i += len - 1;
            }
        }
        return -1;
    }

    /** Index of the bracket closing the one at {@code open}, honouring nesting and escapes; -1 if none. */
    private static int matching(String s, int open, char left, char right) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == left) {
                depth++;
            } else if (c == right && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The offset just past a link's target starting at {@code at} — {@code (destination "title")} or
     * {@code [reference]} — or {@code at} itself for a shortcut reference ({@code [text]} followed by nothing
     * it could be confused with); -1 when what starts there shows the brackets are plain text.
     */
    private static int targetEnd(String s, int at) {
        if (at < s.length() && s.charAt(at) == '(') {
            int close = matching(s, at, '(', ')');
            return close < 0 ? -1 : close + 1;
        }
        if (at < s.length() && s.charAt(at) == '[') {
            int close = matching(s, at, '[', ']');
            return close < 0 ? -1 : close + 1;
        }
        return -1;
    }
}
