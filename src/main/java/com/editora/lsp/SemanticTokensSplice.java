package com.editora.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.lsp4j.SemanticTokensEdit;
import org.jspecify.annotations.Nullable;

/**
 * Applies a {@code semanticTokens/full/delta} response's edits to the previously cached token data array
 * (#679). Each {@link SemanticTokensEdit} is a splice — {@code start}/{@code deleteCount} index the
 * <b>old</b> array, {@code data} is the replacement. The arrays are {@code int[]}: a Java class's tokens run to
 * 150k ints, and a boxed copy per typing pause (plus a boxed cached copy per open document) cost megabytes
 * (perf review 2026-11-23 L9). Pure of JavaFX and I/O; unit-tested.
 */
final class SemanticTokensSplice {

    private SemanticTokensSplice() {}

    /**
     * The new token data after applying {@code edits} to {@code previous}, or {@code null} when an edit is
     * out of range or overlaps another (a stale/garbled delta — the caller falls back to a full request rather
     * than decode a corrupted array). With no edits it is {@code previous} itself.
     */
    static int @Nullable [] apply(int @Nullable [] previous, @Nullable List<SemanticTokensEdit> edits) {
        if (previous == null) {
            return null;
        }
        if (edits == null || edits.isEmpty()) {
            return previous;
        }
        List<SemanticTokensEdit> byStart = new ArrayList<>(edits);
        byStart.sort(Comparator.comparingInt(SemanticTokensEdit::getStart));
        int length = previous.length;
        int end = 0; // where the previous edit's deletion ended, in the old array
        for (SemanticTokensEdit e : byStart) {
            int start = e.getStart();
            int delete = Math.max(0, e.getDeleteCount());
            if (start < end || start + delete > previous.length) {
                return null; // out of range or overlapping — a stale delta; the caller re-requests full
            }
            end = start + delete;
            length += size(e) - delete;
        }
        int[] out = new int[length];
        int from = 0; // next unread index of the old array
        int to = 0; // next free index of the new one
        for (SemanticTokensEdit e : byStart) {
            int kept = e.getStart() - from;
            System.arraycopy(previous, from, out, to, kept);
            to += kept;
            List<Integer> data = e.getData();
            if (data != null) {
                for (Integer n : data) {
                    out[to++] = n == null ? 0 : n;
                }
            }
            from = e.getStart() + Math.max(0, e.getDeleteCount());
        }
        System.arraycopy(previous, from, out, to, previous.length - from);
        return out;
    }

    /** The server's flat token data unboxed, for the cache and the decoder. */
    static int[] toArray(@Nullable List<Integer> data) {
        if (data == null) {
            return new int[0];
        }
        int[] out = new int[data.size()];
        int i = 0;
        for (Integer n : data) {
            out[i++] = n == null ? 0 : n;
        }
        return out;
    }

    private static int size(SemanticTokensEdit e) {
        return e.getData() == null ? 0 : e.getData().size();
    }
}
