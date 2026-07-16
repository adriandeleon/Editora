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
 * <p>Honors the global {@code disabled} set and inline {@link MarkdownLintDirectives}, and skips fenced
 * code blocks + a leading YAML front matter block (as the linter does). Idempotent: a second pass is a
 * no-op. Stateless and toolkit-free — unit-tested directly.
 */
public final class MarkdownLintFix {

    private MarkdownLintFix() {}

    /** Returns {@code text} with the fixable issues corrected (or unchanged when there is nothing to fix). */
    public static String fix(String text, Set<String> disabled, int spacesPerTab) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Set<String> off = disabled == null ? Set.of() : disabled;
        int tabWidth = Math.max(1, spacesPerTab);
        String[] lines = text.split("\n", -1);
        MarkdownLintDirectives dir = MarkdownLintDirectives.compute(lines);

        int frontMatterEnd = -1;
        if (lines.length > 0 && lines[0].strip().equals("---")) {
            for (int j = 1; j < lines.length; j++) {
                if (lines[j].strip().equals("---")) {
                    frontMatterEnd = j;
                    break;
                }
            }
        }

        List<String> outLines = new ArrayList<>(lines.length);
        String fence = null;
        boolean prevBlank = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            boolean cr = !raw.isEmpty() && raw.charAt(raw.length() - 1) == '\r';
            String line = MarkdownLint.stripCr(raw);
            boolean inFront = frontMatterEnd >= 0 && i <= frontMatterEnd;

            String fenceTok = MarkdownLint.fenceToken(line);
            boolean inCode = fence != null;
            if (inCode) {
                if (fenceTok != null && fenceTok.charAt(0) == fence.charAt(0) && fenceTok.length() >= fence.length()) {
                    fence = null;
                }
            } else if (fenceTok != null) {
                fence = fenceTok;
            }

            if (inCode || fenceTok != null || inFront) {
                outLines.add(raw); // verbatim inside code / front matter
                prevBlank = line.isBlank();
                continue;
            }

            String fixed = line;
            if (enabled(off, dir, i, "MD010")) {
                fixed = fixed.replace("\t", " ".repeat(tabWidth));
            }
            int hashes = MarkdownLint.leadingHashes(fixed);
            if (hashes > 0) {
                int indent = MarkdownLint.leadingSpaces(fixed);
                if (indent > 0 && enabled(off, dir, i, "MD023")) {
                    fixed = fixed.substring(indent);
                    indent = 0;
                }
                int after = indent + hashes;
                if (after < fixed.length()
                        && fixed.charAt(after) != ' '
                        && fixed.charAt(after) != '\t'
                        && enabled(off, dir, i, "MD018")) {
                    fixed = fixed.substring(0, after) + " " + fixed.substring(after);
                } else if (enabled(off, dir, i, "MD019")) {
                    int p = after;
                    while (p < fixed.length() && fixed.charAt(p) == ' ') {
                        p++;
                    }
                    if (p - after > 1 && p < fixed.length()) {
                        fixed = fixed.substring(0, after) + " " + fixed.substring(p);
                    }
                }
                if (enabled(off, dir, i, "MD026")) {
                    int hb = MarkdownLint.leadingSpaces(fixed) + MarkdownLint.leadingHashes(fixed);
                    int punct = MarkdownLint.trailingPunctuation(fixed, hb);
                    if (punct >= 0) {
                        fixed = fixed.substring(0, punct) + fixed.substring(punct + 1);
                    }
                }
            }
            if (enabled(off, dir, i, "MD009")) {
                fixed = stripTrailing(fixed, lines, i);
            }

            if (fixed.isBlank()) {
                if (prevBlank && enabled(off, dir, i, "MD012")) {
                    continue; // collapse the run
                }
                outLines.add(cr ? "\r" : "");
                prevBlank = true;
                continue;
            }
            outLines.add(cr ? fixed + "\r" : fixed);
            prevBlank = false;
        }

        String result = String.join("\n", outLines);
        if (enabled(off, dir, Math.max(0, lines.length - 1), "MD047")) {
            int e = result.length();
            while (e > 0 && (result.charAt(e - 1) == '\n' || result.charAt(e - 1) == '\r')) {
                e--;
            }
            result = e == 0 ? "" : result.substring(0, e) + "\n";
        }
        return result;
    }

    private static boolean enabled(Set<String> off, MarkdownLintDirectives dir, int line, String code) {
        return !off.contains(code) && !dir.disabled(line, code);
    }

    /** Strips trailing whitespace, but never a hard line break (see {@link MarkdownLint#isHardLineBreak}). */
    private static String stripTrailing(String line, String[] lines, int i) {
        int e = line.length();
        while (e > 0 && (line.charAt(e - 1) == ' ' || line.charAt(e - 1) == '\t')) {
            e--;
        }
        if (e == line.length() || MarkdownLint.isHardLineBreak(line, e, lines, i)) {
            return line;
        }
        return line.substring(0, e);
    }
}
