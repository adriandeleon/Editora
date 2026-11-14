package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Mark Object and View As through the Variables menu, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is: a
 * row showing an object id offers Mark Object, and every row showing that object draws the label; a number offers
 * none; a string offers View As, which opens it laid out read-only; Unmark All takes the labels away, and a new run
 * starts with none. {@code MarkObjectProbeFxTest} drives java-debug and debugpy.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class MarkObjectWindowFxTest {

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
        Path root = Files.createDirectories(fx.configDir.resolve("marks")).toRealPath();
        script = Files.writeString(root.resolve("main.rb"), "a = [1]\nputs a\n");
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

    private FakeDebugAdapter.Session stopped() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script)); // F5 runs the file in front
        await(
                "main.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        FxTestSupport.runOnFx(
                () -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run("debug.start"));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = script.toString();
        session.frameLine = 2;
        session.variables.put(
                FakeDebugAdapter.Session.LOCALS,
                List.of(
                        FakeDebugAdapter.Session.variable("first", "ArrayList@10 size=2", 0),
                        FakeDebugAdapter.Session.variable("same", "ArrayList@10 size=2", 0),
                        FakeDebugAdapter.Session.variable("n", "42", 0),
                        FakeDebugAdapter.Session.variable("doc", "\"<a><b/></a>\"", 0)));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(1, "breakpoint");
        await("the variables", () -> call(() -> item("doc") != null));
        return session;
    }

    /** The Variables row called {@code name}, in any scope. FX thread. */
    private TreeItem<DebugPanel.VarRow> item(String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        if (tree.getRoot() == null) {
            return null;
        }
        for (TreeItem<DebugPanel.VarRow> top : tree.getRoot().getChildren()) {
            for (TreeItem<DebugPanel.VarRow> child : top.getChildren()) {
                if (child.getValue() != null && child.getValue().name().equals(name)) {
                    return child;
                }
            }
        }
        return null;
    }

    /** The Variables menu as it opens on the row called {@code name}. FX thread. */
    private List<MenuItem> menuOn(String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        tree.getSelectionModel().select(item(name));
        ContextMenu menu = tree.getContextMenu();
        menu.getOnShowing().handle(null);
        return List.copyOf(menu.getItems());
    }

    private static MenuItem find(List<MenuItem> items, String text) {
        return items.stream().filter(i -> text.equals(i.getText())).findFirst().orElse(null);
    }

    private static String text(TextFlow flow) {
        StringBuilder sb = new StringBuilder();
        flow.getChildren().forEach(n -> sb.append(n instanceof Text t ? t.getText() : ""));
        return sb.toString();
    }

    @Test
    void anObjectIsMarkedWhereverItShowsAndAStringIsViewedAs() throws Exception {
        stopped();
        DebugPanel panel = call(debug::panel);

        assertNull(call(() -> find(menuOn("n"), tr("debugpanel.markObject"))), "a number is no object");
        FxTestSupport.runOnFx(
                () -> find(menuOn("first"), tr("debugpanel.markObject")).fire());
        FxTestSupport.runOnFx(() -> OverlayDriver.answer(fx.controller, "list A"));
        DebugPanel.VarRow same = call(() -> item("same").getValue());
        assertEquals("list A", call(() -> panel.objectMarks().labelOf(same.value())));
        assertEquals(
                "same = " + tr("debugpanel.markLabel", "list A") + " ArrayList@10 size=2",
                text(DebugPanel.renderRow(same, false, "list A")));
        List<MenuItem> onSame = call(() -> menuOn("same"));
        assertTrue(
                find(onSame, tr("debugpanel.editMark")) != null && find(onSame, tr("debugpanel.unmarkObject")) != null);
        assertEquals("list A", call(() -> debug.s().marks.labelOf("ArrayList@10 size=3")), "the session's marks");

        Menu viewAs = (Menu) call(() -> find(menuOn("doc"), tr("debugpanel.viewAs")));
        FxTestSupport.runOnFx(
                () -> find(viewAs.getItems(), tr("debugpanel.viewAs.xml")).fire());
        await(
                "the XML, laid out",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && fx.controller.activeBuffer().getPath() == null
                        && fx.controller
                                .activeBuffer()
                                .getArea()
                                .getText()
                                .replace("\r\n", "\n")
                                .equals("<a>\n  <b/>\n</a>\n")));
        assertEquals("xml", call(() -> fx.controller.activeBuffer().getLanguage()));
        assertEquals(
                tr("debugpanel.viewAs.title", "doc", "XML"),
                call(() -> fx.controller.activeBuffer().getDisplayName()));

        FxTestSupport.runOnFx(
                () -> find(menuOn("n"), tr("debugpanel.unmarkAll")).fire());
        assertTrue(call(() -> panel.objectMarks().isEmpty()));
        assertNull(call(() -> find(menuOn("n"), tr("debugpanel.unmarkAll"))), "nothing left to unmark");

        // A new run starts with no marks: an id means nothing to it.
        FxTestSupport.runOnFx(() -> panel.objectMarks().mark("ArrayList@10 size=2", "kept?"));
        FxTestSupport.runOnFx(manager::stop);
        await("ended", () -> !call(manager::isActive));
        assertFalse(call(() -> panel.objectMarks().isEmpty()), "still readable after Stop (Debug History)");
        stopped();
        assertTrue(call(() -> debug.s().marks.isEmpty()));
        FxTestSupport.runOnFx(manager::stop);
        await("ended again", () -> !call(manager::isActive));
    }
}
