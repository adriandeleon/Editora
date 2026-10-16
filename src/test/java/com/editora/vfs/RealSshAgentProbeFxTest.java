package com.editora.vfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logging in with a key a <b>real</b> SSH agent holds — {@code ssh-agent} on Linux or macOS, the OpenSSH agent
 * service's named pipe on Windows — against an in-process server that accepts only that key. Opt-in:
 *
 * <pre>
 *   -Dremote.probe.agent=&lt;socket path, or "default" for SSH_AUTH_SOCK / Windows' pipe&gt;
 *   -Dremote.probe.agentKey=&lt;the .pub of a key the agent holds&gt;
 * </pre>
 *
 * The remote-probe workflow runs it on each OS; the agent tests in CI use an agent of their own.
 */
@Tag("fx")
@EnabledIfSystemProperty(named = "remote.probe.agent", matches = ".+")
class RealSshAgentProbeFxTest {

    @TempDir
    Path dir;

    @Test
    void aKeyInTheRealAgentLogsIn() throws Exception {
        FxToolkit.registerPrimaryStage();
        PublicKey held = PublicKeyEntry.parsePublicKeyEntry(
                        Files.readString(Path.of(System.getProperty("remote.probe.agentKey")))
                                .strip())
                .resolvePublicKey(null, Map.of(), null);
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("host.ser")));
        server.setPublickeyAuthenticator((user, key, session) -> KeyUtils.compareKeys(key, held));
        server.setKeyboardInteractiveAuthenticator(null);
        Path served = Files.createDirectories(dir.resolve("served"));
        server.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(served));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.start();
        Map<String, String> env = new HashMap<>(System.getenv());
        String agent = System.getProperty("remote.probe.agent");
        if (!agent.equals("default")) {
            env.put("SSH_AUTH_SOCK", agent);
        }
        SshEnvironment standard = SshEnvironment.standard();
        RemoteFileSystems engine = new RemoteFileSystems(
                (h, p, t, f) -> true,
                null,
                new SshEnvironment(Files.createDirectories(dir.resolve("dot-ssh")), env, standard.windows()),
                dir.resolve("known_hosts"),
                Duration.ofSeconds(20),
                Duration.ofSeconds(20));
        try {
            AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            engine.connect(
                    new RemoteConnection(
                            "127.0.0.1",
                            server.getPort(),
                            "me",
                            RemoteConnection.AuthMethod.DEFAULT_KEYS,
                            "",
                            "t",
                            "/"),
                    null,
                    r -> {
                        result.set(r);
                        done.countDown();
                    });
            assertTrue(done.await(60, TimeUnit.SECONDS), "the connect never finished");
            assertTrue(
                    result.get().ok(),
                    "the agent's key did not log in: " + result.get().error());
        } finally {
            engine.shutdown();
            server.stop(true);
        }
    }
}
