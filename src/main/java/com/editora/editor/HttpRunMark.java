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
        return find(requestLines, textOf, wanted, List.of(), near);
    }

    /**
     * As {@link #find(List, IntFunction, String, int)}, preferring among requests whose method/URL line reads
     * {@code wanted} the one whose following lines (its headers) best match {@code wantedBlock}, as the request read
     * when the run started; the nearest breaks a tie. Two requests to the same URL with different headers are
     * common, and the nearest alone marked the wrong one after a request was inserted above.
     */
    static int find(
            List<Integer> requestLines, IntFunction<String> textOf, String wanted, List<String> wantedBlock, int near) {
        if (wanted == null) {
            return -1;
        }
        int best = -1;
        int bestMatch = -1;
        for (int i = 0; i < requestLines.size(); i++) {
            int line = requestLines.get(i);
            if (!wanted.equals(textOf.apply(line))) {
                continue;
            }
            int match = matching(line, textOf, wantedBlock);
            if (best < 0 || match > bestMatch || (match == bestMatch && Math.abs(i - near) < Math.abs(best - near))) {
                best = i;
                bestMatch = match;
            }
        }
        return best;
    }

    /** How many of the lines after {@code line} equal {@code block}, in order, before the first that differs. */
    private static int matching(int line, IntFunction<String> textOf, List<String> block) {
        int n = 0;
        for (String want : block) {
            if (!want.equals(textOf.apply(line + 1 + n))) {
                break;
            }
            n++;
        }
        return n;
    }

    /** The most lines after a request's method line that are compared (its headers, up to a blank line). */
    static final int BLOCK_LINES = 30;

    /** The lines after {@code line} up to the first blank one, a {@code ###} separator or the end. Pure. */
    static List<String> blockAfter(int line, IntFunction<String> textOf) {
        List<String> out = new java.util.ArrayList<>();
        for (int k = 1; k <= BLOCK_LINES; k++) {
            String t = textOf.apply(line + k);
            if (t == null || t.isBlank() || t.startsWith("###")) {
                break;
            }
            out.add(t);
        }
        return List.copyOf(out);
    }
}
