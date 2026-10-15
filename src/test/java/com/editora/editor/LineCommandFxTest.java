package com.editora.editor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer.LineCommand;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line commands on a real buffer: with one caret and with several, each as one undo step, with the
 * buffer's own indentation, and with multiple cursors switched off.
 */
@Tag("fx")
class LineCommandFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void insertLineBelowAndAboveIndentLikeTheLine() throws Exception {
        withBuffer("java", "class A {\n    int x;\n}\n", buffer -> {
            buffer.getArea().moveTo(14); // inside "int x;"
            assertTrue(buffer.lineCommand(LineCommand.INSERT_BELOW));
            assertEquals("class A {\n    int x;\n    \n}\n", buffer.getContent());
            assertEquals(25, buffer.getArea().getCaretPosition(), "on the new line, after its indent");

            buffer.getArea().moveTo(12);
            assertTrue(buffer.lineCommand(LineCommand.INSERT_ABOVE));
            assertEquals("class A {\n    \n    int x;\n    \n}\n", buffer.getContent());
            assertEquals(14, buffer.getArea().getCaretPosition());
        });
    }

    @Test
    void everyCommandRunsAtEveryCaretAsOneUndoStep() throws Exception {
        withBuffer("plain", "a1\nb2\nc3\nd4\n", buffer -> {
            buffer.getArea().moveTo(1);
            addCaret(buffer, 7); // on c3
            assertTrue(buffer.lineCommand(LineCommand.DELETE));
            assertEquals("b2\nd4\n", buffer.getContent());
            assertTrue(buffer.hasMultipleCarets(), "both carets stay");
            buffer.getArea().undo();
            assertEquals("a1\nb2\nc3\nd4\n", buffer.getContent());
            assertFalse(buffer.getArea().isUndoAvailable(), "one undo step");
        });
        withBuffer("plain", "a1\nb2\n", buffer -> {
            buffer.getArea().moveTo(0);
            addCaret(buffer, 3);
            assertTrue(buffer.lineCommand(LineCommand.INSERT_BELOW));
            assertEquals("a1\n\nb2\n\n", buffer.getContent());
            carets(buffer).getManager().typeText("x");
            assertEquals("a1\nx\nb2\nx\n", buffer.getContent(), "a caret on each new line");
        });
    }

    @Test
    void selectLineGrowsEverySelectionByWholeLines() throws Exception {
        withBuffer("plain", "one\ntwo\nthree\nfour\n", buffer -> {
            buffer.getArea().moveTo(1);
            assertTrue(buffer.lineCommand(LineCommand.SELECT));
            assertEquals("one\n", buffer.getArea().getSelectedText());
            assertTrue(buffer.lineCommand(LineCommand.SELECT));
            assertEquals("one\ntwo\n", buffer.getArea().getSelectedText());
        });
    }

    @Test
    void indentAndOutdentUseTheBuffersIndentUnitWhereverTheCaretIs() throws Exception {
        withBuffer("java", "class A {\nint x;\n}\n", buffer -> {
            buffer.getArea().moveTo(13); // after "int"
            assertTrue(buffer.lineCommand(LineCommand.INDENT));
            String indented = buffer.getContent();
            assertTrue(
                    indented.startsWith("class A {\n    int x;") || indented.startsWith("class A {\n\tint x;"),
                    indented);
            int unit = indented.indexOf("int") - 10;
            assertEquals(13 + unit, buffer.getArea().getCaretPosition(), "the caret stays after \"int\"");

            assertTrue(buffer.lineCommand(LineCommand.OUTDENT));
            assertEquals("class A {\nint x;\n}\n", buffer.getContent());
            assertEquals(13, buffer.getArea().getCaretPosition());
        });
    }

    @Test
    void withMultipleCursorsOffTheCommandsStillWorkAtTheCaret() throws Exception {
        withBuffer("plain", "a\nb\n", false, buffer -> {
            buffer.getArea().moveTo(2);
            assertTrue(buffer.lineCommand(LineCommand.DELETE));
            assertEquals("a\n", buffer.getContent());
        });
    }

    @Test
    void aReadOnlyBufferIsLeftAloneButCanStillSelect() throws Exception {
        withBuffer("plain", "a\nb\n", buffer -> {
            buffer.setReadOnly(true);
            buffer.getArea().moveTo(0);
            assertFalse(buffer.lineCommand(LineCommand.DELETE));
            assertEquals("a\nb\n", buffer.getContent());
            assertTrue(buffer.lineCommand(LineCommand.SELECT));
            assertEquals("a\n", buffer.getArea().getSelectedText());
        });
    }

    // --- helpers ---

    private static void addCaret(EditorBuffer buffer, int offset) {
        carets(buffer).getManager().addCaretAt(offset);
    }

    private static MultiCarets carets(EditorBuffer buffer) {
        try {
            var f = EditorBuffer.class.getDeclaredField("multiCaret");
            f.setAccessible(true);
            return (MultiCarets) f.get(buffer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void withBuffer(String language, String text, Consumer<EditorBuffer> test) throws Exception {
        withBuffer(language, text, true, test);
    }

    private static void withBuffer(String language, String text, boolean multiCaret, Consumer<EditorBuffer> test)
            throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setMultiCaretEnabled(multiCaret);
                buffer.setLanguageOverride(language);
                buffer.setContent(text);
                buffer.getNode();
                buffer.getArea().requestFocus();
                test.accept(buffer);
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX task timed out");
        if (failure.get() instanceof Error e) {
            throw e;
        }
        if (failure.get() instanceof Exception e) {
            throw e;
        }
    }
}
