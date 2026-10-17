package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Indents an HTML response body for the HTTP Client's response view, the way {@link HttpXmlIndent} does XML. A
 * lenient lexical pass, not a parser or a sanitizer: one block element per line, indented by nesting depth; a run of
 * text and inline elements ({@code <b>}, {@code <a>}, {@code <span>}…) stays on one line, beside the block tag that
 * holds it. The contents of {@code <pre>}, {@code <textarea>}, {@code <script>} and {@code <style>}, comments, and
 * the text itself are copied exactly; only the whitespace <em>between</em> tags changes. Void elements
 * ({@code <br>}, {@code <img>}, {@code <meta>}…) and declarations ({@code <!doctype>}) open no level, and the usual
 * omitted end tags ({@code <li>}, {@code <p>}, {@code <td>}…) close themselves.
 *
 * <p>The body is returned unchanged when it does not start with a tag, when the server already indented it (a line
 * break and indentation before a tag, outside {@code <pre>} and its kin), when it
 * is larger than {@link #MAX_CHARS}, or when a construct is left open (a tag, comment or {@code <script>} without its
 * end — e.g. a view clipped mid-tag). Pure — tested by {@code HttpHtmlIndentTest}.
 */
final class HttpHtmlIndent {

    private HttpHtmlIndent() {}

    /** The largest body indented: the response view's own limit, which is also all {@link HttpXmlIndent} ever sees. */
    static final int MAX_CHARS = HttpClientPanel.MAX_BODY_CHARS;

    private static final String INDENT = "  ";

    // spotless:off
    private static final Set<String> VOID = Set.of(
            "area", "base", "basefont", "bgsound", "br", "col", "command", "embed", "frame", "hr", "img", "input",
            "isindex", "keygen", "link", "meta", "param", "source", "track", "wbr");

    /** Elements whose content is copied verbatim up to their end tag. */
    private static final Set<String> RAW = Set.of(
            "pre", "textarea", "script", "style", "xmp", "plaintext");

    /** Phrasing elements: they stay on the line of the text around them. */
    private static final Set<String> INLINE = Set.of(
            "a", "abbr", "acronym", "b", "bdi", "bdo", "big", "br", "button", "cite", "code", "data", "del", "dfn",
            "em", "font", "i", "img", "input", "ins", "kbd", "label", "mark", "meter", "nobr", "output", "progress",
            "q", "rp", "rt", "ruby", "s", "samp", "small", "span", "strike", "strong", "sub", "sup", "time", "tt",
            "u", "var", "wbr");

    /** Blocks that end an open {@code <p>}. */
    private static final Set<String> CLOSES_P = Set.of(
            "address", "article", "aside", "blockquote", "details", "div", "dl", "fieldset", "figcaption", "figure",
            "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "main", "menu", "nav",
            "ol", "p", "pre", "section", "table", "ul");
    // spotless:on

    private static final Set<String> P = Set.of("p");
    private static final Set<String> LI = Set.of("li", "p");
    private static final Set<String> DT_DD = Set.of("dt", "dd", "p");
    private static final Set<String> CELL = Set.of("td", "th", "p");
    private static final Set<String> ROW = Set.of("tr", "td", "th", "p");
    private static final Set<String> SECTION = Set.of("thead", "tbody", "tfoot", "tr", "td", "th", "p");
    private static final Set<String> OPTION = Set.of("option");
    private static final Set<String> OPTGROUP = Set.of("optgroup", "option");
    private static final Set<String> HEAD = Set.of("head");

    /** The open elements an opening {@code tag} ends when one of them is innermost (an omitted end tag), or null. */
    private static Set<String> autoCloses(String tag) {
        return switch (tag) {
            case "li" -> LI;
            case "dt", "dd" -> DT_DD;
            case "td", "th" -> CELL;
            case "tr" -> ROW;
            case "thead", "tbody", "tfoot" -> SECTION;
            case "option" -> OPTION;
            case "optgroup" -> OPTGROUP;
            case "body" -> HEAD;
            default -> CLOSES_P.contains(tag) ? P : null;
        };
    }

    /** What was last written on the current line. */
    private enum Last {
        NOTHING, // start of the output
        BLOCK_OPEN, // a block's start tag: inline content may follow on its line
        INLINE, // text or a phrasing element: more may follow
        CLOSED // a block's end tag, a void block, a comment or declaration: the line is done
    }

    /** {@code html} with one block element per line, indented by depth; unchanged when that is not safe to do. */
    static String indent(String html) {
        if (html == null) {
            return "";
        }
        if (html.length() > MAX_CHARS) {
            return html;
        }
        String s = html.strip();
        if (!s.startsWith("<")) {
            return html; // not markup
        }
        try {
            String out = new HttpHtmlIndent.Writer(s).run();
            return out == null ? html : out;
        } catch (RuntimeException e) {
            return html; // a lenient formatter never costs the user the body (TooIrregular included)
        }
    }

    /** Thrown when the formatted text would grow out of proportion to the body. */
    private static final class TooIrregular extends RuntimeException {
        TooIrregular() {
            super(null, null, false, false);
        }
    }

    /** One formatting pass over a stripped body. */
    private static final class Writer {
        private final String s;
        private final StringBuilder out;
        private final List<String> open = new ArrayList<>(); // lower-cased names of the open elements
        private Last last = Last.NOTHING;
        private int lineOwner = -1; // index in `open` of the block whose start tag began this line, or -1
        private String pendingSpace = ""; // whitespace inside an inline run, written only if the run goes on

        private final int limit;

        Writer(String s) {
            this.s = s;
            this.limit = 3 * s.length() + 4096;
            this.out = new StringBuilder(s.length() + s.length() / 4);
        }

        /** The formatted text, or null when a construct is left open. */
        String run() {
            int n = s.length();
            int i = 0;
            while (i < n) {
                int lt = nextTag(i);
                if (lt != i) {
                    int end = lt < 0 ? n : lt;
                    if (lt >= 0 && indentedLine(i, end)) {
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
                if (RAW.contains(name) && !tag.endsWith("/>")) {
                    if ("plaintext".equals(name)) {
                        out.append(s, i, n); // everything after <plaintext> is text, to the end
                        return out.toString();
                    }
                    int close = rawEnd(i, name);
                    if (close < 0) {
                        return null; // a <script> or <pre> that never ends: leave the body alone
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
                    last = INLINE.contains(name) ? Last.INLINE : Last.CLOSED;
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
            if (s.startsWith("<!", at) || s.startsWith("<?", at)) {
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
                if (last == Last.INLINE) {
                    pendingSpace += text; // between two inline pieces it matters: "<b>a</b> <i>b</i>"
                }
                return;
            }
            if (last == Last.INLINE) {
                pendingSpace += text.substring(0, a);
            }
            inline(text.substring(a, b));
            pendingSpace = text.substring(b);
        }

        /** Appends text or a phrasing element to the current line, or starts a line for it. */
        private void inline(String piece) {
            if (last == Last.INLINE) {
                out.append(pendingSpace);
            } else if (last != Last.BLOCK_OPEN) {
                newline(open.size());
                lineOwner = -1;
            }
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
            Set<String> ends = autoCloses(name);
            if (ends != null) {
                while (!open.isEmpty() && ends.contains(open.getLast())) {
                    pop();
                }
            }
            boolean isVoid = VOID.contains(name) || tag.endsWith("/>");
            if (INLINE.contains(name)) {
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
            if (INLINE.contains(name)) {
                inline(tag);
                return;
            }
            if (ownsLine && (last == Last.INLINE || last == Last.BLOCK_OPEN)) {
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

        private void newline(int depth) {
            if (out.length() > limit) {
                throw new TooIrregular(); // thousands of unclosed elements: the indentation would dwarf the body
            }
            pendingSpace = "";
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(INDENT.repeat(depth));
        }
    }
}
