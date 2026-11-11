package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.KeymapManager;
import com.editora.dap.DapModels;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debugger's expression field as it is typed in: Enter evaluates one line, Shift+Enter makes a code fragment of
 * several lines (Enter then breaks lines keeping the indentation, Ctrl+Enter evaluates), the history walks back and
 * forth, the Emacs keymap's motion and kills work, and the text is highlighted in the stopped frame's language.
 */
@Tag("fx")
class DebugExpressionFieldFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private final List<String> submitted = new ArrayList<>();
    private DebugExpressionField field;

    @BeforeEach
    void setUp() throws Exception {
        submitted.clear();
        KeymapManager emacs = new KeymapManager();
        emacs.loadNamed("emacs");
        DebugPanelText.History history = new DebugPanelText.History();
        field = FxTestSupport.callOnFx(() -> new DebugExpressionField(
                new DebugExpressionField.Owner() {
                    @Override
                    public boolean paused() {
                        return true;
                    }

                    @Override
                    public void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb) {
                        cb.accept(List.of());
                    }

                    @Override
                    public void submit(String expression) {
                        submitted.add(expression);
                    }
                },
                history,
                () -> emacs));
    }

    private static void press(CodeArea area, KeyCode code, boolean shift, boolean ctrl, boolean alt) {
        area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false));
    }

    @Test
    void enterEvaluatesOneLineAndShiftEnterMakesACodeFragment() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = field.area();
            field.setText("list.size()");
            press(area, KeyCode.ENTER, false, false, false);
            assertEquals(List.of("list.size()"), submitted, "Enter on one line evaluates");

            field.setText("if (x) {");
            press(area, KeyCode.ENTER, true, false, false);
            assertTrue(field.isMultiLine(), "Shift+Enter: several lines");
            area.moveTo(area.getLength());
            press(area, KeyCode.ENTER, false, false, false);
            area.insertText(area.getCaretPosition(), "    return 1;");
            press(area, KeyCode.ENTER, false, false, false);
            assertEquals("if (x) {\n    return 1;\n    ", area.getText(), "Enter breaks the line, keeping its indent");
            assertEquals(1, submitted.size(), "and does not evaluate");
            press(area, KeyCode.ENTER, false, true, false);
            assertEquals("if (x) {\n    return 1;", submitted.getLast(), "Ctrl+Enter evaluates the fragment");

            press(area, KeyCode.ENTER, true, false, false);
            assertTrue(field.isMultiLine(), "several lines do not collapse into one");
            field.clear();
            assertFalse(field.isMultiLine());
            area.replaceText("a\nb"); // pasted
            assertTrue(field.isMultiLine(), "text of several lines expands the field");
        });
    }

    @Test
    void upAndDownWalkTheHistoryAndTheEmacsKeysEdit() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = field.area();
            field.setText("first");
            press(area, KeyCode.ENTER, false, false, false);
            field.setText("second");
            press(area, KeyCode.ENTER, false, false, false);
            field.setText("draft");
            press(area, KeyCode.UP, false, false, false);
            assertEquals("second", field.getText());
            press(area, KeyCode.P, false, true, false); // Emacs C-p on the first line: older
            assertEquals("first", field.getText());
            press(area, KeyCode.N, false, false, true); // M-n
            assertEquals("second", field.getText());
            press(area, KeyCode.DOWN, false, false, false);
            assertEquals("draft", field.getText(), "past the newest: what was being typed");

            field.setText("obj.items.size");
            press(area, KeyCode.A, false, true, false); // C-a
            assertEquals(0, area.getCaretPosition(), "C-a: line start, not select all");
            press(area, KeyCode.F, false, false, true); // M-f
            assertEquals(3, area.getCaretPosition(), "M-f: past the word");
            press(area, KeyCode.E, false, true, false); // C-e
            assertEquals(area.getLength(), area.getCaretPosition());
            press(area, KeyCode.B, false, true, false); // C-b
            press(area, KeyCode.BACK_SPACE, false, false, true); // M-backspace: backward-kill-word
            assertEquals("obj.items.e", area.getText());
            area.moveTo(4);
            press(area, KeyCode.K, false, true, false); // C-k
            assertEquals("obj.", area.getText());
            press(area, KeyCode.Y, false, true, false); // C-y: the kill back
            assertEquals("obj.items.e", area.getText());
        });
    }

    @Test
    void theExpressionIsHighlightedInTheFramesLanguage() throws Exception {
        FxTestSupport.runOnFx(() -> {
            field.setLanguageFile("Main.java");
            field.setText("return new int[] {1};");
        });
        long deadline = System.nanoTime() + 10_000_000_000L;
        boolean styled = false;
        while (!styled && System.nanoTime() < deadline) {
            Thread.sleep(50);
            styled = FxTestSupport.callOnFx(() -> {
                return field.area().getStyleOfChar(1).contains("keyword");
            });
        }
        assertTrue(styled, "`return` styled as a keyword once the grammar has loaded");
    }
}
