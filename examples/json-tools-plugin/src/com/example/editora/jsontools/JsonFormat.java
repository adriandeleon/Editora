package com.example.editora.jsontools;

/**
 * Pure, dependency-free JSON pretty-printer / minifier. A string-aware character walk (no parsing library):
 * it reformats well-formed JSON and leaves string contents untouched. Not a validator.
 */
final class JsonFormat {

    private static final String PAD = "  ";

    private JsonFormat() {
    }

    /** Removes all insignificant whitespace (outside strings). */
    static String minify(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
            } else if (c == '"') {
                inStr = true;
                sb.append(c);
            } else if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Pretty-prints with 2-space indentation. */
    static String pretty(String s) {
        String m = minify(s);
        StringBuilder sb = new StringBuilder(m.length() * 2);
        int indent = 0;
        boolean inStr = false, esc = false;
        for (int i = 0; i < m.length(); i++) {
            char c = m.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inStr = true;
                    sb.append(c);
                }
                case '{', '[' -> {
                    char next = i + 1 < m.length() ? m.charAt(i + 1) : 0;
                    if (next == '}' || next == ']') { // empty {} or [] stays inline
                        sb.append(c).append(next);
                        i++;
                    } else {
                        indent++;
                        sb.append(c).append('\n').append(PAD.repeat(indent));
                    }
                }
                case '}', ']' -> {
                    indent = Math.max(0, indent - 1);
                    sb.append('\n').append(PAD.repeat(indent)).append(c);
                }
                case ',' -> sb.append(c).append('\n').append(PAD.repeat(indent));
                case ':' -> sb.append(": ");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
