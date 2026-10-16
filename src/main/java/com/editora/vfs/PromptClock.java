package com.editora.vfs;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Time a person spent answering during the current connect — a host-key question, a password, a 2FA code — so
 * the login's deadline leaves it out (#486): a careful "compare this fingerprint" or a code read off a phone
 * must not time the handshake out, while a server that is merely stuck still does.
 *
 * <p>{@code accumulated} is finished questions; {@code start} marks one still on screen (0 when none), so the
 * deadline, checked while the dialog is up, counts the wait in progress too. The asking side (an SSH thread)
 * writes both; the login wait (the engine's serialized {@code sftp-connect} thread) reads them. Reset per
 * connect, which the engine serializes.
 */
final class PromptClock {

    private final AtomicLong accumulated = new AtomicLong();
    private final AtomicLong start = new AtomicLong();

    void reset() {
        accumulated.set(0);
        start.set(0);
    }

    /** Answering time so far this connect: finished questions plus one still on screen. */
    long elapsedNanos() {
        long s = start.get();
        long acc = accumulated.get();
        return s == 0 ? acc : acc + Math.max(0, System.nanoTime() - s);
    }

    /** Runs {@code question}, counting the time it takes as answering time. */
    <T> T timed(Supplier<T> question) {
        long t0 = System.nanoTime();
        start.set(t0);
        try {
            return question.get();
        } finally {
            accumulated.addAndGet(System.nanoTime() - t0);
            start.set(0);
        }
    }
}
