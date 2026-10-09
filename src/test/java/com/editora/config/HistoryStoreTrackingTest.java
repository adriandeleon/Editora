package com.editora.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7: the maps {@link HistoryStore} hands out keep its books — every way of changing the index is seen, so a
 * save costs what changed (the snapshot shares untouched projects; the body hashes are already counted).
 */
class HistoryStoreTrackingTest {

    private static HistoryRevision rev(String path, long ts, String sha) {
        return new HistoryRevision(path, ts, 1, sha, HistoryRevision.REASON_SAVE);
    }

    @Test
    void everyWayOfChangingTheIndexReachesTheLedger() {
        HistoryStore store = new HistoryStore();
        HistoryHashLedger ledger = store.ledger();
        Map<String, List<HistoryRevision>> bucket = store.bucket("p");

        assertNull(bucket.put("/a", new ArrayList<>(List.of(rev("/a", 2, "a2"), rev("/a", 1, "a1")))));
        bucket.put("/b", List.of(rev("/b", 1, "b1"), rev("/b", 0, "a1"))); // shares a body with /a
        assertEquals(Set.of("a1", "a2", "b1"), ledger.listedHashes());

        // put replacing a list
        assertEquals(
                2,
                bucket.put("/a", List.of(rev("/a", 3, "a3"), rev("/a", 2, "a2")))
                        .size());
        assertEquals(Set.of("a1", "a2", "a3", "b1"), ledger.listedHashes(), "a1 is still listed by /b");
        // remove
        assertEquals(2, bucket.remove("/b").size());
        assertNull(bucket.remove("/b"));
        assertEquals(Set.of("a2", "a3"), ledger.listedHashes());
        // an entry's setValue
        for (Map.Entry<String, List<HistoryRevision>> entry : bucket.entrySet()) {
            assertEquals(entry, Map.entry(entry.getKey(), entry.getValue()));
            assertEquals(Map.entry(entry.getKey(), entry.getValue()).hashCode(), entry.hashCode());
            assertTrue(entry.toString().startsWith("/a="));
            entry.setValue(List.of(rev("/a", 4, "a4")));
        }
        assertEquals(Set.of("a4"), ledger.listedHashes());
        // an iterator's remove (how HistoryMoves.rename takes a file out)
        bucket.put("/c", List.of(rev("/c", 1, "c1")));
        for (var it = bucket.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getKey().equals("/a")) {
                it.remove();
            }
        }
        assertEquals(Set.of("c1"), ledger.listedHashes());
        assertEquals(Set.of("/c"), bucket.keySet());
        // values().remove / keySet().remove go through the same iterator
        bucket.put("/d", List.of(rev("/d", 1, "d1")));
        assertTrue(bucket.keySet().remove("/c"));
        assertEquals(Set.of("d1"), ledger.listedHashes());
        // merge and putAll go through put
        bucket.merge("/d", List.of(rev("/d", 2, "d2")), (present, added) -> {
            List<HistoryRevision> out = new ArrayList<>(added);
            out.addAll(present);
            return out;
        });
        bucket.putAll(Map.of("/e", List.of(rev("/e", 1, "e1"))));
        assertEquals(Set.of("d1", "d2", "e1"), ledger.listedHashes());
        // clear
        bucket.clear();
        bucket.clear();
        assertTrue(ledger.listsNothing());
        assertTrue(bucket.isEmpty());
        // What was dropped is still protected until an index without it is on disk.
        assertTrue(ledger.protectedHashes().containsAll(Set.of("a1", "a2", "a3", "a4", "b1", "c1", "d1", "d2", "e1")));
        ledger.durable(ledger.publish());
        assertTrue(ledger.protectedHashes().isEmpty());
    }

    @Test
    void aFilesListIsReplacedNeverEditedInPlace() {
        HistoryStore store = new HistoryStore();
        Map<String, List<HistoryRevision>> bucket = store.bucket("");
        List<HistoryRevision> mine = new ArrayList<>(List.of(rev("/a", 1, "a1")));
        bucket.put("/a", mine);
        mine.add(rev("/a", 2, "detached")); // the caller's list is not the index
        assertEquals(1, bucket.get("/a").size());
        assertThrows(UnsupportedOperationException.class, () -> bucket.get("/a").add(rev("/a", 2, "a2")));
        // computeIfAbsent answers the list that is stored, so the old "…computeIfAbsent(…).add(…)" idiom fails
        // loudly instead of adding to a list the index does not hold.
        List<HistoryRevision> stored = bucket.computeIfAbsent("/new", key -> new ArrayList<>());
        assertThrows(UnsupportedOperationException.class, () -> stored.add(rev("/new", 1, "n1")));
        assertSame(bucket.get("/a"), bucket.computeIfAbsent("/a", key -> new ArrayList<>()));
        assertNull(bucket.computeIfAbsent("/none", key -> null));
        assertFalse(bucket.containsKey("/none"));
        // Null rows (a damaged index) are dropped on the way in.
        bucket.put("/n", java.util.Arrays.asList(null, rev("/n", 1, "n1"), null));
        assertEquals(List.of(rev("/n", 1, "n1")), bucket.get("/n"));
        assertThrows(NullPointerException.class, () -> bucket.put("/x", null));
    }

    @Test
    void projectsComeAndGoWithTheirHashes() {
        HistoryStore store = new HistoryStore();
        Map<String, Map<String, List<HistoryRevision>>> projects = store.getByProject();
        Map<String, List<HistoryRevision>> plain = new LinkedHashMap<>();
        plain.put("/a", List.of(rev("/a", 1, "a1")));
        plain.put("/null", null);
        projects.put("p", plain);
        plain.put("/later", List.of(rev("/later", 1, "x"))); // the caller's map is not the index either
        assertEquals(Set.of("/a"), store.bucket("p").keySet());
        assertEquals(Set.of("a1"), store.ledger().listedHashes());
        assertSame(store.bucket("p"), projects.put("p", store.bucket("p")), "putting a bucket back is a no-op");
        assertEquals(Set.of("a1"), store.ledger().listedHashes());

        Map<String, List<HistoryRevision>> detached = projects.remove("p");
        assertTrue(store.ledger().listsNothing());
        detached.put("/b", List.of(rev("/b", 1, "b1"))); // a bucket that left the index no longer counts
        detached.remove("/a");
        assertTrue(store.ledger().listsNothing());

        store.bucket("q").put("/c", List.of(rev("/c", 1, "c1")));
        store.setByProject(store.getByProject()); // itself: nothing happens
        assertEquals(Set.of("c1"), store.ledger().listedHashes());
        Map<String, Map<String, List<HistoryRevision>>> replacement = new LinkedHashMap<>();
        replacement.put(null, Map.of("/d", List.of(rev("/d", 1, "d1"))));
        replacement.put("dead", null);
        store.setByProject(replacement);
        assertEquals(Set.of(""), projects.keySet());
        assertEquals(Set.of("d1"), store.ledger().listedHashes());
        store.setByProject(null);
        assertTrue(projects.isEmpty());
    }

    @Test
    void aSnapshotSharesEveryProjectThatDidNotChange() {
        HistoryStore store = new HistoryStore();
        store.bucket("p").put("/a", List.of(rev("/a", 1, "a1")));
        store.bucket("q").put("/b", List.of(rev("/b", 1, "b1")));
        store.setAcknowledgedLimits(new HistoryStore.Limits(50, 0, 0));
        HistoryStore.Snapshot first = store.snapshot();
        HistoryStore.Snapshot again = store.snapshot();
        assertSame(first.byProject().get("p"), again.byProject().get("p"));

        store.bucket("p").put("/a", List.of(rev("/a", 2, "a2"), rev("/a", 1, "a1")));
        HistoryStore.Snapshot next = store.snapshot();
        assertNotSame(first.byProject().get("p"), next.byProject().get("p"));
        assertSame(first.byProject().get("q"), next.byProject().get("q"), "untouched: not copied again");
        assertEquals(1, first.byProject().get("p").get("/a").size(), "a snapshot does not change afterwards");
        assertEquals(2, next.byProject().get("p").get("/a").size());
        assertEquals(new HistoryStore.Limits(50, 0, 0), next.acknowledgedLimits());
        assertEquals(HistoryStore.SCHEMA_VERSION, next.schemaVersion());
        assertThrows(
                UnsupportedOperationException.class,
                () -> next.byProject().get("p").remove("/a"));
        assertThrows(UnsupportedOperationException.class, () -> next.byProject().remove("p"));
    }

    @Test
    void sortingPutsEveryListNewestFirstAndSaysWhetherItHadTo() {
        HistoryStore store = new HistoryStore();
        store.bucket("")
                .put(
                        "/ordered",
                        List.of(rev("/ordered", 2, "o2"), rev("/ordered", 2, "o2b"), rev("/ordered", 1, "o1")));
        assertFalse(store.sortNewestFirst());
        store.bucket("")
                .put(
                        "/merged",
                        List.of(
                                rev("/merged", 4, "m4"),
                                rev("/merged", 1, "m1"),
                                rev("/merged", 3, "m3"),
                                rev("/merged", 3, "m3b")));
        assertTrue(store.sortNewestFirst());
        assertEquals(
                List.of("m4", "m3", "m3b", "m1"),
                store.bucket("").get("/merged").stream()
                        .map(HistoryRevision::sha256)
                        .toList(),
                "ties keep their order");
        assertEquals(
                Set.of("o1", "o2", "o2b", "m1", "m3", "m3b", "m4"),
                store.ledger().listedHashes());
        assertFalse(store.sortNewestFirst());
    }

    @Test
    void theLedgerKeepsADroppedHashUntilAPublicationWithoutItIsOnDisk() {
        HistoryHashLedger ledger = new HistoryHashLedger();
        ledger.added("x");
        ledger.added("x");
        ledger.added("");
        ledger.added(null);
        long first = ledger.publish(); // lists x
        ledger.removed("x");
        assertEquals(Set.of("x"), ledger.listedHashes(), "another row still lists it");
        ledger.removed("x");
        ledger.removed("x"); // not listed any more: ignored
        ledger.removed("never");
        ledger.removed("");
        ledger.removed(null);
        assertTrue(ledger.listsNothing());
        assertEquals(Set.of("x"), ledger.protectedHashes(), "the index on disk still lists it");
        ledger.durable(first);
        assertEquals(Set.of("x"), ledger.protectedHashes(), "that publication was taken before the row went");
        long second = ledger.publish();
        ledger.durable(second);
        assertTrue(ledger.protectedHashes().isEmpty());
        // Dropped and listed again before anything was written: listed wins.
        ledger.added("y");
        ledger.removed("y");
        ledger.added("y");
        ledger.durable(ledger.publish());
        assertEquals(Set.of("y"), ledger.protectedHashes());
    }
}
