package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import com.editora.lsp.LspManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in checks against the real JDT LS + java-debug ({@code -Dlsp.probe=true}, with both installed under
 * {@code ~/.editora/plugins}) of what the fake adapter cannot show: a Maven project's main class launched on the
 * classpath jdtls resolves, a program Editora starts itself ({@code runInTerminal}) reading what is typed to it,
 * and that neither Stop nor closing the window leaves a debuggee or a compile folder behind.
 */
@Tag("probe")
@Tag("fx")
class JavaDebugRealAdapterProbeFxTest {

    private static final long LONG = 120;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Path jdtls() {
        return Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
    }

    private static Path plugin() {
        return DebugAdapterLocator.locate("", Path.of(System.getProperty("user.home")))
                .orElse(null);
    }

    private static void assumeRealAdapter() {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(Runtime.version().feature() >= 25);
        assumeTrue(Files.isExecutable(jdtls()) && plugin() != null, "jdtls and java-debug are installed");
    }

    private static String javaExec() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
    }

    /** The processes below this JVM whose command line names {@code marker} (a debuggee's main class). */
    private static List<ProcessHandle> processesNaming(String marker) {
        return ProcessHandle.current()
                .descendants()
                .filter(p -> p.info().commandLine().orElse("").contains(marker))
                .toList();
    }

    private static void await(String what, long seconds, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, what);
            Thread.sleep(50); // polling a process table / the file system: there is nothing to wait on
        }
    }

    /** A DapManager on a real jdtls, without a window, recording what reaches its listener. */
    private static final class Harness implements AutoCloseable {
        final LspManager lsp;
        final DapManager dap;
        final CountDownLatch ready = new CountDownLatch(1);
        final LinkedBlockingQueue<List<DapModels.StackFrameInfo>> stops = new LinkedBlockingQueue<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final StringBuffer output = new StringBuffer();
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        volatile boolean input;
        volatile DapManager.State state = DapManager.State.INACTIVE;

        private final Path dir;

        Harness(Path dir) {
            this.dir = dir;
            lsp = new LspManager((p, ds) -> {}, (type, message) -> {
                if ("ServiceReady".equals(type)) ready.countDown();
            });
            dap = new DapManager(lsp);
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State s) {
                    state = s;
                }

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                    stops.add(frames);
                }

                @Override
                public void onOutput(String text, String category) {
                    output.append(text);
                }

                @Override
                public void onError(String message) {
                    errors.add(message);
                }

                @Override
                public void onProgramInput(boolean available) {
                    input = available;
                }

                @Override
                public void onProgramExit(int code) {
                    exit.complete(code);
                }
            });
        }

        void open(Path dir, Path file, Path root, List<DapModels.FileBreakpoints> breakpoints) throws Exception {
            String source = Files.readString(file);
            FxTestSupport.runOnFx(() -> {
                dap.configure(true, plugin().toString());
                lsp.setDebugBundles(dap.bundlePaths());
                lsp.setJdtlsWorkspaceBase(dir.resolve("jdtls-workspaces"));
                lsp.configure(true, Map.of("java", jdtls().toString()));
                lsp.openDocument(file, root, "java", source);
                dap.setBreakpointsSupplier(() -> breakpoints);
            });
            assertTrue(ready.await(LONG, TimeUnit.SECONDS), "JDT LS did not become ready");
        }

        @Override
        public void close() throws Exception {
            // jdtls (and anything it started) keeps writing its workspace under dir until it has exited.
            List<ProcessHandle> servers = ProcessHandle.current()
                    .descendants()
                    .filter(p -> p.info().commandLine().orElse("").contains(dir.toString()))
                    .toList();
            FxTestSupport.runOnFx(() -> {
                dap.shutdown();
                lsp.shutdownAll();
            });
            for (ProcessHandle server : servers) {
                server.onExit().get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void aMavenProjectsMainClassRunsOnTheResolvedClassPathAndStopsAtItsBreakpoint(@TempDir Path dir) throws Exception {
        assumeRealAdapter();
        Path root = Files.createDirectory(dir.resolve("app"));
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>probe</groupId>
                  <artifactId>probe-app</artifactId>
                  <version>1.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """);
        Path file = root.resolve("src/main/java/demo/ProbeApp.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package demo;

                public class ProbeApp {
                    public static void main(String[] args) {
                        System.out.println("cp=" + System.getProperty("java.class.path"));
                        int answer = 41;
                        answer++;
                        System.out.println("answer=" + answer);
                    }
                }
                """);
        // A built project (what A2's build step will produce): jdtls runs with autobuild off.
        Path classes = Files.createDirectories(root.resolve("target/classes"));
        Process javac = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                        "-g",
                        "--release",
                        "21",
                        "-d",
                        classes.toString(),
                        file.toString())
                .inheritIO()
                .start();
        assertEquals(0, javac.waitFor());
        int breakpointLine = 6; // answer++;
        try (Harness h = new Harness(dir)) {
            h.open(
                    dir,
                    file,
                    root,
                    List.of(new DapModels.FileBreakpoints(
                            file, List.of(new DapModels.LineBreakpoint(breakpointLine, null, null)))));
            // The Maven import runs after the server is ready: wait until jdtls lists the project's main class.
            List<DapManager.MainClassOption> options = List.of();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LONG);
            while (options.isEmpty() && System.nanoTime() < deadline) {
                CompletableFuture<List<DapManager.MainClassOption>> found = new CompletableFuture<>();
                FxTestSupport.runOnFx(() -> h.dap.resolveMainClasses(file, found::complete));
                options = found.get(LONG, TimeUnit.SECONDS);
                if (options.isEmpty()) Thread.sleep(500);
            }
            assertFalse(options.isEmpty(), "jdtls never listed the Maven project's main class");
            DapManager.MainClassOption main = options.stream()
                    .filter(o -> "demo.ProbeApp".equals(o.className()))
                    .findFirst()
                    .orElseThrow();

            FxTestSupport.runOnFx(() -> h.dap.startLaunchMainClass(file, main, root, javaExec()));

            List<DapModels.StackFrameInfo> frames = h.stops.poll(LONG, TimeUnit.SECONDS);
            assertNotNull(frames, "the breakpoint was not hit; errors=" + h.errors + " output=" + h.output);
            assertEquals(breakpointLine, frames.getFirst().line());
            assertEquals(file.toRealPath(), frames.getFirst().file().toRealPath());
            await("the program's first line never reached the console", 30, () -> h.output.indexOf("cp=") >= 0);
            assertTrue(
                    h.output.toString().contains(classes.toRealPath().toString())
                            || h.output.toString().contains(classes.toString()),
                    "the program ran on the project's target/classes: " + h.output);
            assertNull(
                    FxTestSupport.callOnFx(() -> FxTestSupport.field(h.dap, "compilationDirectory")),
                    "a project class is not compiled by Editora");
        } finally {
            await(
                    "a debuggee outlived its session",
                    30,
                    () -> processesNaming("demo.ProbeApp").isEmpty());
        }
    }

    @Test
    void aProgramEditoraStartsItselfReadsWhatIsTypedToItAndEndsWithItsExitCode(@TempDir Path dir) throws Exception {
        assumeRealAdapter();
        Path root = Files.createDirectory(dir.resolve("loose"));
        Path file = root.resolve("EchoProbe.java");
        Files.writeString(file, """
                public class EchoProbe {
                    public static void main(String[] args) throws Exception {
                        var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                        System.out.println("ready");
                        System.out.println("got:" + in.readLine());
                    }
                }
                """);
        try (Harness h = new Harness(dir)) {
            h.open(dir, file, root, List.of());
            FxTestSupport.runOnFx(() -> {
                h.dap.setProgramConsole(true); // console: integratedTerminal → the adapter's runInTerminal
                h.dap.startLaunch(file, "java", (o, c) -> c.accept(o.getFirst()), javaExec(), null);
            });
            await("the program never got a standard input; errors=" + h.errors, LONG, () -> h.input);
            await("the program's own output never arrived: " + h.output, 30, () -> h.output.indexOf("ready") >= 0);
            assertFalse(processesNaming("EchoProbe").isEmpty(), "the debuggee is Editora's own child");

            assertTrue(FxTestSupport.callOnFx(() -> h.dap.sendProgramInput("hello probe")));

            assertEquals(0, h.exit.get(30, TimeUnit.SECONDS), "the program ended by itself");
            assertTrue(h.output.toString().contains("got:hello probe"), h.output.toString());
            await("the session never ended", 30, () -> h.state == DapManager.State.INACTIVE);
        } finally {
            await(
                    "a debuggee outlived its session",
                    30,
                    () -> processesNaming("EchoProbe").isEmpty());
        }
    }

    @Test
    void neitherStopNorClosingTheWindowLeavesADebuggeeOrACompileFolder(@TempDir Path dir) throws Exception {
        assumeRealAdapter();
        Path project = Files.createDirectory(dir.resolve("loose"));
        Path file = project.resolve("LingerProbe.java");
        Files.writeString(file, """
                public class LingerProbe {
                    public static void main(String[] args) throws Exception {
                        while (true) {
                            Thread.sleep(200);
                        }
                    }
                }
                """);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setLspSupport(true);
        seed.getSettings().setJavaLspEnabled(true);
        seed.getSettings().setJavaLspCommand(jdtls().toString());
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setJavaDebugPluginPath(plugin().toString());
        assertTrue(seed.saveSettings());
        seed.shutdown();

        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        List<ProcessHandle> servers = new CopyOnWriteArrayList<>();
        boolean disposed = false;
        try {
            DebugCoordinator debug = FxTestSupport.field(fx.controller, "debugCoordinator");
            DapManager dap = FxTestSupport.field(fx.controller, "dapManager");
            await("Java debugging never became available", LONG, () -> {
                try {
                    return FxTestSupport.callOnFx(debug::debugEffective);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });

            Path first = startAndAwaitDebuggee(fx, dap);
            FxTestSupport.runOnFx(dap::stop);
            await(
                    "Stop left the debuggee running",
                    30,
                    () -> processesNaming("LingerProbe").isEmpty());
            await("Stop left the compile folder " + first, 30, () -> Files.notExists(first));

            Path second = startAndAwaitDebuggee(fx, dap);
            servers.addAll(ProcessHandle.current()
                    .descendants()
                    .filter(p -> p.info().commandLine().orElse("").contains(dir.toString()))
                    .toList());
            fx.dispose();
            disposed = true;
            await(
                    "closing the window left the debuggee running",
                    30,
                    () -> processesNaming("LingerProbe").isEmpty());
            await("closing the window left the compile folder " + second, 30, () -> Files.notExists(second));
        } finally {
            if (!disposed) {
                fx.dispose();
            }
            for (ProcessHandle p : processesNaming("LingerProbe")) {
                p.destroyForcibly();
            }
            for (ProcessHandle server : servers) {
                server.onExit().get(30, TimeUnit.SECONDS); // jdtls writes its workspace under dir until it exits
            }
        }
    }

    /** Runs {@code debug.start} and waits for the debuggee and its compile folder; returns the folder. */
    private static Path startAndAwaitDebuggee(FxWindowFixture fx, DapManager dap) throws Exception {
        FxTestSupport.runOnFx(() -> {
            CommandRegistry commands = FxTestSupport.field(fx.controller, "registry");
            commands.run("debug.start");
        });
        await(
                "the debuggee never started",
                LONG,
                () -> !processesNaming("LingerProbe").isEmpty());
        Path[] folder = new Path[1];
        await("the loose file was not compiled into a folder of its own", 30, () -> {
            try {
                folder[0] = FxTestSupport.callOnFx(() -> FxTestSupport.field(dap, "compilationDirectory"));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return folder[0] != null;
        });
        assertTrue(folder[0].getFileName().toString().startsWith("editora-dap-"), folder[0].toString());
        return folder[0];
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
