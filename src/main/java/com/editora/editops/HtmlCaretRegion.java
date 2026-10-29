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
     * the caret). {@code before} is the text before the caret, at most {@link #MAX_SCAN} chars.
     */
    public static boolean quotesAfterEquals(String before, String after) {
        Scan s = scan(before);
        if (s.region() != Region.TAG || s.quote() != 0 || !Character.isLetter(before.charAt(s.tagStart() + 1))) {
            return false; // not in a start tag (an end tag, a doctype), or inside a value already
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
