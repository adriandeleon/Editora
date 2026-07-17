package com.editora.vfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host-key verification, driven against a real in-process SSH server — including a real man-in-the-middle: a
 * second server, on the same address, holding a different host key.
 *
 * <p>Editora used to pass {@code AcceptAllServerKeyVerifier}, so every one of these connections succeeded and
 * the MITM was invisible. SSH's whole security model is host-key verification; without it the channel is
 * unauthenticated, and with password auth the impostor simply receives the password.
 */
class HostKeyVerificationTest {

    /** Starts an SSH server on a free port with a fresh, self-generated host key. */
    private static SshServer startServer(Path hostKey) throws Exception {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0); // any free port
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        sshd.setPasswordAuthenticator(AcceptAllPasswordAuthenticator.INSTANCE);
        sshd.start();
        return sshd;
    }

    /** A client wired exactly as {@link RemoteFileSystems} wires it: known_hosts + a TOFU delegate. */
    private static SshClient startClient(Path knownHosts, ServerKeyVerifier onUnknownHost) {
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(new KnownHostsServerKeyVerifier(onUnknownHost, knownHosts));
        client.start();
        return client;
    }

    private static void connect(SshClient client, int port) throws Exception {
        try (ClientSession s = client.connect("tester", "127.0.0.1", port)
                .verify(java.time.Duration.ofSeconds(10))
                .getSession()) {
            s.addPasswordIdentity("pw");
            s.auth().verify(java.time.Duration.ofSeconds(10));
        }
    }

    @Test
    void anUnknownHostIsAskedAboutOnceThenRemembered(@TempDir Path dir) throws Exception {
        Path knownHosts = dir.resolve("known_hosts");
        AtomicInteger asked = new AtomicInteger();
        SshServer server = startServer(dir.resolve("hostkey.ser"));
        SshClient client = startClient(knownHosts, (session, addr, key) -> {
            asked.incrementAndGet();
            return true; // the user clicks "Connect"
        });
        try {
            connect(client, server.getPort());
            assertEquals(1, asked.get(), "a host we've never seen must be put to the user");
            assertTrue(Files.exists(knownHosts), "accepting must record the key, like ssh does");

            connect(client, server.getPort()); // same host, same key
            assertEquals(1, asked.get(), "a known host with a matching key must not ask again");
        } finally {
            client.stop();
            server.stop(true);
        }
    }

    @Test
    void decliningAnUnknownHostRefusesTheConnectionAndRemembersNothing(@TempDir Path dir) throws Exception {
        Path knownHosts = dir.resolve("known_hosts");
        SshServer server = startServer(dir.resolve("hostkey.ser"));
        SshClient client = startClient(knownHosts, (session, addr, key) -> false); // the user clicks "Cancel"
        try {
            assertThrows(Exception.class, () -> connect(client, server.getPort()), "declining must not connect");
            assertFalse(
                    Files.exists(knownHosts) && Files.readString(knownHosts).contains("ssh-"),
                    "a declined key must not be recorded");
        } finally {
            client.stop();
            server.stop(true);
        }
    }

    /**
     * The attack the advisory describes: the user has connected before, and someone else now answers on that
     * address with their own key. The connection must fail, and the user must NOT be asked — being asked is
     * how a MITM gets waved through by a click.
     */
    @Test
    void aKnownHostPresentingADifferentKeyIsRefusedWithoutAsking(@TempDir Path dir) throws Exception {
        Path knownHosts = dir.resolve("known_hosts");
        AtomicInteger asked = new AtomicInteger();

        // First, a genuine connection to the real server, which the user accepts.
        SshServer real = startServer(dir.resolve("real-hostkey.ser"));
        int port = real.getPort();
        SshClient client = startClient(knownHosts, (session, addr, key) -> {
            asked.incrementAndGet();
            return true;
        });
        try {
            connect(client, port);
            assertEquals(1, asked.get());
            real.stop(true);

            // Now an impostor answers on the same address, holding a different host key.
            SshServer impostor = SshServer.setUpDefaultServer();
            impostor.setHost("127.0.0.1");
            impostor.setPort(port);
            impostor.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("impostor-hostkey.ser")));
            impostor.setPasswordAuthenticator(AcceptAllPasswordAuthenticator.INSTANCE);
            impostor.start();
            try {
                assertThrows(
                        Exception.class,
                        () -> connect(client, port),
                        "a host key that changed under us is a man-in-the-middle — the connection must fail");
                assertEquals(1, asked.get(), "and the user must not be given a button that waves it through");
            } finally {
                impostor.stop(true);
            }
        } finally {
            client.stop();
        }
    }

    /** With no UI to ask with, an unknown host is refused rather than silently trusted. */
    @Test
    void withoutAPromptAnUnknownHostIsRefused(@TempDir Path dir) throws Exception {
        SshServer server = startServer(dir.resolve("hostkey.ser"));
        SshClient client = startClient(dir.resolve("known_hosts"), unknownHostVerifierWithNoPrompt());
        try {
            assertThrows(Exception.class, () -> connect(client, server.getPort()));
        } finally {
            client.stop();
            server.stop(true);
        }
    }

    /** Mirrors {@code RemoteFileSystems.unknownHostVerifier(null)}. */
    private static ServerKeyVerifier unknownHostVerifierWithNoPrompt() {
        return (session, addr, key) -> false;
    }

    /** The prompt is shown a real key type + fingerprint — what the user is actually asked to trust. */
    @Test
    void thePromptDescribesTheKeyTheWayUsersRecogniseIt(@TempDir Path dir) throws Exception {
        Path knownHosts = dir.resolve("known_hosts");
        SshServer server = startServer(dir.resolve("hostkey.ser"));
        StringBuilder seen = new StringBuilder();
        SshClient client = startClient(knownHosts, (session, addr, key) -> {
            seen.append(KeyUtils.getKeyType(key)).append(' ').append(KeyUtils.getFingerPrint(key));
            return true;
        });
        try {
            connect(client, server.getPort());
        } finally {
            client.stop();
            server.stop(true);
        }
        String shown = seen.toString();
        assertTrue(shown.startsWith("ssh-") || shown.startsWith("ecdsa-"), "a real key type: " + shown);
        assertTrue(shown.contains("SHA256:"), "an OpenSSH-style fingerprint the user can compare: " + shown);
    }
}
