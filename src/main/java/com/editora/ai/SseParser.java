package com.editora.ai;

/**
 * A tiny incremental parser for <em>Server-Sent Events</em> (the Anthropic Messages API's streaming
 * transport): feed it lines, it emits an {@link Event} whenever a blank line completes one. Pure and
 * stateful-per-instance (no I/O), unit-tested; {@link AiClient} drives it from the response line stream.
 */
public final class SseParser {

    /** One complete SSE event: the {@code event:} name (may be empty) + the joined {@code data:} payload. */
    public record Event(String name, String data) {}

    private String eventName = "";
    private final StringBuilder data = new StringBuilder();

    /** Feeds one line; returns the completed event when {@code line} is the blank separator, else null. */
    public Event feed(String line) {
        if (line == null || line.isEmpty()) {
            if (data.length() == 0 && eventName.isEmpty()) {
                return null; // stray blank line between events
            }
            Event e = new Event(eventName, data.toString());
            eventName = "";
            data.setLength(0);
            return e;
        }
        if (line.startsWith(":")) {
            return null; // comment/keep-alive
        }
        if (line.startsWith("event:")) {
            eventName = line.substring("event:".length()).strip();
        } else if (line.startsWith("data:")) {
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(line.substring("data:".length()).strip());
        }
        return null;
    }
}
