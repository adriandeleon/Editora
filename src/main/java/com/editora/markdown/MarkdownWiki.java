package com.editora.markdown;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     */
    public record Link(int start, int end, String target, String heading, String label, boolean embed) {

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

    private static final Pattern WIKI =
            Pattern.compile("(!?)\\[\\[([^\\[\\]|#\\n]*)(?:#([^\\[\\]|\\n]*))?(?:\\|([^\\[\\]\\n]*))?\\]\\]");

    private MarkdownWiki() {}

    /** The wiki link whose span contains {@code caret} (ends included), or null; never one inside a code span. */
    public static Link at(String text, int caret) {
        int ls = text.lastIndexOf('\n', caret - 1) + 1;
        int le = text.indexOf('\n', caret);
        String line = text.substring(ls, le < 0 ? text.length() : le);
        int[] code = MarkdownBlocks.codeSpans(line);
        Matcher m = WIKI.matcher(line);
        while (m.find()) {
            if (caret - ls >= m.start() && caret - ls <= m.end() && !MarkdownBlocks.inRanges(code, m.start())) {
                String target = m.group(2).strip();
                if (target.isEmpty() && m.group(3) == null) {
                    return null; // "[[]]": nothing is named
                }
                return new Link(
                        ls + m.start(),
                        ls + m.end(),
                        target,
                        m.group(3) == null ? null : m.group(3).strip(),
                        m.group(4) == null ? null : m.group(4).strip(),
                        !m.group(1).isEmpty());
            }
        }
        return null;
    }

    /** A wiki link written out; a label that says nothing the target does not is left off. */
    public static String write(String address, String label, boolean embed) {
        String a = address == null ? "" : address.strip();
        String l = label == null ? "" : label.strip().replace("|", "").replace("]]", "");
        return (embed ? "!" : "") + "[[" + a + (l.isEmpty() || l.equals(a) ? "" : "|" + l) + "]]";
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
    public static MarkdownEdit toWiki(MarkdownLinks.Link link) {
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
        String made = write(address, label, link.image());
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
