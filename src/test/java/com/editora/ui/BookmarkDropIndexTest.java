package com.editora.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The drag-and-drop insertion math: {@link BookmarksPanel#insertIndex} computes the index to re-insert
 * a dragged row so it lands above/below the target after the row is first removed.
 */
class BookmarkDropIndexTest {

    /** Mirrors the controller's move: remove {@code from}, insert at {@code insertIndex(...)}. */
    private static List<String> applyDrop(List<String> items, int from, int target, boolean after) {
        List<String> list = new ArrayList<>(items);
        int to = BookmarksPanel.insertIndex(from, target, after);
        list.add(to, list.remove(from));
        return list;
    }

    @Test
    void dropBelowAndAbovePlacesRowOnTheRightSideOfTheTarget() {
        List<String> items = List.of("A", "B", "C", "D");
        // Drag A below B → B, A, C, D
        assertEquals(List.of("B", "A", "C", "D"), applyDrop(items, 0, 1, true));
        // Drag A above C → B, A, C, D
        assertEquals(List.of("B", "A", "C", "D"), applyDrop(items, 0, 2, false));
        // Drag D above B → A, D, B, C
        assertEquals(List.of("A", "D", "B", "C"), applyDrop(items, 3, 1, false));
        // Drag D below B → A, B, D, C
        assertEquals(List.of("A", "B", "D", "C"), applyDrop(items, 3, 1, true));
    }

    @Test
    void dropBelowLastAppends() {
        assertEquals(List.of("B", "C", "A"), applyDrop(List.of("A", "B", "C"), 0, 2, true));
    }
}
