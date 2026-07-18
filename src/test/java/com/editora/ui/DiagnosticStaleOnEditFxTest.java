package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspDiagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #417: LSP diagnostics are anchored to absolute line/col and only replaced when the server
 * re-pushes. Between an edit and that push, the overlay/stripe/minimap must NOT keep painting the old marks
 * on the now-shifted lines — an edit clears them until {@code setLspDiagnostics} re-anchors.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiagnosticStaleOnEditFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final List<LspDiagnostic> ONE =
            List.of(new LspDiagnostic(2, 0, 2, 4, LspDiagnostic.Severity.ERROR, "oops", "E1", "javac"));

    @SuppressWarnings("unchecked")
    private static int overlayCount(EditorBuffer b) {
        Object overlay = FxTestSupport.field(b, "lspOverlay");
        return overlay == null ? 0 : ((List<?>) FxTestSupport.field(overlay, "diagnostics")).size();
    }

    @Test
    void anEditClearsTheDiagnosticOverlayUntilTheNextPush() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("class A {\n  int x;\n  bad code\n}\n");
            b.getNode(); // realize the scene graph so the overlay can attach
            b.setLspActive(true);
            b.setLspDiagnostics(ONE);
            return b;
        });
        assertEquals(1, FxTestSupport.callOnFx(() -> overlayCount(buffer)), "diagnostic painted");

        // The user inserts a line above the error — its stored line/col now point at shifted text.
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "// header\n"));
        assertEquals(
                0,
                FxTestSupport.callOnFx(() -> overlayCount(buffer)),
                "the overlay is cleared on edit so nothing paints on the shifted line");

        // The server re-pushes → the overlay re-anchors.
        FxTestSupport.runOnFx(() -> buffer.setLspDiagnostics(ONE));
        assertEquals(1, FxTestSupport.callOnFx(() -> overlayCount(buffer)), "re-anchored on the next push");
    }
}
