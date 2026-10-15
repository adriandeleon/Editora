package com.editora.vfs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecSpec;
import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;
import com.editora.process.PosixShell;
import com.editora.process.ProcessRegistry;
import com.editora.process.ProcessRunner;
import com.editora.process.RemoteProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commands for a remote folder really run on the host it lives on: an in-process SSH server that hands each
 * command line to {@code /bin/sh}, reached through the same connection that serves the folder's files.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class SshExecTargetFxTest {

    @org.junit.jupiter.api.BeforeEach
    void allowRemoteExecution() {
        ExecTargets.setRemoteEnabled(() -> true);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreTheDefault() {
        ExecTargets.setRemoteEnabled(null);
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** The remote engine posts its connect result to the FX thread, so the toolkit has to be running. */
    @BeforeAll
    static void bootToolkit() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture fixture;
    private Path remoteDir;

    @BeforeEach
    void connect() throws Exception {
        fixture = EmbeddedSftpFixture.startWithShell(base);
        remoteDir = fixture.remotePath("project dir");
        Files.createDirectories(remoteDir);
    }

    @AfterEach
    void close() throws Exception {
        fixture.close();
    }

    private String serverDir() throws IOException {
        return fixture.serverPath("project dir").toRealPath().toString();
    }

    @Test
    void aCommandRunsInTheRemoteDirectoryAndItsThreeResultsComeBack() throws Exception {
        ProcessRunner.Result r = ProcessRunner.run(
                remoteDir,
                TIMEOUT,
                List.of("sh", "-c", "pwd -P; echo \"$LC_ALL|$GREETING\"; echo oops >&2; exit 7"),
                Map.of("GREETING", "hola"));

        assertEquals(7, r.exit());
        assertEquals(serverDir() + "\nC|hola\n", r.out());
        assertEquals("oops\n", r.err(), "the pid line is the transport's, not the command's");
    }

    /**
     * A variable the account's login profile exports — JAVA_HOME, MAVEN_OPTS, a version manager's — reaches a
     * command, as it would in the account's own terminal; a command's own value still wins. Before, only PATH
     * and the locale were carried over: a JAVA_HOME set in ~/.bash_profile was missing from every build.
     */
    @Test
    void whatTheLoginProfileExportsReachesACommandUnlessTheCommandSaysOtherwise() throws Exception {
        String profile = "export EDITORA_TEST_FROM_PROFILE='set by the profile'\nexport TOOL_HOME=/opt/tool\n";
        for (String name : List.of(".bash_profile", ".zprofile", ".profile")) {
            Files.writeString(fixture.home().resolve(name), profile); // whichever the login shell reads
        }

        ProcessRunner.Result r = ProcessRunner.run(
                remoteDir,
                TIMEOUT,
                List.of("sh", "-c", "echo \"$TOOL_HOME|$SSH_CONNECTION_TEST|$EDITORA_TEST_FROM_PROFILE\""),
                Map.of("TOOL_HOME", "/opt/override"));

        assertEquals(0, r.exit(), r.err());
        assertEquals("/opt/override||\n", r.out(), "the command's own value wins; EDITORA_ names are Editora's own");
        ProcessRunner.Result plain = ProcessRunner.run(remoteDir, TIMEOUT, List.of("sh", "-c", "echo \"$TOOL_HOME\""));
        assertEquals("/opt/tool\n", plain.out(), "the profile's value, for a command that sets none");
    }

    @Test
    void stdinReachesTheCommandAndItsEndDoesToo() {
        ProcessRunner.Result r = ProcessRunner.run(remoteDir, TIMEOUT, List.of("cat"), Map.of(), "línea\n");

        assertEquals(0, r.exit(), r.err());
        assertEquals("línea\n", r.out());
    }

    @Test
    void aFileWrittenByACommandIsTheFileSftpSees() throws Exception {
        ExecTarget target = ExecTargets.forPath(remoteDir);
        Path file = remoteDir.resolve("made by sh.txt");

        ProcessRunner.Result r = ProcessRunner.run(
                remoteDir, TIMEOUT, List.of("sh", "-c", "printf hi > \"$1\"", "sh", target.toTargetPath(file)));

        assertEquals(0, r.exit(), r.err());
        assertEquals("hi", Files.readString(file));
        assertEquals(file, target.toPath(target.toTargetPath(file)));
        assertEquals(file, target.resolve(remoteDir, "made by sh.txt"));
    }

    @Test
    void aDirectoryThatIsNotThereFailsTheCommandNotTheCaller() {
        ProcessRunner.Result r = ProcessRunner.run(remoteDir.resolve("gone"), TIMEOUT, List.of("echo", "ran"));

        assertEquals(126, r.exit());
        assertEquals("", r.out());
    }

    @Test
    void theTargetIsTheConnectionsAndGoesWithIt() {
        ExecTarget target = ExecTargets.forPath(remoteDir);
        assertFalse(target.isLocal());
        assertTrue(target.isAvailable());
        assertEquals(fixture.connection().id(), target.id());

        fixture.disconnect();

        assertFalse(target.isAvailable());
        assertFalse(ExecTargets.forPath(remoteDir).isAvailable(), "a path of a closed connection has no target");
        ExecSpec spec = ExecSpec.of(List.of("true"), remoteDir, Map.of(), ExecSpec.Locale.USER);
        assertThrows(IOException.class, () -> target.start(spec));
        assertEquals(-1, ProcessRunner.run(remoteDir, TIMEOUT, List.of("true")).exit());
    }

    @Test
    void aStartedProcessIsARemoteProcessWithThePidTheHostGaveIt() throws Exception {
        ExecTarget target = ExecTargets.forPath(remoteDir);
        Process p = target.start(
                ExecSpec.of(List.of("sh", "-c", "echo $$; cat"), remoteDir, Map.of(), ExecSpec.Locale.USER));
        try {
            RemoteProcess remote = assertInstanceOf(RemoteProcess.class, p);
            p.getOutputStream().close(); // cat sees the end of its input and the command finishes
            String printed = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();

            assertEquals(0, p.onExit().get(20, TimeUnit.SECONDS).exitValue(), "onExit completes without polling");
            assertEquals(0, p.waitFor());
            assertEquals(Long.parseLong(printed), remote.remotePid());
            assertFalse(remote.connectionLost());
            assertThrows(UnsupportedOperationException.class, p::pid);
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX) // the fixture needs setsid(1) to give the command its own group, as sshd does
    void killingATreeStopsTheCommandAndWhatItStarted() throws Exception {
        ExecTarget target = ExecTargets.forPath(remoteDir);
        Process p = target.start(ExecSpec.of(
                        List.of("sh", "-c", "sleep 600 & echo $!; exec sleep 600"),
                        remoteDir,
                        Map.of(),
                        ExecSpec.Locale.USER)
                .withLongLived(true));
        long child = Long.parseLong(
                new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))
                        .readLine()
                        .strip());
        long leader = ((RemoteProcess) p).remotePid();
        ProcessRegistry.track(p);

        ProcessRegistry.killTree(p);

        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "the command survived killTree");
        assertTrue(gone(leader) && gone(child), "part of the tree survived");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aCommandThatOutlivesItsTimeoutIsStoppedOnTheHost() throws Exception {
        ProcessRunner.Result r = ProcessRunner.run(remoteDir, Duration.ofMillis(700), List.of("sleep", "600"));

        assertTrue(r.timedOut());
        // The kill is its own command on a second channel, sent without waiting for it.
        boolean killed = false;
        for (int i = 0; i < 100 && !killed; i++) {
            killed = fixture.commands().stream().anyMatch(line -> line.contains("kill -s TERM -- -"));
            Thread.sleep(50);
        }
        assertTrue(killed, "no group kill was sent: " + fixture.commands());
    }

    @Test
    void aForwardedPortReachesAServerOnTheHostUntilItIsClosed() throws Exception {
        try (java.net.ServerSocket onTheHost =
                new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try (java.net.Socket peer = onTheHost.accept()) {
                    peer.getOutputStream().write("from the host\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException closed) {
                    // the test ended first
                }
            });
            ExecTarget target = ExecTargets.forPath(remoteDir);

            com.editora.process.PortForward forward = target.forwardLocal("127.0.0.1", onTheHost.getLocalPort());
            int local = forward.localPort();
            assertTrue(local > 0 && local != onTheHost.getLocalPort(), "a port of its own on this machine");
            try (java.net.Socket here = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), local)) {
                here.setSoTimeout(10_000);
                assertEquals(
                        "from the host",
                        new java.io.BufferedReader(
                                        new java.io.InputStreamReader(here.getInputStream(), StandardCharsets.UTF_8))
                                .readLine());
            }

            forward.close();
            forward.close(); // harmless twice
            assertThrows(
                    IOException.class,
                    () -> new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), local).close());
        }
        assertEquals(
                4321, ExecTargets.local().forwardLocal("127.0.0.1", 4321).localPort(), "nothing to forward locally");
    }

    @Test
    void listenersHearAConnectionComeUpAndGoDown() throws Exception {
        List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        fixture.fileSystems().addConnectionListener((authority, up) -> heard.add(authority + (up ? " up" : " down")));
        String id = fixture.connection().id();

        fixture.disconnect();
        awaitHeard(heard, id + " down");
        fixture.reconnect();
        awaitHeard(heard, id + " up");

        assertEquals(List.of(id + " down", id + " up"), heard);
        assertTrue(
                ExecTargets.forPath(fixture.remotePath("project dir")).isAvailable(),
                "a new target for the new connection");
    }

    private static void awaitHeard(List<String> heard, String event) throws InterruptedException {
        for (int i = 0; i < 200 && !heard.contains(event); i++) {
            Thread.sleep(25);
        }
        assertTrue(heard.contains(event), "never heard '" + event + "', only " + heard);
    }

    @Test
    void keepAlivesDoNotEndAnIdleConnection() throws Exception {
        try (org.apache.sshd.client.SshClient probe = org.apache.sshd.client.SshClient.setUpDefaultClient()) {
            RemoteFileSystems.keepAlive(probe, Duration.ofMillis(100));
            probe.setServerKeyVerifier(org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE);
            probe.start();
            RemoteConnection conn = fixture.connection();
            try (var session = probe.connect(conn.user(), conn.host(), conn.port())
                    .verify(TIMEOUT)
                    .getSession()) {
                session.addPasswordIdentity("password");
                session.auth().verify(TIMEOUT);

                Thread.sleep(1200); // a dozen keep-alives, each answered or the session would be closed

                assertTrue(session.isOpen());
                assertEquals("alive\n", session.executeRemoteCommand("echo alive"));
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void onlySoManyProgramsRunAtOnceAndAFinishedOneFreesItsPlace() throws Exception {
        ExecTarget target = ExecTargets.forPath(remoteDir);
        ExecSpec program = ExecSpec.of(List.of("sleep", "600"), remoteDir, Map.of(), ExecSpec.Locale.USER)
                .withLongLived(true);
        List<Process> running = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < SshExecTarget.MAX_LONG_LIVED; i++) {
                running.add(target.start(program));
            }
            IOException refused = assertThrows(IOException.class, () -> target.start(program));
            assertTrue(refused.getMessage().contains("too many programs are already running"), refused.getMessage());
            // Short commands have their own share: a full house of programs does not stop git.
            assertEquals(
                    0, ProcessRunner.run(remoteDir, TIMEOUT, List.of("true")).exit());

            // A start right behind a stop gets the place the stop frees, a moment later, instead of a refusal
            // (found against a real server: stopping a run and starting the next one was refused).
            Process first = running.removeFirst();
            first.destroyForcibly();
            running.add(target.start(program));
            assertEquals(SshExecTarget.MAX_LONG_LIVED, running.size());
        } finally {
            for (Process p : running) {
                p.destroyForcibly();
            }
            for (Process p : running) {
                p.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void whatAStartupFilePrintsBeforeThePidLineIsDropped() throws Exception {
        byte[] stderr =
                ("motd: welcome\n" + PosixShell.PID_MARK + "4242\nreal error\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(stderr);

        assertEquals(4242, SshExecProcess.readPidLine(in));
        assertEquals("real error\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aCommandTheHostNeverStartedFailsWithWhatTheHostSaid() {
        ByteArrayInputStream in =
                new ByteArrayInputStream("fish: Unknown command: exec\n".getBytes(StandardCharsets.UTF_8));

        IOException e = assertThrows(IOException.class, () -> SshExecProcess.readPidLine(in));
        assertEquals("fish: Unknown command: exec", e.getMessage());
    }

    private static boolean gone(long pid) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
