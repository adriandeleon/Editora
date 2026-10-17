package com.editora.vfs;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ~/.ssh/config}, as {@code ssh} reads it: a {@code Host} alias's HostName, Port, User and IdentityFile,
 * {@code IdentitiesOnly}, and {@code ProxyJump} — all from the engine's own {@code ~/.ssh}, never the one of
 * whoever runs the tests.
 */
@Tag("fx")
class SshConfigLoginFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private final List<SshServer> servers = new CopyOnWriteArrayList<>();
    private RemoteFileSystems engine;

    @AfterEach
    void tearDown() throws Exception {
        if (engine != null) {
            engine.shutdown();
        }
        for (SshServer s : servers) {
            s.stop(true);
        }
    }

    private Path ssh() throws Exception {
        return Files.createDirectories(dir.resolve("dot-ssh"));
    }

    /** A server that accepts only {@code accepted}, recording each key a client offers, in order. */
    private SshServer keyServer(String name, KeyPair accepted, List<String> offered) throws Exception {
        SshServer s = SshServer.setUpDefaultServer();
        s.setHost("127.0.0.1");
        s.setPort(0);
        s.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve(name + "-host.ser")));
        s.setPublickeyAuthenticator((user, key, session) -> {
            offered.add(KeyUtils.getFingerPrint(key));
            return KeyUtils.compareKeys(key, (PublicKey) accepted.getPublic());
        });
        s.setKeyboardInteractiveAuthenticator(null);
        Path served = Files.createDirectories(dir.resolve(name + "-served"));
        Files.writeString(served.resolve("hello.txt"), name + "\n");
        s.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(served));
        s.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        servers.add(s);
        return s;
    }

    private static Path writeKey(KeyPair key, Path file) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "editora-test", null, out);
        }
        return file;
    }

    private static KeyPair newKey() throws Exception {
        return KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    }

    private RemoteFileSystems engine(RemoteFileSystems.HostKeyPrompt hostKeys, LoginPrompt login) throws Exception {
        engine = new RemoteFileSystems(
                hostKeys,
                login,
                SshEnvironment.at(ssh()),
                dir.resolve("known_hosts"),
                Duration.ofSeconds(20),
                Duration.ofSeconds(20));
        return engine;
    }

    private static RemoteFileSystems.Result connect(RemoteFileSystems engine, RemoteConnection conn)
            throws InterruptedException {
        AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        engine.connect(conn, null, r -> {
            result.set(r);
            done.countDown();
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "the connect never finished");
        return result.get();
    }

    private static RemoteConnection automatic(String host, int port, String user) {
        return new RemoteConnection(host, port, user, RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "t", "/");
    }

    @Test
    void anAliasGoesToItsHostNameWithItsIdentityFile() throws Exception {
        KeyPair key = newKey();
        Path keyFile = writeKey(key, dir.resolve("work_key"));
        SshServer server = keyServer("box", key, new CopyOnWriteArrayList<>());
        server.start();
        Files.writeString(
                ssh().resolve("config"),
                "Host box\n  HostName 127.0.0.1\n  Port " + server.getPort() + "\n  User me\n  IdentityFile " + keyFile
                        + "\n");

        RemoteFileSystems.Result r =
                connect(engine((h, p, t, f) -> true, null), automatic("box", server.getPort(), "me"));

        assertTrue(r.ok(), r.error());
        assertEquals("box\n", Files.readString(r.root().resolve("hello.txt")));
    }

    @Test
    void theDefaultKeysAreTheEnginesOwn() throws Exception {
        KeyPair key = newKey();
        writeKey(key, ssh().resolve("id_ed25519"));
        SshServer server = keyServer("plain", key, new CopyOnWriteArrayList<>());
        server.start();

        RemoteFileSystems.Result r =
                connect(engine((h, p, t, f) -> true, null), automatic("127.0.0.1", server.getPort(), "me"));

        assertTrue(r.ok(), r.error());
    }

    @Test
    void identityFileKeysComeBeforeTheDefaultOnesAndIdentitiesOnlyLeavesTheDefaultsOut() throws Exception {
        KeyPair named = newKey();
        KeyPair fallback = newKey();
        Path namedFile = writeKey(named, dir.resolve("named_key"));
        writeKey(fallback, ssh().resolve("id_ed25519"));
        List<String> offered = new CopyOnWriteArrayList<>();
        SshServer server = keyServer("order", fallback, offered); // only the default key gets in
        server.start();
        String block =
                "Host order\n  HostName 127.0.0.1\n  Port " + server.getPort() + "\n  IdentityFile " + namedFile + "\n";
        Files.writeString(ssh().resolve("config"), block);

        RemoteFileSystems.Result both =
                connect(engine((h, p, t, f) -> true, null), automatic("order", server.getPort(), "me"));

        assertTrue(both.ok(), both.error());
        String namedPrint = KeyUtils.getFingerPrint(named.getPublic());
        String fallbackPrint = KeyUtils.getFingerPrint(fallback.getPublic());
        assertEquals(namedPrint, offered.getFirst(), "the config's IdentityFile is offered first: " + offered);
        assertTrue(offered.contains(fallbackPrint), "then the default key");

        engine.shutdown();
        offered.clear();
        Files.writeString(ssh().resolve("config"), block + "  IdentitiesOnly yes\n");

        RemoteFileSystems.Result only =
                connect(engine((h, p, t, f) -> true, null), automatic("order", server.getPort(), "me"));

        assertFalse(only.ok(), "with IdentitiesOnly the default key that would have worked is not offered");
        assertFalse(offered.contains(fallbackPrint), offered.toString());
    }

    @Test
    void proxyJumpGoesThroughTheJumpHostAndAsksAboutBothHostKeys() throws Exception {
        KeyPair key = newKey();
        Path keyFile = writeKey(key, dir.resolve("key"));
        SshServer jump = keyServer("jump", key, new CopyOnWriteArrayList<>());
        List<String> forwarded = new CopyOnWriteArrayList<>();
        jump.setForwardingFilter(new AcceptAllForwardingFilter() {
            @Override
            public boolean canConnect(
                    Type type,
                    org.apache.sshd.common.util.net.SshdSocketAddress address,
                    org.apache.sshd.common.session.Session session) {
                forwarded.add(address.getHostName() + ":" + address.getPort());
                return true;
            }
        });
        jump.start();
        SshServer target = keyServer("target", key, new CopyOnWriteArrayList<>());
        target.start();
        Files.writeString(
                ssh().resolve("config"),
                "Host target\n  HostName 127.0.0.1\n  Port " + target.getPort() + "\n  ProxyJump me@127.0.0.1:"
                        + jump.getPort() + "\n\nHost *\n  IdentityFile " + keyFile + "\n");
        List<Integer> asked = new CopyOnWriteArrayList<>();

        RemoteFileSystems.Result r =
                connect(engine((h, p, t, f) -> asked.add(p), null), automatic("target", target.getPort(), "me"));

        assertTrue(r.ok(), r.error());
        assertEquals("target\n", Files.readString(r.root().resolve("hello.txt")));
        assertEquals(List.of("127.0.0.1:" + target.getPort()), forwarded, "the connection went through the jump host");
        assertTrue(asked.contains(jump.getPort()) && asked.contains(target.getPort()), "asked about " + asked);
    }

    @Test
    void theConfigsHostsAreOfferedAndAnAliasIsDescribedAsAConnectWouldReadIt() throws Exception {
        Files.writeString(
                ssh().resolve("config"),
                "Host box other\n  HostName 10.1.2.3\n  Port 2222\n  User ana\n  IdentityFile ~/.ssh/work\n"
                        + "Host *.corp !skip.corp\n  User corp\n"
                        + "Host via\n  ProxyJump bastion\n");
        RemoteFileSystems e = engine((h, p, t, f) -> true, null);

        assertEquals(List.of("box", "other", "via"), e.configuredHosts(), "patterns are not hosts you can pick");
        RemoteFileSystems.ConfiguredHost box = e.configured("box");
        assertEquals("10.1.2.3", box.hostName());
        assertEquals(2222, box.port());
        assertEquals("ana", box.user());
        assertEquals(1, box.identities().size());
        assertEquals("bastion", e.configured("via").proxyJump());
        assertEquals("corp", e.configured("db.corp").user(), "a pattern block applies to a host typed in");
        assertNull(e.configured("elsewhere.example"), "nothing said about it");
    }
}
