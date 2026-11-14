package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Rust debugging through the window, without Cargo or CodeLLDB: a stand-in {@code cargo} (a script that "builds" and
 * prints Cargo's JSON artifact messages) and a stand-in {@code codelldb} (a script starting {@code RelayAdapterMain}
 * on the port it is given, relaying to a {@link FakeDebugAdapter}) receive what F5, Debug Test at Caret and Restart
 * ask. {@code RustDebugProbeFxTest} drives the real ones.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class RustDebugWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;
    private Path cargoLog;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-ins are shell scripts");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("shelf")).toRealPath();
        Files.writeString(root.resolve("Cargo.toml"), "[package]\nname = \"shelf\"\nversion = \"0.1.0\"\n");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/main.rs"), RustDebugProbeFxTest.MAIN);
        cargoLog = fx.configDir.resolve("cargo.log");
        String main = root.resolve("src/main.rs").toString();
        String artifacts = "{\"reason\":\"compiler-artifact\",\"target\":{\"kind\":[\"bin\"],\"name\":\"shelf\","
                + "\"src_path\":\"" + main + "\"},\"profile\":{\"test\":false},\"executable\":\"/built/shelf\"}\n"
                + "{\"reason\":\"compiler-artifact\",\"target\":{\"kind\":[\"bin\"],\"name\":\"shelf\","
                + "\"src_path\":\"" + main + "\"},\"profile\":{\"test\":true},\"executable\":\"/built/shelf-tests\"}\n";
        Path artifactFile = Files.writeString(fx.configDir.resolve("artifacts.json"), artifacts);
        Path cargo = fx.configDir.resolve("cargo");
        Files.writeString(
                cargo,
                "#!/bin/sh\necho \"$@\" >> '" + cargoLog + "'\n"
                        + "case \"$*\" in *--message-format=json*) cat '" + artifactFile + "' ;;"
                        + " *) echo '    Finished dev profile' >&2 ;; esac\n");
        assertTrue(cargo.toFile().setExecutable(true));
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path codelldb = fx.configDir.resolve("codelldb");
        Files.writeString(
                codelldb,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain listen \"$2\" " + adapter.port() + "\n");
        assertTrue(codelldb.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setCargoCommand(cargo.toString());
            fx.shared.getSettings().setRustDebugPath(codelldb.toString());
            fx.shared.getTrustStore().trust(root);
            debug.applySupport();
        });
        await("Rust debugging available", () -> call(() -> debug.debugEffectiveFor("rust")));
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
        if (adapter != null) {
            adapter.close();
        }
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

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(file));
        await(
                file.getFileName() + " in front",
                () -> call(() -> {
                    EditorBuffer b = fx.controller.activeBuffer();
                    return b != null && b.getPath() != null && Files.isSameFile(b.getPath(), file);
                }));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String sessionName() {
        return call(() -> (String) FxTestSupport.field(debug.panel(), "sessionFile"));
    }

    private FakeDebugAdapter.Session nextLaunch() throws Exception {
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        return session;
    }

    @Test
    void f5BuildsWithCargoAndLaunchesTheProgramItBuilt() throws Exception {
        EditorBuffer main = open(root.resolve("src/main.rs"));
        assertTrue(call(main::isBreakpointsEnabled), "a Rust file has the breakpoint gutter");
        FxTestSupport.runOnFx(() -> main.getFocusedArea().moveTo(12, 4)); // in main, no test
        run("debug.start");
        FakeDebugAdapter.Session session = nextLaunch();
        Map<String, Object> launch = session.launchArgs;
        assertEquals("/built/shelf", launch.get("program"), "the binary Cargo reported for src/main.rs");
        assertEquals(List.of("rust"), launch.get("sourceLanguages"));
        assertEquals(root.toString(), launch.get("cwd"), "the package's folder, as cargo run");
        assertEquals("main.rs", sessionName());
        String log = Files.readString(cargoLog);
        assertTrue(log.contains("build\n") || log.lines().anyMatch("build"::equals), "cargo build ran: " + log);
        assertTrue(log.contains("build --message-format=json"), "then listed its executables: " + log);
        await(
                "Rust's panic stop sent",
                () -> session.exceptionBreakpoints.stream()
                        .anyMatch(a -> java.util.Arrays.asList(a.getFilters()).contains("rust_panic")));
    }

    /**
     * A Rust run configuration debugs its file as F5 does — Cargo builds the package, the program for the file runs
     * on CodeLLDB — with its arguments and variables; with no folder of its own it runs in the package's, with one
     * there. No caret picks a test: the program is what runs.
     */
    @Test
    void aRustConfigurationDebugsItsPackagesProgram() throws Exception {
        var cfg = new com.editora.config.RunConfiguration(
                "Shelf",
                "rust",
                root.resolve("src/main.rs").toString(),
                "",
                "",
                "--all 'a b'",
                "",
                "",
                "MODE=dev",
                "",
                "");
        FxTestSupport.runOnFx(() -> debug.debugConfig(cfg));
        Map<String, Object> launch = nextLaunch().launchArgs;
        assertEquals("/built/shelf", launch.get("program"), "the binary Cargo reported for src/main.rs");
        assertEquals(List.of("--all", "a b"), launch.get("args"));
        assertEquals(Map.of("MODE", "dev"), launch.get("env"));
        assertEquals(root.toString(), launch.get("cwd"), "blank: the package's folder, as F5");
        assertEquals("Shelf", sessionName());
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));

        Path data = Files.createDirectories(root.resolve("data"));
        var inData = new com.editora.config.RunConfiguration(
                "Shelf", "rust", root.resolve("src/main.rs").toString(), "", "", "", "", data.toString(), "", "", "");
        FxTestSupport.runOnFx(() -> debug.debugConfig(inData));
        assertEquals(data.toString(), nextLaunch().launchArgs.get("cwd"));
    }

    @Test
    void debugTestAtCaretRunsTheTestBinaryFilteredToItAndRestartRepeatsIt() throws Exception {
        EditorBuffer main = open(root.resolve("src/main.rs"));
        FxTestSupport.runOnFx(() -> {
            var area = main.getFocusedArea();
            area.replaceText(area.getLength(), area.getLength(), "\n// edited\n");
            area.moveTo(32, 4); // in squares
        });
        run("test.debugAtCaret");
        FakeDebugAdapter.Session first = nextLaunch();
        assertEquals("/built/shelf-tests", first.launchArgs.get("program"), "the test binary");
        assertEquals(List.of("tests::squares", "--exact"), first.launchArgs.get("args"));
        assertEquals("squares", sessionName());
        assertFalse(call(main::isDirty), "the package's Rust files are saved first: Cargo builds from disk");
        assertTrue(Files.readString(cargoLog).contains("test --no-run"));

        FxTestSupport.runOnFx(() -> main.getFocusedArea().moveTo(12, 4));
        FxTestSupport.runOnFx(debug::restart);
        FakeDebugAdapter.Session again = nextLaunch();
        assertEquals(List.of("tests::squares", "--exact"), again.launchArgs.get("args"), "Restart repeats the test");
        assertFalse(call(() -> debug.debugRustTestAtCaret(main)), "the caret is in no test now");
    }

    @Test
    void aRustFileOutsideACargoPackageSaysSo() throws Exception {
        Path loose = Files.writeString(fx.configDir.resolve("loose.rs"), "fn main() {}\n");
        open(loose);
        run("debug.start");
        await(
                "the status",
                () -> tr("status.debug.rustNoPackage", "loose.rs").equals(call(() -> {
                    StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
                    return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo")
                            .getText();
                })));
        assertFalse(call(manager::isActive));
    }

    @Test
    void whyRustCannotBeDebuggedIsSaid() throws Exception {
        try {
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setRustDebugEnabled(false));
            assertEquals(tr("status.debug.rustOff"), call(() -> debug.unavailableMessage("rust", null)));
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setRustDebugEnabled(true);
                fx.shared
                        .getSettings()
                        .setRustDebugPath(fx.configDir.resolve("no-codelldb").toString());
                debug.applySupport();
            });
            await("CodeLLDB not found", () -> !call(() -> debug.debugEffectiveFor("rust")));
            assertEquals(tr("status.debug.codelldbMissing"), call(() -> debug.unavailableMessage("rust", null)));
        } finally {
            FxTestSupport.runOnFx(() -> {
                fx.shared
                        .getSettings()
                        .setRustDebugPath(fx.configDir.resolve("codelldb").toString());
                debug.applySupport();
            });
            await("Rust debugging available again", () -> call(() -> debug.debugEffectiveFor("rust")));
        }
    }

    /** A Rust file is never runnable (no Run File), so the editor menu gives Debug File a place of its own. */
    @Test
    void theEditorMenuOffersDebugFile() throws Exception {
        EditorBuffer b = open(root.resolve("src/main.rs"));
        List<javafx.scene.control.MenuItem> items = FxTestSupport.callOnFx(() -> debug.editorMenuItems(b));
        javafx.scene.control.MenuItem debugFile = items.stream()
                .filter(i -> tr("editmenu.debugFile").equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(items.stream()
                        .map(javafx.scene.control.MenuItem::getText)
                        .toList()));
        FxTestSupport.runOnFx(debugFile::fire);
        FakeDebugAdapter.Session session = nextLaunch();
        assertEquals("/built/shelf", session.launchArgs.get("program"), "the program Cargo built, as F5 debugs it");
        List<String> during = FxTestSupport.callOnFx(() -> debug.editorMenuItems(b).stream()
                .map(javafx.scene.control.MenuItem::getText)
                .toList());
        assertFalse(during.contains(tr("editmenu.debugFile")), "not again while it is debugged: " + during);
    }
}
