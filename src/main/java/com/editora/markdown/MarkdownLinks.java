package com.editora.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The link under a caret as something to edit: where it is, its text and where it points — and the edits
 * the link tools make of it (retarget, remove, inline ⇄ reference). Pure.
 *
 * <p>Two forms are understood: the inline {@code [text](destination "title")} (destination bare or in
 * {@code <…>}, parentheses balanced) and the reference {@code [text][label]} / {@code [text][]} /
 * {@code [label]} with its {@code [label]: destination} definition somewhere in the document. Images
 * ({@code ![…](…)}) are links here too; their {@code !} is kept.
 */
public final class MarkdownLinks {

    /**
     * A link in a document.
     *
     * @param start offset of the {@code [} (or of the {@code !} of an image)
     * @param end offset just past the link
     * @param text the link text as written (inline markup included)
     * @param destination where it points; for a reference link, its definition's destination ("" when the
     *     label is not defined)
     * @param label the reference label, or null for an inline link
     * @param image whether it starts with {@code !}
     */
    public record Link(int start, int end, String text, String destination, String label, boolean image) {
        public boolean isReference() {
            return label != null;
        }
    }

    private static final Pattern DEFINITION =
            Pattern.compile("(?m)^ {0,3}\\[([^\\]\\n]+)\\]:[ \\t]*(<[^>\\n]*>|\\S+)?[^\\n]*$");

    private MarkdownLinks() {}

    /** The link whose span contains {@code caret} (ends included), or null. */
    public static Link at(String text, int caret) {
        int ls = text.lastIndexOf('\n', caret - 1) + 1;
        int le = text.indexOf('\n', caret);
        le = le < 0 ? text.length() : le;
        for (int i = ls; i < le; i++) {
            if (text.charAt(i) != '[' || (i > 0 && text.charAt(i - 1) == '\\')) {
                continue;
            }
            int close = matching(text, i, le, '[', ']');
            if (close < 0) {
                continue;
            }
            boolean image = i > ls && text.charAt(i - 1) == '!';
            int start = image ? i - 1 : i;
            String linkText = text.substring(i + 1, close);
            Link link = null;
            if (close + 1 < le && text.charAt(close + 1) == '(') {
                int paren = matching(text, close + 1, le, '(', ')');
                if (paren > 0) {
                    link = new Link(
                            start, paren + 1, linkText, destinationOf(text.substring(close + 2, paren)), null, image);
                }
            } else if (close + 1 < le && text.charAt(close + 1) == '[') {
                int second = matching(text, close + 1, le, '[', ']');
                if (second > 0) {
                    String label = text.substring(close + 2, second);
                    label = label.isEmpty() ? linkText : label;
                    link = new Link(start, second + 1, linkText, definition(text, label), label, image);
                }
            } else if (definitionMatch(text, linkText) != null && !isDefinitionLine(text, ls, i)) {
                link = new Link(start, close + 1, linkText, definition(text, linkText), linkText, image);
            }
            if (link != null) {
                if (caret >= link.start() && caret <= link.end()) {
                    return link;
                }
                i = link.end() - 1;
            }
        }
        return null;
    }

    private static boolean isDefinitionLine(String text, int ls, int bracket) {
        return text.substring(ls, bracket).isBlank()
                && DEFINITION.matcher(text.substring(ls)).lookingAt();
    }

    /** The destination out of the inside of {@code (…)}: without its {@code <>} and without a title. */
    private static String destinationOf(String inside) {
        String s = inside.strip();
        if (s.startsWith("<")) {
            int close = s.indexOf('>');
            return close < 0 ? s : s.substring(1, close);
        }
        int space = 0;
        while (space < s.length() && !Character.isWhitespace(s.charAt(space))) {
            space++;
        }
        return s.substring(0, space);
    }

    private static int matching(String s, int open, int limit, char left, char right) {
        int depth = 0;
        for (int i = open; i < limit; i++) {
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

    private static Matcher definitionMatch(String text, String label) {
        Matcher m = DEFINITION.matcher(text);
        while (m.find()) {
            if (normal(m.group(1)).equals(normal(label))) {
                return m;
            }
        }
        return null;
    }

    private static String normal(String label) {
        return label.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /** The destination {@code label} is defined to point at, or "" when it has no definition. */
    public static String definition(String text, String label) {
        Matcher m = definitionMatch(text, label);
        if (m == null || m.group(2) == null) {
            return "";
        }
        String d = m.group(2);
        return d.startsWith("<") && d.endsWith(">") ? d.substring(1, d.length() - 1) : d;
    }

    /** {@code [text](destination)} — the destination in {@code <…>} when it needs it. */
    public static String inline(String linkText, String destination, boolean image) {
        return (image ? "!" : "")
                + "["
                + MarkdownInline.escapeUnbalancedBrackets(linkText)
                + "]("
                + (destination.isEmpty() ? "" : MarkdownImagePaste.destination(destination))
                + ")";
    }

    /**
     * Makes {@code [selStart, selEnd)} — an existing link when {@code link} is given — a link with
     * {@code linkText} pointing at {@code destination}. A reference link keeps its form: its text changes in
     * place and its definition is pointed at the new destination. The caret ends behind the link.
     */
    public static MarkdownEdit set(
            String text, Link link, int selStart, int selEnd, String linkText, String destination) {
        String dest = destination == null ? "" : destination.strip();
        if (link == null) {
            String made = inline(linkText, dest, false);
            return new MarkdownEdit(selStart, selEnd, made, selStart + made.length(), selStart + made.length());
        }
        if (!link.isReference()) {
            String made = inline(linkText, dest, link.image());
            return new MarkdownEdit(
                    link.start(), link.end(), made, link.start() + made.length(), link.start() + made.length());
        }
        // Reference: rewrite the use and its definition, which may be on either side of it.
        String use = (link.image() ? "!" : "") + "[" + MarkdownInline.escapeUnbalancedBrackets(linkText) + "]["
                + link.label() + "]";
        Matcher def = definitionMatch(text, link.label());
        String line = "[" + link.label() + "]: " + MarkdownImagePaste.destination(dest);
        if (def == null) {
            String tail = text.substring(link.end()).stripTrailing();
            String made = use + tail + "\n\n" + line;
            return new MarkdownEdit(
                    link.start(), text.length(), made, link.start() + use.length(), link.start() + use.length());
        }
        int from = Math.min(link.start(), def.start());
        int to = Math.max(link.end(), def.end());
        StringBuilder sb = new StringBuilder(text.substring(from, to));
        boolean defFirst = def.start() < link.start();
        // Later span first, so the earlier one's offsets stay valid.
        if (defFirst) {
            sb.replace(link.start() - from, link.end() - from, use);
            sb.replace(0, def.end() - def.start(), line);
        } else {
            sb.replace(def.start() - from, def.end() - from, line);
            sb.replace(0, link.end() - link.start(), use);
        }
        int caret = defFirst ? from + line.length() + (link.start() - def.end()) + use.length() : from + use.length();
        return new MarkdownEdit(from, to, sb.toString(), caret, caret);
    }

    /** Replaces {@code link} by its text (an image by its alt text); a reference's definition stays. */
    public static MarkdownEdit remove(Link link) {
        int end = link.start() + link.text().length();
        return new MarkdownEdit(link.start(), link.end(), link.text(), link.start(), end);
    }

    /**
     * Turns the inline {@code link} into a reference link: {@code [text][n]} with {@code [n]: destination}
     * after the last line, {@code n} being the label an existing definition of the same destination already
     * has, else the next unused number. Returns {@code null} for a link that is a reference already.
     */
    public static MarkdownEdit toReference(String text, Link link) {
        if (link.isReference()) {
            return null;
        }
        String label = null;
        long max = 0;
        Matcher m = DEFINITION.matcher(text);
        while (m.find()) {
            String d = m.group(2) == null ? "" : m.group(2);
            d = d.startsWith("<") && d.endsWith(">") ? d.substring(1, d.length() - 1) : d;
            if (label == null && d.equals(link.destination()) && !m.group(1).startsWith("^")) {
                label = m.group(1);
            }
            if (m.group(1).matches("\\d{1,9}")) {
                max = Math.max(max, Long.parseLong(m.group(1)));
            }
        }
        boolean defined = label != null;
        if (!defined) {
            label = String.valueOf(max + 1);
        }
        String use = (link.image() ? "!" : "") + "[" + link.text() + "][" + label + "]";
        int caret = link.start() + use.length();
        if (defined) {
            return new MarkdownEdit(link.start(), link.end(), use, caret, caret);
        }
        String tail = text.substring(link.end()).stripTrailing();
        int lastLine = tail.lastIndexOf('\n') + 1;
        boolean afterDefinition = DEFINITION.matcher(tail.substring(lastLine)).matches();
        String made = use + tail + (afterDefinition ? "\n" : "\n\n") + "[" + label + "]: "
                + MarkdownImagePaste.destination(link.destination());
        return new MarkdownEdit(link.start(), text.length(), made, caret, caret);
    }

    /**
     * Turns the reference {@code link} into an inline one; its definition goes too when no other link uses
     * it. Returns {@code null} for an inline link and for a label that is not defined.
     */
    public static MarkdownEdit toInline(String text, Link link) {
        Matcher def = link.isReference() ? definitionMatch(text, link.label()) : null;
        if (def == null) {
            return null;
        }
        String made = inline(link.text(), link.destination(), link.image());
        int caret = link.start() + made.length();
        // Is the label used anywhere else? Count the uses outside this link and outside the definition.
        String rest =
                text.substring(0, link.start()) + " ".repeat(link.end() - link.start()) + text.substring(link.end());
        rest = rest.substring(0, def.start()) + " ".repeat(def.end() - def.start()) + rest.substring(def.end());
        boolean shared = Pattern.compile("\\[" + Pattern.quote(link.label()) + "\\]", Pattern.CASE_INSENSITIVE)
                .matcher(rest)
                .find();
        if (shared) {
            return new MarkdownEdit(link.start(), link.end(), made, caret, caret);
        }
        // Drop the definition line with its line break (and the blank line it leaves at the end of the text).
        int defStart = def.start();
        int defEnd = def.end() < text.length() ? def.end() + 1 : def.end();
        if (defEnd == text.length() && defStart > 0) {
            while (defStart > 0 && text.charAt(defStart - 1) == '\n' && defStart - 1 > link.end()) {
                defStart--;
            }
        }
        if (defStart >= link.end()) {
            String between = text.substring(link.end(), defStart);
            String last = defEnd == text.length() && text.endsWith("\n") && !between.endsWith("\n") ? "\n" : "";
            return new MarkdownEdit(link.start(), defEnd, made + between + last, caret, caret);
        }
        String between = text.substring(defEnd, link.start());
        int at = defStart + between.length() + made.length();
        return new MarkdownEdit(defStart, link.end(), between + made, at, at);
    }
}
