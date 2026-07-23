package com.editora.ui;

import java.util.List;

import javafx.scene.layout.Region;

import com.editora.editor.EditorBuffer;
import com.editora.editor.OccurrenceSpan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LSP document highlight (#675): the occurrence overlay attaches lazily, shows on spans, clears on empty. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OccurrenceHighlightFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void spansShowTheOverlayAndEmptyClearsIt() throws Exception {
        boolean[] state = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("int a = 1;\nint b = a + a;\n");
            b.getNode();
            assertNull(FxTestSupport.field(b, "occurrenceOverlay"), "lazy: no overlay before first spans");
            b.setOccurrenceSpans(List.of(
                    new OccurrenceSpan(0, 4, 0, 5, true), // the assignment — Write
                    new OccurrenceSpan(1, 8, 1, 9, false),
                    new OccurrenceSpan(1, 12, 1, 13, false)));
            Region overlay = (Region) FxTestSupport.field(b, "occurrenceOverlay");
            boolean shown = overlay != null && overlay.isVisible();
            b.setOccurrenceSpans(List.of());
            boolean clearedHidden = overlay != null && !overlay.isVisible();
            return new boolean[] {shown, clearedHidden};
        });
        assertTrue(state[0], "overlay visible while spans are set");
        assertTrue(state[1], "overlay hidden (texture released) once cleared");
    }

    @Test
    void largeFileModeIgnoresSpans() throws Exception {
        boolean created = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("x\n");
            b.getNode();
            b.setLargeFile(true);
            b.setOccurrenceSpans(List.of(new OccurrenceSpan(0, 0, 0, 1, false)));
            return FxTestSupport.field(b, "occurrenceOverlay") != null;
        });
        assertFalse(created, "large-file mode never builds the overlay");
    }
}
