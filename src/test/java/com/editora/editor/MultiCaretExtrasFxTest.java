package com.editora.editor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Split Selection into Lines, Add Carets to Line Ends, Skip Occurrence and Remove Last Added Caret on a real
 * area with the fork's multi-caret manager: where the carets end up, in which order, and that typing at them
 * afterwards is one edit.
 */
@Tag("fx")
class MultiCaretExtrasFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void splitIntoLinesSelectsEachLinesPartAndTypingReplacesThemAllAtOnce() throws Exception {
        withBuffer("one\ntwo\nthree\n", buffer -> {
            buffer.getArea().selectRange(1, 11); // "ne\ntwo\nthr"
            assertEquals(3, buffer.splitSelectionIntoLines(false));
            assertEquals("1-3 4-7 8-11", carets(buffer), "primary first, then the others in order");

            type(buffer, "X");
            assertEquals("oX\nX\nXee\n", buffer.getContent());
            buffer.getArea().undo();
            assertEquals("one\ntwo\nthree\n", buffer.getContent());
            assertFalse(buffer.getArea().isUndoAvailable(), "one undo step for every line");
        });
    }

    @Test
    void caretsAtLineEndsPutABareCaretAtTheEndOfEveryLine() throws Exception {
        withBuffer("one\ntwo\nthree\n", buffer -> {
            buffer.getArea().selectRange(0, 14); // through "three", not into the empty last line
            assertEquals(3, buffer.splitSelectionIntoLines(true));
            type(buffer, ";");
            assertEquals("one;\ntwo;\nthree;\n", buffer.getContent());
        });
    }

    @Test
    void everySelectionIsSplitAndNothingIsSelectedMeansNoCarets() throws Exception {
        withBuffer("aa\nbb\ncc\ndd\n", buffer -> {
            assertEquals(0, buffer.splitSelectionIntoLines(false), "a bare caret has no lines to split");
            buffer.placeOccurrenceCarets(List.of(new int[] {0, 4}, new int[] {6, 10}), 0); // "aa\nb", "cc\nd"
            assertEquals(4, buffer.splitSelectionIntoLines(true));
            type(buffer, "!");
            assertEquals("aa!\nbb!\ncc!\ndd!\n", buffer.getContent());
        });
    }

    @Test
    void skipOccurrenceMovesTheNewestSelectionOn() throws Exception {
        withBuffer("id x id y id z id", buffer -> {
            buffer.getArea().selectRange(0, 2);
            buffer.addCaretNextOccurrence(); // + 5-7
            assertEquals("0-2 5-7", carets(buffer));

            assertTrue(buffer.skipOccurrence());
            assertEquals("0-2 10-12", carets(buffer), "the newest moved on; the one it left is free");
            assertTrue(buffer.skipOccurrence());
            assertEquals("0-2 15-17", carets(buffer));
            assertTrue(buffer.skipOccurrence());
            assertEquals("0-2 5-7", carets(buffer), "wraps round past the primary");

            type(buffer, "n");
            assertEquals("n x n y id z id", buffer.getContent());
        });
    }

    @Test
    void skipOccurrenceWithOneSelectionMovesItAndStopsWhenThereIsNoOther() throws Exception {
        withBuffer("id x id", buffer -> {
            buffer.getArea().selectRange(0, 2);
            assertTrue(buffer.skipOccurrence());
            assertEquals("5-7", carets(buffer));
        });
        withBuffer("id only", buffer -> {
            buffer.getArea().selectRange(0, 2);
            assertFalse(buffer.skipOccurrence(), "no other occurrence");
            buffer.getArea().moveTo(3);
            assertFalse(buffer.skipOccurrence(), "nothing selected");
        });
    }

    @Test
    void removeLastAddedCaretGoesBackwardsThroughTheOrderTheyWereAdded() throws Exception {
        withBuffer("a\nb\nc\nd\n", buffer -> {
            buffer.getArea().moveTo(0);
            MultiCarets carets = (MultiCarets) field(buffer, "multiCaret");
            carets.getManager().addCaretAt(6); // d, then b, then c: not document order
            carets.getManager().addCaretAt(2);
            carets.getManager().addCaretAt(4);

            assertTrue(buffer.undoLastCaret());
            assertEquals("0-0 6-6 2-2", carets(buffer), "c, the last added, is gone");
            assertTrue(buffer.undoLastCaret());
            assertEquals("0-0 6-6", carets(buffer));
            assertTrue(buffer.undoLastCaret());
            assertFalse(buffer.hasMultipleCarets());
            assertFalse(buffer.undoLastCaret(), "the primary caret is never removed");
        });
    }

    // --- helpers ---

    /** The carets as "anchor-caret" pairs, the primary first, then the extras in the order they were added. */
    private static String carets(EditorBuffer buffer) {
        MultiCarets carets = (MultiCarets) field(buffer, "multiCaret");
        StringBuilder b = new StringBuilder();
        for (int[] s : carets.selections()) {
            b.append(s[0]).append('-').append(s[1]).append(' ');
        }
        return b.toString().trim();
    }

    private static void type(EditorBuffer buffer, String text) {
        buffer.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, text, text, KeyCode.UNDEFINED, false, false, false, false));
    }

    private static void withBuffer(String text, Consumer<EditorBuffer> test) throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setMultiCaretEnabled(true);
                buffer.setContent(text);
                buffer.getNode();
                buffer.getArea().requestFocus();
                test.accept(buffer);
            } finally {
                buffer.dispose();
            }
        });
    }

    private static Object field(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX task timed out");
        if (failure.get() instanceof Error e) {
            throw e;
        }
        if (failure.get() instanceof Exception e) {
            throw e;
        }
    }
}
