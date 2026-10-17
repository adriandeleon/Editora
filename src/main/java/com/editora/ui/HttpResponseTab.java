package com.editora.ui;

import java.util.Set;

/**
 * The response view's tabs ({@link HttpClientPanel}), in strip order, and which one a shown response opens on.
 * Pure — tested by {@code HttpResponseTabTest}.
 */
enum HttpResponseTab {
    BODY,
    HEADERS,
    TESTS,
    REQUEST,
    NOTES;

    /**
     * The tab to show for a response: Tests when a test or a script failed ({@code testsFailed}), else the tab the
     * user last picked when this response has it, else Body. Warnings never switch tabs: they only colour the Notes
     * count.
     */
    static HttpResponseTab choose(HttpResponseTab picked, boolean testsFailed, Set<HttpResponseTab> available) {
        if (testsFailed && available.contains(TESTS)) {
            return TESTS;
        }
        return picked != null && available.contains(picked) ? picked : BODY;
    }

    /**
     * The tab {@code step} places from {@code from} among the {@code available} ones (Left/Right), wrapping at the
     * ends; {@code from} itself when it is the only one.
     */
    static HttpResponseTab step(HttpResponseTab from, int step, Set<HttpResponseTab> available) {
        HttpResponseTab[] all = values();
        int i = from.ordinal();
        for (int n = 0; n < all.length; n++) {
            i = Math.floorMod(i + Integer.signum(step), all.length);
            if (available.contains(all[i])) {
                return all[i];
            }
        }
        return from;
    }

    /** The first ({@code last} false) or last available tab (Home/End). */
    static HttpResponseTab end(boolean last, Set<HttpResponseTab> available) {
        HttpResponseTab[] all = values();
        for (int n = 0; n < all.length; n++) {
            HttpResponseTab t = all[last ? all.length - 1 - n : n];
            if (available.contains(t)) {
                return t;
            }
        }
        return BODY;
    }
}
