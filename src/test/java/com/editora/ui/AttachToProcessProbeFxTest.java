package com.editora.ui;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.dap.LocalProcesses;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against real programs and debuggers, in a real window: a JVM started with its JDWP agent listening
 * (jdtls + java-debug), a Python started under {@code debugpy --listen}, and a Node.js started with {@code --inspect}
 * (vscode-js-debug) are each found by Attach to Process, and attaching to one stops it at a breakpoint in its own
 * file; Stop detaches and leaves the program running. Each language skips when its tools are not installed. Run it
 * with {@code ./mvnw test -Dtest=AttachToProcessProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class AttachToProcessProbeFxTest {

    private static final String JAVA = """
            public class Main {
                public static void main(String[] args) throws Exception {
                    int count = 0;
                    while (true) {
                        count++;
                        Thread.sleep(100);
                    }
                }
            }
            """;

    private static final int JAVA_LINE = 4; // count++;

    private static final String PYTHON = """
            import time
            count = 0
            while True:
                count += 1
                time.sleep(0.1)
            """;

    private static final int PYTHON_LINE = 3; // count += 1

    private static final String NODE = """
            let count = 0;
            setInterval(() => {
                count++;
            }, 100);
            """;

    private static final int NODE_LINE = 2; // count++;

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aJvmListeningForADebuggerIsAttachedTo(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = HOME.resolve(".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", HOME).orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null, "jdtls and java-debug");
        Path file = write(dir, "Main.java", JAVA);
        int port = freePort();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process program = start(new ProcessBuilder(
                java,
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + port,
                file.toString()));
        attachAndStop(dir, file, JAVA_LINE, program, "127.0.0.1:" + port, settings -> {
            settings.setLspSupport(true);
            settings.setJavaLspEnabled(true);
            settings.setJavaLspCommand(jdtls.toString());
            settings.setJavaDebugPluginPath(plugin.toString());
        });
    }

    @Test
    void aPythonListeningUnderDebugpyIsAttachedTo(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path bundled = HOME.resolve(".editora/plugins/dap/python");
        assumeTrue(runs(debugpyEnv(new ProcessBuilder("python3", "-c", "import debugpy"), bundled)), "debugpy");
        Path file = write(dir, "loop.py", PYTHON);
        int port = freePort();
        Process program = start(debugpyEnv(
                new ProcessBuilder("python3", "-m", "debugpy", "--listen", "127.0.0.1:" + port, file.toString()),
                bundled));
        attachAndStop(dir, file, PYTHON_LINE, program, "127.0.0.1:" + port, settings -> {});
    }

    @Test
    void aNodeWithItsInspectorOnIsAttachedTo(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(runs(new ProcessBuilder("node", "--version")), "node");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "vscode-js-debug");
        Path file = write(dir, "loop.js", NODE);
        int port = freePort();
        Process program = start(new ProcessBuilder("node", "--inspect=127.0.0.1:" + port, file.toString()));
        attachAndStop(dir, file, NODE_LINE, program, "127.0.0.1:" + port, settings -> settings.setJsDebugEnabled(true));
    }

    /**
     * Opens {@code file} in a window, finds {@code program} listening at {@code address}, attaches to it from its row,
     * waits for the stop at {@code line}, then Stops and checks the program still runs.
     */
    private static void attachAndStop(
            Path dir,
            Path file,
            int line,
            Process program,
            String address,
            java.util.function.Consumer<com.editora.config.Settings> configure)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        configure.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            if (file.toString().endsWith(".java")) {
                await("Java debugging available", 60, () -> call(debug::debugEffective));
            } else if (file.toString().endsWith(".js")) {
                await("JavaScript debugging available", 60, () -> call(() -> debug.debugEffectiveFor("javascript")));
            }
            LocalProcesses.Target target = awaitTarget(program.pid());
            assertEquals(address, target.address());
            EditorBuffer b = buffer(c, file);
            FxTestSupport.runOnFx(() -> {
                b.toggleBreakpoint(line);
                debug.attachToProcess(target);
            });
            awaitStop(dap, line, debug);
            FxTestSupport.runOnFx(dap::stop);
            await("detached", 20, () -> !call(dap::isActive));
            Thread.sleep(500);
            assertTrue(program.isAlive(), "Stop detaches: the program runs on");
        } finally {
            fx.dispose();
            program.destroyForcibly();
            program.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static Path write(Path dir, String name, String text) throws Exception {
        Path loose = Files.createDirectories(dir.resolve("loose"));
        Path file = loose.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    private static ProcessBuilder debugpyEnv(ProcessBuilder pb, Path bundled) {
        if (Files.isDirectory(bundled.resolve("debugpy"))) {
            pb.environment().put("PYTHONPATH", bundled.toString()); // Editora's own copy, when installed
        }
        return pb;
    }

    private static boolean runs(ProcessBuilder pb) {
        try {
            Process p = pb.redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Process start(ProcessBuilder pb) throws Exception {
        return pb.redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static EditorBuffer buffer(MainController c, Path file) throws Exception {
        EditorBuffer[] found = new EditorBuffer[1];
        await(
                "the tab of " + file.getFileName(),
                20,
                () -> call(() -> {
                    found[0] = MainController.bufferOf(c.tabForPath(file));
                    return found[0] != null;
                }));
        return found[0];
    }

    private static LocalProcesses.Target awaitTarget(long pid) throws InterruptedException {
        LocalProcesses.Target[] found = new LocalProcesses.Target[1];
        await("process " + pid + " listening", 30, () -> {
            for (LocalProcesses.Target t : LocalProcesses.scan()) {
                if (t.pid() == pid && t.attachable()) {
                    found[0] = t;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private interface Check<T> {
        T get() throws Exception;
    }

    private static <T> T call(Check<T> check) {
        try {
            return FxTestSupport.callOnFx(check::get);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    private static void awaitStop(DapManager dap, int line, DebugCoordinator debug) throws InterruptedException {
        try {
            await(
                    "a stop on line " + (line + 1),
                    90,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().get(0).line() == line));
        } catch (AssertionError e) {
            String console = call(
                    () -> ((org.fxmisc.richtext.CodeArea) FxTestSupport.field(debug.panel(), "console")).getText());
            throw new AssertionError(e.getMessage() + " (state " + call(dap::state) + "; console: " + console + ")", e);
        }
    }
}
