package com.editora.editor;

import com.editora.editops.TextFingerprint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedBaselineTest {

    @Test
    void holdsTheTextUntilSealedAndMatchesByIdentityOrEquality() {
        String text = "one\ntwo\n";
        SavedBaseline baseline = SavedBaseline.of(text);
        assertTrue(baseline.holdsText());
        assertEquals(text.length(), baseline.length());
        assertTrue(baseline.matches(text));
        assertTrue(baseline.matches(new String(text)));
        assertFalse(baseline.matches("one\ntwO\n"), "same length, different text");
        assertFalse(baseline.matches("one\ntwo"), "different length");
    }

    @Test
    void sealingKeepsOnlyTheFingerprintAndStillTellsTheTextApart() {
        String text = "x".repeat(10_000) + "y";
        SavedBaseline baseline = SavedBaseline.of(text);
        baseline.seal();
        assertFalse(baseline.holdsText());
        assertEquals(TextFingerprint.of(text), baseline.fingerprint());
        assertTrue(baseline.matches(new String(text)));
        assertFalse(baseline.matches("x".repeat(10_000) + "z"));
        baseline.seal(); // idempotent
        assertFalse(baseline.holdsText());
    }

    @Test
    void aWorkerSuppliedFingerprintIsUsedAsIsAndSealingComputesNothing() {
        String text = "abc";
        String fingerprint = TextFingerprint.of(text);
        SavedBaseline baseline = SavedBaseline.of(text, fingerprint);
        assertTrue(baseline.holdsText());
        assertEquals(fingerprint, baseline.fingerprint());
        baseline.seal();
        assertFalse(baseline.holdsText());
        assertTrue(baseline.matches("abc"));
        assertFalse(baseline.matches("abd"));
    }

    @Test
    void aSealedBaselineNeverHeldTheText() {
        String text = "saved by a worker";
        SavedBaseline baseline = SavedBaseline.sealed(text.length(), TextFingerprint.of(text));
        assertFalse(baseline.holdsText());
        assertEquals(text.length(), baseline.length());
        assertTrue(baseline.matches("saved by a worker"));
        assertFalse(baseline.matches("saved by a worker!"));
        assertFalse(baseline.matches("saved by a Worker"));
    }
}
