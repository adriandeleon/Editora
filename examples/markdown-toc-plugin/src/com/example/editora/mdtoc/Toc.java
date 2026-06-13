package com.example.editora.mdtoc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Markdown table-of-contents builder: scans ATX headings (`#`…`######`), skips fenced code blocks,
 * and emits a nested bullet list of GitHub-style anchor links. No editor/JavaFX deps.
 */
final class Toc {

    private Toc() {
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");

    private record Heading(int level, String title) { }

    /** Builds the TOC markdown for {@code source}, or {@code ""} when there are no headings. */
    static String build(String source) {
        List<Heading> headings = headings(source);
        if (headings.isEmpty()) {
            return "";
        }
        int min = headings.stream().mapToInt(Heading::level).min().orElse(1);
        Map<String, Integer> seen = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (Heading h : headings) {
            String indent = "  ".repeat(h.level() - min);
            sb.append(indent).append("- [").append(h.title()).append("](#")
                    .append(uniqueAnchor(slug(h.title()), seen)).append(")\n");
        }
        return sb.toString();
    }

    private static List<Heading> headings(String source) {
        List<Heading> out = new ArrayList<>();
        boolean inFence = false;
        for (String line : source.split("\n", -1)) {
            if (FENCE.matcher(line).find()) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                String title = m.group(2).strip();
                if (!title.isEmpty()) {
                    out.add(new Heading(m.group(1).length(), title));
                }
            }
        }
        return out;
    }

    /** GitHub-style anchor slug: lowercase, drop non-word/space/hyphen chars, spaces → hyphens. Pure. */
    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).strip();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') {
                sb.append(c == ' ' ? '-' : c);
            }
        }
        return sb.toString();
    }

    /** GitHub appends {@code -1}, {@code -2}… to repeated anchors. */
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
