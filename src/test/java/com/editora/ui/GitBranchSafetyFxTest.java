package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

import javafx.beans.value.ChangeListener;
import javafx.scene.control.Label;

import com.editora.git.GitService;
import com.editora.git.GitStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Branch commands that must never lose work: a switch that would restore a path of the branch's name, a new
 * branch that silently tracks where it started, a move off a detached HEAD that strands its commits, a merge or
 * rebase over uncommitted changes, and the work-tree and delete refusals.
 */
@Tag("fx")
class GitBranchSafetyFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void showQuestionsAgain() {
        Confirm.answerWith(null);
    }

    /**
     * B1: the branch {@code docs} was deleted elsewhere and a folder {@code docs/} holds uncommitted edits. Switching
     * to "docs" must fail, never restore the folder from the index.
     */
    @Test
    void switchingToADeletedBranchNeverRestoresAPathOfItsName(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.write("docs/guide.md", "committed\n");
        repo.commitAll("docs");
        repo.write("docs/guide.md", "my unsaved edit\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            apply(fx, repo, "main");
            // The refusal is git's error dialog, answered here; the reload after it then runs.
            CountDownLatch answered = OverlayTestKit.answerDialog(
                    async,
                    pane -> tr("status.git.switchFailed", "docs").equals(pane.getHeaderText()),
                    javafx.scene.control.ButtonBar.ButtonData.OK_DONE,
                    null);
            FxTestSupport.runOnFx(() -> coordinator(fx).checkoutBranch("docs"));
            async.await(answered, "the refused switch");
            async.awaitWorker(FxTestSupport.field(coordinator(fx).service(), "exec"));
            async.awaitFx();

            assertEquals("my unsaved edit\n", Files.readString(repo.root.resolve("docs/guide.md")));
            assertEquals("main", currentBranch(repo));
        }
    }

    /** B2: a branch made from a remote-tracking branch does not take it as its upstream, switched to or not. */
    @Test
    void aNewBranchFromARemoteTrackingBranchTracksNothing(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("remote", "add", "origin", dir.resolve("nowhere.git").toString());
        repo.git("update-ref", "refs/remotes/origin/main", "HEAD");
        repo.git("config", "branch.autoSetupMerge", "always");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main");
            CountDownLatch created = watchStatus(fx, tr("status.git.createdBranchFrom", "kept", "origin/main")::equals);
            FxTestSupport.runOnFx(() -> branches.newBranchFrom(repo.root, "origin/main", "kept", false));
            async.await(created, "branch creation without switching");
            async.awaitFx(); // the reload after the command, before the window goes
            assertEquals("", upstreamOf(repo, "kept"));

            CountDownLatch switched = watchStatus(fx, tr("status.createdBranch", "work")::equals);
            FxTestSupport.runOnFx(() -> branches.newBranchFrom(repo.root, "origin/main", "work", true));
            async.await(switched, "branch creation with switching");
            async.awaitFx(); // the reload after the command, before the window goes
            assertEquals("work", currentBranch(repo));
            assertEquals("", upstreamOf(repo, "work"));
        }
    }

    /**
     * B3: from a detached HEAD with a commit no branch has, Check Out Revision, the branch switch and New Branch
     * From with Switch on all ask first; Cancel leaves HEAD where it is.
     */
    @Test
    void leavingADetachedHeadWithItsOwnCommitsIsAskedFirst(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("checkout", "-q", "--detach");
        repo.write("experiment.txt", "only here\n");
        repo.commitAll("experiment");
        String stranded = rev(repo, "HEAD");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "(HEAD detached at " + stranded.substring(0, 7) + ")");
            List<Confirm.Question> asked = new CopyOnWriteArrayList<>();
            Confirm.answerWith(question -> {
                asked.add(question);
                return false;
            });
            List<Runnable> moves = List.of(
                    () -> branches.checkoutRevision(repo.root, "main"),
                    () -> coordinator(fx).checkoutBranch("main"),
                    () -> branches.newBranchFrom(repo.root, "main", "fresh", true));
            for (Runnable move : moves) {
                FxTestSupport.runOnFx(() -> coordinator(fx).host().setStatus("")); // the same words come back each time
                CountDownLatch kept = watchStatus(fx, tr("status.git.headKept")::equals);
                FxTestSupport.runOnFx(move);
                async.await(kept, "the declined move");
                async.awaitFx(); // the reload after the command, before the window goes
            }
            assertEquals(3, asked.size());
            assertEquals(tr("dialog.gitLeaveDetached.title"), asked.get(0).title());
            assertTrue(asked.get(0).message().contains("main"), asked.get(0).message());
            assertEquals(stranded, rev(repo, "HEAD"), "declined: HEAD did not move");
            assertFalse(hasBranch(repo, "fresh"));

            Confirm.answerWith(question -> true);
            CountDownLatch moved = watchStatus(fx, tr("status.checkedOut", "main")::equals);
            FxTestSupport.runOnFx(() -> branches.checkoutRevision(repo.root, "main"));
            async.await(moved, "the confirmed move");
            async.awaitFx(); // the reload after the command, before the window goes
            assertEquals("main", currentBranch(repo));
        }
    }

    /** B3: on a branch nothing is stranded, so nothing is asked. */
    @Test
    void movingOffABranchAsksNothing(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("branch", "other");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            apply(fx, repo, "main");
            List<Confirm.Question> asked = new CopyOnWriteArrayList<>();
            Confirm.answerWith(question -> {
                asked.add(question);
                return true;
            });
            CountDownLatch switched = watchStatus(fx, tr("status.switchedBranch", "other")::equals);
            FxTestSupport.runOnFx(() -> coordinator(fx).checkoutBranch("other"));
            async.await(switched, "the switch");
            async.awaitFx(); // the reload after the command, before the window goes
            assertEquals("other", currentBranch(repo));
            assertEquals(List.of(), asked);
        }
    }

    /** B14: merge and rebase carry uncommitted changes across instead of refusing them. */
    @Test
    void mergeAndRebaseCarryUncommittedChangesAcross(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("checkout", "-q", "-b", "topic");
        repo.write("topic.txt", "topic\n");
        repo.commitAll("topic");
        repo.git("checkout", "-q", "main");
        repo.write("main.txt", "main\n");
        repo.commitAll("main");
        repo.write("file.txt", "uncommitted\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main");
            CountDownLatch merged = watchStatus(fx, tr("status.git.merged", "topic", "main")::equals);
            FxTestSupport.runOnFx(() -> branches.merge(repo.root, "main", "topic"));
            async.await(merged, "the merge");
            async.awaitFx(); // the reload after the command, before the window goes
            assertTrue(Files.exists(repo.root.resolve("topic.txt")));
            assertEquals("uncommitted\n", Files.readString(repo.root.resolve("file.txt")));

            repo.git("checkout", "-q", "topic");
            repo.write("topic.txt", "uncommitted topic\n");
            apply(fx, repo, "topic");
            CountDownLatch rebased = watchStatus(
                    fx,
                    text -> text.equals(tr("status.git.rebased", "topic", "main"))
                            || text.equals(tr("status.git.alreadyUpToDate", "main")));
            FxTestSupport.runOnFx(() -> branches.rebase(repo.root, "topic", "main"));
            async.await(rebased, "the rebase");
            async.awaitFx(); // the reload after the command, before the window goes
            assertTrue(Files.exists(repo.root.resolve("main.txt")), "the branch is on top of main");
            assertEquals("uncommitted topic\n", Files.readString(repo.root.resolve("topic.txt")));
        }
    }

    /** B9: Add Worktree with no branch says what is missing instead of git's "unsafe ref ''". */
    @Test
    void aWorktreeWithoutABranchIsRefusedWithAReason(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main");
            CountDownLatch existing = watchStatus(fx, tr("status.git.worktreeNeedsBranch")::equals);
            FxTestSupport.runOnFx(() -> branches.addWorktree(repo.root, "repo-side", " ", false, () -> {}));
            async.await(existing, "the refusal without a branch");
            async.awaitFx(); // the reload after the command, before the window goes
            CountDownLatch created = watchStatus(fx, tr("status.git.worktreeNeedsNewBranch")::equals);
            FxTestSupport.runOnFx(() -> branches.addWorktree(repo.root, "repo-side", "", true, () -> {}));
            async.await(created, "the refusal without a new branch's name");
            async.awaitFx(); // the reload after the command, before the window goes
            assertFalse(Files.exists(dir.toRealPath().resolve("repo-side")));
        }
    }

    /** B12: a branch checked out in another work tree is not deleted, and the refusal says where it is. */
    @Test
    void aBranchCheckedOutInAnotherWorktreeIsNotDeleted(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path side = dir.toRealPath().resolve("repo-side");
        repo.git("worktree", "add", "-q", "-b", "side", side.toString());

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main");
            List<Confirm.Question> asked = new CopyOnWriteArrayList<>();
            Confirm.answerWith(question -> {
                asked.add(question);
                return true;
            });
            CountDownLatch refused =
                    watchStatus(fx, tr("status.git.branchInWorktree", "side", side.toString())::equals);
            FxTestSupport.runOnFx(() -> branches.deleteBranch(repo.root, "main", "side"));
            async.await(refused, "the refusal");
            async.awaitFx(); // the reload after the command, before the window goes
            assertTrue(hasBranch(repo, "side"));
            assertEquals(List.of(), asked, "nothing asked about a delete that cannot happen");
        }
    }

    /** B13: removing or pruning a work tree hands its folder on, so the project opening it made can go. */
    @Test
    void aRemovedOrPrunedWorktreeIsHandedOn(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path removed = dir.toRealPath().resolve("repo-removed");
        Path pruned = dir.toRealPath().resolve("repo-pruned");
        repo.git("worktree", "add", "-q", "-b", "removed", removed.toString());
        repo.git("worktree", "add", "-q", "-b", "pruned", pruned.toString());

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitBranchCoordinator branches = branches(fx, repo, "main");
            List<Path> gone = new CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(() -> branches.setWorktreeGone(gone::add));
            Confirm.answerWith(question -> true);

            List<com.editora.git.GitWorktrees.Worktree> trees = new ArrayList<>();
            CountDownLatch listed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> coordinator(fx).service().worktrees(repo.root, found -> {
                trees.addAll(found);
                listed.countDown();
            }));
            async.await(listed, "the work trees");
            async.awaitFx(); // the reload after the command, before the window goes
            com.editora.git.GitWorktrees.Worktree tree = trees.stream()
                    .filter(t -> t.branch().equals("removed"))
                    .findFirst()
                    .orElseThrow();
            CountDownLatch removal = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> branches.removeWorktree(repo.root, tree, removal::countDown));
            async.await(removal, "the removal");
            async.awaitFx(); // the reload after the command, before the window goes
            assertEquals(List.of(removed), gone);

            try (var paths = Files.walk(pruned)) {
                for (Path path :
                        paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
            CountDownLatch prune = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> branches.pruneWorktrees(repo.root, prune::countDown));
            async.await(prune, "the prune");
            async.awaitFx(); // the reload after the command, before the window goes
            assertEquals(List.of(removed, pruned), gone);
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static GitTestRepo repoWithCommit(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("file.txt", "base\n");
        repo.commitAll("base");
        return repo;
    }

    private static GitCoordinator coordinator(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "git");
    }

    private static void apply(FxWindowFixture fx, GitTestRepo repo, String branch) throws Exception {
        FxTestSupport.runOnFx(() -> coordinator(fx)
                .applyState(new GitService.RepoState(
                        repo.root, new GitStatus(true, branch, "", 0, 0, List.of()), Map.of(), Map.of())));
    }

    private static GitBranchCoordinator branches(FxWindowFixture fx, GitTestRepo repo, String branch) throws Exception {
        GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
        apply(fx, repo, branch);
        return windows.branches;
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
}
