package com.editora.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which responses a full History keeps. A plain "newest N" let one Run All of a long file push out every older
 * response, including the only response of a request the Run All never reached. Here the newest entry of each
 * request is kept first (newest request first), and the remaining places go to the newest of the rest; the
 * result keeps the newest-first order. Pure — tested by {@code HttpHistoryRetentionTest}.
 */
final class HttpHistoryRetention {

    private HttpHistoryRetention() {}

    /** {@code newestFirst} cut to at most {@code max} entries, keeping each request's newest entry first. */
    static List<HttpHistoryEntry> keep(List<HttpHistoryEntry> newestFirst, int max) {
        if (newestFirst == null || newestFirst.isEmpty() || max <= 0) {
            return List.of();
        }
        if (newestFirst.size() <= max) {
            return List.copyOf(newestFirst);
        }
        Set<Integer> chosen = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < newestFirst.size() && chosen.size() < max; i++) {
            if (seen.add(key(newestFirst.get(i)))) {
                chosen.add(i);
            }
        }
        for (int i = 0; i < newestFirst.size() && chosen.size() < max; i++) {
            chosen.add(i);
        }
        List<HttpHistoryEntry> out = new ArrayList<>(chosen.size());
        for (int i = 0; i < newestFirst.size(); i++) {
            if (chosen.contains(i)) {
                out.add(newestFirst.get(i));
            }
        }
        return out;
    }

    /**
     * What makes two entries "the same request": its name, else its position and method (an unnamed request whose
     * URL varies is still one request), else its method and the URL it sent — as the saved history decides it.
     */
    static String key(HttpHistoryEntry e) {
        var ex = e.exchange();
        return com.editora.config.HttpResponseHistory.requestKey(e.name(), e.requestIndex(), ex.method(), ex.url());
    }
}
