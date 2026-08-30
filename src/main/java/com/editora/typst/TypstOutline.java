package com.editora.typst;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure extraction of a Typst document's heading outline, the {@code com.editora.markdown.MarkdownOutline}
 * twin for {@code .typ} buffers. It drives both the Structure tool window and heading folding whenever no
 * language server is supplying symbols — which, since {@code tinymist} is optional and off by default, is
 * the normal case.
 *
 * <p>Typst marks a heading with a run of {@code =} at the start of a line followed by a space, so
 * {@code = Introduction} is H1 and {@code == A list} is H2. That is <em>not</em> a Markdown Setext
 * underline (there the {@code =} sits on the line below the title), so the two parsers cannot be shared —
 * an underline run of {@code ===} with no space is deliberately not a heading here.
 *
 * <p>Three constructs are skipped so a stray {@code =} inside them is not read as a section:
 * <ul>
 *   <li>raw blocks, delimited by a run of three or more backticks;</li>
 *   <li>block comments {@code /* … *}{@code /}, which nest in Typst;</li>
 *   <li>line comments {@code //}.</li>
 * </ul>
 *
 * <p>What it deliberately does not do is track markup-versus-code mode. A heading is only recognised at the
 * start of a line, and the {@code =} of an assignment always follows a name ({@code #let x = 1}), so the
 * line-start rule already excludes code without needing a parser for the language.
 *
 * <p>Stateless and toolkit-free, so it is unit-tested directly.
 */
public final class TypstOutline {

    /** Typst's own limit: {@code =} through {@code ======}. */
    public static final int MAX_LEVEL = 6;

    private TypstOutline() {}

    /** A heading: its level (1–6), title text ({@code =} markers stripped), and 0-based line. */
    public record Heading(int level, String title, int line) {}

    /** Every heading in document order. Never null; empty for null/blank input. */
    public static List<Heading> headings(String text) {
        List<Heading> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        String fence = null; // the opening run while inside a raw block
        int blockComment = 0; // nesting depth of /* … */

        for (int i = 0; i < lines.length; i++) {
            String line = stripCr(lines[i]);

            if (blockComment > 0) {
                blockComment = trackBlockComment(line, blockComment);
                continue;
            }
            String token = fenceToken(line);
            if (fence != null) {
                if (token != null && token.length() >= fence.length()) {
                    fence = null;
                }
                continue;
            }
            if (token != null) {
                fence = token;
                continue;
            }
            int depthAfter = trackBlockComment(line, 0);
            if (depthAfter > 0) {
                // The comment opens on this line and does not close on it, so it swallows what follows.
                blockComment = depthAfter;
                continue;
            }

            int level = level(line);
            if (level > 0) {
                out.add(new Heading(level, title(line, level), i));
            }
        }
        return out;
    }

    /**
     * The heading level (1–{@link #MAX_LEVEL}) of {@code line}, or 0 when it is not a heading.
     *
     * <p>The trailing space is required, which is what separates a heading from a {@code ===} rule or an
     * equality inside math. A run longer than {@link #MAX_LEVEL} is not a heading in Typst either.
     */
    public static int level(String line) {
        if (line == null) {
            return 0;
        }
        int p = 0;
        while (p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) {
            p++;
        }
        int eqs = 0;
        while (p < line.length() && line.charAt(p) == '=') {
            p++;
            eqs++;
        }
        if (eqs < 1 || eqs > MAX_LEVEL) {
            return 0;
        }
        // A heading needs a space after its markers. End-of-line is NOT a heading: a bare "=" is an
        // empty-titled section nobody writes, while "=" alone is a plausible fragment of edited text.
        return p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '\t') ? eqs : 0;
    }

    private static String title(String line, int level) {
        String body = line.strip().substring(level).strip();
        return body;
    }

    /** The backtick run if {@code line} opens or closes a raw block, else null. */
    private static String fenceToken(String line) {
        int p = 0;
        while (p < line.length() && (line.charAt(p) == ' ' || line.charAt(p) == '\t')) {
            p++;
        }
        int n = 0;
        int start = p;
        while (p < line.length() && line.charAt(p) == '`') {
            p++;
            n++;
        }
        return n >= 3 ? line.substring(start, start + n) : null;
    }

    /**
     * Applies {@code line}'s comment delimiters to a nesting {@code depth} and returns the depth at the end
     * of the line. Typst nests block comments, so this counts rather than flags. A {@code //} outside a
     * block comment ends the line.
     */
    private static int trackBlockComment(String line, int depth) {
        for (int i = 0; i + 1 < line.length(); i++) {
            char a = line.charAt(i);
            char b = line.charAt(i + 1);
            if (a == '/' && b == '*') {
                depth++;
                i++;
            } else if (a == '*' && b == '/') {
                if (depth > 0) {
                    depth--;
                }
                i++;
            } else if (depth == 0 && a == '/' && b == '/') {
                return 0; // line comment: nothing after it can open a block
            }
        }
        return depth;
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
