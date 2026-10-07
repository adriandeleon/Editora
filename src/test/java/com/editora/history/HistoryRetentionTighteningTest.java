package com.editora.history;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.HistoryRevision;
import com.editora.history.HistoryRetention.Impact;
import com.editora.history.HistoryRetention.RetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lowering a Local History limit deletes revisions; raising one never does. These are the decisions behind
 * "ask before a tightened limit is applied": which changes tighten, what they would delete, and which
 * policy stays in force until the user has answered.
 */
class HistoryRetentionTighteningTest {

    private static final long DAY = 86_400_000L;
    private static final long MB = 1024L * 1024L;
    private static final long NOW = 1_000 * DAY;

    private static RetentionPolicy policy(int perFile, int days, int mb) {
        return new RetentionPolicy(perFile, days * DAY, mb * MB);
    }

    private static HistoryRevision rev(String path, int ageDays, String label) {
        return new HistoryRevision(
                path, NOW - ageDays * DAY, 10, path + "@" + ageDays, HistoryRevision.REASON_SAVE, label);
    }

    @Test
    void onlyAStricterLimitTightens() {
        RetentionPolicy base = policy(50, 30, 50);
        assertFalse(HistoryRetention.tightens(base, base));
        assertFalse(HistoryRetention.tightens(base, policy(60, 30, 50)));
        assertFalse(HistoryRetention.tightens(base, policy(50, 31, 50)));
        assertFalse(HistoryRetention.tightens(base, policy(50, 30, 51)));
        assertFalse(HistoryRetention.tightens(base, policy(50, 0, 50)), "0 days = no age limit: the loosest");
        assertTrue(HistoryRetention.tightens(base, policy(49, 30, 50)));
        assertTrue(HistoryRetention.tightens(base, policy(50, 29, 50)));
        assertTrue(HistoryRetention.tightens(base, policy(50, 30, 49)));
        assertTrue(HistoryRetention.tightens(policy(50, 0, 50), base), "an age limit where there was none");
        assertTrue(HistoryRetention.tightens(base, policy(100, 29, 100)), "one stricter limit is enough");
        assertFalse(HistoryRetention.tightens(null, base), "no policy yet: nothing to be stricter than");
    }

    @Test
    void theLoosestOfTwoPoliciesKeepsWhatEitherKeeps() {
        assertEquals(policy(50, 30, 80), HistoryRetention.loosest(policy(50, 10, 50), policy(20, 30, 80)));
        assertEquals(policy(50, 0, 50), HistoryRetention.loosest(policy(50, 0, 50), policy(50, 30, 50)));
    }

    @Test
    void theImpactCountsWhatTheNewLimitDeletesAndTheOldOneKept() {
        String a = "/p/a.txt";
        String b = "/p/b.txt";
        Map<String, Map<String, List<HistoryRevision>>> index = Map.of(
                "proj",
                Map.of(a, List.of(rev(a, 5, ""), rev(a, 15, ""), rev(a, 20, ""), rev(a, 25, ""))),
                "",
                Map.of(b, List.of(rev(b, 1, ""), rev(b, 12, ""), rev(b, 40, ""))));

        // 30 → 10 days: a loses 15/20/25, b loses 12. b's 40-day revision is already outside the current
        // limit and would go at the next sweep whatever the user answers, so it is not counted.
        assertEquals(
                new Impact(4, 2),
                HistoryRetention.tighteningImpact(index, policy(50, 30, 50), policy(50, 10, 50), NOW));
        assertEquals(
                new Impact(1, 1),
                HistoryRetention.tighteningImpact(index, policy(50, 30, 50), policy(50, 22, 50), NOW));
        assertEquals(
                Impact.NONE, HistoryRetention.tighteningImpact(index, policy(50, 30, 50), policy(50, 29, 50), NOW));
        assertEquals(
                new Impact(2, 1),
                HistoryRetention.tighteningImpact(index, policy(50, 30, 50), policy(2, 30, 50), NOW),
                "two per file: a has four inside the age limit, b only two");
    }

    @Test
    void labelledAndPreDeleteRevisionsKeepTheirProtection() {
        String a = "/p/a.txt";
        HistoryRevision deleted = new HistoryRevision(a, NOW - 20 * DAY, 10, "gone", HistoryRevision.REASON_DELETE, "");
        Map<String, Map<String, List<HistoryRevision>>> index =
                Map.of("", Map.of(a, List.of(rev(a, 5, ""), rev(a, 15, "before refactor"), deleted, rev(a, 25, ""))));

        assertEquals(
                new Impact(1, 1),
                HistoryRetention.tighteningImpact(index, policy(50, 30, 50), policy(1, 10, 50), NOW),
                "only the unprotected 25-day revision is in reach of a 10-day, 1-per-file limit");
    }

    @Test
    void aStricterLimitWaitsForTheUserALooserOneDoesNot(@TempDir Path dir) {
        HistoryService service = new HistoryService(new HistoryBlobStore(dir));
        try {
            RetentionPolicy start = policy(50, 30, 50);
            assertEquals(start, service.effectivePolicy(start), "a session starts with what the settings say");

            assertEquals(start, service.effectivePolicy(policy(50, 10, 50)), "a lower limit is not in force yet");
            assertEquals(
                    policy(80, 30, 50),
                    service.effectivePolicy(policy(80, 10, 50)),
                    "the raised limit is, the lowered one still is not");
            assertEquals(policy(80, 60, 50), service.effectivePolicy(policy(80, 60, 50)), "looser: at once");
            assertEquals(
                    policy(80, 60, 50),
                    service.effectivePolicy(policy(80, 30, 50)),
                    "and going back down from there asks again");

            service.acknowledge(policy(80, 10, 50));
            assertEquals(policy(80, 10, 50), service.effectivePolicy(policy(80, 10, 50)), "confirmed: in force");
        } finally {
            service.shutdown();
        }
    }
}
