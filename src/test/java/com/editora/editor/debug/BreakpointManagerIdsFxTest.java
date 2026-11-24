package com.editora.editor.debug;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whether a file's breakpoints include one another may wait for, asked per line shift without copying them. */
@Tag("fx")
class BreakpointManagerIdsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
    }

    @Test
    void aBreakpointShiftLooksAtTriggersOnlyInAFileWithATrigger() throws Exception {
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                CodeArea area = new CodeArea("a()\nb()\nc()\n");
                BreakpointManager bps = new BreakpointManager(area);
                assertFalse(bps.anyWithId());
                bps.toggle(1);
                assertFalse(bps.anyWithId(), "none waits for this file's: a shift here changes no trigger (D9)");
                bps.setId(1, "t1");
                assertTrue(bps.anyWithId());
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
}
