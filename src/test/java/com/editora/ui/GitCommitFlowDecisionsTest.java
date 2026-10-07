package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions behind stage / unstage / discard / clone and Git error reporting. */
class GitCommitFlowDecisionsTest {

    private static GitStatus status(FileEntry... files) {
        return new GitStatus(true, "main", "", 0, 0, List.of(files));
    }

    // --- unstage: a staged rename is two index changes -------------------------------------------------

    @Test
    void unstagingARenameAlsoResetsItsOriginalPath() {
        GitStatus status = status(new FileEntry("b.txt", 'R', '.', "a.txt"), new FileEntry("c.txt", 'M', '.', null));
        assertEquals(List.of("b.txt", "a.txt"), GitCoordinator.withRenameSources(status, List.of("b.txt")));
        assertEquals(List.of("c.txt"), GitCoordinator.withRenameSources(status, List.of("c.txt")));
    }

    @Test
    void unstagingAFolderCoversTheRenamesInsideItAndACopySource() {
        GitStatus status = status(
                new FileEntry("src/new.txt", 'R', '.', "old/was.txt"), new FileEntry("copy.txt", 'C', '.', "orig.txt"));
        assertEquals(List.of("src", "old/was.txt"), GitCoordinator.withRenameSources(status, List.of("src")));
        assertEquals(List.of("copy.txt", "orig.txt"), GitCoordinator.withRenameSources(status, List.of("copy.txt")));
        assertEquals(List.of("x"), GitCoordinator.withRenameSources(null, List.of("x")));
    }

    // --- discard: the command follows the path's status ------------------------------------------------

    @Test
    void aStagedOnlyChangeIsRestoredFromHeadNotFromTheIndex() {
        GitDiscardPlan plan = GitDiscardPlan.of(status(new FileEntry("a.txt", 'M', '.', null)), "a.txt");
        assertEquals(GitDiscardPlan.Kind.HEAD, plan.kind(), "checkout -- would be a no-op here");
        assertEquals(List.of("a.txt"), plan.specs());
    }

    @Test
    void anUnstagedChangeIsRestoredFromTheIndex() {
        GitDiscardPlan plan = GitDiscardPlan.of(status(new FileEntry("a.txt", '.', 'M', null)), "a.txt");
        assertEquals(GitDiscardPlan.Kind.WORKTREE, plan.kind());
    }

    @Test
    void anUntrackedFileIsCleanedEvenInsideAnUntrackedFolder() {
        GitStatus status = status(new FileEntry("new.txt", '?', '?', null), new FileEntry("fresh/", '?', '?', null));
        assertEquals(
                GitDiscardPlan.Kind.UNTRACKED,
                GitDiscardPlan.of(status, "new.txt").kind());
        assertEquals(
                GitDiscardPlan.Kind.UNTRACKED,
                GitDiscardPlan.of(status, "fresh/inner.txt").kind());
        assertEquals(
                GitDiscardPlan.Kind.UNTRACKED,
                GitDiscardPlan.of(status, "fresh").kind());
    }

    @Test
    void anUnmergedPathIsRefusedAndNamed() {
        GitDiscardPlan plan = GitDiscardPlan.of(status(new FileEntry("dir/story.txt", 'U', 'U', null)), "dir");
        assertEquals(GitDiscardPlan.Kind.CONFLICT, plan.kind());
        assertEquals("dir/story.txt", plan.subject());
    }

    @Test
    void aCleanPathOrOneOutsideTheRepositoryHasNothingToDiscard() {
        GitStatus status = status(new FileEntry("a.txt", '.', 'M', null));
        assertEquals(
                GitDiscardPlan.Kind.NOTHING,
                GitDiscardPlan.of(status, "clean.txt").kind());
        assertEquals(
                GitDiscardPlan.Kind.NOTHING, GitDiscardPlan.of(status, null).kind(), "no NullPointerException");
        assertEquals(
                GitDiscardPlan.Kind.NOTHING, GitDiscardPlan.of(null, "a.txt").kind());
    }

    @Test
    void revertingAStagedRenameBringsTheOldPathBackToo() {
        GitDiscardPlan plan = GitDiscardPlan.of(status(new FileEntry("b.txt", 'R', '.', "a.txt")), "b.txt");
        assertEquals(GitDiscardPlan.Kind.HEAD, plan.kind());
        assertEquals(List.of("b.txt", "a.txt"), plan.specs());
    }

    @Test
    void aFolderRevertsItsTrackedChangesAndLeavesUntrackedFilesAlone() {
        GitStatus status =
                status(new FileEntry("src/a.txt", '.', 'M', null), new FileEntry("src/new.txt", '?', '?', null));
        GitDiscardPlan plan = GitDiscardPlan.of(status, "src");
        assertEquals(GitDiscardPlan.Kind.WORKTREE, plan.kind());
        assertEquals(List.of("src"), plan.specs());
        assertEquals(
                GitDiscardPlan.Kind.WORKTREE, GitDiscardPlan.of(status, ".").kind(), "the repository root");
    }

    // --- clone destination ------------------------------------------------------------------------------

    @Test
    void aTildeDestinationIsTheHomeFolderNotAFolderNamedTilde(@TempDir Path home) {
        CloneDestination d = CloneDestination.resolve("~/code/repo", home, home.toString());
        assertTrue(d.ok());
        assertEquals(home.resolve("code/repo"), d.path());
        assertEquals(
                home.resolve("rel"),
                CloneDestination.resolve("rel", home, home.toString()).path());
    }

    @Test
    void aMissingParentIsCreatedAndAnEmptyFolderIsAccepted(@TempDir Path home) throws Exception {
        CloneDestination deep = CloneDestination.prepare("~/a/b/repo", home, home.toString());
        assertTrue(deep.ok());
        assertTrue(Files.isDirectory(home.resolve("a/b")), "git creates the target, not what is above it");
        assertFalse(Files.exists(deep.path()), "the target itself is left to git");

        Files.createDirectory(home.resolve("empty"));
        assertTrue(CloneDestination.prepare("~/empty", home, home.toString()).ok(), "git accepts an empty folder");
    }

    @Test
    void anOccupiedOrUnusableDestinationIsRefusedWithAReason(@TempDir Path home) throws Exception {
        Files.writeString(Files.createDirectory(home.resolve("full")).resolve("x"), "x");
        CloneDestination full = CloneDestination.prepare("~/full", home, home.toString());
        assertFalse(full.ok());
        assertEquals("status.destExists", full.errorKey());

        Files.writeString(home.resolve("file"), "x");
        assertEquals(
                "status.destExists",
                CloneDestination.prepare("~/file", home, home.toString()).errorKey());
        assertEquals(
                "status.clone.parentFailed",
                CloneDestination.prepare("~/file/under/repo", home, home.toString())
                        .errorKey(),
                "the parent cannot be created under a file");

        CloneDestination invalid = CloneDestination.prepare("bad\0name", home, home.toString());
        assertEquals("status.clone.invalidDest", invalid.errorKey(), "no uncaught InvalidPathException");
        assertNull(invalid.path());
        assertEquals(
                "status.clone.invalidDest",
                CloneDestination.prepare("  ", home, home.toString()).errorKey());
    }

    @Test
    void theSuggestedFolderNeverThrowsForAnOddRepositoryName(@TempDir Path home) {
        assertEquals(home.resolve("repo").toString(), GitCoordinator.suggestedCloneDir(home.toString(), "repo"));
        assertEquals("", GitCoordinator.suggestedCloneDir(home.toString(), "re\0po"));
        assertEquals("", GitCoordinator.suggestedCloneDir(home.toString(), ""));
    }

    // --- authentication failures ------------------------------------------------------------------------

    @Test
    void httpsSignInFailuresAreRecognised() {
        assertEquals(
                GitAuthFailure.Kind.HTTPS,
                GitAuthFailure.classify(
                        "fatal: could not read Username for 'https://github.com': terminal prompts disabled"));
        assertEquals(
                GitAuthFailure.Kind.HTTPS,
                GitAuthFailure.classify(
                        "remote: Invalid username or token.\nfatal: Authentication failed for 'https://x/'"));
        assertEquals(
                GitAuthFailure.Kind.HTTPS,
                GitAuthFailure.classify("fatal: unable to access 'https://x/': The requested URL returned error: 403"));
    }

    @Test
    void sshFailuresAreToldApart() {
        assertEquals(
                GitAuthFailure.Kind.SSH,
                GitAuthFailure.classify(
                        "git@github.com: Permission denied (publickey).\nfatal: Could not read from remote"));
        assertEquals(
                GitAuthFailure.Kind.HOST_KEY,
                GitAuthFailure.classify(
                        "Host key verification failed.\nfatal: Could not read from remote repository."));
    }

    @Test
    void otherErrorsGetNoSignInAdvice() {
        String conflict = "error: Your local changes to the following files would be overwritten by merge";
        assertEquals(GitAuthFailure.Kind.NONE, GitAuthFailure.classify(conflict));
        assertEquals(GitAuthFailure.Kind.NONE, GitAuthFailure.classify(null));
        assertEquals(GitAuthFailure.Kind.NONE, GitAuthFailure.classify("fatal: repository 'x' not found"));
        assertEquals(conflict, GitAuthFailure.withGuidance(conflict));
    }

    @Test
    void theErrorShownNamesTheNextStep() {
        com.editora.i18n.Messages.init("en");
        String shown = GitAuthFailure.withGuidance("fatal: terminal prompts disabled\n");
        assertTrue(shown.startsWith("fatal: terminal prompts disabled\n\n"), shown);
        assertTrue(shown.contains("gh auth setup-git"), shown);
        assertTrue(shown.contains("credential.helper"), shown);
        assertTrue(
                GitAuthFailure.withGuidance("x: Permission denied (publickey).").contains("ssh-add"));
    }

    // --- which repository a picked path belongs to -----------------------------------------------------

    @Test
    void aPathInANestedRepositoryOrSubmoduleIsNotTheActiveRepositorys(@TempDir Path dir) throws Exception {
        Path outer = dir.toRealPath();
        Files.createDirectory(outer.resolve(".git"));
        Path nested = Files.createDirectories(outer.resolve("vendor/lib"));
        Files.createDirectory(nested.resolve(".git"));
        Path submodule = Files.createDirectories(outer.resolve("modules/sub"));
        Files.writeString(submodule.resolve(".git"), "gitdir: ../../.git/modules/sub\n"); // a file, not a folder

        assertEquals(GitPathScope.ACTIVE, GitPathScope.of(outer.resolve("src/Main.java"), outer));
        assertEquals(GitPathScope.ACTIVE, GitPathScope.of(outer, outer));
        assertEquals(GitPathScope.OTHER, GitPathScope.of(nested.resolve("a.txt"), outer));
        assertEquals(GitPathScope.OTHER, GitPathScope.of(submodule.resolve("b.txt"), outer));
        assertEquals(GitPathScope.OTHER, GitPathScope.of(outer.resolve("x.txt"), null), "no active repository yet");
        assertEquals(nested, GitPathScope.nearestRepository(nested.resolve("deep/er/a.txt")));
    }

    @Test
    void aPathInNoRepositoryIsNone(@TempDir Path dir) {
        Path plain = dir.resolve("notes.txt");
        // (The temp folder itself could sit inside a repository on some machines; only assert when it does not.)
        if (GitPathScope.nearestRepository(plain) == null) {
            assertEquals(GitPathScope.NONE, GitPathScope.of(plain, null));
        }
        assertEquals(GitPathScope.NONE, GitPathScope.of(null, null));
    }

    // --- every Git error header comes from the catalogs -------------------------------------------------

    @Test
    void noGitErrorHeaderIsAHardCodedString() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/editora/ui/GitCoordinator.java"));
        assertFalse(source.contains("gitError(\""), "gitError(...) summaries go through tr(...)");
        assertFalse(source.contains("\" failed\""), "no English suffix glued onto a label");
        String registrar = Files.readString(Path.of("src/main/java/com/editora/ui/WindowCommandRegistrar.java"));
        assertFalse(registrar.contains("gitSync(\""), "the palette's Fetch / Pull labels are localized");
    }
}
