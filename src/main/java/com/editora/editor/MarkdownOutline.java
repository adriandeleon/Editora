package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure extraction of a Markdown document's heading outline (ATX {@code # …} and Setext
 * {@code ===}/{@code ---} underlines), used to drive the Structure tool window for Markdown
 * buffers that have no LSP symbols. Fenced code blocks and a leading YAML front-matter block are
 * skipped so {@code #} comments inside code (or {@code ---} delimiters) don't show up as headings.
 *
 * <p>Stateless and toolkit-free, so it is unit-tested directly.
 */
public final class MarkdownOutline {

    private MarkdownOutline() {}

    /** A heading: its level (1–6), title text (markers stripped), and 0-based line. */
    public record Heading(int level, String title, int line) {}

    public static List<Heading> headings(String text) {
        List<Heading> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        int i = 0;

        // Skip a leading YAML front-matter block: a "---" on the very first line up to the next "---".
        if (lines.length > 0 && lines[0].strip().equals("---")) {
            int j = 1;
            while (j < lines.length && !lines[j].strip().equals("---")) {
                j++;
            }
            if (j < lines.length) {
                i = j + 1; // consumed the closing delimiter too
            }
        }

        String fence = null; // non-null while inside a fenced code block (the opening fence run)
        for (; i < lines.length; i++) {
            String line = stripCr(lines[i]);
            String trimmed = line.strip();

            String openFence = fenceToken(line);
            if (fence != null) {
                // Inside a fence: only a matching closing fence (same char, ≥ length) ends it.
                if (openFence != null
                        && openFence.charAt(0) == fence.charAt(0)
                        && openFence.length() >= fence.length()) {
                    fence = null;
                }
                continue;
            }
            if (openFence != null) {
                fence = openFence;
                continue;
            }

            int level = atxLevel(line);
            if (level > 0) {
                out.add(new Heading(level, atxTitle(line, level), i));
                continue;
            }

            // Setext: a non-blank text line underlined by all '=' (H1) or all '-' (H2).
            if (!trimmed.isEmpty() && i + 1 < lines.length) {
                int setext = setextLevel(stripCr(lines[i + 1]));
                if (setext > 0 && atxLevel(line) == 0 && fenceToken(line) == null) {
                    out.add(new Heading(setext, trimmed, i));
                    i++; // consume the underline line
                }
            }
        }
        return out;
    }

    /** The fence run ({@code ```} or {@code ~~~}, 3+) if {@code line} opens/closes a fence, else null. */
    private static String fenceToken(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3) {
            return null;
        }
        int p = indent;
        char c = p < line.length() ? line.charAt(p) : ' ';
        if (c != '`' && c != '~') {
            return null;
        }
        int n = 0;
        while (p < line.length() && line.charAt(p) == c) {
            p++;
            n++;
        }
        return n >= 3 ? line.substring(indent, indent + n) : null;
    }

    /** The ATX heading level (1–6) if {@code line} is an ATX heading, else 0. */
    private static int atxLevel(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3) {
            return 0;
        }
        int p = indent;
        int hashes = 0;
        while (p < line.length() && line.charAt(p) == '#') {
            p++;
            hashes++;
        }
        if (hashes < 1 || hashes > 6) {
            return 0;
        }
        // Must be followed by a space/tab or end of line.
        if (p < line.length() && line.charAt(p) != ' ' && line.charAt(p) != '\t') {
            return 0;
        }
        return hashes;
    }

    private static String atxTitle(String line, int level) {
        String body = line.strip().substring(level).strip();
        // Strip an optional trailing run of '#' (closing sequence) and its preceding spaces.
        int end = body.length();
        while (end > 0 && body.charAt(end - 1) == '#') {
            end--;
        }
        if (end < body.length() && (end == 0 || body.charAt(end - 1) == ' ')) {
            body = body.substring(0, end).strip();
        }
        return body;
    }

    /** Setext underline level: 1 for all-'=', 2 for all-'-', else 0. */
    private static int setextLevel(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3) {
            return 0;
        }
        String body = line.strip();
        if (body.isEmpty()) {
            return 0;
        }
        char c = body.charAt(0);
        if (c != '=' && c != '-') {
            return 0;
        }
        for (int k = 0; k < body.length(); k++) {
            if (body.charAt(k) != c) {
                return 0;
            }
        }
        return c == '=' ? 1 : 2;
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String stripCr(String line) {
        return !line.isEmpty() && line.charAt(line.length() - 1) == '\r' ? line.substring(0, line.length() - 1) : line;
    }
}
