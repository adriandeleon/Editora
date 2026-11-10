package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Evaluate window (IntelliJ's Evaluate Expression): opened with an expression, Enter evaluates it in the selected
 * frame and the Result is a tree of its fields; a failure shows the reason; the program moving on leaves the result to
 * read but not to open; and its rows go to Watches.
 */
@Tag("fx")
class DebugEvaluateWindowFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Fake implements DebugEvaluateWindow.Source {
        boolean paused = true;
        final List<String> asked = new ArrayList<>();
        final List<String> watched = new ArrayList<>();
        final List<Boolean> evaluated = new ArrayList<>();

        @Override
        public boolean paused() {
            return paused;
        }

        @Override
        public String frameName() {
            return "main:4, Main.java";
        }

        @Override
        public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
            asked.add(expression);
            cb.accept(
                    expression.equals("point")
                            ? new DapModels.EvalResult("Point@1", 40, "Point", 2, 0, false)
                            : DapModels.EvalResult.failure("cannot evaluate " + expression));
        }

        @Override
        public void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb) {
            cb.accept(List.of());
        }

        @Override
        public void addWatch(String expression) {
            watched.add(expression);
        }

        @Override
        public void evaluated(boolean succeeded) {
            evaluated.add(succeeded);
        }

        @Override
        public void children(
                int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
            cb.accept(List.of(
                    new DapModels.VariableInfo("x", "1", "int", 0), new DapModels.VariableInfo("y", "2", "int", 0)));
        }
    }

    @Test
    void enterEvaluatesInTheFrameAndTheResultIsATree() throws Exception {
        Fake source = new Fake();
        FxTestSupport.runOnFx(() -> {
            Stage owner = new Stage();
            owner.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(), 300, 200));
            owner.show();
            DebugEvaluateWindow window = new DebugEvaluateWindow(source, new DebugPanelText.History());
            window.show(owner, "point", "Main.java", 0);
            assertTrue(window.isShowing());
            assertEquals("point", window.field().getText(), "opened with the selection");
            assertEquals(tr("debugeval.inFrame", "main:4, Main.java"), window.contextText());

            window.field()
                    .area()
                    .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            assertEquals(List.of("point"), source.asked);
            assertEquals(List.of(true), source.evaluated);
            TreeItem<DebugPanel.VarRow> root = window.resultTree().getRoot();
            assertEquals("Point@1", root.getValue().value());
            assertTrue(root.isExpanded());
            assertEquals(2, root.getChildren().size(), "its fields");
            window.resultTree().getSelectionModel().select(root.getChildren().get(1));
            assertEquals("point.y", window.selectedExpression());

            window.evaluate("missing");
            assertTrue(window.resultTree().getRoot().getValue().failed());
            assertEquals(
                    "cannot evaluate missing",
                    window.resultTree().getRoot().getValue().value());

            window.evaluate("point");
            window.sessionChanged(); // Continue: that stop's references are gone
            assertTrue(window.resultTree().getRoot().isLeaf(), "kept to read, no longer to open");

            source.paused = false;
            window.evaluate("point");
            assertEquals(3, source.asked.size(), "not paused: not sent");
            assertEquals(
                    tr("debugpanel.evaluateNeedsPause"),
                    window.resultTree().getRoot().getValue().value());
            assertEquals(tr("debugeval.notPaused"), window.contextText());

            window.close();
            assertFalse(window.isShowing());
            owner.close();
        });
    }
}
