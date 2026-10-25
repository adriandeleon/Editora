package com.editora.editops;

import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Lisp (Clojure) indentation for {@link Indenter}: a line inside a form is indented by the innermost open
 * bracket, not by the line above it. Pure (no toolkit), so it is unit-tested.
 *
 * <ul>
 *   <li>{@code [} and {@code {} (and a quoted {@code '(} list): the elements line up one column past the
 *       bracket.</li>
 *   <li>{@code (} with a body form ({@code defn}, {@code let}, {@code when}, {@code ns} …): two spaces past
 *       the paren.</li>
 *   <li>Any other {@code (}: aligned to its first argument when one follows the operator on the paren's line,
 *       else two spaces past the paren.</li>
 *   <li>Outside every form: column 0.</li>
 * </ul>
 *
 * Strings, {@code ;} comments and {@code \c} character literals are skipped, so {@code \(} or a paren in a
 * docstring never opens a form. The scan starts at the nearest line above that opens a top-level form (a
 * {@code (} in column 0), at most {@link Indenter#MAX_SCAN} characters back.
 */
final class LispIndent {

    private LispIndent() {}

    /** Forms whose body is indented two spaces whatever follows the operator (cljfmt's block/inner forms). */
    private static final Set<String> BODY_FORMS = Set.of(
            "fn",
            "fn*",
            "let",
            "letfn",
            "loop",
            "binding",
            "do",
            "doseq",
            "dotimes",
            "doto",
            "for",
            "if",
            "if-not",
            "when",
            "while",
            "case",
            "cond",
            "cond->",
            "cond->>",
            "condp",
            "try",
            "catch",
            "finally",
            "ns",
            "comment",
            "testing",
            "proxy",
            "reify",
            "extend",
            "extend-type",
            "extend-protocol",
            "future",
            "delay",
            "locking",
            "go",
            "go-loop",
            "thread");

    /**
     * The indent for a line that starts right after {@code text[0, pos)}; {@code null} when {@code pos} is
     * inside a string, whose continuation lines keep their own indentation.
     */
    static @Nullable String indentAfter(String text, int pos) {
        int from = scanStart(text, pos);
        int[] stack = new int[16];
        int depth = 0;
        boolean inString = false;
        for (int i = from; i < pos; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '\\' -> i++; // a character literal: \( \" \; \space
                case ';' -> {
                    int nl = text.indexOf('\n', i);
                    i = nl < 0 || nl >= pos ? pos : nl;
                }
                case '"' -> inString = true; // a string, or the #"…" regex after its #
                case '(', '[', '{' -> {
                    if (depth == stack.length) {
                        stack = java.util.Arrays.copyOf(stack, depth * 2);
                    }
                    stack[depth++] = i;
                }
                case ')', ']', '}' -> {
                    if (depth > 0) {
                        depth--;
                    }
                }
                default -> {}
            }
        }
        if (inString) {
            return null;
        }
        if (depth == 0) {
            return "";
        }
        int open = stack[depth - 1];
        int ls = lineStart(text, open);
        if (text.charAt(open) != '(' || isDataList(text, open)) {
            return columnOf(text, ls, open + 1);
        }
        int end = open + 1;
        while (end < pos && isTokenChar(text.charAt(end))) {
            end++;
        }
        String op = text.substring(open + 1, end);
        if (op.isEmpty()) {
            // `((f x)`, `([x] …)` or a bare `(`: line up with the first element when it is on this line.
            boolean element = end < pos && text.charAt(end) != '\n' && !Character.isWhitespace(text.charAt(end));
            return element ? columnOf(text, ls, end) : columnOf(text, ls, open) + "  ";
        }
        if (isBodyForm(op)) {
            return columnOf(text, ls, open) + "  ";
        }
        int arg = end;
        while (arg < pos && (text.charAt(arg) == ' ' || text.charAt(arg) == '\t' || text.charAt(arg) == ',')) {
            arg++;
        }
        if (arg < pos && text.charAt(arg) != '\n' && text.charAt(arg) != '\r' && text.charAt(arg) != ';') {
            return columnOf(text, ls, arg);
        }
        return columnOf(text, ls, open) + "  ";
    }

    /** A body form by name, a namespace qualifier ignored: {@code defn}, {@code s/def}, {@code with-open}, {@code when-let}. */
    static boolean isBodyForm(String op) {
        int slash = op.lastIndexOf('/');
        String name = slash >= 0 && slash < op.length() - 1 ? op.substring(slash + 1) : op;
        return BODY_FORMS.contains(name)
                || name.startsWith("def")
                || name.startsWith("with-")
                || name.startsWith("when-")
                || name.startsWith("if-");
    }

    /** {@code '(1 2 3)} or {@code `(a b)}: a list of data, not a call. */
    private static boolean isDataList(String text, int open) {
        return open > 0 && (text.charAt(open - 1) == '\'' || text.charAt(open - 1) == '`');
    }

    private static boolean isTokenChar(char c) {
        return !Character.isWhitespace(c)
                && c != '('
                && c != ')'
                && c != '['
                && c != ']'
                && c != '{'
                && c != '}'
                && c != '"'
                && c != ';'
                && c != ',';
    }

    /** The whitespace that reaches column {@code to} on the line starting at {@code ls}; its tabs are kept. */
    private static String columnOf(String text, int ls, int to) {
        StringBuilder sb = new StringBuilder(Math.max(0, to - ls));
        for (int i = ls; i < to; i++) {
            sb.append(text.charAt(i) == '\t' ? '\t' : ' ');
        }
        return sb.toString();
    }

    /** The start of the nearest line at or above {@code pos}'s that opens a top-level form, within the scan bound. */
    private static int scanStart(String text, int pos) {
        int ls = lineStart(text, pos);
        int floor = Math.max(0, pos - Indenter.MAX_SCAN);
        while (ls > floor) {
            if (text.charAt(ls) == '(' && ls < pos) {
                return ls;
            }
            ls = lineStart(text, ls - 1);
        }
        return ls <= floor ? lineStart(text, floor) : ls;
    }

    private static int lineStart(String text, int pos) {
        int nl = text.lastIndexOf('\n', pos - 1);
        return nl < 0 ? 0 : nl + 1;
    }
}
