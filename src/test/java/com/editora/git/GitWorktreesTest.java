package com.editora.git;

import java.util.List;

import com.editora.git.GitWorktrees.Worktree;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code git worktree list --porcelain} → work trees. */
class GitWorktreesTest {

    private static final String HASH = "7df084a6a5b23414771d646dbaada6a7e1f487fe";

    @Test
    void parsesTheMainAndLinkedWorktrees() {
        String porcelain = "worktree /home/me/repo\nHEAD " + HASH + "\nbranch refs/heads/main\n\n"
                + "worktree /home/me/repo-topic\nHEAD " + HASH + "\nbranch refs/heads/feature/topic\n\n";
        assertEquals(
                List.of(
                        new Worktree("/home/me/repo", HASH, "main", true, false, false, false, false),
                        new Worktree("/home/me/repo-topic", HASH, "feature/topic", false, false, false, false, false)),
                GitWorktrees.parse(porcelain));
    }

    @Test
    void parsesDetachedLockedAndPrunableWorktrees() {
        String porcelain = "worktree /r\nHEAD " + HASH + "\nbranch refs/heads/main\n\n"
                + "worktree /r-detached\nHEAD " + HASH + "\ndetached\n\n"
                + "worktree /r-locked\nHEAD " + HASH + "\nbranch refs/heads/a\nlocked on a usb stick\n\n"
                + "worktree /r-gone\nHEAD " + HASH
                + "\nbranch refs/heads/b\nprunable gitdir file points to non-existent location\n";
        List<Worktree> trees = GitWorktrees.parse(porcelain);
        assertEquals(4, trees.size(), "the last stanza needs no closing blank line");
        assertTrue(trees.get(1).detached());
        assertEquals("", trees.get(1).branch());
        assertTrue(trees.get(2).locked());
        assertTrue(trees.get(3).prunable());
        assertEquals("b", trees.get(3).branch());
    }

    /** The main entry of a bare repository has neither HEAD nor branch. */
    @Test
    void parsesABareMainEntry() {
        List<Worktree> trees = GitWorktrees.parse(
                "worktree /srv/repo.git\nbare\n\nworktree /srv/wt\nHEAD " + HASH + "\nbranch refs/heads/main\n\n");
        assertEquals(new Worktree("/srv/repo.git", "", "", true, true, false, false, false), trees.get(0));
        assertEquals("main", trees.get(1).branch());
    }

    @Test
    void aPathWithSpacesAndWindowsLineEndingsSurvive() {
        List<Worktree> trees = GitWorktrees.parse(
                "worktree C:/Users/me/my repo\r\nHEAD " + HASH + "\r\nbranch refs/heads/main\r\n\r\n");
        assertEquals(
                List.of(new Worktree("C:/Users/me/my repo", HASH, "main", true, false, false, false, false)), trees);
    }

    @Test
    void emptyOutputHasNoWorktrees() {
        assertEquals(List.of(), GitWorktrees.parse(""));
        assertEquals(List.of(), GitWorktrees.parse(null));
    }
}
