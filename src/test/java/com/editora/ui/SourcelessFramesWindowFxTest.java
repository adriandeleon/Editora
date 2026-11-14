package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.eclipse.lsp4j.debug.StackFrame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Stops in code the debugger has only by reference, through the window, against a {@link FakeDebugAdapter} reached as
 * Ruby's rdbg is: the code opens read-only with the {@code source} request's text and its execution line, once per
 * session; a breakpoint set there goes to the adapter naming the reference; a pause in de-emphasized code still shows
 * the user's frame; the breakpoints go with the session. {@code SourcelessFramesProbeFxTest} drives js-debug.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class SourcelessFramesWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path script;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("memory")).toRealPath();
        script = Files.writeString(root.resolve("main.rb"), "x = twice(3)\nputs x\n");
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path rdbg = fx.configDir.resolve("rdbg");
        Files.writeString(
                rdbg,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain announce-rdbg " + adapter.port() + "\n");
        assertTrue(rdbg.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setRubyDebugPath(rdbg.toString());
            debug.applySupport();
        });
        await("Ruby debugging available", () -> call(() -> debug.debugEffectiveFor("ruby")));
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

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    private static StackFrame frame(int id, String name, String path, int reference, int line, boolean subtle) {
        StackFrame f = new StackFrame();
        f.setId(id);
        f.setName(name);
        f.setLine(line);
        f.setSource(FakeDebugAdapter.Session.source(name, path, reference));
        if (subtle) {
            f.setPresentationHint(org.eclipse.lsp4j.debug.StackFramePresentationHint.SUBTLE);
        }
        return f;
    }

    /** Whether (0-based) {@code line} of {@code b} is marked as the execution line. FX thread. */
    private static boolean marked(EditorBuffer b, int line) {
        var area = b.getFocusedArea();
        return line < area.getParagraphs().size()
                && area.getParagraph(line).getParagraphStyle().contains("exec-line");
    }

    private void stopWhenRunning(FakeDebugAdapter.Session session, String reason) throws Exception {
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, reason);
    }

    @Test
    void codeWithoutAFileIsShownReadOnlyAndTakesBreakpointsForTheSession() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "main.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        adapter.sourceTexts.put(7, "def twice(n)\n  r = n * 2\n  r\nend\n");
        FxTestSupport.runOnFx(
                () -> ((com.editora.command.CommandRegistry) FxTestSupport.field(fx.controller, "registry"))
                        .run("debug.start"));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.frames = List.of(
                frame(1, "<eval>/VM7", "<eval>/VM7", 7, 2, false), frame(2, "main", script.toString(), 0, 1, false));

        stopWhenRunning(session, "breakpoint");
        await(
                "the code, read-only, at its line",
                () -> call(() -> {
                    EditorBuffer b = fx.controller.activeBuffer();
                    return b != null && b.getPath() == null && b.isViewMode() && marked(b, 1);
                }));
        EditorBuffer doc = call(fx.controller::activeBuffer);
        assertEquals(
                "def twice(n)\n  r = n * 2\n  r\nend\n",
                call(() -> doc.getArea().getText()));
        assertEquals("<eval>/VM7", call(doc::getDisplayName));

        // A breakpoint there goes to the adapter naming the reference.
        FxTestSupport.runOnFx(() -> doc.toggleBreakpoint(2));
        await(
                "sent by reference",
                () -> session.breakpoints.stream()
                        .anyMatch(a -> a.getSource() != null
                                && Integer.valueOf(7).equals(a.getSource().getSourceReference())
                                && a.getBreakpoints().length == 1
                                && a.getBreakpoints()[0].getLine() == 3));
        await(
                "drawn as the adapter answered",
                () -> call(() -> doc.getBreakpointManager().live(2) != null));

        // Stopped there again: the same tab, not a second one.
        FxTestSupport.runOnFx(manager::resume);
        session.frames = List.of(
                frame(1, "<eval>/VM7", "<eval>/VM7", 7, 3, false), frame(2, "main", script.toString(), 0, 1, false));
        stopWhenRunning(session, "breakpoint");
        await("the next line, same tab", () -> call(() -> fx.controller.activeBuffer() == doc && marked(doc, 2)));
        assertEquals(1, call(() -> debug.s().sourceDocs.size()));
        assertEquals(1, session.requests.stream().filter("source"::equals).count(), "its text asked for once");

        // A pause in code the adapter de-emphasizes shows the user's frame.
        FxTestSupport.runOnFx(manager::resume);
        session.frames = List.of(
                frame(1, "<node_internals>/timers", "<node_internals>/timers", 9, 5, true),
                frame(2, "main", script.toString(), 0, 2, false));
        stopWhenRunning(session, "pause");
        await(
                "the user's line",
                () -> call(() -> script.equals(fx.controller.activeBuffer().getPath())
                        && marked(fx.controller.activeBuffer(), 1)));

        // Code whose text the adapter cannot give says so.
        FxTestSupport.runOnFx(manager::resume);
        session.frames = List.of(frame(1, "lost", "<eval>/VM99", 99, 1, false));
        stopWhenRunning(session, "step");
        await("said", () -> tr("status.debug.sourceUnavailable", "lost").equals(status()));

        // The session's end takes the breakpoints set in that code with it.
        FxTestSupport.runOnFx(manager::stop);
        await("ended", () -> !call(manager::isActive));
        await(
                "its breakpoints gone",
                () -> call(() -> doc.getBreakpointManager().lines().isEmpty()));
        assertTrue(call(() -> debug.s().sourceDocs.isEmpty()));
    }
}
