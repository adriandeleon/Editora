package com.editora.markdown;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Pure (no-toolkit, unit-tested) Markdown table-of-contents builder. Reuses {@link MarkdownOutline} for the
 * heading list (ATX + Setext, skipping fenced code blocks and a leading YAML front-matter block) and emits a
 * nested bullet list of GitHub-style anchor links ({@code - [Title](#slug)}), indented two spaces per heading
 * level below the shallowest heading present.
 *
 * <p>The TOC can be wrapped in {@code <!-- toc -->} / {@code <!-- /toc -->} markers so a later "insert/update"
 * run replaces it in place instead of duplicating it.
 */
public final class MarkdownToc {

    /** Marker that opens a managed TOC block (so it can be regenerated in place). */
    public static final String BEGIN = "<!-- toc -->";

    /** Marker that closes a managed TOC block. */
    public static final String END = "<!-- /toc -->";

    private MarkdownToc() {}

    /**
     * What a table of contents lists and how.
     *
     * @param minLevel the shallowest heading level listed (1–6)
     * @param maxLevel the deepest heading level listed
     * @param skipTitle leave out the document's title: a level-1 heading that comes first and is the only one
     * @param ordered number the entries ({@code 1.}) instead of bulleting them
     */
    public record Style(int minLevel, int maxLevel, boolean skipTitle, boolean ordered) {
        public static final Style DEFAULT = new Style(1, 6, false, false);
    }

    private static final java.util.regex.Pattern BEGIN_MARK =
            java.util.regex.Pattern.compile("<!--\\s*toc\\s*-->", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern END_MARK =
            java.util.regex.Pattern.compile("<!--\\s*/toc\\s*-->", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern ENTRY =
            java.util.regex.Pattern.compile("^\\s*(?:[-*+]|\\d+\\.) \\[.*\\]\\(#[^)]*\\)\\s*$");

    /** The TOC body (no markers) for {@code markdown}, all heading levels — or {@code ""} when there are none. */
    public static String build(String markdown) {
        return build(markdown, 1, 6);
    }

    /**
     * The TOC body (no markers) for headings in {@code [minLevel, maxLevel]}, or {@code ""} when none match.
     * An entry shows the heading's plain text ({@link MarkdownText#plain}) and links to the anchor made from
     * that text; entries nest by the headings actually above them, two spaces a step, so a document that
     * jumps from {@code #} to {@code ####} still yields a list rather than an indented code block.
     */
    public static String build(String markdown, int minLevel, int maxLevel) {
        return build(markdown, minLevel, maxLevel, -1);
    }

    /**
     * {@link #build(String, int, int)} for a TOC that stands at 0-based line {@code tocLine}: the heading the
     * TOC sits directly under ("Contents") is not an entry of it.
     */
    static String build(String markdown, int minLevel, int maxLevel, int tocLine) {
        return build(markdown, tocLine, new Style(minLevel, maxLevel, false, false));
    }

    /** The TOC body in {@code style} for a TOC standing at 0-based line {@code tocLine} (-1: nowhere yet). */
    static String build(String markdown, int tocLine, Style style) {
        int minLevel = style.minLevel();
        int maxLevel = style.maxLevel();
        List<MarkdownOutline.Heading> all = MarkdownOutline.headings(markdown);
        int own = ownHeading(markdown, all, tocLine);
        if (own == 0 && all.get(0).level() == 1) {
            own = -1; // directly under the title: that is the document's heading, not the TOC's own
        }
        boolean skipTitle = style.skipTitle()
                && !all.isEmpty()
                && all.get(0).level() == 1
                && all.stream().filter(h -> h.level() == 1).count() == 1;
        Map<String, Integer> seen = new HashMap<>();
        java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>(); // levels of the entries we are inside
        Map<Integer, Long> counters = new HashMap<>(); // nesting depth → entries so far at that depth
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            MarkdownOutline.Heading h = all.get(i);
            String text = MarkdownText.plain(h.title());
            // Every heading takes its anchor, listed or not: the -1, -2 suffixes count them all.
            String anchor = uniqueAnchor(slug(text), seen);
            if (h.level() < minLevel || h.level() > maxLevel || text.isBlank() || i == own || (skipTitle && i == 0)) {
                continue;
            }
            while (!open.isEmpty() && open.peek() >= h.level()) {
                open.pop();
            }
            // A numbered entry's children start under its text: four columns clears "1. " up to "99. ".
            int depth = open.size();
            long n = style.ordered() ? counters.merge(depth, 1L, Long::sum) : 0;
            counters.keySet().removeIf(d -> d > depth);
            sb.append((style.ordered() ? "    " : "  ").repeat(depth))
                    .append(style.ordered() ? n + ". [" : "- [")
                    .append(text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]"))
                    .append("](#")
                    .append(anchor)
                    .append(")\n");
            open.push(h.level());
        }
        return sb.toString();
    }

    /** Index of the heading with nothing but blank lines between it and {@code tocLine}; -1 if none. */
    private static int ownHeading(String markdown, List<MarkdownOutline.Heading> all, int tocLine) {
        if (tocLine < 0) {
            return -1;
        }
        String[] lines = markdown.split("\n", -1);
        int at = Math.min(tocLine, lines.length) - 1;
        while (at >= 0 && lines[at].isBlank()) {
            at--;
        }
        for (int i = 0; i < all.size(); i++) {
            int line = all.get(i).line();
            // A Setext heading ends on its underline, one line below where it starts.
            if (line == at || (line == at - 1 && lines[at].strip().matches("=+|-+"))) {
                return i;
            }
        }
        return -1;
    }

    /** Wraps a TOC {@code body} in {@link #BEGIN}/{@link #END} markers (each on its own line). */
    public static String wrapped(String body) {
        return BEGIN + "\n" + body + END;
    }

    /**
     * If {@code doc} already contains a {@link #BEGIN}…{@link #END} marker block, replaces it with a freshly
     * built TOC and returns the new document; returns {@code null} when there is no marker block (the caller
     * then inserts a new one). See {@link #update}.
     */
    public static @Nullable String updated(String doc, int minLevel, int maxLevel) {
        MarkdownEdit e = update(doc, minLevel, maxLevel);
        return e == null ? null : doc.substring(0, e.from()) + e.replacement() + doc.substring(e.to());
    }

    /**
     * The edit that regenerates the document's managed TOC block, or {@code null} when it has none. The
     * markers are matched whatever their case and spacing ({@code <!--TOC-->}); a marker inside a fenced code
     * block is an example, not the TOC; an opening marker whose closing one is missing takes the list under
     * it as the stale TOC, so a second block is never added beside it. The edit's caret is its start.
     */
    public static @Nullable MarkdownEdit update(String doc, int minLevel, int maxLevel) {
        return update(doc, new Style(minLevel, maxLevel, false, false));
    }

    /** {@link #update(String, int, int)} in {@code style}. */
    public static @Nullable MarkdownEdit update(String doc, Style style) {
        if (doc == null) {
            return null;
        }
        java.util.regex.Matcher begin = BEGIN_MARK.matcher(doc);
        while (begin.find()) {
            int lineStart = doc.lastIndexOf('\n', begin.start() - 1) + 1;
            if (MarkdownLines.insideCode(doc, lineStart)) {
                continue;
            }
            int end = staleEnd(doc, begin.end());
            java.util.regex.Matcher close = END_MARK.matcher(doc);
            if (close.find(begin.end())
                    && !MarkdownLines.insideCode(doc, doc.lastIndexOf('\n', close.start() - 1) + 1)) {
                end = close.end();
            }
            int line = (int) doc.substring(0, begin.start())
                    .chars()
                    .filter(c -> c == '\n')
                    .count();
            String block = wrapped(build(doc, line, style));
            return new MarkdownEdit(begin.start(), end, block, begin.start(), begin.start());
        }
        return null;
    }

    /** End of the TOC entries directly under an opening marker that ends at {@code from}. */
    private static int staleEnd(String doc, int from) {
        int end = from;
        int at = doc.indexOf('\n', from);
        while (at >= 0 && at < doc.length()) {
            int next = doc.indexOf('\n', at + 1);
            String line = doc.substring(at + 1, next < 0 ? doc.length() : next);
            if (!ENTRY.matcher(line).matches()) {
                break;
            }
            end = next < 0 ? doc.length() : next;
            at = next;
        }
        return end;
    }

    /**
     * A new, marker-wrapped TOC for {@code doc} to insert at {@code offset}; {@code ""} when the document has
     * no heading to list.
     */
    public static String insertion(String doc, int offset, int minLevel, int maxLevel) {
        return insertion(doc, offset, new Style(minLevel, maxLevel, false, false));
    }

    /** {@link #insertion(String, int, int, int)} in {@code style}. */
    public static String insertion(String doc, int offset, Style style) {
        int at = Math.max(0, Math.min(offset, doc.length()));
        int line = (int) doc.substring(0, at).chars().filter(c -> c == '\n').count();
        if (at > 0 && doc.charAt(at - 1) != '\n') {
            line++; // mid-line: the block goes on a line of its own, below
        }
        String body = build(doc, line, style);
        return body.isEmpty() ? "" : wrapped(body);
    }

    /** GitHub-style anchor slug: lowercase, drop everything but letters/digits/space/hyphen/underscore, space → hyphen. */
    public static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).strip();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('-');
            }
        }
        return sb.toString();
    }

    /** GitHub appends {@code -1}, {@code -2}… to a slug that has already been used. */
    public static String uniqueAnchor(String base, Map<String, Integer> seen) {
        Integer n = seen.get(base);
        if (n == null) {
            seen.put(base, 0);
            return base;
        }
        n += 1;
        seen.put(base, n);
        return base + "-" + n;
    }
}
