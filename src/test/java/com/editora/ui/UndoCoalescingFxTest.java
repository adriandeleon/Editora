package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Word/line-level undo: typing several words must produce more than one undo step (one C-z undoes a
 * word/line, not the whole burst). Before the boundary policy, RichTextFX merged the entire contiguous
 * burst into a single undo step.
 */
@Tag("fx")
class UndoCoalescingFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void typingMultipleWordsUndoesWordByWord() throws Exception {
        int[] steps = new int[1];
        String[] afterOneUndo = new String[1];
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            var area = b.getArea();
            for (char c : "foo bar baz".toCharArray()) {
                area.insertText(area.getLength(), String.valueOf(c)); // simulate per-keystroke typing
            }
            area.undo();
            afterOneUndo[0] = area.getText();
            int n = 1;
            while (area.isUndoAvailable() && n < 100) {
                area.undo();
                n++;
            }
            steps[0] = n;
        });
        // Three space-separated words → at least three undo steps (was 1 before the boundary policy).
        assertTrue(steps[0] >= 3, "expected word-level undo steps, got " + steps[0]);
        // The first undo must not wipe the whole burst.
        assertTrue(afterOneUndo[0].startsWith("foo"), "first undo removed too much: [" + afterOneUndo[0] + "]");
    }

    @Test
    void typingASingleWordIsOneUndoStep() throws Exception {
        int[] steps = new int[1];
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            var area = b.getArea();
            for (char c : "hello".toCharArray()) {
                area.insertText(area.getLength(), String.valueOf(c));
            }
            int n = 0;
            while (area.isUndoAvailable() && n < 100) {
                area.undo();
                n++;
            }
            steps[0] = n;
        });
        assertEquals(1, steps[0], "a single word typed in one burst should be one undo step");
    }
}
