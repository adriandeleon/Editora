package com.editora.editor;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Which request of a {@code .http} buffer shows the running ■ after the requests moved (a line inserted
 * above, a request added): the one whose method/URL line still reads as it did when the run started, nearest
 * to where the mark was. Without this an edit during a run removed the ■ — and with it the click that cancels —
 * while the request kept running. Pure.
 */
final class HttpRunMark {

    private HttpRunMark() {}

    /**
     * The index, in {@code requestLines}, of the request whose line ({@code textOf}) equals {@code wanted},
     * nearest to {@code near}; -1 when none does (the line itself was edited: no request is marked rather than
     * the wrong one).
     */
    static int find(List<Integer> requestLines, IntFunction<String> textOf, String wanted, int near) {
        if (wanted == null) {
            return -1;
        }
        int best = -1;
        for (int i = 0; i < requestLines.size(); i++) {
            String text = textOf.apply(requestLines.get(i));
            if (wanted.equals(text) && (best < 0 || Math.abs(i - near) < Math.abs(best - near))) {
                best = i;
            }
        }
        return best;
    }
}
