package com.editora.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link SseParser}: event assembly from SSE lines. */
class SseParserTest {

    @Test
    void assemblesEventOnBlankLine() {
        SseParser p = new SseParser();
        assertNull(p.feed("event: content_block_delta"));
        assertNull(p.feed("data: {\"type\":\"content_block_delta\"}"));
        SseParser.Event e = p.feed("");
        assertEquals("content_block_delta", e.name());
        assertEquals("{\"type\":\"content_block_delta\"}", e.data());
    }

    @Test
    void joinsMultipleDataLines() {
        SseParser p = new SseParser();
        p.feed("data: line1");
        p.feed("data: line2");
        SseParser.Event e = p.feed("");
        assertEquals("line1\nline2", e.data());
    }

    @Test
    void ignoresCommentsAndStrayBlankLines() {
        SseParser p = new SseParser();
        assertNull(p.feed(""));
        assertNull(p.feed(": keep-alive"));
        assertNull(p.feed(""));
        p.feed("data: x");
        assertEquals("x", p.feed("").data());
    }

    @Test
    void resetsBetweenEvents() {
        SseParser p = new SseParser();
        p.feed("event: ping");
        p.feed("data: {}");
        p.feed("");
        p.feed("data: second");
        SseParser.Event e = p.feed("");
        assertEquals("", e.name()); // event name does not leak from the previous event
        assertEquals("second", e.data());
    }
}
