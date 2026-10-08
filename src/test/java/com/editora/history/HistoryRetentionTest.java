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

    // --- the budget's eviction order, pinned against a one-scan-per-eviction reference -------------------

    private static long ownBytes(List<HistoryRevision> list) {
        return list.stream().mapToLong(HistoryRevision::sizeBytes).sum(); // every body is distinct here
    }

    private static int oldestEvictableOf(List<HistoryRevision> list) {
        for (int i = list.size() - 1; i >= 1; i--) {
            if (!HistoryRetention.isProtected(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The policy written the slow, obvious way: rescan every file once per eviction. First the largest file
     * above an equal share sheds its oldest evictable row, then the globally oldest evictable row goes.
     */
    private static Map<String, List<HistoryRevision>> referenceBudget(
            Map<String, List<HistoryRevision>> bucket, long maxTotalBytes) {
        Map<String, List<HistoryRevision>> out = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, List<HistoryRevision>> e : bucket.entrySet()) {
            List<HistoryRevision> copy = new java.util.ArrayList<>(e.getValue());
            out.put(e.getKey(), copy);
            total += ownBytes(copy);
        }
        long share = maxTotalBytes / Math.max(1, out.size());
        Set<String> spent = new java.util.HashSet<>();
        while (out.size() > 1 && total > maxTotalBytes) {
            String largest = null;
            for (Map.Entry<String, List<HistoryRevision>> e : out.entrySet()) {
                long own = ownBytes(e.getValue());
                if (!spent.contains(e.getKey())
                        && own > share
                        && (largest == null || own > ownBytes(out.get(largest)))) {
                    largest = e.getKey();
                }
            }
            if (largest == null) {
                break;
            }
            int idx = oldestEvictableOf(out.get(largest));
            if (idx < 0) {
                spent.add(largest);
            } else {
                total -= out.get(largest).remove(idx).sizeBytes();
            }
        }
        while (total > maxTotalBytes) {
            String victimFile = null;
            int victimIndex = -1;
            long victimTs = Long.MAX_VALUE;
            for (Map.Entry<String, List<HistoryRevision>> e : out.entrySet()) {
                int idx = oldestEvictableOf(e.getValue());
                if (idx >= 0 && e.getValue().get(idx).timestamp() < victimTs) {
                    victimTs = e.getValue().get(idx).timestamp();
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

    // --- B1: one large file must not evict the other files' history ---------------------------------------

    private static final long MB = 1024L * 1024L;

    @Test
    void aLargeFileShedsItsOwnRevisionsBeforeAnyOtherFileLosesOne() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        List<HistoryRevision> big = new java.util.ArrayList<>();
        for (int i = 10; i >= 1; i--) {
            big.add(revAt("/p/big.bin", 1000 + i, 6 * MB, "big" + i)); // saved last: every row is newer
        }
        List<HistoryRevision> small = new java.util.ArrayList<>();
        for (int i = 6; i >= 1; i--) {
            small.add(revAt("/p/small.txt", i, 20, "small" + i)); // the oldest rows of the project
        }
        bucket.put("/p/small.txt", small);
        bucket.put("/p/big.bin", big);

        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(bucket, 50 * MB);

        assertEquals(small, out.get("/p/small.txt"), "the small file keeps all six revisions");
        assertEquals(8, out.get("/p/big.bin").size(), "the large file gave up its two oldest");
        assertEquals("big10", out.get("/p/big.bin").get(0).sha256());
        assertEquals("big3", out.get("/p/big.bin").get(7).sha256());
        assertTrue(HistoryRetention.storedBytes(out) <= 50 * MB);
    }

    @Test
    void aFileWithinItsShareIsOnlyTouchedByTheOldestFirstStep() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        // Two files of 60 bytes each; the limit of 100 gives each a share of 50: both are above it, the
        // first sheds first and that is enough.
        bucket.put("/a", List.of(revAt("/a", 9, 30, "a2"), revAt("/a", 1, 30, "a1")));
        bucket.put("/b", List.of(revAt("/b", 8, 30, "b2"), revAt("/b", 2, 30, "b1")));
        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(bucket, 100);
        assertEquals(1, out.get("/a").size());
        assertEquals(2, out.get("/b").size());

        // A large file that can shed nothing (one row) leaves the work to the oldest-first step.
        bucket = new LinkedHashMap<>();
        bucket.put("/huge", List.of(revAt("/huge", 9, 90, "h")));
        bucket.put("/b", List.of(revAt("/b", 8, 10, "b2"), revAt("/b", 2, 10, "b1")));
        out = HistoryRetention.enforceProjectBudget(bucket, 100);
        assertEquals(
                List.of("h"),
                out.get("/huge").stream().map(HistoryRevision::sha256).toList());
        assertEquals(
                List.of("b2"),
                out.get("/b").stream().map(HistoryRevision::sha256).toList());
    }

    // --- A6: a body shared by several rows is one body ----------------------------------------------------

    @Test
    void aBodySharedByRowsCountsOnceAndASaveAsCopyEvictsNothing() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        List<HistoryRevision> original = new java.util.ArrayList<>();
        for (int i = 30; i >= 1; i--) {
            original.add(revAt("/p/a", i, MB, "body" + i));
        }
        bucket.put("/p/a", original);
        assertTrue(HistoryMoves.copy(bucket, "/p/a", "/p/b"));

        assertEquals(30 * MB, HistoryRetention.storedBytes(bucket), "sixty rows, thirty bodies on disk");
        assertEquals(60 * MB, HistoryRetention.totalBytes(bucket), "the row sum is only an upper bound");
        assertEquals(0, HistoryRetention.storedBytes(null));
        assertFalse(HistoryRetention.exceedsBudget(bucket, 50 * MB));
        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(bucket, 50 * MB);
        assertEquals(30, out.get("/p/a").size());
        assertEquals(30, out.get("/p/b").size());

        // Sixty rows of one body are one megabyte, not sixty.
        List<HistoryRevision> repeated = new java.util.ArrayList<>();
        for (int i = 60; i >= 1; i--) {
            repeated.add(labelled(i, MB, "same", "safety " + i));
        }
        assertEquals(MB, HistoryRetention.storedBytes(Map.of("/p/x", repeated)));
    }

    @Test
    void evictingOneOfTwoRowsThatShareABodyFreesNothingSoTheNextOneGoesToo() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put(
                "/a", List.of(revAt("/a", 9, 10, "new"), revAt("/a", 3, 60, "shared"), revAt("/a", 2, 60, "shared")));
        bucket.put("/b", List.of(revAt("/b", 8, 10, "b")));
        assertEquals(80, HistoryRetention.storedBytes(bucket));
        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(bucket, 70);
        assertEquals(
                List.of("new"),
                out.get("/a").stream().map(HistoryRevision::sha256).toList());
        assertEquals(20, HistoryRetention.storedBytes(out));
    }

    @Test
    void exceedsBudgetAgreesWithTheDistinctTotal() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put("/a", List.of(revAt("/a", 2, 60, "x"), revAt("/a", 1, 60, "x")));
        bucket.put("/gone", null); // a damaged index: tolerated, counted as nothing
        assertFalse(HistoryRetention.exceedsBudget(bucket, 100), "rows sum to 120, the one body is 60");
        assertTrue(HistoryRetention.exceedsBudget(bucket, 59));
        assertFalse(HistoryRetention.exceedsBudget(bucket, 0), "no limit");
        assertFalse(HistoryRetention.exceedsBudget(null, 1));
        assertEquals(
                List.of("/a"),
                List.copyOf(HistoryRetention.enforceProjectBudget(bucket, 1).keySet()));
        // A row without a hash cannot be told apart from another: each counts.
        assertEquals(
                14, HistoryRetention.storedBytes(Map.of("/n", List.of(revAt("/n", 2, 7, ""), revAt("/n", 1, 7, "")))));
        assertEquals(
                1,
                HistoryRetention.enforceProjectBudget(
                                Map.of("/n", List.of(revAt("/n", 2, 7, ""), revAt("/n", 1, 7, ""))), 10)
                        .get("/n")
                        .size());
    }

    // --- A12 / B13: folding a recorded revision into the list as it is now --------------------------------

    private static HistoryRevision auto(long ts, String sha) {
        return new HistoryRevision("/tmp/a.txt", ts, 1, sha, HistoryRevision.REASON_AUTOSAVE);
    }

    @Test
    void aRevisionThatRepeatsTheNewestRowIsNotFoldedInTwice() {
        List<HistoryRevision> current = List.of(rev(10, 1, "same"), rev(5, 1, "older"));
        HistoryRevision again = new HistoryRevision("/tmp/a.txt", 11, 1, "same", HistoryRevision.REASON_EXTERNAL);
        assertTrue(HistoryRetention.repeatsNewest(current, again));
        assertTrue(HistoryRetention.fold(current, again, 0) == current, "the same instance: nothing to save");
        assertFalse(HistoryRetention.repeatsNewest(current, rev(11, 1, "older")), "only the newest row counts");
        assertFalse(HistoryRetention.repeatsNewest(current, labelled(11, 1, "same", "v1")), "a label is a point");
        assertFalse(HistoryRetention.repeatsNewest(current, deleted(11, 1, "same")));
        assertFalse(HistoryRetention.repeatsNewest(current, null));
        assertFalse(HistoryRetention.repeatsNewest(List.of(), again));
        assertEquals(List.of(), HistoryRetention.fold(null, null, 0));
        assertEquals(
                List.of(labelled(11, 1, "same", "v1"), rev(10, 1, "same"), rev(5, 1, "older")),
                HistoryRetention.fold(current, labelled(11, 1, "same", "v1"), 0));
        assertEquals(List.of(again), HistoryRetention.fold(null, again, 0));
    }

    @Test
    void aRunOfAutoSavesLeavesOneRevisionPerWindowPlusTheLatest() {
        long window = HistoryRetention.AUTOSAVE_COALESCE_MILLIS;
        List<HistoryRevision> list = List.of();
        for (long t = 0; t <= 2 * window + 20_000; t += 10_000) { // an auto-save every ten seconds
            list = HistoryRetention.fold(list, auto(t, "text@" + t), window);
        }
        assertEquals(
                List.of(2 * window + 20_000, 2 * window - 20_000, window - 10_000, 0L),
                list.stream().map(HistoryRevision::timestamp).toList());
    }

    @Test
    void onlyAnAutoSaveReplacesAndOnlyAnAutoSaveIsReplaced() {
        long window = 1000;
        List<HistoryRevision> autos = List.of(auto(20, "b"), auto(10, "a"));
        assertTrue(HistoryRetention.replacesNewestAutosave(autos, auto(30, "c"), window));
        assertEquals(List.of(auto(30, "c"), auto(10, "a")), HistoryRetention.fold(autos, auto(30, "c"), window));
        assertFalse(HistoryRetention.replacesNewestAutosave(autos, rev(30, 1, "c"), window), "a manual save");
        assertFalse(HistoryRetention.replacesNewestAutosave(autos, auto(1010, "c"), window), "window over");
        assertFalse(HistoryRetention.replacesNewestAutosave(autos, auto(15, "c"), window), "older than newest");
        assertFalse(HistoryRetention.replacesNewestAutosave(autos, auto(30, "c"), 0), "coalescing off");
        assertFalse(HistoryRetention.replacesNewestAutosave(autos, null, window));
        assertFalse(HistoryRetention.replacesNewestAutosave(null, auto(30, "c"), window));
        assertFalse(HistoryRetention.replacesNewestAutosave(List.of(auto(20, "b")), auto(30, "c"), window));
        // The newest row is a manual save or a labelled auto-save: kept.
        assertFalse(HistoryRetention.replacesNewestAutosave(
                List.of(rev(20, 1, "b"), auto(10, "a")), auto(30, "c"), window));
        HistoryRevision named = new HistoryRevision("/tmp/a.txt", 20, 1, "b", HistoryRevision.REASON_AUTOSAVE, "keep");
        assertFalse(HistoryRetention.replacesNewestAutosave(List.of(named, auto(10, "a")), auto(30, "c"), window));
        // The anchor is in the future (a clock that went back): do not replace.
        assertFalse(HistoryRetention.replacesNewestAutosave(
                List.of(auto(20, "b"), rev(40, 1, "a")), auto(30, "c"), window));
    }

    @Test
    void liveHashesSkipsANullRow() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put("/a", java.util.Arrays.asList(rev(2, 1, "x"), null));
        assertEquals(Set.of("x"), HistoryRetention.liveHashes(Map.of("p", bucket)));
    }

    @Test
    void totalBytesIsTheSumOfTheRows() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        bucket.put("/a", List.of(rev(1, 100, "a1"), rev(2, 50, "a2")));
        bucket.put("/b", List.of(rev(3, 7, "b1")));
        bucket.put("/c", List.of());
        assertEquals(157, HistoryRetention.totalBytes(bucket));
        assertEquals(0, HistoryRetention.totalBytes(Map.of()));
        assertEquals(0, HistoryRetention.totalBytes(null));
    }
}
