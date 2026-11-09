package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.dap.LocalProcesses;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Go debugging through the window, without Delve: a stand-in {@code dlv} (a script starting {@code RelayAdapterMain},
 * which announces a port as Delve does and relays to a {@link FakeDebugAdapter}) receives what F5, Debug Test at Caret,
 * Restart and Attach to Process ask of Delve. {@code GoDebugProbeFxTest} drives the real one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoDebugWindowFxTest {

    private static final String MAIN = "package main\n\nfunc main() {\n\tx := 1\n\t_ = x\n}\n";
    private static final String TESTS = """
            package main

            import "testing"

            func TestFirst(t *testing.T) {
            \tt.Log("first")
            }

            func TestSecond(t *testing.T) {
            \tt.Log("second")
            }
            """;

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;
    private Path fakeDlv;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in dlv is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("gomod"));
        Files.writeString(root.resolve("go.mod"), "module example.com/m\n\ngo 1.22\n");
        Files.writeString(root.resolve("main.go"), MAIN);
        Files.writeString(root.resolve("main_test.go"), TESTS);
        String jvm = ProcessHandle.current().info().command().orElse("java");
        fakeDlv = fx.configDir.resolve("dlv");
        Files.writeString(
                fakeDlv,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain announce " + adapter.port() + "\n");
        assertTrue(fakeDlv.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setGoDebugPath(fakeDlv.toString());
            debug.applySupport();
        });
        await("Go debugging available", () -> call(() -> debug.debugEffectiveFor("go")));
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
        long end = System.nanoTime() + 20_000_000_000L;
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

    /** The next session's launch (or attach) arguments, once its breakpoints are in. */
    private FakeDebugAdapter.Session nextSession(String request) throws Exception {
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest(request);
        return session;
    }

    @Test
    void f5DebugsTheFilesPackageWithItsBuildInTheTemporaryFolder() throws Exception {
        EditorBuffer main = open(root.resolve("main.go"));
        assertTrue(call(main::isBreakpointsEnabled), "a Go file has the breakpoint gutter");
        FxTestSupport.runOnFx(() -> main.toggleBreakpoint(3));
        assertFalse(call(() -> debug.debugGoTestAtCaret(main)), "main.go is no test file");
        run("debug.start");
        FakeDebugAdapter.Session session = nextSession("launch");
        Map<String, Object> launch = session.launchArgs;
        assertEquals("debug", launch.get("mode"));
        assertEquals(root.toAbsolutePath().toString(), launch.get("program"), "the package");
        assertTrue(
                Path.of((String) launch.get("output")).startsWith(Path.of(System.getProperty("java.io.tmpdir"))),
                "Delve builds into the temporary folder: " + launch.get("output"));
        assertEquals("main.go", sessionName());
        await(
                "the breakpoint sent",
                () -> session.breakpoints.stream()
                        .anyMatch(a -> a.getSource().getPath().endsWith("main.go")));
        FxTestSupport.runOnFx(() -> main.toggleBreakpoint(3));
    }

    @Test
    void debugTestAtCaretRunsThatTestAndRestartRepeatsIt() throws Exception {
        EditorBuffer tests = open(root.resolve("main_test.go"));
        FxTestSupport.runOnFx(() -> {
            var area = tests.getFocusedArea();
            area.replaceText(area.getLength(), area.getLength(), "\n// edited\n");
            tests.getFocusedArea().moveTo(9, 1); // in TestSecond
        });
        assertTrue(call(tests::isDirty));
        run("test.debugAtCaret");
        FakeDebugAdapter.Session first = nextSession("launch");
        assertEquals("test", first.launchArgs.get("mode"));
        assertEquals(List.of("-test.run", "^TestSecond$"), first.launchArgs.get("args"));
        assertEquals("TestSecond", sessionName());
        assertFalse(call(tests::isDirty), "the module's Go files are saved first: Delve builds from disk");

        FxTestSupport.runOnFx(() -> tests.getFocusedArea().moveTo(5, 1)); // in TestFirst now
        FxTestSupport.runOnFx(debug::restart);
        FakeDebugAdapter.Session again = nextSession("launch");
        assertEquals(List.of("-test.run", "^TestSecond$"), again.launchArgs.get("args"), "Restart repeats the test");
    }

    @Test
    void attachToProcessAttachesDelveByProcessId() throws Exception {
        open(root.resolve("main.go"));
        var target = new LocalProcesses.Target(4242, LocalProcesses.Kind.GO, "server", "", 0);
        FxTestSupport.runOnFx(() -> debug.attachToProcess(target));
        FakeDebugAdapter.Session session = nextSession("attach");
        assertTrue(session.attached);
        assertEquals("local", session.launchArgs.get("mode"));
        assertEquals(4242, ((Number) session.launchArgs.get("processId")).intValue());
        assertEquals("server (4242)", sessionName());
    }

    @Test
    void whyGoCannotBeDebuggedIsSaid() throws Exception {
        try {
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setGoDebugEnabled(false));
            assertEquals(tr("status.debug.goOff"), call(() -> debug.unavailableMessage("go", null)));
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setGoDebugEnabled(true);
                fx.shared
                        .getSettings()
                        .setGoDebugPath(fx.configDir.resolve("no-dlv").toString());
                debug.applySupport();
            });
            await("Delve not found", () -> !call(() -> debug.debugEffectiveFor("go")));
            assertEquals(tr("status.debug.delveMissing"), call(() -> debug.unavailableMessage("go", null)));
            var target = new LocalProcesses.Target(1, LocalProcesses.Kind.GO, "server", "", 0);
            FxTestSupport.runOnFx(() -> debug.attachToProcess(target));
            assertFalse(call(manager::isActive), "nothing is attached without Delve");
        } finally {
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setGoDebugPath(fakeDlv.toString());
                debug.applySupport();
            });
            await("Go debugging available again", () -> call(() -> debug.debugEffectiveFor("go")));
        }
    }

    /** A Go file is never runnable (no Run File), so the editor menu gives Debug File a place of its own. */
    @Test
    void theEditorMenuOffersDebugFile() throws Exception {
        EditorBuffer b = open(root.resolve("main.go"));
        List<javafx.scene.control.MenuItem> items = FxTestSupport.callOnFx(() -> debug.editorMenuItems(b));
        javafx.scene.control.MenuItem debugFile = items.stream()
                .filter(i -> tr("editmenu.debugFile").equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(items.stream()
                        .map(javafx.scene.control.MenuItem::getText)
                        .toList()));
        FxTestSupport.runOnFx(debugFile::fire);
        FakeDebugAdapter.Session session = nextSession("launch");
        assertEquals("debug", session.launchArgs.get("mode"), "the package, as F5 debugs it");
        List<String> during = FxTestSupport.callOnFx(() -> debug.editorMenuItems(b).stream()
                .map(javafx.scene.control.MenuItem::getText)
                .toList());
        assertFalse(during.contains(tr("editmenu.debugFile")), "not again while it is debugged: " + during);
    }
}
