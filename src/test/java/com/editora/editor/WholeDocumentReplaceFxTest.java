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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole-document rewrite is one undo step that holds the text that changed, not the document twice — and
 * the undo queue is bounded by the text it retains as well as by its entry count.
 */
@Tag("fx")
class WholeDocumentReplaceFxTest {

    private static final String LINE = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde\n";

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void threeHundredRewritesOfALargeBufferRetainOnlyWhatChanged() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                String original = LINE.repeat(16 * 1024); // 1 MiB
                buffer.setInitialContent(original);
                CompletionUndoManager<?> undo =
                        (CompletionUndoManager<?>) buffer.getArea().getUndoManager();
                assertEquals(0, undo.historyEntries(), "a load is not an undo step");

                String text = original;
                for (int i = 0; i < 300; i++) {
                    int at = (i * 3491) % text.length();
                    char now = text.charAt(at) == 'X' ? 'Y' : 'X';
                    if (text.charAt(at) == '\n') {
                        at++;
                    }
                    text = text.substring(0, at) + now + text.substring(at + 1);
                    buffer.replaceWholeDocument(text);
                }
                assertEquals(text, buffer.getContent());
                assertEquals(300, undo.historyEntries(), "each rewrite is its own undo step");
                assertEquals(
                        600,
                        undo.historyChars(),
                        "one removed and one inserted character per step — not 2 MiB of document each");

                for (int i = 0; i < 300; i++) {
                    assertTrue(buffer.getArea().isUndoAvailable());
                    buffer.getArea().undo();
                }
                assertEquals(original, buffer.getContent(), "undo restores the exact text");
                assertFalse(buffer.getArea().isUndoAvailable());
                for (int i = 0; i < 300; i++) {
                    buffer.getArea().redo();
                }
                assertEquals(text, buffer.getContent(), "and redo the rewritten one");
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void anEqualReplacementIsNotAnEdit() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("alpha\nbeta\n");
                buffer.getArea().moveTo(3);
                long version = buffer.docVersion();
                buffer.replaceWholeDocument("alpha\r\nbeta\r\n"); // the area never holds a '\r': same text
                assertEquals(version, buffer.docVersion());
                assertFalse(buffer.isDirty());
                assertFalse(buffer.getArea().isUndoAvailable());
                assertEquals(3, buffer.getArea().getCaretPosition());
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void theCaretEndsWhereReplacingEverythingLeftItAndOneUndoRevertsTheRewrite() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("one\ntwo\nthree\n");
                buffer.getArea().insertText(0, "// typed\n");
                buffer.replaceWholeDocument("// typed\none\nTWO\nthree\n");
                assertEquals("// typed\none\nTWO\nthree\n", buffer.getArea().getText());
                assertEquals(buffer.getArea().getLength(), buffer.getArea().getCaretPosition());
                assertEquals(0, buffer.getArea().getSelectedText().length());

                buffer.getArea().undo();
                assertEquals("// typed\none\ntwo\nthree\n", buffer.getArea().getText(), "not merged with the typing");
                buffer.getArea().undo();
                assertEquals("one\ntwo\nthree\n", buffer.getArea().getText());
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void aNarrowedBufferIsWidenedAndRewrittenAsAWhole() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("head\nbody\ntail\n");
                assertTrue(buffer.narrowTo(5, 9));
                assertTrue(buffer.isNarrowed());
                buffer.replaceWholeDocument("head\nBODY\ntail\n");
                assertFalse(buffer.isNarrowed());
                assertEquals("head\nBODY\ntail\n", buffer.getContent());
                assertEquals("head\nBODY\ntail\n", buffer.getArea().getText());
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void theUndoQueueEvictsItsOldestStepsOnceTheRetainedTextExceedsTheBudget() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                // Each step replaces 4 M characters by 4 M others: 8 M retained, so the 64 M budget holds 8.
                String a = "a".repeat(4 << 20);
                String b = "b".repeat(4 << 20);
                buffer.setInitialContent(a);
                CompletionUndoManager<?> undo =
                        (CompletionUndoManager<?>) buffer.getArea().getUndoManager();
                for (int i = 0; i < 12; i++) {
                    buffer.replaceWholeDocument(i % 2 == 0 ? b : a);
                }
                assertEquals(8, undo.historyEntries());
                assertTrue(undo.historyChars() <= CompletionUndoFactory.RETAINED_CHARS);

                int undone = 0;
                while (buffer.getArea().isUndoAvailable()) {
                    buffer.getArea().undo();
                    undone++;
                }
                assertEquals(8, undone, "the evicted steps are simply no longer undoable");
                assertEquals(a, buffer.getArea().getText(), "eight steps back from 'a' is 'a' again");
                for (int i = 0; i < 8; i++) {
                    assertTrue(buffer.getArea().isRedoAvailable());
                    buffer.getArea().redo();
                }
                assertEquals(a, buffer.getArea().getText());
                assertFalse(buffer.getArea().isRedoAvailable());
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
        assertTrue(done.await(120, TimeUnit.SECONDS), "FX task timed out");
        if (failure.get() instanceof Error e) {
            throw e;
        }
        if (failure.get() instanceof Exception e) {
            throw e;
        }
    }
}
