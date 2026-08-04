package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a breadcrumb folder listing into submenu-sized pages.
 *
 * <p>A folder like {@code ~} can hold hundreds of entries, and a flat {@code ContextMenu} of that many
 * items is taller than the screen — it scrolls with tiny arrow buttons and there is no way to jump. Paging
 * turns it into a short menu of ranges, each opening a submenu of at most {@link #PAGE_SIZE} entries.
 *
 * <p>Pure (no toolkit) so the chunking and the range labels are unit-testable.
 */
final class BreadcrumbPages {

    /** Maximum entries in one page — about a screen's worth of menu on a laptop. */
    static final int PAGE_SIZE = 30;

    /**
     * A trailing page smaller than this is folded into the one before it, so a listing of 31 shows one page
     * of 31 rather than a page of 30 and a page of 1.
     */
    static final int MIN_TAIL = PAGE_SIZE / 3;

    /** Longest name shown on either side of a range label before it is elided. */
    static final int MAX_LABEL_PART = 14;

    private BreadcrumbPages() {}

    /**
     * Half-open {@code [from, to)} ranges covering {@code total} items, or an <b>empty list</b> when the
     * listing is short enough to show flat — the caller uses that as the "don't page at all" signal, so
     * the common small folder keeps exactly the menu it had before.
     */
    static List<int[]> pages(int total, int pageSize) {
        List<int[]> out = new ArrayList<>();
        if (total <= pageSize || pageSize <= 0) {
            return out;
        }
        for (int from = 0; from < total; from += pageSize) {
            out.add(new int[] {from, Math.min(total, from + pageSize)});
        }
        // Fold a stub last page into its predecessor.
        int last = out.size() - 1;
        if (out.size() > 1 && out.get(last)[1] - out.get(last)[0] < MIN_TAIL) {
            out.get(last - 1)[1] = out.get(last)[1];
            out.remove(last);
        }
        return out;
    }

    /** {@code "android … cargo"} — the first and last entry of a page, each elided if long. */
    static String label(String first, String last) {
        return elide(first) + " … " + elide(last);
    }

    private static String elide(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_LABEL_PART ? s : s.substring(0, MAX_LABEL_PART - 1) + "…";
    }
}
