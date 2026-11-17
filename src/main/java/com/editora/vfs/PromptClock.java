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

    /** How far a test has moved {@link #now} on (TEST SEAM, see {@link #passForTest}); only ever grows. */
    private static final AtomicLong SKEW = new AtomicLong();

    /** The clock the login deadline and the answering time are measured on: {@code System.nanoTime}, or later. */
    static long now() {
        return System.nanoTime() + SKEW.get();
    }

    /**
     * TEST SEAM — moves {@link #now} on by {@code time}, as if that much had passed: a test plays a person who reads
     * a fingerprint or a code off a phone for longer than the deadline without waiting for it.
     */
    static void passForTest(java.time.Duration time) {
        SKEW.addAndGet(time.toNanos());
    }

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
        return s == 0 ? acc : acc + Math.max(0, now() - s);
    }

    /** Runs {@code question}, counting the time it takes as answering time. */
    <T> T timed(Supplier<T> question) {
        long t0 = now();
        start.set(t0);
        try {
            return question.get();
        } finally {
            accumulated.addAndGet(now() - t0);
            start.set(0);
        }
    }
}
