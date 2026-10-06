package com.editora.ui;

import javafx.scene.input.KeyCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShortcutCaptureTest {

    @Test
    void escapeAlwaysCancels() {
        assertEquals(ShortcutCapture.Action.CANCEL, ShortcutCapture.decide(KeyCode.ESCAPE, false, ""));
        assertEquals(ShortcutCapture.Action.CANCEL, ShortcutCapture.decide(KeyCode.ESCAPE, false, "C-x"));
    }

    @Test
    void plainEnterSavesOnceSomethingIsRecorded() {
        assertEquals(ShortcutCapture.Action.COMMIT, ShortcutCapture.decide(KeyCode.ENTER, false, "C-x C-s"));
    }

    @Test
    void enterIsStillBindableAsTheFirstKeyOrWithAModifier() {
        assertEquals(ShortcutCapture.Action.RECORD, ShortcutCapture.decide(KeyCode.ENTER, false, ""));
        assertEquals(ShortcutCapture.Action.RECORD, ShortcutCapture.decide(KeyCode.ENTER, false, null));
        assertEquals(ShortcutCapture.Action.RECORD, ShortcutCapture.decide(KeyCode.ENTER, true, "C-x"));
    }

    @Test
    void everyOtherKeyIsRecordedIncludingTab() {
        assertEquals(ShortcutCapture.Action.RECORD, ShortcutCapture.decide(KeyCode.TAB, false, "C-x"));
        assertEquals(ShortcutCapture.Action.RECORD, ShortcutCapture.decide(KeyCode.S, true, ""));
    }
}
