package com.editora.editor;

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

    @Test
    void javaMethodSignaturesCanGrowBeyondTheFormerWidthLimit() throws Exception {
        // What CompletionMapper makes of jdtls's Files.find proposal: the signature in the label, the
        // return type as the detail.
        Completion item = Completion.lsp(
                "find(Path start, int maxDepth, BiPredicate<Path,BasicFileAttributes> matcher, FileVisitOption... options)",
                "find",
                "Stream<Path>");

        double width = onFx(() -> CompletionPopup.contentWidth(List.of(item)));

        assertTrue(width > 760, "a typical JDT LS method row must not be capped at the former width");
        assertTrue(width <= 1100, "the popup remains bounded");
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
