package com.editora.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserting a footnote: a {@code [^n]} reference at the caret and its {@code [^n]: } definition at the end of
 * the document, {@code n} being the next number no footnote uses yet. Pure.
 */
public final class MarkdownFootnote {

    private static final Pattern NUMBERED = Pattern.compile("\\[\\^(\\d{1,9})\\]");

    private MarkdownFootnote() {}

    /** One more than the highest footnote number used in {@code text} (references and definitions alike). */
    public static long nextNumber(String text) {
        long max = 0;
        Matcher m = NUMBERED.matcher(text);
        while (m.find()) {
            max = Math.max(max, Long.parseLong(m.group(1)));
        }
        return max + 1;
    }

    /**
     * The edit that puts a reference at {@code caret} and an empty definition after the last line, separated
     * from the text by a blank line (directly under the previous definition when the document ends in one).
     * The caret lands in the definition, ready for its text.
     */
    public static MarkdownEdit insert(String text, int caret) {
        int at = Math.max(0, Math.min(caret, text.length()));
        String ref = "[^" + nextNumber(text) + "]";
        String tail = text.substring(at);
        String body = tail.stripTrailing();
        int lastLine = (text.substring(0, at) + body).lastIndexOf('\n') + 1;
        boolean afterDefinition =
                (text.substring(0, at) + body).substring(lastLine).matches("^\\[\\^[^\\]]+\\]:.*");
        String gap = afterDefinition ? "\n" : "\n\n";
        String replacement = ref + body + gap + ref + ": ";
        int end = at + replacement.length();
        return new MarkdownEdit(at, text.length(), replacement, end, end);
    }
}
