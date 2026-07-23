package com.editora.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.lsp4j.SemanticTokensEdit;

/**
 * Applies a {@code semanticTokens/full/delta} response's edits to the previously cached token data array
 * (#679). Each {@link SemanticTokensEdit} is a splice — {@code start}/{@code deleteCount} index the
 * <b>old</b> array, {@code data} is the replacement — so edits apply in descending-start order to keep
 * earlier indices valid. Pure of JavaFX and I/O; unit-tested.
 */
final class SemanticTokensSplice {

    private SemanticTokensSplice() {}

    /**
     * The new token data after applying {@code edits} to {@code previous}, or {@code null} when an edit is
     * out of range (a stale/garbled delta — the caller falls back to a full request rather than decode a
     * corrupted array).
     */
    static List<Integer> apply(List<Integer> previous, List<SemanticTokensEdit> edits) {
        if (previous == null) {
            return null;
        }
        List<Integer> out = new ArrayList<>(previous);
        if (edits == null || edits.isEmpty()) {
            return out;
        }
        List<SemanticTokensEdit> byStartDesc = new ArrayList<>(edits);
        byStartDesc.sort(Comparator.comparingInt(SemanticTokensEdit::getStart).reversed());
        for (SemanticTokensEdit e : byStartDesc) {
            int start = e.getStart();
            int delete = Math.max(0, e.getDeleteCount());
            if (start < 0 || start + delete > out.size()) {
                return null; // out of range — a stale delta; the caller re-requests full
            }
            out.subList(start, start + delete).clear();
            if (e.getData() != null && !e.getData().isEmpty()) {
                out.addAll(start, e.getData());
            }
        }
        return out;
    }
}
