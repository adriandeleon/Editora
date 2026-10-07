package com.editora.git;

import java.util.List;

import com.editora.git.GitRemotes.Remote;
import com.editora.git.GitRemotes.RemoteBranch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@code git remote -v} → remotes, and the remote/branch split of a remote-tracking name. */
class GitRemotesTest {

    @Test
    void parsesFetchAndPushUrls() {
        String out = "fork\tgit@example.com:me/app.git (fetch)\n"
                + "fork\tgit@example.com:me/app.git (push)\n"
                + "origin\thttps://example.com/team/app.git (fetch)\n"
                + "origin\tssh://git@example.com/team/app.git (push)\n";
        assertEquals(
                List.of(
                        new Remote("fork", "git@example.com:me/app.git", "git@example.com:me/app.git"),
                        new Remote("origin", "https://example.com/team/app.git", "ssh://git@example.com/team/app.git")),
                GitRemotes.parse(out));
    }

    @Test
    void aUrlWithSpacesKeepsThem() {
        assertEquals(
                List.of(new Remote("local", "/srv/my repos/app.git", "/srv/my repos/app.git")),
                GitRemotes.parse("local\t/srv/my repos/app.git (fetch)\nlocal\t/srv/my repos/app.git (push)\n"));
    }

    @Test
    void emptyOutputHasNoRemotes() {
        assertEquals(List.of(), GitRemotes.parse(""));
        assertEquals(List.of(), GitRemotes.parse(null));
    }

    @Test
    void splitsARemoteBranchAtItsRemote() {
        assertEquals(new RemoteBranch("origin", "feature/x"), GitRemotes.split("origin/feature/x", List.of("origin")));
        // Without the remotes' names the first slash is the best guess.
        assertEquals(new RemoteBranch("origin", "feature/x"), GitRemotes.split("origin/feature/x", List.of()));
        assertEquals(new RemoteBranch("origin", "main"), GitRemotes.split("origin/main", null));
    }

    /** A remote may be called {@code team/fork}: the longest known remote that prefixes the name wins. */
    @Test
    void aRemoteNameWithASlashIsNotCutShort() {
        assertEquals(
                new RemoteBranch("team/fork", "main"),
                GitRemotes.split("team/fork/main", List.of("team", "team/fork")));
        assertEquals(new RemoteBranch("team", "fork/main"), GitRemotes.split("team/fork/main", List.of("team")));
    }

    @Test
    void aNameWithNothingToSplitIsRefused() {
        assertNull(GitRemotes.split("main", List.of("origin")));
        assertNull(GitRemotes.split("origin/", List.of()));
        assertNull(GitRemotes.split("/x", List.of()));
        assertNull(GitRemotes.split(null, List.of()));
    }
}
