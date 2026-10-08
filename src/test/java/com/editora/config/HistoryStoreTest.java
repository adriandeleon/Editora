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

    // --- schema 3: a pre-delete revision records its file's encoding ----------------------------------

    @Test
    void encodingOfADeletedFileRoundTripsAndIsLeftOutOfOrdinaryRows() throws Exception {
        HistoryStore store = new HistoryStore();
        HistoryRevision deleted =
                new HistoryRevision("/tmp/a.reg", 1000L, 12L, "sha-a", "DELETE").withEncoding("utf-16le", true, "CRLF");
        HistoryRevision saved = new HistoryRevision("/tmp/a.reg", 900L, 12L, "sha-b", "SAVE");
        store.bucket("").put("/tmp/a.reg", List.of(deleted, saved));

        String json = mapper.writeValueAsString(store);
        HistoryStore back = mapper.readValue(json, HistoryStore.class);

        assertEquals(List.of(deleted, saved), back.bucket("").get("/tmp/a.reg"));
        HistoryRevision read = back.bucket("").get("/tmp/a.reg").get(0);
        assertEquals("utf-16le", read.charset());
        assertTrue(read.bom());
        assertEquals("CRLF", read.lineEnding());
        assertTrue(read.hasEncoding());
        var rows = mapper.readTree(json).get("byProject").get("").get("/tmp/a.reg");
        assertTrue(rows.get(0).has("charset")
                && rows.get(0).has("bom")
                && rows.get(0).has("lineEnding"));
        assertEquals(
                java.util.Set.of("path", "timestamp", "sizeBytes", "sha256", "reason", "label"),
                fieldNames(rows.get(1)),
                "a row without the metadata is written exactly as schema 2 wrote it");
    }

    @Test
    void relabelAndMoveKeepTheRecordedEncoding() {
        HistoryRevision deleted =
                new HistoryRevision("/tmp/a.reg", 1000L, 12L, "sha-a", "DELETE").withEncoding("utf-8-bom", true, "LF");
        assertEquals("utf-8-bom", deleted.withLabel("keep").charset());
        assertTrue(deleted.withLabel("keep").bom());
        assertEquals("keep", deleted.withLabel("keep").label());
        HistoryRevision moved = com.editora.history.HistoryMoves.at("/tmp/b.reg", deleted);
        assertEquals("/tmp/b.reg", moved.path());
        assertEquals(deleted.withPath("/tmp/b.reg"), moved);
        assertTrue(moved.hasEncoding());
    }

    @Test
    void aSchemaTwoIndexIsReadByTheMigrationPathAndStampedCurrent(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path index = dir.resolve("index.json");
        java.nio.file.Files.writeString(
                index,
                "{\"schemaVersion\":2,\"byProject\":{\"\":{\"/tmp/a.txt\":[{\"path\":\"/tmp/a.txt\",\"timestamp\":5,"
                        + "\"sizeBytes\":3,\"sha256\":\"sha\",\"reason\":\"DELETE\",\"label\":\"\"}]}}}");
        java.util.List<com.editora.config.migration.ConfigLoadProblem> problems = new java.util.ArrayList<>();

        HistoryStore read = com.editora.config.migration.ConfigMigrations.readVersioned(
                index, mapper, new HistoryStore(), com.editora.config.migration.ConfigSchema.HISTORY, problems::add);

        assertEquals(List.of(), problems);
        assertEquals(4, HistoryStore.SCHEMA_VERSION);
        assertEquals(HistoryStore.SCHEMA_VERSION, read.getSchemaVersion());
        HistoryRevision row = read.bucket("").get("/tmp/a.txt").get(0);
        assertEquals("sha", row.sha256());
        assertEquals("", row.charset());
        assertEquals("", row.lineEnding());
        assertEquals(false, row.bom());
        assertEquals(false, row.hasEncoding(), "an older row keeps today's restore behaviour");
        assertEquals(
                List.of(),
                List.copyOf(java.nio.file.Files.list(dir)
                        .filter(p -> !p.equals(index))
                        .toList()));
    }

    private static java.util.Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.Set<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
