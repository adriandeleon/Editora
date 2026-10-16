package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The saved HTTP history after the third review: what is on disk. */
class HttpResponseHistoryRound3Test {

    private static HttpResponseHistory.Response entry(
            long at, long landedAt, String name, int index, String url, List<HttpResponseHistory.Header> headers) {
        return new HttpResponseHistory.Response(
                at,
                name,
                index,
                "GET",
                url,
                headers,
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
                false,
                "HTTP/1.1",
                "UTF-8",
                null,
                landedAt,
                null,
                null);
    }

    private static HttpResponseHistory.Stored stored(Path file, List<HttpResponseHistory.Response> rs) {
        HttpResponseHistory.Stored s = new HttpResponseHistory.Stored();
        s.file = file.toString();
        s.responses = new ArrayList<>(rs);
        return s;
    }

    @Test
    void moreCredentialHeadersAreMasked() {
        for (String name : List.of(
                "X-Authorization",
                "X-Forwarded-Authorization",
                "X-JWT-Assertion",
                "X-Goog-IAP-JWT-Assertion",
                "X-Amzn-Oidc-Data",
                "Cookie2",
                "X-Pass",
                "X-Pwd")) {
            assertTrue(HttpResponseHistory.isCredentialHeader(name), name);
        }
        assertFalse(HttpResponseHistory.isCredentialHeader("Accept"));
        assertFalse(HttpResponseHistory.isCredentialHeader("Idempotency-Key"));
    }

    @Test
    void aHeaderAnOlderBuildLeftInClearIsMaskedOnDiskWhenTheHistoryIsRead(@TempDir Path config, @TempDir Path work)
            throws Exception {
        HttpResponseHistory store = new HttpResponseHistory(config);
        Path file = work.resolve("old.http");
        Files.createDirectories(store.fileFor(file).getParent());
        Files.writeString(
                store.fileFor(file),
                "{\"schemaVersion\":1,\"file\":\"" + file.toString().replace("\\", "\\\\") + "\",\"responses\":["
                        + "{\"at\":1,\"method\":\"GET\",\"url\":\"http://h/\",\"status\":200,"
                        + "\"requestHeaders\":[{\"name\":\"X-Auth-Key\",\"value\":\"FAKE-REQ-KEY\"}],"
                        + "\"headers\":[{\"name\":\"X-Authorization\",\"value\":\"FAKE-RESP\"}]}]}");
        HttpResponseHistory.Stored back = store.load(file);
        assertEquals(
                HttpResponseHistory.MASK,
                back.responses.get(0).requestHeaders().get(0).value());
        String onDisk = Files.readString(store.fileFor(file));
        assertFalse(onDisk.contains("FAKE-REQ-KEY"), onDisk);
        assertFalse(onDisk.contains("FAKE-RESP"), onDisk);
    }

    @Test
    void aResponseThatLandedAfterAClearIsKeptThoughItStartedBefore(@TempDir Path config, @TempDir Path work) {
        HttpResponseHistory store = new HttpResponseHistory(config);
        Path file = work.resolve("c.http");
        HttpResponseHistory.Stored mine = stored(
                file,
                List.of(
                        entry(
                                100,
                                300,
                                "slow",
                                1,
                                "http://h/slow",
                                List.of()), // started before the Clear, landed after
                        entry(150, 160, "a", 0, "http://h/a", List.of()))); // shown before the Clear
        mine.clearedAt = 200;
        store.saveMerged(file, mine, Set.of());
        assertEquals(
                List.of("http://h/slow"),
                new HttpResponseHistory(config)
                        .load(file).responses.stream()
                                .map(HttpResponseHistory.Response::url)
                                .toList());
    }

    @Test
    void anUnnamedRequestWhoseUrlVariesIsOneRequestForRetention(@TempDir Path config, @TempDir Path work) {
        HttpResponseHistory store = new HttpResponseHistory(config);
        Path file = work.resolve("ids.http");
        List<HttpResponseHistory.Response> rs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rs.add(entry(10_000 - i, 10_000 - i, null, 0, "http://h/users/" + i, List.of()));
        }
        rs.add(entry(1, 1, null, 1, "http://h/health", List.of()));
        store.saveMerged(file, stored(file, rs), Set.of());
        List<String> kept = new HttpResponseHistory(config)
                .load(file).responses.stream()
                        .map(HttpResponseHistory.Response::url)
                        .toList();
        assertEquals(HttpResponseHistory.MAX_RESPONSES, kept.size());
        assertTrue(kept.contains("http://h/health"), "the only response of the other request stays: " + kept);
    }

    @Test
    void damagedEntriesAreKeptInACopyBeforeTheNextSaveDropsThem(@TempDir Path config, @TempDir Path work)
            throws Exception {
        HttpResponseHistory store = new HttpResponseHistory(config);
        Path file = work.resolve("d.http");
        Files.createDirectories(store.fileFor(file).getParent());
        String json = "{\"schemaVersion\":3,\"file\":\"x\",\"responses\":["
                + "{\"at\":1,\"method\":\"GET\",\"url\":\"http://h/1\",\"status\":200},"
                + "{\"at\":\"yesterday\",\"url\":\"http://h/bad\"}]}";
        Files.writeString(store.fileFor(file), json);
        HttpResponseHistory.Stored back = store.load(file);
        assertEquals(1, back.skipped);
        assertNotNull(back.damagedCopy);
        assertEquals(json, Files.readString(back.damagedCopy));
        store.save(file, back);
        assertFalse(Files.readString(store.fileFor(file)).contains("yesterday"));
    }

    @Test
    void aResponsesValueThatIsNotAListIsReportedAndKept(@TempDir Path config, @TempDir Path work) throws Exception {
        HttpResponseHistory store = new HttpResponseHistory(config);
        Path file = work.resolve("o.http");
        Files.createDirectories(store.fileFor(file).getParent());
        Files.writeString(store.fileFor(file), "{\"schemaVersion\":3,\"file\":\"x\",\"responses\":{\"a\":1}}");
        HttpResponseHistory.Stored back = store.load(file);
        assertTrue(back.skipped > 0, "said, not silently empty");
        assertNotNull(back.damagedCopy);
        assertTrue(Files.readString(back.damagedCopy).contains("{\"a\":1}"));
    }

    @Test
    void aMovedFolderTakesItsFilesHistoriesAlong(@TempDir Path config, @TempDir Path work) throws Exception {
        HttpResponseHistory store = new HttpResponseHistory(config);
        Path from = Files.createDirectories(work.resolve("api"));
        Path a = Files.writeString(from.resolve("a.http"), "GET http://h/\n");
        Path deep = Files.createDirectories(from.resolve("deep"));
        Path b = Files.writeString(deep.resolve("b.http"), "GET http://h/\n");
        Path outside = Files.writeString(work.resolve("other.http"), "GET http://h/\n");
        for (Path f : List.of(a, b, outside)) {
            store.save(f, stored(f, List.of(entry(1, 1, "r", 0, "http://h/" + f.getFileName(), List.of()))));
        }
        Path to = work.resolve("v2");
        Files.move(from, to);
        assertEquals(2, store.move(from, to));
        assertEquals(
                "http://h/a.http",
                store.load(to.resolve("a.http")).responses.get(0).url());
        assertEquals(
                "http://h/b.http",
                store.load(to.resolve("deep/b.http")).responses.get(0).url());
        assertFalse(Files.exists(store.fileFor(a)));
        assertTrue(Files.exists(store.fileFor(outside)));
        assertEquals(
                to.resolve("a.http").toAbsolutePath().normalize().toString(), store.load(to.resolve("a.http")).file);
    }

    @Test
    void quittingWaitsForTheQueuedHistoryWork(@TempDir Path config) throws Exception {
        SharedConfig shared = new SharedConfig(config, false);
        java.util.concurrent.atomic.AtomicBoolean waited = new java.util.concurrent.atomic.AtomicBoolean();
        shared.httpHistory().setPendingWork(() -> waited.set(true));
        shared.shutdown();
        assertTrue(waited.get(), "the windows' queued saves are let through before the writer stops");
    }
}
