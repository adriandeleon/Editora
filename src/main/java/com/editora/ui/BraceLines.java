package com.editora.ui;

import java.util.List;

/**
 * The lines of a C-like source file (Go, Java, Kotlin) with what a line scanner of declarations needs: each line's
 * code with its comments and the contents of its strings blanked out, the brace and parenthesis depth at its start
 * and end, and whether it starts inside a block comment or a multi-line string. Strings ({@code "…"}), character
 * and rune literals ({@code '…'}), Go's raw strings ({@code `…`}), Java and Kotlin text blocks ({@code """…"""}),
 * line and block comments are skipped, so a brace in any of them does not count. Pure: any thread.
 */
final class BraceLines {

    private final String[] code;
    private final int[] braceStart;
    private final int[] braceEnd;
    private final int[] parenStart;
    private final int[] parenEnd;
    private final boolean[] startsInside;

    private enum State {
        CODE,
        BLOCK_COMMENT,
        RAW,
        TEXT_BLOCK
    }

    /**
     * Scans {@code lines}.
     *
     * @param go whether a backtick opens a raw string (Go); otherwise {@code """} opens a text block
     */
    BraceLines(List<String> lines, boolean go) {
        int n = lines.size();
        code = new String[n];
        braceStart = new int[n];
        braceEnd = new int[n];
        parenStart = new int[n];
        parenEnd = new int[n];
        startsInside = new boolean[n];
        State state = State.CODE;
        int brace = 0;
        int paren = 0;
        for (int line = 0; line < n; line++) {
            String text = lines.get(line);
            braceStart[line] = brace;
            parenStart[line] = paren;
            startsInside[line] = state != State.CODE;
            StringBuilder out = new StringBuilder(text.length());
            int i = 0;
            int len = text.length();
            while (i < len) {
                char c = text.charAt(i);
                if (state == State.BLOCK_COMMENT) {
                    if (c == '*' && i + 1 < len && text.charAt(i + 1) == '/') {
                        state = State.CODE;
                        out.append("  ");
                        i += 2;
                    } else {
                        out.append(' ');
                        i++;
                    }
                    continue;
                }
                if (state == State.RAW) {
                    out.append(c == '`' ? '`' : ' ');
                    if (c == '`') {
                        state = State.CODE;
                    }
                    i++;
                    continue;
                }
                if (state == State.TEXT_BLOCK) {
                    if (text.startsWith("\"\"\"", i)) {
                        state = State.CODE;
                        out.append("\"\"\"");
                        i += 3;
                    } else {
                        out.append(' ');
                        i++;
                    }
                    continue;
                }
                if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') {
                    break; // the rest of the line is a comment
                }
                if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
                    state = State.BLOCK_COMMENT;
                    out.append("  ");
                    i += 2;
                    continue;
                }
                if (go && c == '`') {
                    state = State.RAW;
                    out.append('`');
                    i++;
                    continue;
                }
                if (!go && text.startsWith("\"\"\"", i)) {
                    state = State.TEXT_BLOCK;
                    out.append("\"\"\"");
                    i += 3;
                    continue;
                }
                if (c == '"' || c == '\'') {
                    out.append(c);
                    int j = i + 1;
                    while (j < len && text.charAt(j) != c) {
                        int step = text.charAt(j) == '\\' && j + 1 < len ? 2 : 1;
                        out.append(step == 2 ? "  " : " ");
                        j += step;
                    }
                    if (j < len) {
                        out.append(c);
                    }
                    i = j + 1;
                    continue;
                }
                switch (c) {
                    case '{' -> brace++;
                    case '}' -> brace = Math.max(0, brace - 1);
                    case '(' -> paren++;
                    case ')' -> paren = Math.max(0, paren - 1);
                    default -> {}
                }
                out.append(c);
                i++;
            }
            code[line] = out.toString();
            braceEnd[line] = brace;
            parenEnd[line] = paren;
        }
    }

    int size() {
        return code.length;
    }

    /** The line's code: comments removed, string contents blanked (the quotes kept). */
    String code(int line) {
        return code[line];
    }

    int braceStart(int line) {
        return braceStart[line];
    }

    int braceEnd(int line) {
        return braceEnd[line];
    }

    int parenStart(int line) {
        return parenStart[line];
    }

    int parenEnd(int line) {
        return parenEnd[line];
    }

    /** Whether the line starts inside a block comment, a raw string or a text block. */
    boolean startsInside(int line) {
        return startsInside[line];
    }

    /**
     * The last line of what {@code line} opens: the line on which the braces and parentheses it leaves open are
     * closed again, or {@code line} itself when it leaves none open (the end of the text when they never close).
     */
    int blockEnd(int line) {
        int brace = braceStart[line];
        int paren = parenStart[line];
        if (braceEnd[line] <= brace && parenEnd[line] <= paren) {
            return line;
        }
        for (int next = line + 1; next < code.length; next++) {
            if (braceEnd[next] <= brace && parenEnd[next] <= paren) {
                return next;
            }
        }
        return code.length - 1;
    }
}
