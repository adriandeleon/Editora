package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Permalinks read from real repositories: the HEAD commit, the remote, lines mapped to HEAD's. */
class GitServicePermalinkRepoTest {

    @TempDir
    Path temp;

    private final GitService service = new GitService();

    @AfterEach
    void stop() {
        service.shutdown();
    }

    private Path repo() throws Exception {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        GitServiceRecentChangesTest.git(repo, null, "init", "-q", "-b", "main");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/A.java"), "class A {\n  int one;\n  int two;\n}\n");
        GitServiceRecentChangesTest.commit(repo, 1, "Ada", "start");
        return repo;
    }

    private static String head(Path repo) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(repo.toFile())
                .start();
        String hash = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        return hash;
    }

    @Test
    void aFilesLinesAndAFolderAtHead() throws Exception {
        Path repo = repo();
        GitServiceRecentChangesTest.git(repo, null, "remote", "add", "origin", "git@github.com:me/app.git");
        String sha = head(repo);

        GitService.Permalink lines = service.readPermalink(repo.resolve("src/A.java"), 1, 2);
        assertEquals(GitService.Permalink.Status.OK, lines.status());
        assertEquals("https://github.com/me/app/blob/" + sha + "/src/A.java#L2-L3", lines.url());
        assertFalse(lines.pushed(), "no upstream: not pushed");

        assertEquals(
                "https://github.com/me/app/blob/" + sha + "/src/A.java",
                service.readPermalink(repo.resolve("src/A.java"), -1, -1).url());
        assertEquals(
                "https://github.com/me/app/tree/" + sha + "/src",
                service.readPermalink(repo.resolve("src"), -1, -1).url());

        // Two lines added above: the line on disk is HEAD's line two lower.
        Files.writeString(repo.resolve("src/A.java"), "// new\n// lines\nclass A {\n  int one;\n  int two;\n}\n");
        assertEquals(
                "https://github.com/me/app/blob/" + sha + "/src/A.java#L2",
                service.readPermalink(repo.resolve("src/A.java"), 3, 3).url());
        GitService.Permalink fresh = service.readPermalink(repo.resolve("src/A.java"), 0, 1);
        assertEquals(GitService.Permalink.Status.NEW_LINES, fresh.status());
        assertEquals("https://github.com/me/app/blob/" + sha + "/src/A.java", fresh.url(), "the whole file instead");

        Files.writeString(repo.resolve("src/B.java"), "class B {}\n");
        assertEquals(
                GitService.Permalink.Status.UNTRACKED,
                service.readPermalink(repo.resolve("src/B.java"), 0, 0).status());
    }

    @Test
    void theUpstreamsRemoteIsNamedAndPushedIsTold() throws Exception {
        Path origin = Files.createDirectories(temp.resolve("origin.git"));
        GitServiceRecentChangesTest.git(origin, null, "init", "-q", "--bare", "-b", "main");
        Path repo = repo();
        GitServiceRecentChangesTest.git(repo, null, "remote", "add", "origin", "https://gitlab.com/g/app.git");
        GitServiceRecentChangesTest.git(repo, null, "remote", "add", "fork", origin.toString());
        GitServiceRecentChangesTest.git(repo, null, "push", "-q", "-u", "fork", "main");
        GitService.Permalink link = service.readPermalink(repo.resolve("src/A.java"), 0, 0);
        assertEquals(GitService.Permalink.Status.NO_REMOTE, link.status(), "the upstream's remote is a local path");

        GitServiceRecentChangesTest.git(repo, null, "remote", "set-url", "fork", "git@bitbucket.org:t/app.git");
        GitServiceRecentChangesTest.git(repo, null, "remote", "set-url", "--push", "fork", origin.toString());
        link = service.readPermalink(repo.resolve("src/A.java"), 0, 0);
        assertEquals(GitService.Permalink.Status.OK, link.status());
        assertTrue(link.url().startsWith("https://bitbucket.org/t/app/src/"), link.url());
        assertTrue(link.pushed(), "HEAD is on the upstream");
    }

    @Test
    void noLinkWithoutARepositoryACommitOrARemote() throws Exception {
        Path plain = Files.createDirectories(temp.resolve("plain"));
        Path file = Files.writeString(plain.resolve("x.txt"), "x\n");
        assertEquals(
                GitService.Permalink.Status.NO_REPOSITORY,
                service.readPermalink(file, 0, 0).status());

        Path empty = Files.createDirectories(temp.resolve("empty"));
        GitServiceRecentChangesTest.git(empty, null, "init", "-q", "-b", "main");
        Path loose = Files.writeString(empty.resolve("y.txt"), "y\n");
        assertEquals(
                GitService.Permalink.Status.NO_COMMIT,
                service.readPermalink(loose, 0, 0).status());

        Path repo = repo();
        assertEquals(
                GitService.Permalink.Status.NO_REMOTE,
                service.readPermalink(repo.resolve("src/A.java"), 0, 0).status());
        assertEquals("", service.readPermalink(repo.resolve("src/A.java"), 0, 0).url());
    }

    @Test
    void theRemoteNamedIsTheUpstreamsElseOriginElseTheFirst() {
        List<GitRemotes.Remote> remotes = List.of(
                new GitRemotes.Remote("fork", "f-url", "f-url"),
                new GitRemotes.Remote("origin", "o-url", "o-url"),
                new GitRemotes.Remote("pushonly", "", "p-url"));
        assertEquals("f-url", GitService.permalinkRemote(remotes, "fork/main"));
        assertEquals("o-url", GitService.permalinkRemote(remotes, ""));
        assertEquals("o-url", GitService.permalinkRemote(remotes, "gone/main"));
        assertEquals("f-url", GitService.permalinkRemote(remotes.subList(0, 1), ""));
        assertEquals("p-url", GitService.permalinkRemote(remotes.subList(2, 3), ""));
        assertNull(GitService.permalinkRemote(List.of(), ""));
    }
}
