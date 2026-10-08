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
 *   <li>{@link #enforceProjectBudget} — across a whole project bucket: a file above its fair share sheds
 *       its own oldest revisions, then the globally-oldest go, until the uncompressed size of the distinct
 *       bodies is within budget.
 *   <li>{@link #fold} — how a just-recorded revision joins a file's list (repeat skipped, auto-saves
 *       coalesced).
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
     * Enforces the per-project size limit across every file's revisions. The size of a project is what its
     * revision bodies take <em>uncompressed</em>, each distinct body (by {@code sha256}) counted once however
     * many rows share it — bodies are stored by content hash, so a Save As copy or a repeated label of the
     * same text costs nothing more on disk and must not count against the limit.
     *
     * <p>When the project is over its limit, revisions are evicted in two steps until it fits:
     *
     * <ol>
     *   <li><b>Fair share.</b> A file whose own revisions take more than an equal share of the limit
     *       ({@code maxTotalBytes / files}) gives up its own oldest revisions first, the largest such file
     *       first. One large file saved a few times therefore cannot push every other file's history out.
     *   <li><b>Oldest first.</b> If the project is still over, the globally-oldest revisions go, across all
     *       files.
     * </ol>
     *
     * Never evicted by either step: a file's newest revision (so no file loses its whole history) and a
     * {@linkplain #isProtected protected} revision. The limit is therefore a soft one — a project of many
     * files with one revision each, or of many labelled revisions, stays above it. Returns a new bucket map;
     * the input is not mutated. A non-positive limit means "unbounded" (returned unchanged-but-copied).
     */
    public static Map<String, List<HistoryRevision>> enforceProjectBudget(
            Map<String, List<HistoryRevision>> bucket, long maxTotalBytes) {
        Map<String, List<HistoryRevision>> out = new LinkedHashMap<>();
        if (bucket == null) {
            return out;
        }
        for (Map.Entry<String, List<HistoryRevision>> e : bucket.entrySet()) {
            if (e.getValue() != null) {
                out.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        if (maxTotalBytes <= 0 || !exceedsBudget(out, maxTotalBytes)) {
            return out;
        }
        Usage project = new Usage();
        List<FileUse> files = new ArrayList<>(out.size());
        for (List<HistoryRevision> list : out.values()) {
            FileUse file = new FileUse(list, files.size());
            for (HistoryRevision r : list) {
                project.add(r);
                file.own.add(r);
            }
            files.add(file);
        }
        capLargeFiles(files, project, maxTotalBytes);
        evictOldest(files, project, maxTotalBytes);
        return out;
    }

    /** Distinct bodies and their summed size: a body shared by several rows counts once. */
    private static final class Usage {
        private final Map<String, Integer> rows = new java.util.HashMap<>();
        private long total;

        void add(HistoryRevision r) {
            if (r.sha256().isEmpty() || rows.merge(r.sha256(), 1, Integer::sum) == 1) {
                total += r.sizeBytes();
            }
        }

        void remove(HistoryRevision r) {
            if (r.sha256().isEmpty()) {
                total -= r.sizeBytes();
            } else if (rows.merge(r.sha256(), -1, Integer::sum) == 0) {
                rows.remove(r.sha256());
                total -= r.sizeBytes();
            }
        }
    }

    /** One file's list during an eviction: what it takes on its own, and where its next candidate is. */
    private static final class FileUse {
        private final List<HistoryRevision> list;
        private final int order;
        private final Usage own = new Usage();
        /** The index to look for the next evictable row at or before; rows after it were looked at. */
        private int cursor;

        FileUse(List<HistoryRevision> list, int order) {
            this.list = list;
            this.order = order;
            this.cursor = list.size() - 1;
        }

        /** Removes and returns this file's oldest evictable row, or null when it has none left. */
        HistoryRevision evictOldest(Usage project) {
            int index = oldestEvictable(list, cursor);
            if (index < 1) {
                cursor = 0;
                return null;
            }
            HistoryRevision gone = list.remove(index);
            cursor = index - 1; // newest-first: the rows before this one kept their positions
            own.remove(gone);
            project.remove(gone);
            return gone;
        }

        /** The timestamp of the row {@link #evictOldest} would remove next, or null. */
        Long nextTimestamp() {
            int index = oldestEvictable(list, cursor);
            return index < 1 ? null : list.get(index).timestamp();
        }
    }

    /** Step one of {@link #enforceProjectBudget}: files above an equal share shed their own oldest rows. */
    private static void capLargeFiles(List<FileUse> files, Usage project, long maxTotalBytes) {
        if (files.size() < 2) {
            return; // a single file's share is the whole limit: step two is the same thing
        }
        long share = maxTotalBytes / files.size();
        java.util.PriorityQueue<FileUse> largest = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong((FileUse f) -> -f.own.total).thenComparingInt(f -> f.order));
        for (FileUse file : files) {
            if (file.own.total > share) {
                largest.add(file);
            }
        }
        while (project.total > maxTotalBytes && !largest.isEmpty()) {
            FileUse file = largest.poll();
            if (file.own.total <= share) {
                continue;
            }
            if (file.evictOldest(project) != null) {
                largest.add(file); // re-ranked by what it takes now
            }
        }
    }

    /** Step two of {@link #enforceProjectBudget}: the globally-oldest evictable rows, one queue step each. */
    private static void evictOldest(List<FileUse> files, Usage project, long maxTotalBytes) {
        // Each file offers one candidate at a time — its oldest unprotected row — through a queue ordered by
        // age, so an eviction costs a queue step rather than another pass over every file of the project.
        record Candidate(long timestamp, FileUse file) {}
        java.util.PriorityQueue<Candidate> oldest = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(Candidate::timestamp).thenComparingInt(c -> c.file().order));
        for (FileUse file : files) {
            Long timestamp = file.nextTimestamp();
            if (timestamp != null) {
                oldest.add(new Candidate(timestamp, file));
            }
        }
        while (project.total > maxTotalBytes && !oldest.isEmpty()) {
            FileUse file = oldest.poll().file();
            file.evictOldest(project);
            Long next = file.nextTimestamp();
            if (next != null) {
                oldest.add(new Candidate(next, file));
            }
        }
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

    /**
     * What {@code bucket} takes: the summed uncompressed {@code sizeBytes} of its <em>distinct</em> revision
     * bodies. Rows that share a body (the same {@code sha256}) are one body on disk and count once. This is
     * the number the per-project size limit is compared with.
     */
    public static long totalBytes(Map<String, List<HistoryRevision>> bucket) {
        Usage usage = new Usage();
        if (bucket != null) {
            for (List<HistoryRevision> list : bucket.values()) {
                if (list != null) {
                    for (int i = 0, n = list.size(); i < n; i++) {
                        usage.add(list.get(i));
                    }
                }
            }
        }
        return usage.total;
    }

    /**
     * Whether {@code bucket} takes more than {@code maxTotalBytes} (see {@link #totalBytes}); false for a
     * non-positive limit. The check made after every recorded save, so it first sums the rows as they are —
     * an upper bound that allocates nothing — and only counts distinct bodies when that sum is over.
     */
    public static boolean exceedsBudget(Map<String, List<HistoryRevision>> bucket, long maxTotalBytes) {
        if (maxTotalBytes <= 0 || bucket == null) {
            return false;
        }
        long rows = 0;
        for (List<HistoryRevision> list : bucket.values()) {
            if (list != null) {
                for (int i = 0, n = list.size(); i < n; i++) {
                    rows += list.get(i).sizeBytes();
                }
            }
        }
        return rows > maxTotalBytes && totalBytes(bucket) > maxTotalBytes;
    }

    /** How close together automatic saves are folded into one revision (see {@link #replacesNewestAutosave}). */
    public static final long AUTOSAVE_COALESCE_MILLIS = 5 * 60_000L;

    /**
     * True when {@code rev} would only repeat the newest revision of {@code current}: an automatic revision
     * (not a label, not a pre-delete copy) with the body the newest row already has. The recording worker
     * makes the same check against the list as it was when the record was <em>submitted</em>; two records of
     * one text submitted back to back both pass it, so the caller repeats it here, against the list as it is
     * when the revision is folded in.
     */
    public static boolean repeatsNewest(List<HistoryRevision> current, HistoryRevision rev) {
        return rev != null && !isProtected(rev) && isDuplicate(current, rev.sha256());
    }

    /**
     * True when the auto-saved {@code rev} should <em>replace</em> the newest row of {@code current} instead
     * of being added before it. With auto-save on, every pause in typing is a revision, and fifty of them —
     * the default per-file cap — are a few minutes of work that push every earlier revision out.
     *
     * <p>The newest row is replaced when it is itself an unlabelled auto-save and {@code rev} was captured
     * less than {@code windowMillis} after the row <em>before</em> it. That older row is the anchor: measuring
     * from the row being replaced would restart the window with every auto-save and keep a single row for a
     * whole afternoon. So a run of auto-saves leaves one revision per window, plus the latest. A manual save,
     * a label or any other kind of revision is never replaced and never replaces.
     */
    public static boolean replacesNewestAutosave(
            List<HistoryRevision> current, HistoryRevision rev, long windowMillis) {
        if (windowMillis <= 0 || rev == null || current == null || current.size() < 2 || !isPlainAutosave(rev)) {
            return false;
        }
        HistoryRevision newest = current.get(0);
        long sinceAnchor = rev.timestamp() - current.get(1).timestamp();
        return isPlainAutosave(newest)
                && rev.timestamp() >= newest.timestamp()
                && sinceAnchor >= 0
                && sinceAnchor < windowMillis;
    }

    private static boolean isPlainAutosave(HistoryRevision r) {
        return r != null && HistoryRevision.REASON_AUTOSAVE.equals(r.reason()) && !isProtected(r);
    }

    /**
     * {@code current} (newest-first) with the just-recorded {@code rev} folded in: {@code current} itself —
     * the same instance, so the caller can tell nothing changed — when {@code rev} {@linkplain #repeatsNewest
     * repeats the newest row}; a list in which {@code rev} took the newest row's place when it is an auto-save
     * that {@linkplain #replacesNewestAutosave coalesces} with it; otherwise {@code rev} followed by
     * {@code current}. The input is not mutated.
     */
    public static List<HistoryRevision> fold(
            List<HistoryRevision> current, HistoryRevision rev, long autosaveWindowMillis) {
        List<HistoryRevision> present = current == null ? List.of() : current;
        if (rev == null || repeatsNewest(present, rev)) {
            return present;
        }
        boolean replace = replacesNewestAutosave(present, rev, autosaveWindowMillis);
        List<HistoryRevision> out = new ArrayList<>(present.size() + 1);
        out.add(rev);
        out.addAll(replace ? present.subList(1, present.size()) : present);
        return out;
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
                    if (r != null && !r.sha256().isEmpty()) {
                        live.add(r.sha256());
                    }
                }
            }
        }
        return live;
    }
}
