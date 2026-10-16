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
}
