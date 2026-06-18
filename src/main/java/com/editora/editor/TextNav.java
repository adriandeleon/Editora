package com.editora.editor;

/**
 * Pure, unit-tested caret-navigation math for the Emacs-style motion commands — extracted from
 * {@code MainController} so it can be tested without the JavaFX toolkit. Every method takes the whole
 * document {@code text} plus an absolute {@code caret} offset and returns the destination offset; line
 * boundaries are computed from the text's {@code '\n'} separators, matching the RichTextFX
 * {@code CodeArea}'s paragraph model (one paragraph per {@code '\n'}-delimited line, newline excluded).
 */
public final class TextNav {

    private TextNav() {}

    /** Absolute offset of the start of the line containing {@code caret} (the char after the prior newline). */
    public static int lineStart(String text, int caret) {
        return text.lastIndexOf('\n', Math.max(0, caret) - 1) + 1;
    }

    /**
     * Target column for a "smart" line start (C-a): the first non-whitespace column (the start of the
     * line's <em>text</em>), or column 0 when the caret is already there — so a second press toggles to the
     * true line start.
     */
    public static int smartLineStartColumn(String lineText, int caretCol) {
        int indent = 0;
        while (indent < lineText.length() && (lineText.charAt(indent) == ' ' || lineText.charAt(indent) == '\t')) {
            indent++;
        }
        return caretCol == indent ? 0 : indent;
    }

    /** Absolute offset of the smart line start for {@code caret} (see {@link #smartLineStartColumn}). */
    public static int smartLineStart(String text, int caret) {
        int ls = lineStart(text, caret);
        int lineEnd = text.indexOf('\n', ls);
        String lineText = text.substring(ls, lineEnd < 0 ? text.length() : lineEnd);
        return ls + smartLineStartColumn(lineText, caret - ls);
    }

    /** Absolute offset of the first non-whitespace char on the caret's line (Emacs M-m). */
    public static int backToIndentation(String text, int caret) {
        int i = lineStart(text, caret);
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++; // stops at the line's first non-blank, or the trailing '\n' (not a space/tab)
        }
        return i;
    }

    /** Start of the blank line below the current block, or document end (Emacs M-}). */
    public static int forwardParagraph(String text, int caret) {
        String[] lines = text.split("\n", -1);
        int[] starts = lineStarts(lines);
        int n = lines.length;
        int p = paragraphIndex(text, caret) + 1;
        while (p < n && lines[p].isBlank()) {
            p++; // skip blank lines we're sitting on
        }
        while (p < n && !lines[p].isBlank()) {
            p++; // through the text block
        }
        return p >= n ? text.length() : starts[p];
    }

    /** Start of the blank line above the current block, or document start (Emacs M-{). */
    public static int backwardParagraph(String text, int caret) {
        String[] lines = text.split("\n", -1);
        int[] starts = lineStarts(lines);
        int p = paragraphIndex(text, caret) - 1;
        while (p >= 0 && lines[p].isBlank()) {
            p--;
        }
        while (p >= 0 && !lines[p].isBlank()) {
            p--;
        }
        return p < 0 ? 0 : starts[p];
    }

    /** Offset at the start of the next sentence after {@code caret} (Emacs M-e). */
    public static int forwardSentence(String text, int caret) {
        int n = text.length();
        int i = caret;
        while (i < n) {
            char c = text.charAt(i++);
            if (isSentenceEnd(c)) {
                while (i < n
                        && (text.charAt(i) == '"'
                                || text.charAt(i) == '\''
                                || text.charAt(i) == ')'
                                || text.charAt(i) == ']')) {
                    i++; // closing quotes/brackets stay with the sentence
                }
                if (i >= n || Character.isWhitespace(text.charAt(i))) {
                    while (i < n && Character.isWhitespace(text.charAt(i))) {
                        i++;
                    }
                    return i;
                }
            }
        }
        return n;
    }

    /** Offset at the start of the sentence containing/just before {@code caret} (Emacs M-a). */
    public static int backwardSentence(String text, int caret) {
        int i = caret - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--; // skip whitespace immediately before the caret
        }
        // If we're sitting right after a terminator (caret already at a sentence start), skip it so
        // repeated presses keep moving back instead of landing on the same spot.
        while (i >= 0 && isSentenceEnd(text.charAt(i))) {
            i--;
        }
        while (i >= 0 && !isSentenceEnd(text.charAt(i))) {
            i--; // back over the sentence body to the previous sentence's terminator
        }
        if (i < 0) {
            return 0;
        }
        int j = i + 1;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        return j;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?';
    }

    /** The paragraph (line) index of {@code caret} = the number of {@code '\n'} before it. */
    private static int paragraphIndex(String text, int caret) {
        int count = 0;
        int limit = Math.min(caret, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /** Absolute start offset of each line produced by {@code text.split("\n", -1)}. */
    private static int[] lineStarts(String[] lines) {
        int[] starts = new int[lines.length];
        int off = 0;
        for (int i = 0; i < lines.length; i++) {
            starts[i] = off;
            off += lines[i].length() + 1; // + the '\n' that split removed
        }
        return starts;
    }
}
