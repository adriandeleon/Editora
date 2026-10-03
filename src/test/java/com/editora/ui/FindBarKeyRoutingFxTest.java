package com.editora.ui;

import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing chords typed in the Find bar must edit the Find field, never the document.
 *
 * <p>The scene-level {@code KeyDispatcher} is a capture filter, so it sees every key before the focused
 * control. With the caret in the Find field, {@code C-k} (Emacs) deleted the document's first line and left
 * the field untouched; in the CUA keymap the same route sent Ctrl+A / Ctrl+Z to the buffer. These fire real
 * key events at the real field of a wired window, under both keymaps.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindBarKeyRoutingFxTest {

    private static final String DOC = "first line\nsecond line\nthird line\n";

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private FindReplaceBar bar;
    private TextField findField;
    private TextField replaceField;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        bar = FxTestSupport.field(fx.controller, "findBar");
        findField = FxTestSupport.field(bar, "findField");
        replaceField = FxTestSupport.field(bar, "replaceField");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void useKeymap(String name) throws Exception {
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setKeymap(name);
            fx.windowManager.reloadSharedKeymap();
        });
    }

    /** Opens a fresh buffer holding {@link #DOC}, with the caret on the first line, and shows the find bar. */
    private EditorBuffer openWithFindBar() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            if (bar.isShown()) {
                bar.hideBar();
            }
            EditorBuffer b = new EditorBuffer();
            b.setContent(DOC);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().moveTo(0);
            registry.run("find.show");
            assertTrue(bar.isShown(), "precondition: find.show opened the bar");
            return b;
        });
    }

    /** Fires a KEY_PRESSED at {@code field}, so it travels through the scene's dispatcher filter first. */
    private static KeyEvent press(TextField field, KeyCode code, boolean shift, boolean ctrl, boolean alt) {
        KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false);
        field.fireEvent(e);
        return e;
    }

    /** The platform shortcut chord the GUI keymaps use: Ctrl+key, or Cmd+key on macOS (the {@code .mac} map). */
    private static KeyEvent shortcut(TextField field, KeyCode code) {
        boolean mac = com.editora.command.KeymapManager.isMac();
        KeyEvent e = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !mac, false, mac);
        field.fireEvent(e);
        return e;
    }

    private static void setField(TextField field, String text, int caret) {
        field.setText(text);
        field.positionCaret(caret);
    }

    @Test
    void emacsKillLineInTheFindFieldEditsTheFieldNotTheDocument() throws Exception {
        useKeymap("emacs");
        EditorBuffer b = openWithFindBar();
        FxTestSupport.runOnFx(() -> {
            setField(findField, "hello world", 5);
            press(findField, KeyCode.K, false, true, false); // C-k
        });
        assertEquals(DOC, FxTestSupport.callOnFx(b::getContent), "the document must be untouched");
        assertEquals("hello", FxTestSupport.callOnFx(findField::getText), "C-k killed to the end of the field");
    }

    @Test
    void emacsCaretAndDeleteChordsActOnTheFindField() throws Exception {
        useKeymap("emacs");
        EditorBuffer b = openWithFindBar();
        FxTestSupport.runOnFx(() -> {
            setField(findField, "hello world", 5);
            press(findField, KeyCode.A, false, true, false); // C-a: start of field
            assertEquals(0, findField.getCaretPosition());
            press(findField, KeyCode.D, false, true, false); // C-d: delete the char at the caret
            assertEquals("ello world", findField.getText());
            press(findField, KeyCode.E, false, true, false); // C-e: end of field
            assertEquals(findField.getLength(), findField.getCaretPosition());
            press(findField, KeyCode.T, false, true, false); // C-t (transpose-chars): the field ignores it…
        });
        assertEquals(DOC, FxTestSupport.callOnFx(b::getContent), "…and the document is never the one edited");
    }

    @Test
    void emacsChordsActOnTheReplaceFieldToo() throws Exception {
        useKeymap("emacs");
        EditorBuffer b = openWithFindBar();
        FxTestSupport.runOnFx(() -> {
            setField(replaceField, "swap me out", 4);
            press(replaceField, KeyCode.K, false, true, false);
        });
        assertEquals(DOC, FxTestSupport.callOnFx(b::getContent));
        assertEquals("swap", FxTestSupport.callOnFx(replaceField::getText));
    }

    @Test
    void theUniversalArgumentIsNotStartedFromTheFindField() throws Exception {
        // C-u 3 x in the Find field used to insert "xxx" into the document (the prefix argument's self-insert).
        useKeymap("emacs");
        EditorBuffer b = openWithFindBar();
        FxTestSupport.runOnFx(() -> {
            setField(findField, "", 0);
            java.util.List<String> reached = new java.util.ArrayList<>();
            javafx.event.EventHandler<KeyEvent> recorder = e -> reached.add(e.getCharacter());
            findField.addEventHandler(KeyEvent.KEY_TYPED, recorder);
            try {
                press(findField, KeyCode.U, false, true, false); // C-u
                press(findField, KeyCode.DIGIT3, false, false, false);
                press(findField, KeyCode.X, false, false, false);
                // The character itself arrives as KEY_TYPED; with a prefix argument pending the dispatcher
                // swallowed it and inserted three copies into the document instead.
                findField.fireEvent(
                        new KeyEvent(KeyEvent.KEY_TYPED, "x", "", KeyCode.UNDEFINED, false, false, false, false));
                assertEquals(java.util.List.of("x"), reached, "the character reaches the field to be typed");
            } finally {
                findField.removeEventHandler(KeyEvent.KEY_TYPED, recorder);
            }
        });
        assertEquals(DOC, FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void cuaSelectAllAndUndoStayInTheFindField() throws Exception {
        useKeymap("cua");
        try {
            EditorBuffer b = openWithFindBar();
            FxTestSupport.runOnFx(() -> {
                b.getArea().insertText(0, "X"); // an undoable document edit Ctrl+Z must NOT revert
                b.getArea().moveTo(0);
                setField(findField, "", 0);
                findField.insertText(0, "needle"); // an undoable field edit Ctrl+Z SHOULD revert
                shortcut(findField, KeyCode.A); // Ctrl+A
                assertEquals("needle", findField.getSelectedText(), "Ctrl+A selected the field's text");
                assertEquals("", b.getArea().getSelectedText(), "…not the whole document");
                shortcut(findField, KeyCode.Z); // Ctrl+Z
            });
            assertEquals("X" + DOC, FxTestSupport.callOnFx(b::getContent), "Ctrl+Z must not undo the document");
            assertEquals("", FxTestSupport.callOnFx(findField::getText), "Ctrl+Z undid the field's own edit");
        } finally {
            useKeymap("emacs");
        }
    }

    @Test
    void findsOwnChordsStillWorkFromTheField() throws Exception {
        useKeymap("emacs");
        EditorBuffer b = openWithFindBar();
        FxTestSupport.runOnFx(() -> {
            setField(findField, "line", 4);
            FxTestSupport.invoke(bar, "recompute"); // skip the typing debounce
            int first = b.getArea().getSelection().getStart();
            press(findField, KeyCode.S, false, true, false); // C-s = find.show = next match: stays global
            assertTrue(b.getArea().getSelection().getStart() > first, "C-s advanced to the next match");
            press(findField, KeyCode.G, false, true, false); // C-g = edit.cancel: still closes the bar
            assertFalse(bar.isShown(), "cancel stays global so C-g closes the find bar from its field");
        });
        assertEquals(DOC, FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void escapeStillClosesTheBar() throws Exception {
        useKeymap("emacs");
        openWithFindBar();
        FxTestSupport.runOnFx(() -> press(findField, KeyCode.ESCAPE, false, false, false));
        assertFalse(FxTestSupport.callOnFx(bar::isShown));
    }
}
