package com.editora.vfs;

import java.time.Duration;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.core.CoreModuleProperties;

/**
 * The one {@link SshClient} every window's {@link RemoteFileSystems} connects through.
 *
 * <p>One client, not one per window, so that a logged-in session can move between windows: a session lives on
 * the client that made it, and a per-window client stopped with its window, taking every session on it along.
 * That is why a connect from one window used to log in a second time for the project's own window — once with
 * a one-time 2FA code, the second login could not succeed.
 *
 * <p>What used to differ per client still differs per window: the client's server-key check asks the engine
 * that owns the session ({@link #ENGINE}), which consults its own {@code known_hosts} and its own window's
 * prompt. The client starts with the first engine and stops when the last one shuts down.
 */
final class SshClients {

    /**
     * The engine a session belongs to. Set on the connect's context, so it is known from the session's first
     * packet (the host-key check runs during the key exchange, before the connect call returns), and on the
     * session itself once another engine adopts it.
     */
    static final AttributeRepository.AttributeKey<RemoteFileSystems> ENGINE = new AttributeRepository.AttributeKey<>();

    private static final Duration HEARTBEAT = Duration.ofSeconds(30);

    private static SshClient client;
    private static int users;

    private SshClients() {}

    /** The shared client, started if this is its first user; pair with {@link #release()}. */
    static synchronized SshClient acquire() {
        if (client == null) {
            SshClient c = SshClient.setUpDefaultClient();
            // Verify the server's host key against the owning engine's known_hosts (see RemoteFileSystems).
            // A session no engine claims is refused: there is nobody to ask and no file to check against.
            c.setServerKeyVerifier((session, address, key) -> {
                RemoteFileSystems engine = engineOf(session);
                return engine != null && engine.verifyServerKey(session, address, key);
            });
            RemoteFileSystems.keepAlive(c, HEARTBEAT);
            // A language server's messages are small and wait for each other: never hold one back to fill a packet.
            CoreModuleProperties.TCP_NODELAY.set(c, true);
            c.start();
            client = c;
        }
        users++;
        return client;
    }

    /** One user is done with the client; the last one stops it, ending any session still open on it. */
    static synchronized void release() {
        if (users > 0 && --users == 0 && client != null) {
            client.stop();
            client = null;
        }
    }

    /** The engine that owns {@code session}: the one that adopted it, else the one that connected it. */
    static RemoteFileSystems engineOf(Session session) {
        if (session == null) {
            return null;
        }
        RemoteFileSystems adopted = session.getAttribute(ENGINE);
        if (adopted != null) {
            return adopted;
        }
        AttributeRepository context = session instanceof ClientSession cs ? cs.getConnectionContext() : null;
        return context == null ? null : context.getAttribute(ENGINE);
    }
}
