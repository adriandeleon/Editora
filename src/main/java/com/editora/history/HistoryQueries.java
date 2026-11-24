package com.editora.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.config.HistoryRevision;
import org.jspecify.annotations.Nullable;

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
        return matches(r, query, null);
    }

    /**
     * As {@link #matches(HistoryRevision, String)}, also matching {@code reasonLabel} — the reason as the
     * list <em>shows</em> it ("Auto-save", "Sauvegarde automatique"). The stored reason is an identifier
     * ({@code AUTOSAVE}); a user filters by the word in front of them, and in a translated UI the identifier
     * matches nothing they can see. The identifier keeps matching too. {@code reasonLabel} may be null.
     */
    public static boolean matches(HistoryRevision r, String query, @Nullable String reasonLabel) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (r == null) {
            return false;
        }
        String q = query.toLowerCase(Locale.ROOT).strip();
        return contains(r.label(), q) || contains(r.reason(), q) || contains(reasonLabel, q);
    }

    private static boolean contains(@Nullable String haystack, String lowerNeedle) {
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

    /**
     * As {@link #folderRevisions(Map, String)} over several buckets, each file's lists merged as
     * {@link #mergedRevisions} merges them, in the order the files are first met (bucket by bucket). Only the
     * keys under the folder are merged: merging every key of every bucket first, as the folder view did, cost a
     * lookup per key per bucket on each tab switch.
     */
    public static Map<String, List<HistoryRevision>> mergedFolderRevisions(
            List<Map<String, List<HistoryRevision>>> buckets, String folderKey) {
        Map<String, List<HistoryRevision>> out = new java.util.LinkedHashMap<>();
        if (buckets == null || folderKey == null || folderKey.isBlank()) {
            return out;
        }
        for (Map<String, List<HistoryRevision>> bucket : buckets) {
            for (String key : folderRevisions(bucket, folderKey).keySet()) {
                out.computeIfAbsent(key, file -> mergedRevisions(buckets, file));
            }
        }
        return out;
    }

    /**
     * Every revision of the file at {@code key} in {@code buckets}: one list, newest first, a row present in
     * several listed once. When one bucket alone knows the file its list is returned as it is.
     */
    public static List<HistoryRevision> mergedRevisions(List<Map<String, List<HistoryRevision>>> buckets, String key) {
        List<HistoryRevision> only = null;
        java.util.Set<HistoryRevision> all = null;
        for (Map<String, List<HistoryRevision>> bucket : buckets) {
            List<HistoryRevision> list = bucket.get(key);
            if (list == null || list.isEmpty()) {
                continue;
            }
            if (only == null && all == null) {
                only = list;
                continue;
            }
            if (all == null) {
                all = new java.util.LinkedHashSet<>(only);
            }
            all.addAll(list);
        }
        if (all == null) {
            return only == null ? List.of() : only;
        }
        List<HistoryRevision> merged = new ArrayList<>(all);
        merged.sort(Comparator.comparingLong(HistoryRevision::timestamp).reversed()); // stable
        return merged;
    }

    /** True if {@code key} is a path strictly under directory {@code folder} (segment-boundary aware). */
    static boolean isUnder(String key, String folder) {
        if (key == null || !key.startsWith(folder) || key.length() == folder.length()) {
            return false;
        }
        if (folder.endsWith("/") || folder.endsWith("\\")) {
            return true; // the folder is a filesystem root ("/"): its own separator is the boundary
        }
        char sep = key.charAt(folder.length());
        return sep == '/' || sep == '\\';
    }
}
