package com.editora.markdown;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Pure (no-toolkit, unit-tested) inline Markdown formatting: wrap/unwrap a selection in a symmetric
 * marker ({@code **} bold, {@code *} italic, {@code ~~} strikethrough, {@code `} code), turn a selection
 * into a link, and locate the link destination under a caret (for "open link"). {@code EditorBuffer}
 * applies the returned {@link MarkdownEdit}.
 */
public final class MarkdownInline {

    private MarkdownInline() {}

    /**
     * Toggles {@code marker} ({@code **}, {@code *}, {@code ~~}, {@code `}) on {@code [selStart, selEnd)} with
     * Markdown's run semantics: the marker is removed when the text already carries it and added otherwise,
     * whitespace at the ends stays outside, and a selection over several lines is marked line by line. A
     * collapsed caret inside a word toggles that word; anywhere else it inserts an empty pair with the caret
     * between (or removes the one it sits in). Returns {@code null} when there is nothing to mark.
     */
    public static @Nullable MarkdownEdit toggle(String text, int selStart, int selEnd, String marker) {
        return MarkdownEmphasis.toggle(text, selStart, selEnd, marker, true);
    }

    /** {@link #toggle} for a plain symmetric marker with no run semantics (Typst's {@code *bold*}, {@code _emph_}). */
    public static @Nullable MarkdownEdit toggleSymmetric(String text, int selStart, int selEnd, String marker) {
        return MarkdownEmphasis.toggle(text, selStart, selEnd, marker, false);
    }

    /** Whether the selection (or the word around a collapsed caret) already carries {@code marker}. */
    public static boolean isMarked(String text, int selStart, int selEnd, String marker) {
        return MarkdownEmphasis.isMarked(text, selStart, selEnd, marker, true);
    }

    /**
     * Turns {@code [selStart, selEnd)} into a Markdown link {@code [sel](url)}. With a blank {@code url}
     * the caret is placed inside the empty {@code ()} slot; otherwise the link text is selected.
     */
    public static MarkdownEdit link(String text, int selStart, int selEnd, String url) {
        String sel = escapeUnbalancedBrackets(text.substring(selStart, selEnd));
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
     * The destination of the link whose span contains {@code caret} — inline, reference, autolink or bare
     * URL — or {@code null} when the caret is not on a link. See {@link MarkdownLinkAt}.
     */
    public static @Nullable String linkAround(String text, int caret) {
        return MarkdownLinkAt.destination(text, caret);
    }

    /** Link text may hold brackets only in pairs: a stray {@code [} or {@code ]} is backslash-escaped. */
    static String escapeUnbalancedBrackets(String s) {
        boolean[] escape = new boolean[s.length()];
        java.util.ArrayDeque<Integer> open = new java.util.ArrayDeque<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '[') {
                open.push(i);
            } else if (c == ']') {
                if (open.isEmpty()) {
                    escape[i] = true;
                } else {
                    open.pop();
                }
            }
        }
        open.forEach(i -> escape[i] = true);
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            if (escape[i]) {
                sb.append('\\');
            }
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    private static final Pattern URL_ONLY = Pattern.compile("(?i)(https?|ftp|mailto):\\S+");

    /** Whether {@code s} (stripped) is a URL a link can point at — what "paste a URL over a selection" needs. */
    public static boolean isUrl(String s) {
        return s != null && URL_ONLY.matcher(s.strip()).matches();
    }

    /**
     * Whether pasting a URL over {@code [selStart, selEnd)} should turn the selection into a link: not when
     * the selection spans lines or is blank, is itself a URL (the paste replaces it), already is the text of
     * a link, or sits inside inline code.
     */
    public static boolean wrapsAsLink(String text, int selStart, int selEnd) {
        String sel = text.substring(selStart, selEnd);
        if (sel.isBlank() || sel.indexOf('\n') >= 0 || isUrl(sel)) {
            return false;
        }
        int ls = text.lastIndexOf('\n', selStart - 1) + 1;
        int le = text.indexOf('\n', selEnd);
        String before = text.substring(ls, selStart);
        String after = text.substring(selEnd, le < 0 ? text.length() : le);
        long ticks = before.chars().filter(c -> c == '`').count();
        if (ticks % 2 == 1 && after.indexOf('`') >= 0) {
            return false; // inside a code span
        }
        boolean opened = before.lastIndexOf('[') > before.lastIndexOf(']');
        return !(opened && after.matches("^[^\\[\\]]*\\]\\(.*"));
    }
}
