package com.editora.ui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.FakeCoreFile;
import com.editora.dap.FakeDebugAdapter;
import org.eclipse.lsp4j.debug.StackFrame;
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
 * Open Core Dump through the window, against a {@link FakeDebugAdapter} reached as CodeLLDB and as Delve are: the
 * core's program is found in it (or asked for), the right debugger is sent the right request, the crash is shown in
 * the program's own code, and nothing runs. {@code CoreDumpProbeFxTest} opens real cores.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreDumpWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path root;

    /** What the open dialogs answer, in turn; the titles they were shown with. */
    private final Deque<Path> answers = new ArrayDeque<>();

    private final List<String> asked = new ArrayList<>();

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(File.pathSeparatorChar == ';', "the stand-ins are shell scripts");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        root = Files.createDirectories(fx.configDir.resolve("shelf")).toRealPath();
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String relay = "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                + "' com.editora.dap.RelayAdapterMain ";
        Path codelldb = Files.writeString(fx.configDir.resolve("codelldb"), relay + "listen \"$2\" " + adapter.port());
        Path dlv = Files.writeString(fx.configDir.resolve("dlv"), relay + "announce " + adapter.port());
        assertTrue(codelldb.toFile().setExecutable(true) && dlv.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRustDebugPath(codelldb.toString());
            fx.shared.getSettings().setGoDebugPath(dlv.toString());
            debug.chooseFile = chooser -> {
                asked.add(chooser.getTitle());
                Path next = answers.poll();
                return next == null ? null : next.toFile();
            };
            debug.applySupport();
        });
        await(
                "C/C++ and Go debugging available",
                () -> call(() -> debug.debugEffectiveFor("cpp")) && call(() -> debug.debugEffectiveFor("go")));
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
        answers.clear();
        asked.clear();
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

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(bar, "echo").getText();
        });
    }

    private static StackFrame frame(int id, String name, Path path, int line) {
        StackFrame f = new StackFrame();
        f.setId(id);
        f.setName(name);
        f.setLine(line + 1);
        org.eclipse.lsp4j.debug.Source source = new org.eclipse.lsp4j.debug.Source();
        source.setPath(path.toString());
        f.setSource(source);
        return f;
    }

    private FakeDebugAdapter.Session next(String request) throws Exception {
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest(request);
        return session;
    }

    @Test
    void aNativeCoreOpensInCodelldbShowsTheCrashInTheProgramsCodeAndCannotBeRun() throws Exception {
        Path source = Files.writeString(root.resolve("shelf.c"), "int total(int *p) {\n    return *p;\n}\n");
        Path program = Files.writeString(root.resolve("shelf"), "\u007fELF");
        Path libc = Files.writeString(
                Files.createDirectories(fx.configDir.resolve("libc")).resolve("raise.c"), "void raise(void) {}\n");
        Path core = FakeCoreFile.write(
                fx.configDir.resolve("core.77"), "shelf", List.of(program.toString(), "/usr/lib/libc.so.6"));
        answers.add(core);

        run("debug.openCoreDump");
        FakeDebugAdapter.Session session = next("launch");
        assertEquals(List.of(tr("debug.core.chooseCore")), asked, "the program was found in the core: not asked for");
        assertEquals(
                List.of("target create \"" + program + "\" --core \"" + core + "\""),
                session.launchArgs.get("targetCreateCommands"));
        assertEquals(List.of(), session.launchArgs.get("processCreateCommands"));
        assertEquals(List.of("cpp"), session.launchArgs.get("sourceLanguages"));
        assertEquals(tr("debug.core.session", "core.77", "shelf"), call(() ->
                (String) FxTestSupport.field(debug.panel(), "sessionFile")));

        // The signal was raised in libc; the crash is shown in total(), the program's own code.
        session.frames = List.of(frame(1, "raise", libc, 0), frame(2, "total", source, 1));
        session.stop(session.mainThread, "exception");
        await(
                "the stop shown",
                () -> call(() -> manager.state() == DapManager.State.SUSPENDED
                        && FxTestSupport.<ListView<DapModels.StackFrameInfo>>field(debug.panel(), "stack")
                                        .getSelectionModel()
                                        .getSelectedItem()
                                != null));
        assertEquals(
                "total",
                call(() -> FxTestSupport.<ListView<DapModels.StackFrameInfo>>field(debug.panel(), "stack")
                        .getSelectionModel()
                        .getSelectedItem()
                        .name()));
        assertTrue(call(manager::isPostMortem));
        assertTrue(call(() ->
                FxTestSupport.<Label>field(debug.panel(), "status").getText().startsWith(tr("debugpanel.state.core"))));
        for (String control : List.of("start", "stepOver", "stepInto", "stepOut", "runToCursor", "pause")) {
            assertTrue(
                    call(() ->
                            FxTestSupport.<Button>field(debug.panel(), control).isDisabled()),
                    control + " is disabled");
        }
        assertFalse(call(() -> Chrome.contextEnabled("debug.stepOver", fx.controller.paletteContext())));
        assertTrue(
                call(() -> Chrome.contextEnabled("debug.evaluate", fx.controller.paletteContext())),
                "a core's values can be evaluated");

        FxTestSupport.runOnFx(manager::stepOver);
        FxTestSupport.runOnFx(manager::resume);
        assertEquals(tr("status.debug.coreCannotRun"), status());
        FxTestSupport.drainFx();
        assertFalse(session.requests.contains("next") || session.requests.contains("continue"), "nothing ran");
        assertEquals(DapManager.State.SUSPENDED, call(manager::state));
    }

    @Test
    void aGoCoreOpensInDelveAndItsProgramIsAskedForWhenTheCoreDoesNotNameIt() throws Exception {
        Path program = root.resolve("orders");
        Files.write(program, "ÿ Go buildinf: go1.22".getBytes(StandardCharsets.ISO_8859_1));
        Path core = Files.writeString(fx.configDir.resolve("orders.dmp"), "MDMP"); // a minidump names no program
        answers.add(core);
        answers.add(program);

        run("debug.openCoreDump");
        FakeDebugAdapter.Session session = next("launch");
        assertEquals(List.of(tr("debug.core.chooseCore"), tr("debug.core.chooseProgram", "orders.dmp")), asked);
        assertEquals("core", session.launchArgs.get("mode"));
        assertEquals(program.toString(), session.launchArgs.get("program"));
        assertEquals(core.toString(), session.launchArgs.get("coreFilePath"));
        assertTrue(call(manager::isPostMortem));
    }

    @Test
    void choosingNoCoreOrNoProgramOpensNothing() throws Exception {
        int before = adapter.sessionCount();
        run("debug.openCoreDump");
        FxTestSupport.drainFx();
        answers.add(Files.writeString(fx.configDir.resolve("other.dmp"), "MDMP"));
        run("debug.openCoreDump");
        await("the program asked for", () -> asked.size() == 3);
        FxTestSupport.drainFx();
        assertEquals(before, adapter.sessionCount());
        assertFalse(call(manager::isActive));
    }

    @Test
    void aCoresStopShowsTheFirstFrameInTheProgramsOwnCode() {
        assertEquals(0, ownFrameOf(List.of()), "no frames: the top");
        List<DapModels.StackFrameInfo> frames = List.of(
                info(1, "runtime.raise", Path.of("/go/src/runtime/sys.s")),
                info(2, "main.total", Path.of("/srv/app/main.go")),
                info(3, "main.main", Path.of("/srv/app/main.go")));
        assertEquals(1, DebugCoordinator.ownFrame(frames, f -> true, List.of(Path.of("/srv/app"))));
        assertEquals(0, DebugCoordinator.ownFrame(frames, f -> true, List.of(Path.of("/elsewhere"))), "the top");
    }

    private static int ownFrameOf(List<DapModels.StackFrameInfo> frames) {
        return DebugCoordinator.ownFrame(frames, f -> true, List.of(Path.of("/srv")));
    }

    private static DapModels.StackFrameInfo info(int id, String name, Path file) {
        return new DapModels.StackFrameInfo(id, name, file, 1, 1, file.toUri().toString(), "", null, null);
    }
}
