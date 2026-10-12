package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * The engine behind {@link MarkdownInline#toggle}: decides whether a stretch of text already carries an
 * inline marker and adds or removes exactly that marker. Pure.
 *
 * <p>The marker characters form <em>runs</em>, and the run length is what a renderer reads: {@code *x*} is
 * italic, {@code **x**} bold, {@code ***x***} both. So the toggle counts the run on each side of the text
 * and adds or removes its own share of it — matching the marker by position turned {@code ***x***} into
 * {@code *****x*****} and a selected {@code **x**} into {@code *x*}. Three more rules keep the result
 * something that renders:
 *
 * <ul>
 *   <li>whitespace at the ends of the selection stays outside the markers ({@code **word **} is not bold);</li>
 *   <li>a selection over several lines is marked line by line, behind each line's list/quote/heading
 *       prefix, and blank lines are skipped;</li>
 *   <li>inline code is fenced with one backtick more than the longest run inside it.</li>
 * </ul>
 */
final class MarkdownEmphasis {

    private MarkdownEmphasis() {}

    /** One trimmed stretch of text and the marker runs around it. */
    private record Span(int start, int end, int run, char runChar, boolean has) {}

    /**
     * @param markdown Markdown run semantics for {@code *} / {@code _} (odd run = italic, two or more =
     *     bold; {@code _x_} counts as emphasis too). False for a plain symmetric marker (Typst).
     */
    static MarkdownEdit toggle(String text, int selStart, int selEnd, String marker, boolean markdown) {
        int s = Math.max(0, Math.min(selStart, selEnd));
        int e = Math.min(text.length(), Math.max(selStart, selEnd));
        if (s == e) {
            return toggleAtCaret(text, s, marker, markdown);
        }
        List<Span> spans = spans(text, s, e, marker, markdown);
        if (spans.isEmpty()) {
            return null; // nothing but whitespace
        }
        boolean remove = spans.stream().allMatch(Span::has);
        StringBuilder out = new StringBuilder(text);
        int from = Integer.MAX_VALUE;
        int to = 0;
        int n = spans.size();
        int[] coreAt = new int[n]; // where each span's text starts once its own markers are settled
        int[] coreLen = new int[n];
        int[] delta = new int[n];
        for (int i = n - 1; i >= 0; i--) { // last to first: an edit never moves the spans before it
            Span sp = spans.get(i);
            String core = text.substring(sp.start(), sp.end());
            coreAt[i] = sp.start();
            coreLen[i] = core.length();
            if (!remove && sp.has()) {
                continue; // already marked: left alone while its neighbours gain the marker
            }
            int a = sp.start();
            int b = sp.end();
            String replacement;
            if (remove) {
                int k = marker.charAt(0) == '`' ? sp.run() : Math.min(sp.run(), marker.length());
                a -= k;
                b += k;
                replacement = unpadCode(core, marker);
                coreAt[i] = a;
                coreLen[i] = replacement.length();
            } else {
                String open = opener(core, marker);
                String pad = marker.charAt(0) == '`' && (core.startsWith("`") || core.endsWith("`")) ? " " : "";
                replacement = open + pad + core + pad + open;
                coreAt[i] = a + open.length() + pad.length();
            }
            out.replace(a, b, replacement);
            delta[i] = replacement.length() - (b - a);
            from = Math.min(from, a);
            to = Math.max(to, b);
        }
        if (from == Integer.MAX_VALUE) {
            return null;
        }
        int shift = 0;
        for (int i = 0; i < n - 1; i++) {
            shift += delta[i];
        }
        int selFrom = coreAt[0];
        int selTo = coreAt[n - 1] + coreLen[n - 1] + shift;
        return new MarkdownEdit(from, to, out.substring(from, to + shift + delta[n - 1]), selFrom, selTo);
    }

    /** Whether {@code [selStart, selEnd)} — or the word around a collapsed caret — carries {@code marker}. */
    static boolean isMarked(String text, int selStart, int selEnd, String marker, boolean markdown) {
        int s = Math.max(0, Math.min(selStart, selEnd));
        int e = Math.min(text.length(), Math.max(selStart, selEnd));
        if (s == e) {
            int[] w = wordAround(text, s);
            if (w == null) {
                return false;
            }
            s = w[0];
            e = w[1];
        }
        List<Span> spans = spans(text, s, e, marker, markdown);
        return !spans.isEmpty() && spans.stream().allMatch(Span::has);
    }

    private static MarkdownEdit toggleAtCaret(String text, int caret, String marker, boolean markdown) {
        int[] w = wordAround(text, caret);
        if (w != null) {
            // Inside a word: the word is what is meant, and the caret stays on its letter.
            MarkdownEdit e = toggle(text, w[0], w[1], marker, markdown);
            if (e == null) {
                return null;
            }
            int at = e.selStart() + (caret - w[0]);
            return new MarkdownEdit(e.from(), e.to(), e.replacement(), at, at);
        }
        int len = marker.length();
        boolean between = caret >= len
                && caret + len <= text.length()
                && text.startsWith(marker, caret - len)
                && text.startsWith(marker, caret)
                && !longerRun(text, caret, marker);
        if (between) {
            return new MarkdownEdit(caret - len, caret + len, "", caret - len, caret - len); // undo the empty pair
        }
        return new MarkdownEdit(caret, caret, marker + marker, caret + len, caret + len);
    }

    /** True when the pair around {@code caret} is part of a longer run of the marker character. */
    private static boolean longerRun(String text, int caret, String marker) {
        char c = marker.charAt(0);
        int before = caret - marker.length() - 1;
        int after = caret + marker.length();
        return (before >= 0 && text.charAt(before) == c) || (after < text.length() && text.charAt(after) == c);
    }

    /** The word the caret is strictly inside of, or null (at a word's edge the caret starts new text). */
    private static int[] wordAround(String text, int caret) {
        int a = caret;
        while (a > 0 && Character.isLetterOrDigit(text.charAt(a - 1))) {
            a--;
        }
        int b = caret;
        while (b < text.length() && Character.isLetterOrDigit(text.charAt(b))) {
            b++;
        }
        return a < caret && caret < b ? new int[] {a, b} : null;
    }

    /** The trimmed, per-line stretches of {@code [s, e)}; one for a selection inside a line. */
    private static List<Span> spans(String text, int s, int e, String marker, boolean markdown) {
        List<Span> out = new ArrayList<>();
        boolean several = text.indexOf('\n', s) >= 0 && text.indexOf('\n', s) < e;
        int at = s;
        while (at < e) {
            int nl = text.indexOf('\n', at);
            int lineEnd = nl < 0 || nl > e ? e : nl;
            int a = at;
            if (several) {
                int lineStart = text.lastIndexOf('\n', at - 1) + 1;
                int fullEnd = nl < 0 ? text.length() : nl;
                MarkdownLine l = MarkdownLine.parse(text.substring(lineStart, fullEnd));
                a = Math.max(a, lineStart + (fullEnd - lineStart) - l.content().length());
            }
            int b = lineEnd;
            while (a < b && Character.isWhitespace(text.charAt(a))) {
                a++;
            }
            while (b > a && Character.isWhitespace(text.charAt(b - 1))) {
                b--;
            }
            if (a < b) {
                out.add(span(text, a, b, marker, markdown));
            }
            at = lineEnd + 1;
        }
        return out;
    }

    private static Span span(String text, int s, int e, String marker, boolean markdown) {
        char c = marker.charAt(0);
        // Markers the selection took in with the text count as markers, not as text — but only when they
        // are the two ends of one span: "*a* and *b*" starts and ends with a marker without being one.
        int lead = 0;
        while (s + lead < e && text.charAt(s + lead) == c) {
            lead++;
        }
        int trail = 0;
        while (e - trail > s + lead && text.charAt(e - trail - 1) == c) {
            trail++;
        }
        int k = Math.min(lead, trail);
        if (k > 0 && k * 2 < e - s && oneSpan(text, s + k, e - k, c)) {
            s += k;
            e -= k;
        } else if (lead == 0 && trail > 0 && trail < e - s && s > 0 && text.charAt(s - 1) == c) {
            e -= trail; // the selection began behind the opening marker and ran over the closing one
        } else if (trail == 0 && lead > 0 && lead < e - s && e < text.length() && text.charAt(e) == c) {
            s += lead;
        }
        int run = run(text, s, e, c);
        if (c == '`'
                && run == 0
                && s > 0
                && e < text.length()
                && text.charAt(s - 1) == ' '
                && text.charAt(e) == ' '
                && run(text, s - 1, e + 1, c) > 0) {
            s--; // code padded with a blank to keep its own backtick off the fence
            e++;
            run = run(text, s, e, c);
        }
        char runChar = c;
        if (markdown && c == '*' && run == 0) {
            int under = run(text, s, e, '_');
            // An underscore inside a word (snake_case) is never emphasis.
            boolean flanked = (s - under == 0 || !Character.isLetterOrDigit(text.charAt(s - under - 1)))
                    && (e + under >= text.length() || !Character.isLetterOrDigit(text.charAt(e + under)));
            if (under > 0 && flanked) {
                run = under;
                runChar = '_';
            }
        }
        boolean has;
        if (c == '`') {
            has = run >= 1;
        } else if (markdown && c == '*' && marker.length() == 1) {
            has = run % 2 == 1;
        } else {
            has = run >= marker.length();
        }
        return new Span(s, e, run, runChar, has);
    }

    /** The length of the shorter of the two runs of {@code c} directly outside {@code [s, e)}. */
    private static int run(String text, int s, int e, char c) {
        int left = 0;
        while (s - left > 0 && text.charAt(s - left - 1) == c) {
            left++;
        }
        int right = 0;
        while (e + right < text.length() && text.charAt(e + right) == c) {
            right++;
        }
        return Math.min(left, right);
    }

    /** Whether the first run of {@code c} inside {@code [s, e)} opens a span (so the outer markers pair up). */
    private static boolean oneSpan(String text, int s, int e, char c) {
        int i = text.indexOf(c, s);
        if (i < 0 || i >= e) {
            return true;
        }
        int after = i;
        while (after < e && text.charAt(after) == c) {
            after++;
        }
        boolean opens = after < e && !Character.isWhitespace(text.charAt(after));
        boolean closes = i > s && !Character.isWhitespace(text.charAt(i - 1));
        return opens && !closes;
    }

    /** Code that was padded to keep a backtick off the fence loses the padding with the fence. */
    private static String unpadCode(String core, String marker) {
        if (marker.charAt(0) == '`'
                && core.length() > 2
                && core.startsWith(" ")
                && core.endsWith(" ")
                && (core.charAt(1) == '`' || core.charAt(core.length() - 2) == '`')) {
            return core.substring(1, core.length() - 1);
        }
        return core;
    }

    /** The marker to wrap {@code core} in: for code, one backtick longer than the longest run inside. */
    private static String opener(String core, String marker) {
        if (marker.charAt(0) != '`') {
            return marker;
        }
        int longest = 0;
        int cur = 0;
        for (int i = 0; i < core.length(); i++) {
            cur = core.charAt(i) == '`' ? cur + 1 : 0;
            longest = Math.max(longest, cur);
        }
        return "`".repeat(longest + 1);
    }
}
