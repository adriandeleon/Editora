package com.editora.test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Attaching to a test JVM that Maven forks suspended on a port Editora chose (review D1). Surefire 3 reads the
 * fork's stdout as its event channel and only surfaces the JDWP "Listening for transport…" banner after the
 * suspended JVM has run — never, with {@code suspend=y} — so the banner cannot tell Editora when (or where) to
 * attach. Instead the port is picked up front ({@link #freeLoopbackPort}) and polled ({@link #probe}) every
 * {@link #INTERVAL_MS} until the fork listens on it; {@link #decide} is the pure step taken on each answer (on
 * the FX thread, where the session state is known). Gradle still prints its banner through its own logger and
 * keeps the banner watcher.
 *
 * <p>The probe is a plain TCP connect that is closed again at once, without the JDWP handshake: a debugger that
 * completes the handshake and then disconnects makes the agent <em>resume</em> the suspended JVM, which would
 * run the test before the real attach. The agent treats the closed probe as a failed attach and listens again
 * (it prints one "Debugger failed to attach: handshake failed" line, which Surefire shows after the test).
 *
 * <p>Limits: the port is chosen by Editora, so a second fork of the same run ({@code forkCount>1},
 * {@code reuseForks=false}, reruns) can only bind it after the first JVM has exited — the poller then sees the
 * port close and attaches again when it opens; two forks alive at once make the second fail to bind. A foreign
 * process that grabs the port between the pick and the fork's bind is accepted by the probe and refused by
 * the adapter's handshake; the status then reports that attach error. A debugger that disconnects from the
 * fork without ending the build (Debug ▸ Stop ends both) leaves the agent listening again on the same port
 * while the JVM runs on: the loop keeps off it while a session is live or until the port has refused, but
 * a probe that lands in the instant between the disconnect and the agent's re-listen takes the port for free
 * and the next listener for a new fork.
 */
public final class JdwpAttachRetry {

    private JdwpAttachRetry() {}

    /** How often the port is probed. */
    public static final long INTERVAL_MS = 250;

    /** How long to wait for the fork to listen, counted from the launch and again from Surefire's banner. */
    public static final long DEADLINE_MS = 60_000;

    /** The connect timeout of one probe (loopback: refused or accepted at once). */
    public static final int PROBE_TIMEOUT_MS = 200;

    /** What one probe of the port found. */
    public enum Probe {
        /** Something listens: connect succeeded. */
        ACCEPTING,
        /** Nothing listens (connection refused). */
        REFUSED,
        /** Any other failure (timeout, unresolvable host): treated like refused. */
        ERROR
    }

    /** What the poller does with a probe answer. */
    public enum Decision {
        /** The fork listens and no session is attached: attach now. */
        ATTACH,
        /** Keep polling. */
        KEEP_WAITING,
        /** The attached JVM is gone (the port refuses again): the next fork of the run may bind it. */
        PORT_CLOSED,
        /** No JVM ever listened before the deadline: give up and say so. */
        TIMED_OUT
    }

    /** A free TCP port on the loopback interface: bound and released, so a fork can bind it a moment later. */
    public static int freeLoopbackPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    /** One probe of {@code host:port}: a connect closed again at once, with no JDWP handshake (see above). */
    public static Probe probe(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return Probe.ACCEPTING;
        } catch (ConnectException e) {
            return Probe.REFUSED;
        } catch (IOException | RuntimeException e) {
            return Probe.ERROR;
        }
    }

    /**
     * Pure: the step after a probe. {@code attached} is whether the poller has already asked for an attach on
     * the port's current listener; {@code sessionActive} whether a debug session is live or starting — a port
     * that refuses while it is has only stopped listening for the attached debugger, and an agent whose
     * debugger disconnects listens on the same port <em>again</em> while the JVM runs on, so the port counts as
     * closed only once it refuses with no session; {@code everAttached} whether any attach happened in this run
     * (after one the deadline no longer applies — the run is in Surefire's hands); {@code nowMs}/{@code
     * deadlineMs} bound the first attach.
     */
    public static Decision decide(
            Probe probe, boolean attached, boolean sessionActive, boolean everAttached, long nowMs, long deadlineMs) {
        if (probe == Probe.ACCEPTING) {
            return attached ? Decision.KEEP_WAITING : Decision.ATTACH;
        }
        if (attached) {
            // Refused with the session over: the attached JVM exited (or died), the port is free for a next fork.
            return sessionActive ? Decision.KEEP_WAITING : Decision.PORT_CLOSED;
        }
        if (!everAttached && nowMs >= deadlineMs) {
            return Decision.TIMED_OUT;
        }
        return Decision.KEEP_WAITING;
    }
}
