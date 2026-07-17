package com.editora.vfs;

import java.nio.file.Path;
import java.time.Duration;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A password used for SFTP must not linger on the session after the handshake. MINA's client API is
 * String-only, so a String copy of the password is unavoidable at the boundary — but {@code addPasswordIdentity}
 * keeps it on the session's identity list for the whole connected session (potentially hours). Since a password
 * is only needed for the initial handshake, {@link RemoteFileSystems#authenticate} removes it as soon as auth
 * finishes, shrinking that String's lifetime to the handshake.
 *
 * <p>Driven against a real in-process SSH server, since the property is observable only on a real session
 * (the password-identity list is read via {@code ClientSession.passwordIteratorOf}).
 */
class PasswordIdentityLifetimeTest {

    private static SshServer startServer(Path hostKey) throws Exception {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        sshd.setPasswordAuthenticator(AcceptAllPasswordAuthenticator.INSTANCE);
        sshd.start();
        return sshd;
    }

    private static RemoteConnection passwordConnection(int port) {
        return new RemoteConnection("127.0.0.1", port, "tester", RemoteConnection.AuthMethod.PASSWORD, "", "probe", "");
    }

    private static boolean sessionHoldsAPassword(ClientSession session) throws Exception {
        return ClientSession.passwordIteratorOf(session).hasNext();
    }

    @Test
    void thePasswordIsRemovedFromTheSessionAfterAuthentication(@TempDir Path dir) throws Exception {
        SshServer server = startServer(dir.resolve("hostkey.ser"));
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE); // host-key isn't what this test checks
        client.start();
        try (ClientSession session = client.connect("tester", "127.0.0.1", server.getPort())
                .verify(Duration.ofSeconds(10))
                .getSession()) {

            RemoteFileSystems.authenticate(
                    session, passwordConnection(server.getPort()), "hunter2".toCharArray(), Duration.ofSeconds(10));

            assertTrue(session.isAuthenticated(), "precondition: auth succeeded, so removal didn't break it");
            assertFalse(
                    sessionHoldsAPassword(session),
                    "the password must not linger on the session for its whole lifetime after the handshake");
        } finally {
            client.stop();
            server.stop(true);
        }
    }

    @Test
    void aFailedPasswordAuthAlsoLeavesNoPasswordBehind(@TempDir Path dir) throws Exception {
        SshServer server = startServer(dir.resolve("hostkey.ser"));
        server.setPasswordAuthenticator((u, p, s) -> false); // reject every password
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        try (ClientSession session = client.connect("tester", "127.0.0.1", server.getPort())
                .verify(Duration.ofSeconds(10))
                .getSession()) {

            try {
                RemoteFileSystems.authenticate(
                        session, passwordConnection(server.getPort()), "wrong".toCharArray(), Duration.ofSeconds(10));
            } catch (Exception expected) {
                // auth fails — that's the point of this case
            }

            assertFalse(
                    sessionHoldsAPassword(session),
                    "even a rejected password must be cleaned up (the finally runs on failure too)");
        } finally {
            client.stop();
            server.stop(true);
        }
    }
}
