package com.editora.http;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Header preparation for the JDK client: trim what it would reject over whitespace, surface what it can't send. */
class HttpHeadersTest {

    private static HttpHeaders.Partition partition(String name, String value) {
        return HttpHeaders.partition(List.<String[]>of(new String[] {name, value}));
    }

    @Test
    void aTrailingNewlineFromAPasteIsTrimmedSoTheHeaderIsStillSent() {
        // The reported repro: a token copied out of a terminal / wrapped JSON carries a trailing CR/LF. The
        // JDK rejects the value outright, so the header used to be dropped and the request went out
        // unauthenticated. It is now trimmed and sent.
        HttpHeaders.Partition p = partition("Authorization", "Bearer sk-abc123\r\n");
        assertTrue(p.warnings().isEmpty(), "a trailing newline is fixable, not a warning");
        assertEquals(1, p.sendable().size());
        assertEquals("Authorization", p.sendable().get(0)[0]);
        assertEquals("Bearer sk-abc123", p.sendable().get(0)[1], "trimmed to the value the user meant");
    }

    @Test
    void aCleanHeaderPassesThroughUnchanged() {
        HttpHeaders.Partition p = partition("Authorization", "Bearer sk-abc123");
        assertTrue(p.warnings().isEmpty());
        assertEquals("Bearer sk-abc123", p.sendable().get(0)[1]);
    }

    @Test
    void anEmbeddedControlCharacterIsSurfacedNotSilentlyDropped() {
        // A CR/LF in the MIDDLE (a header-injection attempt) can't be trimmed away and must not be sent —
        // but the user must be told, not left with a mystery 401.
        HttpHeaders.Partition p = partition("Authorization", "Bearer a\r\nX-Injected: evil");
        assertTrue(p.sendable().isEmpty(), "the header can't be sent");
        assertEquals(1, p.warnings().size());
        assertTrue(p.warnings().get(0).contains("Authorization"), p.warnings().get(0));
        assertTrue(p.warnings().get(0).toLowerCase(java.util.Locale.ROOT).contains("control character"));
    }

    @Test
    void aRestrictedHeaderNameIsSurfaced() {
        HttpHeaders.Partition p = partition("Host", "evil.example.com");
        assertTrue(p.sendable().isEmpty());
        assertEquals(1, p.warnings().size());
        assertTrue(p.warnings().get(0).contains("Host"));
    }

    @Test
    void goodAndBadHeadersAreSeparated() {
        HttpHeaders.Partition p = HttpHeaders.partition(List.<String[]>of(
                new String[] {"Authorization", "Bearer ok\r\n"}, // fixable → sent
                new String[] {"X-Custom", "fine"}, // fine → sent
                new String[] {"Host", "nope"})); // restricted → warned
        assertEquals(2, p.sendable().size());
        assertEquals(1, p.warnings().size());
        assertEquals("Bearer ok", p.sendable().get(0)[1]);
    }

    @Test
    void nullValueBecomesEmptyNotACrash() {
        HttpHeaders.Partition p = partition("X-Empty", null);
        assertEquals("", p.sendable().get(0)[1]);
        assertTrue(p.warnings().isEmpty());
    }
}
