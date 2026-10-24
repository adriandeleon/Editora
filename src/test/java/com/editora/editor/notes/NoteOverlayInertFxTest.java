package com.editora.editor.notes;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.config.FileIdentity;
import com.editora.config.NoteScope;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Personal-Notes highlight is active for every buffer, but almost no buffer has a note: until one is
 * positioned the overlay must queue no redraw and read nothing from the viewport (K4 in the 2026-10 perf
 * review), the way the TODO overlay is off until it has marks.
 */
@Tag("fx")
class NoteOverlayInertFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void noRedrawIsQueuedAndNoSpanIsReadWhileThereIsNothingToPaint() throws Exception {
        runOnFx(() -> {
            CodeArea area = new CodeArea("one\ntwo\nthree\n");
            NoteHighlightOverlay overlay = new NoteHighlightOverlay(area);
            AtomicInteger spanReads = new AtomicInteger();
            boolean[] hasSpans = {false};
            overlay.setSpans(
                    () -> {
                        spanReads.incrementAndGet();
                        return List.of(new int[] {0, 3});
                    },
                    () -> hasSpans[0]);
            overlay.setActive(true);
            overlay.resize(400, 300);
            overlay.layout();

            for (int i = 0; i < 20; i++) { // edits and scroll steps: the overlay's own triggers
                area.insertText(0, "x");
                area.estimatedScrollYProperty().setValue((double) i);
            }
            overlay.refresh();
            assertFalse(redrawPending(overlay), "nothing to paint: no runLater is queued");
            assertEquals(0, spanReads.get(), "and the span list is never built");

            hasSpans[0] = true;
            overlay.refresh();
            assertTrue(redrawPending(overlay), "a positioned note arms the usual coalesced redraw");
        });
    }

    @Test
    void theNoteManagerAnswersTheGateFromItsNotes() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("alpha beta\ngamma\n");
                NoteManager notes = buffer.getNoteManager();
                assertFalse(notes.hasActiveSpans(), "a fresh buffer has no note to paint");
                PersonalNote note = PersonalNote.create(
                        new FileIdentity("/f", "/f", 0, 0, ""),
                        NoteScope.RANGE,
                        new TextAnchor(0, 6, 0, 10, "beta", "alpha ", "\n", 4),
                        "a note",
                        List.of());
                notes.add(note);
                assertTrue(notes.hasActiveSpans(), "a positioned note turns the overlay on");
                assertEquals(1, notes.activeSpans().size());
                notes.remove(note.id());
                assertFalse(notes.hasActiveSpans(), "and removing it turns it back off");
            } finally {
                buffer.dispose();
            }
        });
    }

    /**
     * Every buffer hit-tests the pointer against its notes on each move; one without a positioned note has
     * nothing to find, so it must not ask the layout where the pointer is.
     */
    @Test
    void aBufferWithoutNotesDoesNotHitTestThePointer() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            Stage stage = new Stage();
            try {
                buffer.setInitialContent("alpha beta\ngamma\n");
                stage.setScene(new Scene(buffer.getNode(), 500, 300));
                stage.show();
                buffer.getNode().applyCss();
                buffer.getNode().layout();
                CodeArea area = buffer.getArea();
                Bounds box = area.getCharacterBoundsOnScreen(6, 7).orElseThrow();
                NoteHover.HITS_FOR_TEST.set(0);
                for (int i = 0; i < 20; i++) {
                    area.fireEvent(moved(box.getCenterX() + i, box.getCenterY()));
                }
                assertEquals(0, NoteHover.HITS_FOR_TEST.get(), "no note: no hit-test");

                buffer.getNoteManager()
                        .add(PersonalNote.create(
                                null,
                                NoteScope.WORD,
                                new TextAnchor(0, 6, 0, 10, "beta", "alpha ", "\n", 4),
                                "a note",
                                List.of()));
                area.fireEvent(moved(box.getCenterX(), box.getCenterY()));
                assertEquals(1, NoteHover.HITS_FOR_TEST.get(), "with one, the pointer is hit-tested");
            } finally {
                stage.close();
                buffer.dispose();
            }
        });
    }

    /** The overlay follows edits by itself; the note manager signals it only when the set of notes changes. */
    @Test
    void editsDoNotSignalTheOverlayButChangesToTheNotesDo() throws Exception {
        runOnFx(() -> {
            CodeArea area = new CodeArea("alpha beta\ngamma\n");
            NoteManager notes = new NoteManager(area);
            AtomicInteger signals = new AtomicInteger();
            notes.setOnSpansChanged(signals::incrementAndGet);
            PersonalNote note = PersonalNote.create(
                    null, NoteScope.WORD, new TextAnchor(0, 6, 0, 10, "beta", "alpha ", "\n", 4), "a note", List.of());
            notes.add(note);
            assertEquals(1, signals.get(), "added");
            for (int i = 0; i < 10; i++) {
                area.insertText(0, i % 2 == 0 ? "\n" : "x");
            }
            assertEquals(1, signals.get(), "edits move the note without a signal");
            assertEquals(
                    List.of(6 + 10, 10 + 10),
                    List.of(notes.activeSpans().get(0)[0], notes.activeSpans().get(0)[1]));
            notes.setStatus(note.id(), com.editora.config.NoteStatus.RESOLVED);
            notes.remove(note.id());
            notes.restore(List.of(note));
            assertEquals(4, signals.get(), "resolved, removed, restored");
        });
    }

    private static MouseEvent moved(double screenX, double screenY) {
        return new MouseEvent(
                MouseEvent.MOUSE_MOVED,
                0,
                0,
                screenX,
                screenY,
                MouseButton.NONE,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    private static boolean redrawPending(Object overlay) {
        try {
            Field f = overlay.getClass().getDeclaredField("redrawPending");
            f.setAccessible(true);
            return f.getBoolean(overlay);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
        if (!done.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
