package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evaluate field at the top of Variables (IntelliJ's): what it evaluates is the tree's first row, an object's
 * fields open under it, a failure in its place, gone when the program moves on; Add to Watches takes it; and the
 * watches it sits over can be paused, evaluated once and removed all at once.
 */
@Tag("fx")
class DebugPanelEvaluateFxTest {

    private static final int LOCALS = 1000;
    private static final int OBJECT = 3000;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Adapter implements DebugPanel.Actions {
        final Map<Integer, List<DapModels.VariableInfo>> children = new HashMap<>();
        final Map<String, DapModels.EvalResult> results = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        int refreshes;

        @Override
        public void start() {}

        @Override
        public void pause() {}

        @Override
        public void stepOver() {}

        @Override
        public void stepInto() {}

        @Override
        public void stepOut() {}

        @Override
        public void runToCursor() {}

        @Override
        public void stop() {}

        @Override
        public void restart() {}

        @Override
        public void selectThread(int threadId) {}

        @Override
        public void selectFrame(DapModels.StackFrameInfo frame) {}

        @Override
        public void loadChildren(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
            calls.add("children " + ref);
            cb.accept(children.getOrDefault(ref, List.of()));
        }

        @Override
        public void evaluate(String expression, int frameId, Consumer<com.editora.dap.ConsoleEvaluation> cb) {
            calls.add("console " + expression);
            cb.accept(com.editora.dap.ConsoleEvaluation.value("c"));
        }

        @Override
        public void evaluateWatch(String expression, int frameId, Consumer<DapModels.EvalResult> cb) {
            calls.add("watch " + expression);
            cb.accept(new DapModels.EvalResult("w(" + expression + ")", 0, null));
        }

        @Override
        public void evaluateCode(String expression, int frameId, Consumer<DapModels.EvalResult> cb) {
            calls.add("code " + expression + " @" + frameId);
            cb.accept(results.getOrDefault(expression, new DapModels.EvalResult("v(" + expression + ")", 0, "int")));
        }

        @Override
        public void setVariable(int parentRef, String name, String value, Consumer<String> cb) {}

        @Override
        public boolean refreshValues() {
            refreshes++;
            return true;
        }
    }

    private Adapter adapter;
    private DebugPanel panel;
    private int watchChanges;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new Adapter();
        adapter.children.put(LOCALS, List.of(new DapModels.VariableInfo("count", "3", "int", 0)));
        adapter.children.put(
                OBJECT,
                List.of(
                        new DapModels.VariableInfo("x", "1", "int", 0),
                        new DapModels.VariableInfo("y", "2", "int", 0)));
        adapter.results.put("point", new DapModels.EvalResult("Point@12", OBJECT, "Point", 2, 0, false));
        adapter.results.put("nope", DapModels.EvalResult.failure("nope cannot be resolved"));
        watchChanges = 0;
        panel = FxTestSupport.callOnFx(() -> {
            DebugPanel p = new DebugPanel(adapter);
            p.setOnWatchesChanged(() -> watchChanges++);
            return p;
        });
    }

    private TreeView<DebugPanel.VarRow> tree() {
        return FxTestSupport.field(panel, "variables");
    }

    private void stop() {
        panel.setState(DapManager.State.SUSPENDED);
        panel.setCallStack(List.of(new DapModels.StackFrameInfo(7, "main", Path.of("Main.java"), 4, 1)));
        panel.setScopes(List.of(new DapModels.ScopeInfo("Locals", LOCALS, false)));
    }

    private DebugPanel.VarRow first() {
        return tree().getRoot().getChildren().getFirst().getValue();
    }

    private List<String> topRows() {
        return tree().getRoot().getChildren().stream()
                .map(i -> i.getValue().name())
                .toList();
    }

    private List<MenuItem> menuFor(TreeItem<DebugPanel.VarRow> item) {
        tree().getSelectionModel().select(item);
        ContextMenu menu = tree().getContextMenu();
        menu.getOnShowing().handle(new javafx.stage.WindowEvent(menu, javafx.stage.WindowEvent.WINDOW_SHOWING));
        return List.copyOf(menu.getItems());
    }

    private static MenuItem item(List<MenuItem> items, String text) {
        return items.stream()
                .filter(i -> text.equals(i.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + text + "' in "
                        + items.stream().map(MenuItem::getText).toList()));
    }

    @Test
    void anEvaluationIsTheFirstRowWithItsFieldsUnderIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stop();
            DebugExpressionField field = panel.evaluateField();
            assertEquals("Main.java", field.languageFile(), "highlighted in the language stopped in");
            field.setText("point");
            field.area()
                    .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertTrue(adapter.calls.contains("code point @7"), "evaluated as code in the selected frame");
            DebugPanel.VarRow row = first();
            assertEquals(DebugPanel.Kind.RESULT, row.kind());
            assertEquals("Point@12", row.value());
            TreeItem<DebugPanel.VarRow> result = tree().getRoot().getChildren().getFirst();
            assertTrue(result.isExpanded(), "an object opens at once");
            assertEquals(
                    List.of("x", "y"),
                    result.getChildren().stream().map(i -> i.getValue().name()).toList());
            assertEquals(1, adapter.refreshes, "what it changed is fetched again (B205)");

            panel.setScopes(List.of(new DapModels.ScopeInfo("Locals", LOCALS, false))); // that refresh's answer
            assertEquals("point", first().name(), "kept while the stop lasts");
            assertEquals(List.of("point", tr("debugpanel.watches"), "Locals"), topRows());
            assertEquals(
                    "point.x", DebugPanel.pathExpression(result.getChildren().getFirst()));

            field.setText("nope");
            field.area()
                    .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertTrue(first().failed(), "a failure in its place");
            assertEquals("nope cannot be resolved", first().value());
            assertEquals(1, adapter.refreshes, "a failure changed nothing");

            panel.setState(DapManager.State.RUNNING);
            assertFalse(topRows().contains("nope"), "the program moved on: its result was that stop's");
        });
    }

    @Test
    void notPausedTheRowSaysSo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setState(DapManager.State.RUNNING);
            panel.evaluateInVariables("x + 1");
            assertTrue(first().failed());
            assertEquals(tr("debugpanel.evaluateNeedsPause"), first().value());
            assertFalse(adapter.calls.stream().anyMatch(c -> c.startsWith("code")));
        });
    }

    @Test
    void addToWatchesAndTheResultRowsMenu() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stop();
            panel.evaluateInVariables("a + b");
            Button toWatches = FxTestSupport.field(panel, "evalToWatches");
            toWatches.fire();
            assertEquals(List.of("a + b"), panel.getWatches(), "the field emptied: the expression evaluated");
            panel.evaluateField().setText("count * 2");
            toWatches.fire();
            assertEquals(List.of("a + b", "count * 2"), panel.getWatches(), "the expression typed");

            TreeItem<DebugPanel.VarRow> result = tree().getRoot().getChildren().getFirst();
            List<MenuItem> menu = menuFor(result);
            item(menu, tr("debugpanel.clearResult")).fire();
            assertNull(tree().getRoot().getChildren().stream()
                    .filter(i -> i.getValue().kind() == DebugPanel.Kind.RESULT)
                    .findFirst()
                    .orElse(null));
        });
    }

    @Test
    void aPausedWatchIsListedNotEvaluatedUntilEvaluatedOnceOrResumed() throws Exception {
        FxTestSupport.runOnFx(() -> {
            panel.setWatches(List.of("cheap", "costly"));
            panel.setPausedWatches(List.of("costly", "gone"));
            assertEquals(List.of("costly"), panel.getPausedWatches(), "only watches there are");
            adapter.calls.clear();
            stop();
            assertTrue(adapter.calls.contains("watch cheap"));
            assertFalse(adapter.calls.contains("watch costly"), "paused: not evaluated at the stop");
            TreeItem<DebugPanel.VarRow> watches = tree().getRoot().getChildren().getFirst();
            TreeItem<DebugPanel.VarRow> costly = watches.getChildren().get(1);
            assertEquals(DapModels.ValueNote.PAUSED, costly.getValue().note());

            item(menuFor(costly), tr("debugpanel.evaluateOnce")).fire();
            assertTrue(adapter.calls.contains("watch costly"), "Evaluate Once");
            assertEquals(List.of("costly"), panel.getPausedWatches(), "still paused for the next stop");
            adapter.calls.clear();
            panel.setState(DapManager.State.RUNNING);
            stop();
            assertFalse(adapter.calls.contains("watch costly"), "the next stop: paused again");

            costly = tree().getRoot().getChildren().getFirst().getChildren().get(1);
            int before = watchChanges;
            item(menuFor(costly), tr("debugpanel.resumeWatch")).fire();
            assertTrue(adapter.calls.contains("watch costly"));
            assertEquals(List.of(), panel.getPausedWatches());
            assertEquals(before + 1, watchChanges, "persisted");

            item(
                            menuFor(tree().getRoot()
                                    .getChildren()
                                    .getFirst()
                                    .getChildren()
                                    .getFirst()),
                            tr("debugpanel.removeAllWatches"))
                    .fire();
            assertEquals(List.of(), panel.getWatches());
        });
    }
}
