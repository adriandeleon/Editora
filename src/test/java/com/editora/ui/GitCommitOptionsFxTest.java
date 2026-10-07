package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.stage.Window;
import javafx.util.Duration;

import com.editora.git.GitOperation;
import com.editora.git.GitPullMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Commit window's options against real repositories, through a real window: amend, commit-and-push,
 * sign-off, undoing the last commit, the bulk actions and the Space key, the per-file line counts, a pull
 * with uncommitted changes (autostash), the automatic background fetch and the clone form's options.
 */
@Tag("fx")
class GitCommitOptionsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void forgetTheSession() {
        GitCommitCoordinator.clearSessionForTest(); // sign-off and recent messages are per session
    }

    // --- A1: amend ------------------------------------------------------------------------------------

    @Test
    void amendRewordsThePushedLastCommitWithNothingStagedAndWarnsInPlace(@TempDir Path dir) throws Exception {
        GitTestRepo repo = withOrigin(dir);
        Path file = repo.root.resolve("story.txt");
        String before = repo.git("rev-parse", "HEAD").text().strip();

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            assertTrue(FxTestSupport.callOnFx(() -> commitButton(panel).isDisable()), "nothing staged: no commit");
            assertFalse(FxTestSupport.callOnFx(() -> amendBox(panel).isVisible()));

            FxTestSupport.runOnFx(() -> check(panel, "amendCheck").fire());
            GitFeatureFx.await("the commit to amend", () -> amendBox(panel).isVisible());

            assertEquals(
                    "base\n\nwith a body",
                    FxTestSupport.callOnFx(() -> message(panel).getText()),
                    "the empty box is prefilled with the last commit's whole message");
            assertEquals(
                    tr("gitpanel.amending", before.substring(0, 7), "base"),
                    FxTestSupport.callOnFx(() -> label(panel, "amendInfo").getText()));
            assertTrue(
                    FxTestSupport.callOnFx(() -> label(panel, "amendWarning").isVisible()), "HEAD is on origin/main");
            assertEquals(
                    tr("gitpanel.amendPushed", "origin/main"),
                    FxTestSupport.callOnFx(() -> label(panel, "amendWarning").getText()));
            assertNoDialog();
            assertFalse(
                    FxTestSupport.callOnFx(() -> commitButton(panel).isDisable()),
                    "a message-only amend needs nothing staged");
            assertEquals(
                    tr("gitpanel.commitAmend"),
                    FxTestSupport.callOnFx(() -> commitButton(panel).getText()));

            CountDownLatch amended = w.watchStatus(tr("status.git.amended")::equals);
            FxTestSupport.runOnFx(() -> {
                message(panel).setText("base, reworded");
                commitButton(panel).fire();
            });
            async.await(amended, "the amend");

            assertEquals(
                    "base, reworded\n",
                    repo.git("log", "-1", "--format=%B").text().replace("\n\n", "\n"));
            assertEquals("1", repo.git("rev-list", "--count", "HEAD").text().strip(), "replaced, not added");
            assertNotEquals(before, repo.git("rev-parse", "HEAD").text().strip());
            GitFeatureFx.await(
                    "the options to reset", () -> !check(panel, "amendCheck").isSelected());
            assertFalse(FxTestSupport.callOnFx(() -> amendBox(panel).isVisible()));
            assertEquals("", FxTestSupport.callOnFx(() -> message(panel).getText()));
            assertEquals(
                    tr("gitpanel.commit"),
                    FxTestSupport.callOnFx(() -> commitButton(panel).getText()));
        }
    }

    @Test
    void amendIsNotOfferedOnAnUnbornBranchNorDuringAMerge(@TempDir Path dir) throws Exception {
        GitTestRepo unborn = GitTestRepo.init(Files.createDirectory(dir.resolve("a")));
        Path first = unborn.write("first.txt", "one\n");
        GitTestRepo merging = merging(Files.createDirectory(dir.resolve("b")));

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(first);
            GitPanel panel = showCommitWindow(w);

            CountDownLatch refused = w.watchStatus(tr("status.git.nothingToAmend")::equals);
            FxTestSupport.runOnFx(() -> check(panel, "amendCheck").fire());
            async.await(refused, "the refusal on a branch without commits");
            GitFeatureFx.await(
                    "the box to be unticked", () -> !check(panel, "amendCheck").isSelected());
            assertFalse(FxTestSupport.callOnFx(() -> amendBox(panel).isVisible()));

            w.open(merging.root.resolve("story.txt"));
            GitFeatureFx.await("the merge to be seen", () -> w.git.operation().kind() == GitOperation.Kind.MERGE);
            GitFeatureFx.await(
                    "Amend to be disabled", () -> check(panel, "amendCheck").isDisable());
        }
    }

    // --- A2: commit and push --------------------------------------------------------------------------

    @Test
    void commitAndPushCommitsThenPushesAndAFailedCommitPushesNothing(@TempDir Path dir) throws Exception {
        GitTestRepo repo = withOrigin(dir);
        Path file = repo.write("story.txt", "second version\n");
        repo.git("add", "story.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            GitFeatureFx.await("the staged file", () -> !commitButton(panel).isDisable());

            CountDownLatch pushed = w.watchStatus(tr("status.gitDone", tr("gitlabel.push"))::equals);
            FxTestSupport.runOnFx(() -> {
                message(panel).setText("second");
                w.git.commits().commitAndPush();
            });
            async.await(pushed, "the push after the commit");
            assertEquals(
                    "second",
                    repo.git("log", "-1", "--format=%s", "origin/main").text().strip());
            assertEquals(
                    List.of("second"),
                    FxTestSupport.callOnFx(() -> w.git.commits().messageHistory()));

            // A commit the hook rejects: the error is shown, and nothing is pushed.
            GitTestRepo.script(repo.root.resolve(".git/hooks/pre-commit"), "echo no >&2; exit 1");
            repo.write("story.txt", "third version\n");
            repo.git("add", "story.txt");
            FxTestSupport.runOnFx(w.git::refresh);
            GitFeatureFx.await("the staged file", () -> !commitButton(panel).isDisable());
            CountDownLatch failed = w.watchStatus(tr("status.git.commitFailed")::equals);
            FxTestSupport.runOnFx(() -> {
                message(panel).setText("third");
                assertTrue(panel.commitAndPushNow());
            });
            closeDialog(); // the error dialog of the failed commit
            async.await(failed, "the failed commit");
            settle(async, w);
            assertEquals("second", repo.git("log", "-1", "--format=%s").text().strip(), "no commit was made");
            repo.git("fetch", "-q", "origin");
            assertEquals(
                    "second",
                    repo.git("log", "-1", "--format=%s", "origin/main").text().strip());
            assertEquals("third", FxTestSupport.callOnFx(() -> message(panel).getText()), "the message is kept");
        }
    }

    // --- A3: sign-off ---------------------------------------------------------------------------------

    @Test
    void signOffAddsTheTrailerAndIsRememberedForTheRepository(@TempDir Path dir) throws Exception {
        GitTestRepo repo = withOrigin(dir);
        Path file = repo.write("story.txt", "signed\n");
        repo.git("add", "story.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            GitFeatureFx.await("the staged file", () -> !commitButton(panel).isDisable());
            assertFalse(
                    FxTestSupport.callOnFx(() -> check(panel, "signOffCheck").isSelected()));

            CountDownLatch committed = w.watchStatus(tr("status.committed")::equals);
            FxTestSupport.runOnFx(() -> {
                check(panel, "signOffCheck").fire();
                message(panel).setText("signed work");
                commitButton(panel).fire();
            });
            async.await(committed, "the commit");

            String body = repo.git("log", "-1", "--format=%B").text();
            assertTrue(
                    body.contains("Signed-off-by: Editora Test <editora-test@example.invalid>"),
                    "the commit carries the sign-off trailer: " + body);
            settle(async, w);
            assertTrue(FxTestSupport.callOnFx(() -> w.git.commits().signOff()), "remembered for this repository");
            assertTrue(FxTestSupport.callOnFx(() -> check(panel, "signOffCheck").isSelected()), "and still ticked");
        }
    }

    // --- A4: undo last commit -------------------------------------------------------------------------

    @Test
    void undoLastCommitKeepsTheChangesStagedAndPutsTheMessageBack(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", "base\n");
        repo.commitAll("base");
        repo.write("story.txt", "second version\n");
        repo.git("add", "-A");
        repo.git("commit", "-q", "--no-verify", "-m", "second", "-m", "why it changed");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            String shortHash = repo.git("rev-parse", "--short", "HEAD").text().strip();

            CountDownLatch undone = w.watchStatus(tr("status.git.undoCommit.done", shortHash)::equals);
            javafx.application.Platform.runLater(() -> w.git.commits().undoLastCommit());
            String prompt = answerDialog(ButtonBar.ButtonData.OK_DONE);
            async.await(undone, "the undo");

            assertEquals(tr("dialog.undoCommit.message", shortHash, "second"), prompt);
            assertEquals("base", repo.git("log", "-1", "--format=%s").text().strip());
            assertEquals(
                    "story.txt",
                    repo.git("diff", "--cached", "--name-only").text().strip(),
                    "still staged");
            assertEquals("second version\n", Files.readString(file));
            assertEquals(
                    "second\n\nwhy it changed",
                    FxTestSupport.callOnFx(() -> message(panel).getText()));

            // The commit that is left has no parent: refused with a sentence, no dialog.
            repo.git("commit", "-q", "--no-verify", "--amend", "-m", "everything");
            CountDownLatch refused = w.watchStatus(tr("status.git.undoCommit.root")::equals);
            FxTestSupport.runOnFx(() -> w.git.commits().undoLastCommit());
            async.await(refused, "the refusal for a root commit");
            assertNoDialog();
            assertEquals(
                    "everything", repo.git("log", "-1", "--format=%s").text().strip());
        }
    }

    @Test
    void undoingAPushedCommitIsConfirmedInStrongerWordsAndAMergeIsRefused(@TempDir Path dir) throws Exception {
        GitTestRepo repo = withOrigin(dir);
        Path file = repo.write("story.txt", "second version\n");
        repo.commitAll("second");
        repo.git("push", "-q", "origin", "main");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            showCommitWindow(w);
            String shortHash = repo.git("rev-parse", "--short", "HEAD").text().strip();

            javafx.application.Platform.runLater(() -> w.git.commits().undoLastCommit());
            String prompt = answerDialog(ButtonBar.ButtonData.CANCEL_CLOSE);
            assertEquals(tr("dialog.undoCommit.pushed", shortHash, "second", "origin/main"), prompt);
            settle(async, w);
            assertEquals("second", repo.git("log", "-1", "--format=%s").text().strip(), "declined: nothing changed");

            // A merge commit is not undone with a soft reset.
            repo.git("checkout", "-q", "-b", "side", "HEAD~1");
            repo.write("other.txt", "side\n");
            repo.commitAll("side");
            repo.git("checkout", "-q", "main");
            repo.git("merge", "-q", "--no-ff", "--no-edit", "side");
            CountDownLatch refused = w.watchStatus(tr("status.git.undoCommit.merge")::equals);
            FxTestSupport.runOnFx(() -> w.git.commits().undoLastCommit());
            async.await(refused, "the refusal for a merge commit");
            assertNoDialog();
        }
    }

    // --- A5: commit template, recent messages -----------------------------------------------------------

    @Test
    void theCommitTemplateFillsAnEmptyBoxAndItsCommentsAreNotCommitted(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", "base\n");
        repo.commitAll("base");
        String template = "\n# Why is this change needed?\n# (lines starting with # are not committed)\n";
        repo.write(".gitmessage", template);
        repo.git("config", "commit.template", ".gitmessage");
        repo.git("add", "-A");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            GitFeatureFx.await(
                    "the template", () -> template.equals(message(panel).getText()));
            GitFeatureFx.await("the staged file", () -> !commitButton(panel).isDisable());

            assertFalse(FxTestSupport.callOnFx(panel::commitNow), "only the template's comments: nothing to commit");

            CountDownLatch committed = w.watchStatus(tr("status.committed")::equals);
            FxTestSupport.runOnFx(() -> {
                message(panel).setText("Add the template\n" + template + "\nBecause it was missing.\n");
                assertTrue(panel.commitNow());
            });
            async.await(committed, "the commit");

            assertEquals(
                    "Add the template\n\nBecause it was missing.\n",
                    repo.git("log", "-1", "--format=%B").text().replaceAll("\\n+$", "\n"),
                    "the comment lines were stripped, as an editor commit would");
            GitFeatureFx.await(
                    "the template to be offered again",
                    () -> template.equals(message(panel).getText()));
            assertEquals(
                    1,
                    FxTestSupport.callOnFx(
                            () -> w.git.commits().messageHistory().size()));
        }
    }

    // --- A6 + A7: bulk actions, the Space key, line counts ---------------------------------------------

    @Test
    void spaceTogglesTheSelectedRowAndTheCountsFollow(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "one\ntwo\nthree\n");
        repo.commitAll("base");
        repo.write("work.txt", "one\nTWO\nthree\nfour\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            GitFeatureFx.await("the row", () -> groups(panel).equals(List.of("MODIFIED")));
            GitFeatureFx.await(
                    "the line counts of the unstaged change",
                    () -> panel.countsText("MODIFIED", "work.txt").equals("+2 −1"));

            FxTestSupport.runOnFx(() -> {
                select(panel, "work.txt");
                GitFeatureFx.press(tree(panel), KeyCode.SPACE);
            });
            GitFeatureFx.await("Space to stage the row", () -> groups(panel).equals(List.of("STAGED")));
            assertEquals(
                    "work.txt",
                    repo.git("diff", "--cached", "--name-only").text().strip());
            GitFeatureFx.await(
                    "the counts to move to the staged row",
                    () -> panel.countsText("STAGED", "work.txt").equals("+2 −1")
                            && panel.countsText("MODIFIED", "work.txt").isEmpty());

            FxTestSupport.runOnFx(() -> {
                select(panel, "work.txt");
                GitFeatureFx.press(tree(panel), KeyCode.SPACE);
            });
            GitFeatureFx.await("Space to unstage it again", () -> groups(panel).equals(List.of("MODIFIED")));
            assertEquals("", repo.git("diff", "--cached", "--name-only").text().strip());
        }
    }

    @Test
    void unstageAllAndDiscardAllActOnEverything(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "committed\n");
        repo.write("other.txt", "committed\n");
        repo.write("old-name.txt", "renamed later\n");
        repo.commitAll("base");
        repo.write("work.txt", "edited\n");
        repo.write("other.txt", "edited too\n");
        repo.write("added.txt", "new and staged\n");
        repo.git("mv", "old-name.txt", "new-name.txt");
        repo.git("add", "work.txt", "added.txt");
        repo.write("scratch.txt", "untracked\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            GitFeatureFx.await("the status", () -> groups(panel).contains("STAGED"));

            CountDownLatch unstaged = w.watchStatus(tr("status.git.unstagedMany", 3)::equals);
            FxTestSupport.runOnFx(w.git::gitUnstageAll);
            async.await(unstaged, "Unstage All");
            assertEquals("", repo.git("diff", "--cached", "--name-only").text().strip(), "nothing is staged");
            assertEquals("edited\n", Files.readString(file), "the files keep their content");
            assertTrue(Files.exists(repo.root.resolve("new-name.txt")));

            GitFeatureFx.await("the refreshed status", () -> !groups(panel).contains("STAGED"));

            // Stage some of it again, so Discard All has staged, unstaged and untracked paths to deal with.
            repo.git("add", "work.txt", "added.txt");
            FxTestSupport.runOnFx(w.git::refresh);
            GitFeatureFx.await("the status", () -> groups(panel).contains("STAGED"));
            GitCoordinator.DiscardAll plan = FxTestSupport.callOnFx(() -> GitCoordinator.DiscardAll.of(w.git.status()));

            CountDownLatch discarded = w.watchStatus(tr(
                    "status.git.discardedAll", plan.tracked(), plan.untracked().size())::equals);
            javafx.application.Platform.runLater(w.git::gitDiscardAll);
            String prompt = answerDialog(ButtonBar.ButtonData.OK_DONE);
            async.await(discarded, "Discard All");

            assertEquals(
                    tr(
                            "dialog.discardAll.message",
                            plan.tracked(),
                            plan.untracked().size()),
                    prompt,
                    "the confirmation counts what is lost");
            assertTrue(plan.tracked() >= 3, "tracked paths: " + plan);
            assertTrue(plan.untracked().size() >= 1, "untracked paths: " + plan);
            assertEquals("", repo.git("status", "--porcelain=v1").text(), "the working tree is clean");
            assertEquals("committed\n", Files.readString(file));
            assertFalse(Files.exists(repo.root.resolve("scratch.txt")), "untracked files are deleted");
            assertFalse(Files.exists(repo.root.resolve("added.txt")), "a newly added file is gone too");
            assertTrue(Files.exists(repo.root.resolve("old-name.txt")), "an unstaged rename is undone");
        }
    }

    // --- A8: pull with a dirty tree -------------------------------------------------------------------

    @Test
    void aRebasePullCarriesUncommittedChangesAcrossAndSaysSo(@TempDir Path dir) throws Exception {
        GitTestRepo repo = withOrigin(dir);
        pushFromElsewhere(repo, dir, "remote.txt", "from the remote\n", "remote");
        repo.write("local.txt", "committed locally\n");
        repo.commitAll("local");
        Path file = repo.write("story.txt", "base\nnot committed yet\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);

            CountDownLatch pulled = w.watchStatus(tr("status.git.pullAutostashApplied")::equals);
            FxTestSupport.runOnFx(() -> w.git.gitPull(GitPullMode.REBASE));
            async.await(pulled, "the pull, with the stash applied back");
            assertNoDialog();

            assertEquals("local\nremote\nbase\n", repo.git("log", "--format=%s").text(), "rebased onto the remote");
            assertEquals("base\nnot committed yet\n", Files.readString(file), "the uncommitted change is back");
            assertEquals("", repo.git("stash", "list").text(), "and no stash is left behind");
        }
    }

    // --- B9: automatic fetch --------------------------------------------------------------------------

    @Test
    void theAutomaticFetchRunsOnlyWhereTheUserVouchedForTheRepository(@TempDir Path dir) throws Exception {
        GitTestRepo repo = withOrigin(dir);
        Path file = repo.root.resolve("story.txt");
        pushFromElsewhere(repo, dir, "remote.txt", "from the remote\n", "remote");
        String local = repo.git("rev-parse", "origin/main").text().strip();

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitAutoFetch autoFetch = w.git.autoFetch();
            FxTestSupport.runOnFx(() -> {
                autoFetch.setTrust(root -> false);
                autoFetch.setRecentlyActiveForTest(() -> true);
                w.fx.shared.getSettings().setGitAutoFetch(true);
                autoFetch.setPeriodForTest(Duration.millis(150));
            });

            // Not a trusted folder, and no fetch, pull or push by hand yet: the timer ticks and does nothing.
            Thread.sleep(700);
            assertEquals(0, FxTestSupport.callOnFx(autoFetch::runsForTest), "no fetch in an unvouched repository");
            assertEquals(local, repo.git("rev-parse", "origin/main").text().strip());

            FxTestSupport.runOnFx(() -> autoFetch.setTrust(root -> root.equals(repo.root)));
            GitFeatureFx.await("the background fetch", () -> w.git.status().behind() == 1);
            assertNotEquals(local, repo.git("rev-parse", "origin/main").text().strip(), "origin/main moved");
            assertNoDialog();

            // Off again: the timer stops.
            FxTestSupport.runOnFx(() -> {
                w.fx.shared.getSettings().setGitAutoFetch(false);
                autoFetch.apply();
            });
            int runs = FxTestSupport.callOnFx(autoFetch::runsForTest);
            Thread.sleep(500);
            assertEquals(runs, FxTestSupport.callOnFx(autoFetch::runsForTest), "switched off: no further fetch");
        }
    }

    @Test
    void aManualFetchAlsoVouchesForTheRepository(@TempDir Path dir) throws Exception {
        GitTestRepo repo = withOrigin(dir);
        Path file = repo.root.resolve("story.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitAutoFetch autoFetch = w.git.autoFetch();
            FxTestSupport.runOnFx(() -> autoFetch.setTrust(root -> false));
            assertFalse(FxTestSupport.callOnFx(() -> autoFetch.allowedIn(repo.root)));

            CountDownLatch fetched = w.watchStatus(tr("status.gitDone", tr("gitlabel.fetch"))::equals);
            FxTestSupport.runOnFx(() -> w.git.gitSync(tr("gitlabel.fetch"), "fetch", "--all", "--prune"));
            async.await(fetched, "the manual fetch");

            assertTrue(FxTestSupport.callOnFx(() -> autoFetch.allowedIn(repo.root)), "the user fetched here");
            assertFalse(FxTestSupport.callOnFx(() -> autoFetch.allowedIn(dir)), "but not anywhere else");
        }
    }

    // --- B10: clone options ---------------------------------------------------------------------------

    @Test
    void theCloneFormClonesOneBranchShallow(@TempDir Path dir) throws Exception {
        GitTestRepo repo = withOrigin(dir);
        repo.git("checkout", "-q", "-b", "topic");
        repo.write("story.txt", "topic one\n");
        repo.commitAll("topic one");
        repo.write("story.txt", "topic two\n");
        repo.commitAll("topic two");
        repo.git("push", "-q", "origin", "topic");
        Path origin = dir.resolve("origin.git").toAbsolutePath();
        Path destination = dir.resolve("clones").resolve("shallow");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(repo.root.resolve("story.txt"));
            CountDownLatch cloned = w.watchStatus(tr("status.clonedInto", destination)::equals);

            FxTestSupport.runOnFx(w.git::cloneRepo);
            GitFeatureFx.await("the clone form", () -> scene(w).lookup("#clone-url") != null);
            FxTestSupport.runOnFx(() -> {
                Scene scene = scene(w);
                // A file:// URL, so the clone goes through the transport and --depth is honoured.
                ((TextField) scene.lookup("#clone-url")).setText(origin.toUri().toString());
                ((TextField) scene.lookup("#clone-directory")).setText(destination.toString());
                ((TextField) scene.lookup("#clone-branch")).setText("topic");
                TextField depth = (TextField) scene.lookup("#clone-depth");
                Button clone = button(scene, tr("dialog.clone.button"));
                depth.setText("lots");
                assertTrue(clone.isDisable(), "a depth that is not a number cannot be cloned with");
                depth.setText("1");
                assertFalse(clone.isDisable());
                clone.fire();
            });
            async.await(cloned, "the clone");

            GitTestRepo clone = repo; // any repository runs `git -C`
            assertEquals(
                    "topic",
                    clone.git("-C", destination.toString(), "rev-parse", "--abbrev-ref", "HEAD")
                            .text()
                            .strip(),
                    "the branch that was asked for is checked out");
            assertEquals(
                    "1",
                    clone.git("-C", destination.toString(), "rev-list", "--count", "HEAD")
                            .text()
                            .strip(),
                    "a shallow clone: one commit of history");
            assertEquals("topic two\n", Files.readString(destination.resolve("story.txt")));
        }
    }

    // --- repositories -----------------------------------------------------------------------------------

    /** A repository with one commit ("base", with a body) pushed to a bare {@code origin.git} beside it. */
    private static GitTestRepo withOrigin(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("story.txt", "base\n");
        repo.git("add", "-A");
        repo.git("commit", "-q", "--no-verify", "-m", "base", "-m", "with a body");
        Path origin = dir.resolve("origin.git").toAbsolutePath();
        repo.git("init", "-q", "--bare", "-b", "main", origin.toString());
        repo.git("remote", "add", "origin", origin.toString());
        repo.git("push", "-q", "-u", "origin", "main");
        return repo;
    }

    /** Commits {@code file} in a second clone of the origin and pushes it: a commit the repository lacks. */
    private static void pushFromElsewhere(GitTestRepo repo, Path dir, String file, String content, String subject)
            throws Exception {
        Path origin = dir.resolve("origin.git").toAbsolutePath();
        Path other = dir.resolve("other").toAbsolutePath();
        repo.git("clone", "-q", origin.toString(), other.toString());
        Files.writeString(other.resolve(file), content);
        repo.git("-C", other.toString(), "add", "-A");
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
                "-m",
                subject);
        repo.git("-C", other.toString(), "push", "-q", "origin", "main");
    }

    /** A repository stopped in a conflicted {@code git merge feature}. */
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

    /** Opens the Commit window and waits for the repository's status to be in it. */
    private static GitPanel showCommitWindow(GitFeatureFx w) throws Exception {
        GitPanel panel = FxTestSupport.field(w.fx.controller, "gitPanel");
        FxTestSupport.runOnFx(() -> {
            w.git.gitCommitFocus();
            w.git.refresh();
        });
        GitFeatureFx.await(
                "the Commit window",
                () -> panel.isOnScreen() && panel.getChildren().size() > 1);
        return panel;
    }

    private static void settle(AsyncTestScope async, GitFeatureFx w) throws Exception {
        async.awaitFx();
        async.awaitWorker(FxTestSupport.field(w.git.service(), "exec"));
        async.awaitFx();
    }

    private static Scene scene(GitFeatureFx w) {
        return ((Node) FxTestSupport.field(w.fx.controller, "root")).getScene();
    }

    private static Button commitButton(GitPanel panel) {
        return FxTestSupport.field(panel, "commitButton");
    }

    private static TextArea message(GitPanel panel) {
        return FxTestSupport.field(panel, "message");
    }

    private static CheckBox check(GitPanel panel, String field) {
        return FxTestSupport.field(panel, field);
    }

    private static Label label(GitPanel panel, String field) {
        return FxTestSupport.field(panel, field);
    }

    private static Node amendBox(GitPanel panel) {
        return FxTestSupport.field(panel, "amendBox");
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(GitPanel panel) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
    }

    private static List<String> groups(GitPanel panel) {
        List<String> names = new ArrayList<>();
        if (tree(panel).getRoot() != null) {
            for (TreeItem<?> group : tree(panel).getRoot().getChildren()) {
                names.add(String.valueOf(FxTestSupport.call(group.getValue(), "group", new Class<?>[] {})));
            }
        }
        return names;
    }

    /** Selects the file row of {@code path}. */
    private static void select(GitPanel panel, String path) {
        TreeView<Object> tree = tree(panel);
        for (TreeItem<Object> group : tree.getRoot().getChildren()) {
            for (TreeItem<Object> row : group.getChildren()) {
                if (String.valueOf(row.getValue()).contains("path=" + path + ",")) {
                    tree.getSelectionModel().clearSelection();
                    tree.getSelectionModel().select(row);
                    return;
                }
            }
        }
        throw new AssertionError("no row for " + path);
    }

    private static Button button(Scene scene, String text) {
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("no button " + text);
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

    /**
     * Waits (on the test thread) for a dialog, presses its button of {@code buttonData} and returns the
     * dialog's text. The command that shows the dialog must have been started with {@code Platform.runLater}:
     * a modal dialog holds the FX call that opened it until it is answered.
     */
    private static String answerDialog(ButtonBar.ButtonData buttonData) throws Exception {
        GitFeatureFx.await("the dialog", GitCommitOptionsFxTest::dialogShowing);
        AtomicReference<String> prompt = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            for (Window window : new ArrayList<>(Window.getWindows())) {
                if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                    continue;
                }
                pane.getButtonTypes().stream()
                        .filter(type -> type.getButtonData() == buttonData)
                        .findFirst()
                        .ifPresent(type -> {
                            prompt.set(pane.getContentText());
                            Button button = (Button) pane.lookupButton(type);
                            if (buttonData == ButtonBar.ButtonData.OK_DONE) {
                                assertTrue(
                                        button.getStyleClass().contains("danger"),
                                        "the confirming button is danger-styled");
                            }
                            button.fire();
                        });
            }
        });
        assertTrue(prompt.get() != null, "the dialog has a " + buttonData + " button");
        return prompt.get();
    }

    /** Waits for a dialog (an error report) and dismisses it. */
    private static void closeDialog() throws Exception {
        GitFeatureFx.await("the dialog", GitCommitOptionsFxTest::dialogShowing);
        FxTestSupport.runOnFx(() -> {
            for (Window window : new ArrayList<>(Window.getWindows())) {
                if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane) {
                    window.hide();
                }
            }
        });
    }
}
