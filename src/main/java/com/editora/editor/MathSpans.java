package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure detector of LaTeX math spans in a run of text: {@code $$…$$} (display) and {@code $…$} (inline).
 * Uses GitHub-style rules to avoid eating prose dollar signs: an inline opener {@code $} must be
 * immediately followed by a non-space, the closer {@code $} immediately preceded by a non-space and not
 * directly followed by a digit, the content non-empty, and {@code \$} is treated as a literal dollar.
 *
 * <p>Stateless and toolkit-free — unit-tested directly. Used by {@code MarkdownRenderer}/{@code
 * MarkdownPdfWriter} to split a text run into literal text + math segments.
 */
public final class MathSpans {

    private MathSpans() {}

    /** A math span covering {@code [start, end)} of the input (delimiters included). */
    public record Span(int start, int end, String latex, boolean display) {}

    public static List<Span> find(String text) {
        List<Span> spans = new ArrayList<>();
        if (text == null || text.length() < 2) {
            return spans;
        }
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (text.charAt(i) != '$' || isEscaped(text, i)) {
                i++;
                continue;
            }
            boolean display = i + 1 < n && text.charAt(i + 1) == '$';
            if (display) {
                int close = findClose(text, i + 2, "$$");
                if (close >= 0) {
                    String latex = text.substring(i + 2, close).strip();
                    if (!latex.isEmpty()) {
                        spans.add(new Span(i, close + 2, latex, true));
                        i = close + 2;
                        continue;
                    }
                }
                i++;
            } else {
                int close = findInlineClose(text, i + 1);
                if (close >= 0) {
                    String latex = text.substring(i + 1, close);
                    spans.add(new Span(i, close + 1, latex.strip(), false));
                    i = close + 1;
                    continue;
                }
                i++;
            }
        }
        return spans;
    }

    /** Splits {@code text} into alternating literal/math segments; math segments have a non-null span. */
    public static List<Segment> segments(String text) {
        List<Segment> out = new ArrayList<>();
        List<Span> spans = find(text);
        int pos = 0;
        for (Span s : spans) {
            if (s.start() > pos) {
                out.add(new Segment(text.substring(pos, s.start()), null));
            }
            out.add(new Segment(null, s));
            pos = s.end();
        }
        if (pos < text.length()) {
            out.add(new Segment(text.substring(pos), null));
        }
        return out;
    }

    /** A literal-text segment ({@code span == null}) or a math segment ({@code text == null}). */
    public record Segment(String text, Span span) {}

    /** First index of an unescaped {@code $$} at/after {@code from}, or -1. */
    private static int findClose(String text, int from, String delim) {
        for (int i = from; i + 1 < text.length(); i++) {
            if (text.charAt(i) == '$' && text.charAt(i + 1) == '$' && !isEscaped(text, i)) {
                return i;
            }
        }
        return -1;
    }

    /** Index of an inline math closing {@code $} starting the search at {@code from}, honoring the rules. */
    private static int findInlineClose(String text, int from) {
        if (from >= text.length() || text.charAt(from) == ' ' || text.charAt(from) == '\t') {
            return -1; // opener must be followed by a non-space
        }
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                return -1; // inline math doesn't span lines
            }
            if (c == '$' && !isEscaped(text, i)) {
                char prev = text.charAt(i - 1);
                if (prev == ' ' || prev == '\t') {
                    return -1; // closer must be preceded by a non-space
                }
                if (i + 1 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                    return -1; // "$…$5" → currency, not math
                }
                return i;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String text, int i) {
        int backslashes = 0;
        for (int k = i - 1; k >= 0 && text.charAt(k) == '\\'; k--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }
}
