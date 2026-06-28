package com.editora.markdown;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, high-confidence Markdown linter (a curated subset of the markdownlint rule set). Returns
 * 1-based {@link Diagnostic}s in document order, mirroring {@link com.editora.mermaid.MaidOutput}'s
 * shape so the lint overlay/panel/stripe can render either. Fenced code blocks (and a leading YAML
 * front matter block) are skipped where a rule would otherwise produce noise.
 *
 * <p>A {@code disabled} set (rule codes the user turned off in Settings / a {@code .markdownlint.json})
 * and inline {@link MarkdownLintDirectives} (<!-- markdownlint-disable … -->) both suppress emission.
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

    /** A lint rule's code + short label (for the Settings checklist / per-rule toggle picker). */
    public record Rule(String code, String name) {}

    /** Every rule this linter can emit, in code order — the source of truth for the per-rule UI. */
    public static final List<Rule> RULES = List.of(
            new Rule("MD001", "Heading levels increment by one"),
            new Rule("MD009", "Trailing whitespace"),
            new Rule("MD010", "Hard tabs"),
            new Rule("MD012", "Multiple consecutive blank lines"),
            new Rule("MD018", "No space after heading marker"),
            new Rule("MD019", "Multiple spaces after heading marker"),
            new Rule("MD022", "Headings surrounded by blank lines"),
            new Rule("MD023", "Headings start at line beginning"),
            new Rule("MD025", "Single top-level heading"),
            new Rule("MD026", "No trailing punctuation in heading"),
            new Rule("MD031", "Fenced code surrounded by blank lines"),
            new Rule("MD034", "No bare URLs"),
            new Rule("MD040", "Fenced code should specify a language"),
            new Rule("MD041", "First line should be a top-level heading"),
            new Rule("MD047", "File should end with a single newline"),
            new Rule("MD052", "Reference links have a definition"));

    // Full reference link use: [text][label] with a non-empty label (not an image).
    private static final Pattern FULL_REF = Pattern.compile("\\[[^\\]]*\\]\\[([^\\]]+)\\]");
    // A reference definition: [label]: url
    private static final Pattern REF_DEF = Pattern.compile("^\\s{0,3}\\[([^\\]]+)\\]:\\s*\\S");
    // A bare URL (no surrounding markdown link / autolink syntax).
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s<>\\[\\]()]+");
    // Trailing punctuation flagged by MD026 (markdownlint default minus the question mark).
    private static final String HEADING_PUNCTUATION = ".,;:!";

    /** Lints with no rules disabled. */
    public static List<Diagnostic> lint(String text) {
        return lint(text, Set.of());
    }

    /** Lints {@code text}; {@code disabled} rule codes (and inline directives) suppress those diagnostics. */
    public static List<Diagnostic> lint(String text, Set<String> disabled) {
        List<Diagnostic> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        Set<String> off = disabled == null ? Set.of() : disabled;
        String[] lines = text.split("\n", -1);
        MarkdownLintDirectives directives = MarkdownLintDirectives.compute(lines);

        // Collect reference-definition labels (normalized) for the broken-reference check.
        Set<String> defined = new HashSet<>();
        for (String raw : lines) {
            Matcher m = REF_DEF.matcher(stripCr(raw));
            if (m.find()) {
                defined.add(normalizeLabel(m.group(1)));
            }
        }

        int frontMatterEnd = -1;
        if (lines.length > 0 && lines[0].strip().equals("---")) {
            for (int j = 1; j < lines.length; j++) {
                if (lines[j].strip().equals("---")) {
                    frontMatterEnd = j;
                    break;
                }
            }
        }

        // MD041: the first content line (after front matter, skipping blanks/HTML comments) should be an H1.
        checkFirstLineHeading(lines, frontMatterEnd, off, directives, out);

        String fence = null; // the opening fence run while inside a fenced code block
        for (int i = 0; i < lines.length; i++) {
            String line = stripCr(lines[i]);
            int lineNo = i + 1;
            boolean inFront = frontMatterEnd >= 0 && i <= frontMatterEnd;

            String fenceTok = fenceToken(line);
            if (fence != null) {
                if (fenceTok != null && fenceTok.charAt(0) == fence.charAt(0) && fenceTok.length() >= fence.length()) {
                    fence = null;
                    // MD031: a fenced code block should be followed by a blank line.
                    if (i + 1 < lines.length && !stripCr(lines[i + 1]).isBlank()) {
                        add(
                                out,
                                off,
                                directives,
                                new Diagnostic(
                                        lineNo,
                                        1,
                                        fenceTok.length(),
                                        WARNING,
                                        "MD031",
                                        "Fenced code block should be followed by a blank line"));
                    }
                }
                continue; // inside code: no line-level rules
            }
            if (fenceTok != null) {
                fence = fenceTok;
                String info = line.strip().substring(fenceTok.length()).strip();
                if (info.isEmpty()) {
                    add(
                            out,
                            off,
                            directives,
                            new Diagnostic(
                                    lineNo,
                                    1,
                                    fenceTok.length(),
                                    WARNING,
                                    "MD040",
                                    "Fenced code block should specify a language"));
                }
                // MD031: a fenced code block should be preceded by a blank line.
                if (i > 0 && !stripCr(lines[i - 1]).isBlank()) {
                    add(
                            out,
                            off,
                            directives,
                            new Diagnostic(
                                    lineNo,
                                    1,
                                    fenceTok.length(),
                                    WARNING,
                                    "MD031",
                                    "Fenced code block should be preceded by a blank line"));
                }
                continue;
            }

            if (inFront) {
                continue; // don't lint inside front matter
            }

            // MD010: hard tabs (outside code blocks).
            int tab = line.indexOf('\t');
            if (tab >= 0) {
                int run = tab;
                while (run < line.length() && line.charAt(run) == '\t') {
                    run++;
                }
                add(out, off, directives, new Diagnostic(lineNo, tab + 1, run - tab, WARNING, "MD010", "Hard tab"));
            }

            // MD009: trailing whitespace.
            int end = line.length();
            while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
                end--;
            }
            if (end < line.length()) {
                add(
                        out,
                        off,
                        directives,
                        new Diagnostic(lineNo, end + 1, line.length() - end, WARNING, "MD009", "Trailing whitespace"));
            }

            // MD012: multiple consecutive blank lines.
            if (line.isBlank() && i > 0 && stripCr(lines[i - 1]).isBlank()) {
                add(
                        out,
                        off,
                        directives,
                        new Diagnostic(lineNo, 1, 1, WARNING, "MD012", "Multiple consecutive blank lines"));
            }

            int hashes = leadingHashes(line);
            if (hashes > 0) {
                int indent = leadingSpaces(line);
                // MD023: headings must start at the beginning of the line.
                if (indent > 0) {
                    add(
                            out,
                            off,
                            directives,
                            new Diagnostic(
                                    lineNo, 1, indent, WARNING, "MD023", "Heading must start at line beginning"));
                }
                int after = indent + hashes;
                // MD018 / MD019: ATX heading hash spacing.
                if (after < line.length() && line.charAt(after) != ' ' && line.charAt(after) != '\t') {
                    add(
                            out,
                            off,
                            directives,
                            new Diagnostic(
                                    lineNo, indent + 1, hashes, WARNING, "MD018", "No space after heading marker"));
                } else {
                    int spaces = 0;
                    int p = after;
                    while (p < line.length() && line.charAt(p) == ' ') {
                        p++;
                        spaces++;
                    }
                    if (spaces > 1 && p < line.length()) {
                        add(
                                out,
                                off,
                                directives,
                                new Diagnostic(
                                        lineNo,
                                        after + 1,
                                        spaces,
                                        WARNING,
                                        "MD019",
                                        "Multiple spaces after heading marker"));
                    }
                }
                // MD026: trailing punctuation in a heading.
                int punct = trailingPunctuation(line, after);
                if (punct >= 0) {
                    add(
                            out,
                            off,
                            directives,
                            new Diagnostic(lineNo, punct + 1, 1, WARNING, "MD026", "Trailing punctuation in heading"));
                }
                // MD022: a heading should be surrounded by blank lines.
                if (i > 0 && i - 1 != frontMatterEnd && !stripCr(lines[i - 1]).isBlank()) {
                    add(
                            out,
                            off,
                            directives,
                            new Diagnostic(
                                    lineNo,
                                    indent + 1,
                                    hashes,
                                    WARNING,
                                    "MD022",
                                    "Heading should be preceded by a blank line"));
                }
                if (i + 1 < lines.length && !stripCr(lines[i + 1]).isBlank()) {
                    add(
                            out,
                            off,
                            directives,
                            new Diagnostic(
                                    lineNo,
                                    indent + 1,
                                    hashes,
                                    WARNING,
                                    "MD022",
                                    "Heading should be followed by a blank line"));
                }
            }

            // MD034: bare URL used (not inside a link/autolink/ref-def/inline code).
            if (!REF_DEF.matcher(line).find()) {
                Matcher url = BARE_URL.matcher(line);
                while (url.find()) {
                    int s = url.start();
                    char before = s > 0 ? line.charAt(s - 1) : ' ';
                    if (before != '(' && before != '<' && !inInlineCode(line, s)) {
                        add(
                                out,
                                off,
                                directives,
                                new Diagnostic(lineNo, s + 1, url.end() - s, WARNING, "MD034", "Bare URL used"));
                    }
                }
            }

            // Broken full reference links: [text][label] with no matching definition.
            Matcher ref = FULL_REF.matcher(line);
            while (ref.find()) {
                if (!defined.contains(normalizeLabel(ref.group(1)))) {
                    add(
                            out,
                            off,
                            directives,
                            new Diagnostic(
                                    lineNo,
                                    ref.start() + 1,
                                    ref.end() - ref.start(),
                                    WARNING,
                                    "MD052",
                                    "Reference link has no matching definition"));
                }
            }
        }

        // MD001 / MD025: heading structure (the outline skips code + front matter).
        int h1Count = 0;
        int prevLevel = 0;
        for (MarkdownOutline.Heading h : MarkdownOutline.headings(text)) {
            int level = h.level();
            int lineNo = h.line() + 1;
            if (level == 1 && ++h1Count > 1) {
                add(
                        out,
                        off,
                        directives,
                        new Diagnostic(lineNo, 1, 1, WARNING, "MD025", "Multiple top-level headings"));
            }
            if (prevLevel > 0 && level > prevLevel + 1) {
                add(
                        out,
                        off,
                        directives,
                        new Diagnostic(
                                lineNo,
                                1,
                                1,
                                WARNING,
                                "MD001",
                                "Heading levels should increment by one level at a time"));
            }
            prevLevel = level;
        }

        // MD047: file should end with a single trailing newline.
        int lastLine = lines.length; // split("\n",-1): a trailing "\n" yields a final empty element
        if (!text.endsWith("\n")) {
            add(
                    out,
                    off,
                    directives,
                    new Diagnostic(lastLine, 1, 1, WARNING, "MD047", "File should end with a newline"));
        } else if (text.endsWith("\n\n")) {
            add(
                    out,
                    off,
                    directives,
                    new Diagnostic(lastLine, 1, 1, WARNING, "MD047", "File should end with a single newline"));
        }

        out.sort((a, b) ->
                a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.column(), b.column()));
        return out;
    }

    /** Adds {@code d} unless its rule is globally disabled or suppressed by an inline directive on its line. */
    private static void add(List<Diagnostic> out, Set<String> off, MarkdownLintDirectives dir, Diagnostic d) {
        if (off.contains(d.code()) || dir.disabled(d.line() - 1, d.code())) {
            return;
        }
        out.add(d);
    }

    private static void checkFirstLineHeading(
            String[] lines, int frontMatterEnd, Set<String> off, MarkdownLintDirectives dir, List<Diagnostic> out) {
        int start = frontMatterEnd >= 0 ? frontMatterEnd + 1 : 0;
        for (int j = start; j < lines.length; j++) {
            String s = stripCr(lines[j]).strip();
            if (s.isEmpty() || s.startsWith("<!--")) {
                continue;
            }
            if (leadingHashes(stripCr(lines[j])) != 1) {
                add(
                        out,
                        off,
                        dir,
                        new Diagnostic(
                                j + 1, 1, 1, WARNING, "MD041", "First line in a file should be a top-level heading"));
            }
            return; // only the first content line matters
        }
    }

    /** The 0-based index of trailing punctuation in an ATX heading whose marker ends at {@code after}, else -1. */
    static int trailingPunctuation(String line, int after) {
        int e = line.length();
        while (e > after && (line.charAt(e - 1) == ' ' || line.charAt(e - 1) == '\t')) {
            e--;
        }
        // Drop a closing ATX hash run ("## Title ##").
        int hashEnd = e;
        while (hashEnd > after && line.charAt(hashEnd - 1) == '#') {
            hashEnd--;
        }
        if (hashEnd < e) { // there was a closing run; trim the space before it
            e = hashEnd;
            while (e > after && (line.charAt(e - 1) == ' ' || line.charAt(e - 1) == '\t')) {
                e--;
            }
        }
        if (e <= after) {
            return -1; // empty heading text
        }
        char last = line.charAt(e - 1);
        return HEADING_PUNCTUATION.indexOf(last) >= 0 ? e - 1 : -1;
    }

    /** Whether the character at {@code idx} falls inside an inline-code span (odd number of backticks before). */
    private static boolean inInlineCode(String line, int idx) {
        int ticks = 0;
        for (int i = 0; i < idx && i < line.length(); i++) {
            if (line.charAt(i) == '`') {
                ticks++;
            }
        }
        return (ticks & 1) == 1;
    }

    private static String normalizeLabel(String label) {
        return label == null ? "" : label.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Count of leading {@code #} (1–6) if {@code line} is an ATX heading marker, else 0. */
    static int leadingHashes(String line) {
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

    static String fenceToken(String line) {
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

    static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    static String stripCr(String line) {
        return !line.isEmpty() && line.charAt(line.length() - 1) == '\r' ? line.substring(0, line.length() - 1) : line;
    }
}
