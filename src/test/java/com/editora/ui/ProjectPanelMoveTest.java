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

    /**
     * An absolute path on every platform. {@code /proj/a.txt} is absolute on Unix; on Windows a leading slash
     * alone names no drive, and the code under test works with (and returns) absolute paths.
     */
    private static Path p(String path) {
        return Path.of(path).toAbsolutePath();
    }

    @Test
    void movingAFileIntoADifferentFolderIsAllowed() {
        assertTrue(ProjectPanel.canMoveInto(p("/proj/a.txt"), p("/proj/sub")));
        assertTrue(ProjectPanel.canMoveInto(p("/proj/sub/a.txt"), p("/proj")));
    }

    @Test
    void movingIntoTheCurrentParentIsANoOp() {
        assertFalse(ProjectPanel.canMoveInto(p("/proj/a.txt"), p("/proj")));
        assertFalse(ProjectPanel.canMoveInto(p("/proj/sub/a.txt"), p("/proj/sub")));
    }

    @Test
    void aFolderCannotMoveIntoItselfOrItsSubtree() {
        assertFalse(ProjectPanel.canMoveInto(p("/proj/dir"), p("/proj/dir")));
        assertFalse(ProjectPanel.canMoveInto(p("/proj/dir"), p("/proj/dir/sub")));
        assertFalse(ProjectPanel.canMoveInto(p("/proj/dir"), p("/proj/dir/sub/deep")));
        // A sibling folder is fine.
        assertTrue(ProjectPanel.canMoveInto(p("/proj/dir"), p("/proj/other")));
    }

    @Test
    void nullsAreRejected() {
        assertFalse(ProjectPanel.canMoveInto(null, p("/proj")));
        assertFalse(ProjectPanel.canMoveInto(p("/proj/a.txt"), null));
    }

    @Test
    void canDropIntoIsTrueWhenAnySourceCanMove() {
        Path target = p("/proj/sub");
        // b.txt is already in the target (no-op), but a.txt can move → the drop is accepted.
        assertTrue(ProjectPanel.canDropInto(List.of(p("/proj/a.txt"), p("/proj/sub/b.txt")), target));
        // Both already in the target → nothing to do.
        assertFalse(ProjectPanel.canDropInto(List.of(p("/proj/sub/b.txt"), p("/proj/sub/c.txt")), target));
        assertFalse(ProjectPanel.canDropInto(List.of(), target));
    }

    @Test
    void aFolderDraggedTogetherWithSomethingInsideItMovesOnce() {
        // The folder carries its contents along, so the nested entries must not be moved a second time —
        // TreeView hands the selection back in row order, so the parent goes first and the child's own
        // move would then fail on a path that no longer exists (a NoSuchFileException → error dialog).
        List<Path> pruned = ProjectPanel.pruneNestedSources(
                List.of(p("/proj/dir"), p("/proj/dir/a.txt"), p("/proj/dir/sub/b.txt")));
        assertEquals(List.of(p("/proj/dir")), pruned);

        // Order-independent: the child listed first is still dropped.
        assertEquals(
                List.of(p("/proj/dir")),
                ProjectPanel.pruneNestedSources(List.of(p("/proj/dir/a.txt"), p("/proj/dir"))));
    }

    @Test
    void unrelatedSourcesAreAllKept() {
        List<Path> sources = List.of(p("/proj/a.txt"), p("/proj/dir"), p("/proj/other/b.txt"));
        assertEquals(sources, ProjectPanel.pruneNestedSources(sources));
        // A sibling whose name merely prefixes another's isn't "nested" (dir2 is not under dir).
        List<Path> siblings = List.of(p("/proj/dir"), p("/proj/dir2"));
        assertEquals(siblings, ProjectPanel.pruneNestedSources(siblings));
    }

    @Test
    void pruningIsNullSafeAndTrivialForASingleSource() {
        assertEquals(List.of(), ProjectPanel.pruneNestedSources(null));
        assertEquals(List.of(p("/proj/a.txt")), ProjectPanel.pruneNestedSources(List.of(p("/proj/a.txt"))));
    }

    /** A single file moves at once; a folder or several items are confirmed first. */
    @Test
    void onlyAFolderOrAMultiSelectionNeedsConfirmation(@org.junit.jupiter.api.io.TempDir Path dir)
            throws java.io.IOException {
        Path file = java.nio.file.Files.writeString(dir.resolve("a.txt"), "a");
        Path other = java.nio.file.Files.writeString(dir.resolve("b.txt"), "b");
        Path folder = java.nio.file.Files.createDirectory(dir.resolve("pkg"));

        assertFalse(ProjectPanel.moveNeedsConfirmation(List.of(file)));
        assertTrue(ProjectPanel.moveNeedsConfirmation(List.of(folder)));
        assertTrue(ProjectPanel.moveNeedsConfirmation(List.of(file, other)));
        assertFalse(ProjectPanel.moveNeedsConfirmation(List.of()));
        assertFalse(ProjectPanel.moveNeedsConfirmation(null));
    }
}
