package com.editora.editor;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import javafx.stage.Stage;

import com.editora.editor.http.HttpLineResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request line scan of the text before an edit must not land after it: its request lines are the old ones, so a
 * line added above the requests made every result read as edited and they all went.
 */
@Tag("fx")
class HttpResultsStaleRunScanFxTest {

    private static final String TEXT = "### one\nGET http://x/a\n\n### two\nGET http://x/b\n";

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static HttpLineResult result(int line, String anchor) {
        return new HttpLineResult(
                line,
                anchor,
                List.of(new HttpLineResult.Segment("200 OK", "http-result-ok")),
                "http-result-ok",
                "Ran at 10:00:00",
                null);
    }

    private static List<Integer> lines(EditorBuffer b) {
        return b.httpResults().stream().map(HttpLineResult::line).toList();
    }

    @Test
    void aScanOfTheTextBeforeAnEditDoesNotDropTheResultsTheEditMoved() throws Exception {
        EditorBuffer b = EditorFx.callFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setDisplayName("api.http");
            created.setLanguageOverride("http");
            created.setContent(TEXT);
            created.setHttpEnabled(true);
            created.settleRunScan();
            return created;
        });
        Stage stage = EditorFx.callFx(() -> EditorFx.show(b, 900, 500));
        try {
            EditorFx.onFx(() -> b.setHttpResults(List.of(result(1, "GET http://x/a"), result(4, "GET http://x/b"))));
            assertEquals(List.of(1, 4), EditorFx.callFx(() -> lines(b)));

            // A settled pass's scan is on the pool when a line is added above the requests: the results move at
            // once, and the scan of the old text lands after the edit.
            int workers = ((ThreadPoolExecutor) EditorBuffer.HIGHLIGHT_POOL).getMaximumPoolSize();
            Runnable release = EditorFx.holdPool(EditorBuffer.HIGHLIGHT_POOL, workers);
            long gen;
            try {
                gen = EditorFx.callFx(() -> {
                    b.runGutter.recomputeRun(true);
                    b.getArea().insertText(0, "# note\n");
                    return b.runGutter.runScanGen;
                });
                assertEquals(List.of(2, 5), EditorFx.callFx(() -> lines(b)), "moved with the edit");
            } finally {
                release.run();
            }

            // The edit's own settled pass scans the new text; once it has landed the results are where it moved them.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (!EditorFx.callFx(
                    () -> b.runGutter.runScanGen > gen && !EditorFx.<Boolean>field(b.runGutter, "runScanInFlight"))) {
                assertTrue(System.nanoTime() < deadline, "the edit's scan never landed");
                Thread.sleep(10);
            }
            EditorFx.drain();
            assertEquals(List.of(2, 5), EditorFx.callFx(() -> lines(b)), "the old text's scan dropped nothing");
        } finally {
            EditorFx.onFx(() -> {
                stage.hide();
                b.dispose();
            });
        }
    }
}
