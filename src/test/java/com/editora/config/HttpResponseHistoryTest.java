package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each .http file's environment and last responses, kept in the config folder (C6, C12). */
class HttpResponseHistoryTest {

    private static HttpResponseHistory.Response response(long at) {
        return new HttpResponseHistory.Response(
                at,
                "r" + at,
                0,
                "GET",
                "http://x.test/" + at,
                List.of(),
                "",
                200,
                List.of(),
                "{}",
                false,
                false,
                "application/json",
                1,
                2,
                null,
                List.of(),
                false);
    }

    @Test
    void aFilesStateComesBackAndStaysInTheConfigFolder(@TempDir Path config, @TempDir Path work) throws Exception {
        HttpResponseHistory store = new HttpResponseHistory(config);
        Path file = work.resolve("api.http");
        assertEquals("", store.load(file).environment, "nothing saved yet");
        assertTrue(store.load(file).responses.isEmpty());

        HttpResponseHistory.Stored state = new HttpResponseHistory.Stored();
        state.environment = "dev";
        for (int i = 0; i < 15; i++) {
            state.responses.add(response(i));
        }
        store.save(file, state);

        Path saved = store.fileFor(file);
        assertTrue(saved.startsWith(config.resolve("http-history")), "never beside the .http file: " + saved);
        try (var listing = Files.list(work)) {
            assertEquals(0, listing.count(), "nothing is written into the user's folder");
        }
        HttpResponseHistory.Stored back = new HttpResponseHistory(config).load(file);
        assertEquals("dev", back.environment);
        assertEquals(HttpResponseHistory.MAX_RESPONSES, back.responses.size(), "the last ten are kept");
        assertEquals(response(0), back.responses.get(0));
        assertEquals(file.toAbsolutePath().normalize().toString(), back.file);
    }

    @Test
    void eachFileHasItsOwnHistory(@TempDir Path config, @TempDir Path work) {
        HttpResponseHistory store = new HttpResponseHistory(config);
        assertNotEquals(store.fileFor(work.resolve("a.http")), store.fileFor(work.resolve("b.http")));
        assertEquals(store.fileFor(work.resolve("a.http")), store.fileFor(work.resolve("x/../a.http")));
    }

    @Test
    void credentialHeadersAreRecognised() {
        for (String name : List.of(
                "Authorization",
                "proxy-authorization",
                "Cookie",
                "Set-Cookie",
                "X-Auth-Token",
                "X-Api-Key",
                "apikey",
                "X-Client-Secret",
                "X-Password",
                "X-Auth-Key",
                "Ocp-Apim-Subscription-Key",
                "X-Access-Key",
                "X-Session-Id",
                "X-Auth",
                "set-cookie2",
                "Authentication",
                "X-Api-Signature",
                " x-AUTH-token ")) {
            assertTrue(HttpResponseHistory.isCredentialHeader(name), name);
        }
        for (String name : List.of(
                "Accept", "Content-Type", "ETag", "X-Request-Id", "Idempotency-Key", "Connection", "X-Auth-Email")) {
            assertFalse(HttpResponseHistory.isCredentialHeader(name), name);
        }
    }

    private static HttpResponseHistory.Response at(long at, String url, String body) {
        return new HttpResponseHistory.Response(
                at,
                null,
                0,
                "GET",
                url,
                List.of(),
                "",
                200,
                List.of(),
                body,
                false,
                false,
                "text/plain",
                1,
                2,
                null,
                List.of(),
                false);
    }

    private static HttpResponseHistory.Stored stored(HttpResponseHistory.Response... rs) {
        HttpResponseHistory.Stored s = new HttpResponseHistory.Stored();
        s.responses.addAll(List.of(rs));
        return s;
    }

    @Test
    void aMergeKeepsAnotherWindowsEntriesAndDropsWhatThisOneRemoved() {
        HttpResponseHistory.Response theirs = at(30, "http://h/b", "b");
        HttpResponseHistory.Response removedHere = at(10, "http://h/old", "old");
        HttpResponseHistory.Response mine = at(40, "http://h/a2", "a2");
        HttpResponseHistory.Stored base = stored(theirs, at(20, "http://h/a", "a"), removedHere);
        java.util.Set<String> seen = java.util.Set.of(
                HttpResponseHistory.entryKey(at(20, "http://h/a", "a")), HttpResponseHistory.entryKey(removedHere));
        HttpResponseHistory.Stored merged =
                HttpResponseHistory.merge(base, stored(mine, at(20, "http://h/a", "a")), seen);
        assertEquals(
                List.of("http://h/a2", "http://h/b", "http://h/a"),
                merged.responses.stream().map(HttpResponseHistory.Response::url).toList());
    }

    @Test
    void aMergeKeepsABodyThisWindowDroppedForMemoryAndHonoursAClear() {
        HttpResponseHistory.Stored base = stored(at(20, "http://h/a", "full body"), at(5, "http://h/x", "x"));
        HttpResponseHistory.Stored mine = stored(at(20, "http://h/a", ""));
        HttpResponseHistory.Stored merged = HttpResponseHistory.merge(base, mine, java.util.Set.of());
        assertEquals("full body", merged.responses.get(0).body(), "not blanked on disk");
        mine.clearedAt = 10;
        merged = HttpResponseHistory.merge(base, mine, java.util.Set.of());
        assertEquals(
                List.of("http://h/a"),
                merged.responses.stream().map(HttpResponseHistory.Response::url).toList());
        assertEquals(10, merged.clearedAt);
    }

    @Test
    void theEntriesOfOneRunAllAreToldApart() {
        HttpResponseHistory.Response one = new HttpResponseHistory.Response(
                7,
                "one",
                0,
                "GET",
                "http://h/same",
                List.of(),
                "",
                200,
                List.of(),
                "1",
                false,
                false,
                "",
                1,
                1,
                null,
                List.of(),
                false);
        HttpResponseHistory.Response two = new HttpResponseHistory.Response(
                7,
                "two",
                1,
                "GET",
                "http://h/same",
                List.of(),
                "",
                200,
                List.of(),
                "2",
                false,
                false,
                "",
                1,
                1,
                null,
                List.of(),
                false);
        assertNotEquals(HttpResponseHistory.entryKey(one), HttpResponseHistory.entryKey(two));
        assertEquals(
                2,
                HttpResponseHistory.merge(stored(), stored(one, two), java.util.Set.of())
                        .responses
                        .size());
    }

    @Test
    void aVersionOneFileIsReadAndADamagedEntryLosesOnlyItself(@TempDir Path config, @TempDir Path work)
            throws Exception {
        HttpResponseHistory store = new HttpResponseHistory(config);
        Path file = work.resolve("v1.http");
        Files.createDirectories(store.fileFor(file).getParent());
        Files.writeString(
                store.fileFor(file),
                "{\"schemaVersion\":1,\"file\":\"x\",\"environment\":\"dev\",\"responses\":["
                        + "{\"at\":3,\"method\":\"GET\",\"url\":\"http://h/1\",\"status\":200,\"body\":\"x\"},"
                        + "{\"at\":2,\"method\":\"GET\",\"url\":\"http://h/bad\",\"status\":\"two hundred\"},"
                        + "{\"at\":1,\"method\":\"GET\",\"url\":\"http://h/3\",\"status\":200,\"body\":\"y\"}]}");
        HttpResponseHistory.Stored back = store.load(file);
        assertEquals("dev", back.environment);
        assertEquals(
                List.of("http://h/1", "http://h/3"),
                back.responses.stream().map(HttpResponseHistory.Response::url).toList());
        assertEquals(1, back.skipped);
        assertEquals(null, back.responses.get(0).sent(), "an old entry does not know how it was sent");
        store.save(file, back);
        assertTrue(Files.readString(store.fileFor(file)).contains("\"schemaVersion\":2"));
    }

    @Test
    void orphanedHistoriesAreDeletedWhenTheirFileIsGone(@TempDir Path config, @TempDir Path work) throws Exception {
        HttpResponseHistory store = new HttpResponseHistory(config);
        Path kept = Files.writeString(work.resolve("kept.http"), "GET http://h/\n");
        Path deleted = work.resolve("deleted.http");
        Path unmounted = work.resolve("gone-folder/elsewhere.http");
        for (Path f : List.of(kept, deleted, unmounted)) {
            store.save(f, stored(at(1, "http://h/", "x")));
        }
        assertEquals(1, store.pruneOrphans(System.currentTimeMillis()));
        assertTrue(Files.exists(store.fileFor(kept)));
        assertFalse(Files.exists(store.fileFor(deleted)), "deleted, renamed or moved: its folder is still there");
        assertTrue(Files.exists(store.fileFor(unmounted)), "its folder is gone too: maybe an unmounted drive");
        assertEquals(
                1,
                store.pruneOrphans(System.currentTimeMillis() + HttpResponseHistory.ORPHAN_GRACE_MILLIS + 1),
                "…until it has not been saved for the grace period");
    }
}
