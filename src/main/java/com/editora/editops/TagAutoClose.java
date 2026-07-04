package com.editora.editops;

import java.util.Locale;

/**
 * Pure, unit-tested auto-close-tag core (the VS Code "auto closing tags" behavior): when the user
 * types the {@code >} that completes an HTML/XML open tag, {@link #closer} returns the matching
 * {@code </name>} to insert after the caret, or {@code null} when nothing should be inserted — the
 * caret isn't inside an open tag, the tag is a closer / doctype / comment / processing instruction,
 * the tag is self-closing ({@code …/>}), the {@code >} sits inside a quoted attribute value, or
 * (HTML) the element is void ({@code <br>}, {@code <img>}, …). Operates on the text <b>before</b>
 * the typed {@code >} only (the caller passes a bounded window ending at the caret), so the cost
 * per keystroke is one short backward scan. No toolkit dependency (mirrors {@link TagRename});
 * {@code EditorBuffer} applies the result.
 */
public final class TagAutoClose {

    /** How far back from the caret a tag may start (bounds the per-keystroke scan). */
    public static final int MAX_TAG_SCAN = 2000;

    private TagAutoClose() {}

    /**
     * The {@code </name>} to insert after a {@code >} typed at the end of {@code beforeCaret}
     * (the text preceding the caret — pass at most the last {@link #MAX_TAG_SCAN} chars), or
     * {@code null} when the {@code >} doesn't complete a closeable open tag. One forward pass
     * tracks whether the window's end is inside a tag and inside a quoted attribute value —
     * quote state only applies <i>within</i> a tag, so an apostrophe in text content (or a
     * {@code >}/{@code <} inside a closed attribute string earlier on) can't derail it.
     */
    public static String closer(String beforeCaret, boolean html) {
        int n = beforeCaret.length();
        int tagStart = -1; // position of the '<' the caret is inside, -1 = in text content
        char quote = 0;
        char lastMeaningful = 0;
        for (int k = 0; k < n; k++) {
            char c = beforeCaret.charAt(k);
            if (tagStart < 0) {
                if (c == '<') {
                    tagStart = k;
                    quote = 0;
                    lastMeaningful = 0;
                }
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                lastMeaningful = c;
            } else if (c == '>') {
                tagStart = -1; // the tag closed — back to text content
            } else if (c == '<') {
                tagStart = k; // stray '<' inside a tag: treat it as a fresh tag start
                lastMeaningful = 0;
            } else if (!Character.isWhitespace(c)) {
                lastMeaningful = c;
            }
        }
        if (tagStart < 0) {
            return null; // the caret is in text content — the > is plain text
        }
        if (quote != 0) {
            return null; // the > is being typed inside an attribute string
        }
        if (lastMeaningful == '/') {
            return null; // the user is completing a self-closing "/>"
        }
        int nameStart = tagStart + 1;
        if (nameStart >= n) {
            return null; // "<" then ">" — no name
        }
        char first = beforeCaret.charAt(nameStart);
        if (first == '/' || first == '!' || first == '?') {
            return null; // closer / doctype-or-comment / processing instruction
        }
        int nameEnd = nameStart;
        while (nameEnd < n && isNameChar(beforeCaret.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameEnd == nameStart) {
            return null; // "<" followed by junk — not a tag
        }
        String name = beforeCaret.substring(nameStart, nameEnd);
        if (html && TagRename.VOID_ELEMENTS.contains(name.toLowerCase(Locale.ROOT))) {
            return null; // void element — it has no close tag
        }
        return "</" + name + ">";
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':';
    }
}
