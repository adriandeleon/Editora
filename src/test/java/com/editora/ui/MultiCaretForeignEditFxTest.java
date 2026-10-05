package com.editora.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Multiple carets through the real window, registry and key events, around the things the fork's caret
 * manager gets wrong on its own: an edit it did not make (Undo, a primary-caret command, a language-server
 * edit, a reload) used to leave every extra caret with a phantom selection that the next key typed over;
 * Backspace and the arrow keys split surrogate pairs; Add Caret Below never got past the second line.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiCaretForeignEditFxTest {

    private static final String EMOJI = "😀";

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String... ids) throws Exception {
        for (String id : ids) {
            FxTestSupport.runOnFx(() -> registry.run(id));
        }
    }

    private static Object manager(EditorBuffer b) {
        return FxTestSupport.call(FxTestSupport.field(b, "multiCaret"), "getManager", new Class<?>[] {});
    }

    /** A buffer in the window with the primary caret at {@code caret} and an extra one at each of {@code extras}. */
    private EditorBuffer carets(String name, String content, int caret, int... extras) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(java.nio.file.Path.of("/nonexistent-multicaret/" + name));
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.setMultiCaretEnabled(true);
            b.getArea().requestFocus();
            b.getArea().moveTo(caret);
            for (int extra : extras) {
                FxTestSupport.call(manager(b), "addCaretAt", new Class[] {int.class}, extra);
            }
            return b;
        });
    }

    private void type(EditorBuffer b, String s) throws Exception {
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            FxTestSupport.runOnFx(() -> b.getArea()
                    .fireEvent(
                            new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, false, false, false)));
        }
    }

    private void press(EditorBuffer b, KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() ->
                b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    /** Sorted offsets of every caret, primary included. */
    private List<Integer> positions(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<Integer> out = new ArrayList<>();
            out.add(b.getArea().getCaretPosition());
            List<?> extras = FxTestSupport.field(manager(b), "extras");
            for (Object cws : extras) {
                out.add((Integer) FxTestSupport.call(FxTestSupport.field(cws, "caret"), "getPosition", new Class[] {}));
            }
            Collections.sort(out);
            return out;
        });
    }

    // --- an edit the caret manager did not make ---------------------------------------------------

    @Test
    void typingAfterUndoTypesAtEveryCaretAndDeletesNothing() throws Exception {
        EditorBuffer b = carets("a.txt", "aaa\nbbb\nccc", 3, 7, 11);
        type(b, "xy");
        assertEquals("aaaxy\nbbbxy\ncccxy", text(b));
        run("edit.undo");
        String undone = text(b);
        type(b, "z");
        // Whatever the undo granularity, the next character lands after each caret: nothing else goes.
        assertEquals(undone.replace("\n", "z\n") + "z", text(b));
        assertEquals(3, positions(b).size(), "the three carets survive the undo");
    }

    @Test
    void typingAfterUndoThenRedoKeepsEveryCaretOnItsOwnLine() throws Exception {
        EditorBuffer b = carets("a.txt", "aaa\nbbb\nccc", 3, 7, 11);
        type(b, "xy");
        run("edit.undo", "edit.redo");
        assertEquals("aaaxy\nbbbxy\ncccxy", text(b));
        // Redo used to drop the primary caret onto the last caret's place.
        assertEquals(List.of(5, 11, 17), positions(b));
        type(b, "z");
        assertEquals("aaaxyz\nbbbxyz\ncccxyz", text(b));
    }

    @Test
    void typingAfterAPrimaryCaretCommandDeletesNothing() throws Exception {
        EditorBuffer b = carets("a.txt", "aaa\nbbb\nccc", 0, 4, 8);
        run("edit.killLine"); // C-k acts at the primary caret only
        assertEquals("\nbbb\nccc", text(b));
        type(b, "Z");
        assertEquals("Z\nZbbb\nZccc", text(b), "bbb and ccc were never selected, so they stay");
    }

    @Test
    void typingAfterALanguageServerEditDeletesNothing() throws Exception {
        EditorBuffer b = carets("a.java", "aaa\nbbb\nccc", 0, 4, 8);
        FxTestSupport.runOnFx(() -> b.applyLspEdits(List.of(new LspTextEdit(0, 0, 0, 0, "import x;\n"))));
        assertEquals("import x;\naaa\nbbb\nccc", text(b));
        type(b, "z");
        String after = text(b);
        assertEquals("import x;", after.substring(0, after.indexOf('\n')).replace("z", ""), "the import is intact");
        assertEquals("importx;aaabbbccc", after.replaceAll("[z\\n ]", ""), "no character was deleted");
    }

    @Test
    void typingAfterTheDocumentIsReplacedDeletesNothing() throws Exception {
        EditorBuffer b = carets("a.txt", "aaa\nbbb\nccc", 3, 7, 11);
        FxTestSupport.runOnFx(() -> b.replaceWholeDocument("aaa\nbbb\nccc\nddd"));
        type(b, "z");
        assertEquals("aaabbbcccddd", text(b).replaceAll("[z\\n]", ""), "the reloaded text is all still there");
    }

    // --- whole characters ---------------------------------------------------------------------------

    @Test
    void backspaceRemovesAWholeEmojiAtEveryCaret() throws Exception {
        EditorBuffer b = carets("a.txt", "a" + EMOJI + "\nb" + EMOJI, 3, 7);
        press(b, KeyCode.BACK_SPACE);
        assertEquals("a\nb", text(b));
    }

    @Test
    void deleteRemovesAWholeEmojiAtEveryCaret() throws Exception {
        EditorBuffer b = carets("a.txt", "a" + EMOJI + "\nb" + EMOJI, 1, 5);
        press(b, KeyCode.DELETE);
        assertEquals("a\nb", text(b));
    }

    @Test
    void theArrowKeyStepsOverAWholeEmojiAtEveryCaret() throws Exception {
        EditorBuffer b = carets("a.txt", "a" + EMOJI + "\nb" + EMOJI, 1, 5);
        press(b, KeyCode.RIGHT);
        assertEquals(List.of(3, 7), positions(b));
        type(b, "x");
        assertEquals("a" + EMOJI + "x\nb" + EMOJI + "x", text(b));
        press(b, KeyCode.LEFT);
        press(b, KeyCode.LEFT);
        assertEquals(List.of(1, 6), positions(b), "and back over it");
    }

    @Test
    void charForwardCommandStepsOverAWholeEmojiAtEveryCaret() throws Exception {
        EditorBuffer b = carets("a.txt", "a" + EMOJI + "\nb" + EMOJI, 1, 5);
        run("nav.charForward");
        assertEquals(List.of(3, 7), positions(b));
        run("nav.charBackward");
        assertEquals(List.of(1, 5), positions(b));
    }

    // --- add caret below ----------------------------------------------------------------------------

    @Test
    void addCaretBelowTwiceReachesAThirdLine() throws Exception {
        EditorBuffer b = carets("a.txt", "ab\n\ncd", 0);
        run("edit.addCaretBelow", "edit.addCaretBelow");
        assertEquals(List.of(0, 3, 4), positions(b), "one caret per line");
        type(b, "x");
        assertEquals("xab\nx\nxcd", text(b));
    }

    @Test
    void addCaretBelowKeepsThePrimaryColumnAcrossAShortLine() throws Exception {
        EditorBuffer b = carets("a.txt", "abcdef\nab\nabcdef", 5);
        run("edit.addCaretBelow", "edit.addCaretBelow");
        assertEquals(List.of(5, 9, 15), positions(b));
    }

    @Test
    void addCaretAboveTwiceReachesAThirdLine() throws Exception {
        EditorBuffer b = carets("a.txt", "ab\ncd\nef", 7);
        run("edit.addCaretAbove", "edit.addCaretAbove");
        assertEquals(List.of(1, 4, 7), positions(b));
    }

    // --- commands that mean one caret -----------------------------------------------------------------

    @Test
    void selectAllLeavesOneCaret() throws Exception {
        EditorBuffer b = carets("a.txt", "aaa\nbbb\nccc", 3, 7, 11);
        run("edit.selectAll");
        type(b, "Q");
        assertEquals("Q", text(b));
    }

    @Test
    void cancelCollapsesTheCarets() throws Exception {
        EditorBuffer b = carets("a.txt", "aaa\nbbb\nccc", 3, 7, 11);
        run("edit.cancel");
        assertFalse(FxTestSupport.callOnFx(b::hasMultipleCarets));
        type(b, "Q");
        assertEquals("aaaQ\nbbb\nccc", text(b));
    }

    @Test
    void documentStartLeavesOneCaret() throws Exception {
        EditorBuffer b = carets("a.txt", "aaa\nbbb\nccc", 3, 7, 11);
        run("nav.docStart");
        type(b, "Q");
        assertEquals("Qaaa\nbbb\nccc", text(b));
    }

    @Test
    void cutWithNothingSelectedRespectsTheLineSetting() throws Exception {
        boolean before = FxTestSupport.callOnFx(() -> fx.shared.getSettings().isCopyLineWhenNoSelection());
        try {
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setCopyLineWhenNoSelection(false);
                ClipboardContent c = new ClipboardContent();
                c.putString("untouched");
                Clipboard.getSystemClipboard().setContent(c);
            });
            EditorBuffer b = carets("a.txt", "aaa\nbbb\nccc\nddd", 1, 5);
            run("edit.cut");
            assertEquals("aaa\nbbb\nccc\nddd", text(b), "nothing selected and line-cut off: nothing to cut");
            run("edit.copy");
            assertEquals(
                    "untouched",
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
        } finally {
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setCopyLineWhenNoSelection(before));
        }
    }
}
