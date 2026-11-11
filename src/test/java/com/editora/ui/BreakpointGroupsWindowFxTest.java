package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;

import com.editora.command.CommandRegistry;
import com.editora.config.Breakpoint;
import com.editora.config.BreakpointExport;
import com.editora.config.MemberBreakpoint;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import com.editora.editor.debug.BreakpointManager;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
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
 * Breakpoint groups and breakpoint files through the window, against a {@link FakeDebugAdapter} reached as Ruby's
 * rdbg is: breakpoints put in a group from the command and the form are turned off and on together — in a tab, in a
 * file with no tab, and a method breakpoint; Export writes them to a file and Import sets them again, into a live
 * session too, leaving those already set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BreakpointGroupsWindowFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path script;
    private Path other;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("groups")).toRealPath();
        script = Files.writeString(root.resolve("main.rb"), "a = 1\nb = 2\nc = 3\nd = 4\n");
        other = Files.writeString(root.resolve("other.rb"), "x = 1\ny = 2\n");
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

    /** Every breakpoint gone, and no session: each test starts from nothing. */
    @AfterEach
    void clear() throws Exception {
        if (call(manager::isActive)) {
            FxTestSupport.runOnFx(manager::stop);
            await("ended", () -> !call(manager::isActive));
        }
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = fx.controller.activeBuffer();
            if (b != null && script.equals(b.getPath())) {
                for (int line : new ArrayList<>(b.getBreakpointManager().lines())) {
                    b.toggleBreakpoint(line);
                }
            }
            ops().breakpointMap().remove(DebugCoordinator.key(other));
            debug.memberBreakpoints().all().forEach(debug.memberBreakpoints()::remove);
        });
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

    private DebugCoordinator.Ops ops() {
        return FxTestSupport.field(debug, "ops");
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

    private EditorBuffer openScript() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "main.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        return FxTestSupport.callOnFx(fx.controller::activeBuffer);
    }

    /** Types {@code group} into the form on screen's Group field and accepts the form. FX thread. */
    @SuppressWarnings("unchecked")
    private void answerGroup(String group) {
        var card = OverlayDriver.card(fx.controller);
        ComboBox<String> combo = card.lookupAll(".combo-box").stream()
                .filter(n -> n instanceof ComboBox<?> c && c.isEditable())
                .map(n -> (ComboBox<String>) n)
                .findFirst()
                .orElseThrow();
        combo.getEditor().setText(group);
        card.lookupAll(".button").stream()
                .filter(n -> n instanceof Button button && tr("dialog.ok").equals(button.getText()))
                .map(Button.class::cast)
                .findFirst()
                .orElseThrow()
                .fire();
    }

    private Breakpoint stored(Path file, int line) {
        return call(() -> ops().breakpointMap().getOrDefault(DebugCoordinator.key(file), List.of()).stream()
                .filter(bp -> bp.line() == line)
                .findFirst()
                .orElse(null));
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

    @Test
    void aGroupsBreakpointsAreTurnedOffAndOnTogether() throws Exception {
        EditorBuffer b = openScript();
        BreakpointManager mgr = b.getBreakpointManager();
        run("debug.toggleBreakpointGroup");
        assertEquals(tr("status.debug.noGroups"), status());

        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(0);
            b.toggleBreakpoint(1);
            b.toggleBreakpoint(2);
            b.getFocusedArea().moveTo(0, 0);
        });
        run("debug.moveBreakpointToGroup");
        FxTestSupport.runOnFx(() -> answerGroup("  Setup "));
        assertEquals("Setup", call(() -> mgr.get(0).group()));
        assertEquals(tr("status.debug.groupSet", 1, "Setup"), status());
        // The breakpoint form has the field too, offering the groups there are.
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().moveTo(1, 0);
            debug.editBreakpointAtCaret();
        });
        @SuppressWarnings("unchecked")
        List<String> offered = call(() -> List.copyOf(OverlayDriver.card(fx.controller).lookupAll(".combo-box").stream()
                .filter(n -> n instanceof ComboBox<?> c && c.isEditable())
                .map(n -> ((ComboBox<String>) n).getItems())
                .findFirst()
                .orElseThrow()));
        assertEquals(List.of("Setup"), offered);
        FxTestSupport.runOnFx(() -> answerGroup("Setup"));
        assertEquals("Setup", call(() -> mgr.get(1).group()));
        assertEquals(tr("debug.breakpoint.group", "Setup"), call(() -> mgr.tooltip(1)));
        // A file with no tab, and a method breakpoint, in the same group.
        FxTestSupport.runOnFx(() -> {
            ops().breakpointMap()
                    .put(
                            DebugCoordinator.key(other),
                            List.of(Breakpoint.plain(1, "y = 2").withGroup("Setup")));
            debug.memberBreakpoints().add(MemberBreakpoint.method("p.A", "run").withGroup("Setup"));
        });

        run("debug.toggleBreakpointGroup");
        List<String> groups = call(() -> OverlayDriver.choices(fx.controller));
        assertEquals(List.of("Setup"), groups);
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, "Setup"));
        assertEquals(tr("status.debug.groupDisabled", "Setup", 4), status());
        assertFalse(call(() -> mgr.get(0).enabled()));
        assertFalse(call(() -> mgr.get(1).enabled()));
        assertTrue(call(() -> mgr.get(2).enabled()), "not in the group");
        assertFalse(stored(other, 1).enabled());
        assertFalse(call(() -> debug.memberBreakpoints().all().getFirst().enabled()));
        assertEquals(
                "    " + tr("debug.breakpoints.groupRow", "Setup", 4),
                call(() -> BreakpointsPicker.groupRow(
                        "Setup", debug.breakpointsPicker().entries())));

        // One turned on by hand: the group is partly on, and choosing it turns them all on.
        FxTestSupport.runOnFx(() -> mgr.setEnabled(0, true));
        assertEquals(
                "– " + tr("debug.breakpoints.groupRow", "Setup", 4),
                call(() -> BreakpointsPicker.groupRow(
                        "Setup", debug.breakpointsPicker().entries())));
        assertTrue(call(() -> debug.breakpointsPicker().toggleGroup("Setup")));
        assertTrue(call(() -> mgr.get(1).enabled()));
        assertTrue(stored(other, 1).enabled());
        assertTrue(call(() -> debug.memberBreakpoints().all().getFirst().enabled()));

        // The list says the group, and finds a breakpoint by it; blank takes a breakpoint out of its group.
        BreakpointsPicker.Entry first =
                call(() -> debug.breakpointsPicker().entries().getFirst());
        assertTrue(BreakpointsPicker.detail(first, null, null).contains(tr("debug.breakpoint.group", "Setup")));
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(0, 0));
        run("debug.moveBreakpointToGroup");
        FxTestSupport.runOnFx(() -> answerGroup(""));
        assertEquals("", call(() -> mgr.get(0).group()));
        assertEquals(tr("status.debug.groupNone", 1), status());
    }

    @Test
    void exportedBreakpointsAreImportedAgainIntoTabsClosedFilesAndALiveSession() throws Exception {
        EditorBuffer b = openScript();
        BreakpointManager mgr = b.getBreakpointManager();
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(0);
            mgr.setCondition(0, "a > 0");
            mgr.setGroup(0, "Setup");
            b.toggleBreakpoint(3);
            ops().breakpointMap().put(DebugCoordinator.key(other), List.of(Breakpoint.plain(1, "y = 2")));
            debug.memberBreakpoints().add(MemberBreakpoint.method("p.A", "run").withGroup("Setup"));
        });
        Path all = fx.configDir.resolve("all.json");
        Path setup = fx.configDir.resolve("setup.json");
        BreakpointGroups groups = call(debug::groups);
        java.util.concurrent.atomic.AtomicReference<String> suggested =
                new java.util.concurrent.atomic.AtomicReference<>();

        // With groups there are, Export asks which: every breakpoint, or a group's.
        groups.chooseSave = chooser -> {
            suggested.set(chooser.getInitialFileName());
            return all.toFile();
        };
        run("debug.exportBreakpoints");
        assertEquals(List.of("", "Setup"), call(() -> OverlayDriver.choices(fx.controller)));
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, ""));
        assertEquals("breakpoints.json", suggested.get());
        assertEquals(tr("status.debug.exported", 4, "all.json"), status());
        BreakpointExport.Document written = BreakpointExport.read(Files.readString(all));
        assertEquals(3, written.breakpoints().size());
        assertEquals(1, written.memberBreakpoints().size());
        groups.chooseSave = chooser -> {
            suggested.set(chooser.getInitialFileName());
            return setup.toFile();
        };
        run("debug.exportBreakpoints");
        FxTestSupport.runOnFx(() -> OverlayDriver.pick(fx.controller, "Setup"));
        assertEquals("breakpoints-Setup.json", suggested.get());
        BreakpointExport.Document group = BreakpointExport.read(Files.readString(setup));
        assertEquals(
                List.of(0),
                group.breakpoints().stream().map(f -> f.breakpoint().line()).toList());

        // All gone, a session started, then imported.
        clear();
        EditorBuffer tab = openScript();
        BreakpointManager now = tab.getBreakpointManager();
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        await("configured", () -> session.requests.contains("configurationDone"));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        await("the start said", () -> !status().isEmpty()); // so the import's line is the last one
        groups.chooseOpen = chooser -> all.toFile();
        run("debug.importBreakpoints");
        assertEquals(tr("status.debug.imported", 4, 0, 0), status());
        assertEquals("a > 0", call(() -> now.get(0).condition()));
        assertEquals("Setup", call(() -> now.get(0).group()));
        assertTrue(call(() -> now.isBreakpoint(3)));
        assertEquals("y = 2", stored(other, 1).lineText());
        assertEquals(
                "Setup", call(() -> debug.memberBreakpoints().all().getFirst().group()));
        await("the file with no tab, sent to the session", () -> List.of(2).equals(sentLines(session, other)));
        await("the tab's, sent", () -> List.of(1, 4).equals(sentLines(session, script)));

        run("debug.importBreakpoints");
        assertEquals(tr("status.debug.imported", 0, 4, 0), status(), "all already set");
        groups.chooseOpen = chooser -> null; // cancelled
        run("debug.importBreakpoints");
        assertEquals(tr("status.debug.imported", 0, 4, 0), status());
        Path broken = Files.writeString(fx.configDir.resolve("broken.json"), "{}");
        groups.chooseOpen = chooser -> broken.toFile();
        run("debug.importBreakpoints");
        assertTrue(
                status().startsWith(tr("status.debug.breakpointsImportFailed", "broken.json", "")
                        .strip()),
                status());
    }
}
