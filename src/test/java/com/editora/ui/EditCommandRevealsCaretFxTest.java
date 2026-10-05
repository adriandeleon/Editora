package com.editora.ui;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An editing command reveals the caret. The chord is consumed by the scene-level key dispatcher, so the
 * text area's own "follow the caret after a key" never runs, and a programmatic edit does not scroll by
 * itself: a multi-line paste, an undo of an off-screen change, and duplicate/move line at the bottom of the
 * window all left the caret outside the viewport. Driven with real key events where a binding exists.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditCommandRevealsCaretFxTest {

    private EditingFx e;

    @BeforeAll
    void setUp() throws Exception {
        e = EditingFx.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (e != null) {
            e.dispose();
        }
    }

    private void clipboard(String text) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(text);
            Clipboard.getSystemClipboard().setContent(cc);
        });
    }

    private int lastVisible(CodeArea area) throws Exception {
        return FxTestSupport.callOnFx(area::lastVisibleParToAllParIndex);
    }

    @Test
    void aMultiLinePasteScrollsToTheCaret() throws Exception {
        EditorBuffer b = e.open("paste.txt", EditingFx.lines("line", 400));
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.moveTo(10, 0));
        clipboard(EditingFx.lines("pasted", 100));

        e.ctrl(area, KeyCode.Y); // C-y = edit.paste in the default (Emacs) keymap

        assertEquals(110, (int) FxTestSupport.callOnFx(area::getCurrentParagraph), "the paste landed");
        assertTrue(e.await(() -> visible(area)), "the view follows the pasted caret: " + e.viewport(area));
    }

    @Test
    void undoRevealsTheChangeItReverted() throws Exception {
        EditorBuffer b = e.open("undo.txt", EditingFx.lines("line", 400));
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.insertText(5, 0, "ZZZ"));
        e.pulses(5);
        FxTestSupport.runOnFx(() -> area.showParagraphAtTop(300)); // the user scrolls away with the wheel
        assertTrue(e.await(() -> area.firstVisibleParToAllParIndex() >= 250), "scrolled away first");

        e.ctrl(area, KeyCode.SLASH); // C-/ = edit.undo

        assertTrue(FxTestSupport.callOnFx(() -> !area.getText(5, 0, 5, 3).equals("ZZZ")), "the edit was undone");
        assertTrue(e.await(() -> visible(area)), "the undone change is on screen: " + e.viewport(area));
    }

    @Test
    void duplicateAndMoveLineKeepTheCaretOnScreen() throws Exception {
        EditorBuffer b = e.open("dup.txt", EditingFx.lines("line", 400));
        CodeArea area = b.getArea();
        int bottom = lastVisible(area);
        FxTestSupport.runOnFx(() -> area.moveTo(bottom, 0));
        for (int i = 0; i < 5; i++) {
            e.run("edit.duplicateLine");
        }
        assertTrue(e.await(() -> visible(area)), "after duplicate line x5: " + e.viewport(area));

        int bottom2 = lastVisible(area);
        FxTestSupport.runOnFx(() -> area.moveTo(bottom2, 0));
        for (int i = 0; i < 5; i++) {
            e.run("edit.moveLineDown");
        }
        assertTrue(e.await(() -> visible(area)), "after move line down x5: " + e.viewport(area));
    }

    /** A command that edits nothing must not drag a deliberately scrolled view back to the caret. */
    @Test
    void aCommandThatDoesNotEditLeavesTheScrollPositionAlone() throws Exception {
        EditorBuffer b = e.open("still.txt", EditingFx.lines("line", 400));
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.moveTo(3, 0));
        FxTestSupport.runOnFx(() -> area.showParagraphAtTop(300));
        assertTrue(e.await(() -> area.firstVisibleParToAllParIndex() >= 250), "scrolled away first");

        e.run("edit.selectAll");
        e.run("edit.copy");

        assertTrue(
                FxTestSupport.callOnFx(() -> area.firstVisibleParToAllParIndex() >= 250),
                "still scrolled away: " + e.viewport(area));
    }

    /** Not only {@code edit.*}: a Markdown formatting command edits at the caret just the same. */
    @Test
    void aMarkdownFormattingCommandRevealsTheCaretItEditedAt() throws Exception {
        EditorBuffer b = e.open("format.md", EditingFx.lines("some words here", 400));
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.selectRange(5, 5, 5, 10));
        FxTestSupport.runOnFx(() -> area.showParagraphAtTop(300));
        assertTrue(e.await(() -> area.firstVisibleParToAllParIndex() >= 250), "scrolled away first");

        e.run("markdown.bold");

        assertEquals(
                "some **words** here 6",
                FxTestSupport.callOnFx(() -> area.getParagraph(5).getText()));
        assertTrue(e.await(() -> visible(area)), "the view follows the formatted text: " + e.viewport(area));
    }

    /** A command that rewrites the whole file is not an edit at the caret, whatever it is called. */
    @Test
    void aWholeFileCommandLeavesTheScrollPositionAlone() throws Exception {
        EditorBuffer b = e.open("whole.txt", EditingFx.lines("line", 400).replace("\n", "   \n"));
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.moveTo(3, 0));
        FxTestSupport.runOnFx(() -> area.showParagraphAtTop(300));
        assertTrue(e.await(() -> area.firstVisibleParToAllParIndex() >= 250), "scrolled away first");
        com.editora.command.CommandRegistry registry = FxTestSupport.field(e.fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            // Stands in for any command that reformats the buffer; an "edit." name must not matter.
            registry.register(com.editora.command.Command.of("edit.testRewriteFile", "Rewrite", () -> {
                int caret = area.getCaretPosition();
                area.replaceText(0, area.getLength(), area.getText().replace("   \n", "\n"));
                area.moveTo(Math.min(caret, area.getLength()));
                area.showParagraphAtTop(300); // as such commands do: replacing everything resets the viewport
            }));
            // And one that edits far from the caret (what a save that trims trailing whitespace does).
            registry.register(com.editora.command.Command.of("edit.testEditElsewhere", "Elsewhere", () -> {
                int at = area.getAbsolutePosition(350, 0);
                area.replaceText(at, at + 4, "LINE");
            }));
        });

        e.run("edit.testRewriteFile");
        e.pulses(10);
        assertTrue(FxTestSupport.callOnFx(() -> !area.getText().contains("   \n")), "the file was rewritten");
        int top = FxTestSupport.callOnFx(area::firstVisibleParToAllParIndex);
        assertTrue(top >= 250, "still scrolled away after a whole-file rewrite: " + e.viewport(area));

        e.run("edit.testEditElsewhere");
        e.pulses(10);
        assertEquals(
                "LINE 351", FxTestSupport.callOnFx(() -> area.getParagraph(350).getText()));
        assertTrue(
                FxTestSupport.callOnFx(() -> area.firstVisibleParToAllParIndex() >= 250),
                "still scrolled away after an edit elsewhere: " + e.viewport(area));
    }

    private static boolean visible(CodeArea area) {
        int par = area.getCurrentParagraph();
        return area.firstVisibleParToAllParIndex() <= par && par <= area.lastVisibleParToAllParIndex();
    }
}
