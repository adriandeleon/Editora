package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The text an agent was shown is kept and compared, not hashed on the FX thread (PERF-01, Pauta 6.0.1). */
class ServedTextSameTextTest {

    @Test
    void lineTerminatorsDoNotCountAndAnythingElseDoes() {
        assertTrue(ServedText.sameText("a\nb\n", "a\r\nb\r\n"));
        assertTrue(ServedText.sameText("a\rb", "a\nb"));
        assertTrue(ServedText.sameText("", ""));
        assertFalse(ServedText.sameText("a\nb", "a\n\nb"));
        assertFalse(ServedText.sameText("a\r\n", "a\n\n"));
        assertFalse(ServedText.sameText("ab", "abc"));
        assertFalse(ServedText.sameText("ab", "ac"));
    }

    @Test
    void aWriteIsJudgedAgainstTheTextServed() {
        ServedText served = new ServedText();
        served.served("k", "one\r\ntwo\r\n");
        assertEquals(ServedText.Verdict.CURRENT, served.check("k", "one\ntwo\n", true));
        assertEquals(ServedText.Verdict.CHANGED_SINCE_READ, served.check("k", "one\ntwo!\n", true));
        assertTrue(served.knows("k", "one\ntwo\n"));
        served.forget("k");
        assertEquals(ServedText.Verdict.UNREAD_UNSAVED, served.check("k", "x", true));
    }

    @Test
    void theOldestTextsAreForgottenPastTheBoundButNotTheOneJustServed() {
        ServedText served = new ServedText();
        String big = "x".repeat(20 * 1024 * 1024);
        served.served("old", big);
        served.served("new", big + "y");
        assertTrue(served.knows("new", big + "y"), "the document just served stays");
        assertFalse(served.knows("old", big), "an older one past the bound is forgotten");
    }
}
