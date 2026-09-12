package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.FoldRegions.Region;

/**
 * Decides which lines to pin above the viewport — the enclosing scope headers that have scrolled off.
 *
 * <p>Deep in a long method you can see the code and not what it belongs to; scrolling up to find out and
 * back down again is the navigation this removes. The information is already there: {@link FoldRegions}
 * knows every block in the file, and a block whose header is above the viewport while its body is inside
 * it is exactly a scope you are currently in but cannot see the name of.
 *
 * <p>Pure and toolkit-free, so the decision is unit-testable on its own; {@code StickyScrollBar} renders
 * whatever this returns.
 */
public final class StickyScroll {

    private StickyScroll() {}

    /** Default cap on pinned rows — beyond a few, the pin eats the viewport it is meant to explain. */
    public static final int DEFAULT_MAX = 5;

    /**
     * The lines to pin for a viewport starting at {@code firstVisible}, outermost first.
     *
     * <p>A region qualifies when it <em>contains</em> the first visible line and its own header sits above
     * it. The second half is what keeps a header from being shown twice: a block whose header is itself on
     * screen needs no pin, and pinning it would cover the real line with a copy of itself.
     *
     * <p>When the chain is deeper than {@code max}, the <em>outermost</em> entries are kept. The innermost
     * scope is the one you can most easily infer from the code in front of you; the file-level type you
     * are in is the one that has been off screen longest.
     */
    public static List<Integer> headerLines(List<Region> regions, int firstVisible, int max) {
        if (regions == null || regions.isEmpty() || firstVisible <= 0 || max <= 0) {
            return List.of();
        }
        List<Integer> starts = new ArrayList<>();
        for (Region r : regions) {
            if (r.startLine() < firstVisible && firstVisible <= r.endLine()) {
                starts.add(r.startLine());
            }
        }
        starts.sort(Integer::compare);
        // A detector may report two regions opening on one line (a brace and a bracket, say); pinning the
        // same line twice would render it twice.
        List<Integer> unique = new ArrayList<>(starts.size());
        for (int line : starts) {
            if (unique.isEmpty() || unique.get(unique.size() - 1) != line) {
                unique.add(line);
            }
        }
        return unique.size() <= max ? List.copyOf(unique) : List.copyOf(unique.subList(0, max));
    }

    /** As {@link #headerLines(List, int, int)} with {@link #DEFAULT_MAX}. */
    public static List<Integer> headerLines(List<Region> regions, int firstVisible) {
        return headerLines(regions, firstVisible, DEFAULT_MAX);
    }

    /**
     * The first viewport line that leaves {@code target} unobscured by the pinned headers above it.
     *
     * <p>Navigation surfaces commonly put their destination at the top of the viewport. That position is
     * underneath this overlay whenever the destination is inside a fold region. Work backwards by the
     * number of headers that the resulting viewport would pin; iterating matters near a nested scope's
     * header, where moving the viewport up can make that scope visible and remove it from the pinned set.
     */
    public static int navigationFirstVisible(List<Region> regions, int target, int max) {
        int clampedTarget = Math.max(0, target);
        int firstVisible = clampedTarget;
        for (int i = 0; i <= Math.max(0, max); i++) {
            int candidate = Math.max(
                    0, clampedTarget - headerLines(regions, firstVisible, max).size());
            if (candidate == firstVisible) {
                return candidate;
            }
            firstVisible = candidate;
        }
        return firstVisible;
    }

    /** As {@link #navigationFirstVisible(List, int, int)} with {@link #DEFAULT_MAX}. */
    public static int navigationFirstVisible(List<Region> regions, int target) {
        return navigationFirstVisible(regions, target, DEFAULT_MAX);
    }
}
