package com.editora.container;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecSpec;
import com.editora.process.ExecTargets;
import com.editora.process.ProcessRegistry;
import com.editora.process.ProcessRunner;
import com.editora.process.RemoteProcess;
import com.editora.run.RunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commands for a folder bind-mounted into a <b>real</b> container. Opt-in: needs an engine and an image that
 * is already on this machine (nothing is pulled), for example
 *
 * <pre>
 * mvn test -Dtest=ContainerProbeFxTest -Dgroups=fx -Dcontainer.probe=podman -Dcontainer.probe.image=eclipse-temurin:25-jdk
 * </pre>
 *
 * The image needs {@code sh}, {@code sleep} and {@code ps}.
 */
@Tag("fx")
@EnabledIfSystemProperty(named = "container.probe", matches = ".+")
class ContainerProbeFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private ContainerEngine engine;
    private String name;
    private Path folder;
    private ContainerExecTarget target;
    private com.editora.process.ExecTargets.Resolver resolver;

    private ProcessRunner.Result engine(String... args) {
        List<String> argv = new java.util.ArrayList<>(engine.command());
        argv.addAll(List.of(args));
        return ProcessRunner.run(null, Duration.ofSeconds(120), argv);
    }

    @BeforeEach
    void setUp() throws Exception {
        engine = ContainerEngine.of(System.getProperty("container.probe"));
        folder = Files.createDirectories(dir.toRealPath().resolve("app"));
        Files.writeString(folder.resolve("a.txt"), "hola\n");
        name = "editora-probe-" + Long.toHexString(System.nanoTime());
        ProcessRunner.Result run = engine(
                "run",
                "-d",
                "--name",
                name,
                "-v",
                folder + ":/workspaces/app:Z",
                System.getProperty("container.probe.image", "eclipse-temurin:25-jdk"),
                "sleep",
                "infinity");
        assertTrue(run.ok(), "the container did not start: " + run.message());
        ContainerInfo info = engine.inspect(name);
        assertNotNull(info, "inspect");
        target = new ContainerExecTarget(engine, info);
        resolver = path -> target.covers(path) ? target : null;
        ExecTargets.setRemoteEnabled(() -> true);
        ExecTargets.registerMapped(resolver);
        ExecTargets.registerMapped(ContainerBindings.shared());
    }

    @AfterEach
    void tearDown() {
        ExecTargets.unregisterMapped(resolver);
        ExecTargets.unregisterMapped(ContainerBindings.shared());
        ExecTargets.setRemoteEnabled(null);
        engine("rm", "-f", name);
    }

    private static void onFx(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    private List<String> processes() {
        ProcessRunner.Result ps = engine("exec", name, "ps", "-eo", "args=");
        return ps.out().lines().map(String::strip).toList();
    }

    @Test
    void aCommandForTheFolderRunsInTheContainerWhereItIsMounted() {
        assertFalse(ExecTargets.runsHere(folder.resolve("a.txt")));

        ProcessRunner.Result r = ProcessRunner.run(
                folder,
                Duration.ofSeconds(60),
                List.of("sh", "-c", "pwd; cat a.txt; test -f /.dockerenv -o -f /run/.containerenv && echo inside"));

        assertTrue(r.ok(), r.message());
        assertEquals(
                List.of("/workspaces/app", "hola", "inside"), r.out().lines().toList());
        assertEquals("/workspaces/app/a.txt", target.toTargetPath(folder.resolve("a.txt")));
        assertEquals(folder.resolve("a.txt"), target.toPath("/workspaces/app/a.txt"));
    }

    @Test
    void editorasWorkingFolderIsInTheContainersHome() throws Exception {
        com.editora.process.TargetCache cache = target.cache();
        String home = target.env().home();
        Path jar = Files.writeString(dir.resolve("debug-0.53.jar"), "jar bytes");

        String copy = cache.putFile("java-debug/debug-0.53.jar", jar);
        String config = cache.putText("git-global-0011.config", "[core]\n\tpager = cat\n");
        String workspace = cache.makeDirectories("jdtls-workspaces/abc123");

        assertEquals(home + "/.cache/editora/java-debug/debug-0.53.jar", copy);
        ProcessRunner.Result seen = engine(
                "exec",
                name,
                "sh",
                "-c",
                "cat \"$0\"; cat \"$1\"; test -d \"$2\" && echo folder",
                copy,
                config,
                workspace);
        assertTrue(seen.ok(), seen.message());
        assertEquals("jar bytes[core]\n\tpager = cat\nfolder\n", seen.out());

        cache.delete("jdtls-workspaces");
        ProcessRunner.Result gone = engine("exec", name, "test", "-e", home + "/.cache/editora/jdtls-workspaces");
        assertFalse(gone.ok(), "removed in the container");
    }

    @Test
    void aProgramACrashedEditoraLeftInTheContainerIsStoppedByTheNextOne() throws Exception {
        RemoteProcess p = (RemoteProcess) target.start(ExecSpec.of(
                        List.of("sh", "-c", "sleep 300 & sleep 301; wait"),
                        folder,
                        Map.of(),
                        ExecSpec.Locale.PARSE_STABLE)
                .withLongLived(true));
        List<String> record = p.orphanRecord();
        assertEquals(ContainerExecTarget.ORPHAN_KIND, record.get(0), String.valueOf(record));
        List<String> fields = record.subList(1, record.size());

        // The crash: the engine's client here dies with Editora; the program in the container does not.
        List<ProcessHandle> clients = ProcessHandle.current()
                .children()
                .filter(h ->
                        h.info().commandLine().orElse("").contains(target.info().id()))
                .toList();
        assertFalse(clients.isEmpty(), "the exec client is a child of this process");
        clients.forEach(ProcessHandle::destroyForcibly);
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the client ended");
        Thread.sleep(500);
        assertTrue(processes().stream().anyMatch(a -> a.startsWith("sleep 30")), "left running: " + processes());

        List<String> other = new java.util.ArrayList<>(fields);
        other.set(3, fields.get(3) + "1");
        assertFalse(ContainerExecTarget.reapOrphan(other), "another start time: not that program");
        assertTrue(processes().stream().anyMatch(a -> a.startsWith("sleep 30")));

        assertTrue(ContainerExecTarget.reapOrphan(fields));
        List<String> left = processes();
        for (int i = 0; i < 50 && left.stream().anyMatch(a -> a.startsWith("sleep 30")); i++) {
            Thread.sleep(200);
            left = processes();
        }
        assertFalse(left.stream().anyMatch(a -> a.startsWith("sleep 30")), "stopped with its group: " + left);
        assertFalse(ContainerExecTarget.reapOrphan(fields), "gone: nothing left to stop");
    }

    @Test
    void theContainersOwnPathAndLocaleAreUsed() throws Exception {
        ProcessRunner.Result r =
                ProcessRunner.run(folder, Duration.ofSeconds(60), List.of("sh", "-c", "echo \"$PATH\""));

        assertTrue(r.ok(), r.message());
        assertEquals(target.env().path(), r.out().strip(), "the container's login PATH, not this machine's");
    }

    @Test
    void stoppingAProgramStopsWhatItStartedInTheContainer() throws Exception {
        Process p = target.start(ExecSpec.of(
                        List.of("sh", "-c", "sleep 300 & sleep 301; wait"),
                        folder,
                        Map.of(),
                        ExecSpec.Locale.PARSE_STABLE)
                .withLongLived(true));
        assertTrue(p instanceof RemoteProcess);
        for (int i = 0; i < 100 && !processes().contains("sleep 301"); i++) {
            Thread.sleep(100);
        }
        assertTrue(processes().contains("sleep 300"), processes().toString());

        ProcessRegistry.killTree(p);

        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the program did not end");
        for (int i = 0; i < 100 && processes().stream().anyMatch(l -> l.startsWith("sleep 30")); i++) {
            Thread.sleep(100);
        }
        assertFalse(processes().stream().anyMatch(l -> l.startsWith("sleep 30")), "left running: " + processes());
    }

    @Test
    void runRunsTheProgramInTheContainerAndReadsWhatIsTyped() throws Exception {
        RunService run = new RunService();
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        int[] exit = {Integer.MIN_VALUE};
        RunService.Listener listener = new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {}

            @Override
            public void onOutput(String line, boolean stderr) {
                out.add(line);
            }

            @Override
            public void onExit(int code) {
                exit[0] = code;
                done.countDown();
            }

            @Override
            public void onError(String message) {
                out.add("ERROR " + message);
                done.countDown();
            }
        };
        onFx(() -> run.runInDir(
                folder, List.of("sh", "-c", "read name; echo \"hello $name\"; exit 3"), Map.of(), listener));
        for (int i = 0; i < 100 && run.pid() <= 0; i++) {
            Thread.sleep(50);
        }
        onFx(() -> run.sendInput("Ñandú"));

        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertTrue(out.contains("hello Ñandú"), out.toString());
        assertEquals(3, exit[0]);
    }

    /** A dev container is found for its folder by the label the Dev Containers CLI and VS Code put on it. */
    @Test
    void aDevContainerIsFoundForItsFolderByItsLabel() throws Exception {
        Path other = Files.createDirectories(dir.toRealPath().resolve("dev"));
        String dev = name + "-dev";
        ProcessRunner.Result run = engine(
                "run",
                "-d",
                "--name",
                dev,
                "--label",
                ContainerInfo.LOCAL_FOLDER_LABEL + "=" + other,
                "-v",
                other + ":/workspaces/dev:Z",
                System.getProperty("container.probe.image", "eclipse-temurin:25-jdk"),
                "sleep",
                "infinity");
        assertTrue(run.ok(), run.message());
        try {
            ContainerExecTarget found = ContainerBindings.shared().bind(other, null, ContainerBindings.lookup(engine));

            assertNotNull(found, "the dev container for the folder");
            assertEquals("container:" + dev, found.id());
            ProcessRunner.Result pwd = ProcessRunner.run(other, Duration.ofSeconds(60), List.of("pwd"));
            assertEquals("/workspaces/dev", pwd.out().strip(), pwd.message());
            assertTrue(engine.runningContainers().contains(dev));
        } finally {
            ContainerBindings.shared().unbind(other);
            engine("rm", "-f", dev);
        }
    }

    /** A port only the container has is reached through a relay run with exec (here bash's /dev/tcp). */
    @Test
    void aPortInTheContainerIsReachedFromHere() throws Exception {
        Files.writeString(folder.resolve("Hello.java"), """
                public class Hello {
                    public static void main(String[] a) throws Exception {
                        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                            System.out.println(server.getLocalPort());
                            System.out.flush();
                            try (var s = server.accept()) {
                                String line = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream())).readLine();
                                s.getOutputStream().write(("hello from the container, " + line + "\\n").getBytes());
                            }
                        }
                    }
                }
                """);
        Process server =
                target.start(ExecSpec.of(List.of("java", "Hello.java"), folder, Map.of(), ExecSpec.Locale.PARSE_STABLE)
                        .withLongLived(true));
        try {
            String portLine =
                    new java.io.BufferedReader(new java.io.InputStreamReader(server.getInputStream())).readLine();
            int port = Integer.parseInt(portLine.strip());
            try (var forward = target.forwardLocal("127.0.0.1", port);
                    var socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), forward.localPort())) {
                socket.getOutputStream().write("Ana\n".getBytes());
                socket.getOutputStream().flush();
                String reply =
                        new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())).readLine();
                assertEquals("hello from the container, Ana", reply);
            }
        } finally {
            ProcessRegistry.killTree(server);
        }
    }
}
