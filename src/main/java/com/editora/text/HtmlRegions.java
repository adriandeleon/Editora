package com.editora.text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Finds the parts of an HTML page that are not HTML (HTML review C3, C10, C13, E20): comments, the content of
 * {@code <script>} and {@code <style>} elements, a PHP file's {@code <?php … ?>} blocks — and, on request, the
 * content of chosen elements ({@code <code>}, {@code <pre>}…) and the values of chosen attributes ({@code alt},
 * {@code title}…). Toggle Comment picks its comment syntax from it, Enter its indentation style, spell check what it
 * skips and what it reads.
 *
 * <p>A lenient lexical pass, not a parser: a tag's attributes are read with their quotes, comments and raw text are
 * skipped whole (a {@code <pre>} mentioned in a comment opens nothing, an {@code a<b} in a script is not a tag), and
 * an element that is never closed simply yields no region. Pure; any thread.
 */
public final class HtmlRegions {

    private HtmlRegions() {}

    /** What a region holds. */
    public enum Kind {
        /** A {@code <!-- … -->} comment, delimiters included. */
        COMMENT,
        /** The content of a JavaScript {@code <script>} (no type, a JavaScript type, or {@code module}). */
        SCRIPT,
        /** The content of a JSON {@code <script>} ({@code application/json}, {@code ld+json}, {@code importmap}). */
        DATA,
        /** The content of any other {@code <script>}, such as an HTML template ({@code text/x-template}). */
        OTHER_SCRIPT,
        /** The content of a {@code <style>}. */
        STYLE,
        /** The code of a PHP block: after {@code <?php} / {@code <?=} / {@code <?}, up to {@code ?>} or the end. */
        PHP,
        /** The content of one of the elements asked for (see {@link #scan(CharSequence, boolean, int, Set, Set)}). */
        ELEMENT,
        /** The value of one of the attributes asked for, without its quotes. */
        ATTRIBUTE
    }

    /** A region {@code [start, end)} of the text; {@code name} is the element or attribute, lower-cased. */
    public record Region(int start, int end, Kind kind, String name) {
        public boolean contains(int offset) {
            return offset >= start && offset < end;
        }
    }

    /**
     * The comments, scripts, styles (and, for {@code php}, PHP blocks) of {@code text} that start before {@code limit}
     * (a region may run past it), in text order.
     */
    public static List<Region> scan(CharSequence text, boolean php, int limit) {
        return scan(text, php, limit, Set.of(), Set.of());
    }

    /**
     * As {@link #scan(CharSequence, boolean, int)}, plus the content of the elements named in {@code elements} and
     * the values of the attributes named in {@code attributes} (lower-case names). An element region is reported when
     * its end tag is seen, so regions are in text order only per kind.
     */
    public static List<Region> scan(
            CharSequence text, boolean php, int limit, Set<String> elements, Set<String> attributes) {
        List<Region> out = new ArrayList<>();
        Deque<Integer> open = new ArrayDeque<>(); // where the content of each open one of `elements` starts
        List<String> openNames = new ArrayList<>();
        int n = text.length();
        int stop = Math.min(n, Math.max(0, limit));
        int i = 0;
        while (i < stop) {
            char c = text.charAt(i);
            if (c != '<') {
                i++;
                continue;
            }
            if (startsWith(text, i, "<!--")) {
                int end = indexOf(text, "-->", i + 4);
                end = end < 0 ? n : end + 3;
                out.add(new Region(i, end, Kind.COMMENT, ""));
                i = end;
            } else if (startsWith(text, i, "<?")) {
                int end = indexOf(text, "?>", i + 2);
                if (php) {
                    out.add(new Region(phpContentStart(text, i), end < 0 ? n : end, Kind.PHP, "php"));
                }
                i = end < 0 ? n : end + 2;
            } else if (startsWith(text, i, "<![CDATA[")) {
                int end = indexOf(text, "]]>", i + 9);
                i = end < 0 ? n : end + 3;
            } else if (startsWith(text, i, "<!")) {
                int end = indexOf(text, ">", i + 2);
                i = end < 0 ? n : end + 1;
            } else if (i + 2 < n && text.charAt(i + 1) == '/' && Character.isLetter(text.charAt(i + 2))) {
                int nameEnd = nameEnd(text, i + 2);
                String name = lower(text, i + 2, nameEnd);
                int end = tagEnd(text, nameEnd, php, null, null, out);
                int at = openNames.lastIndexOf(name);
                if (at >= 0) {
                    int contentStart;
                    do {
                        openNames.removeLast(); // and whatever was left open inside it
                        contentStart = open.pop();
                    } while (openNames.size() > at);
                    out.add(new Region(contentStart, i, Kind.ELEMENT, name));
                }
                i = end;
            } else if (i + 1 < n && Character.isLetter(text.charAt(i + 1))) {
                int nameEnd = nameEnd(text, i + 1);
                String name = lower(text, i + 1, nameEnd);
                String[] type = name.equals("script") ? new String[1] : null;
                int end = tagEnd(text, nameEnd, php, attributes, type, out);
                boolean selfClosing = end >= 2 && text.charAt(end - 1) == '>' && text.charAt(end - 2) == '/';
                if (HtmlElements.isRawText(name) && !selfClosing) {
                    int close = closeTag(text, end, name);
                    int contentEnd = close < 0 ? n : close;
                    Kind kind = name.equals("script")
                            ? scriptKind(type == null ? null : type[0])
                            : name.equals("style") ? Kind.STYLE : elements.contains(name) ? Kind.ELEMENT : null;
                    if (kind != null) {
                        out.add(new Region(end, contentEnd, kind, name));
                    }
                    i = close < 0 ? n : tagEnd(text, nameEnd(text, close + 2), php, null, null, out);
                    continue;
                }
                if (elements.contains(name) && !selfClosing && !HtmlElements.isVoid(name)) {
                    openNames.add(name);
                    open.push(end);
                }
                i = end;
            } else {
                i++; // a '<' in text: "a < b"
            }
        }
        return out;
    }

    /** The region of {@code regions} holding {@code offset}, the innermost when they nest; null when none does. */
    public static @Nullable Region at(List<Region> regions, int offset) {
        Region found = null;
        for (Region r : regions) {
            if (r.contains(offset) && (found == null || r.end() - r.start() <= found.end() - found.start())) {
                found = r;
            }
        }
        return found;
    }

    /**
     * The language at {@code offset} of a {@code language} buffer for comments and indentation: {@code "css"} in a
     * {@code <style>}, {@code "javascript"} in a script, {@code "json"} in a JSON script, {@code "php"} in a PHP block,
     * {@code "html"} elsewhere — or {@code language} itself when it is neither HTML nor PHP.
     */
    public static String languageAt(CharSequence text, int offset, String language) {
        boolean php = "php".equals(language);
        if (!php && !HtmlElements.isHtmlLanguage(language)) {
            return language;
        }
        Region r = at(scan(text, php, offset + 1), offset);
        if (r == null || r.kind() == Kind.COMMENT) {
            return "html";
        }
        return languageOf(r.kind());
    }

    /** The language of a region's content: css, javascript, json, php or html. */
    public static String languageOf(Kind kind) {
        return switch (kind) {
            case SCRIPT -> "javascript";
            case DATA -> "json";
            case STYLE -> "css";
            case PHP -> "php";
            default -> "html";
        };
    }

    /**
     * {@code "javascript"} or {@code "css"} when {@code offset} sits in the content of a {@code <script>} or
     * {@code <style>} whose start tag is in {@code text} before it (read backwards: the slice an Enter or a typed
     * {@code }} is given), else null. With {@code contentStart} non-null, its first element is set to where that
     * content begins.
     */
    public static @Nullable String embeddedBefore(CharSequence text, int offset, int @Nullable [] contentStart) {
        int k = Math.min(offset, text.length());
        while (k > 0) {
            int lt = lastIndexOf(text, '<', k - 1);
            if (lt < 0) {
                return null;
            }
            k = lt;
            boolean closing = lt + 1 < text.length() && text.charAt(lt + 1) == '/';
            int nameStart = lt + (closing ? 2 : 1);
            String name = nameStart < text.length() && Character.isLetter(text.charAt(nameStart))
                    ? lower(text, nameStart, nameEnd(text, nameStart))
                    : "";
            if (!name.equals("script") && !name.equals("style")) {
                continue;
            }
            if (insideComment(text, lt)) {
                continue;
            }
            if (closing) {
                return null; // the last script or style before the caret is closed
            }
            String[] type = name.equals("script") ? new String[1] : null;
            int end = tagEnd(text, nameEnd(text, nameStart), false, null, type, null);
            if (end > offset || text.charAt(end - 1) != '>') {
                return null; // the caret is in the start tag itself
            }
            if (contentStart != null) {
                contentStart[0] = end;
            }
            if (name.equals("style")) {
                return "css";
            }
            return scriptKind(type == null ? null : type[0]) == Kind.SCRIPT ? "javascript" : null;
        }
        return null;
    }

    /** Whether {@code at} lies inside a {@code <!-- … -->} comment (the last opener before it is not closed). */
    private static boolean insideComment(CharSequence text, int at) {
        int open = lastIndexOf(text, "<!--", at);
        return open >= 0 && lastIndexOf(text, "-->", at) < open;
    }

    /** Where the code of the PHP block opened at {@code lt} starts: past {@code <?php}, {@code <?=} or {@code <?}. */
    private static int phpContentStart(CharSequence text, int lt) {
        int k = lt + 2;
        if (startsWith(text, k, "=")) {
            return k + 1;
        }
        if (k + 3 <= text.length() && regionMatchesIgnoreCase(text, k, "php")) {
            return k + 3;
        }
        return k;
    }

    /** The kind of a {@code <script>} by its {@code type} attribute (null: absent). */
    static Kind scriptKind(@Nullable String type) {
        if (type == null) {
            return Kind.SCRIPT;
        }
        String t = type.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()
                || t.equals("module")
                || t.contains("javascript")
                || t.contains("ecmascript")
                || t.contains("jscript")
                || t.equals("text/babel")
                || t.equals("text/jsx")) {
            return Kind.SCRIPT;
        }
        if (t.contains("json") || t.equals("importmap") || t.equals("speculationrules")) {
            return Kind.DATA;
        }
        return Kind.OTHER_SCRIPT;
    }

    /**
     * The index just past the start or end tag whose name ends at {@code from}, quotes respected; the text's length
     * when it never ends. Records the values of {@code attributes} as regions in {@code out}, and the {@code type}
     * attribute's value in {@code type[0]}. A PHP block inside the tag ({@code <a href="<?= $u ?>">}) is skipped.
     */
    private static int tagEnd(
            CharSequence text,
            int from,
            boolean php,
            @Nullable Set<String> attributes,
            String @Nullable [] type,
            @Nullable List<Region> out) {
        int n = text.length();
        int i = from;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '>') {
                return i + 1;
            }
            if (c == '<' && php && startsWith(text, i, "<?")) {
                int end = indexOf(text, "?>", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (Character.isWhitespace(c) || c == '/') {
                i++;
                continue;
            }
            int nameStart = i;
            while (i < n) {
                char d = text.charAt(i);
                if (Character.isWhitespace(d) || d == '=' || d == '>' || d == '/') {
                    break;
                }
                i++;
            }
            String attr = lower(text, nameStart, i);
            int j = i;
            while (j < n && Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            if (j >= n || text.charAt(j) != '=') {
                if (i == nameStart) {
                    i++; // a stray '=' or quote: step over it
                }
                continue;
            }
            j++;
            while (j < n && Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            if (j >= n) {
                return n;
            }
            int valueStart;
            int valueEnd;
            char q = text.charAt(j);
            if (q == '"' || q == '\'') {
                valueStart = j + 1;
                int close = indexOf(text, String.valueOf(q), valueStart);
                valueEnd = close < 0 ? n : close;
                i = close < 0 ? n : close + 1;
            } else {
                valueStart = j;
                int k = j;
                while (k < n && !Character.isWhitespace(text.charAt(k)) && text.charAt(k) != '>') {
                    k++;
                }
                valueEnd = k;
                i = k;
            }
            if (type != null && attr.equals("type")) {
                type[0] = text.subSequence(valueStart, valueEnd).toString();
            }
            if (attributes != null && out != null && attributes.contains(attr) && valueEnd > valueStart) {
                out.add(new Region(valueStart, valueEnd, Kind.ATTRIBUTE, attr));
            }
        }
        return n;
    }

    /** The start of the end tag {@code </name} at or after {@code from} (case-insensitive), or -1. */
    private static int closeTag(CharSequence text, int from, String name) {
        int k = from;
        int n = text.length();
        while (true) {
            int e = indexOf(text, "</", k);
            if (e < 0) {
                return -1;
            }
            int after = e + 2 + name.length();
            if (after <= n && regionMatchesIgnoreCase(text, e + 2, name)) {
                if (after == n || !isNameChar(text.charAt(after))) {
                    return e;
                }
            }
            k = e + 2;
        }
    }

    private static int nameEnd(CharSequence text, int from) {
        int k = from;
        while (k < text.length() && isNameChar(text.charAt(k))) {
            k++;
        }
        return k;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.';
    }

    private static String lower(CharSequence text, int from, int to) {
        return text.subSequence(from, to).toString().toLowerCase(Locale.ROOT);
    }

    private static boolean regionMatchesIgnoreCase(CharSequence text, int at, String lowerName) {
        for (int k = 0; k < lowerName.length(); k++) {
            if (Character.toLowerCase(text.charAt(at + k)) != lowerName.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(CharSequence s, int at, String prefix) {
        if (at + prefix.length() > s.length()) {
            return false;
        }
        for (int k = 0; k < prefix.length(); k++) {
            if (s.charAt(at + k) != prefix.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(CharSequence s, String needle, int from) {
        if (s instanceof String str) {
            return str.indexOf(needle, from);
        }
        int last = s.length() - needle.length();
        for (int i = Math.max(0, from); i <= last; i++) {
            if (startsWith(s, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(CharSequence s, char c, int from) {
        for (int i = Math.min(from, s.length() - 1); i >= 0; i--) {
            if (s.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(CharSequence s, String needle, int from) {
        if (s instanceof String str) {
            return str.lastIndexOf(needle, from);
        }
        for (int i = Math.min(from, s.length() - needle.length()); i >= 0; i--) {
            if (startsWith(s, i, needle)) {
                return i;
            }
        }
        return -1;
    }
}
