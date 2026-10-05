package com.editora.ui;

import java.util.Map;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
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
 * Core text editing through the real window: the keys and commands arrive as they do in the app (key events
 * on the focused area, commands through the registry), so each case covers the filters and the command
 * wiring, not just the pure helper behind it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditingCoreFxTest {

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

    /** A buffer named {@code name} (which picks its language) with {@code [anchor, caret)} selected. */
    private EditorBuffer open(String name, String content, int anchor, int caret) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(java.nio.file.Path.of("/nonexistent-editing-core/" + name));
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().requestFocus();
            b.getArea().selectRange(anchor, caret);
            return b;
        });
    }

    private void press(EditorBuffer b, KeyCode code, boolean shift) throws Exception {
        FxTestSupport.runOnFx(() ->
                b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false)));
    }

    private void typed(EditorBuffer b, String ch, boolean control, boolean alt) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, control, alt, false)));
    }

    private void type(EditorBuffer b, String s) throws Exception {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                press(b, KeyCode.ENTER, false);
            } else {
                typed(b, String.valueOf(s.charAt(i)), false, false);
            }
        }
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private int caret(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getCaretPosition());
    }

    private static void clipboard(String s) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(s);
            Clipboard.getSystemClipboard().setContent(c);
        });
    }

    // --- Tab in prose and unstyled languages ---------------------------------------------------------

    @Test
    void tabOverASelectionIndentsItInMarkdown() throws Exception {
        EditorBuffer b = open("notes.md", "- a\n- b\n- c", 0, 7);
        press(b, KeyCode.TAB, false);
        assertEquals("    - a\n    - b\n- c", text(b), "the two selected lines are indented, not replaced");
        press(b, KeyCode.TAB, true);
        assertEquals("- a\n- b\n- c", text(b), "and Shift-Tab takes the indent back off");
    }

    @Test
    void tabOverASelectionIndentsItInAnUnrecognisedLanguage() throws Exception {
        EditorBuffer b = open("conf.toml", "a = 1\nb = 2", 0, 11);
        press(b, KeyCode.TAB, false);
        assertEquals("    a = 1\n    b = 2", text(b));
    }

    @Test
    void tabInsertsTheConfiguredIndentInPlainText() throws Exception {
        EditorBuffer b = open("a.txt", "ab", 2, 2);
        FxTestSupport.runOnFx(() -> b.setIndentOverride(true, 2));
        press(b, KeyCode.TAB, false);
        assertEquals("ab  ", text(b), "spaces indent style: two spaces, not a tab character");
    }

    @Test
    void tabStaysATabCharacterInAMakefile() throws Exception {
        EditorBuffer b = open("Makefile", "all:\necho hi", 5, 5);
        FxTestSupport.runOnFx(() -> b.setIndentOverride(true, 4)); // a global "spaces" preference
        press(b, KeyCode.TAB, false);
        assertEquals("all:\n\techo hi", text(b), "a recipe line must start with a real tab");
    }

    // --- whole characters ------------------------------------------------------------------------------

    @Test
    void charForwardAndBackwardStepOverAWholeEmoji() throws Exception {
        EditorBuffer b = open("a.txt", "a" + EMOJI + "b", 1, 1);
        run("nav.charForward");
        assertEquals(3, caret(b), "past both halves of the surrogate pair");
        type(b, "x");
        assertEquals("a" + EMOJI + "xb", text(b));
        run("nav.charBackward", "nav.charBackward");
        assertEquals(1, caret(b));
    }

    @Test
    void wordForwardTakesACombiningMarkWithItsLetter() throws Exception {
        EditorBuffer b = open("a.txt", "café x", 0, 0);
        run("nav.wordForward");
        assertEquals(5, caret(b), "the accent belongs to the word");
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(0));
        run("edit.killWord");
        assertEquals(" x", text(b), "and is killed with it");
    }

    // --- undo steps ------------------------------------------------------------------------------------

    @Test
    void aCommandsEditIsItsOwnUndoStep() throws Exception {
        EditorBuffer b = open("a.txt", "start ", 6, 6);
        clipboard("PASTED");
        type(b, "abc");
        run("edit.paste");
        type(b, "x");
        assertEquals("start abcPASTEDx", text(b));
        run("edit.undo");
        assertEquals("start abcPASTED", text(b), "the character typed after the paste");
        run("edit.undo");
        assertEquals("start abc", text(b), "the paste, without the word typed before it");
    }

    @Test
    void aRepeatedCommandUndoesOnePressAtATime() throws Exception {
        EditorBuffer b = open("a.txt", "aaa\nbbb", 1, 1);
        run("edit.duplicateLine", "edit.duplicateLine", "edit.duplicateLine");
        assertEquals("aaa\naaa\naaa\naaa\nbbb", text(b));
        run("edit.undo");
        assertEquals("aaa\naaa\naaa\nbbb", text(b));
    }

    // --- abbreviations ---------------------------------------------------------------------------------

    @Test
    void anAbbreviationExpandsBeforeEnterOnAnIndentedLine() throws Exception {
        EditorBuffer b = open("a.txt", "    ", 4, 4);
        FxTestSupport.runOnFx(() -> b.setAbbrevs(Map.of("btw", "by the way"), true));
        type(b, "btw\n");
        FxTestSupport.runOnFx(() -> {}); // the caret restore is deferred one pulse
        assertEquals("    by the way\n    ", text(b));
        assertEquals(text(b).length(), caret(b), "the caret stays on the new line, after its indent");
    }

    @Test
    void anAbbreviationExpandsBeforeAnAutoClosedBracket() throws Exception {
        EditorBuffer b = open("A.java", "", 0, 0);
        FxTestSupport.runOnFx(() -> b.setAbbrevs(Map.of("btw", "by the way"), true));
        type(b, "btw(");
        FxTestSupport.runOnFx(() -> {});
        assertEquals("by the way()", text(b));
        assertEquals(11, caret(b), "between the pair");
    }

    // --- cut line ----------------------------------------------------------------------------------------

    @Test
    void cuttingTheLastLineAndPastingItBackRestoresIt() throws Exception {
        EditorBuffer b = open("a.txt", "one\ntwo", 5, 5);
        run("edit.cut");
        assertEquals("one", text(b));
        run("edit.paste");
        assertEquals("one\ntwo", text(b), "not joined onto the line above");
    }

    @Test
    void cuttingAMiddleLineStillPutsALineOnTheClipboard() throws Exception {
        EditorBuffer b = open("a.txt", "one\ntwo\nthree", 5, 5);
        run("edit.cut");
        assertEquals("one\nthree", text(b));
        assertEquals(
                "two\n",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
    }

    // --- line endings ------------------------------------------------------------------------------------

    @Test
    void convertingLineEndingsThereAndBackLeavesTheBufferClean() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer nb = new EditorBuffer();
            nb.setInitialContent("a\nb", false);
            nb.markClean();
            return nb;
        });
        FxTestSupport.runOnFx(() -> b.convertLineEndings(true));
        assertTrue(FxTestSupport.callOnFx(b::isDirty), "CRLF differs from the file on disk");
        FxTestSupport.runOnFx(() -> b.convertLineEndings(false));
        assertFalse(FxTestSupport.callOnFx(b::isDirty), "back to the file's own ending: nothing to save");
        // …and saving in the new ending makes that one the clean state.
        FxTestSupport.runOnFx(() -> {
            b.convertLineEndings(true);
            b.markClean();
        });
        assertFalse(FxTestSupport.callOnFx(b::isDirty));
        FxTestSupport.runOnFx(() -> b.convertLineEndings(false));
        assertTrue(FxTestSupport.callOnFx(b::isDirty));
    }

    // --- Markdown Enter ----------------------------------------------------------------------------------

    @Test
    void enterInsideAFencedCodeBlockDoesNotContinueAList() throws Exception {
        String src = "```yaml\n- name: x";
        EditorBuffer b = open("notes.md", src, src.length(), src.length());
        type(b, "\n");
        assertEquals(src + "\n", text(b));
    }

    @Test
    void enterAfterASpacedThematicBreakDoesNotStartAList() throws Exception {
        String src = "text\n\n- - -";
        EditorBuffer b = open("notes.md", src, src.length(), src.length());
        type(b, "\n");
        assertEquals(src + "\n", text(b));
    }

    @Test
    void enterOnAListItemStillContinuesTheList() throws Exception {
        String src = "```\ncode\n```\n- item";
        EditorBuffer b = open("notes.md", src, src.length(), src.length());
        type(b, "\n");
        assertEquals(src + "\n- ", text(b), "after the fence has closed, a list is a list");
    }

    // --- characters typed with Option / AltGr --------------------------------------------------------------

    @Test
    void aBraceTypedWithTheLayoutsModifierIsStillAutoClosed() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        EditorBuffer b = open("A.java", "x ", 2, 2);
        // AltGr arrives as Control+Alt on Windows; Option as Alt on macOS. Both are plain text input.
        typed(b, "{", windows, true);
        assertEquals("x {}", text(b));
    }
}
