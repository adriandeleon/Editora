package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the large-file open freeze: setting the language on a large, fold-heavy buffer that
 * isn't laid out yet must not synchronously recreate a gutter graphic per fold region. Before the fix,
 * {@code FoldManager.recompute()} recreated every fold-start line's graphic (thousands of them) on the first
 * recompute, freezing the FX thread for seconds; now it only touches currently-visible rows.
 */
@Tag("fx")
class LargeFileOpenPerfFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void settingLanguageOnLargeFoldHeavyBufferIsFast() throws Exception {
        // ~16k lines with a fold region every ~3 lines → thousands of fold headers.
        StringBuilder sb = new StringBuilder(600_000);
        for (int i = 0; i < 4000; i++) {
            sb.append("class C").append(i).append(" {\n");
            sb.append("    int f() {\n");
            sb.append("        return ").append(i).append(";\n");
            sb.append("    }\n");
        }
        String text = sb.toString();

        long ms = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setContent(text);
            long s = System.nanoTime();
            buf.setLanguageOverride("java"); // first fold recompute over the whole document
            long elapsed = (System.nanoTime() - s) / 1_000_000;
            // setLanguageOverride also dispatched a full 16k-line background tokenize. Dispose so its
            // per-line cancel check fires: left running, that pass holds the shared java grammar monitor
            // for however long 16k lines take this machine — on a slow CI runner, minutes — and every
            // later java highlight in the suite queues behind it. That leak is what wedged the first test
            // to ever *wait* for a highlight (BracketColorsFxTest) while nothing here appeared to fail.
            buf.dispose();
            return elapsed;
        });

        System.out.println("setLanguageOverride on " + (text.length() / 1024) + " KB fold-heavy buffer: " + ms + " ms");
        // Pre-fix this was multiple seconds (one recreateParagraphGraphic per fold header). The visible-only
        // fix makes it a few ms; a generous bound catches a regression to O(folds) graphic recreation.
        assertTrue(ms < 2000, "setLanguageOverride took " + ms + " ms (expected < 2000 ms — fold-graphic regression?)");
    }

    @org.junit.jupiter.api.Test
    void aDisposedBufferDoesNotRedispatchHighlightingFromTheDebounce() throws Exception {
        // The subtler half of the leak this class caused: dispose() bumps the generations, which kills the
        // IN-FLIGHT pass — but the still-subscribed debounced edit pulse fires once more after the last
        // change, and used to dispatch a FRESH full tokenize whose generation was then current, so nothing
        // cancelled it. On this class's 16k-line buffer that ghost pass held the shared java grammar
        // monitor for minutes on a slow runner, wedging every later java highlight in the suite — and in
        // the app, closing a big tab right after an edit did the same. The mechanism is size-independent,
        // so a small buffer pins it fast.
        // A twin left open, edited in the same FX turn, starts the same debounce timers: once its debounced pass
        // has been dispatched, the disposed buffer's would have been too.
        EditorBuffer[] twin = new EditorBuffer[1];
        long[] twinGen = new long[1];
        EditorBuffer buf = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            EditorBuffer t = new EditorBuffer();
            b.setContent("class D { int x; }\n");
            t.setContent("class D { int x; }\n");
            b.setLanguageOverride("java");
            t.setLanguageOverride("java");
            b.dispose();
            twin[0] = t;
            twinGen[0] = highlightGen(t);
            return b;
        });
        long genAfterDispose = FxTestSupport.callOnFx(() -> highlightGen(buf));
        try {
            // applyHighlighting bumps highlightGen at dispatch, so an unchanged gen is exactly "no new pass was
            // dispatched".
            OnFx.until(
                    () -> settledSequenceDone(twin[0]) && highlightGen(twin[0]) > twinGen[0],
                    "the open twin's debounced highlighting pass");
        } finally {
            FxTestSupport.runOnFx(twin[0]::dispose);
        }
        long genLater = FxTestSupport.callOnFx(
                () -> FxTestSupport.<Long>field(FxTestSupport.field(buf, "highlighter"), "highlightGen"));
        org.junit.jupiter.api.Assertions.assertEquals(
                genAfterDispose, genLater, "the debounce pulse re-dispatched highlighting on a disposed buffer");
    }

    @Test
    void largeFileModeSkipsTheDebouncedWholeDocumentFoldScan() throws Exception {
        // A twin not in large-file mode, loaded in the same FX turn, runs FoldManager's succession debounce: once
        // its scan has found folds, the large file's would have run too.
        EditorBuffer[] twin = new EditorBuffer[1];
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setLargeFile(true); // the loader now applies this before the initial replacement
            b.setInitialContent("class A {\n  void f() {\n  }\n}\n".repeat(2000));
            EditorBuffer t = new EditorBuffer();
            t.setLanguageOverride("java");
            t.setInitialContent("class A {\n  void f() {\n  }\n}\n".repeat(2000));
            twin[0] = t;
            return b;
        });
        try {
            OnFx.until(
                    () -> !twin[0].getFoldManager().regions().isEmpty(),
                    "the twin's debounced whole-document fold scan");
            assertEquals(
                    0,
                    FxTestSupport.callOnFx(
                            () -> buffer.getFoldManager().regions().size()),
                    "large-file insertion must not trigger heuristic folding");
        } finally {
            FxTestSupport.runOnFx(() -> {
                buffer.dispose();
                twin[0].dispose();
            });
        }
    }

    /** Every settled-edit milestone of {@code b}'s last edit has fired (its debounce timer is not armed). */
    private static boolean settledSequenceDone(EditorBuffer b) {
        Object settled = FxTestSupport.field(b, "settledEdits");
        return FxTestSupport.<Long>field(settled, "armedMillis") == -1
                && FxTestSupport.<Long>field(settled, "cursorMillis") > 0;
    }

    private static long highlightGen(EditorBuffer b) {
        return FxTestSupport.<Long>field(FxTestSupport.field(b, "highlighter"), "highlightGen");
    }
}
