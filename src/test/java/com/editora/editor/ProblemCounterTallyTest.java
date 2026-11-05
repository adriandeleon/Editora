package com.editora.editor;

import java.util.List;

import com.editora.text.LspDiagnostic;
import com.editora.text.LspDiagnostic.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** What the editor's problem counter counts (Python review 2, C104). */
class ProblemCounterTallyTest {

    private static LspDiagnostic diag(Severity severity, boolean unnecessary, boolean deprecated) {
        return new LspDiagnostic(0, 0, 0, 1, severity, "m", null, "Pyright", null, unnecessary, deprecated);
    }

    /** Pyright's "os is not accessed" is drawn faded and left out of Problems: the counter leaves it out too. */
    @Test
    void tagOnlyHintsAreNotCounted() {
        int[] counts = ProblemCounter.tally(List.of(
                diag(Severity.ERROR, false, false),
                diag(Severity.HINT, true, false),
                diag(Severity.HINT, false, true),
                diag(Severity.WARNING, false, false)));
        assertArrayEquals(new int[] {1, 1, 0}, counts);
    }

    /** A plain hint or information (no tag) is still a note, as Problems lists it. */
    @Test
    void untaggedHintsAndInformationAreNotes() {
        int[] counts = ProblemCounter.tally(List.of(
                diag(Severity.HINT, false, false),
                diag(Severity.INFO, false, false),
                diag(Severity.INFO, true, false)));
        assertArrayEquals(new int[] {0, 0, 3}, counts);
    }
}
