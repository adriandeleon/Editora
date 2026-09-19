package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompletionEditTrackerTest {
    private final LspTextEdit edit = new LspTextEdit(0, 0, 0, 0, "import java.util.List;\n");

    @Test
    void importSurvivesTypingAndNewlinesBelowIt() {
        var tracker = new CompletionEditTracker(new LspEditShift.Change(2, 0, 2, 2, 2, 4), 8, 12);
        tracker.changed(12, 0, 3, new LspEditShift.Change(2, 4, 2, 4, 3, 2));
        assertEquals(List.of(edit), tracker.translate(List.of(edit)));
    }

    @Test
    void undoOfTheAcceptedSymbolInvalidatesTheImport() {
        var tracker = new CompletionEditTracker(null, 8, 12);
        tracker.changed(8, 4, 2, new LspEditShift.Change(2, 0, 2, 4, 2, 2));
        assertTrue(tracker.translate(List.of(edit)).isEmpty());
    }

    @Test
    void editsOverlappingTheImportAreRejectedEvenWhenLengthIsUnchanged() {
        var tracker = new CompletionEditTracker(null, 80, 84);
        tracker.changed(0, 4, 4, new LspEditShift.Change(0, 0, 0, 4, 0, 4));
        assertTrue(tracker.translate(List.of(new LspTextEdit(0, 0, 0, 8, "import X;")))
                .isEmpty());
    }

    @Test
    void safeEditsBeforeTheImportRebaseIt() {
        var tracker = new CompletionEditTracker(null, 80, 84);
        tracker.changed(0, 0, 5, new LspEditShift.Change(0, 0, 0, 0, 1, 0));
        assertEquals(
                List.of(new LspTextEdit(2, 0, 2, 0, "import X;\n")),
                tracker.translate(List.of(new LspTextEdit(1, 0, 1, 0, "import X;\n"))));
    }
}
