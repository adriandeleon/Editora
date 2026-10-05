package com.editora.editor;

import java.util.Set;
import java.util.function.IntFunction;

/**
 * Which lines the debugger's inline values belong on. The suspended frame's variables are matched against
 * line text by name, and a name means something else in another function: stopped in {@code work(n)}, the
 * {@code x} and {@code n} of an unrelated {@code other()} further down are not the frame's. So a line is
 * annotated only when it sits in the same function as the frame's line.
 *
 * <p>"Same function" is decided from the text alone — indentation, which Java, Python and JavaScript sources
 * all carry — so it needs no language server and costs nothing off the visible lines: {@link #headerOf} walks
 * up from a line through ever smaller indentation, past control-flow blocks, to the line that opens the
 * enclosing function, class or lambda. Two lines are in the same scope when they share that header. It is a
 * heuristic and errs towards fewer annotations, never towards a value on a line it does not belong to.
 * Pure — unit-tested.
 */
final class InlineValueScope {

    /** No enclosing header: top-level code. */
    static final int TOP_LEVEL = -1;

    /** The header was not found within {@link #MAX_WALK} lines; the scope of such a line is not known. */
    static final int UNKNOWN = -2;

    static final int MAX_WALK = 800;

    /** Block openers that do not start a new variable scope worth telling apart. */
    private static final Set<String> CONTROL = Set.of(
            "if",
            "else",
            "elif",
            "for",
            "foreach",
            "while",
            "do",
            "switch",
            "case",
            "default",
            "try",
            "catch",
            "except",
            "finally",
            "with",
            "synchronized",
            "match",
            "when",
            "static");

    private InlineValueScope() {}

    /** Whether inline values of a frame stopped on {@code frameLine} belong on {@code line}. */
    static boolean sameScope(IntFunction<String> lineAt, int frameLine, int line) {
        if (frameLine < 0) {
            return true; // the frame's line is not known: no scope to hold a line against
        }
        int frameHeader = headerOf(lineAt, frameLine);
        return frameHeader == UNKNOWN || line == frameHeader || headerOf(lineAt, line) == frameHeader;
    }

    /**
     * The index of the line opening the function / class / lambda that encloses {@code line}, {@link
     * #TOP_LEVEL}, or {@link #UNKNOWN}. {@code lineAt} returns a line's text ({@code ""} for one to ignore, such
     * as a line inside a comment or a multi-line string).
     */
    static int headerOf(IntFunction<String> lineAt, int line) {
        String own = lineAt.apply(line);
        if (own == null || own.isBlank()) {
            return UNKNOWN;
        }
        int min = indent(own);
        for (int i = line - 1, steps = 0; i >= 0 && min > 0; i--, steps++) {
            if (steps == MAX_WALK) {
                return UNKNOWN;
            }
            String text = lineAt.apply(i);
            if (text == null || text.isBlank()) {
                continue;
            }
            int ind = indent(text);
            if (ind >= min) {
                continue;
            }
            String code = code(text);
            if (code.isEmpty()) {
                continue; // a lone closer, or a comment line
            }
            if (!opensScope(code)) {
                min = ind; // an if / for / try block, or the first line of a wrapped statement: keep going out
                continue;
            }
            return i;
        }
        return TOP_LEVEL;
    }

    private static int indent(String text) {
        int n = 0;
        while (n < text.length() && (text.charAt(n) == ' ' || text.charAt(n) == '\t')) {
            n++;
        }
        return n;
    }

    /** The line without indentation, leading closers ({@code "} else {"} → {@code "else {"}) and trailing comment. */
    private static String code(String text) {
        int from = 0;
        while (from < text.length() && " \t})]".indexOf(text.charAt(from)) >= 0) {
            from++;
        }
        String code = text.substring(from);
        if (code.startsWith("#") || code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
            return "";
        }
        for (String marker : new String[] {" //", " #"}) {
            int at = code.indexOf(marker);
            if (at >= 0) {
                code = code.substring(0, at);
            }
        }
        return code.strip();
    }

    private static boolean opensScope(String code) {
        int w = 0;
        while (w < code.length() && Character.isLetter(code.charAt(w))) {
            w++;
        }
        String first = code.substring(0, w);
        if (CONTROL.contains(first)) {
            return false;
        }
        char last = code.charAt(code.length() - 1);
        if (last == '{' || last == ':') {
            return true; // a function, method, class or lambda body starts here
        }
        if (last != '(' && last != ',') {
            return false;
        }
        // A signature wrapped over several lines — "def f(a," / "void run(int a," — as opposed to a wrapped
        // call ("x = compute(", "log.info(\"…\","), which is a statement of the scope it stands in.
        int paren = code.indexOf('(');
        String head = paren < 0 ? code : code.substring(0, paren);
        return paren > 0
                && head.indexOf('=') < 0
                && head.indexOf('.') < 0
                && head.strip().indexOf(' ') > 0;
    }
}
