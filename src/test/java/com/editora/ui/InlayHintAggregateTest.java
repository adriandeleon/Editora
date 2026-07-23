package com.editora.ui;

import java.util.List;
import java.util.Map;

import com.editora.lsp.LspManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The per-line end-of-line aggregation behind the inlay-hints overlay (#681). */
class InlayHintAggregateTest {

    @Test
    void hintsGroupPerLineInColumnOrder() {
        var spans = List.of(
                new LspManager.InlayHintSpan(2, 20, "b:"),
                new LspManager.InlayHintSpan(2, 10, "a:"),
                new LspManager.InlayHintSpan(5, 0, ": String"));
        Map<Integer, String> out = LspCoordinator.aggregateInlayHints(spans);
        assertEquals("a:  b:", out.get(2), "same-line hints join in column order");
        assertEquals(": String", out.get(5));
        assertEquals(2, out.size());
    }

    @Test
    void emptyInputAggregatesToEmpty() {
        assertEquals(Map.of(), LspCoordinator.aggregateInlayHints(List.of()));
    }
}
