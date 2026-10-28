package com.editora.editops;

import org.jspecify.annotations.Nullable;

/**
 * Text pasted into a Java string literal, escaped as IntelliJ's "Escape text on paste in string literals" does: a
 * pasted {@code C:\temp "x"} silently became a tab and broke the literal (thirteenth review AK2). Backslashes, quotes
 * and control characters are escaped, and each line break ends the literal and goes on in a concatenated one. Text
 * copied from inside a string literal is escaped already and pastes as it is. Pure, but for the one remembered copy.
 */
public final class JavaStringPaste {

    private JavaStringPaste() {}

    /** The text last copied from inside a string literal, or null. */
    private static volatile @Nullable String fromLiteral;

    /** Remembers whether {@code text}, just copied, came from inside a string literal. */
    public static void copied(@Nullable String text, boolean inLiteral) {
        fromLiteral = inLiteral ? text : null;
    }

    /** Where a paste lands: in no literal, a {@code "…"} string, a text block or a char literal. */
    enum Literal {
        NONE,
        STRING,
        TEXT_BLOCK,
        CHAR
    }

    /**
     * {@code pasted} as it should land at {@code offset} of {@code text}, a Java file: escaped for the literal the
     * offset is in (not in a comment), unless the text was copied from inside a string literal. A text block keeps its
     * line breaks and quotes, so only its backslashes and a {@code """} are escaped there, and a char literal's
     * backslash and quote (fourteenth check RJ4).
     */
    public static String forPaste(String text, int offset, String pasted) {
        Literal at = literalAt(text, offset);
        if (pasted.isEmpty() || pasted.equals(fromLiteral) || at == Literal.NONE) {
            return pasted;
        }
        if (at == Literal.TEXT_BLOCK) {
            return pasted.replace("\\", "\\\\").replace("\"\"\"", "\\\"\"\"");
        }
        if (at == Literal.CHAR) {
            return pasted.replace("\\", "\\\\").replace("'", "\\'");
        }
        int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int indentEnd = lineStart;
        while (indentEnd < text.length() && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) {
            indentEnd++;
        }
        String indent = text.substring(lineStart, indentEnd) + "        ";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pasted.length(); i++) {
            char c = pasted.charAt(i);
            boolean lineBreak = c == '\n' || (c == '\r' && (i + 1 >= pasted.length() || pasted.charAt(i + 1) != '\n'));
            if (c == '\r' && !lineBreak) {
                continue; // the \r of a \r\n
            }
            if (lineBreak) {
                out.append("\\n");
                if (i + 1 < pasted.length()) {
                    out.append("\" +\n").append(indent).append('"');
                }
                continue;
            }
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** Whether {@code from} and {@code to} of {@code text} are in the same {@code "…"} literal. */
    public static boolean withinLiteral(String text, int from, int to) {
        if (!inLiteral(text, from) || to < from) {
            return false;
        }
        for (int i = from; i < to && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '"' && !escaped(text, i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code offset} of {@code text} is inside a {@code "…"} literal: after its opening quote, not past its end. */
    public static boolean inLiteral(String text, int offset) {
        return literalAt(text, offset) == Literal.STRING;
    }

    /** The literal {@code offset} of {@code text} is inside, after its opening quote(s) and not past its end. */
    public static Literal literalAt(String text, int offset) {
        int end = Math.min(offset, text.length());
        int i = 0;
        boolean string = false;
        while (i < end) {
            char c = text.charAt(i);
            if (string) {
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"' || c == '\n') {
                    string = false;
                }
                i++;
            } else if (text.startsWith("//", i)) {
                int nl = text.indexOf('\n', i);
                if (nl < 0 || nl >= end) {
                    return Literal.NONE;
                }
                i = nl + 1;
            } else if (text.startsWith("/*", i)) {
                int close = text.indexOf("*/", i + 2);
                if (close < 0 || close + 2 > end) {
                    return Literal.NONE;
                }
                i = close + 2;
            } else if (text.startsWith("\"\"\"", i)) {
                int close = text.indexOf("\"\"\"", i + 3);
                if (close < 0 || close >= end) {
                    return i + 3 <= end ? Literal.TEXT_BLOCK : Literal.NONE;
                }
                i = close + 3;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < text.length() && text.charAt(j) != '\'' && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                if (j >= end) {
                    return Literal.CHAR;
                }
                i = j + 1;
            } else {
                string = c == '"';
                i++;
            }
        }
        return string && i == end ? Literal.STRING : Literal.NONE;
    }

    private static boolean escaped(String text, int at) {
        int slashes = 0;
        for (int i = at - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }
}
