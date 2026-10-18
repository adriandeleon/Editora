package com.editora.editops;

import java.util.Set;

/**
 * Scala 3's optional braces: the line endings that open an indented block, shared by {@link Indenter} (Enter
 * indents after one) and the fold scanner (a block folds from one to the end of its indentation). Pure.
 *
 * <p>A line opens such a block when its code, without a trailing comment, ends with {@code :} (a template
 * body: {@code class A:}, {@code object B:}), {@code =} or {@code =>} (a definition or a case body), or one
 * of the keywords {@code then}, {@code do}, {@code else}, {@code try}, {@code catch}, {@code finally},
 * {@code match}, {@code yield} or {@code with}. Braces are not counted: the brace rules already handle them.
 */
public final class ScalaIndent {

    private ScalaIndent() {}

    private static final Set<String> KEYWORDS =
            Set.of("then", "do", "else", "try", "catch", "finally", "match", "yield", "with");

    /** Whether {@code line} ends with a Scala 3 block opener. */
    public static boolean opensIndentedBlock(String line) {
        String code = code(line).stripTrailing();
        if (code.isEmpty()) {
            return false;
        }
        String lead = code.stripLeading();
        if (lead.startsWith("*") || lead.startsWith("/*") || lead.startsWith("@")) {
            return false; // a Scaladoc line, or an annotation
        }
        char last = code.charAt(code.length() - 1);
        if (code.endsWith("=>")) {
            return true; // a case body, or a lambda's
        }
        if (last == ':') {
            return !code.endsWith("::"); // `xs ::` continues an expression
        }
        if (last == '=') {
            return !code.endsWith("==") && !code.endsWith("!=") && !code.endsWith("<=") && !code.endsWith(">=");
        }
        int start = code.length();
        while (start > 0 && Character.isLetter(code.charAt(start - 1))) {
            start--;
        }
        if (start == code.length() || (start > 0 && isIdentChar(code.charAt(start - 1)))) {
            return false;
        }
        return KEYWORDS.contains(code.substring(start));
    }

    /** Whether {@code line} is an {@code end} marker ({@code end Foo}, {@code end if}, a bare {@code end}). */
    public static boolean isEndMarker(String line) {
        String t = code(line).strip();
        return t.equals("end") || (t.startsWith("end ") && t.indexOf(' ', 4) < 0 && t.length() > 4);
    }

    /** {@code line} without a trailing {@code //} or {@code /* … *}{@code /} comment that is outside a string. */
    static String code(String line) {
        int n = line.length();
        for (int i = 0; i < n; i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (line.startsWith("\"\"\"", i)) {
                    int close = line.indexOf("\"\"\"", i + 3);
                    if (close < 0) {
                        return line; // a multi-line string starts here: nothing after it is a comment
                    }
                    i = close + 2;
                    continue;
                }
                int j = i + 1;
                while (j < n && line.charAt(j) != '"') {
                    j += line.charAt(j) == '\\' ? 2 : 1;
                }
                i = j;
            } else if (c == '\'' && i + 2 < n && (line.charAt(i + 2) == '\'' || line.charAt(i + 1) == '\\')) {
                int close = line.indexOf('\'', i + 2);
                i = close < 0 ? i : close; // a char literal: '"', '/', '\n'
            } else if (c == '/' && i + 1 < n && (line.charAt(i + 1) == '/' || line.charAt(i + 1) == '*')) {
                if (line.charAt(i + 1) == '*') {
                    int close = line.indexOf("*/", i + 2);
                    if (close >= 0 && !line.substring(close + 2).isBlank()) {
                        i = close + 1; // code follows the comment
                        continue;
                    }
                }
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
