package com.editora.ui;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The empty-selection Copy/Cut (VS Code's {@code editor.emptySelectionClipboard}): with no selection,
 * {@link EditorBuffer#copyCurrentLine()} puts the whole current line (plus a newline) on the clipboard
 * without touching the document, and {@link EditorBuffer#cutCurrentLine()} additionally removes the line
 * as one edit — taking the trailing newline for a middle line and the <em>preceding</em> newline for the
 * last line, so the caret never strands a blank line.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CopyLineNoSelectionFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer bufferAtLine(String content, int paragraph) {
        EditorBuffer b = new EditorBuffer();
        b.setContent(content);
        b.getArea().moveTo(paragraph, 0); // caret at column 0 of the target line, no selection
        return b;
    }

    private static void setClipboard(String s) {
        ClipboardContent c = new ClipboardContent();
        c.putString(s);
        Clipboard.getSystemClipboard().setContent(c);
    }

    @Test
    void copyCurrentLineGrabsWholeLineAndLeavesDocumentUntouched() throws Exception {
        String clip = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = bufferAtLine("alpha\nbeta\ngamma", 1); // caret somewhere on "beta"
            setClipboard("stale");
            b.copyCurrentLine();
            assertEquals("alpha\nbeta\ngamma", b.getArea().getText(), "copy must not change the document");
            return Clipboard.getSystemClipboard().getString();
        });
        assertEquals("beta\n", clip, "the whole current line plus a newline goes on the clipboard");
    }

    @Test
    void cutMiddleLineRemovesLineWithItsTrailingNewline() throws Exception {
        String[] r = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = bufferAtLine("alpha\nbeta\ngamma", 1);
            b.cutCurrentLine();
            return new String[] {
                b.getArea().getText(), Clipboard.getSystemClipboard().getString()
            };
        });
        assertEquals("alpha\ngamma", r[0], "the middle line and its newline are gone");
        assertEquals("beta\n", r[1], "the cut line is on the clipboard with a newline");
    }

    @Test
    void cutLastLineRemovesItAndThePrecedingNewline() throws Exception {
        String text = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = bufferAtLine("alpha\nbeta\ngamma", 2); // caret on the last line
            b.cutCurrentLine();
            return b.getArea().getText();
        });
        assertEquals("alpha\nbeta", text, "the last line and the newline before it are removed — no trailing blank");
    }

    @Test
    void cutTheOnlyLineClearsTheBuffer() throws Exception {
        String[] r = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = bufferAtLine("solo", 0);
            b.cutCurrentLine();
            return new String[] {
                b.getArea().getText(), Clipboard.getSystemClipboard().getString()
            };
        });
        assertEquals("", r[0], "the sole line is cleared");
        assertEquals("solo\n", r[1], "and copied with a newline");
    }
}
