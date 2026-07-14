package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure validation for the Project tree's drag-to-move: {@link ProjectPanel#canMoveInto} rejects no-ops
 * (already in the target folder) and invalid moves (a folder into itself or its own subtree), and
 * {@link ProjectPanel#canDropInto} accepts a drop when at least one dragged path can move.
 */
class ProjectPanelMoveTest {

    @Test
    void movingAFileIntoADifferentFolderIsAllowed() {
        assertTrue(ProjectPanel.canMoveInto(Path.of("/proj/a.txt"), Path.of("/proj/sub")));
        assertTrue(ProjectPanel.canMoveInto(Path.of("/proj/sub/a.txt"), Path.of("/proj")));
    }

    @Test
    void movingIntoTheCurrentParentIsANoOp() {
        assertFalse(ProjectPanel.canMoveInto(Path.of("/proj/a.txt"), Path.of("/proj")));
        assertFalse(ProjectPanel.canMoveInto(Path.of("/proj/sub/a.txt"), Path.of("/proj/sub")));
    }

    @Test
    void aFolderCannotMoveIntoItselfOrItsSubtree() {
        assertFalse(ProjectPanel.canMoveInto(Path.of("/proj/dir"), Path.of("/proj/dir")));
        assertFalse(ProjectPanel.canMoveInto(Path.of("/proj/dir"), Path.of("/proj/dir/sub")));
        assertFalse(ProjectPanel.canMoveInto(Path.of("/proj/dir"), Path.of("/proj/dir/sub/deep")));
        // A sibling folder is fine.
        assertTrue(ProjectPanel.canMoveInto(Path.of("/proj/dir"), Path.of("/proj/other")));
    }

    @Test
    void nullsAreRejected() {
        assertFalse(ProjectPanel.canMoveInto(null, Path.of("/proj")));
        assertFalse(ProjectPanel.canMoveInto(Path.of("/proj/a.txt"), null));
    }

    @Test
    void canDropIntoIsTrueWhenAnySourceCanMove() {
        Path target = Path.of("/proj/sub");
        // b.txt is already in the target (no-op), but a.txt can move → the drop is accepted.
        assertTrue(ProjectPanel.canDropInto(List.of(Path.of("/proj/a.txt"), Path.of("/proj/sub/b.txt")), target));
        // Both already in the target → nothing to do.
        assertFalse(ProjectPanel.canDropInto(List.of(Path.of("/proj/sub/b.txt"), Path.of("/proj/sub/c.txt")), target));
        assertFalse(ProjectPanel.canDropInto(List.of(), target));
    }
}
