package com.editora.vfs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link SshExecTarget#leastLead}: how far ahead the host's clock is at least, from one {@code date +%s}. */
class HostClockTest {

    @Test
    void theHostsSecondBeganNoLaterThanTheAnswerArrived() {
        // Host said 1000 s; the answer arrived at local 1_000_400 ms: the host is at least 400 ms behind.
        assertEquals(-400L, SshExecTarget.leastLead("1000\n", 1_000_400));
        // A host an hour ahead.
        assertEquals(3_600_000L - 250, SshExecTarget.leastLead("4600", 1_000_250));
    }

    @Test
    void anUnreadableAnswerIsNoMeasurement() {
        assertNull(SshExecTarget.leastLead("", 1));
        assertNull(SshExecTarget.leastLead("date: not found", 1));
    }
}
