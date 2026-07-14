package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void aFolderDraggedTogetherWithSomethingInsideItMovesOnce() {
        // The folder carries its contents along, so the nested entries must not be moved a second time —
        // TreeView hands the selection back in row order, so the parent goes first and the child's own
        // move would then fail on a path that no longer exists (a NoSuchFileException → error dialog).
        List<Path> pruned = ProjectPanel.pruneNestedSources(
                List.of(Path.of("/proj/dir"), Path.of("/proj/dir/a.txt"), Path.of("/proj/dir/sub/b.txt")));
        assertEquals(List.of(Path.of("/proj/dir")), pruned);

        // Order-independent: the child listed first is still dropped.
        assertEquals(
                List.of(Path.of("/proj/dir")),
                ProjectPanel.pruneNestedSources(List.of(Path.of("/proj/dir/a.txt"), Path.of("/proj/dir"))));
    }

    @Test
    void unrelatedSourcesAreAllKept() {
        List<Path> sources = List.of(Path.of("/proj/a.txt"), Path.of("/proj/dir"), Path.of("/proj/other/b.txt"));
        assertEquals(sources, ProjectPanel.pruneNestedSources(sources));
        // A sibling whose name merely prefixes another's isn't "nested" (dir2 is not under dir).
        List<Path> siblings = List.of(Path.of("/proj/dir"), Path.of("/proj/dir2"));
        assertEquals(siblings, ProjectPanel.pruneNestedSources(siblings));
    }

    @Test
    void pruningIsNullSafeAndTrivialForASingleSource() {
        assertEquals(List.of(), ProjectPanel.pruneNestedSources(null));
        assertEquals(List.of(Path.of("/proj/a.txt")), ProjectPanel.pruneNestedSources(List.of(Path.of("/proj/a.txt"))));
    }
}
