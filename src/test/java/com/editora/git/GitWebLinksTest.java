package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Permalinks from remote URLs, for each kind of host. */
class GitWebLinksTest {

    private static final String SHA = "0123abcd";

    private static GitWebLinks.Repo repo(String url) {
        GitWebLinks.Repo repo = GitWebLinks.repo(url);
        if (repo == null) {
            throw new AssertionError("no repository for " + url);
        }
        return repo;
    }

    @Test
    void remoteUrlsInEveryFormName() {
        GitWebLinks.Repo github = new GitWebLinks.Repo(GitWebLinks.Host.GITHUB, "https://github.com/me/app");
        assertEquals(github, repo("git@github.com:me/app.git"));
        assertEquals(github, repo("https://github.com/me/app.git"));
        assertEquals(github, repo("https://me:token@github.com/me/app/"));
        assertEquals(github, repo("ssh://git@github.com:22/me/app.git"));
        assertEquals(github, repo("git://github.com/me/app"));
        assertEquals(github, repo("  github.com:me/app  "));
        assertEquals(
                new GitWebLinks.Repo(GitWebLinks.Host.GITHUB, "https://git.example.com:8443/team/app"),
                repo("https://git.example.com:8443/team/app.git"),
                "an unknown host is taken to write links as GitHub does; a web port is kept");
        assertEquals(
                new GitWebLinks.Repo(GitWebLinks.Host.GITHUB, "http://localhost:3000/me/app"),
                repo("http://localhost:3000/me/app.git"));
        assertEquals(
                new GitWebLinks.Repo(GitWebLinks.Host.GITLAB, "https://gitlab.com/group/sub/app"),
                repo("git@gitlab.com:group/sub/app.git"));
        assertEquals(
                GitWebLinks.Host.GITLAB,
                repo("ssh://git@gitlab.example.org:2222/a/b.git").host());
        assertEquals(
                "https://gitlab.example.org/a/b",
                repo("ssh://git@gitlab.example.org:2222/a/b.git").base());
        assertEquals(
                GitWebLinks.Host.BITBUCKET,
                repo("git@bitbucket.org:team/app.git").host());
        assertEquals(
                GitWebLinks.Host.GITEA, repo("https://codeberg.org/me/app.git").host());
        assertEquals(
                GitWebLinks.Host.GITEA, repo("git@gitea.example.com:me/app.git").host());
        assertEquals(
                new GitWebLinks.Repo(GitWebLinks.Host.SOURCEHUT, "https://git.sr.ht/~me/app"),
                repo("git@git.sr.ht:~me/app"));
        assertEquals(
                new GitWebLinks.Repo(GitWebLinks.Host.AZURE, "https://dev.azure.com/org/proj/_git/app"),
                repo("git@ssh.dev.azure.com:v3/org/proj/app"));
        assertEquals(
                new GitWebLinks.Repo(GitWebLinks.Host.AZURE, "https://dev.azure.com/org/proj/_git/app"),
                repo("https://org@dev.azure.com/org/proj/_git/app"));
        assertEquals(
                GitWebLinks.Host.AZURE,
                repo("https://org.visualstudio.com/proj/_git/app").host());

        assertNull(GitWebLinks.repo("/srv/git/app.git"), "a local path");
        assertNull(GitWebLinks.repo("file:///srv/git/app.git"));
        assertNull(GitWebLinks.repo("C:\\repos\\app"));
        assertNull(GitWebLinks.repo("../app"));
        assertNull(GitWebLinks.repo("https://github.com/"), "no repository in it");
        assertNull(GitWebLinks.repo("server:app.git"), "a bare host name is no web host");
        assertNull(GitWebLinks.repo("git@git.sr.ht:app"), "sourcehut repositories are under ~user");
        assertNull(GitWebLinks.repo("https://dev.azure.com/org/proj"), "no _git");
        assertNull(GitWebLinks.repo("ssh.dev.azure.com:v2/x"));
    }

    @Test
    void fileLinksWithLinesForEachHost() {
        String path = "src/My File.java";
        assertEquals(
                "https://github.com/me/app/blob/0123abcd/src/My%20File.java#L5-L7",
                GitWebLinks.fileUrl(repo("git@github.com:me/app"), SHA, path, 4, 6));
        assertEquals(
                "https://github.com/me/app/blob/0123abcd/src/My%20File.java#L5",
                GitWebLinks.fileUrl(repo("git@github.com:me/app"), SHA, path, 4, 4));
        assertEquals(
                "https://github.com/me/app/blob/0123abcd/src/My%20File.java",
                GitWebLinks.fileUrl(repo("git@github.com:me/app"), SHA, path, -1, -1));
        assertEquals(
                "https://gitlab.com/g/app/-/blob/0123abcd/a.c#L1-3",
                GitWebLinks.fileUrl(repo("git@gitlab.com:g/app"), SHA, "a.c", 0, 2));
        assertEquals(
                "https://bitbucket.org/t/app/src/0123abcd/a.c#lines-2:4",
                GitWebLinks.fileUrl(repo("git@bitbucket.org:t/app"), SHA, "a.c", 1, 3));
        assertEquals(
                "https://bitbucket.org/t/app/src/0123abcd/a.c#lines-2",
                GitWebLinks.fileUrl(repo("git@bitbucket.org:t/app"), SHA, "a.c", 1, 1));
        assertEquals(
                "https://codeberg.org/me/app/src/commit/0123abcd/a.c#L2-L3",
                GitWebLinks.fileUrl(repo("https://codeberg.org/me/app"), SHA, "a.c", 1, 2));
        assertEquals(
                "https://git.sr.ht/~me/app/tree/0123abcd/item/a.c#L2-3",
                GitWebLinks.fileUrl(repo("https://git.sr.ht/~me/app"), SHA, "a.c", 1, 2));
        assertEquals(
                "https://dev.azure.com/o/p/_git/r?path=/a.c&version=GC0123abcd&line=2&lineEnd=4"
                        + "&lineStartColumn=1&lineEndColumn=1&lineStyle=plain",
                GitWebLinks.fileUrl(repo("https://dev.azure.com/o/p/_git/r"), SHA, "a.c", 1, 2));
        assertEquals(
                "https://dev.azure.com/o/p/_git/r?path=/a.c&version=GC0123abcd",
                GitWebLinks.fileUrl(repo("https://dev.azure.com/o/p/_git/r"), SHA, "a.c", -1, -1));
        assertEquals(
                "https://github.com/me/app/blob/0123abcd/a.c#L3",
                GitWebLinks.fileUrl(repo("git@github.com:me/app"), SHA, "a.c", 2, 1),
                "an end before the start is the start");
    }

    @Test
    void folderLinksForEachHost() {
        assertEquals(
                "https://github.com/me/app/tree/0123abcd/src/main",
                GitWebLinks.folderUrl(repo("git@github.com:me/app"), SHA, "src/main"));
        assertEquals(
                "https://github.com/me/app/tree/0123abcd",
                GitWebLinks.folderUrl(repo("git@github.com:me/app"), SHA, ""));
        assertEquals(
                "https://gitlab.com/g/app/-/tree/0123abcd/src",
                GitWebLinks.folderUrl(repo("git@gitlab.com:g/app"), SHA, "src"));
        assertEquals(
                "https://bitbucket.org/t/app/src/0123abcd/src/",
                GitWebLinks.folderUrl(repo("git@bitbucket.org:t/app"), SHA, "src"));
        assertEquals(
                "https://codeberg.org/me/app/src/commit/0123abcd/src",
                GitWebLinks.folderUrl(repo("https://codeberg.org/me/app"), SHA, "src"));
        assertEquals(
                "https://git.sr.ht/~me/app/tree/0123abcd/item/src",
                GitWebLinks.folderUrl(repo("https://git.sr.ht/~me/app"), SHA, "src"));
        assertEquals(
                "https://git.sr.ht/~me/app/tree/0123abcd",
                GitWebLinks.folderUrl(repo("https://git.sr.ht/~me/app"), SHA, ""));
        assertEquals(
                "https://dev.azure.com/o/p/_git/r?path=/src&version=GC0123abcd",
                GitWebLinks.folderUrl(repo("https://dev.azure.com/o/p/_git/r"), SHA, "src"));
        assertEquals("a%23b/%C3%A9t%C3%A9", GitWebLinks.encode("a#b/été"));
    }
}
