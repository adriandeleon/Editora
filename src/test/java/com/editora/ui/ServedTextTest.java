package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServedTextTest {

    @Test
    void aWriteIsCurrentOnlyAgainstTheTextTheAgentLastSaw() {
        ServedText served = new ServedText();
        served.served("a", "one\n");
        assertEquals(ServedText.Verdict.CURRENT, served.check("a", "one\n", false));
        assertEquals(ServedText.Verdict.CURRENT, served.check("a", "one\n", true), "it read the unsaved text");
        assertEquals(ServedText.Verdict.CHANGED_SINCE_READ, served.check("a", "one\ntyped since\n", true));
        assertEquals(ServedText.Verdict.CHANGED_SINCE_READ, served.check("a", "", false));
        served.served("a", "one\ntyped since\n"); // re-read
        assertEquals(ServedText.Verdict.CURRENT, served.check("a", "one\ntyped since\n", true));
    }

    @Test
    void theSameTextWithOtherLineTerminatorsIsNotAChange() {
        ServedText served = new ServedText();
        served.served("a", "one\r\ntwo\r\n"); // read from disk, before the file had a buffer
        assertEquals(ServedText.Verdict.CURRENT, served.check("a", "one\ntwo\n", false), "now open in a tab");
        assertEquals(ServedText.Verdict.CHANGED_SINCE_READ, served.check("a", "one\ntwo!\n", false));
    }

    @Test
    void aDocumentNeverReadIsWritableUnlessItHoldsUnsavedText() {
        ServedText served = new ServedText();
        assertEquals(ServedText.Verdict.CURRENT, served.check("b", "on disk\n", false));
        assertEquals(ServedText.Verdict.UNREAD_UNSAVED, served.check("b", "only in the editor\n", true));
        served.served("b", "x");
        served.clear();
        assertEquals(ServedText.Verdict.CURRENT, served.check("b", "y", false), "a new session starts unread");
        served.served("b", "x");
        served.forget("b");
        assertEquals(ServedText.Verdict.UNREAD_UNSAVED, served.check("b", "y", true));
    }

    @Test
    void aRefusalTellsTheAgentWhatToDo() {
        assertNull(ServedText.Verdict.CURRENT.refusal("a.txt", "read_buffer"));
        String changed = ServedText.Verdict.CHANGED_SINCE_READ.refusal("a.txt", "read_buffer");
        assertTrue(changed.contains("a.txt") && changed.contains("Read it again (read_buffer)"), changed);
        String unread = ServedText.Verdict.UNREAD_UNSAVED.refusal("a.txt", "fs/read_text_file");
        assertTrue(unread.contains("unsaved changes") && unread.contains("(fs/read_text_file)"), unread);
    }

    @Test
    void knowsIsTrueOnlyForTheExactTextLastServed() {
        ServedText served = new ServedText(new java.util.WeakHashMap<>());
        Object document = new Object();
        org.junit.jupiter.api.Assertions.assertFalse(served.knows(document, "x"));
        served.served(document, "x");
        assertTrue(served.knows(document, "x"));
        org.junit.jupiter.api.Assertions.assertFalse(served.knows(document, "y"));
    }
}
