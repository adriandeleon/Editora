package com.editora.editor;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.editora.editops.LineEndings;
import com.editora.editops.TextFingerprint;
import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saved baseline keeps a length and a fingerprint, not a second copy of the document — and the buffer's
 * modified flag behaves exactly as it did when it held the text: an edit back to the saved text is clean. The
 * hashes run off the FX thread (PERF-01, Pauta 6.0.1); these tests run that work in place ({@link OffFx}'s seam),
 * except {@link #anUndoBackToTheSavedTextOfALargeDocumentIsHashedOffTheFxThread}, which runs it as the app does.
 */
@Tag("fx")
class EditorBufferBaselineFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @BeforeEach
    void hashInPlace() {
        OffFx.runnerForTest((name, task) -> task.run());
    }

    @AfterEach
    void restore() {
        OffFx.runnerForTest(null);
        BlockingSites.listenForTest(null);
    }

    /**
     * A few MiB, edited and settled (so the baseline is sealed), then undone back to the saved text: the FX thread
     * hashes nothing — the tab stays modified until the hash made off it lands, then is clean — and a different
     * text of the same length is never shown clean.
     */
    @Test
    void anUndoBackToTheSavedTextOfALargeDocumentIsHashedOffTheFxThread() throws Exception {
        OffFx.runnerForTest(null); // as the app runs it
        java.util.List<String> onFx = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> offFx = new java.util.concurrent.CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> (Platform.isFxApplicationThread() ? onFx : offFx).add(site));
        String loaded = "0123456789abcdef\n".repeat(200_000); // 3.4 MB
        EditorBuffer[] holder = new EditorBuffer[1];
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            holder[0] = buffer;
            buffer.setInitialContent(loaded);
            buffer.getArea().insertText(0, "x");
            settle(buffer); // the fingerprint of the held text is worked out off FX, then the text let go
        });
        EditorBuffer buffer = holder[0];
        try {
            waitFor(() -> !buffer.baselineHoldsText());
            runOnFx(() -> {
                buffer.getArea().deleteText(0, 1); // back to the saved text: same length, sealed baseline
                assertTrue(buffer.isDirty(), "unknown until the hash lands: the cheap check's dirty, never clean");
            });
            waitFor(() -> !buffer.isDirty());
            long hashesBefore = hashes(offFx);
            runOnFx(() -> {
                buffer.getArea().replaceText(0, 1, "Z"); // same length, different text
                assertTrue(buffer.isDirty());
            });
            // the hash of the changed text has been made and has landed
            waitFor(() -> hashes(offFx) > hashesBefore && EditorFx.<Long>field(buffer, "hashingVersion") == -1L);
            runOnFx(() -> assertTrue(buffer.isDirty(), "a different text of the same length stays modified"));
            assertEquals(java.util.List.of(), onFx, "nothing hashed on the FX thread");
            assertTrue(offFx.contains(EditorBuffer.DIRTY_HASH_SITE), "hashed off it: " + offFx);
        } finally {
            runOnFx(buffer::dispose);
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier onFxCondition) throws Exception {
        Await.until(
                EditorFx.check(() -> {
                    boolean[] met = {false};
                    runOnFx(() -> met[0] = onFxCondition.getAsBoolean());
                    return met[0];
                }),
                "the condition on the FX thread",
                java.time.Duration.ofSeconds(20));
    }

    private static long hashes(java.util.List<String> sites) {
        return sites.stream().filter(EditorBuffer.DIRTY_HASH_SITE::equals).count();
    }

    @Test
    void aDirtyBufferEditedBackToItsSavedTextIsCleanAgain() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("alpha\nbeta\n");
                assertFalse(buffer.isDirty());

                buffer.getArea().appendText("gamma");
                assertTrue(buffer.isDirty());
                buffer.getArea()
                        .deleteText(
                                buffer.getArea().getLength() - 5,
                                buffer.getArea().getLength());
                assertFalse(buffer.isDirty(), "a different-length edit undone by hand is clean");

                buffer.getArea().replaceText(0, 1, "A"); // same length: a clean buffer compares at once
                assertTrue(buffer.isDirty());
                buffer.getArea().replaceText(0, 1, "a"); // same length while dirty: compared when asked
                assertFalse(buffer.isDirty(), "a same-length edit back to the saved text is clean");
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void aCrlfFileComparesEqualToItsBaselineAfterAnEditAndItsUndo() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("one\r\ntwo\r\n");
                assertEquals(LineEndings.CRLF, buffer.getLineEnding());
                assertFalse(buffer.isDirty());

                buffer.getArea().insertText(0, "x");
                assertTrue(buffer.isDirty());
                buffer.getArea().getUndoManager().undo();
                assertFalse(buffer.isDirty(), "the baseline is kept in the area's own LF form");
                assertEquals(LineEndings.CRLF, buffer.getLineEnding());

                buffer.convertLineEndings(false);
                assertTrue(buffer.isDirty(), "a different line ending is an unsaved change");
                buffer.convertLineEndings(true);
                assertFalse(buffer.isDirty());
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void onceSettledDirtyTheBaselineHoldsAFingerprintNotTheText() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                String loaded = "line\n".repeat(2000);
                buffer.setInitialContent(loaded);
                assertTrue(buffer.baselineHoldsText(), "while clean the baseline is the shared snapshot");

                buffer.getArea().appendText("tail");
                assertTrue(buffer.isDirty());
                settle(buffer); // what the 150 ms settled-edit milestone does
                assertFalse(buffer.baselineHoldsText(), "a dirty tab holds no second copy of its document");

                buffer.getArea().deleteText(loaded.length(), loaded.length() + 4);
                assertFalse(buffer.isDirty(), "compared by hash against the fingerprint");
                settle(buffer);
                assertFalse(buffer.baselineHoldsText(), "nothing brings the text back but a save or a load");

                buffer.getArea().appendText("more");
                buffer.markClean(); // a save
                assertTrue(buffer.baselineHoldsText(), "a save shares the snapshot again");
                assertFalse(buffer.isDirty());
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void aSaveAcknowledgedByVersionLengthAndFingerprintSharesTheSnapshotOnlyWhenUnchanged() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("saved text\n");
                buffer.getArea().appendText("edit");
                String content = buffer.getContent();
                long version = buffer.docVersion();
                String fingerprint = TextFingerprint.of(content);

                // Acknowledged with the document untouched since the capture: clean, snapshot shared.
                buffer.acknowledgeSavedContent(version, content.length(), fingerprint, buffer.getLineEnding());
                assertFalse(buffer.isDirty());
                assertTrue(buffer.baselineHoldsText());

                // Edited while the write was in flight: still dirty, and the baseline starts sealed.
                buffer.getArea().appendText("!");
                buffer.acknowledgeSavedContent(version, content.length(), fingerprint, buffer.getLineEnding());
                assertTrue(buffer.isDirty());
                assertFalse(buffer.baselineHoldsText());
                buffer.getArea().deleteText(content.length(), content.length() + 1);
                assertFalse(buffer.isDirty(), "back at what was saved: compared by hash");

                // A line-ending conversion chosen while the write was in flight is not on disk.
                buffer.acknowledgeSavedContent(version, content.length(), fingerprint, LineEndings.CRLF);
                assertTrue(buffer.isDirty());
                buffer.convertLineEndings(true);
                assertFalse(buffer.isDirty());
            } finally {
                buffer.dispose();
            }
        });
    }

    private static void settle(EditorBuffer buffer) {
        try {
            Method seal = EditorBuffer.class.getDeclaredMethod("sealBaselineIfDirty");
            seal.setAccessible(true);
            seal.invoke(buffer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void runOnFx(Runnable body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX work did not finish");
        Throwable t = failure.get();
        if (t instanceof Error e) {
            throw e;
        }
        if (t != null) {
            throw new AssertionError(t);
        }
    }
}
