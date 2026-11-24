package com.editora.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HistoryDelta}: what a delta says, how it applies, and how two writers' deltas merge. */
class HistoryDeltaTest {

    private final ObjectMapper json = new ObjectMapper();

    private static HistoryRevision row(String path, long time) {
        return new HistoryRevision(path, time, 1, String.format("%064x", time), HistoryRevision.REASON_SAVE);
    }

    private static HistoryStore.Snapshot snapshot(Map<String, List<HistoryRevision>> files) {
        return new HistoryStore.Snapshot(HistoryStore.SCHEMA_VERSION, Map.of("p", files), null);
    }

    @Test
    void aDeltaTurnsTheIndexItWasTakenAgainstIntoTheOneInMemory() {
        HistoryRevision a1 = row("/a", 1);
        HistoryRevision a2 = row("/a", 2);
        HistoryRevision b1 = row("/b", 1);
        HistoryRevision c1 = row("/c", 1);
        HistoryStore.Snapshot base = snapshot(Map.of("/a", List.of(a1), "/b", List.of(b1)));
        Map<String, List<HistoryRevision>> now = new LinkedHashMap<>();
        now.put("/a", List.of(a2, a1)); // a revision added
        now.put("/c", List.of(c1)); // a file added; /b purged
        HistoryStore.Snapshot mine = snapshot(now);

        HistoryDelta delta = HistoryDelta.diff(base, mine, "h");

        assertEquals(Map.of("p", Map.of("/a", List.of(a2), "/c", List.of(c1))), delta.added());
        assertEquals(Map.of("p", Map.of("/b", List.of(b1))), delta.removed());
        assertEquals(mine.byProject(), delta.applyTo(base).byProject());
    }

    @Test
    void applyingTwiceOrToAnIndexThatAlreadyHoldsItChangesNothing() {
        HistoryRevision a1 = row("/a", 1);
        HistoryRevision a2 = row("/a", 2);
        HistoryStore.Snapshot base = snapshot(Map.of("/a", List.of(a1)));
        HistoryStore.Snapshot mine = snapshot(Map.of("/a", List.of(a2)));
        HistoryDelta delta = HistoryDelta.diff(base, mine, "h");

        HistoryStore.Snapshot once = delta.applyTo(base);
        // A crash between writing the index whole and emptying the delta leaves this delta beside `mine`.
        assertEquals(once.byProject(), delta.applyTo(once).byProject());
        assertEquals(mine.byProject(), delta.applyTo(mine).byProject());
    }

    @Test
    void anotherWritersRowsInTheIndexSurviveAStaleDelta() {
        HistoryRevision a1 = row("/a", 1);
        HistoryRevision mineNew = row("/a", 5);
        HistoryRevision theirsNew = row("/a", 3);
        HistoryStore.Snapshot base = snapshot(Map.of("/a", List.of(a1)));
        HistoryDelta delta = HistoryDelta.diff(base, snapshot(Map.of("/a", List.of(mineNew))), "h");
        // An older build added a row to the index and did not know of the delta.
        HistoryStore.Snapshot rewritten = snapshot(Map.of("/a", List.of(theirsNew, a1)));

        assertEquals(
                List.of(mineNew, theirsNew),
                delta.applyTo(rewritten).byProject().get("p").get("/a"));
    }

    @Test
    void theFileRoundTripsAndANewerOneIsRefused() throws Exception {
        HistoryDelta delta = new HistoryDelta(
                "abc",
                Map.of("p", Map.of("/a", List.of(row("/a", 2)))),
                Map.of("p", Map.of("/b", List.of(row("/b", 1)))),
                new HistoryStore.Limits(5, 6, 7));
        assertEquals(delta, HistoryDelta.fromBytes(delta.toBytes(json), json));

        JsonNode newer = delta.toTree(json).put("schemaVersion", HistoryDelta.SCHEMA_VERSION + 1);
        assertThrows(HistoryDelta.NewerDeltaException.class, () -> HistoryDelta.fromTree(newer, json));
        assertThrows(java.io.IOException.class, () -> HistoryDelta.fromBytes("{\"added\":".getBytes(), json));
    }

    @Test
    void twoWritersDeltasMergeRowByRow() throws Exception {
        HistoryRevision a1 = row("/a", 1);
        HistoryRevision mine = row("/a", 2);
        HistoryRevision theirs = row("/a", 3);
        HistoryDelta base = new HistoryDelta("h", Map.of("p", Map.of("/a", List.of(a1))), Map.of(), null);
        HistoryDelta ours = new HistoryDelta("h", Map.of("p", Map.of("/a", List.of(a1, mine))), Map.of(), null);
        // The other writer purged a1 (it only ever was in the delta) and added its own row.
        HistoryDelta other = new HistoryDelta("h", Map.of("p", Map.of("/a", List.of(theirs))), Map.of(), null);

        JsonNode merged =
                StoreMerge.merge(base.toTree(json), ours.toTree(json), other.toTree(json), HistoryDelta.MERGE_KEYS);

        List<HistoryRevision> rows =
                HistoryDelta.fromTree(merged, json).added().get("p").get("/a");
        assertEquals(List.of(mine, theirs), rows, "the purge stays, both new rows are kept");
    }

    @Test
    void theDeltaIsNamedSoOlderBuildsProtectItsBodies() {
        // HistoryIndexGuard's backup pattern in every release since 0.25: index.json + "\..*bak(\.\d+)?".
        assertTrue(java.util.regex.Pattern.compile("\\..*bak(\\.\\d+)?")
                .matcher(HistoryIndexFiles.DELTA_SUFFIX)
                .matches());
    }

    @Test
    void aFileTheOtherWriterEmptiedMergesRowByRowNotAsAConflict() {
        HistoryRevision old = row("/a", 1);
        HistoryRevision mineNew = row("/a", 2);
        JsonNode base = json.valueToTree(snapshot(Map.of("/a", List.of(old))));
        JsonNode mine = json.valueToTree(snapshot(Map.of("/a", List.of(mineNew, old))));
        // The other writer purged the file's only row, which took the file (and the project) out of its index.
        JsonNode theirs = json.valueToTree(new HistoryStore.Snapshot(HistoryStore.SCHEMA_VERSION, Map.of(), null));

        JsonNode merged = HistoryIndexFiles.mergeRows(
                base,
                mine,
                theirs,
                StoreMerge.keysFor(com.editora.config.migration.ConfigSchema.HISTORY),
                List.of(List.of("byProject")));

        assertEquals(json.valueToTree(snapshot(Map.of("/a", List.of(mineNew)))), merged);
    }
}
