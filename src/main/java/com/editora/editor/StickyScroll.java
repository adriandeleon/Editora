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

    /**
     * {@link #headerLines(List, int, int)} for one region list, without scanning the list per query.
     *
     * <p>The pinned lines are asked for on every scroll step, and a large file has tens of thousands of
     * regions. Sorted by start line, each region gets a <em>parent</em>: the nearest earlier region that is
     * still open where it starts. Every region containing a line is then on the parent chain of the last
     * region starting above that line, so a query is a binary search plus a walk of the nesting depth.
     * Regions that overlap without nesting ({@code } else {} puts an end and a start on one line) only make
     * a chain carry an entry that the walk's own containment test rejects.
     */
    static final class Index {

        static final Index EMPTY = new Index(null, new int[0], new int[0], new int[0]);

        private final List<Region> source;
        private final int[] start;
        private final int[] end;
        private final int[] parent;

        private Index(List<Region> source, int[] start, int[] end, int[] parent) {
            this.source = source;
            this.start = start;
            this.end = end;
            this.parent = parent;
        }

        static Index of(List<Region> regions) {
            if (regions == null || regions.isEmpty()) {
                return new Index(regions, new int[0], new int[0], new int[0]);
            }
            List<Region> sorted = new ArrayList<>(regions);
            sorted.sort((a, b) -> a.startLine() != b.startLine()
                    ? Integer.compare(a.startLine(), b.startLine())
                    : Integer.compare(b.endLine(), a.endLine()));
            int n = sorted.size();
            int[] start = new int[n];
            int[] end = new int[n];
            int[] parent = new int[n];
            int[] open = new int[n]; // indices of the regions still open, innermost last
            int depth = 0;
            for (int i = 0; i < n; i++) {
                start[i] = sorted.get(i).startLine();
                end[i] = sorted.get(i).endLine();
                while (depth > 0 && end[open[depth - 1]] < start[i]) {
                    depth--;
                }
                parent[i] = depth == 0 ? -1 : open[depth - 1];
                open[depth++] = i;
            }
            return new Index(regions, start, end, parent);
        }

        /** Whether this index was built from exactly this list (the fold manager replaces it wholesale). */
        boolean isFor(List<Region> regions) {
            return source == regions;
        }

        List<Integer> headerLines(int firstVisible, int max) {
            if (start.length == 0 || firstVisible <= 0 || max <= 0) {
                return List.of();
            }
            int lo = 0;
            int hi = start.length; // first index whose start is >= firstVisible
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (start[mid] < firstVisible) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            List<Integer> inner = new ArrayList<>();
            for (int i = lo - 1; i >= 0; i = parent[i]) {
                boolean repeated = !inner.isEmpty() && inner.get(inner.size() - 1) == start[i];
                if (end[i] >= firstVisible && !repeated) {
                    inner.add(start[i]);
                }
            }
            int keep = Math.min(max, inner.size());
            Integer[] out = new Integer[keep];
            for (int k = 0; k < keep; k++) {
                out[k] = inner.get(inner.size() - 1 - k); // the walk ran innermost first
            }
            return List.of(out);
        }
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
