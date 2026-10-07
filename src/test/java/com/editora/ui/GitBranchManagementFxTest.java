package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitRemotes;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.git.GitWorktrees;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Branch, push-variant, remote and work-tree management against real repositories, through the window's
 * {@link GitBranchCoordinator}: what git ends up with, what the user is told, and what is asked first.
 */
@Tag("fx")
class GitBranchManagementFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- delete ------------------------------------------------------------------------------------

    @Test
    void aMergedBranchIsDeletedWithoutAQuestion(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("branch", "done");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch deleted = watchStatus(fx, tr("status.git.deletedBranch", "done")::equals);

            FxTestSupport.runOnFx(() -> branches.deleteBranch(repo.root, "main", "done"));
            async.await(deleted, "branch deletion");

            assertFalse(hasBranch(repo, "done"));
        }
    }

    @Test
    void anUnmergedBranchIsDeletedOnlyAfterADangerConfirmationNamingItsCommits(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithUnmergedTopic(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch deleted = watchStatus(fx, tr("status.git.deletedBranch", "topic")::equals);
            CompletableFuture<Seen> asked =
                    answer(async, type -> type.getText().equals(tr("dialog.deleteBranch.force")));

            FxTestSupport.runOnFx(() -> branches.deleteBranch(repo.root, "main", "topic"));
            Seen confirmation = async.await(asked);
            async.await(deleted, "forced branch deletion");

            assertEquals(tr("dialog.deleteBranch.unmerged.many", "topic", 2), confirmation.content());
            assertTrue(confirmation.danger(), "the deleting button is styled as destructive");
            assertFalse(hasBranch(repo, "topic"));
        }
    }

    @Test
    void decliningTheConfirmationKeepsTheUnmergedBranch(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithUnmergedTopic(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch kept = watchStatus(fx, tr("status.git.branchKept", "topic")::equals);
            CompletableFuture<Seen> asked = answer(async, CANCEL);

            FxTestSupport.runOnFx(() -> branches.deleteBranch(repo.root, "main", "topic"));
            async.await(asked);
            async.await(kept, "kept branch");

            assertTrue(hasBranch(repo, "topic"));
        }
    }

    @Test
    void theCheckedOutBranchIsNeverDeleted(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch refused = watchStatus(fx, tr("status.git.deleteCurrentBranch", "main")::equals);

            FxTestSupport.runOnFx(() -> branches.deleteBranch(repo.root, "main", "main"));
            async.await(refused, "refusal to delete the current branch");
            async.awaitFx();

            assertTrue(hasBranch(repo, "main"));
        }
    }

    // --- rename / new branch / checkout by name / upstream -------------------------------------------

    @Test
    void renameMovesTheBranchAndRefusesAnInvalidName(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("branch", "topic");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch invalid = watchStatus(fx, tr("status.git.invalidBranchName", "bad name")::equals);
            FxTestSupport.runOnFx(() -> branches.renameBranch(repo.root, "topic", "bad name"));
            async.await(invalid, "invalid name refusal");
            assertTrue(hasBranch(repo, "topic"));

            CountDownLatch renamed = watchStatus(fx, tr("status.git.renamedBranch", "topic", "feature/new")::equals);
            FxTestSupport.runOnFx(() -> branches.renameBranch(repo.root, "topic", "feature/new"));
            async.await(renamed, "branch rename");

            assertFalse(hasBranch(repo, "topic"));
            assertTrue(hasBranch(repo, "feature/new"));
        }
    }

    @Test
    void aNewBranchFromARefIsCreatedWithOrWithoutSwitching(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("tag", "v1");
        repo.write("later.txt", "later\n");
        repo.commitAll("later");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch created = watchStatus(fx, tr("status.git.createdBranchFrom", "from-tag", "v1")::equals);
            FxTestSupport.runOnFx(() -> branches.newBranchFrom(repo.root, "v1", "from-tag", false));
            async.await(created, "branch creation without switching");

            assertEquals("main", currentBranch(repo), "the current branch is left alone");
            assertEquals(rev(repo, "v1"), rev(repo, "from-tag"));
            assertTrue(Files.exists(repo.root.resolve("later.txt")));

            CountDownLatch switched = watchStatus(fx, tr("status.createdBranch", "work")::equals);
            FxTestSupport.runOnFx(() -> branches.newBranchFrom(repo.root, "v1", "work", true));
            async.await(switched, "branch creation with switching");

            assertEquals("work", currentBranch(repo));
            assertFalse(Files.exists(repo.root.resolve("later.txt")), "the working tree is the tag's");
        }
    }

    @Test
    void aTagIsCheckedOutByNameAndAnOptionLikeNameIsRefused(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("tag", "v1");
        repo.write("later.txt", "later\n");
        repo.commitAll("later");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch refused = watchStatus(fx, tr("status.git.unsafeRef", "--orphan")::equals);
            FxTestSupport.runOnFx(() -> branches.checkoutRevision(repo.root, "--orphan"));
            async.await(refused, "unsafe revision refusal");
            assertEquals("main", currentBranch(repo));

            CountDownLatch checkedOut = watchStatus(fx, tr("status.checkedOut", "v1")::equals);
            FxTestSupport.runOnFx(() -> branches.checkoutRevision(repo.root, "v1"));
            async.await(checkedOut, "tag checkout");

            assertEquals("", currentBranch(repo), "a tag leaves HEAD detached");
            assertEquals(rev(repo, "v1"), rev(repo, "HEAD"));
        }
    }

    @Test
    void upstreamIsSetAndUnset(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path remote = bareRemote(dir, "origin");
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "origin", "main");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch set = watchStatus(fx, tr("status.git.upstreamSet", "main", "origin/main")::equals);
            FxTestSupport.runOnFx(() -> branches.setUpstream(repo.root, "main", "origin/main"));
            async.await(set, "upstream set");
            assertEquals("origin/main", upstreamOf(repo, "main"));

            CountDownLatch unset = watchStatus(fx, tr("status.git.upstreamUnset", "main")::equals);
            FxTestSupport.runOnFx(() -> branches.unsetUpstream(repo.root, "main"));
            async.await(unset, "upstream unset");
            assertEquals("", upstreamOf(repo, "main"));
        }
    }

    // --- merge / rebase ----------------------------------------------------------------------------

    @Test
    void mergeBringsTheBranchInAndReloadsOpenBuffers(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path file = repo.root.resolve("file.txt");
        repo.git("checkout", "-q", "-b", "topic");
        repo.write("file.txt", "topic text\n");
        repo.commitAll("topic");
        repo.git("checkout", "-q", "main");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, file);
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch merged = watchStatus(fx, tr("status.git.merged", "topic", "main")::equals);

            FxTestSupport.runOnFx(() -> branches.merge(repo.root, "main", "topic"));
            async.await(merged, "merge");
            awaitBufferContent(buffer, "topic text\n");

            assertEquals(rev(repo, "topic"), rev(repo, "main"), "a fast-forward merge");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void aMergeThatStopsOnConflictsIsReportedAsAStopNotAFailure(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithConflictingTopic(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            String summary = tr("status.git.stoppedOnConflicts", tr("gitlabel.merge"));
            CountDownLatch stopped = watchStatus(fx, summary::equals);
            CompletableFuture<Seen> shown = answer(async, OK);

            FxTestSupport.runOnFx(() -> branches.merge(repo.root, "main", "topic"));
            async.await(stopped, "conflict stop");
            Seen dialog = async.await(shown);

            assertEquals(Alert.AlertType.INFORMATION, dialog.type(), "a conflict stop is not an error");
            assertEquals(summary, dialog.header());
            assertTrue(dialog.content().contains("CONFLICT (content): Merge conflict in file.txt"), dialog.content());
            assertFalse(dialog.content().contains("hint:"));
            assertTrue(Files.exists(repo.root.resolve(".git/MERGE_HEAD")), "the merge is left in progress");
            assertTrue(Files.readString(repo.root.resolve("file.txt")).contains("<<<<<<<"));
        }
    }

    @Test
    void rebaseReplaysTheBranchAndStopsOnConflictsWithoutAFailure(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("checkout", "-q", "-b", "topic");
        repo.write("topic.txt", "topic\n");
        repo.commitAll("topic");
        repo.git("checkout", "-q", "main");
        repo.write("main.txt", "main\n");
        repo.commitAll("main moves");
        repo.git("checkout", "-q", "topic");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "topic", "");
            CountDownLatch rebased = watchStatus(fx, tr("status.git.rebased", "topic", "main")::equals);
            FxTestSupport.runOnFx(() -> branches.rebase(repo.root, "topic", "main"));
            async.await(rebased, "rebase");

            assertEquals(rev(repo, "main"), rev(repo, "topic~1"), "topic's commit now sits on main");
            assertTrue(Files.exists(repo.root.resolve("main.txt")));
        }

        GitTestRepo conflicting = repoWithConflictingTopic(Files.createDirectory(dir.resolve("second")));
        conflicting.git("checkout", "-q", "topic");
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, conflicting, "topic", "");
            String summary = tr("status.git.stoppedOnConflicts", tr("gitlabel.rebase"));
            CountDownLatch stopped = watchStatus(fx, summary::equals);
            CompletableFuture<Seen> shown = answer(async, OK);

            FxTestSupport.runOnFx(() -> branches.rebase(conflicting.root, "topic", "main"));
            async.await(stopped, "rebase conflict stop");
            Seen dialog = async.await(shown);

            assertEquals(Alert.AlertType.INFORMATION, dialog.type());
            assertTrue(Files.isDirectory(conflicting.root.resolve(".git/rebase-merge")), "the rebase is in progress");
        }
    }

    // --- compare -----------------------------------------------------------------------------------

    @Test
    void comparingTwoBranchesOpensTheChangedFilesReview(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithUnmergedTopic(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch opened = watchStatus(fx, tr("status.diff.directoryOpened", 2)::equals);

            FxTestSupport.runOnFx(() -> branches.compare(repo.root, "main", "topic"));
            async.await(opened, "ref comparison review");

            CountDownLatch listed = new CountDownLatch(1);
            List<GitService.CommitFile> files = new ArrayList<>();
            FxTestSupport.runOnFx(() -> coordinator(fx).service().refDiff(repo.root, "topic", "main", diff -> {
                files.addAll(diff.files());
                listed.countDown();
            }));
            async.await(listed, "ref diff");
            // topic added one.txt and two.txt; seen from topic towards main they are deletions.
            assertEquals(
                    List.of(
                            new GitService.CommitFile('D', "one.txt", null),
                            new GitService.CommitFile('D', "two.txt", null)),
                    files);
        }
    }

    // --- push --------------------------------------------------------------------------------------

    @Test
    void aRejectedPushOffersPullThenPush(@TempDir Path dir) throws Exception {
        Diverged d = diverged(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, d.repo(), "main", "origin/main");
            CountDownLatch pushed = watchStatus(fx, tr("status.gitDone", tr("gitlabel.push"))::equals);
            CompletableFuture<Seen> offered =
                    answer(async, type -> type.getText().equals(tr("dialog.pushRejected.pull")));

            FxTestSupport.runOnFx(() -> {
                applyRepo(fx, d.repo(), "main", "origin/main");
                coordinator(fx).gitPush(); // the Commit window's button, the palette and the dropdown all push here
            });
            Seen offer = async.await(offered);
            async.await(pushed, "pull then push");

            assertEquals(tr("dialog.pushRejected.content", "main", "origin/main"), offer.content());
            assertEquals(
                    List.of(
                            tr("dialog.pushRejected.pull"),
                            tr("dialog.pushRejected.force"),
                            ButtonType.CANCEL.getText()),
                    offer.buttons());
            assertTrue(offer.danger(), "forcing is the destructive choice");
            assertEquals(rev(d.repo(), "main"), remoteRev(d.remote(), "main"));
            assertTrue(Files.exists(d.repo().root.resolve("theirs.txt")), "the other commit was merged in");
            assertTrue(Files.exists(d.repo().root.resolve("mine.txt")));
        }
    }

    @Test
    void aRejectedPushCanBeForcedWithALease(@TempDir Path dir) throws Exception {
        Diverged d = diverged(dir);
        d.repo().git("fetch", "-q", "origin"); // the lease is on what this repository last saw

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, d.repo(), "main", "origin/main");
            CountDownLatch forced = watchStatus(fx, tr("status.gitDone", tr("gitlabel.pushForce"))::equals);
            CompletableFuture<Seen> offered =
                    answer(async, type -> type.getText().equals(tr("dialog.pushRejected.force")));

            FxTestSupport.runOnFx(() -> {
                applyRepo(fx, d.repo(), "main", "origin/main");
                branches.push();
            });
            async.await(offered);
            async.await(forced, "forced push");

            assertEquals(rev(d.repo(), "main"), remoteRev(d.remote(), "main"), "the remote branch was replaced");
            assertFalse(Files.exists(d.repo().root.resolve("theirs.txt")), "nothing was pulled");
        }
    }

    /** The lease: a remote branch that moved since the last fetch is not overwritten. */
    @Test
    void aForcedPushDoesNotOverwriteCommitsThisRepositoryNeverSaw(@TempDir Path dir) throws Exception {
        Diverged d = diverged(dir);
        String theirs = remoteRev(d.remote(), "main");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, d.repo(), "main", "origin/main");
            CountDownLatch failed = watchStatus(fx, tr("status.git.syncFailed", tr("gitlabel.pushForce"))::equals);
            CompletableFuture<Seen> error = answer(async, OK);

            FxTestSupport.runOnFx(() -> branches.forcePush(d.repo().root, "main", "origin/main"));
            async.await(failed, "refused forced push");
            Seen dialog = async.await(error);

            assertTrue(dialog.content().contains("stale info"), dialog.content());
            assertEquals(theirs, remoteRev(d.remote(), "main"), "the unseen commit survives");
        }
    }

    @Test
    void forcePushIsRefusedOnADetachedHead(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "(detached)", "");
            CountDownLatch refused = watchStatus(fx, tr("status.git.detachedNoBranch")::equals);

            FxTestSupport.runOnFx(() -> {
                applyRepo(fx, repo, "(detached)", "");
                branches.pushForce();
            });
            async.await(refused, "detached HEAD refusal");
        }
    }

    @Test
    void pushToAnotherRemoteAndNameAndPushTags(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path fork = bareRemote(dir, "fork");
        repo.git("remote", "add", "fork", fork.toString());
        repo.git("tag", "v1");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CountDownLatch pushed = watchStatus(fx, tr("status.git.pushedTo", "main", "fork/review/main")::equals);
            FxTestSupport.runOnFx(() -> branches.pushTo(repo.root, "main", "fork", "review/main", true));
            async.await(pushed, "push to another name");

            assertEquals(rev(repo, "main"), remoteRev(fork, "review/main"));
            assertEquals("fork/review/main", upstreamOf(repo, "main"));

            CountDownLatch tags = watchStatus(fx, tr("status.git.pushedTags", "fork")::equals);
            FxTestSupport.runOnFx(() -> branches.pushTags(repo.root, "fork"));
            async.await(tags, "push tags");
            assertEquals(rev(repo, "v1"), gitDir(fork, "rev-parse", "refs/tags/v1"));
        }
    }

    @Test
    void aRemoteBranchIsDeletedOnlyAfterConfirmation(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path remote = bareRemote(dir, "origin");
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "origin", "main", "main:feature/old");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            CompletableFuture<Seen> declined = answer(async, CANCEL);
            FxTestSupport.runOnFx(
                    () -> branches.deleteRemoteBranch(repo.root, "origin/feature/old", List.of("origin")));
            Seen question = async.await(declined);
            async.awaitFx();
            assertEquals(tr("dialog.deleteRemoteBranch.confirm", "feature/old", "origin"), question.content());
            assertTrue(question.danger());
            assertFalse(remoteRev(remote, "feature/old").isEmpty(), "declined: still there");

            CountDownLatch deleted =
                    watchStatus(fx, tr("status.git.deletedRemoteBranch", "feature/old", "origin")::equals);
            CompletableFuture<Seen> confirmed =
                    answer(async, type -> type.getText().equals(tr("dialog.deleteRemoteBranch")));
            FxTestSupport.runOnFx(
                    () -> branches.deleteRemoteBranch(repo.root, "origin/feature/old", List.of("origin")));
            async.await(confirmed);
            async.await(deleted, "remote branch deletion");

            assertEquals("", remoteRev(remote, "feature/old"));
            assertFalse(remoteRev(remote, "main").isEmpty());
        }
    }

    // --- remotes -----------------------------------------------------------------------------------

    @Test
    void remotesAreAddedRenamedRepointedFetchedAndRemoved(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path first = bareRemote(dir, "first");
        Path second = bareRemote(dir, "second");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");

            CountDownLatch refused = watchStatus(fx, tr("status.git.invalidRemoteUrl")::equals);
            FxTestSupport.runOnFx(() -> branches.addRemote(repo.root, "evil", "--upload-pack=touch x", () -> {}));
            async.await(refused, "option-like URL refusal");
            assertEquals("", repo.git("remote").text());

            run(async, done -> branches.addRemote(repo.root, "origin", first.toString(), done));
            assertEquals(
                    first.toString(),
                    repo.git("remote", "get-url", "origin").text().strip());

            run(async, done -> branches.renameRemote(repo.root, "origin", "upstream", done));
            assertEquals("upstream\n", repo.git("remote").text());

            run(async, done -> branches.setRemoteUrl(repo.root, "upstream", second.toString(), done));
            assertEquals(
                    second.toString(),
                    repo.git("remote", "get-url", "upstream").text().strip());

            // Someone else's branch appears on the remote; a fetch of that one remote brings it in.
            repo.git("push", "-q", second.toString(), "main:refs/heads/theirs");
            run(async, done -> branches.fetchRemote(repo.root, "upstream", done));
            assertEquals(rev(repo, "main"), rev(repo, "refs/remotes/upstream/theirs"));

            // …and once it is deleted there, prune drops the stale remote-tracking branch.
            gitDir(second, "update-ref", "-d", "refs/heads/theirs");
            run(async, done -> branches.pruneRemote(repo.root, "upstream", done));
            assertNotEquals(
                    0,
                    repo.tryGit("rev-parse", "-q", "--verify", "refs/remotes/upstream/theirs")
                            .exit());

            CompletableFuture<Seen> asked = answer(async, type -> type.getText().equals(tr("dialog.remoteRemove")));
            CountDownLatch removed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> branches.removeRemote(repo.root, "upstream", removed::countDown));
            Seen question = async.await(asked);
            async.await(removed, "remote removal");
            assertEquals(tr("dialog.remoteRemove.confirm", "upstream"), question.content());
            assertTrue(question.danger());
            assertEquals("", repo.git("remote").text());
        }
    }

    /** A token stored in a remote's URL never reaches the manager's rows. */
    @Test
    void theRemotesManagerListsRemotesWithoutTheirCredentials(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("remote", "add", "origin", "https://alice:s3cr3t-token@example.invalid/team/app.git");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            FxTestSupport.runOnFx(() -> {
                applyRepo(fx, repo, "main", "");
                branches.manageRemotes();
            });
            List<GitRemotes.Remote> rows = awaitValue(async, () -> {
                GitManagerOverlay<GitRemotes.Remote> manager = branches.remotesManager;
                return manager == null ? null : manager.items();
            });

            assertEquals(1, rows.size());
            assertEquals("origin", rows.get(0).name());
            assertEquals("https://example.invalid/team/app.git", GitBranchCoordinator.remoteDetail(rows.get(0)));
        }
    }

    // --- work trees --------------------------------------------------------------------------------

    @Test
    void worktreesAreAddedListedAndRemoved(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path target = dir.toRealPath().resolve("repo-topic");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");

            // A relative path lands beside the repository, never inside it.
            run(async, done -> branches.addWorktree(repo.root, "repo-topic", "topic", true, done));
            assertTrue(Files.exists(target.resolve("file.txt")));
            assertTrue(hasBranch(repo, "topic"));

            List<GitWorktrees.Worktree> trees = worktrees(async, fx, repo);
            assertEquals(2, trees.size());
            assertTrue(trees.get(0).main());
            assertEquals(target.toString(), trees.get(1).path());
            assertEquals("topic", trees.get(1).branch());

            CountDownLatch mainRefused = watchStatus(fx, tr("status.git.worktreeMain")::equals);
            FxTestSupport.runOnFx(() -> branches.removeWorktree(repo.root, trees.get(0), () -> {}));
            async.await(mainRefused, "main work tree refusal");

            CompletableFuture<Seen> asked = answer(async, type -> type.getText().equals(tr("dialog.worktreeRemove")));
            CountDownLatch removed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> branches.removeWorktree(repo.root, trees.get(1), removed::countDown));
            Seen question = async.await(asked);
            async.await(removed, "work tree removal");

            assertEquals(tr("dialog.worktreeRemove.confirm", target.toString()), question.content());
            assertFalse(Files.exists(target));
            assertTrue(hasBranch(repo, "topic"), "the branch outlives its work tree");
            assertEquals(1, worktrees(async, fx, repo).size());
        }
    }

    @Test
    void aDirtyWorktreeIsRemovedOnlyAfterASecondConfirmation(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path target = dir.toRealPath().resolve("repo-dirty");
        repo.git("worktree", "add", "-q", "-b", "dirty", target.toString());
        Files.writeString(target.resolve("unsaved-work.txt"), "not committed\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            GitWorktrees.Worktree tree = worktrees(async, fx, repo).get(1);

            // First attempt: confirm the removal, then decline once git reports the uncommitted files.
            CompletableFuture<Seen> first = answer(async, type -> type.getText().equals(tr("dialog.worktreeRemove")));
            CountDownLatch kept = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> branches.removeWorktree(repo.root, tree, kept::countDown));
            async.await(first);
            Seen warning = async.await(answer(async, CANCEL));
            async.await(kept, "kept dirty work tree");
            assertEquals(tr("dialog.worktreeRemove.dirty", target.toString()), warning.content());
            assertTrue(warning.danger());
            assertTrue(Files.exists(target.resolve("unsaved-work.txt")), "declined: nothing was deleted");

            // Second attempt: confirm both.
            CompletableFuture<Seen> again = answer(async, type -> type.getText().equals(tr("dialog.worktreeRemove")));
            CountDownLatch removed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> branches.removeWorktree(repo.root, tree, removed::countDown));
            async.await(again);
            async.await(answer(async, type -> type.getText().equals(tr("dialog.worktreeRemove.force"))));
            async.await(removed, "forced work tree removal");
            assertFalse(Files.exists(target));
        }
    }

    @Test
    void pruneForgetsAWorktreeWhoseFolderWasDeleted(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path target = dir.toRealPath().resolve("repo-gone");
        repo.git("worktree", "add", "-q", "-b", "gone", target.toString());
        deleteRecursively(target);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            List<GitWorktrees.Worktree> before = worktrees(async, fx, repo);
            assertEquals(2, before.size());
            assertTrue(before.get(1).prunable());

            run(async, done -> branches.pruneWorktrees(repo.root, done));
            assertEquals(1, worktrees(async, fx, repo).size());
        }
    }

    /** "Open in new window" hands the work tree's folder to the window opener the registrar installed. */
    @Test
    void openingAWorktreeGoesThroughTheWindowOpener(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main", "");
            List<Path> opened = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                branches.setWindowOpener(opened::add);
                branches.openWorktree(repo.root.toString());
            });
            assertEquals(List.of(repo.root), opened);
        }
    }

    /** Every command is registered, and reports the missing repository instead of doing nothing. */
    @Test
    void theCommandsAreRegisteredAndGuardedOutsideARepository() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            com.editora.command.CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            List<String> ids = List.of(
                    "git.newBranchFrom",
                    "git.checkoutRevision",
                    "git.renameBranch",
                    "git.deleteBranch",
                    "git.deleteRemoteBranch",
                    "git.mergeBranch",
                    "git.rebaseOnto",
                    "git.compareBranch",
                    "git.setUpstream",
                    "git.unsetUpstream",
                    "git.pushTo",
                    "git.pushForce",
                    "git.pushTags",
                    "git.fetchRemote",
                    "git.remotes",
                    "git.worktrees");
            StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
            Label echo = FxTestSupport.field(statusBar, "echo");
            for (String id : ids) {
                assertTrue(FxTestSupport.callOnFx(() -> registry.get(id).isPresent()), id + " is not a command");
                String said = FxTestSupport.callOnFx(() -> {
                    coordinator(fx).applyState(GitService.RepoState.NONE);
                    echo.setText("");
                    registry.run(id);
                    return echo.getText();
                });
                assertEquals(tr("status.notARepo"), said, id + " outside a repository");
            }
        }
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private static final Predicate<ButtonType> OK = type -> type.getButtonData() == ButtonBar.ButtonData.OK_DONE;
    private static final Predicate<ButtonType> CANCEL =
            type -> type.getButtonData() == ButtonBar.ButtonData.CANCEL_CLOSE;

    /** What a dialog showed when the test answered it. */
    private record Seen(Alert.AlertType type, String header, String content, List<String> buttons, boolean danger) {}

    private record Diverged(GitTestRepo repo, Path remote) {}

    private static GitTestRepo repoWithCommit(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("file.txt", "base\n");
        repo.commitAll("base");
        return repo;
    }

    /** {@code main} plus a branch {@code topic} holding two commits that {@code main} lacks. */
    private static GitTestRepo repoWithUnmergedTopic(Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("checkout", "-q", "-b", "topic");
        repo.write("one.txt", "one\n");
        repo.commitAll("one");
        repo.write("two.txt", "two\n");
        repo.commitAll("two");
        repo.git("checkout", "-q", "main");
        return repo;
    }

    /** {@code main} and {@code topic} each change the same line of {@code file.txt}. */
    private static GitTestRepo repoWithConflictingTopic(Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("checkout", "-q", "-b", "topic");
        repo.write("file.txt", "topic version\n");
        repo.commitAll("topic");
        repo.git("checkout", "-q", "main");
        repo.write("file.txt", "main version\n");
        repo.commitAll("main");
        return repo;
    }

    /**
     * A repository whose {@code main} tracks {@code origin/main}, with one local commit ({@code mine.txt}) and
     * one commit pushed to the remote by someone else ({@code theirs.txt}) that it has not fetched.
     */
    private static Diverged diverged(Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path remote = bareRemote(dir, "origin");
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "--set-upstream", "origin", "main");
        GitTestRepo other = GitTestRepo.init(Files.createDirectory(dir.resolve("other")));
        other.git("pull", "-q", remote.toString(), "main");
        other.write("theirs.txt", "theirs\n");
        other.commitAll("theirs");
        other.git("push", "-q", remote.toString(), "main");
        repo.write("mine.txt", "mine\n");
        repo.commitAll("mine");
        return new Diverged(repo, remote);
    }

    private static Path bareRemote(Path dir, String name) throws Exception {
        Path bare = dir.toRealPath().resolve(name + ".git");
        Process init = new ProcessBuilder("git", "init", "-q", "--bare", "-b", "main", bare.toString())
                .inheritIO()
                .start();
        assertEquals(0, init.waitFor());
        return bare;
    }

    /** Runs git against a bare repository; the trimmed stdout, or {@code ""} when it exits non-zero. */
    private static String gitDir(Path bare, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--git-dir", bare.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return process.waitFor() == 0 ? out.strip() : "";
    }

    private static String remoteRev(Path bare, String branch) throws Exception {
        return gitDir(bare, "rev-parse", "-q", "--verify", "refs/heads/" + branch);
    }

    private static String rev(GitTestRepo repo, String revision) throws Exception {
        return repo.git("rev-parse", revision).text().strip();
    }

    private static boolean hasBranch(GitTestRepo repo, String name) throws Exception {
        return repo.tryGit("rev-parse", "-q", "--verify", "refs/heads/" + name).exit() == 0;
    }

    private static String currentBranch(GitTestRepo repo) throws Exception {
        return repo.git("branch", "--show-current").text().strip();
    }

    private static String upstreamOf(GitTestRepo repo, String branch) throws Exception {
        return repo.git("for-each-ref", "--format=%(upstream:short)", "refs/heads/" + branch)
                .text()
                .strip();
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static GitCoordinator coordinator(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "git");
    }

    private static void applyRepo(FxWindowFixture fx, GitTestRepo repo, String branch, String upstream) {
        coordinator(fx)
                .applyState(new GitService.RepoState(
                        repo.root, new GitStatus(true, branch, upstream, 0, 0, List.of()), Map.of(), Map.of()));
    }

    /** The window's branch coordinator, with {@code repo} applied as its active repository. */
    private static GitBranchCoordinator branches(FxWindowFixture fx, GitTestRepo repo, String branch, String upstream)
            throws Exception {
        GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
        FxTestSupport.runOnFx(() -> applyRepo(fx, repo, branch, upstream));
        return windows.branches;
    }

    /** Runs an operation that reports completion through a {@code Runnable}, and waits for it. */
    private static void run(AsyncTestScope async, java.util.function.Consumer<Runnable> operation) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> operation.accept(done::countDown));
        async.await(done, "git operation");
        async.awaitFx();
    }

    private static List<GitWorktrees.Worktree> worktrees(AsyncTestScope async, FxWindowFixture fx, GitTestRepo repo)
            throws Exception {
        CompletableFuture<List<GitWorktrees.Worktree>> listed = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> coordinator(fx).service().worktrees(repo.root, listed::complete));
        return async.await(listed);
    }

    private static <T> T awaitValue(AsyncTestScope async, java.util.concurrent.Callable<T> probe) throws Exception {
        for (int i = 0; i < 400; i++) {
            T value = FxTestSupport.callOnFx(probe);
            if (value != null) {
                return value;
            }
            Thread.sleep(25);
        }
        async.assertNoAsynchronousFailures();
        throw new AssertionError("timed out waiting for a value");
    }

    private static EditorBuffer open(MainController controller, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(Files.readString(file));
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            FxTestSupport.call(
                    controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static void awaitBufferContent(EditorBuffer buffer, String expected) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (expected.equals(FxTestSupport.callOnFx(buffer::getContent))) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, FxTestSupport.callOnFx(buffer::getContent));
    }

    private static CountDownLatch watchStatus(FxWindowFixture fx, Predicate<String> expected) throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> {
            StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
            Label echo = FxTestSupport.field(statusBar, "echo");
            ChangeListener<String> listener = (observable, before, after) -> {
                if (expected.test(after)) {
                    seen.countDown();
                }
            };
            echo.textProperty().addListener(listener);
        });
        return seen;
    }

    /**
     * Answers the next dialog that comes up by firing the button {@code choice} selects, and reports what
     * the dialog said. Polls from a worker: the dialog runs a nested event loop on the FX thread.
     */
    private static CompletableFuture<Seen> answer(AsyncTestScope async, Predicate<ButtonType> choice) {
        CompletableFuture<Seen> answered = new CompletableFuture<>();
        async.start("dialog-answer", () -> {
            for (int i = 0; i < 1200 && !answered.isDone(); i++) {
                FxTestSupport.runOnFx(() -> {
                    Seen seen = pressDialog(choice);
                    if (seen != null) {
                        answered.complete(seen);
                    }
                });
                Thread.sleep(25);
            }
        });
        return answered;
    }

    private static Seen pressDialog(Predicate<ButtonType> choice) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (!window.isShowing()
                    || window.getScene() == null
                    || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            ButtonType chosen =
                    pane.getButtonTypes().stream().filter(choice).findFirst().orElse(null);
            if (chosen == null) {
                continue;
            }
            String content = pane.getContent() instanceof TextArea area ? area.getText() : pane.getContentText();
            boolean danger = pane.getButtonTypes().stream()
                    .anyMatch(type -> pane.lookupButton(type).getStyleClass().contains("danger"));
            Alert.AlertType type = pane.getStyleClass().contains("information")
                    ? Alert.AlertType.INFORMATION
                    : pane.getStyleClass().contains("error")
                            ? Alert.AlertType.ERROR
                            : pane.getStyleClass().contains("warning")
                                    ? Alert.AlertType.WARNING
                                    : Alert.AlertType.CONFIRMATION;
            Seen seen = new Seen(
                    type,
                    pane.getHeaderText(),
                    content,
                    pane.getButtonTypes().stream().map(ButtonType::getText).toList(),
                    danger);
            ((Button) pane.lookupButton(chosen)).fire();
            return seen;
        }
        return null;
    }
}
