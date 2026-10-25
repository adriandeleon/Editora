package com.editora.editor.completion;

import java.util.List;

import javafx.application.Platform;

import com.editora.completion.Completion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The completion popup sizes itself to its widest row, wide enough for a Java signature but still capped. */
@Tag("fx")
class CompletionPopupFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    /**
     * A row wider than the former 760px cap gets its own width. The row is grown one parameter at a time rather
     * than fixed, because what a given signature measures depends on the platform's UI font: the jdtls
     * {@code Files.find} row is 833px on Linux and under 760px on macOS. One parameter adds far less than the
     * 340px between the two caps, so the first row past 760px is always short of 1100.
     */
    @Test
    void aRowWiderThanTheFormerCapGetsItsOwnWidth() throws Exception {
        StringBuilder params = new StringBuilder("find(Path start");
        double width = 0;
        for (int i = 0; i < 200 && width <= 760; i++) {
            params.append(", Path p").append(i);
            Completion item = Completion.lsp(params + ")", "find", "Stream<Path>");
            width = onFx(() -> CompletionPopup.contentWidth(List.of(item)));
        }

        assertTrue(width > 760, "a long Java signature is no longer clamped to the former 760px cap");
        assertTrue(width < 1100, "it gets its own width, not the new cap: " + width);
    }

    @Test
    void exceptionallyLongCompletionRowsRemainCapped() throws Exception {
        Completion item = Completion.lsp("method(" + "ParameterType, ".repeat(100) + ")", "method", "Type".repeat(200));

        assertEquals(1100, onFx(() -> CompletionPopup.contentWidth(List.of(item))));
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return task.call();
        }
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var done = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }
}
