package com.editora.editor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("fx")
class DocumentSnapshotFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void settledConsumersShareOneMaterializationPerEditVersion() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("class A {\n  void one() {}\n}\n");
                buffer.getArea().appendText("// first\n");
                long before = buffer.documentSnapshotMaterializations();

                assertEquals("// first\n", buffer.text().substring(buffer.text().length() - 9)); // LSP consumer
                buffer.getFoldManager().recompute(); // folding consumer
                buffer.captureUndoCheckpoint(); // Undo History consumer
                assertEquals(before + 1, buffer.documentSnapshotMaterializations());

                buffer.getArea().appendText("// second\n");
                assertEquals(
                        "// second\n", buffer.text().substring(buffer.text().length() - 10));
                buffer.getFoldManager().recompute();
                buffer.captureUndoCheckpoint();
                assertEquals(before + 2, buffer.documentSnapshotMaterializations());
            } finally {
                buffer.dispose();
            }
        });
    }

    private static void runOnFx(Runnable task) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
