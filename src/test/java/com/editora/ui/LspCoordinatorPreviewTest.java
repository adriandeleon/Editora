package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.lsp.LspManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #471: Find References must split each file's content <em>once</em>, not once per reference —
 * 500 references into one open file did 500 whole-document splits in a single FX pulse.
 */
class LspCoordinatorPreviewTest {

    @Test
    void previewLinesSplitsEachFileAtMostOnce() {
        Path a = Path.of("/proj/A.java");
        Path b = Path.of("/proj/B.java");
        Path closed = Path.of("/proj/Closed.java");
        List<LspManager.Target> targets = List.of(
                new LspManager.Target(a, 0, 0),
                new LspManager.Target(a, 2, 4),
                new LspManager.Target(b, 1, 0),
                new LspManager.Target(a, 0, 9), // a again — must NOT re-split A
                new LspManager.Target(closed, 3, 0)); // no open buffer

        AtomicInteger calls = new AtomicInteger();
        List<String> previews = LspCoordinator.previewLines(targets, f -> {
            calls.incrementAndGet();
            if (f.equals(a)) {
                return "  alpha\nbeta\ngamma  ";
            }
            if (f.equals(b)) {
                return "one\n  two\n";
            }
            return null; // closed file → no content
        });

        assertEquals(3, calls.get(), "content fetched once per distinct file (A, B, Closed), not once per target");
        assertEquals(List.of("alpha", "gamma", "two", "alpha", ""), previews);
    }
}
