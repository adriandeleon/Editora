package com.editora.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.HistoryRevision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E2 of the second review, and how the newer capture reasons are retained. */
class HistoryRetentionBudgetFloorTest {

    private static final long MB = 1L << 20;

    /** Four big files saved once each, twenty source files saved ten times each. */
    private static Map<String, List<HistoryRevision>> project() {
        Map<String, List<HistoryRevision>> bucket = new LinkedHashMap<>();
        int sha = 0;
        for (int f = 0; f < 4; f++) {
            String path = "/p/big" + f + ".json";
            bucket.put(
                    path,
                    new ArrayList<>(List.of(new HistoryRevision(path, 900_000 + f, 15 * MB, "s" + sha++, "SAVE"))));
        }
        for (int f = 0; f < 20; f++) {
            String path = "/p/src" + f + ".java";
            List<HistoryRevision> list = new ArrayList<>();
            for (int r = 10; r > 0; r--) {
                list.add(new HistoryRevision(path, r * 1000L + f, 8192, "s" + sha++, "SAVE"));
            }
            bucket.put(path, list);
        }
        return bucket;
    }

    @Test
    void revisionsThatAreNeverEvictedOverTheLimitDoNotEmptyEveryOtherFile() {
        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(project(), 50 * MB);
        for (int f = 0; f < 20; f++) {
            assertEquals(10, out.get("/p/src" + f + ".java").size(), "a small file keeps its history");
        }
    }

    @Test
    void underTheFloorTheOldestStillGoFirst() {
        Map<String, List<HistoryRevision>> bucket = project();
        // 60 MB never evicted + 1.6 MB of older source revisions; a 61 MB limit is reachable
        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(bucket, 61 * MB);
        int small = out.entrySet().stream()
                .filter(e -> e.getKey().contains("src"))
                .mapToInt(e -> e.getValue().size())
                .sum();
        assertTrue(small < 200 && small >= 20, "oldest-first ran: " + small);
        assertTrue(HistoryRetention.storedBytes(out) <= 61 * MB);
    }

    @Test
    void aLargeFileAboveItsShareStillShedsItsOwnOlderRevisions() {
        Map<String, List<HistoryRevision>> bucket = project();
        List<HistoryRevision> big = bucket.get("/p/big0.json");
        big.add(new HistoryRevision("/p/big0.json", 800_000, 15 * MB, "older-big", "SAVE"));
        Map<String, List<HistoryRevision>> out = HistoryRetention.enforceProjectBudget(bucket, 50 * MB);
        assertEquals(1, out.get("/p/big0.json").size());
    }

    @Test
    void theNewerReasonsAreAutomaticRevisionsLikeAnExternalChange() {
        for (String reason : List.of(
                HistoryRevision.REASON_AGENT,
                HistoryRevision.REASON_HTTP,
                HistoryRevision.REASON_RESTORE,
                HistoryRevision.REASON_REVERT,
                HistoryRevision.REASON_LINK_UPDATE,
                HistoryRevision.REASON_EXTERNAL)) {
            HistoryRevision older = new HistoryRevision("/a", 1_000, 1, "old", reason);
            assertFalse(HistoryRetention.isProtected(older), reason);
            List<HistoryRevision> list = List.of(new HistoryRevision("/a", 3_000, 1, "new", reason), older);
            assertEquals(1, HistoryRetention.prune(list, 1, 0, 4_000).size(), reason + " counts against the cap");
            assertEquals(1, HistoryRetention.prune(list, 0, 1_500, 4_000).size(), reason + " ages out");
        }
    }
}
