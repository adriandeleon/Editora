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
    void aMapCardsChangeBarsComeFromItsFilesOwnRepository() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("bars"));
        GitServiceRecentChangesTest.git(repo, null, "init", "-q", "-b", "main");
        Files.createDirectories(repo.resolve("src"));
        Path file = Files.writeString(repo.resolve("src/A.java"), "class A {\n  int one;\n  int two;\n}\n");
        GitServiceRecentChangesTest.commit(repo, 1, "Ada", "start");
        Files.writeString(file, "class A {\n  long one;\n  int two;\n  int three;\n}\n");

        GitService.GitDiff diff = service.fileChangesNow(file);
        assertEquals(java.util.Map.of(1, ChangeType.MODIFIED, 3, ChangeType.ADDED), diff.changes());
        assertFalse(diff.hunks().isEmpty(), "the bars' hover text");

        Path plain =
                Files.writeString(Files.createDirectories(temp.resolve("loose")).resolve("B.txt"), "b\n");
        assertEquals(null, service.fileChangesNow(plain), "no repository");
    }

    @Test
    void aBranchsChangesSinceItLeftMainAndTheRefsOffered() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("branch"));
        GitServiceRecentChangesTest.git(repo, null, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("A.txt"), "a\n");
        GitServiceRecentChangesTest.commit(repo, 3, "Ada", "start");
        GitServiceRecentChangesTest.git(repo, null, "checkout", "-q", "-b", "feature");
        Files.writeString(repo.resolve("A.txt"), "a\nb\n");
        Files.writeString(repo.resolve("B.txt"), "b\n");
        GitServiceRecentChangesTest.commit(repo, 2, "Ada", "feature work");
        GitServiceRecentChangesTest.git(repo, null, "checkout", "-q", "main");
        Files.writeString(repo.resolve("C.txt"), "c\n");
        GitServiceRecentChangesTest.commit(repo, 1, "Bo", "main moves on");
        GitServiceRecentChangesTest.git(repo, null, "checkout", "-q", "feature");
        Files.writeString(repo.resolve("A.txt"), "a\nb\nc\n"); // and an uncommitted change

        GitChanges.Result head = service.workingChangesNow(repo);
        assertEquals(
                List.of("A.txt"),
                head.files().stream().map(GitChanges.FileDiff::path).toList());
        assertEquals("HEAD", head.base());

        GitChanges.Result branch = service.workingChangesNow(repo, new GitService.ChangesBase("main", true));
        assertTrue(branch.ok(), branch.error());
        assertEquals(
                List.of("A.txt", "B.txt"),
                branch.files().stream().map(GitChanges.FileDiff::path).toList(),
                "what the branch changes, not what main did since");
        assertEquals(2, branch.file("A.txt").added());
        assertTrue(branch.base().matches("[0-9a-f]{40}"), branch.base());

        GitChanges.Result tip = service.workingChangesNow(repo, new GitService.ChangesBase("main", false));
        assertEquals('D', tip.file("C.txt").status(), "against main's tip: its new file is not here");

        assertTrue(service.workingChangesNow(repo, new GitService.ChangesBase("-x", true))
                .error()
                .startsWith("unsafe"));
        assertTrue(service.workingChangesNow(repo, new GitService.ChangesBase("nope", true))
                .error()
                .startsWith("no base"));
        assertFalse(GitService.ChangesBase.HEAD.equals(new GitService.ChangesBase("main", true)));
        assertTrue(GitService.ChangesBase.HEAD.head());

        GitService.ReviewRefs refs = service.reviewRefsNow(repo);
        assertEquals("feature", refs.branch());
        assertEquals("main", refs.defaultBranch(), "no origin: the main branch there is");
        assertEquals(List.of("feature", "main"), refs.refs().stream().sorted().toList());
        GitServiceRecentChangesTest.git(repo, null, "update-ref", "refs/remotes/origin/main", "main");
        GitServiceRecentChangesTest.git(
                repo, null, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main");
        GitServiceRecentChangesTest.git(repo, null, "checkout", "-q", "--detach");
        GitService.ReviewRefs detached = service.reviewRefsNow(repo);
        assertEquals("", detached.branch(), "a detached HEAD is on no branch");
        assertEquals("origin/main", detached.defaultBranch(), "origin's HEAD names it");
        assertTrue(detached.refs().contains("origin/main"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < GitService.MAX_REVIEW_REFS + 5; i++) {
            many.append("refs/heads/b").append(i).append('\n');
        }
        assertEquals(
                GitService.MAX_REVIEW_REFS,
                GitService.reviewRefNames(many.toString()).size());
        assertSame(GitService.ReviewRefs.NONE, service.reviewRefsNow(Files.createDirectories(temp.resolve("none"))));
        assertSame(GitService.ReviewRefs.NONE, service.reviewRefsNow(null));
        assertEquals(
                List.of("a", "origin/a"),
                GitService.reviewRefNames(
                        "refs/heads/a\nrefs/remotes/origin/HEAD\nrefs/remotes/origin/a\nrefs/tags/x\nrefs/heads/a\n\n"));
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
