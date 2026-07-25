package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.FoldRegions.Region;

/**
 * Pure, unit-tested tree queries over the flat {@link FoldRegions.Region} list. {@link FoldRegions#detect}
 * returns a flat list whose regions nest by <em>containment</em>; this derives the depth/parent/sibling
 * structure VS Code's fold-level, recursive-fold and fold-navigation commands need — no re-parsing, no
 * toolkit. Depth is 0-based (an outermost region has depth 0, i.e. fold "level 1"); containment is by line
 * span, matching the best-effort model of the detectors (a bracket inside a string can still nest wrongly,
 * the same caveat the fold gutter already carries).
 */
public final class FoldTree {

    private FoldTree() {}

    /**
     * True when {@code outer} strictly contains {@code inner}: {@code inner}'s span sits within
     * {@code outer}'s and the two are not identical. Equal spans are not containment (neither is a
     * region its own parent).
     */
    public static boolean contains(Region outer, Region inner) {
        if (outer == inner || outer.equals(inner)) {
            return false;
        }
        return outer.startLine() <= inner.startLine() && inner.endLine() <= outer.endLine();
    }

    /** Nesting depth of {@code r} = the number of regions that strictly contain it (outermost = 0). */
    public static int depthOf(List<Region> regions, Region r) {
        int depth = 0;
        for (Region other : regions) {
            if (contains(other, r)) {
                depth++;
            }
        }
        return depth;
    }

    /**
     * Every region at fold <b>level</b> {@code level} (1-based, VS Code's {@code foldLevel1}..{@code 7}) —
     * i.e. depth {@code level - 1}. Folding these collapses that nesting level while shallower levels stay
     * open and deeper ones are hidden inside the folds.
     */
    public static List<Region> atLevel(List<Region> regions, int level) {
        int wantDepth = Math.max(0, level - 1);
        List<Region> out = new ArrayList<>();
        for (Region r : regions) {
            if (depthOf(regions, r) == wantDepth) {
                out.add(r);
            }
        }
        return out;
    }

    /** Every region strictly contained by {@code r} (its descendants at any depth). */
    public static List<Region> descendantsOf(List<Region> regions, Region r) {
        List<Region> out = new ArrayList<>();
        for (Region other : regions) {
            if (contains(r, other)) {
                out.add(other);
            }
        }
        return out;
    }

    /**
     * The innermost region whose line span contains {@code line} (header or body), or {@code null} when
     * {@code line} is outside every region. "Innermost" = the containing region with the largest start
     * line, breaking ties toward the smaller (later-ending) span.
     */
    public static Region innermostContaining(List<Region> regions, int line) {
        Region best = null;
        for (Region r : regions) {
            if (r.startLine() <= line && line <= r.endLine()) {
                if (best == null
                        || r.startLine() > best.startLine()
                        || (r.startLine() == best.startLine() && r.endLine() < best.endLine())) {
                    best = r;
                }
            }
        }
        return best;
    }

    /**
     * The parent fold to jump to for "Go to Parent Fold" from {@code line}: when the caret sits on the
     * header of its innermost region, the region enclosing that one; otherwise the innermost region's own
     * header. Returns {@code null} at the top level (nothing to ascend to).
     */
    public static Region parentFold(List<Region> regions, int line) {
        Region innermost = innermostContaining(regions, line);
        if (innermost == null) {
            return null;
        }
        if (line != innermost.startLine()) {
            return innermost; // caret is in the body → ascend to this fold's header
        }
        // Already on the header → ascend to the enclosing fold (the smallest region containing it).
        Region parent = null;
        for (Region r : regions) {
            if (contains(r, innermost)
                    && (parent == null
                            || r.startLine() > parent.startLine()
                            || (r.startLine() == parent.startLine() && r.endLine() < parent.endLine()))) {
                parent = r;
            }
        }
        return parent;
    }

    /** The next fold whose header starts after {@code line} (smallest such start), or {@code null}. */
    public static Region nextFold(List<Region> regions, int line) {
        Region best = null;
        for (Region r : regions) {
            if (r.startLine() > line && (best == null || r.startLine() < best.startLine())) {
                best = r;
            }
        }
        return best;
    }

    /** The previous fold whose header starts before {@code line} (largest such start), or {@code null}. */
    public static Region previousFold(List<Region> regions, int line) {
        Region best = null;
        for (Region r : regions) {
            if (r.startLine() < line && (best == null || r.startLine() > best.startLine())) {
                best = r;
            }
        }
        return best;
    }
}
