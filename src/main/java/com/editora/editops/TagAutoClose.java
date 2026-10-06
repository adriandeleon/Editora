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
        int braces = 0; // JSX {expression} depth: operators are legitimate inside one
        boolean operator = false;
        String rawText = null; // the open <script>/<style> whose content the scan is in (HTML)
        for (int k = 0; k < n; k++) {
            char c = beforeCaret.charAt(k);
            if (tagStart < 0) {
                if (rawText != null) {
                    // Raw text holds code, not markup: `x<3` and `i<n` are comparisons. Only its own
                    // closing tag ends it.
                    if (c != '<' || !beforeCaret.regionMatches(true, k + 1, "/" + rawText, 0, rawText.length() + 1)) {
                        continue;
                    }
                    rawText = null;
                }
                if (c == '<') {
                    tagStart = k;
                    quote = 0;
                    lastMeaningful = 0;
                    braces = 0;
                    operator = false;
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
                if (html && lastMeaningful != '/') {
                    rawText = rawTextElement(beforeCaret, tagStart);
                }
                tagStart = -1; // the tag closed — back to text content
            } else if (c == '<') {
                tagStart = k; // stray '<' inside a tag: treat it as a fresh tag start
                lastMeaningful = 0;
                braces = 0;
                operator = false;
            } else if (!Character.isWhitespace(c)) {
                lastMeaningful = c;
                if (c == '{') {
                    braces++;
                } else if (c == '}') {
                    braces = Math.max(0, braces - 1);
                } else if (braces == 0 && (c == '&' || c == '|' || c == ';')) {
                    operator = true;
                }
            }
        }
        if (operator) {
            return null; // "if (i<n && j" in an inline script: a comparison, not a tag
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
        if (nameEnd == nameStart || !(Character.isLetter(first) || first == '_' || first == ':')) {
            return null; // "<" followed by junk or a number ("x<3") — not a tag
        }
        if (nameEnd < n && !Character.isWhitespace(beforeCaret.charAt(nameEnd)) && beforeCaret.charAt(nameEnd) != '/') {
            return null; // "i<n) return x": a tag name ends at whitespace, "/" or the ">" being typed
        }
        String name = beforeCaret.substring(nameStart, nameEnd);
        if (html && TagRename.VOID_ELEMENTS.contains(name.toLowerCase(Locale.ROOT))) {
            return null; // void element — it has no close tag
        }
        return "</" + name + ">";
    }

    /** {@code "script"}/{@code "style"} when the tag starting at {@code lt} opens that raw-text element, else null. */
    private static String rawTextElement(String s, int lt) {
        for (String name : TagRename.RAW_TEXT_ELEMENTS) {
            int end = lt + 1 + name.length();
            if (s.regionMatches(true, lt + 1, name, 0, name.length())
                    && end < s.length()
                    && !isNameChar(s.charAt(end))) {
                return name;
            }
        }
        return null;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':';
    }
}
