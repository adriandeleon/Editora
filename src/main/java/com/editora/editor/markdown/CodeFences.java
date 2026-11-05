package com.editora.editor.markdown;

/**
 * Pure: the longest line inside a Markdown document's fenced code blocks, in characters — what a popup showing it
 * needs to be wide enough for, so a signature is not cut behind a sideways scroll bar (the LSP hover card, and the
 * completion documentation beside the list: Python review 2, C106).
 */
public final class CodeFences {

    private CodeFences() {}

    /** The longest line between {@code ```} or {@code ~~~} fences; 0 without a code block or for null. */
    public static int longestLine(String markdown) {
        if (markdown == null) {
            return 0;
        }
        int longest = 0;
        boolean inFence = false;
        for (String line : markdown.split("\\R", -1)) {
            String t = line.strip();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                longest = Math.max(longest, line.length());
            }
        }
        return longest;
    }
}
