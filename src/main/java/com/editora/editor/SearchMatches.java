package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable, compact run of find matches in document order: {@code [start, end)} offset pairs held in one
 * flat {@code int[]} rather than one small array per match.
 *
 * <p>It may be a <em>page</em> of a longer result. A query like {@code e} matches millions of times in a
 * large file, and holding (or even counting) them all is what made such a search cost hundreds of megabytes
 * and hundreds of milliseconds; {@link SearchMatcher#around} keeps at most a bounded number of matches near
 * an offset and records how many precede them ({@link #before()}) and whether more follow
 * ({@link #moreAfter()}), so the page still knows each match's true ordinal.
 *
 * <p>Matches never overlap, so starts strictly increase and ends never decrease — every lookup here is a
 * binary search.
 */
public final class SearchMatches {

    /** No matches. */
    public static final SearchMatches EMPTY = new SearchMatches(new int[0], 0, 0, false, true);

    private final int[] offsets;
    private final int size;
    private final long before;
    private final boolean moreAfter;
    private final boolean complete;

    /**
     * @param offsets start/end pairs; the first {@code 2 * size} entries are used and must not be modified
     * @param before how many matches of the document precede the first one held
     * @param moreAfter whether the document has matches after the last one held
     * @param complete false when the search was abandoned part-way (a regex out of time or stack)
     */
    SearchMatches(int[] offsets, int size, long before, boolean moreAfter, boolean complete) {
        this.offsets = offsets;
        this.size = size;
        this.before = before;
        this.moreAfter = moreAfter;
        this.complete = complete;
    }

    /** One match. */
    public static SearchMatches of(int start, int end) {
        return new SearchMatches(new int[] {start, end}, 1, 0, false, true);
    }

    /** All of {@code pairs} (each {@code {start, end}}, in document order) as one complete result. */
    public static SearchMatches ofPairs(List<int[]> pairs) {
        int[] flat = new int[pairs.size() * 2];
        for (int i = 0; i < pairs.size(); i++) {
            flat[2 * i] = pairs.get(i)[0];
            flat[2 * i + 1] = pairs.get(i)[1];
        }
        return new SearchMatches(flat, pairs.size(), 0, false, true);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int start(int index) {
        return offsets[2 * index];
    }

    public int end(int index) {
        return offsets[2 * index + 1];
    }

    /** How many matches of the document precede the first one held here. */
    public long before() {
        return before;
    }

    /** Whether the document has matches after the last one held here (their number is not known). */
    public boolean moreAfter() {
        return moreAfter;
    }

    /** False when the search was abandoned part-way, so what is held is not everything there is. */
    public boolean complete() {
        return complete;
    }

    /** The 1-based position of match {@code index} among all the document's matches. */
    public long ordinal(int index) {
        return before + index + 1;
    }

    /** The number of matches known to exist: the exact total unless {@link #moreAfter()}. */
    public long counted() {
        return before + size;
    }

    /** Index of the first match starting at or after {@code offset}; {@link #size()} when there is none. */
    public int firstStartingAtOrAfter(int offset) {
        int lo = 0;
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (offsets[2 * mid] < offset) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Index of the first match ending at or after {@code offset}; {@link #size()} when there is none. With
     * "while it starts at or before the last visible offset" this walks exactly the matches that touch a
     * range, without visiting the ones before it.
     */
    public int firstEndingAtOrAfter(int offset) {
        int lo = 0;
        int hi = size;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (offsets[2 * mid + 1] < offset) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** Index of the match that contains or starts at {@code caret}, else -1. */
    public int indexAt(int caret) {
        int i = firstEndingAtOrAfter(caret);
        return i < size && offsets[2 * i] <= caret ? i : -1;
    }

    /** Index of the match that is exactly {@code [start, end)}, else -1. */
    public int indexOf(int start, int end) {
        int i = firstStartingAtOrAfter(start);
        return i < size && offsets[2 * i] == start && offsets[2 * i + 1] == end ? i : -1;
    }

    /**
     * The match to jump to from {@code fromOffset} <em>when these matches alone can tell</em>: forward, the
     * first one starting at or after it, wrapping to the document's first; backward, the last one starting
     * before it, wrapping to the document's last. Returns -1 when the answer may lie outside this page (or
     * there are no matches), in which case the caller searches again around {@code fromOffset}
     * ({@link SearchMatcher#locate}). A result that holds every match always answers.
     */
    public int step(int fromOffset, boolean forward) {
        if (size == 0) {
            return -1;
        }
        int at = firstStartingAtOrAfter(fromOffset);
        if (forward) {
            if (at < size) {
                // The first one held is the answer only if nothing unseen could start in between.
                return at > 0 || before == 0 || offsets[0] == fromOffset ? at : -1;
            }
            return !moreAfter && before == 0 ? 0 : -1; // past the last match: wrap to the first, if held
        }
        if (at > 0) {
            return at < size || !moreAfter ? at - 1 : -1;
        }
        return before == 0 && !moreAfter ? size - 1 : -1; // before the first match: wrap to the last, if held
    }

    /**
     * Whether a match starting at {@code offset} would have to be among the ones held — so that not finding
     * it here means it is not a match at all.
     */
    public boolean covers(int offset) {
        boolean fromTheFirst = before == 0 || (size > 0 && offset >= offsets[0]);
        boolean toTheLast = !moreAfter || (size > 0 && offset <= offsets[2 * (size - 1)]);
        return fromTheFirst && toTheLast;
    }

    /** The matches as {@code {start, end}} pairs — for callers that want every one as an object. */
    public List<int[]> toList() {
        List<int[]> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(new int[] {offsets[2 * i], offsets[2 * i + 1]});
        }
        return out;
    }
}
