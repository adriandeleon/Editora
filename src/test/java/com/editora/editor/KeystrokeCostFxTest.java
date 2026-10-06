package com.editora.editor;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.editora.editops.IndentWindow;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work that used to be done per keystroke against the whole document, pinned by counts rather than by a
 * clock: the dirty check, the indent unit, the document view the Enter/Tab edits read, and the run scan.
 */
@Tag("fx")
class KeystrokeCostFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    // --- the dirty check ---

    @Test
    void sameLengthEditsOfADirtyBufferDoNotCopyTheDocument() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("one\ntwo\nthree\n");
                assertFalse(buffer.isDirty());
                CodeArea area = buffer.getArea();

                // Move line down, as edit.moveLineDown does it: one same-length replace. From a clean buffer
                // that is compared at once — a clean buffer must never show as modified when it is not.
                area.replaceText(0, 8, "two\none\n");
                assertTrue(buffer.dirtyProperty().get(), "a real change is dirty immediately");

                long before = buffer.documentSnapshotMaterializations();
                for (int i = 0; i < 20; i++) { // keep moving it: each one used to build and compare the text
                    area.replaceText(0, 8, i % 2 == 0 ? "one\ntwo\n" : "two\none\n");
                }
                area.replaceText(4, 7, "ONE"); // overwrite in place: same length again
                assertEquals(
                        before,
                        buffer.documentSnapshotMaterializations(),
                        "no same-length edit of a dirty buffer materializes the document");
                assertTrue(buffer.dirtyProperty().get(), "and the buffer stays dirty meanwhile");
                assertTrue(buffer.isDirty());
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void returningToTheSavedTextClearsTheFlagWhenAskedAndWhenTheEditSettles() throws Exception {
        AtomicReference<EditorBuffer> ref = new AtomicReference<>();
        CountDownLatch cleared = new CountDownLatch(1);
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            ref.set(buffer);
            buffer.setInitialContent("one\ntwo\nthree\n");
            CodeArea area = buffer.getArea();
            area.replaceText(0, 8, "two\none\n"); // move line down
            assertTrue(buffer.isDirty());
            area.replaceText(0, 8, "one\ntwo\n"); // and back up: the saved text again
            assertTrue(buffer.dirtyProperty().get(), "not compared on the edit itself");
            assertFalse(buffer.isDirty(), "asking gives the exact answer at once");
            assertFalse(buffer.dirtyProperty().get());

            // The same round trip without anyone asking: the settled edit resolves it.
            area.replaceText(0, 8, "two\none\n");
            area.replaceText(0, 8, "one\ntwo\n");
            assertTrue(buffer.dirtyProperty().get());
            buffer.dirtyProperty().addListener((obs, was, now) -> {
                if (!now) {
                    cleared.countDown();
                }
            });
        });
        try {
            assertTrue(cleared.await(20, TimeUnit.SECONDS), "the tab's dirty marker clears once typing pauses");
        } finally {
            runOnFx(() -> ref.get().dispose());
        }
    }

    @Test
    void undoBackToTheSavedTextIsCleanAndADifferentLengthIsDecidedWithoutTheText() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setInitialContent("alpha\nbeta\n");
                CodeArea area = buffer.getArea();
                long before = buffer.documentSnapshotMaterializations();
                area.insertText(0, "x");
                assertTrue(buffer.dirtyProperty().get());
                area.insertText(1, "y");
                assertEquals(before, buffer.documentSnapshotMaterializations(), "a length change needs no text");
                area.deleteText(0, 2); // back to the saved length — and the saved text
                assertFalse(buffer.isDirty(), "typing then deleting it again is clean");

                area.replaceText(0, 5, "ALPHA");
                assertTrue(buffer.isDirty());
                area.undo();
                assertFalse(buffer.isDirty(), "undo back to the saved text is clean");
            } finally {
                buffer.dispose();
            }
        });
    }

    // --- Enter / Tab ---

    @Test
    void theAreaIsAddressedByLineExactlyLikeItsText() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                String text = "class A {\n\n    void m() {\n        call(1,\n            2);\n    }\n}\n\nlast";
                buffer.setInitialContent(text);
                IndentWindow.Doc area = IndentKeys.doc(buffer.getArea());
                IndentWindow.Doc string = IndentWindow.of(text);
                assertEquals(string.length(), area.length());
                for (int offset = 0; offset <= text.length(); offset++) {
                    int line = string.lineOf(offset);
                    assertEquals(line, area.lineOf(offset), "line of offset " + offset);
                    assertEquals(string.lineStart(line), area.lineStart(line));
                    assertEquals(string.lineLength(line), area.lineLength(line));
                }
                assertEquals(text.substring(3, 30), area.text(3, 30));
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void theIndentUnitIsDetectedOncePerChangeToTheDocumentHead() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                StringBuilder sb = new StringBuilder("class A {\n\tvoid m() {\n\t}\n");
                while (sb.length() < 3 * IndentWindow.HEAD_CHARS) {
                    sb.append("\t// filler line to push the caret well below the head\n");
                }
                buffer.setInitialContent(sb.toString());
                CodeArea area = buffer.getArea();
                IndentKeys keys = field(buffer, "indentKeys");

                assertEquals("\t", keys.unit(4, null, null));
                int detected = keys.detections();
                area.moveTo(area.getLength());
                for (int i = 0; i < 25; i++) {
                    buffer.typeChar('x');
                    buffer.typeChar('\n'); // Enter: needs the unit every time
                    assertEquals("\t", keys.unit(4, null, null));
                }
                assertEquals(detected, keys.detections(), "typing below the head re-detects nothing");
                assertFalse(buffer.detectInsertSpaces(4));

                assertEquals("  ", keys.unit(2, Boolean.TRUE, 2), "an override never reads the document");
                assertEquals(detected, keys.detections());

                area.replaceText(0, area.getLength(), "class A {\n    void m() {\n    }\n}\n"); // now spaces
                assertEquals("    ", keys.unit(4, null, null));
                assertEquals(detected + 1, keys.detections(), "a change to the head re-detects once");
                assertTrue(buffer.detectInsertSpaces(4));
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void enterTabAndACloserGiveTheUsualEditsFarDownALongFile() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setLanguageOverride("java");
                StringBuilder sb = new StringBuilder("class A {\n");
                for (int i = 0; i < 2000; i++) {
                    sb.append("    int field").append(i).append(" = ").append(i).append(";\n");
                }
                sb.append("    void m() {");
                buffer.setInitialContent(sb.toString());
                CodeArea area = buffer.getArea();
                area.moveTo(area.getLength());

                buffer.typeChar('\n'); // after an opener: one level deeper
                assertEquals("        ", area.getText(area.getCurrentParagraph()));
                buffer.typeString("int x;");
                buffer.typeChar('\n');
                buffer.typeChar('}'); // a closer alone on its line: back to the opener's indent
                assertEquals("    }", area.getText(area.getCurrentParagraph()));

                buffer.typeChar('\n');
                area.replaceText(area.getAbsolutePosition(area.getCurrentParagraph(), 0), area.getLength(), "");
                buffer.typeChar('\t'); // Tab on an empty line: snap to the indent the code above implies
                assertEquals("    ", area.getText(area.getCurrentParagraph()));
            } finally {
                buffer.dispose();
            }
        });
    }

    // --- the run scan ---

    @Test
    void theSettledRunScanIsNotDoneOnTheFxThread() throws Exception {
        AtomicReference<EditorBuffer> ref = new AtomicReference<>();
        CountDownLatch runnable = new CountDownLatch(1);
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            ref.set(buffer);
            buffer.setLanguageOverride("makefile");
            buffer.setInitialContent("# no targets yet\n");
            assertFalse(buffer.isRunnable());
            buffer.setOnRunnableChanged(runnable::countDown);

            buffer.getArea().appendText("all:\n\t@echo hi\n");
            invoke(buffer, "recomputeRun", true); // what the 150 ms settle does
            assertFalse(buffer.isRunnable(), "the scan has not run on this (the FX) thread");
        });
        try {
            assertTrue(runnable.await(20, TimeUnit.SECONDS), "its result arrives and is applied");
            runOnFx(() -> {
                assertTrue(ref.get().isRunnable());
                ref.get().getArea().replaceText("# gone again\n");
                invoke(ref.get(), "recomputeRun"); // load / Save As / a toggle still answer at once
                assertFalse(ref.get().isRunnable());
            });
        } finally {
            runOnFx(() -> ref.get().dispose());
        }
    }

    // --- helpers ---

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invoke(Object target, String name, Object... args) {
        try {
            Method m = args.length == 0
                    ? target.getClass().getDeclaredMethod(name)
                    : target.getClass().getDeclaredMethod(name, boolean.class);
            m.setAccessible(true);
            m.invoke(target, args);
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
