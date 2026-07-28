package com.editora.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.CodeAction;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the caret-anchored quick-fix list (#767): that it opens, that the editor's key filter drives it,
 * and that accepting hands back the action the user selected.
 *
 * <p>The protocol side already worked and is not retested here — what changed is the presentation, and the
 * risk in a focus-less popup is entirely in the key wiring: a list that appears but cannot be driven, or one
 * that swallows keys after it has closed, are both invisible to a compile.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeActionPopupFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static final List<CodeAction> ACTIONS = List.of(
            new CodeAction("Remove unused import", "quickfix", false, "A"),
            new CodeAction("Add missing cast", "quickfix", true, "B"),
            new CodeAction("Extract method", "refactor.extract", false, "C"));

    private EditorBuffer openBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("class A {\n  void x() {}\n}\n");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            // Autocomplete off for these buffers. It is caret-anchored and claims the same key ownership,
            // so a request in flight can land *after* the quick-fix list closes and legitimately take the
            // chords back — which would make the key-release assertion below flap for a reason that has
            // nothing to do with what is under test.
            b.setAutocomplete(false, false, false, false);
            CodeArea area = FxTestSupport.field(b, "area");
            area.requestFocus();
            area.moveTo(12);
            return b;
        });
    }

    /** The server's preferred action is selected on open, so Enter alone does the likely-right thing. */
    @Test
    void thePopupOpensAtTheCaretWithThePreferredActionSelected() throws Exception {
        EditorBuffer b = openBuffer();
        AtomicReference<CodeAction> accepted = new AtomicReference<>();

        FxTestSupport.runOnFx(() -> b.showCodeActions(ACTIONS, accepted::set));

        assertTrue(FxTestSupport.callOnFx(b::codeActionsShowing), "the list is up");
        FxTestSupport.runOnFx(() -> press(b, KeyCode.ENTER));
        assertEquals("B", accepted.get().token(), "Enter took the preferred action");
        assertFalse(FxTestSupport.callOnFx(b::codeActionsShowing), "and the list closed");

        close(b);
    }

    /** Arrow and Emacs chords both move the selection — the editor owns the keys while the list is open. */
    @Test
    void theKeyboardDrivesTheSelection() throws Exception {
        EditorBuffer b = openBuffer();
        AtomicReference<CodeAction> accepted = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> b.showCodeActions(ACTIONS, accepted::set));

        FxTestSupport.runOnFx(() -> press(b, KeyCode.DOWN)); // B -> C
        FxTestSupport.runOnFx(() -> press(b, KeyCode.ENTER));
        assertEquals("C", accepted.get().token(), "Down moved to the next action");

        accepted.set(null);
        FxTestSupport.runOnFx(() -> b.showCodeActions(ACTIONS, accepted::set));
        FxTestSupport.runOnFx(() -> pressCtrl(b, KeyCode.P)); // B -> A
        FxTestSupport.runOnFx(() -> press(b, KeyCode.ENTER));
        assertEquals("A", accepted.get().token(), "C-p moved to the previous action");

        close(b);
    }

    /** Escape dismisses without applying, and releases the key ownership it took. */
    @Test
    void escapeDismissesWithoutApplying() throws Exception {
        EditorBuffer b = openBuffer();
        AtomicReference<CodeAction> accepted = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> b.showCodeActions(ACTIONS, accepted::set));

        FxTestSupport.runOnFx(() -> press(b, KeyCode.ESCAPE));

        assertFalse(FxTestSupport.callOnFx(b::codeActionsShowing), "dismissed");
        assertNull(accepted.get(), "nothing was applied");
        assertNull(
                FxTestSupport.callOnFx(() -> {
                    CodeArea area = FxTestSupport.field(b, "area");
                    return area.getProperties().get("editora.ownsKeys");
                }),
                "the editor chords were handed back to the dispatcher");

        close(b);
    }

    /**
     * A keystroke that is not list navigation dismisses the list. It was computed for one caret position, so
     * leaving it open while the caret or the text moves would offer fixes for somewhere else.
     */
    @Test
    void anUnrelatedKeystrokeDismissesTheList() throws Exception {
        EditorBuffer b = openBuffer();
        FxTestSupport.runOnFx(() -> b.showCodeActions(ACTIONS, a -> {}));

        FxTestSupport.runOnFx(() -> press(b, KeyCode.LEFT));

        assertFalse(FxTestSupport.callOnFx(b::codeActionsShowing), "moving the caret closed the list");
        close(b);
    }

    private static void press(EditorBuffer b, KeyCode code) {
        fire(b, code, false);
    }

    private static void pressCtrl(EditorBuffer b, KeyCode code) {
        fire(b, code, true);
    }

    /** Fires a real KEY_PRESSED at the focused area, so the buffer's own filter chain is what runs. */
    private static void fire(EditorBuffer b, KeyCode code, boolean control) {
        CodeArea area = b.getFocusedArea();
        area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false));
    }

    private void close(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> {
            b.hideCodeActions();
            b.markClean();
            javafx.scene.control.TabPane pane = FxTestSupport.field(fx.controller, "tabPane");
            javafx.scene.control.Tab sel = pane.getSelectionModel().getSelectedItem();
            if (sel != null) {
                FxTestSupport.call(fx.controller, "closeTab", new Class[] {javafx.scene.control.Tab.class}, sel);
            }
        });
    }
}
