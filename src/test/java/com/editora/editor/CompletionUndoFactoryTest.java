package com.editora.editor;

import java.time.Duration;
import java.util.*;

import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.undo.UndoManager;
import org.fxmisc.undo.impl.MultiChangeUndoManagerImpl;
import org.junit.jupiter.api.Test;
import org.reactfx.EventSource;

import static org.junit.jupiter.api.Assertions.*;

class CompletionUndoFactoryTest {
    private static final class History {
        final String original = "// heading\nclass A { Arr }";
        String text = original;
        final EventSource<List<PlainTextChange>> changes = new EventSource<>();
        final CompletionUndoFactory.RebasableQueue<List<PlainTextChange>> queue;
        final UndoManager<List<PlainTextChange>> manager;

        History(int capacity) {
            queue = new CompletionUndoFactory.RebasableQueue<>(capacity);
            manager = new MultiChangeUndoManagerImpl<>(
                    queue,
                    PlainTextChange::invert,
                    this::apply,
                    PlainTextChange::mergeWith,
                    PlainTextChange::isIdentity,
                    changes,
                    Duration.ZERO);
        }

        void apply(List<PlainTextChange> batch) {
            for (var change : batch) {
                assertEquals(change.getRemoved(), text.substring(change.getPosition(), change.getRemovalEnd()));
                text = text.substring(0, change.getPosition())
                        + change.getInserted()
                        + text.substring(change.getRemovalEnd());
            }
            changes.push(batch);
        }

        void change(int at, String removed, String inserted) {
            manager.preventMerge();
            apply(List.of(new PlainTextChange(at, removed, inserted)));
        }

        List<PlainTextChange> complete() {
            change(text.indexOf("Arr"), "Arr", "ArrayList");
            return manager.getNextUndo();
        }

        void imports(List<PlainTextChange> target) {
            queue.target(target);
            change(text.indexOf("class"), "", "import java.util.ArrayList;\n");
        }
    }

    @Test
    void rebasePreservesTheOriginalMarkAndInvalidatesRewrittenPositions() {
        var h = new History(20);
        h.manager.mark();
        var completion = h.complete();
        var oldPosition = h.manager.getCurrentPosition();
        h.change(h.text.indexOf(" }"), "", " values");
        h.imports(completion);
        String end = h.text;
        assertFalse(oldPosition.isValid());
        assertFalse(h.manager.isAtMarkedPosition());
        assertTrue(h.manager.undo());
        assertTrue(h.text.contains("import java.util.ArrayList;"));
        assertFalse(h.text.contains("values"));
        assertTrue(h.manager.undo());
        assertEquals(h.original, h.text);
        assertTrue(h.manager.isAtMarkedPosition());
        assertTrue(h.manager.redo());
        assertTrue(h.manager.redo());
        assertEquals(end, h.text);
        h.manager.close();
    }

    @Test
    void trimmedOrOverlappingHistoryUsesNormalUndo() {
        var h = new History(2);
        var completion = h.complete();
        h.change(h.text.indexOf(" }"), "", " x");
        h.change(h.text.indexOf(" }"), "", " y");
        String beforeImport = h.text;
        h.imports(completion);
        h.manager.undo();
        assertEquals(beforeImport, h.text);
        h.manager.close();
        h = new History(20);
        completion = h.complete();
        h.change(0, "// heading", "// edited heading");
        beforeImport = h.text;
        h.queue.target(completion);
        h.change(0, "// edited heading\n", "import java.util.ArrayList;\n");
        h.manager.undo();
        assertEquals(beforeImport, h.text);
        h.manager.close();
    }

    @Test
    void importsAfterUndoCreateAnOrdinaryBranchAndDoNotResurrectRedo() {
        var h = new History(20);
        var completion = h.complete();
        h.change(h.text.indexOf(" }"), "", " values");
        h.manager.undo();
        String beforeImport = h.text;
        assertTrue(h.manager.isRedoAvailable());
        h.imports(completion);
        assertFalse(h.manager.isRedoAvailable());
        h.manager.undo();
        assertEquals(beforeImport, h.text);
        h.manager.close();
    }

    @Test
    void aNewEditAfterRebasedUndoDiscardsOnlyTheAbandonedFuture() {
        var h = new History(20);
        var completion = h.complete();
        h.change(h.text.indexOf(" }"), "", " values");
        h.imports(completion);
        h.manager.undo();
        h.change(h.text.indexOf(" }"), "", " different");
        assertFalse(h.manager.isRedoAvailable());
        h.manager.undo();
        h.manager.undo();
        assertEquals(h.original, h.text);
        h.manager.close();
    }
}
