package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.fxmisc.richtext.model.PlainTextChange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class LineMarkTrackerTest {

    /**
     * With no marks there is nothing to carry through a batch of changes, so none of it is resolved: not the
     * document (no area is even needed here), and not the per-replacement settling that is quadratic in the
     * size of the batch — a formatter's or a multi-caret edit's thousands of replacements in a buffer
     * without bookmarks or breakpoints, which is nearly every buffer.
     */
    @Test
    void aBatchInABufferWithoutMarksIsNotSettled() {
        List<PlainTextChange> batch = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            batch.add(new PlainTextChange(i * 10, "old\n", "new line\nand another\n"));
        }
        NavigableMap<Integer, Object> none = new TreeMap<>();

        LineMarkTracker.Result<Object> result = LineMarkTracker.apply(none, null, batch, null);

        assertSame(none, result.marks());
        assertFalse(result.moved());
        assertFalse(result.retexted());
    }
}
