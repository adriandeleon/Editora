package com.editora.terminal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Takes the shell-integration sequences out of a terminal's output and turns them into events, in stream order:
 * OSC 7 (the shell's working directory, as a {@code file://host/path} URL) and OSC 133 (prompt marks:
 * {@code A} prompt starts, {@code B} command starts, {@code C} its output starts, {@code D;status} it finished).
 *
 * <p>JediTerm stubs the first and does not know the second, and warns about each, at every prompt. Every other
 * escape sequence is passed on exactly as it came, split across reads or not.
 *
 * <p>An event is delivered only once the text before it has been read, so a reader that processes what it got
 * before reading again (the emulator does) sees the cursor where the mark belongs.
 */
final class OscTap {

    /** What the tap found. Called on the reading thread. */
    interface Listener {
        /** OSC 7: the shell's working directory, as the shell sent it (a {@code file:} URL, percent-encoded). */
        void cwd(String url);

        /** OSC 133: {@code kind} is A, B, C or D; {@code params} what followed it (D's exit status), or "". */
        void promptMark(char kind, String params);
    }

    /** An event found between pieces of text, handed to the listener {@link #next} is given. */
    private interface Event {
        void deliver(Listener listener);
    }

    /** Longest OSC kept while deciding whether it is ours; a longer one is passed on as it is. */
    static final int MAX_SEQUENCE = 4096;

    private static final char ESC = 0x1b;
    private static final char BEL = 0x07;

    private enum State {
        TEXT,
        ESCAPE,
        OSC,
        OSC_ESCAPE
    }

    /** Text not yet handed out, and the events between its pieces, in order. */
    private final Deque<Object> queue = new ArrayDeque<>();

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder sequence = new StringBuilder();
    private State state = State.TEXT;

    /** Feeds what was read; afterwards {@link #next} hands out the text and events it held. */
    void feed(char[] chars, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            char c = chars[i];
            switch (state) {
                case TEXT -> {
                    if (c == ESC) {
                        state = State.ESCAPE;
                    } else {
                        text.append(c);
                    }
                }
                case ESCAPE -> {
                    if (c == ']') {
                        sequence.setLength(0);
                        state = State.OSC;
                    } else if (c == ESC) {
                        text.append(ESC); // the first ESC led nowhere; the second may start a sequence
                    } else {
                        text.append(ESC).append(c);
                        state = State.TEXT;
                    }
                }
                case OSC -> {
                    if (c == BEL) {
                        finish("\u0007");
                    } else if (c == ESC) {
                        state = State.OSC_ESCAPE;
                    } else {
                        sequence.append(c);
                        if (sequence.length() > MAX_SEQUENCE) {
                            passOn("");
                        }
                    }
                }
                case OSC_ESCAPE -> {
                    if (c == '\\') {
                        finish("\u001b\\");
                    } else {
                        // Not a string terminator: an OSC cut short by a new escape. Pass it on; reread c.
                        passOn("");
                        state = State.ESCAPE;
                        i--;
                    }
                }
            }
        }
        flushText();
    }

    /**
     * Hands out an ESC that ended what was fed, when nothing follows yet: it may start a sequence, but holding it
     * until more output arrives kept a program's last ESC (vim leaving a mode, a lone Escape echoed) from the screen.
     */
    void releaseTrailingEscape() {
        if (state == State.ESCAPE) {
            text.append(ESC);
            state = State.TEXT;
            flushText();
        }
    }

    /** True when there is something to hand out. */
    boolean hasNext() {
        return !queue.isEmpty();
    }

    /**
     * The next piece: delivers the event at the head of the queue to {@code listener} and returns 0, or copies up to
     * {@code length} chars of text and returns how many.
     */
    int next(char[] into, int offset, int length, Listener listener) {
        Object head = queue.peekFirst();
        if (head instanceof Event event) {
            queue.pollFirst();
            event.deliver(listener);
            return 0;
        }
        StringBuilder chunk = (StringBuilder) head;
        int n = Math.min(length, chunk.length());
        chunk.getChars(0, n, into, offset);
        chunk.delete(0, n);
        if (chunk.isEmpty()) {
            queue.pollFirst();
        }
        return n;
    }

    /** An OSC ended with {@code terminator}: ours becomes an event, anything else goes on as it came. */
    private void finish(String terminator) {
        String body = sequence.toString();
        state = State.TEXT;
        int semi = body.indexOf(';');
        String number = semi < 0 ? body : body.substring(0, semi);
        String rest = semi < 0 ? "" : body.substring(semi + 1);
        if (number.equals("7")) {
            flushText();
            queue.addLast((Event) listener -> listener.cwd(rest));
        } else if (number.equals("133") && !rest.isEmpty()) {
            flushText();
            int more = rest.indexOf(';');
            char kind = rest.charAt(0);
            String params = more < 0 ? "" : rest.substring(more + 1);
            queue.addLast((Event) listener -> listener.promptMark(kind, params));
        } else {
            text.append(ESC).append(']').append(body).append(terminator);
        }
    }

    /** Gives up on an OSC: what was gathered goes on as text. */
    private void passOn(String tail) {
        text.append(ESC).append(']').append(sequence).append(tail);
        sequence.setLength(0);
        state = State.TEXT;
    }

    private void flushText() {
        if (!text.isEmpty()) {
            queue.addLast(new StringBuilder(text));
            text.setLength(0);
        }
    }
}
