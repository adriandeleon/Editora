package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Changes card's reading of real repositories: modified, added, deleted and untracked files below a folder. */
class GitServiceWorkingChangesRepoTest {

    @TempDir
    Path temp;

    private final GitService service = new GitService();

    @AfterEach
    void stop() {
        service.shutdown();
    }

    @Test
    void theChangesBelowAFolderRelativeToIt() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        GitServiceRecentChangesTest.git(repo, null, "init", "-q", "-b", "main");
        Files.createDirectories(repo.resolve("app/src"));
        Files.writeString(repo.resolve("app/src/A.java"), "class A {\n  int one;\n}\n");
        Files.writeString(repo.resolve("app/src/Gone.java"), "class Gone {}\n");
        Files.writeString(repo.resolve("README.md"), "# Readme\n");
        Files.writeString(repo.resolve(".gitignore"), "*.log\n");
        GitServiceRecentChangesTest.commit(repo, 1, "Ada", "start");

        Files.writeString(repo.resolve("app/src/A.java"), "class A {\n  long one;\n}\n");
        Files.delete(repo.resolve("app/src/Gone.java"));
        Files.writeString(repo.resolve("app/src/Staged.java"), "class Staged {}\n");
        GitServiceRecentChangesTest.git(repo, null, "add", "app/src/Staged.java");
        Files.writeString(repo.resolve("app/New.txt"), "new\n");
        Files.writeString(repo.resolve("app/debug.log"), "ignored\n");
        Files.write(repo.resolve("app/blob.bin"), new byte[] {1, 0, 2});
        Files.writeString(repo.resolve("README.md"), "# Changed, but outside app\n");

        GitChanges.Result found = service.workingChangesNow(repo.resolve("app"));
        assertTrue(found.ok(), found.error());
        assertEquals(
                List.of("New.txt", "blob.bin", "src/A.java", "src/Gone.java", "src/Staged.java"),
                found.files().stream().map(GitChanges.FileDiff::path).toList());
        assertEquals('U', found.file("New.txt").status());
        assertTrue(found.file("blob.bin").binary());
        assertEquals('M', found.file("src/A.java").status());
        assertEquals(1, found.file("src/A.java").added());
        assertEquals('D', found.file("src/Gone.java").status());
        assertEquals('A', found.file("src/Staged.java").status(), "staged: against HEAD all the same");
    }

    @Test
    void aRepositoryWithNoCommitAndAFolderOutsideAny() throws Exception {
        Path fresh = Files.createDirectories(temp.resolve("fresh"));
        GitServiceRecentChangesTest.git(fresh, null, "init", "-q", "-b", "main");
        Files.writeString(fresh.resolve("A.txt"), "a\n");
        GitServiceRecentChangesTest.git(fresh, null, "add", "A.txt");
        Files.writeString(fresh.resolve("B.txt"), "b\n");
        GitChanges.Result found = service.workingChangesNow(fresh);
        assertTrue(found.ok(), found.error());
        assertEquals(
                List.of("A.txt", "B.txt"),
                found.files().stream().map(GitChanges.FileDiff::path).toList());
        assertEquals('A', found.file("A.txt").status());

        Path plain = Files.createDirectories(temp.resolve("plain"));
        assertSame(GitChanges.Result.UNAVAILABLE, service.workingChangesNow(plain));
        assertSame(GitChanges.Result.UNAVAILABLE, service.workingChangesNow(null));
        assertFalse(GitChanges.Result.UNAVAILABLE.ok());
    }

    @Test
    void largeUntrackedFilesManyFilesAndAFolderGitCannotDiff() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("big"));
        GitServiceRecentChangesTest.git(repo, null, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("keep.txt"), "k\n");
        GitServiceRecentChangesTest.commit(repo, 1, "Ada", "start");
        Files.writeString(repo.resolve("huge.txt"), "x".repeat(1024 * 1024 + 10));
        Path many = Files.createDirectories(repo.resolve("many"));
        for (int i = 0; i < GitChanges.MAX_FILES + 3; i++) {
            Files.writeString(many.resolve(String.format("f%04d.txt", i)), "f\n");
        }
        GitChanges.Result found = service.workingChangesNow(repo);
        assertTrue(found.ok(), found.error());
        assertTrue(found.truncated(), "more files than are kept");
        assertEquals(GitChanges.MAX_FILES, found.files().size());
        GitChanges.Result top = service.workingChangesNow(repo);
        assertEquals("huge.txt", top.files().getFirst().path());
        assertTrue(top.files().getFirst().truncated(), "listed, not read");
        assertTrue(top.files().getFirst().lines().isEmpty());

        GitChanges.Result inside = service.workingChangesNow(repo.resolve(".git"));
        assertFalse(inside.ok(), "git diff needs a work tree");
    }
}
