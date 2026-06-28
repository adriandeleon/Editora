package com.editora.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure (no-toolkit, unit-tested) inline Markdown formatting: wrap/unwrap a selection in a symmetric
 * marker ({@code **} bold, {@code *} italic, {@code ~~} strikethrough, {@code `} code), turn a selection
 * into a link, and locate the link destination under a caret (for "open link"). {@code EditorBuffer}
 * applies the returned {@link MarkdownEdit}.
 */
public final class MarkdownInline {

    /** Inline link {@code [text](url "title")} — captures the URL (group 1), title optional, ignored. */
    private static final Pattern INLINE_LINK = Pattern.compile("\\[[^\\]]*\\]\\(\\s*([^)\\s]+)(?:\\s+[^)]*)?\\)");
    /** A bare http(s) URL. */
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s)<>\"']+");

    private MarkdownInline() {}

    /**
     * Toggles {@code marker} around {@code [selStart, selEnd)}. If the markers already sit just outside
     * or just inside the selection they are removed; an empty selection inserts an empty pair with the
     * caret between; otherwise the selection is wrapped (and re-selected so repeated toggles work).
     */
    public static MarkdownEdit toggle(String text, int selStart, int selEnd, String marker) {
        int len = marker.length();
        // Markers immediately OUTSIDE the selection -> unwrap them.
        if (selStart >= len
                && selEnd + len <= text.length()
                && text.startsWith(marker, selStart - len)
                && text.startsWith(marker, selEnd)) {
            String inner = text.substring(selStart, selEnd);
            return new MarkdownEdit(
                    selStart - len, selEnd + len, inner, selStart - len, selStart - len + inner.length());
        }
        String sel = text.substring(selStart, selEnd);
        // Markers INSIDE the selection -> strip them.
        if (sel.length() >= 2 * len && sel.startsWith(marker) && sel.endsWith(marker)) {
            String inner = sel.substring(len, sel.length() - len);
            return new MarkdownEdit(selStart, selEnd, inner, selStart, selStart + inner.length());
        }
        if (selStart == selEnd) {
            // Empty selection: insert the pair, caret between the markers.
            return new MarkdownEdit(selStart, selEnd, marker + marker, selStart + len, selStart + len);
        }
        String wrapped = marker + sel + marker;
        return new MarkdownEdit(selStart, selEnd, wrapped, selStart + len, selEnd + len);
    }

    /**
     * Turns {@code [selStart, selEnd)} into a Markdown link {@code [sel](url)}. With a blank {@code url}
     * the caret is placed inside the empty {@code ()} slot; otherwise the link text is selected.
     */
    public static MarkdownEdit link(String text, int selStart, int selEnd, String url) {
        String sel = text.substring(selStart, selEnd);
        String u = url == null ? "" : url.strip();
        String replacement = "[" + sel + "](" + u + ")";
        if (u.isEmpty()) {
            int caret = selStart + 1 + sel.length() + 2; // after "[sel]("
            return new MarkdownEdit(selStart, selEnd, replacement, caret, caret);
        }
        // Select the link text between the brackets.
        return new MarkdownEdit(selStart, selEnd, replacement, selStart + 1, selStart + 1 + sel.length());
    }

    /**
     * The URL of the link (inline {@code [..](url)} or a bare {@code http(s)://…}) whose span contains
     * {@code caret}, or {@code null} when the caret is not on a link.
     */
    public static String linkAround(String text, int caret) {
        if (text == null || caret < 0 || caret > text.length()) {
            return null;
        }
        Matcher m = INLINE_LINK.matcher(text);
        while (m.find()) {
            if (caret >= m.start() && caret <= m.end()) {
                return m.group(1);
            }
        }
        Matcher b = BARE_URL.matcher(text);
        while (b.find()) {
            if (caret >= b.start() && caret <= b.end()) {
                return b.group();
            }
        }
        return null;
    }
}
