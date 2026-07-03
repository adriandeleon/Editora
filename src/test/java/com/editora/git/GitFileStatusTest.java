package com.editora.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static com.editora.git.GitStatus.FileEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitFileStatusTest {

    @Test
    void classifiesPorcelainLetters() {
        assertEquals(GitFileStatus.UNTRACKED, GitFileStatus.of(new FileEntry("new.txt", '?', '?', null)));
        assertEquals(GitFileStatus.ADDED, GitFileStatus.of(new FileEntry("a.txt", 'A', '.', null)));
        assertEquals(GitFileStatus.MODIFIED, GitFileStatus.of(new FileEntry("b.txt", '.', 'M', null)));
        assertEquals(GitFileStatus.MODIFIED, GitFileStatus.of(new FileEntry("b.txt", 'M', 'M', null)));
        assertEquals(GitFileStatus.DELETED, GitFileStatus.of(new FileEntry("c.txt", 'D', '.', null)));
        assertEquals(GitFileStatus.RENAMED, GitFileStatus.of(new FileEntry("d.txt", 'R', '.', "old.txt")));
    }

    @Test
    void deletedAndAddedTakePrecedenceOverModified() {
        // A file staged-added but also worktree-deleted → deletion wins (it's gone).
        assertEquals(GitFileStatus.DELETED, GitFileStatus.of(new FileEntry("x", 'A', 'D', null)));
        // Staged-add + worktree-modified → still "added".
        assertEquals(GitFileStatus.ADDED, GitFileStatus.of(new FileEntry("y", 'A', 'M', null)));
    }

    @Test
    void byPathResolvesRelativeToRootAsAbsolute() {
        Path root = Path.of("/repo").toAbsolutePath();
        GitStatus status = new GitStatus(
                true,
                "main",
                "",
                0,
                0,
                List.of(new FileEntry("src/App.java", 'M', '.', null), new FileEntry("todo.txt", '?', '?', null)));
        Map<Path, GitFileStatus> byPath = GitFileStatus.byPath(status, root);
        assertEquals(
                GitFileStatus.MODIFIED,
                byPath.get(root.resolve("src/App.java").toAbsolutePath().normalize()));
        assertEquals(
                GitFileStatus.UNTRACKED,
                byPath.get(root.resolve("todo.txt").toAbsolutePath().normalize()));
        assertTrue(GitFileStatus.byPath(GitStatus.NOT_A_REPO, root).isEmpty());
        assertTrue(GitFileStatus.byPath(status, null).isEmpty());
    }
}
