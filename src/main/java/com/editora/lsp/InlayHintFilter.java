package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

import com.editora.lsp.LspManager.InlayHintSpan;

/**
 * Client-side filtering of LSP inlay hints (#823) — pure, so it is unit-testable without a server.
 *
 * <p>A server hands back a hint for <em>every</em> parameter of every call, whether or not the name
 * explains anything: {@code System.out.println("Hello")} yields {@code x:}, because the JDK declares
 * {@code PrintStream.println(String x)}. Those cost a run of trailing grey text and tell the reader
 * nothing, and they are the common case rather than an edge case — a large share of JDK and library
 * signatures use single-letter or positional parameter names (and class files compiled without
 * {@code -parameters} report {@code arg0}, {@code arg1}, …).
 *
 * <p>Two tiers, mirroring what VS Code's Java extension does:
 *
 * <ul>
 *   <li><b>Always</b>: drop a parameter hint whose name is uninformative ({@link #uninformativeName})
 *       or merely repeats the argument it labels ({@code foo(name: name)}).
 *   <li><b>{@link Mode#LITERALS}</b> (the default): additionally keep a parameter hint only when its
 *       argument is a literal, which is where a parameter name genuinely disambiguates — an unlabelled
 *       {@code true} or {@code 0} is what a reader actually cannot decode. {@link Mode#ALL} keeps the rest.
 * </ul>
 *
 * <p><b>Type hints are never filtered.</b> Both rules above are about naming an argument; a
 * {@code : String} after a {@code var} declaration is information the source does not otherwise carry,
 * so it survives every mode. That is why {@link InlayHintSpan} carries the server's
 * {@code InlayHintKind} at all.
 */
public final class InlayHintFilter {

    /** How aggressively parameter-name hints are suppressed. */
    public enum Mode {
        /** Keep every parameter hint that survives the always-on rules. */
        ALL("all"),
        /** Keep a parameter hint only when it labels a literal argument (the default). */
        LITERALS("literals");

        private final String id;

        Mode(String id) {
            this.id = id;
        }

        /** The persisted id ({@code Settings.inlayHintMode}). */
        public String id() {
            return id;
        }

        /** The mode for a persisted id; unknown/null falls back to {@link #LITERALS}. */
        public static Mode of(String id) {
            for (Mode m : values()) {
                if (m.id.equalsIgnoreCase(id)) {
                    return m;
                }
            }
            return LITERALS;
        }
    }

    private InlayHintFilter() {}

    /**
     * The spans worth showing. {@code lineText} supplies a 0-based line's text (empty when out of range)
     * so the argument at a hint's column can be classified.
     */
    public static List<InlayHintSpan> filter(List<InlayHintSpan> spans, Mode mode, IntFunction<String> lineText) {
        if (spans == null || spans.isEmpty()) {
            return List.of();
        }
        List<InlayHintSpan> out = new ArrayList<>(spans.size());
        for (InlayHintSpan s : spans) {
            if (s != null && keep(s, mode, lineText.apply(s.line()))) {
                out.add(s);
            }
        }
        return out;
    }

    /** Whether one hint survives, given the text of the line it sits on. */
    static boolean keep(InlayHintSpan span, Mode mode, String line) {
        if (!span.parameter()) {
            return true; // type hints are never filtered
        }
        String name = parameterName(span.label());
        if (name.isEmpty() || uninformativeName(name)) {
            return false;
        }
        String argument = argumentAt(line, span.col());
        if (repeatsArgument(name, argument)) {
            return false;
        }
        return mode != Mode.LITERALS || isLiteral(argument);
    }

    /**
     * The bare parameter name inside a hint label — {@code "x:"} and {@code "x ="} both yield {@code "x"}.
     * Servers differ on the separator, and some pad the label, so both are stripped.
     */
    static String parameterName(String label) {
        if (label == null) {
            return "";
        }
        String s = label.strip();
        while (!s.isEmpty() && (s.endsWith(":") || s.endsWith("=") || s.endsWith(" "))) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    /**
     * Whether a parameter name explains nothing: a single character ({@code x}, {@code s}), or a
     * positional placeholder ({@code arg0}, {@code param1}, {@code p2}) — what a class file compiled
     * without {@code -parameters} reports. Two letters is the floor for "might be meaningful", since
     * {@code id}, {@code to} and {@code on} all are.
     */
    static boolean uninformativeName(String name) {
        if (name.length() <= 1) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String stem = lower.startsWith("param")
                ? lower.substring(5)
                : lower.startsWith("arg") ? lower.substring(3) : lower.startsWith("p") ? lower.substring(1) : null;
        return stem != null && !stem.isEmpty() && stem.chars().allMatch(Character::isDigit);
    }

    /**
     * Whether the hint merely restates its argument — {@code foo(name: name)}, and the equally redundant
     * {@code foo(name: this.name)} / {@code foo(name: user.name)}, so the trailing identifier is what is
     * compared. Case-insensitive: {@code setColor(color: COLOR)} is no more informative.
     */
    static boolean repeatsArgument(String name, String argument) {
        if (argument.isEmpty()) {
            return false;
        }
        int cut = -1;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (!Character.isJavaIdentifierPart(c) && c != '.') {
                break;
            }
            if (c == '.') {
                cut = i;
            }
        }
        String tail = argument.substring(cut + 1);
        int end = 0;
        while (end < tail.length() && Character.isJavaIdentifierPart(tail.charAt(end))) {
            end++;
        }
        return tail.substring(0, end).equalsIgnoreCase(name);
    }

    /**
     * The argument text a hint labels: what follows the hint's column on its line, leading whitespace
     * skipped. Empty when the column is out of range — the caller then treats the hint as unclassifiable,
     * which in {@link Mode#LITERALS} means dropping it rather than guessing.
     */
    static String argumentAt(String line, int col) {
        if (line == null || col < 0 || col >= line.length()) {
            return "";
        }
        int i = col;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(i);
    }

    /**
     * Whether an argument is a literal — a string/char, a (possibly signed) number, or one of the
     * keyword literals. This is where the parameter name earns its space: {@code copy(overwrite: true)}
     * decodes a bare {@code true} that the call site otherwise leaves unexplained.
     */
    static boolean isLiteral(String argument) {
        if (argument.isEmpty()) {
            return false;
        }
        char c = argument.charAt(0);
        if (c == '"' || c == '\'') {
            return true;
        }
        if (Character.isDigit(c)) {
            return true;
        }
        if ((c == '-' || c == '+') && argument.length() > 1) {
            char next = argument.charAt(1);
            return Character.isDigit(next)
                    || (next == '.' && argument.length() > 2 && Character.isDigit(argument.charAt(2)));
        }
        if (c == '.' && argument.length() > 1 && Character.isDigit(argument.charAt(1))) {
            return true; // .5f
        }
        return startsWithKeyword(argument, "true")
                || startsWithKeyword(argument, "false")
                || startsWithKeyword(argument, "null");
    }

    /** Whether {@code s} begins with {@code keyword} as a whole word (so {@code nullable} does not match). */
    private static boolean startsWithKeyword(String s, String keyword) {
        return s.startsWith(keyword)
                && (s.length() == keyword.length() || !Character.isJavaIdentifierPart(s.charAt(keyword.length())));
    }
}
