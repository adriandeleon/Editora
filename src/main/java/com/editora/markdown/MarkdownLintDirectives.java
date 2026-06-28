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
 *   <li>{@code <!-- markdownlint-disable [MDxxx ...] -->} — from this line onward (all rules, or listed)
 *   <li>{@code <!-- markdownlint-enable [MDxxx ...] -->} — re-enable (all, or listed)
 *   <li>{@code <!-- markdownlint-disable-line [MDxxx ...] -->} — this line only
 *   <li>{@code <!-- markdownlint-disable-next-line [MDxxx ...] -->} — the following line only
 * </ul>
 *
 * <p>Pure and toolkit-free — unit-tested directly. {@link #ALL} is the wildcard token used internally
 * for a code-less {@code disable}/{@code enable}. Selectively re-enabling a single rule after a
 * code-less {@code disable} (which sets {@link #ALL}) is a no-op — a documented v1 limitation.
 */
final class MarkdownLintDirectives {

    /** Wildcard standing for "every rule" in the per-line disabled sets. */
    static final String ALL = "*";

    private static final Pattern DIRECTIVE = Pattern.compile(
            "<!--\\s*markdownlint-(disable-next-line|disable-line|disable|enable)([^>]*?)-->",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE = Pattern.compile("MD\\d+", Pattern.CASE_INSENSITIVE);

    private final Set<String>[] perLine;

    @SuppressWarnings("unchecked")
    private MarkdownLintDirectives(int lineCount) {
        perLine = new Set[Math.max(0, lineCount)];
    }

    /** Builds the per-line suppression table for {@code lines} (already CR-stripped is fine; not required). */
    static MarkdownLintDirectives compute(String[] lines) {
        MarkdownLintDirectives d = new MarkdownLintDirectives(lines.length);
        Set<String> region = new HashSet<>(); // active region disables (codes or ALL)
        Set<String> nextLine = null; // disables queued for the next line by disable-next-line
        for (int i = 0; i < lines.length; i++) {
            Set<String> eff = new HashSet<>(region);
            if (nextLine != null) {
                eff.addAll(nextLine);
                nextLine = null;
            }
            Matcher m = DIRECTIVE.matcher(lines[i] == null ? "" : lines[i]);
            while (m.find()) {
                String kind = m.group(1).toLowerCase(Locale.ROOT);
                Set<String> codes = codes(m.group(2));
                switch (kind) {
                    case "disable" -> {
                        region.addAll(codes);
                        eff.addAll(codes);
                    }
                    case "enable" -> {
                        if (codes.contains(ALL)) {
                            region.clear();
                            eff.clear();
                        } else {
                            region.removeAll(codes);
                            eff.removeAll(codes);
                        }
                    }
                    case "disable-line" -> eff.addAll(codes);
                    case "disable-next-line" -> nextLine = codes;
                    default -> {
                        // unreachable
                    }
                }
            }
            d.perLine[i] = eff;
        }
        return d;
    }

    /** Whether {@code code} is suppressed on the 0-based {@code line}. */
    boolean disabled(int line, String code) {
        if (line < 0 || line >= perLine.length) {
            return false;
        }
        Set<String> set = perLine[line];
        return set != null && (set.contains(ALL) || set.contains(code));
    }

    private static Set<String> codes(String tail) {
        Set<String> out = new HashSet<>();
        if (tail != null) {
            Matcher m = CODE.matcher(tail);
            while (m.find()) {
                out.add(m.group().toUpperCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? Set.of(ALL) : out;
    }
}
