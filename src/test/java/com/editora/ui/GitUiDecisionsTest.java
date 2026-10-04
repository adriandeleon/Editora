package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure decisions behind the Git / GitHub windows. */
class GitUiDecisionsTest {

    @Test
    void aFileHistoryComparesOnlyItsOwnFileWithTheHistoryFile(@TempDir Path dir) throws Exception {
        Path root = dir.toRealPath();
        Path a = Files.writeString(root.resolve("a.txt"), "a\n");
        Files.writeString(root.resolve("b.txt"), "b\n");

        assertEquals(a, GitWindowCoordinator.historyWorkingFile(root, a, "a.txt"));
        assertEquals(
                root.resolve("b.txt"),
                GitWindowCoordinator.historyWorkingFile(root, a, "b.txt"),
                "another file of the same commit is compared with ITS working copy, never written into a.txt");
        assertEquals(root.resolve("b.txt"), GitWindowCoordinator.historyWorkingFile(root, null, "b.txt"));
        assertEquals(root.resolve("b.txt"), GitWindowCoordinator.historyWorkingFile(root, root, "b.txt"));
    }

    @Test
    void unstageRefusesAConflictedFileAndAFolderHoldingOne() {
        GitStatus status = new GitStatus(
                true,
                "main",
                "",
                0,
                0,
                List.of(new FileEntry("src/story.txt", 'U', 'U', null), new FileEntry("ok.txt", 'M', '.', null)));

        assertEquals("src/story.txt", GitCoordinator.firstUnmergedUnder(status, List.of("src/story.txt")));
        assertEquals("src/story.txt", GitCoordinator.firstUnmergedUnder(status, List.of("ok.txt", "src")));
        assertEquals("src/story.txt", GitCoordinator.firstUnmergedUnder(status, List.of(".")));
        assertNull(GitCoordinator.firstUnmergedUnder(status, List.of("ok.txt")));
        assertNull(GitCoordinator.firstUnmergedUnder(null, List.of("ok.txt")));
    }

    @Test
    void aFailedListSaysWhatGhSaid() {
        assertEquals(
                "Could not list issues: HTTP 403: API rate limit exceeded",
                GitHubCoordinator.failureLine(
                        "Could not list issues", "\n  HTTP 403: API rate limit exceeded\nmore\n"));
        assertEquals("Could not list issues", GitHubCoordinator.failureLine("Could not list issues", " \n"));
        assertEquals("Could not list issues", GitHubCoordinator.failureLine("Could not list issues", null));
    }
}
