package com.editora.editor.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure: the lambda expressions that start on one line of Java, where a lambda breakpoint can stop. The Java debugger
 * stops in a lambda body only for a breakpoint at the column the lambda starts at (its parameter, or the parenthesis
 * of its parameter list), so this finds those columns as the Java parser would: each {@code ->} outside strings and
 * comments that is not a {@code case}/{@code default} arrow of a switch, traced back to its parameters.
 */
public final class JavaLambdas {

    private JavaLambdas() {}

    /** Most characters of a lambda's text kept for a menu or a hover. */
    public static final int MAX_PREVIEW = 40;

    /** A lambda starting on the line: its 0-based start column, and its text from there (cut for a menu). */
    public record Lambda(int column, String preview) {}

    /** Every lambda starting on {@code line}, left to right (the order lambda numbers count in). */
    public static List<Lambda> on(String line) {
        List<Lambda> out = new ArrayList<>();
        if (line == null || line.indexOf("->") < 0) {
            return out;
        }
        char[] code = blankNonCode(line);
        for (int i = 0; i + 1 < code.length; i++) {
            if (code[i] != '-' || code[i + 1] != '>') {
                continue;
            }
            int start = parametersStart(code, i);
            if (start >= 0 && !isSwitchArrow(code, start)) {
                out.add(new Lambda(start, preview(line, start)));
            }
            i++;
        }
        return out;
    }

    /** The 0-based start column of lambda {@code number} (1-based) on {@code line}, or -1 when it has fewer. */
    public static int column(String line, int number) {
        List<Lambda> all = on(line);
        return number >= 1 && number <= all.size() ? all.get(number - 1).column() : -1;
    }

    /**
     * The number (from 1) of the lambda {@code column} (0-based) is in or after: the last one starting at or before
     * it, or the first when it is before them all; 0 when there is none.
     */
    public static int numberAt(List<Lambda> lambdas, int column) {
        int n = lambdas.isEmpty() ? 0 : 1;
        for (int i = 0; i < lambdas.size(); i++) {
            if (lambdas.get(i).column() <= column) {
                n = i + 1;
            }
        }
        return n;
    }

    /** {@code line} with string, character and comment contents (and their quotes) turned into spaces. */
    static char[] blankNonCode(String line) {
        char[] c = line.toCharArray();
        int i = 0;
        while (i < c.length) {
            char ch = c[i];
            if (ch == '/' && i + 1 < c.length && c[i + 1] == '/') {
                for (int k = i; k < c.length; k++) {
                    c[k] = ' ';
                }
                break;
            }
            if (ch == '/' && i + 1 < c.length && c[i + 1] == '*') {
                int end = line.indexOf("*/", i + 2);
                int stop = end < 0 ? c.length : end + 2;
                for (int k = i; k < stop; k++) {
                    c[k] = ' ';
                }
                i = stop;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                int k = i + 1;
                while (k < c.length && c[k] != ch) {
                    k += c[k] == '\\' ? 2 : 1;
                }
                int stop = Math.min(c.length, k + 1);
                for (int m = i; m < stop; m++) {
                    c[m] = ' ';
                }
                i = stop;
                continue;
            }
            i++;
        }
        return c;
    }

    /**
     * Where the parameters before the {@code ->} at {@code arrow} start: the identifier just before it, or the
     * {@code (} that opens the parenthesised list; -1 when neither is there.
     */
    private static int parametersStart(char[] c, int arrow) {
        int k = arrow - 1;
        while (k >= 0 && Character.isWhitespace(c[k])) {
            k--;
        }
        if (k < 0) {
            return -1;
        }
        if (c[k] == ')') {
            int depth = 0;
            for (; k >= 0; k--) {
                if (c[k] == ')') {
                    depth++;
                } else if (c[k] == '(' && --depth == 0) {
                    return k;
                }
            }
            return -1;
        }
        if (!Character.isJavaIdentifierPart(c[k])) {
            return -1;
        }
        while (k > 0 && Character.isJavaIdentifierPart(c[k - 1])) {
            k--;
        }
        return Character.isJavaIdentifierStart(c[k]) ? k : -1;
    }

    /**
     * Whether the {@code ->} whose left side starts at {@code start} is a switch rule's arrow ({@code case A, B ->},
     * {@code case Point(int x, int y) ->}, {@code default ->}): walking back over the label at the same nesting, a
     * {@code case} or {@code default} comes before anything that ends an expression.
     */
    private static boolean isSwitchArrow(char[] c, int start) {
        if (word(c, start).equals("default")) {
            return true;
        }
        int depth = 0;
        for (int k = start - 1; k >= 0; k--) {
            char ch = c[k];
            if (ch == ')' || ch == ']') {
                depth++;
            } else if (ch == '(' || ch == '[') {
                if (depth == 0) {
                    return false; // an argument list or a parenthesised expression: a lambda
                }
                depth--;
            } else if (depth == 0 && (ch == ';' || ch == '{' || ch == '}' || ch == '=' || ch == '?' || ch == ':')) {
                return false;
            } else if (depth == 0 && Character.isJavaIdentifierPart(ch)) {
                int from = k;
                while (from > 0 && Character.isJavaIdentifierPart(c[from - 1])) {
                    from--;
                }
                String w = new String(c, from, k - from + 1);
                if (w.equals("case") || w.equals("default")) {
                    return true;
                }
                if (w.equals("return")) {
                    return false;
                }
                k = from;
            }
        }
        return false;
    }

    private static String word(char[] c, int start) {
        int end = start;
        while (end < c.length && Character.isJavaIdentifierPart(c[end])) {
            end++;
        }
        return new String(c, start, end - start);
    }

    private static String preview(String line, int start) {
        String rest = line.substring(start).strip();
        return rest.length() > MAX_PREVIEW ? rest.substring(0, MAX_PREVIEW) + "…" : rest;
    }
}
