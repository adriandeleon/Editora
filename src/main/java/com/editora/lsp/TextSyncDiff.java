package com.editora.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * The minimal single-splice difference between the server's last-known document text and the editor's
 * current text — what incremental document sync ({@code TextDocumentSyncKind.Incremental}, #678) sends
 * instead of the whole document. Common-prefix/common-suffix scan: O(n) char comparisons, zero allocation
 * beyond the replacement substring, and — because it is computed <b>shadow vs. current</b> rather than by
 * accumulating editor change events — immune to event-ordering bugs by construction.
 *
 * <p>Positions are LSP UTF-16 code-unit line/columns. The prefix/suffix never split a surrogate pair: a
 * range boundary between a high and low surrogate would put half a pair into the replacement string —
 * invalid JSON — so the scan backs off one unit at such a boundary. Pure; heavily unit-tested (including a
 * seeded randomized convergence test).
 */
final class TextSyncDiff {

    private TextSyncDiff() {}

    /** One splice: replace {@code [start, end)} (offsets into the OLD text) with {@code replacement}. */
    record Delta(int start, int end, String replacement) {}

    /** The minimal splice turning {@code oldText} into {@code newText}, or null when they are identical. */
    static Delta diff(String oldText, String newText) {
        int lo = oldText.length();
        int ln = newText.length();
        int min = Math.min(lo, ln);
        int p = 0;
        while (p < min && oldText.charAt(p) == newText.charAt(p)) {
            p++;
        }
        if (p == lo && p == ln) {
            return null; // identical
        }
        // Don't let the prefix end between a surrogate pair (the replacement would start mid-pair).
        if (p > 0 && Character.isHighSurrogate(oldText.charAt(p - 1))) {
            p--;
        }
        int s = 0;
        while (s < min - p && oldText.charAt(lo - 1 - s) == newText.charAt(ln - 1 - s)) {
            s++;
        }
        // Don't let the suffix start between a surrogate pair.
        if (s > 0 && Character.isLowSurrogate(oldText.charAt(lo - s))) {
            s--;
        }
        return new Delta(p, lo - s, newText.substring(p, ln - s));
    }

    /** The LSP range of {@code [start, end)} within {@code text} — both positions in one scan. */
    static Range rangeOf(String text, int start, int end) {
        int line = 0;
        int lineStart = 0;
        Position startPos = null;
        int limit = Math.min(end, text.length());
        for (int i = 0; i <= limit; i++) {
            if (startPos == null && i == start) {
                startPos = new Position(line, start - lineStart);
            }
            if (i == limit) {
                break;
            }
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        if (startPos == null) {
            startPos = new Position(line, Math.max(0, start - lineStart));
        }
        return new Range(startPos, new Position(line, Math.max(0, end - lineStart)));
    }
}
