package com.editora.vfs;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The prompt-excluded connect deadline (#486). */
class RemoteFileSystemsTimeoutTest {

    private static final long S = Duration.ofSeconds(1).toNanos();

    @Test
    void expiresWhenTheHandshakeItselfRanPastTheTimeout() {
        // 25s elapsed, no prompt shown → past a 20s connect timeout.
        assertTrue(RemoteFileSystems.handshakeExpired(25 * S, 0, Duration.ofSeconds(20)));
    }

    @Test
    void doesNotExpireWhileTheDialogAccountsForTheWait() {
        // 25s elapsed but 10s of it was the host-key dialog → only 15s of real handshake, under 20s.
        assertFalse(RemoteFileSystems.handshakeExpired(25 * S, 10 * S, Duration.ofSeconds(20)));
    }

    @Test
    void expiresOnceRealHandshakeTimeExceedsTheTimeoutEvenWithAPrompt() {
        // 35s elapsed, 10s of dialog → 25s of real handshake, past 20s: a genuinely stuck host still fails.
        assertTrue(RemoteFileSystems.handshakeExpired(35 * S, 10 * S, Duration.ofSeconds(20)));
    }

    @Test
    void aDeadHostStillTimesOutAtTheTimeoutWhenNobodyIsAsked() {
        // No prompt (promptNanos 0): behaves exactly like a plain deadline.
        assertFalse(RemoteFileSystems.handshakeExpired(19 * S, 0, Duration.ofSeconds(20)));
        assertTrue(RemoteFileSystems.handshakeExpired(20 * S, 0, Duration.ofSeconds(20)));
    }
}
