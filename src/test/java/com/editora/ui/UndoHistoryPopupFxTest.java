package com.editora.ui;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the Undo History popup: the {@code undoHistory.jump} command opens the QuickOpen
 * overlay (the popup mirror of the Undo History tool window) for the active buffer's checkpoints.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UndoHistoryPopupFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void jumpCommandOpensTheUndoHistoryPopup() throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");

        // An edited buffer with a couple of captured checkpoints.
        FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("first\n");
            Tab tab = (Tab) FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.captureUndoCheckpoint();
            b.typeString("second");
            b.captureUndoCheckpoint();
            return tab;
        });

        assertFalse(FxTestSupport.callOnFx(overlay::isShowing), "no overlay before the command");
        FxTestSupport.runOnFx(() -> registry.run("undoHistory.jump"));
        assertTrue(FxTestSupport.callOnFx(overlay::isShowing), "undoHistory.jump opens the popup overlay");

        FxTestSupport.runOnFx(overlay::hide);
        assertFalse(FxTestSupport.callOnFx(overlay::isShowing), "overlay dismissed");
        // No tab cleanup: the buffer is dirty, so closeTab would pop a modal save prompt that blocks the
        // FX thread. tearDown()'s programmatic stage close disposes everything without prompting.
    }
}
