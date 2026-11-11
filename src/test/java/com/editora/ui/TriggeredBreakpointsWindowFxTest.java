package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.Breakpoint;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import com.editora.editor.debug.BreakpointManager;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Triggered breakpoints through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: a breakpoint
 * that waits for another is not sent until the program stops on that one, then it is; the next session it waits
 * again; and once its trigger is removed it waits for none. {@code TriggeredBreakpointsProbeFxTest} drives a real
 * adapter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TriggeredBreakpointsWindowFxTest {

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
        Path root = Files.createDirectories(fx.configDir.resolve("gate")).toRealPath();
        script = Files.writeString(root.resolve("gate.rb"), "x = 1\nx += 1\nputs x\ny = x\n");
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

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private String status() {
        return call(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    /** The 1-based lines the adapter was last told of for the script, or null when it was told nothing yet. */
    private List<Integer> sentLines(FakeDebugAdapter.Session session) {
        return sentLines(session, script);
    }

    /** The 1-based lines the adapter was last told of for {@code file}, or null when it was told nothing yet. */
    private static List<Integer> sentLines(FakeDebugAdapter.Session session, Path file) {
        List<SetBreakpointsArguments> all = session.breakpoints;
        for (int i = all.size() - 1; i >= 0; i--) {
            SetBreakpointsArguments args = all.get(i);
            if (args.getSource() != null
                    && file.toString().equals(args.getSource().getPath())) {
                SourceBreakpoint[] bps =
                        args.getBreakpoints() == null ? new SourceBreakpoint[0] : args.getBreakpoints();
                return Arrays.stream(bps)
                        .map(SourceBreakpoint::getLine)
                        .sorted()
                        .toList();
            }
        }
        return null;
    }

    private BreakpointsPicker.Entry entryAt(int line) {
        return call(() ->
                BreakpointsPicker.lineEntries(FxTestSupport.field(debug, "host"), FxTestSupport.field(debug, "ops"))
                        .stream()
                        .filter(e -> e.line() == line && script.equals(e.file()))
                        .findFirst()
                        .orElseThrow());
    }

    private FakeDebugAdapter.Session start() throws Exception {
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = script.toString();
        await("configured", () -> session.requests.contains("configurationDone"));
        return session;
    }

    @Test
    void aTriggeredBreakpointIsSentOnlyOnceItsTriggerIsHit() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "gate.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        EditorBuffer b = FxTestSupport.callOnFx(fx.controller::activeBuffer);
        BreakpointManager mgr = b.getBreakpointManager();

        // No breakpoint to wait for yet.
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(3, 0));
        run("debug.addTriggeredBreakpoint");
        assertEquals(tr("status.debug.noTriggerChoices"), status());

        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(1)); // the trigger: "x += 1"
        BreakpointsPicker.Entry trigger = entryAt(1);
        FxTestSupport.runOnFx(() -> debug.linkTriggered(b, 3, trigger)); // what the picker's choice runs
        Breakpoint triggerBp = call(() -> mgr.get(1));
        Breakpoint waiting = call(() -> mgr.get(3));
        assertFalse(triggerBp.id().isEmpty(), "the trigger is given an id");
        assertEquals(triggerBp.id(), waiting.triggeredBy());
        assertEquals(tr("status.debug.triggeredAdded", 4, "gate.rb:2"), status());
        assertEquals("triggered", call(() -> mgr.styleClasses(3)));
        assertEquals(tr("debug.breakpoint.triggeredBy", "gate.rb:2"), call(() -> mgr.tooltip(3)));
        List<Breakpoint> saved = call(() -> FxTestSupport.<DebugCoordinator.Ops>field(debug, "ops")
                .breakpointMap()
                .get(DebugCoordinator.key(script)));
        assertEquals(triggerBp.id(), saved.get(1).triggeredBy(), "saved with the link");

        FakeDebugAdapter.Session session = start();
        assertEquals(List.of(2), sentLines(session), "the triggered one is not sent");
        await(
                "said to wait",
                () -> call(() -> {
                    BreakpointManager.Live live = mgr.live(3);
                    return live != null
                            && live.state() == BreakpointManager.LiveState.PENDING
                            && tr("debug.breakpoint.waiting", "gate.rb:2").equals(live.tooltip());
                }));

        session.frameLine = 2; // DAP lines start at 1: "x += 1"
        session.stop(1, "breakpoint");
        await("sent once its trigger is hit", () -> List.of(2, 4).equals(sentLines(session)));
        await("said", () -> tr("status.debug.triggerHit", "gate.rb:2", 1).equals(status()));
        FxTestSupport.runOnFx(manager::resume);

        // A second stop on the trigger changes nothing.
        int told = session.breakpoints.size();
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, "breakpoint");
        await("stopped", () -> call(manager::state) == DapManager.State.SUSPENDED);
        session.awaitDelivered();
        assertEquals(told, session.breakpoints.size());
        FxTestSupport.runOnFx(manager::stop);
        await("ended", () -> !call(manager::isActive));

        // The next session it waits again.
        FakeDebugAdapter.Session next = start();
        assertEquals(List.of(2), sentLines(next));
        // Its trigger removed, it waits for none: sent at once, drawn as a plain breakpoint.
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(1));
        await("sent once its trigger is gone", () -> List.of(4).equals(sentLines(next)));
        assertNull(call(() -> mgr.waitsFor(mgr.get(3))));
        assertNotNull(call(() -> mgr.get(3)));
        FxTestSupport.runOnFx(manager::stop);
        await("ended again", () -> !call(manager::isActive));
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(3));
    }

    private EditorBuffer openScript() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "gate.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    /** Sets the breakpoint form's Wait for to the row {@code pick} chooses, and accepts the form. */
    @SuppressWarnings("unchecked")
    private void submitForm(
            EditorBuffer b,
            int line,
            java.util.function.Function<List<BreakpointsPicker.Entry>, BreakpointsPicker.Entry> pick)
            throws Exception {
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().moveTo(line, 0);
            debug.editBreakpointAtCaret();
        });
        FxTestSupport.runOnFx(() -> {
            var combo = (javafx.scene.control.ComboBox<BreakpointsPicker.Entry>)
                    OverlayDriver.card(fx.controller).lookup(".combo-box");
            combo.setValue(pick.apply(combo.getItems()));
            OverlayDriver.card(fx.controller).lookupAll(".button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button button
                            && tr("dialog.ok").equals(button.getText()))
                    .map(javafx.scene.control.Button.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .fire();
        });
    }

    @Test
    void thePickerAndTheFormLinkBreakpointsAndAClosedFilesWaitingOneIsSentOnceItsTriggerIsHit() throws Exception {
        EditorBuffer b = openScript();
        BreakpointManager mgr = b.getBreakpointManager();
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(0)); // the trigger: "x = 1"

        // The picker: the one other breakpoint is offered; choosing it links the caret line's new breakpoint.
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(2, 0));
        run("debug.addTriggeredBreakpoint");
        List<BreakpointsPicker.Entry> offered = call(() -> OverlayDriver.choices(fx.controller));
        assertEquals(
                List.of(0), offered.stream().map(BreakpointsPicker.Entry::line).toList());
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, offered.getFirst()));
        String id = call(() -> mgr.get(0).id());
        assertFalse(id.isEmpty());
        assertEquals(id, call(() -> mgr.get(2).triggeredBy()));
        assertEquals(tr("status.debug.triggeredAdded", 3, "gate.rb:1"), status());
        // Choosing it again: it waits for none. The line that waits is not offered to the trigger (a loop).
        run("debug.addTriggeredBreakpoint");
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(
                fx.controller,
                OverlayDriver.<BreakpointsPicker.Entry>choices(fx.controller).getFirst()));
        assertFalse(call(() -> mgr.get(2).isTriggered()));
        assertEquals(tr("status.debug.triggeredRemoved", 3), status());
        FxTestSupport.runOnFx(() -> mgr.setTriggeredBy(2, id));
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(0, 0));
        run("debug.addTriggeredBreakpoint");
        assertEquals(tr("status.debug.noTriggerChoices"), status(), "line 3 waits for it: nothing else to offer");

        // The form: Wait for none, then the trigger again.
        submitForm(b, 2, items -> items.getFirst());
        assertFalse(call(() -> mgr.get(2).isTriggered()));
        submitForm(
                b,
                2,
                items -> items.stream().filter(e -> e.line() == 0).findFirst().orElseThrow());
        assertEquals(id, call(() -> mgr.get(2).triggeredBy()));
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(2));

        // A breakpoint of a file with no tab waits for it too.
        Path other = Files.writeString(script.resolveSibling("other.rb"), "a = 1\nb = 2\n");
        DebugCoordinator.Ops ops = FxTestSupport.field(debug, "ops");
        FxTestSupport.runOnFx(() -> ops.breakpointMap()
                .put(
                        DebugCoordinator.key(other),
                        List.of(Breakpoint.plain(1, "b = 2").withTriggeredBy(id))));
        FakeDebugAdapter.Session session = start();
        assertEquals(List.of(1), sentLines(session));
        assertNull(sentLines(session, other), "not sent while it waits");
        session.frameLine = 1;
        session.stop(1, "breakpoint");
        await("sent once its trigger is hit", () -> List.of(2).equals(sentLines(session, other)));
        FxTestSupport.runOnFx(manager::resume);
        FxTestSupport.runOnFx(manager::stop);
        await("ended", () -> !call(manager::isActive));
        FxTestSupport.runOnFx(() -> {
            ops.breakpointMap().remove(DebugCoordinator.key(other));
            b.toggleBreakpoint(0);
        });
    }
}
