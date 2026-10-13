package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code markdown.*} commands run from the registry in a real window: what they say when they cannot
 * apply, the table-size picker under the keyboard, and Tab against the bundled snippets.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkdownCommandsFxTest {

    private static final String TABLE = "| a | b |\n| --- | --- |\n| 1 | 2 |\n";

    @TempDir
    Path dir;

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private int counter;

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

    /** Opens a new Markdown file holding {@code text} with the caret at {@code caret}. */
    private EditorBuffer open(String text, int caret) throws Exception {
        Path file = Files.writeString(dir.resolve("doc" + counter++ + ".md"), text);
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class[] {Path.class}, file));
        FxTestSupport.drainFx();
        EditorBuffer b = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().requestFocus();
            b.getFocusedArea().moveTo(caret);
        });
        return b;
    }

    private String statusAfter(String command) throws Exception {
        for (int i = 0; i < 5; i++) {
            FxTestSupport.drainFx(); // let "Opened …" arrive first: it is posted after the file is shown
        }
        FxTestSupport.runOnFx(() -> {
            fx.controller.setStatus("-");
            registry.run(command);
        });
        FxTestSupport.drainFx();
        return SaveDecisionsFxTest.lastMessage(fx);
    }

    private static String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getFocusedArea().getText());
    }

    @Test
    void aTableCommandThatCannotApplySaysWhy() throws Exception {
        EditorBuffer prose = open("plain text\n", 3);
        for (String command : new String[] {
            "markdown.tableAddRow",
            "markdown.tableDeleteRow",
            "markdown.tableAddColumn",
            "markdown.tableDeleteColumn",
            "markdown.tableAlignLeft",
            "markdown.tableAlignCenter",
            "markdown.tableAlignRight"
        }) {
            assertEquals(tr("status.markdown.notTable"), statusAfter(command), command + " used to say nothing");
        }
        assertEquals("plain text\n", text(prose));

        open(TABLE, 2); // the header row
        assertEquals(tr("status.markdown.tableHeaderRow"), statusAfter("markdown.tableDeleteRow"));
        open("| a |\n| --- |\n| 1 |\n", 2);
        assertEquals(tr("status.markdown.tableLastColumn"), statusAfter("markdown.tableDeleteColumn"));

        EditorBuffer ok = open(TABLE, TABLE.indexOf("1"));
        assertEquals("-", statusAfter("markdown.tableAddRow"), "nothing to report when it worked");
        assertEquals(4, text(ok).lines().count());
    }

    @Test
    void aReadOnlyMarkdownFileIsNotCalledNotMarkdown() throws Exception {
        EditorBuffer b = open("some text\n", 2);
        FxTestSupport.runOnFx(() -> b.setReadOnly(true));
        assertEquals(tr("status.lsp.readOnly"), statusAfter("markdown.bold"));
        assertEquals(tr("status.lsp.readOnly"), statusAfter("markdown.tableAddRow"));
        FxTestSupport.runOnFx(() -> b.setReadOnly(false));

        Path other = Files.writeString(dir.resolve("plain.txt"), "hello");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class[] {Path.class}, other));
        assertEquals(tr("status.notMarkdown"), statusAfter("markdown.bold"));
    }

    @Test
    void extraCaretsAreDroppedWithAMessageBeforeACommandEditsAtOne() throws Exception {
        EditorBuffer b = open("one\ntwo\nthree\n", 1);
        FxTestSupport.runOnFx(() -> {
            b.addCaretBelow();
            b.addCaretBelow();
        });
        assertTrue(FxTestSupport.callOnFx(b::hasMultipleCarets));
        assertEquals(tr("status.markdown.multiCaret"), statusAfter("markdown.bulletList"));
        assertFalse(FxTestSupport.callOnFx(b::hasMultipleCarets));
        assertEquals("- one\ntwo\nthree\n", text(b));
    }

    @Test
    void theTableSizePickerIsDrivenFromTheKeyboard() throws Exception {
        EditorBuffer b = open("x\n\n", 2);
        CodeArea area = FxTestSupport.callOnFx(b::getFocusedArea);
        FxTestSupport.runOnFx(b::insertTableInteractive);
        FxTestSupport.drainFx();
        Node card = FxTestSupport.callOnFx(() -> area.getScene().lookup(".table-size-picker"));
        assertNotNull(card, "the picker is up");
        assertTrue(card.isFocusTraversable());
        // The card is what the keyboard reaches: it used to stay with the editor under the scrim.
        FxTestSupport.runOnFx(() -> {
            card.requestFocus();
            javafx.event.Event.fireEvent(card, key(KeyEvent.KEY_TYPED, "q", KeyCode.UNDEFINED));
            javafx.event.Event.fireEvent(card, key(KeyEvent.KEY_PRESSED, "", KeyCode.RIGHT));
            javafx.event.Event.fireEvent(card, key(KeyEvent.KEY_PRESSED, "", KeyCode.UP));
            javafx.event.Event.fireEvent(card, key(KeyEvent.KEY_PRESSED, "", KeyCode.ENTER));
        });
        FxTestSupport.drainFx();
        assertNull(FxTestSupport.callOnFx(() -> area.getScene().lookup(".table-size-picker")), "Enter closed it");
        String[] lines = text(b).split("\n", -1);
        assertEquals("x", lines[0], "nothing typed reached the document");
        assertEquals("| Column 1 | Column 2 | Column 3 | Column 4 |", lines[2], "3×3 to start, one column more");
        assertEquals(3, text(b).lines().filter(l -> l.startsWith("|")).count(), "and one row fewer: header + one");

        FxTestSupport.runOnFx(b::insertTableInteractive);
        FxTestSupport.drainFx();
        Node again = FxTestSupport.callOnFx(() -> area.getScene().lookup(".table-size-picker"));
        String before = text(b);
        FxTestSupport.runOnFx(() -> {
            again.requestFocus();
            javafx.event.Event.fireEvent(again, key(KeyEvent.KEY_PRESSED, "", KeyCode.ESCAPE));
        });
        FxTestSupport.drainFx();
        assertNull(FxTestSupport.callOnFx(() -> area.getScene().lookup(".table-size-picker")), "Esc closed it");
        assertEquals(before, text(b));
    }

    private static KeyEvent key(javafx.event.EventType<KeyEvent> type, String ch, KeyCode code) {
        return new KeyEvent(type, ch, ch, code, false, false, false, false);
    }

    @Test
    void movingAnItemOfAnOrderedListRenumbersIt() throws Exception {
        EditorBuffer b = open("1. a\n2. b\n3. c\n", 8);
        FxTestSupport.runOnFx(() -> registry.run("edit.moveLineUp"));
        assertEquals("1. b\n2. a\n3. c\n", text(b));
        FxTestSupport.runOnFx(() -> registry.run("edit.moveLineDown"));
        FxTestSupport.runOnFx(() -> registry.run("edit.moveLineDown"));
        assertEquals("1. a\n2. c\n3. b\n", text(b));
        EditorBuffer bullets = open("- a\n- b\n", 6);
        FxTestSupport.runOnFx(() -> registry.run("edit.moveLineUp"));
        assertEquals("- b\n- a\n", text(bullets));
    }

    @Test
    void tabExpandsABlockSnippetOnlyAtTheStartOfALine() throws Exception {
        EditorBuffer sentence = open("take a note\n", 11);
        FxTestSupport.runOnFx(() ->
                javafx.event.Event.fireEvent(sentence.getFocusedArea(), key(KeyEvent.KEY_PRESSED, "", KeyCode.TAB)));
        assertFalse(text(sentence).contains("[!NOTE]"), text(sentence));

        EditorBuffer start = open("note\n", 4);
        FxTestSupport.runOnFx(
                () -> javafx.event.Event.fireEvent(start.getFocusedArea(), key(KeyEvent.KEY_PRESSED, "", KeyCode.TAB)));
        assertTrue(text(start).startsWith("> [!NOTE]\n> "), text(start));
    }
}
