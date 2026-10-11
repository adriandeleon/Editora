package com.editora.test;

import com.editora.i18n.Messages;

/**
 * Bounds the text kept for one test: its captured output, failure message and stack trace were unbounded, so a
 * test that logs by the megabyte (or a run of thousands of such tests) pinned all of it until the next run.
 * Over {@link #MAX_CHARS} the head and the tail are kept — the assertion is usually at one end and the setup at
 * the other — with a note in between saying how much was cut. Pure.
 */
public final class OutputCap {

    /** About what one test's text may hold, the same bound as a console (200,000 characters). */
    public static final int MAX_CHARS = 200_000;

    static final int HEAD_CHARS = 150_000;
    static final int TAIL_CHARS = MAX_CHARS - HEAD_CHARS;

    private OutputCap() {}

    /** {@code text} unchanged while it fits, else its head and tail around a note of what was cut. */
    public static String cap(String text) {
        if (text == null || text.length() <= MAX_CHARS) {
            return text;
        }
        int cut = text.length() - HEAD_CHARS - TAIL_CHARS;
        return text.substring(0, HEAD_CHARS) + note(cut) + text.substring(text.length() - TAIL_CHARS);
    }

    /** The line that stands in for {@code cut} characters. */
    static String note(long cut) {
        return "\n" + Messages.tr("output.charactersCut", cut) + "\n";
    }

    /**
     * Collects a test's output as it streams in, holding at most the head and the last {@link #TAIL_CHARS}
     * of it — so the capture never grows past the bound even while the test is still printing.
     */
    public static final class Accumulator {

        private final StringBuilder head = new StringBuilder();
        private final StringBuilder tail = new StringBuilder();
        private long dropped;

        public void append(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            int from = 0;
            if (head.length() < HEAD_CHARS) {
                int take = Math.min(HEAD_CHARS - head.length(), text.length());
                head.append(text, 0, take);
                from = take;
            }
            if (from < text.length()) {
                tail.append(text, from, text.length());
                if (tail.length() > 2 * TAIL_CHARS) { // trim in batches, not per append
                    int excess = tail.length() - TAIL_CHARS;
                    tail.delete(0, excess);
                    dropped += excess;
                }
            }
        }

        public boolean isEmpty() {
            return head.isEmpty() && tail.isEmpty();
        }

        /** Everything appended while it fit, else the head, a note of what was cut, and the tail. */
        @Override
        public String toString() {
            if (tail.isEmpty()) {
                return head.toString();
            }
            long cut = dropped + Math.max(0, tail.length() - TAIL_CHARS);
            if (cut == 0) {
                return head + tail.toString();
            }
            return head + note(cut) + tail.substring(tail.length() - TAIL_CHARS);
        }
    }
}
