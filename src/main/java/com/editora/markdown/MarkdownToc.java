package com.editora.markdown;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /** The TOC body (no markers) for {@code markdown}, all heading levels — or {@code ""} when there are none. */
    public static String build(String markdown) {
        return build(markdown, 1, 6);
    }

    /**
     * The TOC body (no markers) for headings in {@code [minLevel, maxLevel]}, or {@code ""} when none match.
     * Indentation is two spaces per level below the shallowest matching heading, so a doc whose top heading is
     * {@code ##} still starts flush-left.
     */
    public static String build(String markdown, int minLevel, int maxLevel) {
        List<MarkdownOutline.Heading> headings = MarkdownOutline.headings(markdown).stream()
                .filter(h -> h.level() >= minLevel
                        && h.level() <= maxLevel
                        && !h.title().isBlank())
                .toList();
        if (headings.isEmpty()) {
            return "";
        }
        int base =
                headings.stream().mapToInt(MarkdownOutline.Heading::level).min().orElse(1);
        Map<String, Integer> seen = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (MarkdownOutline.Heading h : headings) {
            sb.append("  ".repeat(Math.max(0, h.level() - base)))
                    .append("- [")
                    .append(h.title())
                    .append("](#")
                    .append(uniqueAnchor(slug(h.title()), seen))
                    .append(")\n");
        }
        return sb.toString();
    }

    /** Wraps a TOC {@code body} in {@link #BEGIN}/{@link #END} markers (each on its own line). */
    public static String wrapped(String body) {
        return BEGIN + "\n" + body + END;
    }

    /**
     * If {@code doc} already contains a {@link #BEGIN}…{@link #END} marker block, replaces it with a freshly
     * built TOC and returns the new document; returns {@code null} when there is no marker block (the caller
     * then inserts a new one). The rebuild scans the whole document — the existing TOC's bullet links aren't
     * headings, so they never feed back into the new TOC.
     */
    public static String updated(String doc, int minLevel, int maxLevel) {
        if (doc == null) {
            return null;
        }
        int b = doc.indexOf(BEGIN);
        if (b < 0) {
            return null;
        }
        int e = doc.indexOf(END, b + BEGIN.length());
        if (e < 0) {
            return null;
        }
        String block = wrapped(build(doc, minLevel, maxLevel));
        return doc.substring(0, b) + block + doc.substring(e + END.length());
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
    private static String uniqueAnchor(String base, Map<String, Integer> seen) {
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
