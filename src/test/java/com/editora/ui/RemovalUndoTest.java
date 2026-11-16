package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RemovalUndo} by itself: the newest removal comes back first, twenty are kept, a restore that answers false
 * or throws is used up and said, an empty list says so, and closing the window empties it.
 */
class RemovalUndoTest {

    @BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en");
    }

    private final List<String> said = new ArrayList<>();
    private final List<String> restored = new ArrayList<>();
    private final RemovalUndo undo = new RemovalUndo(said::add);

    private String last() {
        return said.getLast();
    }

    private void remove(String name) {
        undo.removed(name + " removed.", name + " restored.", () -> restored.add(name));
    }

    @Test
    void aRemovalSaysItsSentenceThenHowToTakeItBack() {
        remove("Watch “total”");
        assertEquals("Watch “total” removed. Undo Last Removal takes this back.", last());
        assertEquals(1, undo.size());
    }

    @Test
    void theHintNamesTheKeyWhenTheCommandHasOne() {
        KeymapManager before = TextInputKeymap.sharedKeymap();
        KeymapManager keymap = new KeymapManager();
        keymap.loadNamed("cua");
        keymap.applyOverrides(java.util.Map.of("C-M-S-z", RemovalUndo.COMMAND));
        TextInputKeymap.setShared(keymap);
        try {
            assertEquals(
                    "Undo Last Removal (" + keymap.displayChord(RemovalUndo.COMMAND) + ") takes this back.",
                    RemovalUndo.hint());
        } finally {
            TextInputKeymap.setShared(before);
        }
    }

    @Test
    void theNewestRemovalComesBackFirst() {
        remove("A");
        remove("B");
        undo.undoLast();
        assertEquals(List.of("B"), restored);
        assertEquals("B restored.", last());
        undo.undoLast();
        assertEquals(List.of("B", "A"), restored);
        assertEquals("A restored.", last());
        assertEquals(0, undo.size());
    }

    @Test
    void anEmptyListSaysThereIsNothingToUndo() {
        undo.undoLast();
        assertEquals("No removal to undo.", last());
        remove("A");
        undo.undoLast();
        undo.undoLast();
        assertEquals("No removal to undo.", last(), "a removal is taken back once");
        assertEquals(List.of("A"), restored);
    }

    @Test
    void onlyTheTwentyNewestAreKept() {
        for (int i = 1; i <= RemovalUndo.LIMIT + 5; i++) {
            remove("R" + i);
        }
        assertEquals(RemovalUndo.LIMIT, undo.size());
        for (int i = 0; i < RemovalUndo.LIMIT; i++) {
            undo.undoLast();
        }
        assertEquals("R25", restored.getFirst(), "newest first");
        assertEquals("R6", restored.getLast(), "the five oldest fell off");
        undo.undoLast();
        assertEquals("No removal to undo.", last());
        assertEquals(RemovalUndo.LIMIT, restored.size());
    }

    @Test
    void aRestoreThatAnswersFalseIsSaidAndUsedUp() {
        remove("A");
        undo.removed("Watch “total” removed.", "Watch “total” restored.", () -> false);
        undo.undoLast();
        assertEquals("This can no longer be undone: Watch “total” removed.", last());
        assertEquals(MessageLog.Severity.WARN, StatusSeverity.of(last()), "said as a warning, not as plain news");
        assertEquals(1, undo.size(), "the entry is dropped, and the one under it is next");
        undo.undoLast();
        assertEquals("A restored.", last());
    }

    @Test
    void aRestoreThatThrowsIsSaidAsOneThatCouldNot() {
        undo.removed("Pin removed.", "Pin restored.", () -> {
            throw new IllegalStateException("its buffer is gone");
        });
        undo.undoLast();
        assertEquals("This can no longer be undone: Pin removed.", last());
        assertEquals(0, undo.size());
    }

    @Test
    void closingTheWindowForgetsEveryRemoval() {
        remove("A");
        remove("B");
        undo.clear();
        assertEquals(0, undo.size());
        undo.undoLast();
        assertEquals("No removal to undo.", last());
        assertEquals(List.of(), restored);
    }
}
