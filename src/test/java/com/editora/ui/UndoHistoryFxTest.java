package com.editora.ui;

import com.editora.editor.EditorBuffer;
import com.editora.editor.UndoHistory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** EditorBuffer's undo-history capture + restore (the model behind the Undo History tool window). */
@Tag("fx")
class UndoHistoryFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void capturesCheckpointsAndRestoresThem() throws Exception {
        String restored = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            var area = b.getArea();

            b.captureUndoCheckpoint(); // baseline (empty)
            area.replaceText("first");
            b.captureUndoCheckpoint();
            area.replaceText("first second");
            b.captureUndoCheckpoint();

            var entries = b.getUndoHistory().entriesNewestFirst();
            assertEquals(3, entries.size());
            assertEquals("first second", entries.get(0).text()); // newest first
            assertEquals("", entries.get(2).text()); // baseline

            // Restore the "first" checkpoint (index 1) — document goes back to that state.
            UndoHistory.Checkpoint firstCp = entries.get(1);
            assertEquals("first", firstCp.text());
            b.restoreUndoCheckpoint(firstCp);
            return area.getText();
        });
        assertEquals("first", restored);
    }

    @Test
    void duplicateCaptureIsIgnored() throws Exception {
        int count = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.getArea().replaceText("x");
            b.captureUndoCheckpoint();
            b.captureUndoCheckpoint(); // same text → no new checkpoint
            return b.getUndoHistory().entriesNewestFirst().size();
        });
        assertEquals(1, count);
    }
}
