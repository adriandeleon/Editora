package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Bracket-pair colorization: tints each {@code () [] {}} by its nesting depth so "how deep am I?" is
 * readable at a glance (VS Code's {@code editor.bracketPairColorization}, on by default there since 1.67).
 * Distinct from the matching-pair highlight ({@code .text.brace-match}, see {@link BraceMatcher}), which
 * answers "where does <em>this</em> one close?" — both are worth having and they combine.
 *
 * <p>Computed in Java rather than by a grammar (TextMate cannot count nesting) and folded into the
 * existing incremental highlight in {@link EditorBuffer#applyHighlighting}: the depth pass runs on the
 * same background thread, over the same captured text, as the tokenize that precedes it, and its spans are
 * overlaid onto the token spans in the one {@code setStyleSpans} that was happening anyway. So there is no
 * extra apply, no extra repaint, and nothing added to the caret or scroll paths.
 *
 * <p><b>Brackets inside strings and comments are skipped.</b> {@link BraceMatcher} cannot do this — it runs
 * off the caret with no token information — but here the tokenize has just finished, so the string/comment
 * spans are free. It matters more than it does for match-at-caret: a stray {@code "{"} in a string would
 * shift the colour of every bracket below it, which reads as the feature being broken rather than as one
 * bracket being wrong.
 *
 * <p>Depth is carried across incremental passes by {@link Analysis#lineEndDepths()}, stored per line beside
 * the grammar end-states and spliced identically, so re-highlighting from the edited line never has to
 * rescan the unchanged prefix.
 *
 * <p>The pure {@link #analyze} (no toolkit) is unit-tested; {@link #buildSpans} is the thin RichTextFX
 * wrapper, mirroring {@link CsvRainbow}.
 */
final class BracketColors {

    /**
     * Number of depth slots before the cycle repeats, matching VS Code's six
     * {@code editorBracketHighlight.foreground1..6} tokens. Its default palette colours only the first
     * three (4-6 are fully transparent), so the visible cycle there is three; ours does the same by
     * repeating the three hues in {@code syntax.css}, which a theme can override for a true six-deep cycle.
     */
    static final int COLORS = 6;

    /** Style code for a closer with nothing open — VS Code's {@code unexpectedBracket}. */
    static final int UNMATCHED = -1;

    private BracketColors() {}

    /**
     * The bracket marks in {@code text} from {@code from} onward, plus the nesting depth at the end of each
     * line covered.
     *
     * @param marks {@code {offset, code}} per colourable bracket, offset <em>relative to {@code from}</em>
     *     (so it lines up with the token spans, which start there); {@code code} is the depth modulo
     *     {@code colors}, or {@link #UNMATCHED}
     * @param lineEndDepths depth after each line, one entry per line from {@code from}'s line to the last —
     *     the same line accounting {@code TextMateHighlighter.analyzeFrom} uses for its end-states, so the
     *     two lists splice in lockstep
     */
    record Analysis(List<int[]> marks, List<Integer> lineEndDepths) {}

    /**
     * Pure depth pass. {@code skip} is the ascending, non-overlapping list of {@code {start, end)} ranges
     * (relative to {@code from}) whose brackets are ignored — the string/comment token spans.
     *
     * <p>One shared depth counter across all three bracket kinds, matching VS Code's default
     * {@code independentColorPoolPerBracketType: false}. Bracket <em>kinds</em> are deliberately not matched
     * against each other: doing so needs the open-bracket stack carried across the incremental boundary, not
     * just an int, and mismatched kinds are far rarer than the depth reading this exists to give.
     */
    static Analysis analyze(String text, int from, int startDepth, List<int[]> skip, int colors) {
        List<int[]> marks = new ArrayList<>();
        List<Integer> lineEndDepths = new ArrayList<>();
        if (text == null) {
            return new Analysis(marks, lineEndDepths);
        }
        int len = text.length();
        int pos = Math.max(0, Math.min(from, len));
        int base = pos;
        int depth = Math.max(0, startDepth);
        int cycle = Math.max(1, colors);
        int si = 0; // cursor into `skip`; the scan is monotonic, so it only ever advances
        while (true) {
            int newline = text.indexOf('\n', pos);
            int lineEnd = newline < 0 ? len : newline;
            for (int i = pos; i < lineEnd; i++) {
                char c = text.charAt(i);
                if (!isBracket(c)) {
                    continue;
                }
                int rel = i - base;
                while (skip != null && si < skip.size() && skip.get(si)[1] <= rel) {
                    si++;
                }
                if (skip != null && si < skip.size() && skip.get(si)[0] <= rel) {
                    continue; // inside a string or comment
                }
                if (isOpen(c)) {
                    marks.add(new int[] {rel, depth % cycle});
                    depth++;
                } else if (depth == 0) {
                    marks.add(new int[] {rel, UNMATCHED});
                } else {
                    depth--;
                    marks.add(new int[] {rel, depth % cycle});
                }
            }
            lineEndDepths.add(depth);
            if (newline < 0) {
                return new Analysis(marks, lineEndDepths);
            }
            pos = newline + 1;
        }
    }

    /**
     * The sparse {@link StyleSpans} for {@code marks} over a range of {@code length} characters — each
     * bracket carries its depth class, everything else an empty style so it can {@code overlay} the token
     * spans. Returns {@code null} when there is nothing to colour, so the caller can skip the overlay.
     *
     * <p>The overlay <b>replaces</b> rather than unions: a coloured bracket carries only its
     * {@code bracket-depth-N} class. Unioning would leave the fill to be decided between two equally
     * specific {@code .text.<class>} rules, and every editor theme restates the token classes in a
     * stylesheet loaded after {@code syntax.css} — so the theme's punctuation colour would quietly win and
     * colorization would appear to work only under the default theme.
     */
    static StyleSpans<Collection<String>> buildSpans(int length, List<int[]> marks) {
        if (length <= 0 || marks == null || marks.isEmpty()) {
            return null;
        }
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int at = 0;
        int runCode = Integer.MIN_VALUE; // an open run of adjacent brackets sharing a depth
        int runLen = 0;
        for (int[] m : marks) {
            int off = m[0];
            if (off < at || off >= length) {
                continue; // defensive: a mark outside the span range would break the exact-cover contract
            }
            // Merge adjacent brackets of the same depth into one span, as SpanMerger does for tokens.
            // RichTextFX materializes a Text node per span, so the empty pairs that pepper real code —
            // "()", "{}", "[]", whose two halves share a depth — would otherwise each cost two nodes.
            if (off == at && m[1] == runCode) {
                runLen++;
                at = off + 1;
                continue;
            }
            flush(b, runCode, runLen);
            runCode = Integer.MIN_VALUE;
            runLen = 0;
            if (off > at) {
                b.add(List.of(), off - at);
            }
            runCode = m[1];
            runLen = 1;
            at = off + 1;
        }
        flush(b, runCode, runLen);
        if (at == 0) {
            return null;
        }
        if (at < length) {
            b.add(List.of(), length - at);
        }
        return b.create();
    }

    /**
     * The token spans that brackets should be ignored inside — strings and comments — as ascending,
     * non-overlapping {@code {start, end)} ranges relative to the start of {@code spans}. Adjacent
     * qualifying spans are merged so the list stays short.
     */
    static List<int[]> skipRanges(StyleSpans<Collection<String>> spans) {
        List<int[]> out = new ArrayList<>();
        if (spans == null) {
            return out;
        }
        int at = 0;
        for (var span : spans) {
            int len = span.getLength();
            Collection<String> style = span.getStyle();
            if (style != null && (style.contains("string") || style.contains("comment"))) {
                int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last != null && last[1] == at) {
                    last[1] = at + len; // extend the previous range rather than adding a neighbour
                } else {
                    out.add(new int[] {at, at + len});
                }
            }
            at += len;
        }
        return out;
    }

    private static void flush(StyleSpansBuilder<Collection<String>> b, int code, int len) {
        if (len > 0) {
            b.add(List.of(classFor(code)), len);
        }
    }

    /** CSS class for a style code — {@code bracket-depth-<k>}, or {@code bracket-unmatched}. */
    static String classFor(int code) {
        return code == UNMATCHED ? "bracket-unmatched" : "bracket-depth-" + code;
    }

    static boolean isBracket(char c) {
        return isOpen(c) || isClose(c);
    }

    private static boolean isOpen(char c) {
        return c == '(' || c == '[' || c == '{';
    }

    private static boolean isClose(char c) {
        return c == ')' || c == ']' || c == '}';
    }
}
