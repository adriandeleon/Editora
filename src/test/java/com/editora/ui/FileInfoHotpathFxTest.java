package com.editora.ui;

import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #547: the File Information panel used to re-count words (an O(n) whole-document scan) on every
 * caret move, and refresh on every keystroke via the code area's {@code textProperty} (which materializes the
 * whole document just to fire) — all with no gate for whether the tool window is even open. This verifies:
 * (1) a pure caret move updates the caret position but NOT the word total, and (2) a hidden panel (its node not in
 * a scene) does no work on a caret move.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileInfoHotpathFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void caretMoveUpdatesPositionButNotTheWordCount() throws Exception {
        FileInformationPanel panel = new FileInformationPanel();
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            new Scene(new StackPane(panel), 300, 400); // panel now has a scene → visible() == true
            EditorBuffer b = new EditorBuffer();
            b.getArea().replaceText("one two three four\nfive six\n");
            panel.attach(b);
            return b;
        });
        // A word count is present after attach — counted on a worker thread, so it lands a turn later.
        FileInformationPanelFxTest.awaitValue(panel, "words", "6");

        String[] result = FxTestSupport.callOnFx(() -> {
            TextField words = FxTestSupport.field(panel, "wordsValue");
            TextField line = FxTestSupport.field(panel, "lineValue");
            // Poison the word field, then move the caret only (no edit). The caret path must not touch it.
            words.setText("SENTINEL");
            buffer.getArea().moveTo(0); // fires caretPositionProperty → refreshCaret
            return new String[] {words.getText(), line.getText()};
        });
        Thread.sleep(100); // long enough for a count the caret move wrongly started to have landed
        assertEquals(
                "SENTINEL",
                FxTestSupport.callOnFx(() ->
                        FxTestSupport.<TextField>field(panel, "wordsValue").getText()),
                "a pure caret move must not recompute the word total");
        assertEquals("SENTINEL", result[0]);
        assertEquals("1", result[1], "the caret line still updates on a caret move");
    }

    @Test
    void hiddenPanelDoesNoWorkOnCaretMove() throws Exception {
        String sentinel = FxTestSupport.callOnFx(() -> {
            FileInformationPanel panel = new FileInformationPanel(); // never added to a scene → visible() == false

            EditorBuffer buffer = new EditorBuffer();
            buffer.getArea().replaceText("alpha beta gamma\n");
            panel.attach(buffer);

            TextField line = FxTestSupport.field(panel, "lineValue");
            line.setText("SENTINEL");
            buffer.getArea().moveTo(3); // caret move on a hidden panel must be a no-op
            return line.getText();
        });

        assertEquals("SENTINEL", sentinel, "a hidden File Information panel does no work on a caret move");
    }
}
