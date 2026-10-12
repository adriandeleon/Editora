package com.editora.markdown;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses inline {@code markdownlint} control comments and resolves, per line, which rules are
 * suppressed — so both {@link MarkdownLint} (skip emitting) and {@link MarkdownLintFix} (skip fixing)
 * honor the same directives. Recognized HTML-comment forms (markdownlint-compatible):
 *
 * <ul>
 *   <li>{@code <!-- markdownlint-disable [rule ...] -->} — from this line onward (all rules, or the listed)
 *   <li>{@code <!-- markdownlint-enable [rule ...] -->} — re-enable (all, or the listed)
 *   <li>{@code <!-- markdownlint-disable-line [rule ...] -->} — this line only
 *   <li>{@code <!-- markdownlint-disable-next-line [rule ...] -->} — the following line only
 *   <li>{@code <!-- markdownlint-capture -->} / {@code <!-- markdownlint-restore -->} — remember the current
 *       state / go back to it (to the file's initial state when nothing was captured)
 *   <li>{@code <!-- markdownlint-disable-file [rule ...] -->} / {@code -enable-file} — the whole file, wherever
 *       the comment sits
 *   <li>{@code <!-- markdownlint-configure-file { "MD009": false } -->} — a config object for the whole file
 * </ul>
 *
 * <p>A rule is named by its code ({@code MD009}), an alias ({@code no-trailing-spaces}) or a tag
 * ({@code whitespace}) — see {@link MarkdownLint#resolve}. A name this linter does not implement
 * ({@code no-inline-html}) changes nothing; only a directive with <em>no</em> name means "every rule".
 * Directives inside a code block are examples, not directives.
 *
 * <p>Pure and toolkit-free — unit-tested directly.
 */
final class MarkdownLintDirectives {

    private static final Pattern DIRECTIVE = Pattern.compile(
            "<!--\\s*markdownlint-(disable-next-line|disable-line|disable-file|disable|enable-file|enable"
                    + "|configure-file|capture|restore)(?:\\s+(.*?))?\\s*-->",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECTIVE_LINE =
            Pattern.compile("^\\s*(?:<!--\\s*markdownlint-[^>]*-->\\s*)+$", Pattern.CASE_INSENSITIVE);

    private final Set<String>[] perLine;
    /** The {@code configure-file} objects of the document, in order (their rule options refine the project's). */
    private final java.util.List<MarkdownLintConfig.Parsed> configured = new java.util.ArrayList<>();

    @SuppressWarnings("unchecked")
    private MarkdownLintDirectives(int lineCount) {
        perLine = new Set[Math.max(0, lineCount)];
    }

    /** Whether {@code content} holds nothing but markdownlint directive comments. */
    static boolean isDirectiveLine(String content) {
        return content.indexOf("markdownlint-") >= 0
                && DIRECTIVE_LINE.matcher(content).matches();
    }

    /** Builds the per-line suppression table for {@code lines} (already CR-stripped is fine; not required). */
    static MarkdownLintDirectives compute(String[] lines) {
        return compute(MarkdownBlocks.scan(lines));
    }

    /** Builds the per-line suppression table for an already scanned document. */
    static MarkdownLintDirectives compute(MarkdownBlocks blocks) {
        int n = blocks.lineCount();
        MarkdownLintDirectives d = new MarkdownLintDirectives(n);

        // File-scope directives first: they apply from line 1 wherever they are written.
        Set<String> initial = new HashSet<>();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (blocks.isCode(i) || blocks.line(i).indexOf("markdownlint-") < 0) {
                continue;
            }
            any = true;
            Matcher m = DIRECTIVE.matcher(blocks.line(i));
            while (m.find()) {
                switch (m.group(1).toLowerCase(Locale.ROOT)) {
                    case "disable-file" -> initial.addAll(codes(m.group(2)));
                    case "enable-file" -> initial.removeAll(codes(m.group(2)));
                    case "configure-file" -> {
                        MarkdownLintConfig.Parsed parsed = MarkdownLintConfig.parse(m.group(2));
                        MarkdownLintConfig.apply(parsed, initial);
                        d.configured.add(parsed);
                    }
                    default -> {}
                }
            }
        }

        // Sets are shared between lines and replaced (never mutated) when a directive changes them.
        Set<String> region = Set.copyOf(initial);
        if (!any) {
            java.util.Arrays.fill(d.perLine, region);
            return d;
        }
        Set<String> captured = region;
        Set<String> nextLine = null; // disables queued for the next line by disable-next-line
        for (int i = 0; i < n; i++) {
            Set<String> eff = region;
            if (nextLine != null) {
                eff = plus(eff, nextLine);
                nextLine = null;
            }
            String line = blocks.line(i);
            if (!blocks.isCode(i) && line.indexOf("markdownlint-") >= 0) {
                Matcher m = DIRECTIVE.matcher(line);
                while (m.find()) {
                    Set<String> codes = codes(m.group(2));
                    switch (m.group(1).toLowerCase(Locale.ROOT)) {
                        case "disable" -> {
                            region = plus(region, codes);
                            eff = plus(eff, codes);
                        }
                        case "enable" -> {
                            region = minus(region, codes);
                            eff = minus(eff, codes);
                        }
                        case "disable-line" -> eff = plus(eff, codes);
                        case "disable-next-line" -> nextLine = codes;
                        case "capture" -> captured = region;
                        case "restore" -> {
                            region = captured;
                            eff = captured;
                        }
                        default -> {
                            // file-scope forms: handled above
                        }
                    }
                }
            }
            d.perLine[i] = eff;
        }
        return d;
    }

    /** {@code base} refined by the rule options of the document's {@code configure-file} comments. */
    MarkdownLintOptions options(MarkdownLintOptions base) {
        MarkdownLintOptions out = base;
        for (MarkdownLintConfig.Parsed parsed : configured) {
            out = MarkdownLintConfig.options(parsed, out);
        }
        return out;
    }

    /** Whether {@code code} is suppressed on the 0-based {@code line}. */
    boolean disabled(int line, String code) {
        if (line < 0 || line >= perLine.length) {
            return false;
        }
        Set<String> set = perLine[line];
        return set != null && set.contains(code);
    }

    /** The rules a directive's argument list names: every rule when it names none at all. */
    private static Set<String> codes(String tail) {
        if (tail == null || tail.isBlank()) {
            return MarkdownLint.CODES;
        }
        Set<String> out = new HashSet<>();
        for (String name : tail.strip().split("[\\s,]+")) {
            out.addAll(MarkdownLint.resolve(name));
        }
        return out;
    }

    private static Set<String> plus(Set<String> base, Set<String> more) {
        if (base.containsAll(more)) {
            return base;
        }
        Set<String> out = new HashSet<>(base);
        out.addAll(more);
        return out;
    }

    private static Set<String> minus(Set<String> base, Set<String> less) {
        Set<String> out = new HashSet<>(base);
        return out.removeAll(less) ? out : base;
    }
}
