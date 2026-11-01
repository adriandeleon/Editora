package com.editora.editops;

import java.util.Locale;

import com.editora.text.HtmlElements;
import org.jspecify.annotations.Nullable;

/**
 * Where a caret is in an HTML document, from the text before it: in element content, inside a tag (among its
 * attributes), in a comment, or in the CSS of a {@code <style>} or the JavaScript of a {@code <script>}. Local
 * snippets are chosen by it: the buffer's language is {@code html} throughout, and HTML element snippets offered
 * everywhere put {@code <col>} into a CSS rule ({@code p { col}), {@code <var></var>} into a script and {@code
 * <hr>} into a start tag ({@code <a hr}) — preselected above the server's own item (HTML review B5/C4).
 *
 * <p>One forward pass over a bounded window, like {@link TagAutoClose}: quotes count only inside a tag, a {@code
 * <script>}/{@code <style>} (and {@code <textarea>}/{@code <title>}) holds no markup until its own end tag, and a
 * comment none until {@code -->}. Pure; no toolkit dependency.
 */
public final class HtmlCaretRegion {

    /** How far back from the caret the scan starts: a page's inline script is rarely longer. */
    public static final int MAX_SCAN = 20_000;

    /** What the caret is in. */
    public enum Region {
        CONTENT,
        TAG,
        COMMENT,
        STYLE,
        SCRIPT
    }

    private HtmlCaretRegion() {}

    /** How far before the window {@link #before} looks for the start tag of a script or style the window is in. */
    public static final int MAX_LOOK_BACK = 100_000;

    /** Read access to a document: its text in {@code [from, to)}. */
    @FunctionalInterface
    public interface Text {
        String text(int from, int to);
    }

    /**
     * The text before {@code caret} the region scans read: the {@link #MAX_SCAN} characters before it, headed by
     * the start tag of the {@code <script>}, {@code <style>} (or other raw-text element) that window begins inside.
     * Read from the middle of a long inline script, the window looked like markup: its {@code i<n} opened a tag, so
     * {@code total=} got quotes and HTML snippets were offered in the code (HTML review 2 E112). Whether it begins
     * inside one is read backwards from the window's start (up to {@link #MAX_LOOK_BACK} characters): the last start
     * or end tag of a raw-text element there, outside comments, is a start tag.
     */
    public static String before(Text doc, int caret) {
        int start = Math.max(0, caret - MAX_SCAN);
        String window = doc.text(start, caret);
        String open = start == 0 ? null : rawOpenBefore(doc, start, caret);
        return open == null ? window : "<" + open + ">" + window;
    }

    /**
     * The raw-text element whose start tag is the last such tag starting before {@code at}, else null (see {@link
     * #before}); the text up to {@code end} may be read for a name that runs past {@code at}.
     */
    static @Nullable String rawOpenBefore(Text doc, int at, int end) {
        for (int hi = at; hi > 0 && at - hi < MAX_LOOK_BACK; ) {
            int lo = Math.max(0, hi - MAX_SCAN);
            // A little past `hi`, so the name of a tag whose '<' is just before it is read whole.
            String chunk = doc.text(lo, Math.min(end, hi + 16)).toLowerCase(Locale.ROOT);
            for (int k = chunk.lastIndexOf('<', hi - lo - 1); k >= 0; k = k == 0 ? -1 : chunk.lastIndexOf('<', k - 1)) {
                boolean closing = k + 1 < chunk.length() && chunk.charAt(k + 1) == '/';
                int nameStart = k + (closing ? 2 : 1);
                int nameEnd = nameStart;
                while (nameEnd < chunk.length() && isNameChar(chunk.charAt(nameEnd))) {
                    nameEnd++;
                }
                if (!HtmlElements.isRawText(chunk.substring(nameStart, nameEnd))) {
                    continue;
                }
                int comment = chunk.lastIndexOf("<!--", k);
                if (comment >= 0 && chunk.lastIndexOf("-->", k) < comment) {
                    continue; // a tag mentioned in a comment
                }
                return closing ? null : chunk.substring(nameStart, nameEnd);
            }
            hi = lo;
        }
        return null;
    }

    /** The region at the end of {@code before} (the text preceding the caret, at most {@link #MAX_SCAN} chars). */
    public static Region at(String before) {
        return scan(before).region();
    }

    /** Where a scan ended: the region, the open tag's {@code <} (or -1) and the quote it is inside (or 0). */
    private record Scan(Region region, int tagStart, char quote) {}

    private static Scan scan(String before) {
        int n = before.length();
        boolean inTag = false;
        int tagStart = -1;
        char quote = 0;
        @Nullable String raw = null;
        for (int k = 0; k < n; k++) {
            char c = before.charAt(k);
            if (raw != null) {
                if (c == '<' && before.regionMatches(true, k + 1, "/" + raw, 0, raw.length() + 1)) {
                    raw = null;
                    inTag = true;
                    tagStart = k;
                }
                continue;
            }
            if (!inTag) {
                if (c == '<' && before.startsWith("<!--", k)) {
                    int end = before.indexOf("-->", k + 4);
                    if (end < 0) {
                        return new Scan(Region.COMMENT, -1, (char) 0);
                    }
                    k = end + 2;
                } else if (c == '<' && k + 1 < n && startsTag(before.charAt(k + 1))) {
                    inTag = true;
                    tagStart = k;
                    quote = 0;
                }
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                inTag = false;
                if (before.charAt(k - 1) != '/') {
                    raw = rawTextElement(before, tagStart);
                }
            }
        }
        if (inTag) {
            return new Scan(Region.TAG, tagStart, quote);
        }
        Region region = "style".equals(raw) ? Region.STYLE : "script".equals(raw) ? Region.SCRIPT : Region.CONTENT;
        return new Scan(region, -1, (char) 0);
    }

    /**
     * Whether an {@code =} typed at the caret ends an attribute name, so its value's quotes go in with it (VS Code's
     * {@code html.autoCreateQuotes}; HTML review D10): the caret is in a start tag, outside any quoted value, right
     * after a name that follows a space, and no quote or {@code =} is already next ({@code after}, the text after
     * the caret), and not inside a template tag. {@code before} is the text before the caret, as {@link #before}
     * reads it.
     */
    public static boolean quotesAfterEquals(String before, String after) {
        Scan s = scan(before);
        if (s.region() != Region.TAG || s.quote() != 0 || !Character.isLetter(before.charAt(s.tagStart() + 1))) {
            return false; // not in a start tag (an end tag, a doctype), or inside a value already
        }
        if (inTemplateTag(before, s.tagStart())) {
            return false; // `<option {% if a==b %}`: the template's code, not an attribute (HTML review 2 C104)
        }
        int i = before.length();
        while (i > 0 && isAttributeNameChar(before.charAt(i - 1))) {
            i--;
        }
        if (i == before.length() || !Character.isWhitespace(before.charAt(i - 1))) {
            return false; // no name right before the caret, or it is the element's own name
        }
        char next = after.isEmpty() ? 0 : after.charAt(0);
        return next != '"' && next != '\'' && next != '=';
    }

    /**
     * Whether the end of {@code before} is inside a template tag opened at or after {@code from}: {@code {% … %}},
     * {@code {{ … }}} or {@code <% … %>} (Jinja, Django, Twig, Liquid, Handlebars, ERB, EJS) not yet closed.
     */
    static boolean inTemplateTag(String before, int from) {
        return unclosed(before, from, "{%", "%}")
                || unclosed(before, from, "{{", "}}")
                || unclosed(before, from, "<%", "%>");
    }

    private static boolean unclosed(String s, int from, String open, String close) {
        int at = s.lastIndexOf(open);
        return at >= from && s.indexOf(close, at + open.length()) < 0;
    }

    private static boolean isAttributeNameChar(char c) {
        return isNameChar(c) || c == '@';
    }

    /**
     * The language whose local snippets suit the caret of a {@code language} buffer whose text before the caret is
     * {@code before}: {@code css} in a {@code <style>}, {@code javascript} in a {@code <script>}, none (null) inside
     * a tag, and {@code language} itself everywhere else — and for every buffer that is not HTML.
     */
    public static @Nullable String snippetLanguage(String language, String before) {
        if (!"html".equals(language)) {
            return language;
        }
        return switch (at(before)) {
            case STYLE -> "css";
            case SCRIPT -> "javascript";
            case TAG -> null;
            case CONTENT, COMMENT -> language;
        };
    }

    /**
     * Whether the caret sits in the name of an end tag that is already whole: {@code before} ends in {@code </} and
     * part of a name, and {@code after} goes on with the rest of the name and its {@code >}. That is where a closer
     * typed by hand joins the one Editora inserted ({@code <p>a</|p>}); the server, asked there, counts {@code </p>}
     * as closed and offers the next open ancestor, so the Enter that follows renamed it to {@code </body>} (HTML
     * review C6). Works for XML as well.
     */
    public static boolean inWholeEndTag(String before, String after) {
        int i = before.length();
        while (i > 0 && isNameChar(before.charAt(i - 1))) {
            i--;
        }
        if (i < 2 || before.charAt(i - 1) != '/' || before.charAt(i - 2) != '<') {
            return false;
        }
        int j = 0;
        while (j < after.length() && isNameChar(after.charAt(j))) {
            j++;
        }
        boolean named = j > 0 || i < before.length();
        return named && j < after.length() && after.charAt(j) == '>';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':';
    }

    private static boolean startsTag(char c) {
        return Character.isLetter(c) || c == '/' || c == '!' || c == '?';
    }

    /** The raw-text element the start tag at {@code lt} opens ({@code script}, {@code style}, …), else null. */
    private static @Nullable String rawTextElement(String s, int lt) {
        int i = lt + 1;
        int start = i;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '-')) {
            i++;
        }
        String name = s.substring(start, i).toLowerCase(Locale.ROOT);
        return HtmlElements.isRawText(name) ? name : null;
    }
}
