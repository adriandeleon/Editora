package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure auto-fixer for the safely-mechanical {@link MarkdownLint} rules — the markdownlint {@code --fix}
 * subset that can be applied in place without restructuring the document: MD009 (trailing whitespace),
 * MD010 (hard tabs → spaces), MD012 (collapse blank-line runs), MD018/MD019 (heading hash spacing),
 * MD023 (de-indent headings), MD026 (strip heading trailing punctuation), and MD047 (single final
 * newline). Structural rules (MD001/MD022/MD025/MD031/MD034/MD040/MD041/MD052) are <b>not</b> auto-fixed.
 *
 * <p>Honors the global {@code disabled} set and inline {@link MarkdownLintDirectives}. Only prose changes:
 * every line {@link MarkdownBlocks} classifies as code (fenced at any nesting, or indented), front matter (YAML
 * or TOML), an HTML comment or a {@code <pre>}-style block is copied through untouched. Line endings are kept
 * (a CRLF document stays CRLF). Idempotent: a second pass is a no-op. Stateless and toolkit-free — unit-tested
 * directly.
 */
public final class MarkdownLintFix {

    private MarkdownLintFix() {}

    /** Which issues a pass may fix: asked with the 0-based line and the rule code. */
    @FunctionalInterface
    public interface Scope {
        boolean includes(int line, String code);

        /** Every fixable issue of the document. */
        Scope ALL = (line, code) -> true;

        /** Only the issue {@code d} (its rule, on its line). */
        static Scope only(MarkdownLint.Diagnostic d) {
            return (line, code) -> line == d.line() - 1 && code.equals(d.code());
        }
    }

    /**
     * A fixed document.
     *
     * @param text the new text (the input itself when nothing changed)
     * @param lineMap for each 0-based line of the input, the line of {@code text} it became (a dropped blank
     *     line maps to the blank line that was kept)
     */
    public record Result(String text, int[] lineMap) {
        /** The line of {@link #text} that input line {@code line} became, clamped to the document. */
        public int mapLine(int line) {
            if (lineMap.length == 0) {
                return 0;
            }
            return lineMap[Math.max(0, Math.min(line, lineMap.length - 1))];
        }
    }

    /** Returns {@code text} with the fixable issues corrected (or unchanged when there is nothing to fix). */
    public static String fix(String text, Set<String> disabled, int spacesPerTab) {
        return fix(text, disabled, spacesPerTab, Scope.ALL).text();
    }

    /** As {@link #fix(String, Set, int)}, limited to the issues in {@code scope}. */
    public static Result fix(String text, Set<String> disabled, int spacesPerTab, Scope scope) {
        return fix(text, disabled, spacesPerTab, scope, MarkdownLintOptions.DEFAULT);
    }

    /**
     * As {@link #fix(String, Set, int, Scope)}, with the project's rule {@code options} — the same ones the
     * linter was given, so the two agree on what an issue is.
     */
    public static Result fix(
            String text, Set<String> disabled, int spacesPerTab, Scope scope, MarkdownLintOptions options) {
        if (text == null || text.isEmpty()) {
            return new Result(text, new int[0]);
        }
        Set<String> off = disabled == null ? Set.of() : disabled;
        String[] lines = text.split("\n", -1);
        MarkdownBlocks blocks = MarkdownBlocks.scan(lines);
        MarkdownLintDirectives dir = MarkdownLintDirectives.compute(blocks);
        MarkdownLintOptions opts = dir.options(options == null ? MarkdownLintOptions.DEFAULT : options);
        int tabWidth = Math.max(1, opts.spacesPerTab() > 0 ? opts.spacesPerTab() : spacesPerTab);
        Gate gate = new Gate(off, dir, scope == null ? Scope.ALL : scope);
        boolean crlf = lines.length > 1 && lines[0].endsWith("\r");

        List<String> outLines = new ArrayList<>(lines.length);
        int[] lineMap = new int[lines.length];
        int blankRun = 0; // blank prose lines kept in a row so far
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            boolean cr = !raw.isEmpty() && raw.charAt(raw.length() - 1) == '\r';
            String line = blocks.line(i);

            if (!blocks.isText(i)) {
                lineMap[i] = outLines.size();
                outLines.add(raw); // verbatim inside code / front matter / raw HTML
                blankRun = 0; // a blank line that belongs to a code block is content, not spacing
                continue;
            }

            String fixed = line;
            int off0 = blocks.contentStart(i);
            if (gate.on(i, "MD010") && line.indexOf('\t') >= 0 && tabReplacementKeepsBlock(line, tabWidth)) {
                String spaces = " ".repeat(tabWidth);
                String prefix = line.substring(0, off0).replace("\t", spaces);
                fixed = prefix + line.substring(off0).replace("\t", spaces);
                off0 = prefix.length();
            }
            // Heading-ness is decided on the ORIGINAL line: a tab-indented `\t# install deps` is indented code,
            // and must not turn into a heading because this pass rewrote its indentation.
            int hashes = MarkdownLint.headingHashes(blocks, i);
            if (hashes > 0) {
                fixed = fixed.substring(0, off0) + fixHeading(fixed.substring(off0), hashes, gate, i, opts);
            }
            if (gate.on(i, "MD009")) {
                fixed = stripTrailing(fixed, lines, i, opts.brSpaces());
            }

            if (fixed.isBlank()) {
                boolean phantom = i == 1 && lines.length == 2; // the empty element after a lone "\n"
                if (blankRun >= opts.maxBlankLines() && line.isBlank() && !phantom && gate.on(i, "MD012")) {
                    lineMap[i] = Math.max(0, outLines.size() - 1);
                    continue; // collapse the run
                }
                lineMap[i] = outLines.size();
                outLines.add(cr ? fixed + "\r" : fixed);
                blankRun++;
                continue;
            }
            lineMap[i] = outLines.size();
            outLines.add(cr ? fixed + "\r" : fixed);
            blankRun = 0;
        }

        String result = String.join("\n", outLines);
        if (gate.on(Math.max(0, lines.length - 1), "MD047")) {
            int e = result.length();
            while (e > 0 && (result.charAt(e - 1) == '\n' || result.charAt(e - 1) == '\r')) {
                e--;
            }
            if (e > 0) {
                result = result.substring(0, e) + (crlf ? "\r\n" : "\n");
            } else if (result.indexOf('\n') != result.lastIndexOf('\n')) {
                result = ""; // nothing but blank lines (a lone newline is left as it is)
            }
        }
        if (result.equals(text)) {
            return new Result(text, lineMap);
        }
        int last = Math.max(0, result.split("\n", -1).length - 1);
        for (int i = 0; i < lineMap.length; i++) {
            lineMap[i] = Math.min(lineMap[i], last);
        }
        return new Result(result, lineMap);
    }

    /**
     * Returns {@code text} with rule {@code code} suppressed on 0-based line {@code line}: a
     * {@code <!-- markdownlint-disable-next-line CODE -->} comment is inserted above it, inside the same block
     * quote / list indentation — or {@code code} is added to such a comment when one already sits there (two
     * stacked comments would each apply to the other, not to the line).
     */
    public static Result disableForLine(String text, int line, String code) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        int at = Math.max(0, Math.min(line, lines.length - 1));
        int[] lineMap = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            lineMap[i] = i;
        }
        boolean crlf = lines.length > 1 && lines[0].endsWith("\r");
        String above = at > 0 ? MarkdownLint.stripCr(lines[at - 1]) : "";
        List<String> out = new ArrayList<>(List.of(lines));
        if (MarkdownLintDirectives.isDirectiveLine(above) && above.contains("markdownlint-disable-next-line")) {
            int close = above.lastIndexOf("-->");
            String names = above.substring(above.indexOf("disable-next-line") + "disable-next-line".length(), close)
                    .strip();
            if (names.isEmpty() || java.util.Arrays.asList(names.split("\\s+")).contains(code)) {
                return new Result(text, lineMap); // already covers it
            }
            String merged = above.substring(0, close).stripTrailing() + " " + code + " -->";
            out.set(at - 1, lines[at - 1].endsWith("\r") ? merged + "\r" : merged);
        } else {
            String target = MarkdownLint.stripCr(lines[at]);
            int start = Math.min(MarkdownBlocks.scan(lines).contentStart(at), target.length());
            // The line's own container prefix: block-quote markers and indentation, up to a list marker (a
            // comment for the line that opens an item goes before the item, not into it).
            StringBuilder prefix = new StringBuilder();
            for (int p = 0; p < start; p++) {
                char c = target.charAt(p);
                if (c != '>' && c != ' ' && c != '\t') {
                    break;
                }
                prefix.append(c);
            }
            String comment = prefix + "<!-- markdownlint-disable-next-line " + code + " -->";
            out.add(at, crlf ? comment + "\r" : comment);
            for (int i = at; i < lines.length; i++) {
                lineMap[i] = i + 1;
            }
        }
        return new Result(String.join("\n", out), lineMap);
    }

    /** The heading fixes (MD023, MD018/MD019, MD026) on a heading's text inside its container. */
    private static String fixHeading(String body, int hashes, Gate gate, int i, MarkdownLintOptions opts) {
        String fixed = body;
        int indent = MarkdownLint.leadingSpaces(fixed);
        if (indent > 0 && gate.on(i, "MD023")) {
            fixed = fixed.substring(indent);
            indent = 0;
        }
        int after = indent + hashes;
        if (after < fixed.length() && fixed.charAt(after) != ' ' && fixed.charAt(after) != '\t') {
            if (gate.on(i, "MD018")) {
                fixed = fixed.substring(0, after) + " " + fixed.substring(after);
            }
        } else if (gate.on(i, "MD019")) {
            int p = after;
            while (p < fixed.length() && fixed.charAt(p) == ' ') {
                p++;
            }
            if (p - after > 1 && p < fixed.length()) {
                fixed = fixed.substring(0, after) + " " + fixed.substring(p);
            }
        }
        if (gate.on(i, "MD026")) {
            int hb = MarkdownLint.leadingSpaces(fixed) + MarkdownLint.leadingHashes(fixed);
            // Strip the whole run (`# Wait...` → `# Wait`), not one character per pass.
            for (int punct = MarkdownLint.trailingPunctuation(fixed, hb, opts.punctuation());
                    punct >= 0;
                    punct = MarkdownLint.trailingPunctuation(fixed, hb, opts.punctuation())) {
                fixed = fixed.substring(0, punct) + fixed.substring(punct + 1);
            }
        }
        return fixed;
    }

    /**
     * Whether replacing {@code line}'s tabs with {@code tabWidth} spaces leaves its Markdown block structure
     * alone. Markdown measures indentation with 4-column tab stops, and 4+ columns means indented code (or a
     * nested list level), so a line indented that far is rewritten only when the spaces land on the same
     * column. With a tab size of 2, {@code \t# install deps} would otherwise become a 2-space-indented
     * heading; such lines are left as they are.
     */
    static boolean tabReplacementKeepsBlock(String line, int tabWidth) {
        int cols = 0;
        int spaces = 0;
        int i = 0;
        for (; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                cols += 4 - cols % 4;
                spaces += tabWidth;
            } else if (c == ' ') {
                cols++;
                spaces++;
            } else {
                break;
            }
        }
        return cols < 4 || cols == spaces;
    }

    /** Whether a rule may be fixed on a line: not switched off, not suppressed inline, and in scope. */
    private record Gate(Set<String> off, MarkdownLintDirectives dir, Scope scope) {
        boolean on(int line, String code) {
            return !off.contains(code) && !dir.disabled(line, code) && scope.includes(line, code);
        }
    }

    /** Strips trailing whitespace, but never a hard line break (see {@link MarkdownLint#isHardLineBreak}). */
    private static String stripTrailing(String line, String[] lines, int i, int brSpaces) {
        int e = line.length();
        while (e > 0 && (line.charAt(e - 1) == ' ' || line.charAt(e - 1) == '\t')) {
            e--;
        }
        if (e == line.length() || MarkdownLint.isHardLineBreak(line, e, lines, i, brSpaces)) {
            return line;
        }
        return line.substring(0, e);
    }
}
