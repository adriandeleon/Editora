package com.editora.vfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoteFileSystems} driven against a real in-process SFTP server — and against a real impostor: a
 * second server on the same address holding a different host key.
 *
 * <p>This is the wiring test. {@code HostKeyVerificationTest} pins what MINA's verifier does; this pins that
 * Editora actually uses it. Before the fix Editora passed {@code AcceptAllServerKeyVerifier}, so the impostor
 * was accepted in silence and — with password auth — was simply handed the user's password.
 */
@Tag("fx")
class RemoteFileSystemsHostKeyFxTest {

    @BeforeAll
    static void boot() {
        // connect() delivers its Result via Platform.runLater, so the toolkit must be up. (The ui package's
        // FxTestSupport isn't visible from here; this needs nothing but a live FX thread.)
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another FX test booted the toolkit in this JVM
        }
        javafx.application.Platform.setImplicitExit(false);
    }

    private RemoteFileSystems fs;

    @AfterEach
    void tearDown() {
        if (fs != null) {
            fs.shutdown(); // also un-pins the Vfs statics this installs
            fs = null;
        }
    }

    private static SshServer sftpServer(Path hostKey, int port) throws Exception {
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(port);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        sshd.setPasswordAuthenticator(AcceptAllPasswordAuthenticator.INSTANCE);
        sshd.setSubsystemFactories(java.util.List.of(new SftpSubsystemFactory()));
        sshd.start();
        return sshd;
    }

    private static RemoteConnection connection(int port) {
        return new RemoteConnection("127.0.0.1", port, "tester", RemoteConnection.AuthMethod.PASSWORD, "", "probe", "");
    }

    /** Runs a connect and waits for the Result the FX callback delivers. */
    private RemoteFileSystems.Result connectAndWait(int port) throws Exception {
        return connectAndWait(fs, port);
    }

    private static RemoteFileSystems.Result connectAndWait(RemoteFileSystems engine, int port) throws Exception {
        AtomicReference<RemoteFileSystems.Result> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        engine.connect(connection(port), "pw".toCharArray(), r -> {
            got.set(r);
            done.countDown();
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "the connect attempt should have reported back");
        return got.get();
    }

    /**
     * Two windows connecting to different SFTP hosts don't strand each other (#436). Each {@link RemoteFileSystems}
     * registers its own {@link Vfs.RemoteProvider}, so both hosts' {@code sftp://} paths keep resolving — and
     * closing one window's engine leaves the other's paths resolvable.
     */
    @Test
    void twoWindowsToDifferentHostsDoNotStrandEachOther(@TempDir Path dir) throws Exception {
        SshServer serverA = sftpServer(dir.resolve("a.ser"), 0);
        SshServer serverB = sftpServer(dir.resolve("b.ser"), 0);
        RemoteFileSystems fsA = new RemoteFileSystems((h, p, t, f) -> true, dir.resolve("known_hosts"));
        RemoteFileSystems fsB = new RemoteFileSystems((h, p, t, f) -> true, dir.resolve("known_hosts"));
        try {
            // Connect on the test thread (the Result callback fires on the FX thread and releases the latch).
            connectAndWait(fsA, serverA.getPort());
            connectAndWait(fsB, serverB.getPort());
            String uriA = "sftp://tester@127.0.0.1:" + serverA.getPort() + "/srv/app.js";
            String uriB = "sftp://tester@127.0.0.1:" + serverB.getPort() + "/srv/app.js";

            // Both hosts resolve through the shared registry, and a resolved path round-trips back to its URI
            // via the owning engine's storable (Vfs is pure, so this runs on the test thread).
            Path pathA = Vfs.parseStorable(uriA);
            Path pathB = Vfs.parseStorable(uriB);
            assertNotNull(pathA, "window A's host resolves");
            assertNotNull(pathB, "window B's host resolves");
            assertEquals(uriA, Vfs.toStorableString(pathA), "A's path stores back via its own engine");
            assertEquals(uriB, Vfs.toStorableString(pathB), "B's path stores back via its own engine");

            fsB.shutdown(); // window B closes
            assertNotNull(Vfs.parseStorable(uriA), "A still resolves after B closed (the single-slot bug stranded it)");
            assertNull(Vfs.parseStorable(uriB), "B's host no longer resolves once its window is gone");
        } finally {
            fsA.shutdown();
            fsB.shutdown();
            serverA.stop(true);
            serverB.stop(true);
        }
    }

    @Test
    void connectingToAnImpostorIsRefusedWithoutAskingTheUser(@TempDir Path dir) throws Exception {
        Path knownHosts = dir.resolve("known_hosts");
        AtomicInteger asked = new AtomicInteger();
        fs = new RemoteFileSystems(
                (host, port, keyType, fingerprint) -> {
                    asked.incrementAndGet();
                    return true; // the user trusts the genuine server on first connect
                },
                knownHosts);

        SshServer real = sftpServer(dir.resolve("real.ser"), 0);
        int port = real.getPort();
        try {
            RemoteFileSystems.Result first = connectAndWait(port);
            assertTrue(first.ok(), "the genuine server should connect: " + first.error());
            assertNotNull(first.root());
            assertEquals(1, asked.get(), "an unknown host is put to the user once");
            assertTrue(Files.exists(knownHosts), "…and remembered on acceptance");
        } finally {
            real.stop(true);
        }

        // Someone else now answers on that address, holding their own host key.
        SshServer impostor = sftpServer(dir.resolve("impostor.ser"), port);
        try {
            RemoteFileSystems.Result second = connectAndWait(port);
            assertFalse(second.ok(), "a changed host key is a man-in-the-middle — the connection must fail");
            assertEquals(1, asked.get(), "and the user must not be offered a button that waves it through");
        } finally {
            impostor.stop(true);
        }
    }

    @Test
    void aHostAlreadyTrustedConnectsWithoutAskingAgain(@TempDir Path dir) throws Exception {
        Path knownHosts = dir.resolve("known_hosts");
        AtomicInteger asked = new AtomicInteger();
        fs = new RemoteFileSystems(
                (host, port, keyType, fingerprint) -> {
                    asked.incrementAndGet();
                    return true;
                },
                knownHosts);
        SshServer server = sftpServer(dir.resolve("host.ser"), 0);
        try {
            assertTrue(connectAndWait(server.getPort()).ok());
            assertTrue(connectAndWait(server.getPort()).ok());
            assertEquals(1, asked.get(), "the trust-on-first-use question is asked once, not every connect");
        } finally {
            server.stop(true);
        }
    }

    @Test
    void decliningTheHostKeyRefusesTheConnection(@TempDir Path dir) throws Exception {
        fs = new RemoteFileSystems((host, port, keyType, fingerprint) -> false, dir.resolve("known_hosts"));
        SshServer server = sftpServer(dir.resolve("host.ser"), 0);
        try {
            RemoteFileSystems.Result r = connectAndWait(server.getPort());
            assertFalse(r.ok(), "the user said no");
            assertNotNull(r.error());
        } finally {
            server.stop(true);
        }
    }

    /**
     * A user who takes longer than the handshake timeout to compare the fingerprint still connects — the dialog's
     * think-time is excluded from the deadline (#486). The host-key prompt blocks the key exchange that gates
     * authentication, so it lands inside the auth wait: here the auth timeout is a short 600ms but the prompt
     * blocks for 1500ms before accepting; without the exclusion the handshake would time out first.
     */
    @Test
    void aSlowHostKeyAnswerDoesNotTimeOutTheHandshake(@TempDir Path dir) throws Exception {
        fs = new RemoteFileSystems(
                (host, port, keyType, fingerprint) -> {
                    try {
                        Thread.sleep(1500); // the human is reading the fingerprint — longer than the 600ms timeout
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                },
                dir.resolve("known_hosts"),
                java.time.Duration.ofSeconds(20), // connect: generous (the future fulfils before the prompt)
                java.time.Duration.ofMillis(600)); // auth: short — the prompt overlaps this wait
        SshServer server = sftpServer(dir.resolve("host.ser"), 0);
        try {
            RemoteFileSystems.Result r = connectAndWait(server.getPort());
            assertTrue(r.ok(), "a slow fingerprint comparison must not time the handshake out: " + r.error());
            assertNotNull(r.root());
        } finally {
            server.stop(true);
        }
    }

    /** The prompt is handed the address the user typed and a fingerprint they can compare against the server. */
    @Test
    void thePromptIsToldWhichHostAndWhichKey(@TempDir Path dir) throws Exception {
        AtomicReference<String> shown = new AtomicReference<>("");
        fs = new RemoteFileSystems(
                (host, port, keyType, fingerprint) -> {
                    shown.set(host + ":" + port + " " + keyType + " " + fingerprint);
                    return true;
                },
                dir.resolve("known_hosts"));
        SshServer server = sftpServer(dir.resolve("host.ser"), 0);
        try {
            assertTrue(connectAndWait(server.getPort()).ok());
        } finally {
            server.stop(true);
        }
        assertTrue(shown.get().startsWith("127.0.0.1:" + server.getPort() + " "), shown.get());
        assertTrue(shown.get().contains("SHA256:"), "an OpenSSH-style fingerprint: " + shown.get());
    }
}
