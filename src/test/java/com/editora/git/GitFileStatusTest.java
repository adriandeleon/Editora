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
    void letterMatchesTheCommitWindowConvention() {
        assertEquals("A", GitFileStatus.ADDED.letter());
        assertEquals("M", GitFileStatus.MODIFIED.letter());
        assertEquals("D", GitFileStatus.DELETED.letter());
        assertEquals("R", GitFileStatus.RENAMED.letter());
        assertEquals("U", GitFileStatus.UNTRACKED.letter());
    }

    @Test
    void fromLetterMapsNameStatusCodesForTheGitLog() {
        assertEquals(GitFileStatus.ADDED, GitFileStatus.fromLetter('A'));
        assertEquals(GitFileStatus.MODIFIED, GitFileStatus.fromLetter('M'));
        assertEquals(GitFileStatus.DELETED, GitFileStatus.fromLetter('D'));
        assertEquals(GitFileStatus.RENAMED, GitFileStatus.fromLetter('R'));
        assertEquals(GitFileStatus.RENAMED, GitFileStatus.fromLetter('C'), "a copy is colored like a rename");
        assertEquals(GitFileStatus.MODIFIED, GitFileStatus.fromLetter('T'), "a type change is modified");
        assertEquals(GitFileStatus.ADDED, GitFileStatus.fromLetter('a'), "case-insensitive");
        assertEquals(
                GitFileStatus.MODIFIED, GitFileStatus.fromLetter('X'), "an unexpected letter defaults to modified");
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
