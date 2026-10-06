package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaretEditTrackerTest {

    /** A 1000-character document whose caret sits at 500 on the line [480, 520]. */
    private static CaretEditTracker caretAt500() {
        return new CaretEditTracker(480, 520, 500, 500, 1000);
    }

    @Test
    void aCommandThatChangesNothingIsNotAnEdit() {
        CaretEditTracker t = caretAt500();
        assertFalse(t.changed());
        assertFalse(t.editedAtCaret(500));
    }

    @Test
    void aPasteAtTheCaretIsAnEditAtTheCaret() {
        CaretEditTracker t = caretAt500();
        t.change(500, 0, 300);
        assertTrue(t.editedAtCaret(800));
    }

    @Test
    void aChangeElsewhereOnTheCaretsLineCounts() {
        CaretEditTracker t = caretAt500();
        t.change(480, 0, 3); // toggle comment: "// " at the start of the line, the caret merely shifts
        assertTrue(t.editedAtCaret(503));
    }

    @Test
    void aChangeFarFromTheCaretDoesNot() {
        CaretEditTracker above = caretAt500();
        above.change(10, 5, 0);
        assertTrue(above.changed());
        assertFalse(above.editedAtCaret(495), "trimmed text far above: the caret only shifted");

        CaretEditTracker below = caretAt500();
        below.change(900, 0, 40);
        assertFalse(below.editedAtCaret(500));
    }

    @Test
    void theCaretsLinesAreFollowedThroughEarlierChanges() {
        CaretEditTracker t = caretAt500();
        t.change(0, 0, 100); // pushes the caret's line to [580, 620]
        t.change(520, 0, 5); // where the line used to be: not the caret's line any more
        assertFalse(t.editedAtCaret(600));
        t.change(590, 2, 0); // inside the line where it is now
        assertTrue(t.editedAtCaret(598));
    }

    @Test
    void aChangeTheCommandLeftTheCaretInCounts() {
        CaretEditTracker t = caretAt500();
        t.change(40, 0, 3); // undo re-inserts "ZZZ" far above and takes the caret there
        assertTrue(t.editedAtCaret(43));
        assertTrue(t.editedAtCaret(40));
        assertFalse(t.editedAtCaret(503), "had the caret stayed where it was, it would not");
    }

    @Test
    void theCaretIsTracedBackThroughLaterChanges() {
        CaretEditTracker t = caretAt500();
        t.change(40, 0, 3); // [40, 43]
        t.change(10, 0, 20); // later, above it: the first insertion now sits at [60, 63]
        assertTrue(t.editedAtCaret(62));
        assertFalse(t.editedAtCaret(45));
    }

    @Test
    void replacingTheWholeDocumentIsNotAnEditAtTheCaret() {
        CaretEditTracker t = caretAt500();
        t.change(0, 1000, 990); // reformat / sort / convert the file
        assertTrue(t.changed());
        assertFalse(t.editedAtCaret(495));
    }

    @Test
    void unlessTheWholeDocumentWasTheSelection() {
        CaretEditTracker t = new CaretEditTracker(0, 1000, 0, 1000, 1000);
        t.change(0, 1000, 4000); // select all, paste
        assertTrue(t.editedAtCaret(4000));
    }

    @Test
    void anEditAfterAWholeDocumentReplacementStillCounts() {
        CaretEditTracker t = caretAt500();
        t.change(0, 1000, 990);
        t.change(200, 0, 7);
        assertTrue(t.editedAtCaret(207));
        assertFalse(t.editedAtCaret(600));
    }

    @Test
    void anEmptyDocumentIsNeverAWholeDocumentReplacement() {
        CaretEditTracker t = new CaretEditTracker(0, 0, 0, 0, 0);
        t.change(0, 0, 50);
        assertTrue(t.editedAtCaret(50));
    }

    @Test
    void manyChangesAreAllRemembered() {
        CaretEditTracker t = caretAt500();
        for (int i = 0; i < 40; i++) {
            t.change(900, 0, 1); // forty carets' worth of typing far below
        }
        assertFalse(t.editedAtCaret(500));
        assertTrue(t.editedAtCaret(905));
    }
}
