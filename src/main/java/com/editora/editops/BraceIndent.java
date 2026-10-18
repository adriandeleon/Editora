package com.editora.editops;

import java.util.regex.Pattern;

/**
 * Brace-language indentation the plain "a line ending in an opener indents" rule misses, as WebStorm and
 * Prettier-formatted code lay it out:
 *
 * <ul>
 *   <li>the body of a {@code case …:} / {@code default:} label is indented, and a label typed at body level steps
 *       back to the previous label (or one unit inside its {@code switch});
 *   <li>the one statement under a brace-less {@code if (…)}, {@code for (…)}, {@code while (…)} or {@code else}
 *       is indented, and the line after it returns to the header's level;
 *   <li>a call whose argument list is still open ({@code foo(a,}) indents its next line, and the line after the
 *       one that closes it ({@code b);}) returns to the statement's own level;
 *   <li>a line starting with {@code .} continues the statement above it ({@code .then(…)} under
 *       {@code return fetch(url)}), and the line after a chain ends returns to the chain's first line.
 * </ul>
 *
 * Pure. {@code text} holds the caret's line and a bounded stretch above it; {@code ls} is where the caret's line
 * starts in it.
 */
public final class BraceIndent {

    private BraceIndent() {}

    /** A {@code case} or {@code default} label, alone on its (comment-stripped) line. */
    private static final Pattern CASE_LABEL = Pattern.compile("(?:case\\b.*[^:]|default\\s*):");

    /** A control header with no braces after it. */
    private static final Pattern CONTROL = Pattern.compile("(?:(?:else\\s+)?if|for|while)\\s*\\(.*\\)|else|do");

    /** How many lines back the scans look. */
    private static final int MAX_LINES = 200;

    /**
     * The indent of the line after Enter, or {@code null} when none of these rules applies (the caller's own
     * rules then decide).
     *
     * @param before the caret's line up to the caret
     */
    static String afterEnter(String text, int ls, String before, String unit) {
        String indent = Indenter.leadingWhitespace(before);
        String code = code(before);
        if (code.isEmpty()) {
            return null;
        }
        if (CASE_LABEL.matcher(code).matches() || isControlHeader(code)) {
            return indent + unit;
        }
        int balance = parenBalance(code);
        if (balance > 0 && !endsWithOpener(code)) {
            return indent + unit; // `foo(a,` — the arguments go on
        }
        if (balance < 0) {
            String head = openingLineIndent(text, ls, balance);
            if (head != null) { // `b);` — back to the line the call started on; `int b) {` — one inside it
                return endsWithOpener(code) ? head + unit : head;
            }
        }
        String previous = previousCodeLine(text, ls);
        if (previous != null && isControlHeader(code(previous)) && code.endsWith(";")) {
            return Indenter.leadingWhitespace(previous); // the one statement under `if (x)` is done
        }
        if (code.startsWith(".") && code.endsWith(";")) {
            return chainHeadIndent(text, ls); // the chain is done: back to its first line
        }
        return null;
    }

    /**
     * Whether {@code lineUpToCaretPlusChar} is a label or chain line just completed by its typed character: a
     * {@code case …:} / {@code default:} on its {@code :}, or a lone leading {@code .}.
     */
    static boolean completesClause(String lineUpToCaretPlusChar) {
        if (lineUpToCaretPlusChar.isEmpty()
                || ".:".indexOf(lineUpToCaretPlusChar.charAt(lineUpToCaretPlusChar.length() - 1)) < 0) {
            return false; // only the typed `:` or `.` itself completes one (not Enter, not a later character)
        }
        String rest = lineUpToCaretPlusChar.strip();
        if (rest.equals(".")) {
            return true;
        }
        return rest.endsWith(":")
                && !rest.endsWith("::")
                && CASE_LABEL.matcher(rest).matches();
    }

    /**
     * The indent for the line the just-typed {@code typed} completed (see {@link #completesClause}), or
     * {@code null} to leave it.
     */
    static String clauseIndent(String text, int ls, char typed, String currentIndent, String unit) {
        if (typed == ':') {
            return caseIndent(text, ls, unit);
        }
        if (typed == '.') {
            String previous = previousCodeLine(text, ls);
            if (previous == null) {
                return null;
            }
            String code = code(previous);
            if (code.isEmpty() || ";{}([,".indexOf(code.charAt(code.length() - 1)) >= 0 || code.startsWith("//")) {
                return null; // a new statement, a spread inside a literal, …: not a continuation
            }
            String prevIndent = Indenter.leadingWhitespace(previous);
            return code.startsWith(".") ? prevIndent : prevIndent + unit;
        }
        return null;
    }

    /** A {@code case} label's indent: the previous label's, or one unit inside the {@code switch}. */
    private static String caseIndent(String text, int ls, String unit) {
        int end = ls - 1;
        for (int n = 0; end > 0 && n < MAX_LINES; n++) {
            int start = text.lastIndexOf('\n', end - 1) + 1;
            String line = text.substring(start, end);
            String code = code(line);
            if (CASE_LABEL.matcher(code).matches()) {
                return Indenter.leadingWhitespace(line);
            }
            if (code.startsWith("switch") && code.endsWith("{")) {
                return Indenter.leadingWhitespace(line) + unit;
            }
            end = start - 1;
        }
        return null;
    }

    private static boolean isControlHeader(String code) {
        return CONTROL.matcher(code).matches() && (code.indexOf('(') < 0 || headerParensClose(code));
    }

    /** Whether the header's first {@code (} is closed by its last character, so nothing follows the condition. */
    private static boolean headerParensClose(String code) {
        int depth = 0;
        int open = code.indexOf('(');
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i == code.length() - 1;
            }
        }
        return false;
    }

    private static boolean endsWithOpener(String code) {
        char last = code.charAt(code.length() - 1);
        return last == '{' || last == '(' || last == '[';
    }

    /** The indent of the line holding the opener a line with {@code balance} unmatched {@code )} closes. */
    private static String openingLineIndent(String text, int ls, int balance) {
        int end = ls - 1;
        for (int n = 0; end > 0 && n < MAX_LINES; n++) {
            int start = text.lastIndexOf('\n', end - 1) + 1;
            String line = text.substring(start, end);
            balance += parenBalance(code(line));
            if (balance >= 0) {
                return Indenter.leadingWhitespace(line);
            }
            end = start - 1;
        }
        return null;
    }

    /** The indent of the first line of the {@code .} chain the caret's line ends. */
    private static String chainHeadIndent(String text, int ls) {
        int end = ls - 1;
        for (int n = 0; end > 0 && n < MAX_LINES; n++) {
            int start = text.lastIndexOf('\n', end - 1) + 1;
            String line = text.substring(start, end);
            if (!code(line).startsWith(".") && !line.isBlank()) {
                return Indenter.leadingWhitespace(line);
            }
            end = start - 1;
        }
        return null;
    }

    /** The nearest non-blank line above the caret's line, or {@code null}. */
    private static String previousCodeLine(String text, int ls) {
        int end = ls - 1;
        for (int n = 0; end > 0 && n < MAX_LINES; n++) {
            int start = text.lastIndexOf('\n', end - 1) + 1;
            String line = text.substring(start, end);
            if (!line.isBlank()) {
                return line;
            }
            end = start - 1;
        }
        return null;
    }

    /** {@code line} without its trailing {@code //} comment and outer whitespace (strings are respected). */
    static String code(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                return line.substring(0, i).strip();
            }
        }
        return line.strip();
    }

    /** {@code (} minus {@code )} outside string literals. */
    static int parenBalance(String code) {
        int balance = 0;
        char quote = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            } else if (c == '(') {
                balance++;
            } else if (c == ')') {
                balance--;
            }
        }
        return balance;
    }
}
