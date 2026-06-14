package com.editora.history;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.config.HistoryRevision;

/**
 * Pure, unit-tested retention policy for the Local File History — no I/O, no FX. Decides which
 * revisions survive (and which content blobs are still referenced) so the on-disk history stays
 * bounded. Revision lists are kept <b>newest-first</b> throughout.
 *
 * <ul>
 *   <li>{@link #isDuplicate} — skip a snapshot whose content matches the newest existing revision.
 *   <li>{@link #prune} — per-file caps: drop revisions older than a max age (the newest always
 *       survives), then keep only the newest N.
 *   <li>{@link #enforceProjectBudget} — across a whole project bucket, evict the globally-oldest
 *       revisions until the total uncompressed size is within budget.
 *   <li>{@link #liveHashes} — all sha256 hashes still referenced by any revision (for blob GC).
 * </ul>
 */
public final class HistoryRetention {

    /** Configurable limits (built from {@code Settings} at snapshot time). */
    public record RetentionPolicy(int maxPerFile, long maxAgeMillis, long maxTotalBytesPerProject) {}

    private HistoryRetention() {}

    /** True when {@code newSha} equals the newest (index 0) existing revision's sha — a no-op save. */
    public static boolean isDuplicate(List<HistoryRevision> existing, String newSha) {
        if (existing == null || existing.isEmpty() || newSha == null) {
            return false;
        }
        return newSha.equals(existing.get(0).sha256());
    }

    /**
     * Per-file pruning over a newest-first list: drop revisions older than {@code maxAgeMillis} (when
     * positive; the newest revision is always kept regardless), then cap to the newest {@code maxPerFile}
     * (when positive). Returns a new list; the input is not mutated.
     */
    public static List<HistoryRevision> prune(
            List<HistoryRevision> revisions, int maxPerFile, long maxAgeMillis, long now) {
        if (revisions == null || revisions.isEmpty()) {
            return new ArrayList<>();
        }
        List<HistoryRevision> out = new ArrayList<>(revisions.size());
        for (int i = 0; i < revisions.size(); i++) {
            HistoryRevision r = revisions.get(i);
            boolean tooOld = maxAgeMillis > 0 && (now - r.timestamp()) > maxAgeMillis;
            if (i == 0 || !tooOld) {
                out.add(r);
            }
        }
        if (maxPerFile > 0 && out.size() > maxPerFile) {
            out = new ArrayList<>(out.subList(0, maxPerFile));
        }
        return out;
    }

    /**
     * Enforces a per-project total-size cap across every file's revisions: if the summed
     * {@code sizeBytes} exceeds {@code maxTotalBytes}, evicts the globally-oldest revisions (across all
     * files) until within budget, dropping any file entry that becomes empty. The newest revision of each
     * file is preserved so no file loses its entire history. Returns a new bucket map; the input is not
     * mutated. A non-positive budget means "unbounded" (returned unchanged-but-copied).
     */
    public static Map<String, List<HistoryRevision>> enforceProjectBudget(
            Map<String, List<HistoryRevision>> bucket, long maxTotalBytes) {
        Map<String, List<HistoryRevision>> out = new LinkedHashMap<>();
        if (bucket == null) {
            return out;
        }
        long total = 0;
        for (Map.Entry<String, List<HistoryRevision>> e : bucket.entrySet()) {
            List<HistoryRevision> copy = new ArrayList<>(e.getValue());
            out.put(e.getKey(), copy);
            for (HistoryRevision r : copy) {
                total += r.sizeBytes();
            }
        }
        if (maxTotalBytes <= 0 || total <= maxTotalBytes) {
            return out;
        }
        // Repeatedly drop the oldest evictable revision (not a file's last surviving one) until in budget.
        while (total > maxTotalBytes) {
            String victimFile = null;
            int victimIndex = -1;
            long victimTs = Long.MAX_VALUE;
            for (Map.Entry<String, List<HistoryRevision>> e : out.entrySet()) {
                List<HistoryRevision> list = e.getValue();
                if (list.size() <= 1) {
                    continue; // keep each file's newest revision
                }
                HistoryRevision oldest = list.get(list.size() - 1); // newest-first ⇒ last is oldest
                if (oldest.timestamp() < victimTs) {
                    victimTs = oldest.timestamp();
                    victimFile = e.getKey();
                    victimIndex = list.size() - 1;
                }
            }
            if (victimFile == null) {
                break; // nothing left to evict (every file down to its last revision)
            }
            total -= out.get(victimFile).remove(victimIndex).sizeBytes();
        }
        return out;
    }

    /** All sha256 hashes still referenced by any revision in any project (the blobs to keep). */
    public static Set<String> liveHashes(Map<String, Map<String, List<HistoryRevision>>> byProject) {
        Set<String> live = new HashSet<>();
        if (byProject == null) {
            return live;
        }
        for (Map<String, List<HistoryRevision>> bucket : byProject.values()) {
            if (bucket == null) {
                continue;
            }
            for (List<HistoryRevision> list : bucket.values()) {
                if (list == null) {
                    continue;
                }
                for (HistoryRevision r : list) {
                    if (r.sha256() != null && !r.sha256().isEmpty()) {
                        live.add(r.sha256());
                    }
                }
            }
        }
        return live;
    }
}
