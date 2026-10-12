package com.editora.markdown;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure (no-toolkit, unit-tested) resolution of a link fragment ({@code #installation}) to the line it names
 * in a Markdown document: the heading whose anchor is that slug — the same {@link MarkdownToc#slug} plus
 * {@code -1}, {@code -2} sequence the table of contents writes, so every entry of a generated TOC resolves —
 * or, failing that, an explicit HTML anchor ({@code <a name="…">} / {@code id="…"}).
 */
public final class MarkdownAnchors {

    private static final Pattern HTML_ANCHOR = Pattern.compile("\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']");

    private MarkdownAnchors() {}

    /** The 0-based line {@code fragment} (with or without its {@code #}) names in {@code markdown}, or -1. */
    public static int lineOf(String markdown, String fragment) {
        if (markdown == null || fragment == null) {
            return -1;
        }
        String wanted = (fragment.startsWith("#") ? fragment.substring(1) : fragment).strip();
        if (wanted.isEmpty()) {
            return -1;
        }
        String folded = wanted.toLowerCase(Locale.ROOT);
        Map<String, Integer> seen = new HashMap<>();
        for (MarkdownOutline.Heading h : MarkdownOutline.headings(markdown)) {
            if (h.title().isBlank()) {
                continue; // the table of contents leaves these out of the numbering too
            }
            if (MarkdownToc.uniqueAnchor(MarkdownToc.slug(h.title()), seen).equals(folded)) {
                return h.line();
            }
        }
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].indexOf('<') < 0) {
                continue;
            }
            Matcher m = HTML_ANCHOR.matcher(lines[i]);
            while (m.find()) {
                if (m.group(1).equals(wanted)) {
                    return i;
                }
            }
        }
        return -1;
    }
}
