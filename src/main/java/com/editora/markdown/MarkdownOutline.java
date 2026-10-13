package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure extraction of a Markdown document's heading outline (ATX {@code # …} and Setext
 * {@code ===}/{@code ---} underlines), used to drive the Structure tool window for Markdown
 * buffers that have no LSP symbols. Code blocks (fenced with backticks or tildes, or indented), front
 * matter, HTML comments and raw HTML blocks are skipped ({@link MarkdownBlocks}), so a {@code #} comment in
 * code, a {@code ---} delimiter or a commented-out section doesn't show up as a heading.
 *
 * <p>Stateless and toolkit-free, so it is unit-tested directly.
 */
public final class MarkdownOutline {

    private MarkdownOutline() {}

    /**
     * A heading: its level (1–6), title text (heading markers stripped, inline markup as written — see
     * {@link MarkdownText#plain}), and the 0-based line it starts on.
     */
    public record Heading(int level, String title, int line) {}

    public static List<Heading> headings(String text) {
        List<Heading> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        // What is code, front matter, an HTML comment or a raw HTML block is not Markdown: a "# …" line in a
        // ~~~ fence, under a <div>, or in a section commented out with <!-- … --> is not a heading.
        MarkdownBlocks blocks = MarkdownBlocks.scan(text);
        int count = blocks.lineCount();
        for (int i = 0; i < count; i++) {
            if (!blocks.isText(i)) {
                continue;
            }
            String line = blocks.line(i);
            String trimmed = line.strip();

            int level = atxLevel(line);
            if (level > 0) {
                out.add(new Heading(level, atxTitle(line, level), i));
                continue;
            }

            // Setext: a *paragraph* underlined by all '=' (H1) or all '-' (H2). The paragraph may run over
            // several lines — all of them are the heading — so look ahead to where it ends.
            if (!trimmed.isEmpty() && isParagraphStart(trimmed)) {
                StringBuilder title = new StringBuilder(trimmed);
                for (int j = i + 1; j < count && blocks.isText(j); j++) {
                    String next = blocks.line(j);
                    int setext = setextLevel(next);
                    if (setext > 0) {
                        out.add(new Heading(setext, title.toString(), i));
                        i = j; // consume the paragraph and its underline
                        break;
                    }
                    String nextTrimmed = next.strip();
                    if (nextTrimmed.isEmpty() || atxLevel(next) > 0 || !isParagraphStart(nextTrimmed)) {
                        // The paragraph ended without an underline: none of its lines is a heading, so the
                        // scan resumes at the line that ended it (which keeps a long paragraph linear).
                        i = j - 1;
                        break;
                    }
                    title.append(' ').append(nextTrimmed);
                }
            }
        }
        return out;
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

    /**
     * True when {@code trimmed} could open a <b>paragraph</b> — the only block a setext underline may
     * underline. A {@code ---} under a list item, blockquote or HTML block is a thematic break, not an H2
     * (CommonMark's own example: {@code - Foo\n---} is a list plus an {@code <hr>}). Treating it as a
     * heading put list text in the Structure outline and, via the shared outline, in the generated TOC —
     * including a {@code <!-- /toc -->} marker sitting above a {@code ---}.
     */
    private static boolean isParagraphStart(String trimmed) {
        char c = trimmed.charAt(0);
        if (isThematicBreak(trimmed)) {
            return false; // "---", "* * *": a rule, which neither starts nor continues a paragraph
        }
        if (c == '>' || c == '<') {
            return false; // blockquote / HTML block
        }
        if ((c == '-' || c == '*' || c == '+') && trimmed.length() > 1 && trimmed.charAt(1) == ' ') {
            return false; // bullet list item
        }
        int d = 0;
        while (d < trimmed.length() && Character.isDigit(trimmed.charAt(d))) {
            d++;
        }
        boolean ordered = d > 0
                && d + 1 < trimmed.length()
                && (trimmed.charAt(d) == '.' || trimmed.charAt(d) == ')')
                && trimmed.charAt(d + 1) == ' ';
        return !ordered;
    }

    /** Three or more of the same {@code -}, {@code *} or {@code _}, with nothing but spaces between them. */
    private static boolean isThematicBreak(String trimmed) {
        char c = trimmed.charAt(0);
        if (c != '-' && c != '*' && c != '_') {
            return false;
        }
        int marks = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == c) {
                marks++;
            } else if (ch != ' ' && ch != '\t') {
                return false;
            }
        }
        return marks >= 3;
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
}
