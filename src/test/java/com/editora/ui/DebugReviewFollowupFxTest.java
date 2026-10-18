package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java-debugging follow-ups of the UI (follow-ups 5 and 6): completion in the evaluate field, and the
 * expandable value popup — each against a scripted adapter, in a real stage.
 */
@Tag("fx")
class DebugReviewFollowupFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static KeyEvent key(KeyCode code, boolean control, boolean shift) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false);
    }

    /** The adapter's side of the panel, recording completion requests and answering like java-debug. */
    private static DebugPanel.Actions actions(List<String> asked) {
        return new DebugPanel.Actions() {
            public void start() {}

            public void pause() {}

            public void stepOver() {}

            public void stepInto() {}

            public void stepOut() {}

            public void runToCursor() {}

            public void stop() {}

            public void restart() {}

            public void selectThread(int threadId) {}

            public void selectFrame(DapModels.StackFrameInfo frame) {}

            public void loadChildren(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
                cb.accept(List.of());
            }

            public void evaluate(String expression, int frameId, Consumer<String> cb) {
                cb.accept("val");
            }

            public void evaluateWatch(String expression, int frameId, Consumer<DapModels.EvalResult> cb) {
                cb.accept(new DapModels.EvalResult("w", 0, null));
            }

            public void setVariable(int parentRef, String name, String value, Consumer<String> cb) {}

            @Override
            public void complete(String text, int caret, int frameId, Consumer<List<DapModels.CompletionTarget>> cb) {
                asked.add(text + "@" + caret + "#" + frameId);
                cb.accept(List.of(
                        new DapModels.CompletionTarget(
                                "subList(int a, int b) : List<String>", "subList()", "function", null, "1", -1, -1),
                        new DapModels.CompletionTarget("size() : int", "size()", "function", null, "1", -1, -1)));
            }
        };
    }

    /** Follow-up 5: Ctrl+Space and "." ask the adapter; the list is worked from the keyboard; Up/Down stay history. */
    @Test
    void theEvaluateFieldCompletesWhilePausedAndSaysWhyNotOtherwise() throws Exception {
        List<String> asked = new ArrayList<>();
        Object[] made = FxTestSupport.callOnFx(() -> {
            DebugPanel panel = new DebugPanel(actions(asked));
            Stage stage = new Stage();
            stage.setScene(new Scene(panel, 1000, 320));
            stage.show();
            return new Object[] {panel, stage};
        });
        DebugPanel panel = (DebugPanel) made[0];
        Stage stage = (Stage) made[1];
        try {
            FxTestSupport.runOnFx(() -> {
                panel.setState(DapManager.State.SUSPENDED);
                panel.setCallStack(List.of(new DapModels.StackFrameInfo(3, "main", null, 1, 1)));
                TextField eval = FxTestSupport.field(panel, "evalInput");
                DebugCompletion completion = FxTestSupport.field(panel, "evalCompletion");
                eval.requestFocus();
                eval.setText("names.s");
                eval.end();
                eval.fireEvent(key(KeyCode.SPACE, true, false));
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                TextField eval = FxTestSupport.field(panel, "evalInput");
                DebugCompletion completion = FxTestSupport.field(panel, "evalCompletion");
                assertEquals(List.of("names.s@7#3"), asked, "the field's text, the caret and the selected frame");
                assertTrue(completion.isShowing());
                assertEquals("size()", completion.popup().selected().label(), "sorted by name, the detail apart");
                eval.fireEvent(key(KeyCode.DOWN, false, false));
                assertEquals(
                        "subList(int a, int b)", completion.popup().selected().label());
                eval.fireEvent(key(KeyCode.ENTER, false, false));
                assertEquals("names.subList()", eval.getText());
                assertEquals(14, eval.getCaretPosition(), "between the parentheses");
                assertFalse(completion.isShowing());
                assertEquals(
                        List.of(),
                        FxTestSupport.<List<?>>field(FxTestSupport.field(panel, "history"), "entries"),
                        "Enter accepted, it did not evaluate");

                // Closed, Up/Down are the history again.
                eval.setText("count");
                eval.fireEvent(new javafx.event.ActionEvent());
                eval.setText("x");
                eval.fireEvent(key(KeyCode.UP, false, false));
                assertEquals("count", eval.getText());

                // Typing a "." asks by itself.
                asked.clear();
                eval.setText("");
                eval.appendText("names"); // as typing does: the caret follows
                eval.fireEvent(
                        new KeyEvent(KeyEvent.KEY_TYPED, ".", ".", KeyCode.UNDEFINED, false, false, false, false));
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertEquals(List.of("names.@6#3"), asked);
                TextField eval = FxTestSupport.field(panel, "evalInput");
                DebugCompletion completion = FxTestSupport.field(panel, "evalCompletion");
                eval.fireEvent(key(KeyCode.ESCAPE, false, false));
                assertFalse(completion.isShowing(), "Esc closes the list");

                // Running: no frame to complete in — said, not silence.
                panel.setState(DapManager.State.RUNNING);
                asked.clear();
                eval.requestFocus();
                eval.fireEvent(key(KeyCode.SPACE, true, false));
                assertEquals(List.of(), asked);
                assertEquals(tr("debug.complete.needsPause"), completion.note());
            });
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /** The window side of the value popup: canned evaluations and children, a record of watches added. */
    private static final class Values implements DebugValuePopup.Source {
        final List<String> watches = new ArrayList<>();
        final List<String> requests = new ArrayList<>();

        @Override
        public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
            cb.accept(
                    switch (expression) {
                        case "names" -> new DapModels.EvalResult("ArrayList@10 size=3", 5, "ArrayList", 0, 3, false);
                        case "big" -> new DapModels.EvalResult("int[250]@4", 9, "int[]", 0, 250, false);
                        case "total" -> new DapModels.EvalResult("14", 0, "int");
                        default -> DapModels.EvalResult.failure("cannot");
                    });
        }

        @Override
        public void children(int ref, String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
            requests.add(ref + ":" + filter + ":" + start + "+" + count);
            List<DapModels.VariableInfo> out = new ArrayList<>();
            if (ref == 5) {
                out.add(new DapModels.VariableInfo("0", "\"ada\"", "String", 6));
                out.add(new DapModels.VariableInfo("1", "\"linus\"", "String", 7));
                out.add(new DapModels.VariableInfo("2", "\"grace\"", "String", 8));
            } else if (ref == 6) {
                out.add(new DapModels.VariableInfo("hash", "0", "int", 0));
            } else if (ref == 9) {
                for (int i = start; i < Math.min(250, start + count); i++) {
                    out.add(new DapModels.VariableInfo(Integer.toString(i), "0", "int", 0));
                }
            }
            cb.accept(out);
        }

        @Override
        public void addWatch(String expression) {
            watches.add(expression);
        }
    }

    /** Follow-up 6: an object unfolds in the popup; the keyboard works it; a plain value is one line. */
    @Test
    void theValuePopupUnfoldsAnObjectPagesAnArrayAndKeepsAPlainValueOnOneLine() throws Exception {
        Values values = new Values();
        Object[] made = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.getArea().replaceText("names.size();\nint total = big.length;\n");
            Stage stage = new Stage();
            stage.setScene(new Scene(new javafx.scene.layout.VBox(b.getNode()), 800, 400));
            stage.show();
            return new Object[] {b, stage, new DebugValuePopup(values)};
        });
        EditorBuffer b = (EditorBuffer) made[0];
        Stage stage = (Stage) made[1];
        DebugValuePopup popup = (DebugValuePopup) made[2];
        try {
            FxTestSupport.runOnFx(() -> {
                b.setDebugValuePresenter(popup);
                b.setDebugHoverEvaluator((e, cb) -> cb.accept(null));
                b.setDebugHoverActive(true);
                CodeArea area = b.getArea();
                area.moveTo(0, 2); // on "names"
                assertTrue(b.showDebugValueAtCaret());
                assertTrue(popup.isShowing());
                TreeItem<DebugPanel.VarRow> root = popup.tree().getRoot();
                assertEquals("names", root.getValue().name());
                assertTrue(root.isExpanded(), "the object's fields at once");
                assertEquals(
                        List.of("0", "1", "2"),
                        root.getChildren().stream()
                                .map(i -> i.getValue().name())
                                .toList());

                // The keyboard, with focus still in the editor: down to "0", unfold it, Shift+Enter to Watches.
                area.fireEvent(key(KeyCode.DOWN, false, false));
                area.fireEvent(key(KeyCode.RIGHT, false, false));
                assertEquals(
                        List.of("hash"),
                        root.getChildren().getFirst().getChildren().stream()
                                .map(i -> i.getValue().name())
                                .toList());
                area.fireEvent(key(KeyCode.ENTER, false, true));
                assertEquals(List.of("names[0]"), values.watches);
                assertFalse(popup.isShowing(), "adding a watch closes the popup");

                // A 250-element array: a page of 100 and a "show more" row; Enter on it loads the next page.
                area.moveTo(1, area.getParagraph(1).getText().indexOf("big") + 1);
                b.showDebugValueAtCaret();
                TreeItem<DebugPanel.VarRow> big = popup.tree().getRoot();
                assertEquals(DebugValues.PAGE_SIZE + 1, big.getChildren().size());
                assertEquals(
                        DebugPanel.Kind.MORE,
                        big.getChildren().getLast().getValue().kind());
                popup.tree().getSelectionModel().select(big.getChildren().getLast());
                area.fireEvent(key(KeyCode.ENTER, false, false));
                assertEquals(2 * DebugValues.PAGE_SIZE + 1, big.getChildren().size());
                assertTrue(values.requests.contains("9:indexed:100+100"), values.requests.toString());
                area.fireEvent(key(KeyCode.ESCAPE, false, false));
                assertFalse(popup.isShowing(), "Esc closes it");

                // A plain value stays a one-line popup.
                area.moveTo(1, area.getParagraph(1).getText().indexOf("total") + 1);
                b.showDebugValueAtCaret();
                assertTrue(popup.isShowing());
                assertEquals("total = 14", popup.line().getText());
                assertFalse(popup.tree().getParent() != null, "no tree for a plain value");

                // Resuming closes it.
                b.setDebugHoverActive(false);
                assertFalse(popup.isShowing());
                assertNotNull(popup.popup());
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                popup.hide();
                stage.close();
                b.dispose();
            });
        }
    }
}
