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

    /**
     * A typing pause takes the document in O(1) on the FX thread and the highlight pass builds its text on the
     * pool (perf review 2026-11-23 E2); a consumer on the FX thread afterwards reuses that String.
     */
    @Test
    void aHighlightPassBuildsTheTextOffTheFxThreadAndTheFxThreadReusesIt() throws Exception {
        EditorBuffer[] holder = new EditorBuffer[1];
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            holder[0] = buffer;
            buffer.setPath(java.nio.file.Path.of("/tmp/snap/A.java").toAbsolutePath());
            buffer.setInitialContent("class A {\n  void one() {}\n}\n");
        });
        EditorBuffer buffer = holder[0];
        try {
            awaitOnFx(() -> !buffer.settlingForTest());
            long[] before = new long[1];
            runOnFx(() -> {
                buffer.getArea().appendText("// edit\n");
                before[0] = buffer.documentSnapshotMaterializations();
                long onFx = buffer.documentSnapshotFxMaterializations();
                buffer.highlighter.applyHighlighting();
                // The FX-thread count: the pass's worker may build the String before the next statement here
                // (a CI flake read the any-thread count as one too many).
                assertEquals(onFx, buffer.documentSnapshotFxMaterializations(), "nothing built on the FX thread");
            });
            awaitOnFx(() -> !buffer.settlingForTest());
            runOnFx(() -> {
                // The pass, the pause's own pass, the Run scan, the spell code lines and the Undo History
                // checkpoint (on the FX thread) all read this version: its text was built once.
                assertEquals(before[0] + 1, buffer.documentSnapshotMaterializations(), "built once, and reused");
            });
        } finally {
            runOnFx(buffer::dispose);
        }
    }

    /**
     * Regression for a CI flake ("nothing built on the FX thread" expected 1, was 2): the pass's worker built the
     * text while the FX thread was still between dispatching the pass and counting, and the count did not say on
     * which thread a String was built. Here the FX thread waits for the worker's build before it counts.
     */
    @Test
    void aBuildOnThePassThreadIsNotCountedAsOneOnTheFxThread() throws Exception {
        EditorBuffer[] holder = new EditorBuffer[1];
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            holder[0] = buffer;
            buffer.setPath(java.nio.file.Path.of("/tmp/snap/B.java").toAbsolutePath());
            buffer.setInitialContent("class B {\n  void one() {}\n}\n");
        });
        EditorBuffer buffer = holder[0];
        try {
            awaitOnFx(() -> !buffer.settlingForTest());
            runOnFx(() -> {
                buffer.getArea().appendText("// edit\n");
                long all = buffer.documentSnapshotMaterializations();
                long onFx = buffer.documentSnapshotFxMaterializations();
                buffer.highlighter.applyHighlighting();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                while (buffer.documentSnapshotMaterializations() == all && System.nanoTime() < deadline) {
                    Thread.onSpinWait(); // the worker builds the text meanwhile
                }
                assertEquals(all + 1, buffer.documentSnapshotMaterializations(), "the pass's thread built it");
                assertEquals(onFx, buffer.documentSnapshotFxMaterializations(), "nothing built on the FX thread");
            });
            awaitOnFx(() -> !buffer.settlingForTest());
        } finally {
            runOnFx(buffer::dispose);
        }
    }

    /** A pause in a CSV buffer restyles the records an edit changed, not the whole document (E4). */
    @Test
    void aCsvPauseRestylesOnlyWhatTheEditChanged() throws Exception {
        String rows = "name,qty,price\n" + "apple,1,2.5\n".repeat(2_000);
        EditorBuffer[] holder = new EditorBuffer[1];
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            holder[0] = buffer;
            buffer.setPath(java.nio.file.Path.of("/tmp/snap/rows.csv").toAbsolutePath());
            buffer.setCsvRainbowEnabled(true);
            buffer.setInitialContent(rows);
        });
        EditorBuffer buffer = holder[0];
        try {
            awaitOnFx(() -> !buffer.settlingForTest() && buffer.highlighter.csvRestyledForTest >= 0);
            runOnFx(() -> assertEquals(
                    styles(
                            rows,
                            com.editora.editor.highlight.CsvRainbow.buildSpans(
                                    rows, ',', com.editora.editor.highlight.CsvRainbow.COLORS)),
                    styles(rows, buffer.getArea().getStyleSpans(0, rows.length())),
                    "coloured whole once"));
            int at = rows.indexOf("apple", rows.length() / 2) + 2;
            runOnFx(() -> {
                buffer.getArea().insertText(at, ",x"); // a new column in one record
            });
            awaitOnFx(() -> !buffer.settlingForTest());
            runOnFx(() -> {
                int restyled = buffer.highlighter.csvRestyledForTest;
                org.junit.jupiter.api.Assertions.assertTrue(
                        restyled > 0 && restyled < 20, "only that record's tail: " + restyled);
                String text = buffer.getArea().getText();
                assertEquals(
                        styles(
                                text,
                                com.editora.editor.highlight.CsvRainbow.buildSpans(
                                        text,
                                        com.editora.csv.CsvParser.detectDelimiter(text),
                                        com.editora.editor.highlight.CsvRainbow.COLORS)),
                        styles(text, buffer.getArea().getStyleSpans(0, text.length())),
                        "the document shows the colours a whole restyle would");
            });
        } finally {
            runOnFx(buffer::dispose);
        }
    }

    /** Each character's style, line breaks left out (the area gives a break its line's last style). */
    private static java.util.List<Object> styles(
            String text, org.fxmisc.richtext.model.StyleSpans<java.util.Collection<String>> spans) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        int pos = 0;
        for (var span : spans) {
            for (int i = 0; i < span.getLength(); i++, pos++) {
                if (text.charAt(pos) != '\n') {
                    out.add(span.getStyle());
                }
            }
        }
        return out;
    }

    private static void awaitOnFx(java.util.function.BooleanSupplier condition) throws Exception {
        com.editora.testkit.Await.until(
                () -> {
                    boolean[] ok = {false};
                    try {
                        runOnFx(() -> ok[0] = condition.getAsBoolean());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return ok[0];
                },
                "the buffer settled");
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
