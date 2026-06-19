package com.editora.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.config.HistoryRevision;

/**
 * Pure, unit-tested queries over the Local File History (filtering, cross-file aggregation). Kept free of any
 * toolkit/config dependency so it can be tested without a JavaFX runtime — the impure plumbing
 * ({@code HistoryService}, the bucket persistence) lives elsewhere.
 */
public final class HistoryQueries {

    private HistoryQueries() {}

    /**
     * Metadata match for the File History filter: {@code true} when {@code query} is blank, or (case-
     * insensitively) a substring of the revision's {@code label} or {@code reason}. Timestamp matching is
     * done by the panel (it owns the display formatter); this stays pure and zone-free.
     */
    public static boolean matches(HistoryRevision r, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (r == null) {
            return false;
        }
        String q = query.toLowerCase(Locale.ROOT).strip();
        return contains(r.label(), q) || contains(r.reason(), q);
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }

    /**
     * The most recent revisions across <em>all</em> files in a project bucket, newest-first, capped at
     * {@code limit} (≤0 ⇒ unbounded). Powers the "Recent Changes" action. Input lists are per-file
     * newest-first; this flattens and re-sorts by timestamp (descending) so the order is correct across files.
     */
    public static List<HistoryRevision> recent(Map<String, List<HistoryRevision>> bucket, int limit) {
        List<HistoryRevision> all = new ArrayList<>();
        if (bucket != null) {
            for (List<HistoryRevision> revs : bucket.values()) {
                if (revs != null) {
                    all.addAll(revs);
                }
            }
        }
        all.sort(Comparator.comparingLong(HistoryRevision::timestamp).reversed());
        if (limit > 0 && all.size() > limit) {
            return new ArrayList<>(all.subList(0, limit));
        }
        return all;
    }

    /**
     * The history entries (file key → newest-first revisions) for files <em>under</em> {@code folderKey},
     * preserving the bucket's iteration order. A file directly at or outside the folder is excluded; matching
     * is by path-segment boundary, so {@code /foo} does not match {@code /foobar}. Powers the folder-history
     * view (a "deleted" file is one of these keys whose path no longer exists on disk — determined by the
     * caller, which keeps this pure/zone-free).
     */
    public static Map<String, List<HistoryRevision>> folderRevisions(
            Map<String, List<HistoryRevision>> bucket, String folderKey) {
        Map<String, List<HistoryRevision>> out = new java.util.LinkedHashMap<>();
        if (bucket == null || folderKey == null || folderKey.isBlank()) {
            return out;
        }
        String folder = folderKey;
        while (folder.length() > 1 && (folder.endsWith("/") || folder.endsWith("\\"))) {
            folder = folder.substring(0, folder.length() - 1);
        }
        for (Map.Entry<String, List<HistoryRevision>> e : bucket.entrySet()) {
            if (isUnder(e.getKey(), folder)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** True if {@code key} is a path strictly under directory {@code folder} (segment-boundary aware). */
    static boolean isUnder(String key, String folder) {
        if (key == null || !key.startsWith(folder) || key.length() == folder.length()) {
            return false;
        }
        char sep = key.charAt(folder.length());
        return sep == '/' || sep == '\\';
    }
}
