package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitOperation;
import com.editora.git.GitPullMode;
import com.editora.git.GitService;
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
 * W1 + W2 against real repositories, through a real window: a diverged branch has a way forward (pull with
 * rebase or merge), an operation that stops on conflicts ends in the Commit window's banner rather than an
 * error dialog, and it can be continued, skipped or aborted from there.
 */
@Tag("fx")
class GitOperationsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- pull (rebase) → conflict → banner → resolve → continue → clean -------------------------------

    @Test
    void aRebasePullThatConflictsIsResolvedAndContinuedFromTheBanner(@TempDir Path dir) throws Exception {
        GitTestRepo repo = diverged(dir);
        Path story = repo.root.resolve("story.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, story);
            GitCoordinator git = activate(async, fx, repo.root);
            assertFalse(git.operationInProgress());
            assertFalse(FxTestSupport.callOnFx(() -> banner(fx).isVisible()), "nothing in progress: no banner");
            assertFalse(operationCommandsEnabled(fx), "nothing to continue, skip or abort");

            // Pull with rebase: the local commit conflicts with the remote one.
            CountDownLatch stopped = watchStatus(fx, tr("status.git.stoppedOnConflicts")::equals);
            FxTestSupport.runOnFx(() -> git.gitPull(GitPullMode.REBASE));
            async.await(stopped, "the pull stopping on the conflict");
            assertNoDialog();
            awaitThat(() -> git.operation().kind() == GitOperation.Kind.REBASE, "the rebase to be detected");

            assertTrue(operationCommandsEnabled(fx), "the palette and VCS menu light the three commands up");

            // The state is visible: status bar, banner, Conflicts group, Commit blocked with a reason.
            assertTrue(FxTestSupport.callOnFx(() -> gitSegment(fx).getText())
                    .endsWith(" · " + tr("statusbar.git.rebasing")));
            assertTrue(FxTestSupport.callOnFx(() -> banner(fx).isVisible()));
            assertEquals(
                    tr(
                            "gitpanel.operation.conflicts",
                            tr("gitpanel.operation.step", tr("git.operation.rebase"), 1, 1),
                            1),
                    FxTestSupport.callOnFx(() -> bannerLabel(fx).getText()));
            assertTrue(FxTestSupport.callOnFx(
                    () -> bannerButton(fx, "continueButton").isVisible()));
            assertTrue(
                    FxTestSupport.callOnFx(() -> bannerButton(fx, "skipButton").isVisible()));
            assertTrue(
                    FxTestSupport.callOnFx(() -> bannerButton(fx, "abortButton").isVisible()));
            assertEquals(List.of("CONFLICTS"), FxTestSupport.callOnFx(() -> groups(fx)));
            assertTrue(FxTestSupport.callOnFx(() -> commitButton(fx).isDisable()));
            assertEquals(
                    tr("gitpanel.commitBlockedConflicts", 1),
                    FxTestSupport.callOnFx(() -> panel(fx).commitBlockedReason()));
            awaitBufferContains(buffer, "<<<<<<<"); // the open buffer shows what the pull wrote

            // Continue is refused while the file is unmerged — with a sentence, not git's transcript.
            CountDownLatch refused =
                    watchStatus(fx, tr("status.git.cannotContinueConflicts", tr("git.operation.rebase"), 1)::equals);
            FxTestSupport.runOnFx(() -> bannerButton(fx, "continueButton").fire());
            async.await(refused, "Continue being refused");
            assertNoDialog();
            assertTrue(Files.isDirectory(repo.root.resolve(".git/rebase-merge")), "still rebasing");

            // Resolve by taking the commit being replayed ("theirs" during a rebase), then continue.
            CountDownLatch resolved = watchStatus(fx, tr("status.git.conflictResolved", "story.txt")::equals);
            CountDownLatch accepted = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, accepted, null));
                git.acceptConflictSide(List.of("story.txt"), false);
            });
            async.await(accepted, "the accept-theirs confirmation");
            async.await(resolved, "the conflict being resolved");
            awaitThat(() -> com.editora.git.GitConflicts.unmerged(git.status()).isEmpty(), "no unmerged files");
            assertEquals(
                    tr(
                            "gitpanel.operation.inProgress",
                            tr("gitpanel.operation.step", tr("git.operation.rebase"), 1, 1)),
                    FxTestSupport.callOnFx(() -> bannerLabel(fx).getText()),
                    "still in progress, nothing left to resolve");

            CountDownLatch continued =
                    watchStatus(fx, tr("status.git.operationContinued", tr("git.operation.rebase"))::equals);
            FxTestSupport.runOnFx(() -> bannerButton(fx, "continueButton").fire());
            async.await(continued, "the rebase continuing");
            assertNoDialog();
            awaitThat(() -> !git.operation().inProgress(), "the rebase to be over");

            assertEquals("", repo.git("status", "--porcelain=v1").text(), "clean");
            assertEquals("local\nremote\nbase\n", repo.git("log", "--format=%s").text(), "replayed on the remote");
            assertEquals("local version\n", Files.readString(story));
            assertFalse(FxTestSupport.callOnFx(() -> banner(fx).isVisible()), "the banner is gone");
            assertEquals(
                    "⎇ main  ↑1", FxTestSupport.callOnFx(() -> gitSegment(fx).getText()));
            awaitBufferContains(buffer, "local version");
        }
    }

    @Test
    void abortingAConflictedRebasePullRestoresThePrePullState(@TempDir Path dir) throws Exception {
        GitTestRepo repo = diverged(dir);
        Path story = repo.root.resolve("story.txt");
        String headBefore = repo.git("rev-parse", "HEAD").text();

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, story);
            GitCoordinator git = activate(async, fx, repo.root);
            CountDownLatch stopped = watchStatus(fx, tr("status.git.stoppedOnConflicts")::equals);
            FxTestSupport.runOnFx(() -> git.gitPull(GitPullMode.REBASE));
            async.await(stopped, "the pull stopping on the conflict");
            awaitThat(() -> git.operation().kind() == GitOperation.Kind.REBASE, "the rebase to be detected");
            assertNotEquals(headBefore, repo.git("rev-parse", "HEAD").text(), "HEAD is detached on the remote commit");

            // Cancel is the default: nothing happens.
            CountDownLatch cancelled = new CountDownLatch(1);
            AtomicReference<String> prompt = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE, cancelled, prompt));
                git.abortOperation();
            });
            async.await(cancelled, "the abort confirmation");
            assertEquals(tr("dialog.abortOperation.rebase"), prompt.get(), "it says what will be lost");
            settleGit(async, fx);
            assertTrue(Files.isDirectory(repo.root.resolve(".git/rebase-merge")), "cancelled: still rebasing");

            CountDownLatch aborted =
                    watchStatus(fx, tr("status.git.operationAborted", tr("git.operation.rebase"))::equals);
            CountDownLatch confirmed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed, null));
                bannerButton(fx, "abortButton").fire();
            });
            async.await(confirmed, "the abort confirmation");
            async.await(aborted, "the abort");
            awaitThat(() -> !git.operation().inProgress(), "the rebase to be over");

            assertEquals(headBefore, repo.git("rev-parse", "HEAD").text(), "back on the pre-pull commit");
            assertEquals("main\n", repo.git("rev-parse", "--abbrev-ref", "HEAD").text());
            assertEquals("", repo.git("status", "--porcelain=v1").text());
            assertEquals("local version\n", Files.readString(story));
            assertFalse(FxTestSupport.callOnFx(() -> banner(fx).isVisible()));
        }
    }

    // --- a fast-forward-only pull of a diverged branch offers the way forward --------------------------

    @Test
    void aDivergedFastForwardPullOffersRebaseOrMergeAndTheMergeIsConcludedWithGitsMessage(@TempDir Path dir)
            throws Exception {
        GitTestRepo repo = diverged(dir);
        Path story = repo.root.resolve("story.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, story);
            GitCoordinator git = activate(async, fx, repo.root);
            assertEquals(GitPullMode.FF_ONLY, git.pullMode(), "the default is what pull always did");

            // The real dialog, cancelled: it explains the two options and how to make one permanent.
            CountDownLatch cancelled = new CountDownLatch(1);
            AtomicReference<String> prompt = new AtomicReference<>();
            CountDownLatch declined = watchStatus(fx, tr("status.git.pullDiverged")::equals);
            FxTestSupport.runOnFx(git::gitPull);
            awaitThat(() -> dialogShowing(), "the diverged-pull offer");
            FxTestSupport.runOnFx(() -> pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE, cancelled, prompt));
            async.await(cancelled, "cancelling the offer");
            async.await(declined, "the pull being left alone");
            assertEquals(tr("dialog.pullDiverged.message"), prompt.get());
            settleGit(async, fx);
            assertEquals("local\nbase\n", repo.git("log", "--format=%s").text(), "nothing was pulled");

            // Choosing Merge: the pull is run again as a merge, and stops on the conflict.
            AtomicInteger asked = new AtomicInteger();
            FxTestSupport.runOnFx(() -> git.divergedPullChooser = () -> {
                asked.incrementAndGet();
                return GitPullMode.MERGE;
            });
            CountDownLatch stopped = watchStatus(fx, tr("status.git.stoppedOnConflicts")::equals);
            FxTestSupport.runOnFx(git::gitPull);
            async.await(stopped, "the merge stopping on the conflict");
            assertNoDialog();
            assertEquals(1, asked.get());
            awaitThat(() -> git.operation().kind() == GitOperation.Kind.MERGE, "the merge to be detected");
            assertTrue(FxTestSupport.callOnFx(() -> gitSegment(fx).getText())
                    .endsWith(" · " + tr("statusbar.git.merging")));
            assertFalse(
                    FxTestSupport.callOnFx(() -> bannerButton(fx, "skipButton").isVisible()), "a merge has no skip");

            // The commit box holds git's prepared message, without the "# Conflicts:" comment block.
            String prepared = FxTestSupport.callOnFx(() -> message(fx).getText());
            assertTrue(prepared.startsWith("Merge branch 'main' of "), prepared);
            assertFalse(prepared.contains("#"), prepared);

            CountDownLatch resolved = watchStatus(fx, tr("status.git.conflictResolved", "story.txt")::equals);
            CountDownLatch accepted = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, accepted, null));
                git.acceptConflictSide(List.of("story.txt"), true);
            });
            async.await(accepted, "the accept-ours confirmation");
            async.await(resolved, "the conflict being resolved");
            awaitThat(() -> com.editora.git.GitConflicts.unmerged(git.status()).isEmpty(), "no unmerged files");
            // Ours is identical to HEAD, so nothing is staged — and the merge can still be concluded.
            assertFalse(FxTestSupport.callOnFx(() -> commitButton(fx).isDisable()), "Commit concludes the merge");

            CountDownLatch committed = watchStatus(fx, tr("status.committed")::equals);
            FxTestSupport.runOnFx(git::continueOperation);
            async.await(committed, "the merge commit");
            awaitThat(() -> !git.operation().inProgress(), "the merge to be over");

            assertEquals(prepared, repo.git("log", "-1", "--format=%B").text().strip(), "git's message, no comments");
            assertEquals(
                    "1\n", repo.git("rev-list", "--count", "--merges", "HEAD").text(), "a merge commit");
            assertEquals("", repo.git("status", "--porcelain=v1").text());
            assertEquals("local version\n", Files.readString(story));
            assertEquals("", FxTestSupport.callOnFx(() -> message(fx).getText()), "the committed message is cleared");
            assertFalse(FxTestSupport.callOnFx(() -> banner(fx).isVisible()));
        }
    }

    // --- cherry-pick / revert / stash pop that stop on a conflict ---------------------------------------

    @Test
    void aConflictedCherryPickEndsInTheBannerAndCanBeSkipped(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path story = repo.write("story.txt", "base\n");
        repo.commitAll("base");
        repo.git("checkout", "-q", "-b", "side");
        repo.write("story.txt", "side version\n");
        repo.commitAll("side");
        String side = repo.git("rev-parse", "HEAD").text().strip();
        repo.git("checkout", "-q", "main");
        repo.write("story.txt", "main version\n");
        repo.commitAll("main change");
        String headBefore = repo.git("rev-parse", "HEAD").text();

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, story);
            GitCoordinator git = activate(async, fx, repo.root);

            // The path the Git Log's Cherry-Pick takes.
            CountDownLatch stopped = watchStatus(fx, tr("status.git.stoppedOnConflicts")::equals);
            FxTestSupport.runOnFx(() -> git.mutateWorkingTree(repo.root, "picked", null, "cherry-pick", side));
            async.await(stopped, "the cherry-pick stopping on the conflict");
            assertNoDialog();
            awaitThat(() -> git.operation().kind() == GitOperation.Kind.CHERRY_PICK, "the cherry-pick to be detected");
            assertTrue(FxTestSupport.callOnFx(() -> banner(fx).isVisible()));
            assertTrue(
                    FxTestSupport.callOnFx(() -> gitSegment(fx).getText()).endsWith(tr("statusbar.git.cherryPicking")));

            CountDownLatch skipped =
                    watchStatus(fx, tr("status.git.operationSkipped", tr("git.operation.cherryPick"))::equals);
            CountDownLatch confirmed = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.OK_DONE, confirmed, null));
                git.skipOperation();
            });
            async.await(confirmed, "the skip confirmation");
            async.await(skipped, "the skip");
            awaitThat(() -> !git.operation().inProgress(), "the cherry-pick to be over");
            assertEquals(headBefore, repo.git("rev-parse", "HEAD").text());
            assertEquals("", repo.git("status", "--porcelain=v1").text());
            assertEquals("main version\n", Files.readString(story));
        }
    }

    @Test
    void aConflictedRevertEndsInTheBannerAndIsContinuedAfterResolving(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path story = repo.write("story.txt", "one\n");
        repo.commitAll("base");
        repo.write("story.txt", "two\n");
        repo.commitAll("second");
        String second = repo.git("rev-parse", "HEAD").text().strip();
        repo.write("story.txt", "three\n");
        repo.commitAll("third");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            EditorBuffer buffer = open(fx.controller, story);
            GitCoordinator git = activate(async, fx, repo.root);

            CountDownLatch stopped = watchStatus(fx, tr("status.git.stoppedOnConflicts")::equals);
            FxTestSupport.runOnFx(
                    () -> git.mutateWorkingTree(repo.root, "reverted", null, "revert", "--no-edit", second));
            async.await(stopped, "the revert stopping on the conflict");
            assertNoDialog();
            awaitThat(() -> git.operation().kind() == GitOperation.Kind.REVERT, "the revert to be detected");
            awaitBufferContains(buffer, "<<<<<<<");

            // Resolved in the three-way resolver: applying a finished resolution saves and stages the file.
            CountDownLatch staged = watchStatus(fx, tr("status.git.conflictResolved", "story.txt")::equals);
            DiffCoordinator diffs = FxTestSupport.field(fx.controller, "diffCoordinator");
            FxTestSupport.runOnFx(
                    () -> assertTrue(diffs.applyMergeResolution(buffer, buffer.getContent(), "resolved by hand\n")));
            async.await(staged, "the resolution being staged");
            assertEquals("resolved by hand\n", Files.readString(story), "saved");
            assertEquals(
                    "M  story.txt\n", repo.git("status", "--porcelain=v1").text(), "and staged: no longer unmerged");
            awaitThat(() -> com.editora.git.GitConflicts.unmerged(git.status()).isEmpty(), "no unmerged files");

            CountDownLatch continued =
                    watchStatus(fx, tr("status.git.operationContinued", tr("git.operation.revert"))::equals);
            FxTestSupport.runOnFx(git::continueOperation);
            async.await(continued, "the revert continuing");
            assertNoDialog();
            awaitThat(() -> !git.operation().inProgress(), "the revert to be over");
            assertEquals("", repo.git("status", "--porcelain=v1").text());
            assertTrue(repo.git("log", "-1", "--format=%s").text().startsWith("Revert \"second\""));
        }
    }

    @Test
    void aStashPopThatConflictsShowsTheConflictsInsteadOfAnErrorDialog(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path story = repo.write("story.txt", "base\n");
        repo.commitAll("base");
        repo.write("story.txt", "stashed version\n");
        repo.git("stash", "push", "-q");
        repo.write("story.txt", "committed version\n");
        repo.commitAll("change");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, story);
            GitCoordinator git = activate(async, fx, repo.root);

            CountDownLatch stopped = watchStatus(fx, tr("stash.conflict.pop")::equals);
            FxTestSupport.runOnFx(git::gitStashPop);
            async.await(stopped, "the pop stopping on the conflict");
            assertNoDialog();
            awaitThat(() -> !com.editora.git.GitConflicts.unmerged(git.status()).isEmpty(), "the unmerged file");

            // No operation to continue or abort — the banner says what to do and offers neither.
            assertFalse(git.operationInProgress());
            assertTrue(FxTestSupport.callOnFx(() -> banner(fx).isVisible()));
            assertEquals(
                    tr("gitpanel.conflictsOnly", 1),
                    FxTestSupport.callOnFx(() -> bannerLabel(fx).getText()));
            assertFalse(FxTestSupport.callOnFx(
                    () -> bannerButton(fx, "continueButton").isVisible()));
            assertFalse(
                    FxTestSupport.callOnFx(() -> bannerButton(fx, "abortButton").isVisible()));
            assertEquals(List.of("CONFLICTS"), FxTestSupport.callOnFx(() -> groups(fx)));
            assertTrue(FxTestSupport.callOnFx(() -> commitButton(fx).isDisable()));
            assertTrue(repo.git("stash", "list").text().contains("stash@{0}"), "git keeps the stash");

            CountDownLatch none = watchStatus(fx, tr("status.git.noOperation")::equals);
            FxTestSupport.runOnFx(git::abortOperation);
            async.await(none, "abort having nothing to abort");
            assertNoDialog();
        }
    }

    // --- staging a file that still has conflict markers -------------------------------------------------

    @Test
    void stagingAConflictedFileThatStillHasMarkersAsksFirst(@TempDir Path dir) throws Exception {
        GitTestRepo repo = merging(dir);
        Path story = repo.root.resolve("story.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, story);
            GitCoordinator git = activate(async, fx, repo.root);
            assertEquals(GitOperation.Kind.MERGE, git.operation().kind());

            CountDownLatch cancelled = new CountDownLatch(1);
            AtomicReference<String> prompt = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE, cancelled, prompt));
                git.gitStagePaths(List.of("story.txt"));
            });
            async.await(cancelled, "the staging confirmation");
            assertEquals(tr("dialog.stageConflict.message", "story.txt"), prompt.get());
            settleGit(async, fx);
            assertEquals("UU story.txt\n", repo.git("status", "--porcelain=v1").text(), "declined: still unmerged");

            // Stage All asks as well.
            CountDownLatch cancelledAll = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> {
                Platform.runLater(() -> pressDialog(ButtonBar.ButtonData.CANCEL_CLOSE, cancelledAll, null));
                git.gitStageAll();
            });
            async.await(cancelledAll, "the Stage All confirmation");
            settleGit(async, fx);
            assertEquals("UU story.txt\n", repo.git("status", "--porcelain=v1").text());

            // With the markers gone there is nothing to ask.
            Files.writeString(story, "resolved\n");
            CountDownLatch staged = watchStatus(fx, tr("status.git.staged", "story.txt")::equals);
            FxTestSupport.runOnFx(() -> git.gitStagePaths(List.of("story.txt")));
            async.await(staged, "staging the resolved file");
            assertNoDialog();
            assertEquals("M  story.txt\n", repo.git("status", "--porcelain=v1").text());
        }
    }

    @Test
    void enterOnAConflictRowOpensTheFileInTheThreeWayResolver(@TempDir Path dir) throws Exception {
        GitTestRepo repo = merging(dir);

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            activate(async, fx, repo.root); // story.txt is not open in any tab
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");

            FxTestSupport.runOnFx(() -> {
                @SuppressWarnings("unchecked")
                TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel(fx), "tree");
                TreeItem<Object> conflict =
                        tree.getRoot().getChildren().get(0).getChildren().get(0);
                tree.getSelectionModel().clearSelection();
                tree.getSelectionModel().select(conflict);
                tree.fireEvent(new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "",
                        "",
                        javafx.scene.input.KeyCode.ENTER,
                        false,
                        false,
                        false,
                        false));
            });

            awaitThat(
                    () -> area.selectedTab() != null && area.selectedTab().getUserData() instanceof MergeViewerPane,
                    "the resolver tab for the conflicted file");
            assertEquals("UU story.txt\n", repo.git("status", "--porcelain=v1").text(), "opening resolves nothing");
        }
    }

    // --- detection --------------------------------------------------------------------------------------

    @Test
    void theOperationIsReadFromTheGitDirectoryOfALinkedWorktree(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("story.txt", "base\n");
        repo.commitAll("base");
        repo.git("checkout", "-q", "-b", "feature");
        repo.write("story.txt", "feature version\n");
        repo.commitAll("feature");
        repo.git("checkout", "-q", "main");
        Path linked = dir.resolve("linked").toAbsolutePath();
        repo.git("worktree", "add", "-q", "-b", "work", linked.toString());
        Files.writeString(linked.resolve("story.txt"), "work version\n");
        repo.git("-C", linked.toString(), "commit", "-q", "-am", "work");
        assertEquals(1, repo.tryGit("-C", linked.toString(), "merge", "feature").exit(), "the merge conflicts");
        // A linked work tree's .git is a file; its MERGE_HEAD lives under the main repository's git dir.
        assertTrue(Files.isRegularFile(linked.resolve(".git")));

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            GitService.RepoState inLinked = status(async, service, linked);
            assertEquals(GitOperation.Kind.MERGE, inLinked.operation().kind());
            assertEquals(
                    "Merge branch 'feature' into work", inLinked.operation().message());
            assertEquals(
                    1, com.editora.git.GitConflicts.unmerged(inLinked.status()).size());

            GitService.RepoState inMain = status(async, service, repo.root);
            assertFalse(inMain.operation().inProgress(), "the main work tree is not merging");

            // Later refreshes read the state files; the git directory is resolved once per root.
            repo.git("-C", linked.toString(), "merge", "--abort");
            assertFalse(status(async, service, linked).operation().inProgress());
        }
    }

    // --- E5 follow-up: a repository git refuses to work in ----------------------------------------------

    @Test
    void aRefusedRepositoryShowsGitsReasonInTheStatusBarAndTheCommitWindow() throws Exception {
        String reason = "detected dubious ownership in repository at '/mnt/share/project'";
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitCoordinator git = FxTestSupport.field(fx.controller, "git");
            FxTestSupport.runOnFx(() -> git.applyState(GitService.RepoState.refused(reason)));

            assertEquals(
                    tr("statusbar.gitRefused"),
                    FxTestSupport.callOnFx(() -> gitSegment(fx).getText()));
            assertEquals(
                    tr("statusbar.tip.gitRefused", reason),
                    FxTestSupport.callOnFx(() -> gitSegment(fx).getTooltip().getText()));
            Label placeholder = FxTestSupport.field(panel(fx), "placeholder");
            assertEquals(tr("gitpanel.refused", reason), FxTestSupport.callOnFx(placeholder::getText));
            Button clone = FxTestSupport.field(panel(fx), "cloneButton");
            assertFalse(FxTestSupport.callOnFx(clone::isVisible), "there is a repository here: do not offer to clone");

            // An ordinary folder afterwards goes back to "No VCS".
            FxTestSupport.runOnFx(() -> git.applyState(GitService.RepoState.NONE));
            assertEquals(
                    tr("statusbar.noVcs"),
                    FxTestSupport.callOnFx(() -> gitSegment(fx).getText()));
            assertEquals(tr("gitpanel.placeholder"), FxTestSupport.callOnFx(placeholder::getText));
            assertTrue(FxTestSupport.callOnFx(clone::isVisible));
        }
    }

    // --- fixtures ---------------------------------------------------------------------------------------

    /**
     * A repository on {@code main} tracking {@code origin/main}, one commit ahead ("local") and one behind
     * ("remote"), both changing {@code story.txt}: a pull cannot fast-forward, and rebasing or merging
     * conflicts.
     */
    private static GitTestRepo diverged(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("story.txt", "base\n");
        repo.commitAll("base");
        Path origin = dir.resolve("origin.git").toAbsolutePath();
        repo.git("init", "-q", "--bare", "-b", "main", origin.toString());
        repo.git("remote", "add", "origin", origin.toString());
        repo.git("push", "-q", "-u", "origin", "main");
        Path other = dir.resolve("other").toAbsolutePath();
        repo.git("clone", "-q", origin.toString(), other.toString());
        Files.writeString(other.resolve("story.txt"), "remote version\n");
        repo.git(
                "-C",
                other.toString(),
                "-c",
                "user.name=Someone Else",
                "-c",
                "user.email=else@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "-q",
                "--no-verify",
                "-am",
                "remote");
        repo.git("-C", other.toString(), "push", "-q", "origin", "main");
        repo.write("story.txt", "local version\n");
        repo.commitAll("local");
        return repo;
    }

    /** A repository stopped in a conflicted {@code git merge feature}, with markers in {@code story.txt}. */
    private static GitTestRepo merging(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("story.txt", "base\n");
        repo.commitAll("base");
        repo.git("checkout", "-q", "-b", "feature");
        repo.write("story.txt", "feature version\n");
        repo.commitAll("feature");
        repo.git("checkout", "-q", "main");
        repo.write("story.txt", "main version\n");
        repo.commitAll("main change");
        assertEquals(1, repo.tryGit("merge", "feature").exit(), "the merge conflicts");
        return repo;
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private static GitService.RepoState status(AsyncTestScope async, GitService service, Path context)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<GitService.RepoState> state = new AtomicReference<>();
        service.status(context, answered -> {
            state.set(answered);
            done.countDown();
        });
        async.await(done, "git status of " + context);
        return state.get();
    }

    /** Whether the palette / VCS menu currently enable the continue, skip and abort commands. */
    private static boolean operationCommandsEnabled(FxWindowFixture fx) throws Exception {
        Chrome.PaletteContext context = FxTestSupport.callOnFx(
                () -> (Chrome.PaletteContext) FxTestSupport.call(fx.controller, "paletteContext", new Class<?>[] {}));
        return Chrome.contextEnabled("git.continueOperation", context)
                && Chrome.contextEnabled("git.skipOperation", context)
                && Chrome.contextEnabled("git.abortOperation", context);
    }

    private static GitPanel panel(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "gitPanel");
    }

    private static Node banner(FxWindowFixture fx) {
        return FxTestSupport.field(panel(fx), "operationBanner");
    }

    private static Label bannerLabel(FxWindowFixture fx) {
        return FxTestSupport.field(panel(fx), "operationLabel");
    }

    private static Button bannerButton(FxWindowFixture fx, String field) {
        return FxTestSupport.field(panel(fx), field);
    }

    private static Button commitButton(FxWindowFixture fx) {
        return FxTestSupport.field(panel(fx), "commitButton");
    }

    private static TextArea message(FxWindowFixture fx) {
        return FxTestSupport.field(panel(fx), "message");
    }

    private static Label gitSegment(FxWindowFixture fx) {
        StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.field(statusBar, "git");
    }

    /** The names of the groups the Commit window lists, top to bottom. */
    private static List<String> groups(FxWindowFixture fx) {
        TreeView<?> tree = FxTestSupport.field(panel(fx), "tree");
        List<String> names = new ArrayList<>();
        if (tree.getRoot() != null) {
            for (TreeItem<?> group : tree.getRoot().getChildren()) {
                names.add(String.valueOf(FxTestSupport.call(group.getValue(), "group", new Class<?>[] {})));
            }
        }
        return names;
    }

    private static void awaitThat(Callable<Boolean> condition, String description) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (FxTestSupport.callOnFx(condition)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static void awaitBufferContains(EditorBuffer buffer, String expected) throws Exception {
        awaitThat(() -> buffer.getContent().contains(expected), "the buffer to contain " + expected);
    }

    /** Lets a Git refresh in flight finish and reach the FX thread. */
    private static void settleGit(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        GitCoordinator git = FxTestSupport.field(fx.controller, "git");
        async.awaitFx();
        async.awaitWorker(FxTestSupport.field(git.service(), "exec"));
        async.awaitFx();
    }

    /** Applies the repository's real status, as a refresh for a tab inside it would. */
    private static GitCoordinator activate(AsyncTestScope async, FxWindowFixture fx, Path root) throws Exception {
        GitCoordinator git = FxTestSupport.field(fx.controller, "git");
        CountDownLatch applied = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> git.service().status(root, state -> {
            git.applyState(state);
            applied.countDown();
        }));
        async.await(applied, "repository state");
        return git;
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

    private static boolean dialogShowing() {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoDialog() throws Exception {
        FxTestSupport.runOnFx(() -> assertFalse(dialogShowing(), "no confirmation or error dialog was shown"));
    }

    private static void pressDialog(
            ButtonBar.ButtonData buttonData, CountDownLatch pressed, AtomicReference<String> prompt) {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            pane.getButtonTypes().stream()
                    .filter(type -> type.getButtonData() == buttonData)
                    .findFirst()
                    .ifPresent(type -> {
                        if (prompt != null) {
                            prompt.set(pane.getContentText());
                        }
                        Button button = (Button) pane.lookupButton(type);
                        if (buttonData == ButtonBar.ButtonData.OK_DONE) {
                            assertTrue(
                                    button.getStyleClass().contains("danger"),
                                    "the confirming button is danger-styled");
                        }
                        pressed.countDown();
                        button.fire();
                    });
        }
    }
}
