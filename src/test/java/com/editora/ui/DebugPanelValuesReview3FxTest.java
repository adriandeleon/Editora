package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.editora.dap.ConsoleEvaluation;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java debugging review 3 in the Debug panel: a row the toString() budget left unloaded loads on a click or an expand
 * (B201), the values are fetched again after a console evaluation and after Set Value (B205), and Set Value hands
 * the adapter what to assign when it cannot set the type (B208).
 */
@Tag("fx")
class DebugPanelValuesReview3FxTest {

    private static final int LOCALS = 1000;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private final List<String> calls = new ArrayList<>();
    private DebugPanel panel;

    private static final DapModels.VariableInfo LOOPS = new DapModels.VariableInfo(
            "loops", "Main$Loops@12", "Main$Loops", 7, 0, 0, true, DapModels.ValueNote.NOT_LOADED);

    @BeforeEach
    void setUp() throws Exception {
        calls.clear();
        panel = FxTestSupport.callOnFx(() -> {
            DebugPanel p = new DebugPanel(new DebugPanel.Actions() {
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
                    cb.accept(
                            ref == LOCALS
                                    ? List.of(LOOPS, new DapModels.VariableInfo("boxed", "42", "Integer", 0))
                                    : List.of(new DapModels.VariableInfo("n", "1", "int", 0)));
                }

                @Override
                public void evaluate(String expression, int frameId, Consumer<ConsoleEvaluation> cb) {
                    calls.add("evaluate " + expression);
                    cb.accept(
                            expression.startsWith("boom")
                                    ? ConsoleEvaluation.failure("no")
                                    : ConsoleEvaluation.value("1"));
                }

                @Override
                public void evaluateWatch(String expression, int frameId, Consumer<DapModels.EvalResult> cb) {}

                @Override
                public void setVariable(int parentRef, String name, String value, Consumer<String> cb) {
                    calls.add("set " + name);
                }

                @Override
                public void setVariable(
                        int parentRef, String name, String value, String assignTo, int frameId, Consumer<String> cb) {
                    calls.add("assign " + assignTo + " = " + value + " @" + frameId);
                    cb.accept(value);
                }

                @Override
                public void loadValue(DapModels.VariableInfo row, Consumer<DapModels.VariableInfo> cb) {
                    calls.add("load " + row.value() + " ref " + row.variablesReference());
                    cb.accept(new DapModels.VariableInfo(row.name(), row.value() + " \"spins\"", row.type(), 70));
                }

                @Override
                public boolean refreshValues() {
                    calls.add("refresh");
                    return true;
                }
            });
            p.setPrompt((title, label, initial, onAccept) -> onAccept.accept("44"));
            return p;
        });
        FxTestSupport.runOnFx(() -> {
            panel.setState(DapManager.State.SUSPENDED);
            panel.setCallStack(
                    List.of(new DapModels.StackFrameInfo(5, "Main.main(String[])", Path.of("Main.java"), 9, 1)));
            panel.setScopes(List.of(new DapModels.ScopeInfo("Locals", LOCALS, false)));
        });
        FxTestSupport.drainFx();
        calls.clear();
    }

    private TreeView<DebugPanel.VarRow> tree() {
        return FxTestSupport.field(panel, "variables");
    }

    private TreeItem<DebugPanel.VarRow> local(String name) {
        TreeItem<DebugPanel.VarRow> locals = tree().getRoot().getChildren().stream()
                .filter(c -> c.getValue().name().equals("Locals"))
                .findFirst()
                .orElseThrow();
        return locals.getChildren().stream()
                .filter(c -> c.getValue().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static MouseEvent click(int clicks) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                5,
                5,
                5,
                5,
                MouseButton.PRIMARY,
                clicks,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                null);
    }

    @Test
    void aClickLoadsARowTheBudgetLeftAndPutsItInPlace() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertTrue(local("loops").getValue().notLoaded());
            tree().getSelectionModel().select(local("loops"));
            tree().fireEvent(click(1));
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            DebugPanel.VarRow loaded = local("loops").getValue();
            assertEquals("Main$Loops@12 \"spins\"", loaded.value());
            assertFalse(loaded.notLoaded());
            assertEquals(local("loops"), tree().getSelectionModel().getSelectedItem(), "still selected");
        });
        assertEquals(List.of("load Main$Loops@12 ref 7"), calls);
    }

    @Test
    void expandingARowTheBudgetLeftLoadsItThenShowsItsFields() throws Exception {
        FxTestSupport.runOnFx(() -> local("loops").setExpanded(true));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            TreeItem<DebugPanel.VarRow> loaded = local("loops");
            assertTrue(loaded.isExpanded());
            assertEquals(1, loaded.getChildren().size());
        });
        assertEquals(List.of("load Main$Loops@12 ref 7", "children 70"), calls);
    }

    @Test
    void aConsoleEvaluationThatWorkedFetchesTheValuesAgain() throws Exception {
        TextField eval = FxTestSupport.field(panel, "evalInput");
        FxTestSupport.runOnFx(() -> {
            eval.setText("cnt.inc()");
            eval.fireEvent(new javafx.event.ActionEvent());
            eval.setText("boom()");
            eval.fireEvent(new javafx.event.ActionEvent());
        });
        FxTestSupport.drainFx();
        assertEquals(List.of("evaluate cnt.inc()", "refresh", "evaluate boom()"), calls, "a failure changed nothing");
    }

    @Test
    void setValueGivesTheAssignmentAndRefreshes() throws Exception {
        FxTestSupport.runOnFx(() -> {
            tree().getSelectionModel().select(local("boxed"));
            FxTestSupport.call(panel, "setValuePrompt", new Class<?>[0]);
        });
        FxTestSupport.drainFx();
        assertEquals(List.of("assign boxed = 44 @5", "refresh"), calls);
        assertEquals(
                "44", FxTestSupport.callOnFx(() -> local("boxed").getValue().value()));
    }
}
