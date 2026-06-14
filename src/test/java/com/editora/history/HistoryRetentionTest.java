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
}
