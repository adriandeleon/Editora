package com.editora.test;

import java.net.InetAddress;
import java.net.ServerSocket;

import com.editora.test.JdwpAttachRetry.Decision;
import com.editora.test.JdwpAttachRetry.Probe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attaching to a Maven test JVM on a port chosen up front (review D1): the pure step per probe answer, and the
 * probe itself against a real loopback socket.
 */
class JdwpAttachRetryTest {

    @Test
    void theForkListeningIsAttachedOnceAndAgainAfterItWentAway() {
        long now = 1_000;
        long deadline = 61_000;
        // Nothing listens yet: keep polling (Maven is still compiling, or Surefire has not forked).
        assertEquals(Decision.KEEP_WAITING, JdwpAttachRetry.decide(Probe.REFUSED, false, false, false, now, deadline));
        assertEquals(Decision.KEEP_WAITING, JdwpAttachRetry.decide(Probe.ERROR, false, false, false, now, deadline));
        // The fork listens: attach, once — the port stays open until the adapter's own connect lands.
        assertEquals(Decision.ATTACH, JdwpAttachRetry.decide(Probe.ACCEPTING, false, false, false, now, deadline));
        assertEquals(Decision.KEEP_WAITING, JdwpAttachRetry.decide(Probe.ACCEPTING, true, false, true, now, deadline));
        // The attached JVM stopped listening (it ran and exited): a further fork may bind the port next.
        assertEquals(Decision.PORT_CLOSED, JdwpAttachRetry.decide(Probe.REFUSED, true, false, true, now, deadline));
        assertEquals(Decision.ATTACH, JdwpAttachRetry.decide(Probe.ACCEPTING, false, false, true, now, deadline));
    }

    @Test
    void aPortThatRefusesDuringTheSessionIsNotClosedNorIsTheAgentListeningAgainANewFork() {
        long now = 1_000;
        long deadline = 61_000;
        // The agent stops listening once the adapter connects: the port refuses while the session runs.
        assertEquals(Decision.KEEP_WAITING, JdwpAttachRetry.decide(Probe.REFUSED, true, true, true, now, deadline));
        assertEquals(Decision.KEEP_WAITING, JdwpAttachRetry.decide(Probe.ERROR, true, true, true, now, deadline));
        // Debug ▸ Stop disconnects: the same agent listens again while its JVM runs on to the end — still the
        // attached JVM, not a next fork, so nothing is attached until it has exited (refused, no session).
        assertEquals(Decision.KEEP_WAITING, JdwpAttachRetry.decide(Probe.ACCEPTING, true, false, true, now, deadline));
        assertEquals(Decision.PORT_CLOSED, JdwpAttachRetry.decide(Probe.REFUSED, true, false, true, now, deadline));
    }

    @Test
    void theFirstAttachHasADeadlineLaterOnesDoNot() {
        assertEquals(
                Decision.TIMED_OUT,
                JdwpAttachRetry.decide(Probe.REFUSED, false, false, false, 61_000, 61_000),
                "no JVM ever listened: say so instead of waiting forever");
        assertEquals(Decision.KEEP_WAITING, JdwpAttachRetry.decide(Probe.REFUSED, false, false, false, 60_999, 61_000));
        assertEquals(
                Decision.KEEP_WAITING,
                JdwpAttachRetry.decide(Probe.REFUSED, false, false, true, 999_999, 61_000),
                "between two forks of the same run the deadline no longer applies");
        assertEquals(
                Decision.ATTACH,
                JdwpAttachRetry.decide(Probe.ACCEPTING, false, false, false, 999_999, 61_000),
                "a late listener is still attached: the deadline only ends the wait for a refused port");
    }

    @Test
    void theProbeTellsAListeningPortFromAClosedOneWithoutTalkingJdwp() throws Exception {
        int port = JdwpAttachRetry.freeLoopbackPort();
        assertTrue(port > 0 && port <= 65535);
        assertEquals(Probe.REFUSED, JdwpAttachRetry.probe("127.0.0.1", port, 200), "released: nothing listens");
        try (ServerSocket listener = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(Probe.ACCEPTING, JdwpAttachRetry.probe("127.0.0.1", port, 200));
            // The probe connects and closes without sending anything: a JDWP agent that completed the handshake
            // and then lost the debugger would resume the suspended JVM.
            try (var accepted = listener.accept()) {
                accepted.setSoTimeout(2_000);
                assertEquals(-1, accepted.getInputStream().read(), "closed again at once, no bytes sent");
            }
        }
        assertEquals(Probe.REFUSED, JdwpAttachRetry.probe("127.0.0.1", port, 200), "closed again");
        assertNotEquals(Probe.ACCEPTING, JdwpAttachRetry.probe("no-such-host.invalid", port, 200));
    }
}
