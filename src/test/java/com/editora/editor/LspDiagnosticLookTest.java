package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hint (ShellCheck's "style" level) is drawn fainter than information, and unused code is faded — both were
 * drawn exactly like an info diagnostic before (shell review C9).
 */
class LspDiagnosticLookTest {

    private static LspDiagnostic diag(LspDiagnostic.Severity severity, boolean unnecessary) {
        return new LspDiagnostic(0, 0, 0, 3, severity, "m", "SC2004", "shellcheck", null, unnecessary);
    }

    @Test
    void aHintIsAFaintDottedLineAndInformationTheFullWavyOne() {
        LspDiagnosticOverlay.Look hint = LspDiagnosticOverlay.lookOf(diag(LspDiagnostic.Severity.HINT, false));
        LspDiagnosticOverlay.Look info = LspDiagnosticOverlay.lookOf(diag(LspDiagnostic.Severity.INFO, false));
        assertTrue(hint.dotted());
        assertFalse(info.dotted());
        assertTrue(hint.opacity() < info.opacity());
        assertEquals(1.0, info.opacity());
    }

    @Test
    void unusedCodeIsFadedWhateverItsSeverity() {
        assertTrue(LspDiagnosticOverlay.lookOf(diag(LspDiagnostic.Severity.WARNING, true))
                .faded());
        assertFalse(LspDiagnosticOverlay.lookOf(diag(LspDiagnostic.Severity.WARNING, false))
                .faded());
    }

    @Test
    void thePaletteCarriesTheBackgroundUsedToFade() {
        assertEquals(
                javafx.scene.paint.Color.WHITE,
                OverlayPalette.of(javafx.scene.paint.Color.WHITE).ground());
    }

    /** A deprecated call is struck through; nothing else is (Python review B3). */
    @Test
    void deprecatedCodeIsStruckThrough() {
        var deprecated = new LspDiagnostic(
                0, 0, 0, 8, LspDiagnostic.Severity.HINT, "deprecated", null, "Pyright", null, false, true);
        assertTrue(LspDiagnosticOverlay.lookOf(deprecated).struck());
        assertFalse(LspDiagnosticOverlay.lookOf(diag(LspDiagnostic.Severity.HINT, true))
                .struck());
    }

    /** Tag-only hints are drawn but not listed; a real hint or any tagged warning still is. */
    @Test
    void onlyAHintThatIsJustATagIsKeptOutOfProblems() {
        assertTrue(diag(LspDiagnostic.Severity.HINT, true).tagOnlyHint());
        assertFalse(diag(LspDiagnostic.Severity.HINT, false).tagOnlyHint());
        assertFalse(diag(LspDiagnostic.Severity.WARNING, true).tagOnlyHint());
    }
}
