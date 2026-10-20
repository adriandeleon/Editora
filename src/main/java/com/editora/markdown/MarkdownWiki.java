package com.editora.markdown;

import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * The wiki link under a caret as something to edit — {@code [[note]]}, {@code [[note|label]]},
 * {@code [[note#heading]]}, {@code [[note#heading|label]]} and the embed {@code ![[image.png]]} — and its
 * conversion to an ordinary Markdown link and back. Pure; the editing side only, the parse lives with the
 * renderer.
 */
public final class MarkdownWiki {

    /**
     * A wiki link in a document.
     *
     * @param start offset of the first {@code [} (or of the {@code !} of an embed)
     * @param end offset just past the closing {@code ]]}
     * @param target the note or file it names ("" for a link to a heading of this document)
     * @param heading the heading after {@code #}, or null
     * @param label the text after {@code |}, or null
     * @param embed whether it starts with {@code !}
     * @param inTable whether it stands in a table row, where its bar is written {@code \|}
     */
    public record Link(
            int start,
            int end,
            String target,
            @Nullable String heading,
            @Nullable String label,
            boolean embed,
            boolean inTable) {

        /** What the reader sees: the label, else the target (with its heading). */
        public String shown() {
            if (label != null && !label.isBlank()) {
                return label;
            }
            return heading == null ? target : target.isEmpty() ? heading : target + " › " + heading;
        }

        /** {@code target#heading}, as it is written between the brackets. */
        public String address() {
            return heading == null ? target : target + "#" + heading;
        }
    }

    /**
     * The wiki link whose span contains {@code caret} (ends included), or null. What is and is not a wiki
     * link is what the renderer's parser ({@link MarkdownWikiLinks}) says, so that the editing commands act on
     * exactly what the preview shows as one:
     *
     * <ul>
     *   <li>the inside of the brackets is read by {@link MarkdownWikiLinks#parse};
     *   <li>never in a code span or a code block, and not when the brackets are escaped ({@code \[[note]]});
     *   <li>not directly after a letter or a digit ({@code x[[1]]} is indexing);
     *   <li>not when a destination or a label follows ({@code [[note]](url)}, {@code [[note]][ref]}): that is
     *       an ordinary link whose text is in brackets;
     *   <li>not when the document defines {@code [note]: …}: then it is that reference link, in brackets;
     *   <li>the innermost pair counts: {@code [[[a]]]} is {@code [}, the link, {@code ]};
     *   <li>in a table row the bar before the label is written {@code \|}; a bare {@code |} there ends the
     *       cell, so it is no wiki link.
     * </ul>
     */
    public static @Nullable Link at(String text, int caret) {
        int ls = text.lastIndexOf('\n', caret - 1) + 1;
        int le = text.indexOf('\n', caret);
        String line = text.substring(ls, le < 0 ? text.length() : le);
        int col = caret - ls;
        if (line.indexOf("[[") < 0) {
            return null; // the line alone says no: nothing else is looked at
        }
        int[] code = MarkdownBlocks.codeSpans(line);
        boolean table = MarkdownTable.isRow(line) && MarkdownTable.blockBounds(text, caret) != null;
        for (int open = line.indexOf("[["); open >= 0; open = line.indexOf("[[", open + 1)) {
            int close = line.indexOf("]]", open + 2);
            if (close < 0) {
                break;
            }
            String inside = line.substring(open + 2, close);
            boolean embed = open > 0 && line.charAt(open - 1) == '!' && !escaped(line, open - 1);
            int start = embed ? open - 1 : open;
            int end = close + 2;
            if (col < start || col > end || inside.indexOf('[') >= 0) {
                continue; // not here — or an outer bracket of "[[[a]]]": the inner pair is tried next
            }
            char before = start > 0 ? line.charAt(start - 1) : ' ';
            char after = end < line.length() ? line.charAt(end) : ' ';
            boolean followedByLabel = after == '[' && !(end + 1 < line.length() && line.charAt(end + 1) == '[');
            if (escaped(line, open)
                    || MarkdownBlocks.inRanges(code, open)
                    || (!embed && Character.isLetterOrDigit(before))
                    || after == '('
                    || followedByLabel) {
                return null;
            }
            if (table) {
                if (inside.replace("\\|", "").indexOf('|') >= 0) {
                    return null; // a bare bar in a table row ends the cell
                }
                inside = inside.replace("\\|", "|");
            }
            MarkdownWikiLinks.WikiLink parsed = MarkdownWikiLinks.parse(inside, embed);
            if (parsed == null || MarkdownLinks.isDefined(text, inside) || !MarkdownLines.isProse(text, ls)) {
                return null;
            }
            return new Link(
                    ls + start, ls + end, parsed.getTarget(), parsed.getHeading(), parsed.getLabel(), embed, table);
        }
        return null;
    }

    /** Whether the character at {@code index} is backslash-escaped (an odd number of backslashes before it). */
    private static boolean escaped(String line, int index) {
        int n = 0;
        while (index - n > 0 && line.charAt(index - n - 1) == '\\') {
            n++;
        }
        return n % 2 == 1;
    }

    /** A wiki link written out; a label that says nothing the target does not is left off. */
    public static String write(String address, String label, boolean embed) {
        return write(address, label, embed, false);
    }

    /**
     * {@link #write(String, String, boolean)} for a link in a table row ({@code inTable}): there the bar
     * before the label is written {@code \|}, or it would end the cell. Brackets, bars and line breaks
     * cannot be part of an address or a label and are dropped.
     */
    public static String write(String address, String label, boolean embed, boolean inTable) {
        String a = plain(address);
        String l = plain(label);
        String bar = inTable ? "\\|" : "|";
        return (embed ? "!" : "") + "[[" + a + (l.isEmpty() || l.equals(a) ? "" : bar + l) + "]]";
    }

    private static String plain(String part) {
        return part == null ? "" : part.replaceAll("[\\[\\]|\\r\\n]", "").strip();
    }

    /**
     * Turns {@code link} into a Markdown link (an embed into an image). {@code resolve} maps the target to
     * the path to link — the window's resolver, which knows where the note is; when it answers null the path
     * is the target as written, with {@code .md} added when it names no file type. A heading becomes its
     * anchor ({@link MarkdownToc#slug}).
     */
    public static MarkdownEdit toMarkdown(Link link, UnaryOperator<String> resolve) {
        String path = link.target().isEmpty() ? "" : resolve == null ? null : resolve.apply(link.target());
        if (path == null) {
            String t = link.target();
            int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
            path = t.lastIndexOf('.') > slash ? t : t + ".md";
        }
        String destination = path + (link.heading() == null ? "" : "#" + MarkdownToc.slug(link.heading()));
        String made = MarkdownLinks.inline(link.shown(), destination, link.embed());
        return new MarkdownEdit(
                link.start(), link.end(), made, link.start() + made.length(), link.start() + made.length());
    }

    /**
     * Turns the Markdown link {@code link} into a wiki link: the destination without its {@code .md} is the
     * target, a fragment the heading, the link text the label (left off when it is the target's own name).
     * Returns {@code null} for a link that leaves the project — one with a scheme ({@code https:}, {@code
     * mailto:}) — which a wiki link cannot name.
     */
    public static @Nullable MarkdownEdit toWiki(MarkdownLinks.Link link) {
        return toWiki(link, false);
    }

    /** {@link #toWiki(MarkdownLinks.Link)} for a link in a table row, where the bar is written {@code \|}. */
    public static @Nullable MarkdownEdit toWiki(MarkdownLinks.Link link, boolean inTable) {
        String d = link.destination();
        if (d.isEmpty() || d.matches("(?i)^[a-z][a-z0-9+.-]*:.*") || d.startsWith("//")) {
            return null;
        }
        int hash = d.indexOf('#');
        String path = decode(hash < 0 ? d : d.substring(0, hash));
        String fragment = hash < 0 ? null : decode(d.substring(hash + 1));
        if (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (!link.image() && path.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            path = path.substring(0, path.length() - 3);
        }
        String address = fragment == null ? path : path + "#" + fragment;
        String name = path.substring(path.lastIndexOf('/') + 1);
        String label = link.text().equals(name) || link.text().equals(path) ? "" : link.text();
        String made = write(address, label, link.image(), inTable);
        return new MarkdownEdit(
                link.start(), link.end(), made, link.start() + made.length(), link.start() + made.length());
    }

    private static String decode(String s) {
        try {
            return s.indexOf('%') < 0
                    ? s
                    : java.net.URLDecoder.decode(s.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s; // a stray percent sign: it is what it is
        }
    }
}
