package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.SelectionRange;

/**
 * Flattens a server's {@code textDocument/selectionRange} answer into the innermost-first chain of
 * {@code [start, end]} document offsets that {@code SmartSelectStack} expands through (#739).
 *
 * <p>Pure and unit-tested. The conversion from LSP line/character coordinates to offsets happens here so the
 * {@code editor} package stays free of lsp4j, mirroring how {@code LspPositions} handles it in the other
 * direction.
 */
public final class LspSelection {

    private LspSelection() {}

    /**
     * The chain for the first position in {@code ranges}, innermost first: the server answers with one
     * {@link SelectionRange} per requested position, each linked to its parent, and expand-selection wants
     * that list flattened.
     *
     * <p>{@code lineStarts[i]} is the document offset of line {@code i}. A range naming a line the document
     * does not have is dropped rather than clamped — it means the response is stale relative to the buffer,
     * and silently snapping it to the end would select the wrong text.
     *
     * <p>Two shapes are rejected outright because they would break the expand/shrink contract rather than
     * merely be useless: a link that does not <em>strictly</em> contain its child (some servers repeat a
     * range as its own parent, which would make expand appear to do nothing), and a cycle in the parent
     * chain, which is malformed but must not hang the request.
     */
    public static List<int[]> toOffsetChain(List<SelectionRange> ranges, int[] lineStarts) {
        if (ranges == null || ranges.isEmpty() || lineStarts == null || lineStarts.length == 0) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        int guard = 0;
        for (SelectionRange r = ranges.get(0); r != null && guard < MAX_DEPTH; r = r.getParent(), guard++) {
            int[] span = toOffsets(r, lineStarts);
            if (span == null) {
                break; // a bad link invalidates everything above it — its parents are measured from it
            }
            if (!out.isEmpty()) {
                int[] child = out.get(out.size() - 1);
                if (span[0] > child[0] || span[1] < child[1]) {
                    break; // not a containing parent: the chain is not nested, so stop trusting it
                }
                if (span[0] == child[0] && span[1] == child[1]) {
                    continue; // a repeated range would be an expand step that changes nothing
                }
            }
            out.add(span);
        }
        return out;
    }

    /** Depth cap: a malformed self-referential parent chain must not spin. */
    private static final int MAX_DEPTH = 256;

    private static int[] toOffsets(SelectionRange r, int[] lineStarts) {
        if (r == null || r.getRange() == null) {
            return null;
        }
        var range = r.getRange();
        Integer start = offsetOf(range.getStart(), lineStarts);
        Integer end = offsetOf(range.getEnd(), lineStarts);
        if (start == null || end == null || end < start) {
            return null;
        }
        return new int[] {start, end};
    }

    private static Integer offsetOf(org.eclipse.lsp4j.Position p, int[] lineStarts) {
        if (p == null) {
            return null;
        }
        int line = p.getLine();
        if (line < 0 || line >= lineStarts.length) {
            return null;
        }
        return lineStarts[line] + Math.max(0, p.getCharacter());
    }
}
