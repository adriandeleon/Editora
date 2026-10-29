package com.editora.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.editora.text.HtmlElements;
import org.jspecify.annotations.Nullable;

/**
 * Indents HTML: one block element per line, indented by nesting depth. A lenient lexical pass, not a parser or a
 * sanitizer: a run of text and inline elements ({@code <b>}, {@code <a>}, {@code <span>}…) stays on one line, beside
 * the block tag that holds it. The contents of {@code <pre>}, {@code <textarea>}, {@code <script>}, {@code <style>}
 * and their kin ({@link HtmlElements#VERBATIM}), comments, and tags are copied exactly; only the whitespace
 * <em>between</em> tags changes. Void elements ({@code <br>}, {@code <img>}, {@code <meta>}…) and declarations
 * ({@code <!doctype>}) open no level, and the usual omitted end tags ({@code <li>}, {@code <p>}, {@code <td>}…) close
 * themselves.
 *
 * <p>Two uses (HTML review C17, E21):
 * <ul>
 *   <li>{@link #indentResponse} lays out an HTTP response body for the HTTP Client's view. The body is returned
 *       unchanged when it does not start with a tag, when the server already indented it (a line break and
 *       indentation before a tag, outside {@code <pre>} and its kin), when it is larger than the caller's cap, or when
 *       a construct is left open.</li>
 *   <li>{@link #format} is Format Document for an HTML file no language server formats. It keeps the author's line
 *       breaks around text (a paragraph written on its own lines stays on them, re-indented) and their blank lines
 *       between blocks (at most {@value #MAX_BLANK_LINES} in a row), and indents with the buffer's own unit. A
 *       construct left open (a tag, comment or {@code <script>} without its end) leaves the file alone: null.</li>
 * </ul>
 * Line breaks are {@code \n}. Pure; any thread.
 */
public final class HtmlFormatter {

    private HtmlFormatter() {}

    /** The most blank lines {@link #format} keeps in a row between two blocks. */
    static final int MAX_BLANK_LINES = 1;

    /** What was last written on the current line. */
    private enum Last {
        NOTHING, // start of the output
        BLOCK_OPEN, // a block's start tag: inline content may follow on its line
        INLINE, // text or a phrasing element: more may follow
        CLOSED // a block's end tag, a void block, a comment or declaration: the line is done
    }

    /**
     * {@code html} (an HTTP response body) with one block element per line, indented two spaces per level; unchanged
     * when that is not safe to do, when the body is already indented, or when it is longer than {@code maxChars}.
     */
    public static String indentResponse(@Nullable String html, int maxChars) {
        if (html == null) {
            return "";
        }
        if (html.length() > maxChars) {
            return html;
        }
        String s = html.strip();
        if (!s.startsWith("<")) {
            return html; // not markup
        }
        try {
            String out = new Writer(s, "  ", false).run();
            return out == null ? html : out;
        } catch (RuntimeException e) {
            return html; // a lenient formatter never costs the user the body (TooIrregular included)
        }
    }

    /**
     * {@code text} (an HTML document) re-laid out with {@code indent} per level, or null when a construct is left
     * open, so it is not safe to format. A final line break is kept.
     */
    public static @Nullable String format(String text, String indent) {
        String s = text.strip();
        if (s.isEmpty()) {
            return text;
        }
        try {
            String out = new Writer(s, indent.isEmpty() ? "  " : indent, true).run();
            if (out == null) {
                return null;
            }
            return text.endsWith("\n") ? out + "\n" : out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Thrown when the formatted text would grow out of proportion to the body. */
    private static final class TooIrregular extends RuntimeException {
        TooIrregular() {
            super(null, null, false, false);
        }
    }

    /** One formatting pass over a stripped text. */
    private static final class Writer {
        private final String s;
        private final String indent;
        /** The editor's Format Document: line breaks around text and blank lines kept, nothing already indented. */
        private final boolean document;

        private final StringBuilder out;
        private final List<String> open = new ArrayList<>(); // lower-cased names of the open elements
        private Last last = Last.NOTHING;
        private int lineOwner = -1; // index in `open` of the block whose start tag began this line, or -1
        private String pendingSpace = ""; // whitespace inside an inline run, written only if the run goes on
        /** The source had a line break before what comes next ({@link #document} only). */
        private boolean breakPending;
        /** Blank lines the source had before what comes next ({@link #document} only). */
        private int blankLines;

        private final int limit;

        Writer(String s, String indent, boolean document) {
            this.s = s;
            this.indent = indent;
            this.document = document;
            this.limit = 3 * s.length() + 4096 + (document ? s.length() * indent.length() : 0);
            this.out = new StringBuilder(s.length() + s.length() / 4);
        }

        /** The formatted text, or null when a construct is left open. */
        @Nullable
        String run() {
            int n = s.length();
            int i = 0;
            while (i < n) {
                int lt = nextTag(i);
                if (lt != i) {
                    int end = lt < 0 ? n : lt;
                    if (!document && lt >= 0 && indentedLine(i, end)) {
                        return null; // the server already formatted the page (a <pre>'s own lines do not count)
                    }
                    text(s.substring(i, end));
                    i = end;
                    continue;
                }
                int end = tagEnd(i);
                if (end < 0) {
                    return null;
                }
                String tag = s.substring(i, end);
                if (tag.startsWith("<!") || tag.startsWith("<?")) {
                    ownLine(tag); // comment, doctype, CDATA, processing instruction
                    i = end;
                    continue;
                }
                boolean closing = tag.startsWith("</");
                String name = nameOf(tag, closing ? 2 : 1);
                if (closing) {
                    closeTag(tag, name);
                    i = end;
                    continue;
                }
                openTag(tag, name);
                i = end;
                if (HtmlElements.VERBATIM.contains(name) && !tag.endsWith("/>")) {
                    if ("plaintext".equals(name)) {
                        out.append(s, i, n); // everything after <plaintext> is text, to the end
                        return out.toString();
                    }
                    int close = rawEnd(i, name);
                    if (close < 0) {
                        return null; // a <script> or <pre> that never ends: leave the text alone
                    }
                    int closeEnd = tagEnd(close);
                    if (closeEnd < 0) {
                        return null;
                    }
                    out.append(s, i, close); // the element's content, untouched
                    open.removeLast(); // pushed by openTag
                    out.append(s, close, closeEnd);
                    if (lineOwner >= open.size()) {
                        lineOwner = -1;
                    }
                    last = HtmlElements.PHRASING.contains(name) ? Last.INLINE : Last.CLOSED;
                    i = closeEnd;
                }
            }
            return out.toString();
        }

        /** Whether the text in {@code [from, to)}, which a tag follows, ends in a line break and indentation. */
        private boolean indentedLine(int from, int to) {
            int k = to;
            while (k > from && (s.charAt(k - 1) == ' ' || s.charAt(k - 1) == '\t')) {
                k--;
            }
            return k < to && k > from && s.charAt(k - 1) == '\n';
        }

        /** The index of the next {@code <} that starts markup, at or after {@code from}, or -1. */
        private int nextTag(int from) {
            int k = from;
            while (true) {
                int lt = s.indexOf('<', k);
                if (lt < 0 || lt + 1 >= s.length()) {
                    return -1; // a trailing "<" is text
                }
                char c = s.charAt(lt + 1);
                if (Character.isLetter(c) || c == '!' || c == '?') {
                    return lt;
                }
                if (c == '/' && lt + 2 < s.length() && Character.isLetter(s.charAt(lt + 2))) {
                    return lt;
                }
                k = lt + 1; // "a < b", "</ >": text
            }
        }

        /** The index just past the construct at {@code at} (a tag, comment, CDATA or declaration), or -1. */
        private int tagEnd(int at) {
            if (s.startsWith("<!--", at)) {
                int e = s.indexOf("-->", at + 4);
                return e < 0 ? -1 : e + 3;
            }
            if (s.startsWith("<![CDATA[", at)) {
                int e = s.indexOf("]]>", at + 9);
                return e < 0 ? -1 : e + 3;
            }
            if (s.startsWith("<?", at)) {
                int e = s.indexOf("?>", at + 2);
                int gt = s.indexOf('>', at);
                return e >= 0 ? e + 2 : gt < 0 ? -1 : gt + 1;
            }
            if (s.startsWith("<!", at)) {
                int e = s.indexOf('>', at);
                return e < 0 ? -1 : e + 1;
            }
            char quote = 0;
            for (int k = at + 1; k < s.length(); k++) {
                char c = s.charAt(k);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if ((c == '"' || c == '\'') && afterEq(k)) {
                    quote = c; // a quoted attribute value: a ">" inside it does not end the tag
                } else if (c == '>') {
                    return k + 1;
                }
            }
            return -1;
        }

        /** Whether the quote at {@code k} opens an attribute value ({@code =} before it, maybe with spaces). */
        private boolean afterEq(int k) {
            int j = k - 1;
            while (j > 0 && Character.isWhitespace(s.charAt(j))) {
                j--;
            }
            return s.charAt(j) == '=';
        }

        /** The start of the end tag {@code </name} that closes a raw element whose content starts at {@code from}. */
        private int rawEnd(int from, String name) {
            int k = from;
            while (true) {
                int e = s.indexOf("</", k);
                if (e < 0) {
                    return -1;
                }
                int after = e + 2 + name.length();
                if (after < s.length() && s.regionMatches(true, e + 2, name, 0, name.length())) {
                    char c = s.charAt(after);
                    if (c == '>' || c == '/' || Character.isWhitespace(c)) {
                        return e;
                    }
                }
                k = e + 2; // "</scripts", "</b>" inside a <pre>: not the end
            }
        }

        private static String nameOf(String tag, int from) {
            int k = from;
            while (k < tag.length()) {
                char c = tag.charAt(k);
                if (!(Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '_' || c == '.')) {
                    break;
                }
                k++;
            }
            return tag.substring(from, k).toLowerCase(Locale.ROOT);
        }

        private void text(String text) {
            int a = 0;
            int b = text.length();
            while (a < b && Character.isWhitespace(text.charAt(a))) {
                a++;
            }
            while (b > a && Character.isWhitespace(text.charAt(b - 1))) {
                b--;
            }
            if (a == b) {
                if (document) {
                    noteBreaks(text);
                }
                if (last == Last.INLINE) {
                    pendingSpace += text; // between two inline pieces it matters: "<b>a</b> <i>b</i>"
                }
                return;
            }
            if (document) {
                noteBreaks(text.substring(0, a));
            }
            if (last == Last.INLINE) {
                pendingSpace += text.substring(0, a);
            }
            inline(document ? reindentText(text.substring(a, b)) : text.substring(a, b));
            pendingSpace = text.substring(b);
            if (document) {
                noteBreaks(pendingSpace);
            }
        }

        /** Records the line breaks and blank lines of whitespace the source had between two pieces. */
        private void noteBreaks(String whitespace) {
            int newlines = 0;
            for (int k = 0; k < whitespace.length(); k++) {
                if (whitespace.charAt(k) == '\n') {
                    newlines++;
                }
            }
            if (newlines > 0) {
                breakPending = true;
                blankLines = Math.max(blankLines, Math.min(MAX_BLANK_LINES, newlines - 1));
            }
        }

        /** A text run whose own line breaks are kept, each following line indented to the run's depth. */
        private String reindentText(String piece) {
            if (piece.indexOf('\n') < 0) {
                return piece;
            }
            String pad = indent.repeat(open.size());
            String[] lines = piece.split("\n", -1);
            StringBuilder b = new StringBuilder(lines[0].stripTrailing());
            for (int k = 1; k < lines.length; k++) {
                String line = lines[k].strip();
                b.append('\n');
                if (!line.isEmpty()) {
                    b.append(pad).append(line);
                }
            }
            return b.toString();
        }

        /** Appends text or a phrasing element to the current line, or starts a line for it. */
        private void inline(String piece) {
            if (breakPending && last != Last.NOTHING) {
                newline(open.size()); // the author broke the line here: so does the formatted text
                lineOwner = -1;
            } else if (last == Last.INLINE) {
                out.append(pendingSpace);
            } else if (last != Last.BLOCK_OPEN) {
                newline(open.size());
                lineOwner = -1;
            }
            clearBreak();
            pendingSpace = "";
            out.append(piece);
            last = Last.INLINE;
        }

        private void ownLine(String construct) {
            newline(open.size());
            out.append(construct);
            lineOwner = -1;
            last = Last.CLOSED;
        }

        private void openTag(String tag, String name) {
            Set<String> ends = HtmlElements.impliedEnds(name);
            if (ends != null) {
                while (!open.isEmpty() && ends.contains(open.getLast())) {
                    pop();
                }
            }
            boolean isVoid = HtmlElements.VOID.contains(name) || tag.endsWith("/>");
            if (HtmlElements.PHRASING.contains(name)) {
                inline(tag);
            } else {
                newline(open.size());
                out.append(tag);
                pendingSpace = "";
                lineOwner = isVoid ? -1 : open.size();
                last = isVoid ? Last.CLOSED : Last.BLOCK_OPEN;
            }
            if (!isVoid) {
                open.add(name);
            }
        }

        private void closeTag(String tag, String name) {
            int at = open.lastIndexOf(name);
            boolean ownsLine = at >= 0 && at == lineOwner; // read before the pops below clear it
            if (at >= 0) {
                while (open.size() > at) {
                    pop(); // closes what it holds too: "<ul><li>a</ul>"
                }
            }
            if (HtmlElements.PHRASING.contains(name)) {
                inline(tag);
                return;
            }
            if (ownsLine && !breakPending && (last == Last.INLINE || last == Last.BLOCK_OPEN)) {
                out.append(tag); // "<h1>Not Found</h1>" stays on one line
            } else {
                newline(open.size());
                out.append(tag);
            }
            pendingSpace = "";
            lineOwner = -1;
            last = Last.CLOSED;
        }

        private void pop() {
            open.removeLast();
            if (lineOwner >= open.size()) {
                lineOwner = -1;
            }
        }

        private void clearBreak() {
            breakPending = false;
            blankLines = 0;
        }

        private void newline(int depth) {
            if (out.length() > limit) {
                throw new TooIrregular(); // thousands of unclosed elements: the indentation would dwarf the text
            }
            pendingSpace = "";
            if (!out.isEmpty()) {
                out.append('\n');
                for (int k = 0; k < blankLines; k++) {
                    out.append('\n');
                }
            }
            clearBreak();
            out.append(indent.repeat(depth));
        }
    }
}
