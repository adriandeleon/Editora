package com.editora.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import com.editora.editor.FoldRegions;
import org.eclipse.lsp4j.FoldingRange;

/**
 * Maps a server's {@code textDocument/foldingRange} answer onto the editor's own {@link FoldRegions.Region}
 * list (#738), so a language server's grammar-accurate regions can stand in for the brace/indent heuristic.
 *
 * <p>Pure and unit-tested: this is where the two models are reconciled, and every mismatch shows up as folds
 * that hide the wrong lines rather than as an exception.
 *
 * <p><b>The two models line up, but only under {@code lineFoldingOnly}</b>, which we declare. LSP defines the
 * folded span as "after {@code startLine} through {@code endLine}", and Editora's {@code Region(start, end)}
 * is the header line through the last folded line inclusive — the same pair of numbers. The optional
 * {@code startCharacter}/{@code endCharacter} are deliberately ignored for that reason; honouring them would
 * mean partial-line folds the gutter can't express.
 */
public final class LspFolding {

    private LspFolding() {}

    /**
     * The foldable regions for a server response, in the order {@link FoldRegions} itself would emit them.
     *
     * <p>Ordering matters and is not cosmetic: {@code FoldManager.foldRecursivelyAtCaret} collapses
     * <em>deepest-first</em> and relies on the detector's innermost-first convention (the brace scanner emits
     * a region when it reaches the closing delimiter, so an inner block is emitted before the outer one that
     * contains it). Sorting by {@code endLine} ascending, breaking ties on {@code startLine} descending,
     * reproduces exactly that order for any nesting — servers are free to answer outermost-first, and several
     * do.
     *
     * <p>Ranges that cannot be folded line-wise are dropped rather than clamped: a single-line range
     * ({@code endLine <= startLine}) would produce a fold that hides nothing but still draws a chevron, and a
     * negative line is a malformed response.
     */
    public static List<FoldRegions.Region> toRegions(List<FoldingRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return List.of();
        }
        // A set: servers can repeat a range (jdtls emits one per syntactic construct, and a class body and
        // its only method can coincide), and a duplicate would put two chevrons on one line.
        LinkedHashSet<FoldRegions.Region> unique = new LinkedHashSet<>();
        for (FoldingRange r : ranges) {
            if (r == null) {
                continue;
            }
            int start = r.getStartLine();
            int end = r.getEndLine();
            if (start < 0 || end <= start) {
                continue;
            }
            unique.add(new FoldRegions.Region(start, end));
        }
        List<FoldRegions.Region> out = new ArrayList<>(unique);
        out.sort(Comparator.comparingInt(FoldRegions.Region::endLine)
                .thenComparing(
                        Comparator.comparingInt(FoldRegions.Region::startLine).reversed()));
        return out;
    }
}
