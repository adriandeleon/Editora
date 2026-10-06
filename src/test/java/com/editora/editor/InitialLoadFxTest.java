package com.editora.editor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a load leaves behind and what it does on the FX thread: one whole-document String (not the loaded text
 * plus a copy read back out of RichTextFX), and no synchronous whole-document fold scan for a long file.
 */
@Tag("fx")
class InitialLoadFxTest {

    /** A document long enough for a deferred fold restore, with a foldable block at lines 1..3. */
    private static final String LONG_SOURCE = longSource();

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void theLoadedStringIsTheBaselineAndTheSnapshot() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                String loaded = "class A {\n    void one() {}\n}\n";
                long before = buffer.documentSnapshotMaterializations();
                buffer.setInitialContent(loaded);

                assertSame(loaded, buffer.getContent(), "the loaded String itself serves whole-document readers");
                assertSame(loaded, field(buffer, "cleanText"));
                assertEquals(
                        before,
                        buffer.documentSnapshotMaterializations(),
                        "nothing copied the document back out of RichTextFX");
                assertFalse(buffer.isDirty());
                assertSame(
                        loaded,
                        buffer.getUndoHistory().entriesNewestFirst().getFirst().text(),
                        "the first Undo History checkpoint shares it too");

                buffer.getArea().appendText("// edit\n");
                assertTrue(buffer.isDirty());
                assertEquals(loaded + "// edit\n", buffer.getContent(), "an edit drops the seeded snapshot");
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void aPreparedDocumentInstallsTheSameTextAndLineEnding() throws Exception {
        String crlf = "one\r\ntwo\r\nthree";
        EditorBuffer[] holder = new EditorBuffer[1];
        runOnFx(() -> holder[0] = new EditorBuffer());
        EditorBuffer buffer = holder[0];
        // Built on this (non-FX) thread, as the file-read worker does.
        InitialDocument prepared = buffer.prepareInitialContent(crlf, false);
        assertNotNull(prepared.document());
        runOnFx(() -> {
            try {
                long before = buffer.documentSnapshotMaterializations();
                buffer.setInitialContent(prepared);
                assertEquals("one\ntwo\nthree", buffer.getArea().getText());
                assertEquals(3, buffer.getArea().getParagraphs().size());
                assertEquals(LineEndings.CRLF, buffer.getLineEnding());
                assertSame(prepared.text(), buffer.getContent());
                assertEquals(before, buffer.documentSnapshotMaterializations());
                assertFalse(buffer.isDirty());
                assertFalse(buffer.getArea().getUndoManager().isUndoAvailable(), "a load is not an undo step");
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void aTruncatedLoadKeepsNoBaselineCopyAndIsNeverDirty() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setTruncatedLoad(true);
                buffer.setReadOnly(true);
                buffer.setInitialContent("first slice of a huge file\n");
                assertNull(field(buffer, "cleanText"));
                assertFalse(buffer.isDirty());

                buffer.setTruncatedLoad(false); // a reload of a file that is no longer huge...
                buffer.setReadOnly(false);
                buffer.convertLineEndings(true);
                buffer.convertLineEndings(false); // back to the file's own: re-evaluates the dirty flag
                assertFalse(buffer.isDirty(), "...is not dirty for a moment before its text arrives");
                buffer.setInitialContent("whole file\n");
                assertEquals("whole file\n", field(buffer, "cleanText"));
                buffer.getArea().appendText("x");
                assertTrue(buffer.isDirty());
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void aLongFileRestoresItsFoldsWithoutScanningOnTheFxThread() throws Exception {
        EditorBuffer[] holder = new EditorBuffer[1];
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            holder[0] = buffer;
            buffer.setPath(java.nio.file.Path.of("Long.java"));
            buffer.setInitialContent(LONG_SOURCE);
            buffer.getFoldManager().recompute();
            assertTrue(buffer.getFoldManager().regionStartingAt(1).isPresent(), "the fixture has a block at line 1");
            buffer.setInitialContent(LONG_SOURCE + "\n"); // a fresh load: the regions above are for the old text
            buffer.getArea().moveTo(0);

            buffer.getFoldManager().restore(List.of(), List.of(1));
            assertFalse(buffer.getFoldManager().isCollapsed(1), "the restore did not detect and fold synchronously");
        });
        EditorBuffer buffer = holder[0];
        try {
            await(() -> buffer.getFoldManager().isCollapsed(1));
            runOnFx(() -> {
                assertEquals(0, buffer.getArea().getCaretPosition(), "folding later must not move the caret");
                assertEquals(List.of(1), buffer.getFoldManager().collapsedStartLines());
            });
        } finally {
            runOnFx(buffer::dispose);
        }
    }

    @Test
    void savedFoldsOfALongFileAreReportedBeforeTheirRegionsArrive() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setPath(java.nio.file.Path.of("Long.java"));
                buffer.setInitialContent(LONG_SOURCE);
                buffer.getFoldManager().restore(List.of(), List.of(1));
                // What a session save in this instant persists: the saved fold, not an empty list.
                assertEquals(List.of(1), buffer.getFoldManager().collapsedStartLines());
                assertTrue(buffer.getFoldManager().isCollapsed(1));
            } finally {
                buffer.dispose();
            }
        });
    }

    @Test
    void anEditBeforeTheRegionsArriveMovesThePendingFoldWithItsHeader() throws Exception {
        EditorBuffer[] holder = new EditorBuffer[1];
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            holder[0] = buffer;
            buffer.setPath(java.nio.file.Path.of("Long.java"));
            buffer.setInitialContent(LONG_SOURCE);
            buffer.getFoldManager().restore(List.of(), List.of(1));
            buffer.getArea().insertText(0, "// a new first line\n// and a second\n");
            buffer.getArea().moveTo(0);
        });
        EditorBuffer buffer = holder[0];
        try {
            await(() -> buffer.getFoldManager().isCollapsed(3));
            runOnFx(() -> assertEquals(List.of(3), buffer.getFoldManager().collapsedStartLines()));
        } finally {
            runOnFx(buffer::dispose);
        }
    }

    @Test
    void aShortFileStillRestoresSynchronously() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setPath(java.nio.file.Path.of("Short.java"));
                buffer.setInitialContent("class A {\n    void one() {\n        int x;\n    }\n}\n");
                buffer.getFoldManager().restore(List.of(), List.of(1));
                assertTrue(buffer.getFoldManager().isCollapsed(1));
                assertFalse(buffer.getFoldManager().regions().isEmpty());
            } finally {
                buffer.dispose();
            }
        });
    }

    private static String longSource() {
        StringBuilder sb = new StringBuilder("class Long {\n    void first() {\n        int x = 1;\n    }\n");
        int i = 0;
        while (sb.length() < FoldManager.DEFERRED_RESTORE_CHARS + 1024) {
            sb.append("    int field").append(i++).append(" = 0;\n");
        }
        return sb.append("}\n").toString();
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Polls {@code condition} on the FX thread until it holds; the background detection posts back there. */
    private static void await(Supplier<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        boolean[] met = {false};
        while (!met[0]) {
            assertTrue(System.nanoTime() < deadline, "condition never held");
            runOnFx(() -> met[0] = condition.get());
            if (!met[0]) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
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
