package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure identifier extraction for the debugger's editor surfaces: {@link #wordAt} feeds the hover
 * value tooltip (which identifier is under the mouse) and {@link #matchesIn} feeds the inline-values
 * overlay (which suspended-frame variables appear on a visible line). An identifier is a
 * letter/underscore/dollar start followed by letter/digit/underscore/dollar — the common ground of
 * Java, Python, and JavaScript names.
 */
final class DebugIdentifiers {

    private DebugIdentifiers() {}

    static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** The identifier covering 0-based {@code col} of {@code line}, or null (not on an identifier,
     *  or the covering token starts with a digit — a number literal, not a name). */
    static String wordAt(String line, int col) {
        if (line == null || col < 0 || col >= line.length() || !isIdentChar(line.charAt(col))) {
            return null;
        }
        int s = col;
        while (s > 0 && isIdentChar(line.charAt(s - 1))) {
            s--;
        }
        int e = col;
        while (e < line.length() - 1 && isIdentChar(line.charAt(e + 1))) {
            e++;
        }
        return isIdentStart(line.charAt(s)) ? line.substring(s, e + 1) : null;
    }

    /**
     * What hovering 0-based {@code col} of {@code line} means: the identifier there together with the
     * attribute chain before it — {@code self.total} when the mouse is on {@code total}, {@code p} when it is on
     * the {@code p} of {@code p.x}. Hovering the {@code x} of {@code p.x} used to evaluate a variable {@code x},
     * a different value or none. A chain stops at a call or an index ({@code f().x} is just {@code x}: hovering
     * must not run code). Null when {@code col} is not on an identifier.
     */
    static String expressionAt(String line, int col) {
        String word = wordAt(line, col);
        if (word == null) {
            return null;
        }
        int s = col;
        while (s > 0 && isIdentChar(line.charAt(s - 1))) {
            s--;
        }
        int e = s + word.length();
        int start = s;
        while (true) {
            int dot = start - 1;
            while (dot >= 0 && line.charAt(dot) == ' ') {
                dot--;
            }
            if (dot < 0 || line.charAt(dot) != '.') {
                break;
            }
            int end = dot; // exclusive end of the identifier before the dot
            while (end > 0 && line.charAt(end - 1) == ' ') {
                end--;
            }
            int b = end;
            while (b > 0 && isIdentChar(line.charAt(b - 1))) {
                b--;
            }
            if (b == end || !isIdentStart(line.charAt(b))) {
                break; // "f().x", "a[0].x", "1.5": not a plain attribute chain
            }
            start = b;
        }
        return start == s ? word : line.substring(start, e).replace(" ", "");
    }

    /** Whether the identifier starting at {@code start} follows a {@code .} — an attribute, not a variable. */
    static boolean isAttribute(String line, int start) {
        int i = start - 1;
        while (i >= 0 && line.charAt(i) == ' ') {
            i--;
        }
        return i >= 0 && line.charAt(i) == '.';
    }

    /** The members of {@code names} appearing in {@code line} as whole identifiers, in
     *  first-occurrence order, deduplicated. */
    static List<String> matchesIn(String line, Set<String> names) {
        return matchesIn(line, names, col -> false);
    }

    /**
     * As {@link #matchesIn(String, Set)}, leaving out occurrences that start at a column {@code notCode}
     * accepts — a name inside a comment or a string literal is a word, not the variable.
     */
    static List<String> matchesIn(String line, Set<String> names, java.util.function.IntPredicate notCode) {
        List<String> out = new ArrayList<>();
        if (line == null || names == null || names.isEmpty()) {
            return out;
        }
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (isIdentStart(c)) {
                int s = i;
                while (i < n && isIdentChar(line.charAt(i))) {
                    i++;
                }
                String w = line.substring(s, i);
                // obj.x is obj's attribute x, not the variable x: a value shown for it would be another one's.
                if (names.contains(w) && !out.contains(w) && !notCode.test(s) && !isAttribute(line, s)) {
                    out.add(w);
                }
            } else if (isIdentChar(c)) {
                // A token starting with a digit (number literal, possibly with trailing letters like
                // 0x1F or 10L): consume the whole run so its letters aren't misread as a name.
                while (i < n && isIdentChar(line.charAt(i))) {
                    i++;
                }
            } else {
                i++;
            }
        }
        return out;
    }
    /** Python's {@code lambda a, b=1: ...} — the parameter list up to the colon. */
    private static final java.util.regex.Pattern PY_LAMBDA = java.util.regex.Pattern.compile("\\blambda\\b([^:]*):");

    /**
     * The parameters the lambdas on {@code line} declare: {@code n} of {@code names.forEach(n -> …)}, {@code a}
     * and {@code b} of {@code (String a, int b) ->}, JavaScript's {@code x => …}, Python's {@code lambda x, y:}.
     * Such a name on that line is the lambda's own parameter, not the stopped frame's variable of the same name
     * (review C3).
     */
    static Set<String> lambdaParameters(String line) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (line == null || line.isEmpty()) {
            return out;
        }
        for (int i = line.indexOf('>'); i > 0; i = line.indexOf('>', i + 1)) {
            char arrow = line.charAt(i - 1);
            if (arrow != '-' && arrow != '=') {
                continue;
            }
            int j = i - 2;
            while (j >= 0 && line.charAt(j) == ' ') {
                j--;
            }
            if (j < 0) {
                continue;
            }
            if (line.charAt(j) == ')') {
                int open = line.lastIndexOf('(', j);
                if (open < 0) {
                    continue;
                }
                for (String param : line.substring(open + 1, j).split(",")) {
                    addLastIdentifier(param.split("=")[0], out); // "String a" → a; JS "b = 1" → b
                }
            } else {
                addLastIdentifier(line.substring(0, j + 1), out);
            }
        }
        var m = PY_LAMBDA.matcher(line);
        while (m.find()) {
            for (String param : m.group(1).split(",")) {
                addLastIdentifier(param.split("=")[0].replace("*", ""), out);
            }
        }
        return out;
    }

    private static void addLastIdentifier(String text, Set<String> out) {
        int e = text.length();
        while (e > 0 && !isIdentChar(text.charAt(e - 1))) {
            if (text.charAt(e - 1) != ' ') {
                return; // "f(x) ->" is not "x ->"; only spaces may sit between the name and the arrow
            }
            e--;
        }
        int b = e;
        while (b > 0 && isIdentChar(text.charAt(b - 1))) {
            b--;
        }
        if (b < e && isIdentStart(text.charAt(b))) {
            out.add(text.substring(b, e));
        }
    }
}
