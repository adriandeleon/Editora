package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The server's spans becoming positioned editor hints (#824).
 *
 * <p>This replaced a per-line aggregation test: hints used to be joined into one end-of-line string, so a
 * line's labels lost their columns on the way to the renderer. They now carry their own column through, and
 * what matters is that the column survives untouched — the renderer places by it.
 */
class InlayHintPositionTest {

    @Test
    void everyHintKeepsItsOwnLineAndColumn() {
        var spans = List.of(
                new LspManager.InlayHintSpan(2, 20, "b:"),
                new LspManager.InlayHintSpan(2, 10, "a:"),
                new LspManager.InlayHintSpan(5, 0, ": String"));
        List<EditorBuffer.InlayHint> out = LspCoordinator.toInlayHints(spans);

        assertEquals(3, out.size(), "no hint is merged away");
        assertEquals(new EditorBuffer.InlayHint(2, 10, "a:"), out.get(0));
        assertEquals(new EditorBuffer.InlayHint(2, 20, "b:"), out.get(1));
        assertEquals(new EditorBuffer.InlayHint(5, 0, ": String"), out.get(2));
    }

    @Test
    void hintsAreOrderedByLineThenColumn() {
        var spans = List.of(
                new LspManager.InlayHintSpan(5, 0, "late"),
                new LspManager.InlayHintSpan(2, 20, "second"),
                new LspManager.InlayHintSpan(2, 10, "first"));
        List<EditorBuffer.InlayHint> out = LspCoordinator.toInlayHints(spans);
        assertEquals(
                List.of("first", "second", "late"),
                out.stream().map(EditorBuffer.InlayHint::label).toList());
    }

    @Test
    void twoHintsAtTheSameColumnAreBothKept() {
        // Nothing collapses them any more; the renderer places both, in order.
        var spans = List.of(new LspManager.InlayHintSpan(1, 4, "x:"), new LspManager.InlayHintSpan(1, 4, "y:"));
        assertEquals(2, LspCoordinator.toInlayHints(spans).size());
    }

    @Test
    void emptyInputYieldsNoHints() {
        assertEquals(List.of(), LspCoordinator.toInlayHints(List.of()));
    }
}
