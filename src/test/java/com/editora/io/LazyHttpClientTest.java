package com.editora.io;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyHttpClientTest {

    @Test
    void nothingIsBuiltUntilTheFirstUse() {
        AtomicInteger built = new AtomicInteger();
        try (LazyHttpClient lazy = new LazyHttpClient(() -> {
            built.incrementAndGet();
            return HttpClient.newHttpClient();
        })) {
            assertFalse(lazy.isBuilt());
            assertEquals(0, built.get());

            HttpClient first = lazy.get();
            assertTrue(lazy.isBuilt());
            assertSame(first, lazy.get(), "one client for every later request");
            assertEquals(1, built.get());
        }
    }

    @Test
    void closeReleasesTheClientAndIsHarmlessWhenNoneWasBuilt() throws InterruptedException {
        LazyHttpClient lazy = LazyHttpClient.following(Duration.ofSeconds(1));
        lazy.close();
        assertFalse(lazy.isBuilt());

        HttpClient first = lazy.get();
        assertEquals(HttpClient.Redirect.NORMAL, first.followRedirects());
        lazy.close();
        assertFalse(lazy.isBuilt());
        assertTrue(first.awaitTermination(Duration.ofSeconds(10)), "the closed client winds down");

        HttpClient second = lazy.get();
        assertNotSame(first, second);
        lazy.close();
    }
}
