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
}
