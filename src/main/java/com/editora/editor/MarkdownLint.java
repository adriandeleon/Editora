package com.editora.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, high-confidence Markdown linter (a small subset of the markdownlint rule set). Returns
 * 1-based {@link Diagnostic}s in document order, mirroring {@link com.editora.mermaid.MaidOutput}'s
 * shape so the lint overlay/panel can render either. Fenced code blocks (and a leading YAML front
 * matter block) are skipped where it would otherwise produce noise.
 *
 * <p>Stateless and toolkit-free — unit-tested directly.
 */
public final class MarkdownLint {

    private MarkdownLint() {}

    public static final String WARNING = "warning";

    /** One diagnostic: 1-based {@code line}/{@code column}, char span {@code length} (≥1), a
     *  {@code severity} ({@code error}/{@code warning}), a rule {@code code}, and a {@code message}. */
    public record Diagnostic(int line, int column, int length, String severity, String code, String message) {
        public boolean isError() {
            return "error".equalsIgnoreCase(severity);
        }
    }

    // Full reference link use: [text][label] with a non-empty label (not an image).
    private static final Pattern FULL_REF = Pattern.compile("\\[[^\\]]*\\]\\[([^\\]]+)\\]");
    // A reference definition: [label]: url
    private static final Pattern REF_DEF = Pattern.compile("^\\s{0,3}\\[([^\\]]+)\\]:\\s*\\S");

    public static List<Diagnostic> lint(String text) {
        List<Diagnostic> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);

        // Collect reference-definition labels (normalized) for the broken-reference check.
        Set<String> defined = new HashSet<>();
        for (String raw : lines) {
            Matcher m = REF_DEF.matcher(stripCr(raw));
            if (m.find()) {
                defined.add(normalizeLabel(m.group(1)));
            }
        }

        boolean inFrontMatter = lines.length > 0 && lines[0].strip().equals("---");
        int frontMatterEnd = -1;
        if (inFrontMatter) {
            for (int j = 1; j < lines.length; j++) {
                if (lines[j].strip().equals("---")) {
                    frontMatterEnd = j;
                    break;
                }
            }
        }

        String fence = null; // the opening fence run while inside a fenced code block
        int h1Count = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = stripCr(lines[i]);
            int lineNo = i + 1;
            boolean inFront = frontMatterEnd >= 0 && i <= frontMatterEnd;

            String fenceTok = fenceToken(line);
            if (fence != null) {
                if (fenceTok != null && fenceTok.charAt(0) == fence.charAt(0) && fenceTok.length() >= fence.length()) {
                    fence = null;
                }
                continue; // inside code: no line-level rules
            }
            if (fenceTok != null) {
                fence = fenceTok;
                // MD040: opening fence with no language/info string.
                String info = line.strip().substring(fenceTok.length()).strip();
                if (info.isEmpty()) {
                    out.add(new Diagnostic(
                            lineNo,
                            1,
                            fenceTok.length(),
                            WARNING,
                            "MD040",
                            "Fenced code block should specify a language"));
                }
                continue;
            }

            if (inFront) {
                continue; // don't lint inside front matter
            }

            // MD009: trailing whitespace.
            int end = line.length();
            while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
                end--;
            }
            if (end < line.length()) {
                out.add(new Diagnostic(lineNo, end + 1, line.length() - end, WARNING, "MD009", "Trailing whitespace"));
            }

            // MD012: multiple consecutive blank lines.
            if (line.isBlank() && i > 0 && stripCr(lines[i - 1]).isBlank()) {
                out.add(new Diagnostic(lineNo, 1, 1, WARNING, "MD012", "Multiple consecutive blank lines"));
            }

            // MD018 / MD019: ATX heading hash spacing.
            int hashes = leadingHashes(line);
            if (hashes > 0) {
                int after = leadingSpaces(line) + hashes;
                if (after < line.length() && line.charAt(after) != ' ' && line.charAt(after) != '\t') {
                    out.add(new Diagnostic(
                            lineNo,
                            leadingSpaces(line) + 1,
                            hashes,
                            WARNING,
                            "MD018",
                            "No space after heading marker"));
                } else {
                    int spaces = 0;
                    int p = after;
                    while (p < line.length() && line.charAt(p) == ' ') {
                        p++;
                        spaces++;
                    }
                    if (spaces > 1 && p < line.length()) {
                        out.add(new Diagnostic(
                                lineNo, after + 1, spaces, WARNING, "MD019", "Multiple spaces after heading marker"));
                    }
                }
            }

            // Broken full reference links: [text][label] with no matching definition.
            Matcher ref = FULL_REF.matcher(line);
            while (ref.find()) {
                if (!defined.contains(normalizeLabel(ref.group(1)))) {
                    out.add(new Diagnostic(
                            lineNo,
                            ref.start() + 1,
                            ref.end() - ref.start(),
                            WARNING,
                            "MD052",
                            "Reference link has no matching definition"));
                }
            }
        }

        // MD025: more than one top-level (H1) heading. Reuse the outline (skips code/front matter).
        for (MarkdownOutline.Heading h : MarkdownOutline.headings(text)) {
            if (h.level() == 1) {
                h1Count++;
                if (h1Count > 1) {
                    out.add(new Diagnostic(h.line() + 1, 1, 1, WARNING, "MD025", "Multiple top-level headings"));
                }
            }
        }

        // MD047: file should end with a single trailing newline.
        if (!text.isEmpty()) {
            int lastLine = lines.length; // split("\n",-1): a trailing "\n" yields a final empty element
            if (!text.endsWith("\n")) {
                out.add(new Diagnostic(lastLine, 1, 1, WARNING, "MD047", "File should end with a newline"));
            } else if (text.endsWith("\n\n")) {
                out.add(new Diagnostic(lastLine, 1, 1, WARNING, "MD047", "File should end with a single newline"));
            }
        }

        out.sort((a, b) ->
                a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.column(), b.column()));
        return out;
    }

    private static String normalizeLabel(String label) {
        return label == null ? "" : label.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Count of leading {@code #} (1–6) if {@code line} is an ATX heading marker, else 0. */
    private static int leadingHashes(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3) {
            return 0;
        }
        int p = indent;
        int n = 0;
        while (p < line.length() && line.charAt(p) == '#') {
            p++;
            n++;
        }
        return n >= 1 && n <= 6 ? n : 0;
    }

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
