package com.editora.history;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.config.HistoryRevision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure retention-policy logic: dedup, per-file pruning (count + age), the per-project byte budget, and
 *  live-hash collection for blob GC. */
class HistoryRetentionTest {

    private static HistoryRevision rev(long ts, long size, String sha) {
        return new HistoryRevision("/tmp/a.txt", ts, size, sha, HistoryRevision.REASON_SAVE);
    }

    @Test
    void isDuplicateChecksNewestOnly() {
        List<HistoryRevision> existing = List.of(rev(100, 1, "aaa"), rev(50, 1, "bbb"));
        assertTrue(HistoryRetention.isDuplicate(existing, "aaa"));
        assertFalse(HistoryRetention.isDuplicate(existing, "bbb")); // not the newest
        assertFalse(HistoryRetention.isDuplicate(existing, "ccc"));
        assertFalse(HistoryRetention.isDuplicate(List.of(), "aaa"));
        assertFalse(HistoryRetention.isDuplicate(null, "aaa"));
    }

    @Test
    void pruneCapsToMaxPerFileNewestFirst() {
        List<HistoryRevision> revs = List.of(rev(5, 1, "e"), rev(4, 1, "d"), rev(3, 1, "c"), rev(2, 1, "b"));
        List<HistoryRevision> out = HistoryRetention.prune(revs, 2, 0, 100);
        assertEquals(2, out.size());
        assertEquals("e", out.get(0).sha256());
        assertEquals("d", out.get(1).sha256());
    }

    @Test
    void pruneDropsOldButKeepsNewest() {
        long now = 1_000_000L;
        long day = 86_400_000L;
        // newest at now, others 2 and 10 days old; maxAge = 5 days.
        List<HistoryRevision> revs =
                List.of(rev(now, 1, "new"), rev(now - 2 * day, 1, "mid"), rev(now - 10 * day, 1, "old"));
        List<HistoryRevision> out = HistoryRetention.prune(revs, 0, 5 * day, now);
        assertEquals(2, out.size());
        assertEquals("new", out.get(0).sha256());
        assertEquals("mid", out.get(1).sha256());
    }

    @Test
    void pruneKeepsNewestEvenWhenItWouldBeTooOld() {
        long now = 1_000_000L;
        // Single revision older than maxAge: the newest is always kept (index 0 exemption).
        List<HistoryRevision> out = HistoryRetention.prune(List.of(rev(0, 1, "x")), 50, 1, now);
        assertEquals(1, out.size());
    }

    @Test
    void pruneEmptyAndZeroLimitsAreNoOps() {
        assertTrue(HistoryRetention.prune(List.of(), 10, 10, 0).isEmpty());
        List<HistoryRevision> revs = List.of(rev(3, 1, "c"), rev(2, 1, "b"), rev(1, 1, "a"));
        assertEquals(3, HistoryRetention.prune(revs, 0, 0, 100).size()); // 0 = unbounded for both
    }

    @Test
    void enforceProjectBudgetEvictsGloballyOldestKeepingEachFilesNewest() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        // file a: 100B newest @t=10, 100B @t=5; file b: 100B newest @t=8, 100B @t=2 (oldest overall).
        bucket.put("a", List.of(rev(10, 100, "a2"), rev(5, 100, "a1")));
        bucket.put("b", List.of(rev(8, 100, "b2"), rev(2, 100, "b1")));
        // Total 400B; budget 250B ⇒ must evict oldest (b1 @t=2, then a1 @t=5) until <= 250.
        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(bucket, 250);
        long total = out.values().stream()
                .flatMap(List::stream)
                .mapToLong(HistoryRevision::sizeBytes)
                .sum();
        assertTrue(total <= 250, "total=" + total);
        // Each file keeps at least its newest.
        assertEquals("a2", out.get("a").get(0).sha256());
        assertEquals("b2", out.get("b").get(0).sha256());
        // b1 (the globally-oldest) is gone.
        assertFalse(out.get("b").stream().anyMatch(r -> r.sha256().equals("b1")));
    }

    @Test
    void enforceProjectBudgetUnboundedReturnsCopy() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put("a", List.of(rev(1, 999, "a1")));
        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(bucket, 0);
        assertEquals(1, out.get("a").size());
    }

    @Test
    void liveHashesCollectsAcrossProjectsAndFiles() {
        Map<String, Map<String, List<HistoryRevision>>> byProject = new LinkedHashMap<>();
        Map<String, List<HistoryRevision>> p0 = new LinkedHashMap<>();
        p0.put("/x", List.of(rev(1, 1, "h1"), rev(2, 1, "h2")));
        Map<String, List<HistoryRevision>> p1 = new LinkedHashMap<>();
        p1.put("/y", List.of(rev(3, 1, "h2"), rev(4, 1, "h3"))); // h2 shared
        byProject.put("", p0);
        byProject.put("proj", p1);
        Set<String> live = HistoryRetention.liveHashes(byProject);
        assertEquals(Set.of("h1", "h2", "h3"), live);
    }

    private static HistoryRevision labelled(long ts, long size, String sha, String label) {
        return new HistoryRevision("/tmp/a.txt", ts, size, sha, HistoryRevision.REASON_LABEL, label);
    }

    private static HistoryRevision deleted(long ts, long size, String sha) {
        return new HistoryRevision("/tmp/a.txt", ts, size, sha, HistoryRevision.REASON_DELETE, "");
    }

    /**
     * A "Put Label" revision is a named restore point — the user's deliberate mark, and the whole reason the
     * label path forces a revision. Retention treated it as cache: past the age limit it was dropped (and its
     * blob GCd) with no warning.
     */
    @Test
    void ageBasedPruningKeepsLabelledAndPreDeleteRevisions() {
        long now = 100_000_000L;
        long day = 86_400_000L;
        List<HistoryRevision> revs = List.of(
                rev(now, 10, "newest"),
                labelled(now - 31 * day, 10, "before-refactor-sha", "before-refactor"),
                deleted(now - 40 * day, 10, "last-copy-sha"),
                rev(now - 31 * day, 10, "old-auto"));
        List<HistoryRevision> out = HistoryRetention.prune(revs, 0, 30 * day, now);
        assertTrue(out.stream().anyMatch(r -> r.sha256().equals("before-refactor-sha")), "the user's label survives");
        assertTrue(out.stream().anyMatch(r -> r.sha256().equals("last-copy-sha")), "the pre-delete copy survives");
        assertFalse(out.stream().anyMatch(r -> r.sha256().equals("old-auto")), "an old automatic revision is cache");
    }

    /** With autosave on, the per-file cap is reached in hours — it must not evict the user's label. */
    @Test
    void theCountCapOnlyCountsAutomaticRevisions() {
        List<HistoryRevision> revs = new java.util.ArrayList<>();
        revs.add(rev(1000, 10, "newest"));
        revs.add(labelled(999, 10, "kept-label", "before-refactor"));
        for (int i = 0; i < 10; i++) {
            revs.add(rev(900 - i, 10, "auto" + i));
        }
        List<HistoryRevision> out = HistoryRetention.prune(revs, 3, 0, 2000);
        assertTrue(out.stream().anyMatch(r -> r.sha256().equals("kept-label")), "the label is not evicted by the cap");
        assertEquals(
                3,
                out.stream().filter(r -> r.label().isBlank()).count(),
                "exactly maxPerFile automatic revisions are kept");
    }

    /** The project byte budget walks past a label rather than reclaiming its bytes. */
    @Test
    void theProjectBudgetNeverEvictsALabelledRevision() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put(
                "/tmp/a.txt",
                List.of(rev(500, 100, "newest-a"), labelled(100, 100, "label-a", "keep-me"), rev(200, 100, "auto-a")));
        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(bucket, 200);
        List<HistoryRevision> a = out.get("/tmp/a.txt");
        assertTrue(a.stream().anyMatch(r -> r.sha256().equals("label-a")), "the label survives the budget");
        assertFalse(a.stream().anyMatch(r -> r.sha256().equals("auto-a")), "the automatic revision was evicted first");
    }

    @Test
    void isProtectedIdentifiesUserIntent() {
        assertTrue(HistoryRetention.isProtected(labelled(1, 1, "s", "my-label")));
        assertTrue(HistoryRetention.isProtected(deleted(1, 1, "s")));
        assertFalse(HistoryRetention.isProtected(rev(1, 1, "s")));
        assertFalse(HistoryRetention.isProtected(null));
    }

    private static final long DAY = 86_400_000L;

    private static HistoryRevision revAt(String path, long ts, long size, String sha) {
        return new HistoryRevision(path, ts, size, sha, HistoryRevision.REASON_SAVE);
    }

    /** Protection is a longer lease, not a permanent one: six times the age limit, never under 180 days. */
    @Test
    void protectedRevisionsExpireAfterALongerFiniteLease() {
        assertEquals(180 * DAY, HistoryRetention.protectedMaxAgeMillis(30 * DAY));
        assertEquals(180 * DAY, HistoryRetention.protectedMaxAgeMillis(DAY));
        assertEquals(600 * DAY, HistoryRetention.protectedMaxAgeMillis(100 * DAY));
        assertEquals(0, HistoryRetention.protectedMaxAgeMillis(0), "no age limit means no expiry at all");
        assertEquals(Long.MAX_VALUE, HistoryRetention.protectedMaxAgeMillis(Long.MAX_VALUE / 2));

        long now = 1_000 * DAY;
        List<HistoryRevision> revs = List.of(
                rev(now, 10, "newest"),
                labelled(now - 179 * DAY, 10, "recent-label", "keep"),
                labelled(now - 181 * DAY, 10, "ancient-label", "expired"),
                deleted(now - 181 * DAY, 10, "ancient-delete"));
        List<HistoryRevision> out = HistoryRetention.prune(revs, 0, 30 * DAY, now);
        assertEquals(
                List.of("newest", "recent-label"),
                out.stream().map(HistoryRevision::sha256).toList());
        // With the age limit off nothing expires, protected or not.
        assertEquals(4, HistoryRetention.prune(revs, 0, 0, now).size());
    }

    /**
     * The limits were only ever applied to the file being saved. A file saved once and never touched again
     * kept its revision — and its content on disk — forever. The sweep applies them to every file.
     */
    @Test
    void theSweepAppliesTheLimitsToFilesThatAreNeverSavedAgain() {
        long now = 1_000 * DAY;
        Map<String, List<HistoryRevision>> project = new LinkedHashMap<>();
        project.put("/p/.env", List.of(revAt("/p/.env", now - 400 * DAY, 10, "secret")));
        project.put(
                "/p/active.txt",
                List.of(
                        revAt("/p/active.txt", now - DAY, 10, "fresh"),
                        revAt("/p/active.txt", now - 31 * DAY, 10, "stale"),
                        revAt("/p/active.txt", now - 60 * DAY, 10, "staler")));
        project.put("/p/idle.txt", List.of(revAt("/p/idle.txt", now - 90 * DAY, 10, "only-copy")));
        Map<String, Map<String, List<HistoryRevision>>> index = new LinkedHashMap<>();
        index.put("proj", project);
        var policy = new HistoryRetention.RetentionPolicy(50, 30 * DAY, 0);

        Map<String, Map<String, List<HistoryRevision>>> swept = HistoryRetention.sweep(index, policy, now);

        Map<String, List<HistoryRevision>> out = swept.get("proj");
        assertFalse(
                out.containsKey("/p/.env"),
                "a newest revision past the protected lease is removed with its file entry");
        assertEquals(
                List.of("fresh"),
                out.get("/p/active.txt").stream().map(HistoryRevision::sha256).toList());
        assertEquals(1, out.get("/p/idle.txt").size(), "a file's newest revision is kept inside the lease");
        assertEquals(3, project.get("/p/active.txt").size(), "the input is not mutated");
        assertEquals(Set.of("fresh", "only-copy"), HistoryRetention.liveHashes(swept));

        Map<String, Map<String, List<HistoryRevision>>> evicted = HistoryRetention.evicted(index, swept);
        assertEquals(Set.of("/p/.env", "/p/active.txt"), evicted.get("proj").keySet());
        assertEquals(
                List.of("stale", "staler"),
                evicted.get("proj").get("/p/active.txt").stream()
                        .map(HistoryRevision::sha256)
                        .toList());
    }

    @Test
    void theSweepHoldsEveryProjectToItsByteBudget() {
        long now = 1_000 * DAY;
        Map<String, List<HistoryRevision>> a = new LinkedHashMap<>();
        a.put(
                "/a/x",
                List.of(
                        revAt("/a/x", now - 1, 100, "x3"),
                        revAt("/a/x", now - 2, 100, "x2"),
                        revAt("/a/x", now - 3, 100, "x1")));
        Map<String, List<HistoryRevision>> b = new LinkedHashMap<>();
        b.put("/b/y", List.of(revAt("/b/y", now - 1, 100, "y2"), revAt("/b/y", now - 2, 100, "y1")));
        Map<String, Map<String, List<HistoryRevision>>> index = new LinkedHashMap<>();
        index.put("a", a);
        index.put("b", b);

        var swept = HistoryRetention.sweep(index, new HistoryRetention.RetentionPolicy(0, 0, 200), now);

        assertEquals(
                List.of("x3", "x2"),
                swept.get("a").get("/a/x").stream().map(HistoryRevision::sha256).toList());
        assertEquals(2, swept.get("b").get("/b/y").size(), "each project has its own budget");
    }

    @Test
    void evictedCountsIdenticalRowsRatherThanCollapsingThem() {
        HistoryRevision same = rev(5, 1, "dup");
        Map<String, Map<String, List<HistoryRevision>>> before =
                Map.of("p", Map.of("/f", List.of(same, same, rev(1, 1, "old"))));
        Map<String, Map<String, List<HistoryRevision>>> after = Map.of("p", Map.of("/f", List.of(same)));

        assertEquals(
                List.of(same, rev(1, 1, "old")),
                HistoryRetention.evicted(before, after).get("p").get("/f"));
        assertTrue(HistoryRetention.evicted(before, before).isEmpty());
    }

    // --- the budget's eviction order, pinned against the original one-scan-per-eviction algorithm ---------

    /** The algorithm as it was: rescan every file for the globally oldest evictable row, once per eviction. */
    private static Map<String, List<HistoryRevision>> referenceBudget(
            Map<String, List<HistoryRevision>> bucket, long maxTotalBytes) {
        Map<String, List<HistoryRevision>> out = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, List<HistoryRevision>> e : bucket.entrySet()) {
            List<HistoryRevision> copy = new java.util.ArrayList<>(e.getValue());
            out.put(e.getKey(), copy);
            for (HistoryRevision r : copy) {
                total += r.sizeBytes();
            }
        }
        while (maxTotalBytes > 0 && total > maxTotalBytes) {
            String victimFile = null;
            int victimIndex = -1;
            long victimTs = Long.MAX_VALUE;
            for (Map.Entry<String, List<HistoryRevision>> e : out.entrySet()) {
                List<HistoryRevision> list = e.getValue();
                if (list.size() <= 1) {
                    continue;
                }
                int idx = -1;
                for (int i = list.size() - 1; i >= 1; i--) {
                    if (!HistoryRetention.isProtected(list.get(i))) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0 && list.get(idx).timestamp() < victimTs) {
                    victimTs = list.get(idx).timestamp();
                    victimFile = e.getKey();
                    victimIndex = idx;
                }
            }
            if (victimFile == null) {
                break;
            }
            total -= out.get(victimFile).remove(victimIndex).sizeBytes();
        }
        return out;
    }

    @Test
    void theQueuedEvictionEvictsExactlyWhatTheRescanningOneDid() {
        java.util.Random random = new java.util.Random(20261005);
        for (int round = 0; round < 300; round++) {
            Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
            long total = 0;
            int files = 1 + random.nextInt(8);
            for (int f = 0; f < files; f++) {
                List<HistoryRevision> list = new java.util.ArrayList<>();
                int revisions = 1 + random.nextInt(7);
                for (int r = 0; r < revisions; r++) {
                    long ts = random.nextInt(12); // few distinct values: ties between files are common
                    long size = 1 + random.nextInt(50);
                    String sha = "f" + f + "r" + r;
                    int kind = random.nextInt(6);
                    list.add(
                            kind == 0
                                    ? labelled(ts, size, sha, "keep")
                                    : kind == 1 ? deleted(ts, size, sha) : rev(ts, size, sha));
                    total += size;
                }
                bucket.put("/p/file" + f, list);
            }
            long budget = random.nextInt(4) == 0 ? 1 : Math.max(1, total * random.nextInt(100) / 100);

            assertEquals(
                    referenceBudget(bucket, budget),
                    HistoryRetention.enforceProjectBudget(bucket, budget),
                    "round " + round + " budget " + budget + " of " + total);
        }
    }

    @Test
    void totalBytesIsTheSumTheBudgetIsCheckedAgainst() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put("/a", List.of(rev(1, 100, "a1"), rev(2, 50, "a2")));
        bucket.put("/b", List.of(rev(3, 7, "b1")));
        bucket.put("/c", List.of());
        assertEquals(157, HistoryRetention.totalBytes(bucket));
        assertEquals(0, HistoryRetention.totalBytes(Map.of()));
        assertEquals(0, HistoryRetention.totalBytes(null));
    }
}
