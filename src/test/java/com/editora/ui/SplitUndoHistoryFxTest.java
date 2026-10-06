package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A split's two views share one undo history. These drive it the way a user does — keys typed into a pane,
 * commands run through the registry with that pane focused — across the features that edit on their own:
 * multiple carets, commands, snippets, abbreviations, reload, closing the split and narrowing.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitUndoHistoryFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private int files;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            stage.setWidth(1100);
            stage.setHeight(700);
            if (!stage.isShowing()) {
                stage.show();
            }
        });
        settle();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
        FxTestSupport.drainFx();
    }

    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        Thread.sleep(150);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    private EditorBuffer open(String name, String content) throws Exception {
        Path file = fx.configDir.resolve((files++) + name);
        Files.writeString(file, content);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        for (int i = 0; i < 100; i++) {
            settle();
            EditorBuffer b = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            if (b != null && file.equals(b.getPath()) && !b.isLoading()) {
                settle();
                return b;
            }
        }
        throw new IllegalStateException("did not open " + file);
    }

    private CodeArea split(EditorBuffer b) throws Exception {
        run("view.splitVertical");
        settle();
        CodeArea second = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "area2"));
        assertNotNull(second, "the split created a second view");
        return second;
    }

    /** Puts the keyboard in {@code view} with its caret at {@code offset}. */
    private void enter(EditorBuffer b, CodeArea view, int offset) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ((Stage) FxTestSupport.field(fx.controller, "stage")).requestFocus();
            view.requestFocus();
            view.moveTo(offset);
        });
        settle();
        Assumptions.assumeTrue(
                FxTestSupport.callOnFx(() -> view.isFocused() && b.getFocusedArea() == view),
                "headless window could not focus the editor view");
    }

    private static void type(CodeArea view, String s) throws Exception {
        for (int i = 0; i < s.length(); i++) {
            String ch = String.valueOf(s.charAt(i));
            FxTestSupport.runOnFx(() -> view.fireEvent(
                    new KeyEvent(KeyEvent.KEY_TYPED, ch, ch, KeyCode.UNDEFINED, false, false, false, false)));
        }
        FxTestSupport.drainFx();
    }

    private static void press(CodeArea view, KeyCode code) throws Exception {
        FxTestSupport.runOnFx(
                () -> view.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false)));
        FxTestSupport.drainFx();
    }

    private static String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    /** Runs {@code id} with the keyboard in {@code view}, as its key binding or menu item would. */
    private void runIn(EditorBuffer b, CodeArea view, String id) throws Exception {
        FxTestSupport.runOnFx(view::requestFocus);
        settle();
        assertEquals(view, FxTestSupport.callOnFx(b::getFocusedArea));
        run(id);
        settle();
    }

    @Test
    void typingInOnePaneIsUndoneAndRedoneFromTheOther() throws Exception {
        EditorBuffer b = open("typing.txt", "first\nsecond\n");
        CodeArea first = b.getArea();
        CodeArea second = split(b);

        enter(b, first, 5);
        type(first, "A");
        enter(b, second, 13); // end of "second" (after the A)
        type(second, "B");
        assertEquals("firstA\nsecondB\n", text(b));

        runIn(b, first, "edit.undo"); // the newest edit is pane 2's: undone from pane 1
        assertEquals("firstA\nsecond\n", text(b));
        assertEquals(13, FxTestSupport.callOnFx(first::getCaretPosition), "the caret of the pane undo ran in");
        runIn(b, second, "edit.undo"); // pane 1's, from pane 2
        assertEquals("first\nsecond\n", text(b));
        assertFalse(FxTestSupport.callOnFx(second::isUndoAvailable));
        assertFalse(FxTestSupport.callOnFx(first::isUndoAvailable));

        runIn(b, second, "edit.redo");
        assertEquals("firstA\nsecond\n", text(b));
        runIn(b, first, "edit.redo");
        assertEquals("firstA\nsecondB\n", text(b));
        assertFalse(FxTestSupport.callOnFx(second::isRedoAvailable));
    }

    @Test
    void anEditAtSeveralCaretsIsOneStepFromEitherPane() throws Exception {
        EditorBuffer b = open("carets.txt", "one\ntwo\nthree\nfour\n");
        CodeArea first = b.getArea();
        CodeArea second = split(b);

        // Three carets in pane 2, one edit; undone and redone from pane 1.
        enter(b, second, 0);
        runIn(b, second, "edit.addCaretBelow");
        runIn(b, second, "edit.addCaretBelow");
        type(second, "x");
        assertEquals("xone\nxtwo\nxthree\nfour\n", text(b));
        runIn(b, first, "edit.undo");
        assertEquals("one\ntwo\nthree\nfour\n", text(b), "one undo step for the three carets");
        runIn(b, first, "edit.redo");
        assertEquals("xone\nxtwo\nxthree\nfour\n", text(b));

        // Pane 2's carets are still there and still type, after an undo issued in pane 2 itself.
        runIn(b, second, "edit.undo");
        assertEquals("one\ntwo\nthree\nfour\n", text(b));
        type(second, "y");
        assertEquals("yone\nytwo\nythree\nfour\n", text(b), "the extra carets survive the undo");
        runIn(b, second, "edit.collapseCarets");

        // And the other way round: carets in pane 1, undo from pane 2.
        int four = text(b).indexOf("four");
        enter(b, first, four);
        runIn(b, first, "edit.addCaretAbove");
        type(first, "z");
        assertEquals("yone\nytwo\nzythree\nzfour\n", text(b));
        runIn(b, second, "edit.undo");
        assertEquals("yone\nytwo\nythree\nfour\n", text(b));
        runIn(b, second, "edit.undo");
        assertEquals("one\ntwo\nthree\nfour\n", text(b));
        runIn(b, first, "edit.collapseCarets");
    }

    @Test
    void aCommandsEditIsItsOwnUndoStepInEitherPane() throws Exception {
        EditorBuffer b = open("command.txt", "alpha\nbeta\n");
        CodeArea first = b.getArea();
        CodeArea second = split(b);

        enter(b, second, 5);
        type(second, "1");
        runIn(b, second, "edit.duplicateLine");
        assertEquals("alpha1\nalpha1\nbeta\n", text(b));
        type(second, "2");
        String typed = text(b);

        runIn(b, first, "edit.undo");
        assertEquals("alpha1\nalpha1\nbeta\n", text(b), "the typing after the command");
        runIn(b, first, "edit.undo");
        assertEquals("alpha1\nbeta\n", text(b), "the command alone");
        runIn(b, second, "edit.undo");
        assertEquals("alpha\nbeta\n", text(b), "the typing before it");
        runIn(b, second, "edit.redo");
        runIn(b, second, "edit.redo");
        runIn(b, first, "edit.redo");
        assertEquals(typed, text(b));
    }

    @Test
    void aSnippetExpandedInTheSecondPaneUndoesAsOneStepFromTheFirst() throws Exception {
        EditorBuffer b = open("Snip.java", "class Snip {\n\n}\n");
        CodeArea first = b.getArea();
        CodeArea second = split(b);

        enter(b, second, 13); // the empty line
        type(second, "sysout");
        press(second, KeyCode.TAB);
        settle();
        String expanded = text(b);
        assertTrue(expanded.contains("System.out.println();"), expanded);
        int inParens = expanded.indexOf("println(") + "println(".length();
        assertEquals(inParens, FxTestSupport.callOnFx(second::getCaretPosition), "the caret is in pane 2's snippet");

        // One step back to before the prefix was typed: the granularity a single pane has (the typed prefix
        // and the expansion that replaces it are adjacent edits, which merge).
        runIn(b, first, "edit.undo");
        assertEquals("class Snip {\n\n}\n", text(b));
        assertFalse(FxTestSupport.callOnFx(first::isUndoAvailable));
        runIn(b, first, "edit.redo");
        assertEquals(expanded, text(b));
        press(second, KeyCode.ESCAPE);
    }

    @Test
    void anAbbreviationExpandedInTheSecondPaneUndoesFromTheFirst() throws Exception {
        EditorBuffer b = open("abbrev.txt", "say \nend\n");
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        FxTestSupport.runOnFx(() -> b.setAbbrevs(Map.of("btw", "by the way"), true));

        enter(b, second, 4);
        type(second, "btw ");
        assertEquals("say by the way \nend\n", text(b), "expanded where pane 2's caret is");
        assertEquals(15, FxTestSupport.callOnFx(second::getCaretPosition));

        runIn(b, first, "edit.undo");
        assertEquals("say btw \nend\n", text(b), "the expansion alone, leaving what was typed");
        runIn(b, first, "edit.redo");
        assertEquals("say by the way \nend\n", text(b));

        // The explicit command, from pane 2.
        int end = text(b).indexOf("end") + 3;
        enter(b, second, end);
        type(second, " btw");
        runIn(b, second, "edit.expandAbbrev");
        assertEquals("say by the way \nend by the way\n", text(b));
        runIn(b, first, "edit.undo");
        assertEquals("say by the way \nend btw\n", text(b));
    }

    @Test
    void aLineAutoFilledWhileTypingInTheSecondPaneKeepsTheCaretAfterWhatWasTyped() throws Exception {
        EditorBuffer b = open("fill.txt", "aaaa bbbb cccc dddd\n");
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        FxTestSupport.runOnFx(() -> {
            b.setFillColumn(20);
            b.setAutoFillEnabled(true);
        });
        enter(b, second, 19);
        type(second, " eeee");
        settle();
        assertEquals("aaaa bbbb cccc dddd\neeee\n", text(b), "the word that passed the column moved down");
        assertEquals(24, FxTestSupport.callOnFx(second::getCaretPosition), "the caret is after it");
        type(second, "!");
        assertEquals("aaaa bbbb cccc dddd\neeee!\n", text(b));
        runIn(b, first, "edit.undo");
        runIn(b, first, "edit.undo");
        assertTrue(text(b).startsWith("aaaa bbbb cccc dddd"), text(b));
        FxTestSupport.runOnFx(() -> b.setAutoFillEnabled(false));
    }

    @Test
    void reloadFromDiskForgetsTheHistoryOfBothPanes() throws Exception {
        EditorBuffer b = open("reload.txt", "on disk\n");
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        enter(b, second, 0);
        type(second, "edited ");
        assertTrue(FxTestSupport.callOnFx(first::isUndoAvailable));

        Files.writeString(b.getPath(), "changed on disk\n");
        Object workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        Tab tab = FxTestSupport.callOnFx(
                () -> (Tab) FxTestSupport.call(fx.controller, "tabForPath", new Class[] {Path.class}, b.getPath()));
        FxTestSupport.runOnFx(() ->
                FxTestSupport.call(workflows, "reloadFromDisk", new Class[] {Tab.class, EditorBuffer.class}, tab, b));
        for (int i = 0; i < 50 && !"changed on disk\n".equals(text(b)); i++) {
            settle();
        }
        assertEquals("changed on disk\n", text(b));
        assertEquals("changed on disk\n", FxTestSupport.callOnFx(second::getText));

        assertFalse(FxTestSupport.callOnFx(first::isUndoAvailable), "nothing to undo in pane 1");
        assertFalse(FxTestSupport.callOnFx(second::isUndoAvailable), "nor in pane 2");
        runIn(b, second, "edit.undo");
        runIn(b, first, "edit.undo");
        assertEquals("changed on disk\n", text(b), "undo cannot bring the pre-reload text back");
        assertFalse(b.isDirty());

        // Still one history afterwards.
        enter(b, first, 0);
        type(first, "X");
        runIn(b, second, "edit.undo");
        assertEquals("changed on disk\n", text(b));
    }

    @Test
    void closingTheSplitKeepsTheHistoryAndReopeningItSharesItAgain() throws Exception {
        EditorBuffer b = open("close.txt", "body\n");
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        enter(b, second, 0);
        type(second, "A");
        enter(b, first, 5);
        type(first, "B");
        assertEquals("AbodyB\n", text(b));

        runIn(b, second, "view.unsplit");
        settle();
        assertEquals(first, FxTestSupport.callOnFx(b::getFocusedArea));
        run("edit.undo");
        run("edit.undo");
        assertEquals("body\n", text(b), "both panes' edits are undone after the split is closed");
        run("edit.redo");
        run("edit.redo");
        String redone = text(b);

        CodeArea again = split(b);
        runIn(b, again, "edit.undo");
        runIn(b, again, "edit.undo");
        assertEquals("body\n", text(b), "and the reopened pane works on the same history");
        runIn(b, first, "edit.redo");
        runIn(b, again, "edit.redo");
        assertEquals(redone, text(b));
    }

    @Test
    void narrowingWhileSplitKeepsBothPanesAndStartsAFreshHistory() throws Exception {
        String doc = "head\none\ntwo\nthree\ntail\n";
        EditorBuffer b = open("narrow.txt", doc);
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        enter(b, first, 0);
        type(first, "#");
        String edited = "#" + doc;

        // Narrow to "two" from pane 2, whose caret is at the end of the selection.
        int two = edited.indexOf("two");
        enter(b, second, two);
        FxTestSupport.runOnFx(() -> second.selectRange(two, two + 3)); // caret after "two"
        runIn(b, second, "edit.narrowToRegion");
        assertEquals("two", text(b));
        assertEquals("two", FxTestSupport.callOnFx(second::getText));
        assertEquals(3, FxTestSupport.callOnFx(second::getCaretPosition), "pane 2's caret stays on its character");
        assertEquals(0, FxTestSupport.callOnFx(first::getCaretPosition), "pane 1's was above the region");
        assertFalse(FxTestSupport.callOnFx(second::isUndoAvailable), "undo cannot cross into the narrowed region");
        runIn(b, second, "edit.undo");
        assertEquals("two", text(b));

        FxTestSupport.runOnFx(() -> second.moveTo(1));
        type(second, "!");
        assertEquals("t!wo", text(b));
        runIn(b, first, "edit.undo");
        assertEquals("two", text(b), "inside the region the panes share a history again");
        runIn(b, first, "edit.redo");

        runIn(b, second, "edit.widen");
        assertEquals(edited.replace("two", "t!wo"), text(b));
        assertEquals(two + 2, FxTestSupport.callOnFx(second::getCaretPosition), "and widening keeps pane 2's caret");
        assertFalse(FxTestSupport.callOnFx(first::isUndoAvailable));
        runIn(b, first, "edit.undo");
        assertEquals(edited.replace("two", "t!wo"), text(b));
    }
}
