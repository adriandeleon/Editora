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
 *   <li>{@link #sweep} — the whole policy over every project, including files that are never saved again.
 *   <li>{@link #liveHashes} — all sha256 hashes still referenced by any revision (for blob GC).
 * </ul>
 *
 * <p>Three kinds of revision are <em>protected</em> from the ordinary age limit, count cap and byte budget:
 * a file's newest revision, a labelled restore point, and the copy captured before a delete. Protection is
 * a longer lease, not a permanent one: such a revision expires after
 * {@link #protectedMaxAgeMillis}, so a file saved once and never touched again does not stay on disk
 * forever.
 */
public final class HistoryRetention {

    /** Configurable limits (built from {@code Settings} at snapshot time). */
    public record RetentionPolicy(int maxPerFile, long maxAgeMillis, long maxTotalBytesPerProject) {}

    private HistoryRetention() {}

    /** A protected revision outlives the configured maximum age by this factor… */
    static final int PROTECTED_AGE_FACTOR = 6;
    /** …and is never expired sooner than this (180 days), however short the configured age. */
    static final long PROTECTED_MIN_AGE_MILLIS = 180L * 86_400_000L;

    /**
     * How long a protected revision (see {@link #isProtected}, plus each file's newest) is kept: six times
     * the configured maximum age and at least 180 days. {@code 0} — no expiry — when the age limit itself is
     * off ({@code maxAgeMillis <= 0}).
     */
    public static long protectedMaxAgeMillis(long maxAgeMillis) {
        if (maxAgeMillis <= 0) {
            return 0;
        }
        long scaled = maxAgeMillis > Long.MAX_VALUE / PROTECTED_AGE_FACTOR
                ? Long.MAX_VALUE
                : maxAgeMillis * PROTECTED_AGE_FACTOR;
        return Math.max(scaled, PROTECTED_MIN_AGE_MILLIS);
    }

    /** True when {@code newSha} equals the newest (index 0) existing revision's sha — a no-op save. */
    public static boolean isDuplicate(List<HistoryRevision> existing, String newSha) {
        if (existing == null || existing.isEmpty() || newSha == null) {
            return false;
        }
        return newSha.equals(existing.get(0).sha256());
    }

    /**
     * Per-file pruning over a newest-first list: drop revisions older than {@code maxAgeMillis} (when
     * positive; the newest revision is always kept regardless, and a protected one until
     * {@link #protectedMaxAgeMillis}), then cap to the newest {@code maxPerFile} (when positive). Returns a
     * new list; the input is not mutated.
     */
    public static List<HistoryRevision> prune(
            List<HistoryRevision> revisions, int maxPerFile, long maxAgeMillis, long now) {
        if (revisions == null || revisions.isEmpty()) {
            return new ArrayList<>();
        }
        List<HistoryRevision> out = new ArrayList<>(revisions.size());
        long protectedAge = protectedMaxAgeMillis(maxAgeMillis);
        for (int i = 0; i < revisions.size(); i++) {
            HistoryRevision r = revisions.get(i);
            long age = now - r.timestamp();
            boolean tooOld = maxAgeMillis > 0 && age > maxAgeMillis;
            boolean leaseExpired = protectedAge > 0 && age > protectedAge;
            if (i == 0 || !tooOld || (isProtected(r) && !leaseExpired)) {
                out.add(r);
            }
        }
        if (maxPerFile > 0 && out.size() > maxPerFile) {
            // The cap counts only automatic revisions: a labelled restore point (or the last copy of a
            // deleted file) is deliberate, and with autosave on, 50 automatic revisions is a few hours of
            // editing — so counting them evicted the user's named revision the same day they made it.
            List<HistoryRevision> capped = new ArrayList<>(out.size());
            int automatic = 0;
            for (HistoryRevision r : out) {
                if (isProtected(r)) {
                    capped.add(r);
                } else if (automatic < maxPerFile) {
                    automatic++;
                    capped.add(r);
                }
            }
            out = capped;
        }
        return out;
    }

    /**
     * True for a revision that exists because the <b>user</b> asked for it, not because a save happened: a
     * named restore point ("Put Label"), or the copy captured just before a file was deleted — which may be
     * the only copy left. Retention treats those as intent: the ordinary age limit, the count cap and the
     * byte budget all walk past them, and only the much longer {@link #protectedMaxAgeMillis} lease ends them.
     * Everything else is cache.
     */
    public static boolean isProtected(HistoryRevision r) {
        return r != null
                && ((r.label() != null && !r.label().isBlank()) || HistoryRevision.REASON_DELETE.equals(r.reason()));
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
        // Drop the oldest evictable revision (not a file's last surviving one) until in budget. Each file
        // offers one candidate at a time — its oldest unprotected row — through a queue ordered by age, so an
        // eviction costs a queue step rather than another pass over every file of the project.
        record Candidate(long timestamp, int fileOrder, List<HistoryRevision> list, int index) {}
        java.util.PriorityQueue<Candidate> oldest = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(Candidate::timestamp).thenComparingInt(Candidate::fileOrder));
        int order = 0;
        for (List<HistoryRevision> list : out.values()) {
            int idx = oldestEvictable(list, list.size() - 1);
            if (idx >= 1) {
                oldest.add(new Candidate(list.get(idx).timestamp(), order, list, idx));
            }
            order++;
        }
        while (total > maxTotalBytes && !oldest.isEmpty()) {
            Candidate victim = oldest.poll();
            total -= victim.list().remove(victim.index()).sizeBytes();
            // Rows are newest-first and candidates are taken from the old end, so the rows before this one
            // kept their positions: the file's next candidate is the nearest unprotected row above it.
            int next = oldestEvictable(victim.list(), victim.index() - 1);
            if (next >= 1) {
                oldest.add(new Candidate(victim.list().get(next).timestamp(), victim.fileOrder(), victim.list(), next));
            }
        }
        return out;
    }

    /**
     * The index of the oldest row at or before {@code from} that the budget may evict, or -1: never row 0
     * (each file keeps its newest revision), and never a labelled or pre-delete revision — that is user
     * intent, not cache, so the budget walks past it rather than deleting it (and its blob) without a word.
     */
    private static int oldestEvictable(List<HistoryRevision> list, int from) {
        for (int i = Math.min(from, list.size() - 1); i >= 1; i--) { // newest-first ⇒ walk from the oldest
            if (!isProtected(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The summed {@code sizeBytes} of every revision in {@code bucket}; allocates nothing. */
    public static long totalBytes(Map<String, List<HistoryRevision>> bucket) {
        long total = 0;
        if (bucket != null) {
            for (List<HistoryRevision> list : bucket.values()) {
                for (int i = 0, n = list.size(); i < n; i++) {
                    total += list.get(i).sizeBytes();
                }
            }
        }
        return total;
    }

    /**
     * Applies the whole policy to every project at once — the startup sweep. Recording a revision only ever
     * pruned <em>that</em> file's list, so the age limit and byte budget were never applied to a file that
     * was not saved again: its history stayed until the user happened to edit it. Here every file's list is
     * {@link #prune pruned}, a file whose newest revision has outlived even the protected lease loses its
     * entry altogether, and each project is then held to its byte budget. Returns a new map; the input is
     * not mutated.
     */
    public static Map<String, Map<String, List<HistoryRevision>>> sweep(
            Map<String, Map<String, List<HistoryRevision>>> byProject, RetentionPolicy policy, long now) {
        Map<String, Map<String, List<HistoryRevision>>> out = new LinkedHashMap<>();
        if (byProject == null || policy == null) {
            return out;
        }
        long protectedAge = protectedMaxAgeMillis(policy.maxAgeMillis());
        for (Map.Entry<String, Map<String, List<HistoryRevision>>> project : byProject.entrySet()) {
            Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
            if (project.getValue() != null) {
                for (Map.Entry<String, List<HistoryRevision>> file :
                        project.getValue().entrySet()) {
                    List<HistoryRevision> kept =
                            prune(file.getValue(), policy.maxPerFile(), policy.maxAgeMillis(), now);
                    // prune() always keeps index 0 (when recording, that is the revision just made). In a
                    // sweep the newest can itself be long expired, and then so is everything behind it.
                    if (!kept.isEmpty() && protectedAge > 0 && now - kept.get(0).timestamp() > protectedAge) {
                        kept = List.of();
                    }
                    if (!kept.isEmpty()) {
                        bucket.put(file.getKey(), kept);
                    }
                }
            }
            out.put(project.getKey(), enforceProjectBudget(bucket, policy.maxTotalBytesPerProject()));
        }
        return out;
    }

    /**
     * The revisions present in {@code before} but absent from {@code after} (by value), per project and
     * file — what a {@link #sweep} decided to drop, in a form that can be subtracted from an index that kept
     * changing while the sweep was computed.
     */
    public static Map<String, Map<String, List<HistoryRevision>>> evicted(
            Map<String, Map<String, List<HistoryRevision>>> before,
            Map<String, Map<String, List<HistoryRevision>>> after) {
        Map<String, Map<String, List<HistoryRevision>>> out = new LinkedHashMap<>();
        if (before == null) {
            return out;
        }
        for (Map.Entry<String, Map<String, List<HistoryRevision>>> project : before.entrySet()) {
            Map<String, List<HistoryRevision>> keptFiles =
                    after == null ? Map.of() : after.getOrDefault(project.getKey(), Map.of());
            for (Map.Entry<String, List<HistoryRevision>> file :
                    project.getValue().entrySet()) {
                List<HistoryRevision> gone = new ArrayList<>(file.getValue());
                for (HistoryRevision kept : keptFiles.getOrDefault(file.getKey(), List.of())) {
                    gone.remove(kept); // one occurrence: identical rows are counted, not collapsed
                }
                if (!gone.isEmpty()) {
                    out.computeIfAbsent(project.getKey(), k -> new LinkedHashMap<>())
                            .put(file.getKey(), gone);
                }
            }
        }
        return out;
    }

    /** How many revisions, in how many files, a change of limits would delete. */
    public record Impact(int revisions, int files) {
        public static final Impact NONE = new Impact(0, 0);
    }

    /** A limit is "off" at zero or below; otherwise the smaller value is the stricter one. */
    private static boolean stricter(long from, long to) {
        return to > 0 && (from <= 0 || to < from);
    }

    private static long looser(long a, long b) {
        return a <= 0 || b <= 0 ? 0 : Math.max(a, b);
    }

    /**
     * True when {@code to} is stricter than {@code from} in any limit — the only kind of change that can
     * delete revisions. A {@code null} {@code from} is "no policy yet", which nothing tightens.
     */
    public static boolean tightens(RetentionPolicy from, RetentionPolicy to) {
        if (from == null || to == null) {
            return false;
        }
        return stricter(from.maxPerFile(), to.maxPerFile())
                || stricter(from.maxAgeMillis(), to.maxAgeMillis())
                || stricter(from.maxTotalBytesPerProject(), to.maxTotalBytesPerProject());
    }

    /** The policy that keeps whatever either of {@code a} and {@code b} keeps: each limit at its looser value. */
    public static RetentionPolicy loosest(RetentionPolicy a, RetentionPolicy b) {
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        return new RetentionPolicy(
                (int) looser(a.maxPerFile(), b.maxPerFile()),
                looser(a.maxAgeMillis(), b.maxAgeMillis()),
                looser(a.maxTotalBytesPerProject(), b.maxTotalBytesPerProject()));
    }

    /**
     * What replacing {@code current} with {@code candidate} would delete from {@code byProject} right now:
     * the revisions {@code candidate} evicts that {@code current} keeps (what {@code current} would drop
     * anyway is not the change's doing). This is the number a confirmation shows before a tightened limit
     * is applied; the input is not mutated.
     */
    public static Impact tighteningImpact(
            Map<String, Map<String, List<HistoryRevision>>> byProject,
            RetentionPolicy current,
            RetentionPolicy candidate,
            long now) {
        if (byProject == null || candidate == null) {
            return Impact.NONE;
        }
        // No current policy: every limit off, so the baseline is the index as it stands.
        Map<String, Map<String, List<HistoryRevision>>> kept =
                sweep(byProject, current == null ? new RetentionPolicy(0, 0, 0) : current, now);
        int revisions = 0;
        int files = 0;
        for (Map<String, List<HistoryRevision>> project :
                evicted(kept, sweep(kept, candidate, now)).values()) {
            for (List<HistoryRevision> gone : project.values()) {
                revisions += gone.size();
                files++;
            }
        }
        return new Impact(revisions, files);
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
