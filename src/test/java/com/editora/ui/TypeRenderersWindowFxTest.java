package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.dap.TypeRenderer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Type renderers through the window, against a {@link FakeDebugAdapter} reached as Ruby's rdbg is (its {@code
 * evaluate} answers {@code val(<expression>)}): a Money variable shows its renderer's value, opens on the renderer's
 * children and [Raw View], a watch is rendered when evaluating it again is harmless, Mute Renderers shows the
 * debugger's own, Customize Rendering… saves a renderer, and Set Value on a renderer's child assigns its expression.
 * {@code TypeRenderersProbeFxTest} drives real adapters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class TypeRenderersWindowFxTest {

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
        Path root = Files.createDirectories(fx.configDir.resolve("shop")).toRealPath();
        script = Files.writeString(root.resolve("shop.rb"), "total = Money.new(3)\nputs total\n");
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

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        await("stopped", () -> !call(manager::isActive));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugTypeRenderers(new ArrayList<>());
            if (debug.renderersMuted()) {
                debug.toggleRenderersMuted();
            }
            debug.rendererEditor = null;
        });
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

    private static org.eclipse.lsp4j.debug.Variable variable(
            String name, String value, String type, int ref, String evaluateName) {
        org.eclipse.lsp4j.debug.Variable v = FakeDebugAdapter.Session.variable(name, value, ref);
        v.setType(type);
        v.setEvaluateName(evaluateName);
        return v;
    }

    /** Opens the script, starts it, scripts a Money local with one field, and stops; the session. */
    private FakeDebugAdapter.Session stopped(List<TypeRenderer> renderers) throws Exception {
        FxTestSupport.runOnFx(() -> fx.shared.getSettings().setDebugTypeRenderers(new ArrayList<>(renderers)));
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(script));
        await(
                "shop.rb in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && script.equals(fx.controller.activeBuffer().getPath())));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        session.framePath = script.toString();
        session.frameLine = 2;
        session.variables.put(
                FakeDebugAdapter.Session.LOCALS, List.of(variable("total", "Money@1", "Money", 50, "total")));
        session.variables.put(50, List.of(variable("amount", "3", "Integer", 0, "total.amount")));
        await("running", () -> call(manager::state) == DapManager.State.RUNNING);
        session.stop(session.mainThread, "breakpoint");
        await("the stop shown", () -> call(() -> !debug.panel().callStack().isEmpty()));
        return session;
    }

    /** The row called {@code name} of kind {@code kind} anywhere in the Variables tree. FX thread. */
    private TreeItem<DebugPanel.VarRow> row(DebugPanel.Kind kind, String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        return tree.getRoot() == null ? null : find(tree.getRoot(), kind, name);
    }

    private static TreeItem<DebugPanel.VarRow> find(
            TreeItem<DebugPanel.VarRow> item, DebugPanel.Kind kind, String name) {
        for (TreeItem<DebugPanel.VarRow> child : item.getChildren()) {
            DebugPanel.VarRow v = child.getValue();
            if (v != null && v.kind() == kind && v.name().equals(name)) {
                return child;
            }
            TreeItem<DebugPanel.VarRow> deeper = find(child, kind, name);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private String valueOf(DebugPanel.Kind kind, String name) {
        return call(() -> {
            TreeItem<DebugPanel.VarRow> r = row(kind, name);
            return r == null ? null : r.getValue().value();
        });
    }

    private static final TypeRenderer MONEY =
            new TypeRenderer(true, "", "Money", "this.amount", "owner = this.owner\nbadChild = bad.thing");

    @Test
    void aRenderedVariableShowsItsValueAndChildrenThenTheRawView() throws Exception {
        stopped(List.of(MONEY));
        await("total rendered", () -> "val((total).amount)".equals(valueOf(DebugPanel.Kind.VARIABLE, "total")));

        FxTestSupport.runOnFx(() -> row(DebugPanel.Kind.VARIABLE, "total").setExpanded(true));
        await("its children", () -> valueOf(DebugPanel.Kind.VARIABLE, TypeRendering.RAW_VIEW) != null);
        List<String> names = call(() -> row(DebugPanel.Kind.VARIABLE, "total").getChildren().stream()
                .map(i -> i.getValue().name())
                .toList());
        assertEquals(List.of("owner", "badChild", TypeRendering.RAW_VIEW), names);
        assertEquals("val((total).owner)", valueOf(DebugPanel.Kind.VARIABLE, "owner"));
        assertEquals("Cannot evaluate: bad.thing", valueOf(DebugPanel.Kind.VARIABLE, "badChild"));
        assertEquals(
                "(total).owner",
                call(() -> DebugPanel.expression(row(DebugPanel.Kind.VARIABLE, "owner"))),
                "Copy as Expression gives the renderer's");

        FxTestSupport.runOnFx(
                () -> row(DebugPanel.Kind.VARIABLE, TypeRendering.RAW_VIEW).setExpanded(true));
        await("the debugger's own field", () -> "3".equals(valueOf(DebugPanel.Kind.VARIABLE, "amount")));

        // Mute Renderers: the debugger's value again.
        run("debug.muteRenderers");
        await("muted", () -> "Money@1".equals(valueOf(DebugPanel.Kind.VARIABLE, "total")));
        run("debug.muteRenderers");
        await("on again", () -> "val((total).amount)".equals(valueOf(DebugPanel.Kind.VARIABLE, "total")));
    }

    @Test
    void aWatchIsRenderedOnlyWhenEvaluatingItAgainIsHarmless() throws Exception {
        FakeDebugAdapter.Session session = stopped(List.of(MONEY));
        session.evaluateTypes.put("total", "Money");
        session.evaluateTypes.put("make()", "Money");
        FxTestSupport.runOnFx(() -> {
            debug.panel().addWatchExpression("total");
            debug.panel().addWatchExpression("make()");
        });
        await("the watch rendered", () -> "val((total).amount)".equals(valueOf(DebugPanel.Kind.WATCH, "total")));
        await("the call's watch", () -> valueOf(DebugPanel.Kind.WATCH, "make()") != null);
        assertEquals("val(make())", valueOf(DebugPanel.Kind.WATCH, "make()"), "a call is not evaluated again");
    }

    @Test
    void customizeRenderingSavesARendererForTheValuesType() throws Exception {
        stopped(List.of());
        await("total", () -> "Money@1".equals(valueOf(DebugPanel.Kind.VARIABLE, "total")));
        TypeRenderer[] offered = new TypeRenderer[1];
        FxTestSupport.runOnFx(() -> debug.rendererEditor = (initial, isNew) -> {
            offered[0] = initial;
            TypeRenderer r = initial.copy();
            r.setValue("this.label");
            return Optional.of(r);
        });
        MenuItem customize = call(() -> {
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
            tree.getSelectionModel().select(row(DebugPanel.Kind.VARIABLE, "total"));
            ContextMenu menu = tree.getContextMenu();
            menu.getOnShowing().handle(null);
            return menu.getItems().stream()
                    .filter(i -> tr("debugpanel.customizeRendering").equals(i.getText()))
                    .findFirst()
                    .orElse(null);
        });
        assertNotNull(customize, "Customize Rendering… on a value with a type");
        FxTestSupport.runOnFx(customize::fire);
        assertEquals("Money", offered[0].getType(), "a new renderer named after the type");
        assertEquals("ruby", offered[0].getLanguage(), "for the session's language");
        await(
                "saved",
                () -> call(() -> fx.shared.getSettings().getDebugTypeRenderers().size() == 1));
        await("shown at once", () -> "val((total).label)".equals(valueOf(DebugPanel.Kind.VARIABLE, "total")));
    }

    @Test
    void setValueOnARenderersChildAssignsItsExpression() throws Exception {
        FakeDebugAdapter.Session session = stopped(List.of(MONEY));
        await("total rendered", () -> "val((total).amount)".equals(valueOf(DebugPanel.Kind.VARIABLE, "total")));
        FxTestSupport.runOnFx(() -> row(DebugPanel.Kind.VARIABLE, "total").setExpanded(true));
        await("its children", () -> valueOf(DebugPanel.Kind.VARIABLE, "owner") != null);
        DebugPanel.VarRow owner =
                call(() -> row(DebugPanel.Kind.VARIABLE, "owner").getValue());
        DebugPanel.Actions actions = FxTestSupport.field(debug.panel(), "actions");
        String[] set = new String[1];
        int frameId = call(() -> debug.panel().callStack().getFirst().id());
        FxTestSupport.runOnFx(() ->
                actions.setVariable(owner.parentRef(), "owner", "nil", owner.evaluateName(), frameId, v -> set[0] = v));
        await("assigned", () -> set[0] != null);
        assertTrue(
                session.evaluateRequests.stream()
                        .anyMatch(a -> a.getExpression().startsWith("(total).owner = nil")),
                "an assignment to the child's expression");
    }
}
