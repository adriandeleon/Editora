package com.editora.config;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Local File History index ({@code history/index.json}) is a {@link HistoryStore} — per-project
 * buckets of per-file, newest-first {@link HistoryRevision} metadata. Verifies JSON round-trip and the
 * per-project bucket creation, mirroring {@code BookmarkStoreTest}.
 */
class HistoryStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsThroughJson() throws Exception {
        HistoryStore store = new HistoryStore();
        store.bucket("").put("/tmp/a.txt", List.of(new HistoryRevision("/tmp/a.txt", 1000L, 12L, "sha-a", "SAVE")));
        store.bucket("proj-1")
                .put("/tmp/b.txt", List.of(new HistoryRevision("/tmp/b.txt", 2000L, 34L, "sha-b", "EXTERNAL")));

        HistoryStore back = mapper.readValue(mapper.writeValueAsString(store), HistoryStore.class);

        assertEquals(HistoryStore.SCHEMA_VERSION, back.getSchemaVersion());
        HistoryRevision a = back.bucket("").get("/tmp/a.txt").get(0);
        assertEquals("sha-a", a.sha256());
        assertEquals(1000L, a.timestamp());
        assertEquals("EXTERNAL", back.bucket("proj-1").get("/tmp/b.txt").get(0).reason());
    }

    @Test
    void bucketCreatesEmptyForUnknownKey() {
        HistoryStore store = new HistoryStore();
        assertTrue(store.bucket("nope").isEmpty());
        assertTrue(store.bucket(null).isEmpty()); // null collapses to the "" bucket
    }

    @Test
    void revisionNullGuards() {
        HistoryRevision r = new HistoryRevision(null, 0L, 0L, null, null);
        assertEquals("", r.path());
        assertEquals("", r.sha256());
        assertEquals("SAVE", r.reason());
    }
}
