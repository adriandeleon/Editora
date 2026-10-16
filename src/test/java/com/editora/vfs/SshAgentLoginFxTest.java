package com.editora.vfs;

import java.io.IOException;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.agent.common.AbstractAgentClient;
import org.apache.sshd.agent.local.AgentImpl;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.session.SessionContext;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logging in with a key the SSH agent holds — an agent of the test's own on a Unix-domain socket, speaking the
 * agent protocol (MINA's {@link AbstractAgentClient} over an in-memory {@link AgentImpl}) — and the rules: only
 * an Automatic login asks it, {@code IdentityAgent none} turns it off, a dead socket is passed over, and its keys
 * come before key files.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the agent here is a Unix-domain socket; Windows' named pipe is the remote probe's
class SshAgentLoginFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private SshServer server;
    private RemoteFileSystems engine;
    private TestAgent agent;
    private Path socketDir;

    @AfterEach
    void tearDown() throws Exception {
        if (engine != null) {
            engine.shutdown();
        }
        if (server != null) {
            server.stop(true);
        }
        if (agent != null) {
            agent.close();
        }
        if (socketDir != null) {
            try (var files = Files.list(socketDir)) {
                for (Path f : files.toList()) {
                    Files.deleteIfExists(f);
                }
            }
            Files.deleteIfExists(socketDir);
        }
    }

    /** A socket path short enough for {@code sun_path} (about 104 bytes), which macOS's temp folders are not. */
    private Path socket() throws IOException {
        Path tmp = Path.of("/tmp");
        socketDir = Files.isDirectory(tmp) ? Files.createTempDirectory(tmp, "ea") : Files.createTempDirectory("ea");
        return socketDir.resolve("agent.sock");
    }

    private Path ssh() throws IOException {
        return Files.createDirectories(dir.resolve("dot-ssh"));
    }

    private void startServer(KeyPair accepted, List<String> offered) throws Exception {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("host.ser")));
        server.setPublickeyAuthenticator((user, key, session) -> {
            offered.add(KeyUtils.getFingerPrint(key));
            return KeyUtils.compareKeys(key, (PublicKey) accepted.getPublic());
        });
        server.setKeyboardInteractiveAuthenticator(null);
        Path served = Files.createDirectories(dir.resolve("served"));
        Files.writeString(served.resolve("hello.txt"), "hi\n");
        server.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(served));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.start();
    }

    private RemoteFileSystems engine(Map<String, String> env) throws IOException {
        engine = new RemoteFileSystems(
                (h, p, t, f) -> true,
                null,
                new SshEnvironment(ssh(), env, false),
                dir.resolve("known_hosts"),
                Duration.ofSeconds(20),
                Duration.ofSeconds(20));
        return engine;
    }

    private RemoteFileSystems.Result connect(RemoteConnection.AuthMethod auth, String keyPath) throws Exception {
        RemoteConnection conn = new RemoteConnection("127.0.0.1", server.getPort(), "me", auth, keyPath, "t", "/");
        AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        engine.connect(conn, null, r -> {
            result.set(r);
            done.countDown();
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "the connect never finished");
        return result.get();
    }

    private static KeyPair newKey() throws Exception {
        return KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    }

    private static Path writeKey(KeyPair key, Path file) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "editora-test", null, out);
        }
        return file;
    }

    @Test
    void aKeyTheAgentHoldsLogsInWithNoKeyFileAnywhere() throws Exception {
        KeyPair key = newKey();
        Path sock = socket();
        agent = TestAgent.start(sock, key);
        startServer(key, new CopyOnWriteArrayList<>());
        engine(Map.of("SSH_AUTH_SOCK", sock.toString()));

        RemoteFileSystems.Result r = connect(RemoteConnection.AuthMethod.DEFAULT_KEYS, "");

        assertTrue(r.ok(), r.error());
        assertEquals(1, agent.signatures.get(), "the agent signed the login");
        assertEquals("hi\n", Files.readString(r.root().resolve("hello.txt")));
    }

    @Test
    void identityAgentInTheConfigFindsItAndNoneTurnsItOff() throws Exception {
        KeyPair key = newKey();
        Path sock = socket();
        agent = TestAgent.start(sock, key);
        startServer(key, new CopyOnWriteArrayList<>());
        Files.writeString(ssh().resolve("config"), "Host 127.0.0.1\n  IdentityAgent " + sock + "\n");
        engine(Map.of()); // no SSH_AUTH_SOCK: only the config says where

        assertTrue(connect(RemoteConnection.AuthMethod.DEFAULT_KEYS, "").ok(), "found through IdentityAgent");

        engine.shutdown();
        Files.writeString(ssh().resolve("config"), "Host 127.0.0.1\n  IdentityAgent none\n");
        engine(Map.of("SSH_AUTH_SOCK", sock.toString()));
        int before = agent.signatures.get();

        assertFalse(connect(RemoteConnection.AuthMethod.DEFAULT_KEYS, "").ok());
        assertEquals(before, agent.signatures.get(), "IdentityAgent none: the agent is not asked");
    }

    @Test
    void anAgentThatIsNotThereIsPassedOverForTheKeyFiles() throws Exception {
        KeyPair key = newKey();
        writeKey(key, ssh().resolve("id_ed25519"));
        startServer(key, new CopyOnWriteArrayList<>());
        engine(Map.of("SSH_AUTH_SOCK", socket().toString())); // nothing listens there

        RemoteFileSystems.Result r = connect(RemoteConnection.AuthMethod.DEFAULT_KEYS, "");

        assertTrue(r.ok(), r.error());
    }

    @Test
    void aKeyFileLoginDoesNotAskTheAgent() throws Exception {
        KeyPair agentKey = newKey();
        Path sock = socket();
        agent = TestAgent.start(sock, agentKey);
        startServer(agentKey, new CopyOnWriteArrayList<>());
        Path other = writeKey(newKey(), dir.resolve("other_key"));
        engine(Map.of("SSH_AUTH_SOCK", sock.toString()));

        RemoteFileSystems.Result r = connect(RemoteConnection.AuthMethod.KEY, other.toString());

        assertFalse(r.ok(), "only the named key is offered, and the server wants the agent's");
        assertEquals(0, agent.requests.get(), "the agent was never asked");
    }

    @Test
    void theAgentsKeysAreOfferedBeforeTheKeyFiles() throws Exception {
        KeyPair agentKey = newKey();
        KeyPair fileKey = newKey();
        writeKey(fileKey, ssh().resolve("id_ed25519"));
        Path sock = socket();
        agent = TestAgent.start(sock, agentKey);
        List<String> offered = new CopyOnWriteArrayList<>();
        startServer(fileKey, offered); // only the file key gets in
        engine(Map.of("SSH_AUTH_SOCK", sock.toString()));

        RemoteFileSystems.Result r = connect(RemoteConnection.AuthMethod.DEFAULT_KEYS, "");

        assertTrue(r.ok(), r.error());
        assertEquals(KeyUtils.getFingerPrint(agentKey.getPublic()), offered.getFirst(), offered.toString());
        assertTrue(offered.contains(KeyUtils.getFingerPrint(fileKey.getPublic())));
    }

    @Test
    void aProxyJumpHopLogsInWithTheAgentToo() throws Exception {
        KeyPair key = newKey();
        Path sock = socket();
        agent = TestAgent.start(sock, key);
        startServer(key, new CopyOnWriteArrayList<>()); // the target
        SshServer target = server;
        startServer(key, new CopyOnWriteArrayList<>()); // the jump host, which forwards
        SshServer jump = server;
        jump.setForwardingFilter(org.apache.sshd.server.forward.AcceptAllForwardingFilter.INSTANCE);
        server = target;
        Files.writeString(
                ssh().resolve("config"),
                "Host target\n  HostName 127.0.0.1\n  Port " + target.getPort() + "\n  ProxyJump me@127.0.0.1:"
                        + jump.getPort() + "\n");
        engine(Map.of("SSH_AUTH_SOCK", sock.toString()));
        try {
            RemoteConnection conn = new RemoteConnection(
                    "target", target.getPort(), "me", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "t", "/");
            AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            engine.connect(conn, null, r -> {
                result.set(r);
                done.countDown();
            });
            assertTrue(done.await(60, TimeUnit.SECONDS));

            assertTrue(
                    result.get().ok(),
                    "the hop has no key but the agent's: " + result.get().error());
            assertEquals(2, agent.signatures.get(), "one signature for the hop, one for the target");
        } finally {
            jump.stop(true);
        }
    }

    /** An SSH agent on a Unix-domain socket, holding {@code keys}: MINA's protocol handler over AgentImpl. */
    static final class TestAgent implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger signatures = new AtomicInteger();
        private final ServerSocketChannel listener;
        private final AgentImpl keys = new AgentImpl() {
            @Override
            public java.util.Map.Entry<String, byte[]> sign(
                    SessionContext session, PublicKey key, String algo, byte[] data) throws IOException {
                signatures.incrementAndGet();
                return super.sign(session, key, algo, data);
            }
        };

        private TestAgent(ServerSocketChannel listener) {
            this.listener = listener;
        }

        static TestAgent start(Path socket, KeyPair... held) throws IOException {
            ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            listener.bind(UnixDomainSocketAddress.of(socket));
            TestAgent agent = new TestAgent(listener);
            for (KeyPair k : held) {
                agent.keys.addIdentity(k, "test");
            }
            Thread.ofVirtual().start(agent::serve);
            return agent;
        }

        private void serve() {
            while (listener.isOpen()) {
                try {
                    SocketChannel client = listener.accept();
                    Thread.ofVirtual().start(() -> answer(client));
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void answer(SocketChannel client) {
            AbstractAgentClient protocol = new AbstractAgentClient(keys) {
                @Override
                protected void reply(Buffer buf) throws IOException {
                    ByteBuffer out = ByteBuffer.wrap(buf.array(), buf.rpos(), buf.available());
                    while (out.hasRemaining()) {
                        client.write(out);
                    }
                }
            };
            try (client) {
                ByteBuffer in = ByteBuffer.allocate(64 * 1024);
                while (client.read(in) >= 0) {
                    in.flip();
                    byte[] chunk = new byte[in.remaining()];
                    in.get(chunk);
                    in.clear();
                    requests.incrementAndGet();
                    protocol.messageReceived(new ByteArrayBuffer(chunk));
                }
            } catch (IOException e) {
                // the client went away
            }
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }
}
