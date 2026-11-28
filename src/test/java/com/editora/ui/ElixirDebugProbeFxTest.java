package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Elixir debugging and Run on the real ElixirLS, in a window: Debug Test at Caret runs the ExUnit test
 * at the caret alone and stops in the project's module with its values; F5 on a script of the project runs it under
 * {@code mix run} and stops; F5 on a script with no Mix project says why it is not debugged; Run File on a test file
 * runs its tests. Run with {@code ./mvnw test -Dtest=ElixirDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs
 * {@code elixir} and {@code mix} on the PATH and ElixirLS (Editora's install, or {@code -Delixirls.path=…}); ElixirLS's
 * first start compiles it, which can take minutes.
 */
@Tag("probe")
@Tag("fx")
class ElixirDebugProbeFxTest {

    private static final String MODULE = """
            defmodule Calc do
              def add(a, b) do
                sum = a + b
                sum
              end
            end
            """;

    /** {@code sum}, the line after it is computed (0-based). */
    private static final int MODULE_LINE = 3;

    private static final String TEST = """
            defmodule CalcTest do
              use ExUnit.Case

              test "adds" do
                assert Calc.add(2, 2) == 4
              end

              test "adds more" do
                assert Calc.add(2, 3) == 5
              end
            end
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static String assumeElixirLs() {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        for (String tool : List.of("elixir", "mix")) {
            assumeTrue(
                    Path.of(ProcessRunner.resolveExecutable(List.of(tool)).getFirst())
                            .isAbsolute(),
                    "needs " + tool);
        }
        String configured = System.getProperty("elixirls.path", "");
        assumeTrue(DapManager.locateExternal(DebugLanguages.ELIXIR, configured) != null, "needs ElixirLS");
        return configured;
    }

    /** A Mix project made by {@code mix new}, with {@link #MODULE} and {@link #TEST}. */
    private static Path project(Path dir) throws Exception {
        Process mix = new ProcessBuilder(ProcessRunner.resolveExecutable(List.of("mix", "new", "calc")))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(mix.waitFor(120, TimeUnit.SECONDS) && mix.exitValue() == 0, "mix new made the project");
        Path root = dir.resolve("calc").toRealPath();
        Files.writeString(root.resolve("lib/calc.ex"), MODULE);
        Files.writeString(root.resolve("test/calc_test.exs"), TEST);
        return root;
    }

    @Test
    void debugTestAtCaretRunsThatTestAndStopsInTheModule(@TempDir Path dir) throws Exception {
        String configured = assumeElixirLs();
        Path root = project(dir);
        Window w = open(dir, configured, root.resolve("lib/calc.ex"), root.resolve("test/calc_test.exs"));
        try {
            EditorBuffer module = FxTestSupport.callOnFx(() -> FxTestSupport.<DebugCoordinator.Ops>field(w.debug, "ops")
                    .bufferForPath(root.resolve("lib/calc.ex")));
            FxTestSupport.runOnFx(() -> {
                module.toggleBreakpoint(MODULE_LINE);
                w.b.getFocusedArea().moveTo(8, 0); // in "adds more"
            });
            FxTestSupport.runOnFx(() -> w.c.testNavigation.runTestAtCaret(false, true));
            awaitStop(w, MODULE_LINE, "calc.ex");
            awaitRow(w, "b", "3"); // add(2, 3): the test at the caret
            int stops = call(() -> w.debug.s().stopSerial);
            FxTestSupport.runOnFx(w.dap::resume);
            // The breakpoint stays: had the other test run too, it would have stopped again. ElixirLS does not pass
            // ExUnit's report to the console, so the end without a second stop is what says the test ran alone.
            await("the test's end", 300, () -> !call(w.dap::isActive));
            assertEquals(stops, (int) call(() -> w.debug.s().stopSerial), "no other stop: that test alone ran");
        } finally {
            w.close();
        }
    }

    @Test
    void f5OnAScriptOfTheProjectRunsItUnderMixRun(@TempDir Path dir) throws Exception {
        String configured = assumeElixirLs();
        Path root = project(dir);
        Files.createDirectories(root.resolve("scripts"));
        Path script = Files.writeString(root.resolve("scripts/try.exs"), "IO.puts(Calc.add(40, 2))\n");
        Window w = open(dir, configured, root.resolve("lib/calc.ex"), script);
        try {
            EditorBuffer module = FxTestSupport.callOnFx(() -> FxTestSupport.<DebugCoordinator.Ops>field(w.debug, "ops")
                    .bufferForPath(root.resolve("lib/calc.ex")));
            FxTestSupport.runOnFx(() -> module.toggleBreakpoint(MODULE_LINE));
            run(w, "debug.start");
            awaitStop(w, MODULE_LINE, "calc.ex");
            awaitRow(w, "sum", "42");
            FxTestSupport.runOnFx(w.dap::resume);
            await("the script's end", 300, () -> !call(w.dap::isActive));
        } finally {
            w.close();
        }
    }

    /**
     * A Phoenix app made by {@code mix phx.new} (no Ecto, no assets) with its dependencies fetched, its server on
     * {@code port}; skips without the phx_new archive or the network {@code mix deps.get} needs.
     */
    private static Path phoenix(Path dir, int port) throws Exception {
        Process phx = new ProcessBuilder(ProcessRunner.resolveExecutable(List.of(
                        "mix",
                        "phx.new",
                        "hello",
                        "--no-ecto",
                        "--no-assets",
                        "--no-tailwind",
                        "--no-esbuild",
                        "--no-gettext",
                        "--no-mailer",
                        "--no-dashboard",
                        "--install")))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("phx.new.log").toFile())
                .start();
        boolean made = phx.waitFor(300, TimeUnit.SECONDS) && phx.exitValue() == 0;
        assumeTrue(
                made && Files.isDirectory(dir.resolve("hello/deps/phoenix")),
                "needs the phx_new archive (mix archive.install hex phx_new) and Hex: "
                        + Files.readString(dir.resolve("phx.new.log")));
        Path root = dir.resolve("hello").toRealPath();
        Path runtime = root.resolve("config/runtime.exs");
        String text = Files.readString(runtime);
        assertTrue(text.contains("System.get_env(\"PORT\", \"4000\")"), "the port is read where mix phx.new puts it");
        Files.writeString(runtime, text.replace("System.get_env(\"PORT\", \"4000\")", "\"" + port + "\""));
        return root;
    }

    /** {@code GET url}'s status, or -1 when nothing answers. */
    private static int get(String url, int seconds) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    java.net.URI.create(url).toURL().openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(seconds * 1000);
            return c.getResponseCode();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    @Test
    void f5OnAPhoenixModuleRunsItsServerAndARequestStopsInTheController(@TempDir Path dir) throws Exception {
        String configured = assumeElixirLs();
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        Path root = phoenix(dir, port);
        Path controller = root.resolve("lib/hello_web/controllers/page_controller.ex");
        Window w = open(dir, configured, controller);
        String home = "http://127.0.0.1:" + port + "/";
        try {
            FxTestSupport.runOnFx(() -> w.b.toggleBreakpoint(4)); // render(conn, :home)
            run(w, "debug.start");
            await("the session started", 60, () -> call(w.dap::isActive));
            // The server runs on after phx.server returns: the session must still be there to stop.
            await("the server up", 600, () -> get(home + "nothing-here", 5) == 404 || !call(w.dap::isActive));
            assertTrue(
                    call(w.dap::isActive),
                    "the session runs on with the server:\n" + console(w) + "\nstatus: " + status(w));
            java.util.concurrent.CompletableFuture<Integer> page =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> get(home, 120));
            awaitStop(w, 4, "page_controller.ex");
            await(
                    "conn in Variables",
                    60,
                    () -> call(() -> {
                        javafx.scene.control.TreeView<DebugPanel.VarRow> tree =
                                FxTestSupport.field(w.debug.panel(), "variables");
                        return tree.getRoot() != null && named(tree.getRoot(), "conn");
                    }));
            FxTestSupport.runOnFx(w.dap::resume);
            assertEquals(200, (int) page.get(120, TimeUnit.SECONDS), "the page, once resumed");
            FxTestSupport.runOnFx(w.dap::stop);
            await("the session's end", 60, () -> !call(w.dap::isActive));
            await("the server's end with it", 60, () -> get(home, 2) == -1);
        } finally {
            w.close();
        }
    }

    @Test
    void aScriptWithNoMixProjectIsRunNotDebugged(@TempDir Path dir) throws Exception {
        String configured = assumeElixirLs();
        Path script = Files.writeString(
                Files.createDirectories(dir.resolve("loose")).toRealPath().resolve("hello.exs"),
                "IO.puts(\"hello \" <> \"probe\")\n");
        Window w = open(dir, configured, script);
        try {
            run(w, "debug.start");
            await(
                    "said",
                    60,
                    () -> call(() -> status(w).contains(com.editora.i18n.Messages.tr("status.debug.elixirNeedsMix"))));
            run(w, "file.run");
            await("the script's output", 120, () -> runOutput(w).contains("hello probe"));
        } finally {
            w.close();
        }
    }

    @Test
    void runFileOnATestFileRunsItsTests(@TempDir Path dir) throws Exception {
        String configured = assumeElixirLs();
        Path root = project(dir);
        Window w = open(dir, configured, root.resolve("test/calc_test.exs"));
        try {
            await("a ▶ for the test file", 30, () -> call(w.b::isRunnable));
            run(w, "file.run");
            await("mix test's report", 300, () -> runOutput(w).contains("2 tests, 0 failures"));
        } finally {
            w.close();
        }
    }

    private record Window(
            FxWindowFixture fx, MainController c, DebugCoordinator debug, DapManager dap, EditorBuffer b) {
        void close() throws Exception {
            FxTestSupport.runOnFx(dap::stop);
            fx.dispose();
        }
    }

    /** A window with {@code files} open, the last in front, Elixir debugging on ElixirLS. */
    private static Window open(Path dir, String configured, Path... files) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setElixirDebugPath(configured);
        seed.getTrustStore().trust(dir.toRealPath()); // the trust question (Mix compiles the project), answered
        assertTrue(seed.saveSettings());
        seed.saveTrust();
        seed.shutdown();
        List<MainController.OpenTarget> targets = java.util.Arrays.stream(files)
                .map(p -> new MainController.OpenTarget(p, 0, 0))
                .toList();
        FxWindowFixture fx = FxWindowFixture.create(configDir, false, false, false, targets, true, c -> {});
        MainController c = fx.controller;
        DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
        DapManager dap = FxTestSupport.field(c, "dapManager");
        await("Elixir debugging available", 60, () -> call(() -> debug.debugEffectiveFor("elixir")));
        Path front = files[files.length - 1];
        FxTestSupport.runOnFx(() -> c.fileWorkflows.openPath(front));
        await(
                "the file in front",
                30,
                () -> call(() -> c.activeBuffer() != null
                        && front.equals(c.activeBuffer().getPath())));
        return new Window(fx, c, debug, dap, FxTestSupport.callOnFx(c::activeBuffer));
    }

    private static void run(Window w, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(w.c, "registry")).run(command));
    }

    private static String console(Window w) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(w.debug.panel(), "console")
                .getText());
    }

    private static String runOutput(Window w) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(w.c.runCoordinator.panel(), "output")
                .getText());
    }

    private static String status(Window w) {
        MessageLog log = FxTestSupport.field(FxTestSupport.field(w.c, "statusBar"), "messageLog");
        return String.join(
                " | ", log.entries().stream().map(MessageLog.Entry::text).toList());
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
        Await.until(condition, what, Duration.ofSeconds(seconds));
    }

    /** A row {@code name} of the stop's Variables showing {@code value}. */
    private static void awaitRow(Window w, String name, String value) throws InterruptedException {
        await(
                name + " = " + value,
                60,
                () -> call(() -> {
                    javafx.scene.control.TreeView<DebugPanel.VarRow> tree =
                            FxTestSupport.field(w.debug.panel(), "variables");
                    return tree.getRoot() != null && find(tree.getRoot(), name, value);
                }));
    }

    private static boolean named(javafx.scene.control.TreeItem<DebugPanel.VarRow> item, String name) {
        if (item.getValue() != null && name.equals(item.getValue().name())) {
            return true;
        }
        return item.getChildren().stream().anyMatch(child -> named(child, name));
    }

    private static boolean find(javafx.scene.control.TreeItem<DebugPanel.VarRow> item, String name, String value) {
        DebugPanel.VarRow row = item.getValue();
        if (row != null && name.equals(row.name()) && value.equals(row.value())) {
            return true;
        }
        for (var child : item.getChildren()) {
            if (find(child, name, value)) {
                return true;
            }
        }
        return false;
    }

    private static void awaitStop(Window w, int line, String file) throws InterruptedException {
        String debugError = com.editora.i18n.Messages.tr("status.debug.error", "");
        try {
            await(
                    "a stop at " + file + ":" + (line + 1),
                    // ElixirLS's first start compiles it (under a minute); kept under the test's 5-minute limit, so a
                    // stop that never comes reports the state, the console and the status below, not a bare timeout
                    240,
                    () -> call(() -> status(w).contains(debugError) // a session that failed: no stop will come
                            || (w.dap.state() == DapManager.State.SUSPENDED
                                    && !w.debug.panel().callStack().isEmpty()
                                    && w.debug.panel().callStack().get(0).line() == line
                                    && w.debug.panel().callStack().get(0).file() != null
                                    && w.debug.panel().callStack().get(0).file().endsWith(file))));
            if (call(() -> status(w).contains(debugError))) {
                throw new AssertionError("the debug session failed instead of stopping at " + file + ":" + (line + 1));
            }
        } catch (AssertionError e) {
            String top = call(() -> w.debug.panel().callStack().isEmpty()
                    ? "no frames"
                    : w.debug.panel().callStack().get(0).name() + " at "
                            + w.debug.panel().callStack().get(0).file() + ":"
                            + (w.debug.panel().callStack().get(0).line() + 1));
            throw new AssertionError(
                    e.getMessage() + " (" + call(w.dap::state) + ", " + top + ")\n" + console(w) + "\nstatus: "
                            + status(w),
                    e);
        }
    }
}
