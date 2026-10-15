package com.editora.vfs;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ed25519 — what {@code ssh-keygen} makes by default, and a host key many servers offer first: a server with
 * only an ed25519 host key, and a login with only an ed25519 key, both work. Without an EdDSA provider (MINA
 * SSHD has none of its own) both failed against a real OpenSSH server.
 */
@Tag("fx")
class RemoteFileSystemsEd25519FxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    @Test
    void anEd25519KeyLogsInToAServerWithOnlyAnEd25519HostKey() throws Exception {
        KeyPair hostKey = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
        KeyPair userKey = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
        Path keyFile = dir.resolve("id_ed25519");
        try (OutputStream out = Files.newOutputStream(keyFile)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(userKey, "editora-test", null, out);
        }
        Path served = Files.createDirectories(dir.resolve("served"));
        Files.writeString(served.resolve("hello.txt"), "hi\n");

        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
        PublickeyAuthenticator onlyThatKey =
                (user, key, session) -> KeyUtils.compareKeys(key, (PublicKey) userKey.getPublic());
        server.setPublickeyAuthenticator(onlyThatKey);
        server.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(served));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.start();
        AtomicReference<String> offered = new AtomicReference<>();
        RemoteFileSystems engine = new RemoteFileSystems(
                (host, port, keyType, fingerprint) -> {
                    offered.set(keyType);
                    return true;
                },
                dir.resolve("known_hosts"));
        try {
            RemoteConnection conn = new RemoteConnection(
                    "127.0.0.1", server.getPort(), "me", RemoteConnection.AuthMethod.KEY, keyFile.toString(), "t", "/");
            AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            engine.connect(conn, new char[0], r -> {
                result.set(r);
                done.countDown();
            });
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertTrue(result.get().ok(), result.get().error());
            assertEquals(KeyPairProvider.SSH_ED25519, offered.get(), "the host key the user was asked about");
            assertEquals("hi\n", Files.readString(result.get().root().resolve("hello.txt")));
        } finally {
            engine.shutdown();
            server.stop(true);
        }
    }
}
