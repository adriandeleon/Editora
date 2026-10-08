package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.input.Clipboard;

import com.editora.git.StashOptions;
import com.editora.git.StashParser.StashEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stash commands as the user reaches them — the stash form, the apply and drop pickers, a branch from a
 * stash — and what each says when the stash cannot be applied or the list it was picked from is out of date.
 */
@Tag("fx")
class GitStashCommandsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Window(GitFeatureFx w, GitStashCoordinator stashes, GitTestRepo repo, Path file) {}

    private static Window window(AsyncTestScope async, Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("file.txt", "one\ntwo\n");
        repo.commitAll("base");
        GitFeatureFx w = GitFeatureFx.create(async);
        w.open(file);
        return new Window(w, w.git.stashes(), repo, file);
    }

    private static void awaitMessage(AsyncTestScope async, Window win, String message) throws Exception {
        OverlayTestKit.await(
                async, "\"" + message + "\"", () -> win.w().messages().contains(message));
    }

    private static List<StashEntry> stashes(AsyncTestScope async, Window win) throws Exception {
        CompletableFuture<List<StashEntry>> listed = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> win.w().git.service().stashList(win.repo().root, listed::complete));
        return async.await(listed);
    }

    private static long stashCount(Window win) throws Exception {
        return win.repo().git("stash", "list").text().lines().count();
    }

    /** Runs {@code action} and returns the error dialog headed {@code header} that it ends in. */
    private static OverlayTestKit.Shown errorDialog(AsyncTestScope async, String header, Runnable action)
            throws Exception {
        AtomicReference<OverlayTestKit.Shown> error = new AtomicReference<>();
        CountDownLatch shown = OverlayTestKit.answerDialog(
                async, pane -> header.equals(pane.getHeaderText()), ButtonBar.ButtonData.OK_DONE, error);
        FxTestSupport.runOnFx(action);
        async.await(shown, "the error dialog \"" + header + "\"");
        return error.get();
    }

    @Test
    void theStashFormTakesAMessageAndItsOptionsAndSaysWhenThereIsNothingToStash(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, dir);

            // A clean tree: git has nothing to save, and that is what the user is told.
            FxTestSupport.runOnFx(() -> win.stashes().stash(win.repo().root, StashOptions.DEFAULT));
            awaitMessage(async, win, tr("stash.nothing"));
            assertEquals(0, stashCount(win));

            win.repo().write("file.txt", "one\ntwo\nedited\n");
            win.repo().write("brand-new.txt", "untracked\n");
            FxTestSupport.runOnFx(win.stashes()::promptStash);
            assertEquals(
                    tr("stash.prompt.title"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            List<CheckBox> options = FxTestSupport.callOnFx(
                    () -> OverlayTestKit.formChecks(win.w().scene()));
            assertEquals(
                    List.of(tr("stash.option.untracked"), tr("stash.option.staged"), tr("stash.option.keepIndex")),
                    options.stream().map(CheckBox::getText).toList());

            // Staged-only excludes the other two; switching it off gives them back.
            FxTestSupport.runOnFx(() -> options.get(1).setSelected(true));
            assertTrue(FxTestSupport.callOnFx(
                    () -> options.get(0).isDisable() && options.get(2).isDisable()));
            FxTestSupport.runOnFx(() -> options.get(1).setSelected(false));
            assertFalse(FxTestSupport.callOnFx(
                    () -> options.get(0).isDisable() || options.get(2).isDisable()));

            FxTestSupport.runOnFx(() -> {
                options.get(0).setSelected(true);
                OverlayTestKit.submitForm(win.w().scene(), "  half-done idea  ");
            });
            awaitMessage(async, win, tr("stash.pushed"));
            assertEquals("one\ntwo\n", Files.readString(win.file()));
            assertFalse(Files.exists(win.repo().root.resolve("brand-new.txt")), "the untracked file was stashed too");
            List<StashEntry> listed = stashes(async, win);
            assertEquals(1, listed.size());
            assertTrue(
                    listed.get(0).subject().endsWith("half-done idea"),
                    listed.get(0).subject());
        }
    }

    @Test
    void theApplyAndDropPickersListTheStashesAndDropAsksFirst(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, dir);

            // With no stash, each command only says so.
            for (Runnable command : List.<Runnable>of(
                    win.stashes()::pickToApply,
                    win.stashes()::pickToDrop,
                    win.stashes()::popLatest,
                    win.stashes()::showList)) {
                win.w().clearStatus();
                FxTestSupport.runOnFx(command);
                OverlayTestKit.await(
                        async,
                        "the no-stashes message",
                        () -> tr("stash.empty").equals(win.w().status()));
                assertNull(FxTestSupport.callOnFx(
                        () -> OverlayTestKit.picker(win.w().scene())));
            }

            win.repo().write("file.txt", "one\ntwo\nfirst stash\n");
            win.repo().git("stash", "push", "-q", "-m", "older");
            win.repo().write("other.txt", "second stash\n");
            win.repo().git("stash", "push", "-q", "-u", "-m", "newer");

            FxTestSupport.runOnFx(win.stashes()::pickToApply);
            OverlayTestKit.await(
                    async,
                    "the stash picker",
                    () -> OverlayTestKit.pickerItems(win.w().scene()).size() == 2);
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(win.w().scene(), "older")));
            awaitMessage(async, win, tr("stash.applied"));
            assertEquals("one\ntwo\nfirst stash\n", Files.readString(win.file()));
            assertEquals(2, stashCount(win), "Apply keeps the stash in the list");
            win.repo().git("checkout", "-q", "--", "file.txt");

            // Drop: declined keeps it; confirmed deletes it, naming the stash in the question.
            AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
            CountDownLatch declined =
                    OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, question);
            FxTestSupport.runOnFx(win.stashes()::pickToDrop);
            OverlayTestKit.await(
                    async,
                    "the stash picker",
                    () -> OverlayTestKit.pickerItems(win.w().scene()).size() == 2);
            assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(win.w().scene(), "newer")));
            async.await(declined, "the drop question");
            assertTrue(
                    question.get().content().contains("stash@{0}"),
                    question.get().content());
            assertTrue(
                    question.get().content().contains("newer"), question.get().content());
            assertEquals(2, stashCount(win));

            CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
            StashEntry newest = stashes(async, win).get(0);
            FxTestSupport.runOnFx(() -> win.stashes().drop(win.repo().root, newest));
            async.await(agreed, "the drop question, accepted");
            awaitMessage(async, win, tr("stash.dropped"));
            assertEquals(1, stashCount(win));
            assertTrue(win.repo().git("stash", "list").text().contains("older"));

            // Pop takes the newest one out of the list.
            FxTestSupport.runOnFx(win.stashes()::popLatest);
            awaitMessage(async, win, tr("stash.popped"));
            assertEquals(0, stashCount(win));
            assertEquals("one\ntwo\nfirst stash\n", Files.readString(win.file()));
        }
    }

    /** {@code stash@{0}} names another stash once the list has changed: the action is refused, not misapplied. */
    @Test
    void anEntryFromAListThatHasSinceChangedIsNeverActedOn(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, dir);
            win.repo().write("file.txt", "one\ntwo\nthe listed stash\n");
            win.repo().git("stash", "push", "-q", "-m", "listed");
            StashEntry listed = stashes(async, win).get(0);
            win.repo().write("file.txt", "one\ntwo\na later stash\n");
            win.repo().git("stash", "push", "-q", "-m", "later");

            FxTestSupport.runOnFx(() -> win.stashes().apply(win.repo().root, listed, true));
            awaitMessage(async, win, tr("stash.moved"));
            assertEquals("one\ntwo\n", Files.readString(win.file()), "nothing was applied");

            win.w().clearStatus();
            FxTestSupport.runOnFx(() -> win.stashes().branch(win.repo().root, listed, "rescue"));
            OverlayTestKit.await(
                    async, "the refusal", () -> tr("stash.moved").equals(win.w().status()));
            assertEquals(
                    "main", win.repo().git("branch", "--show-current").text().strip());

            win.w().clearStatus();
            CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
            FxTestSupport.runOnFx(() -> win.stashes().drop(win.repo().root, listed));
            async.await(agreed, "the drop question");
            OverlayTestKit.await(
                    async, "the refusal", () -> tr("stash.moved").equals(win.w().status()));
            assertEquals(2, stashCount(win), "both stashes are still there");

            // No repository: nothing to act in.
            FxTestSupport.runOnFx(() -> {
                win.stashes().apply(null, listed, false);
                win.stashes().drop(null, listed);
                win.stashes().branch(null, listed, "rescue");
                win.stashes().show(null, listed);
                win.stashes().stash(null, StashOptions.DEFAULT);
            });
            assertEquals(2, stashCount(win));
        }
    }

    @Test
    void aStashThatCannotBeAppliedIsExplainedAndKept(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, dir);
            Path root = win.repo().root;
            win.repo().write("file.txt", "one\ntwo\nstashed edit\n");
            win.repo().git("stash", "push", "-q", "-m", "tracked work");
            StashEntry tracked = stashes(async, win).get(0);

            // The same file edited again and not committed: git will not overwrite it.
            win.repo().write("file.txt", "one\ntwo\na different edit\n");
            OverlayTestKit.Shown overwrite = errorDialog(
                    async, tr("stash.wouldOverwrite"), () -> win.stashes().apply(root, tracked, false));
            assertTrue(overwrite.content().contains("file.txt"), overwrite.content());
            assertEquals("one\ntwo\na different edit\n", Files.readString(win.file()), "the edit is untouched");
            assertEquals(1, stashCount(win), "and the stash is still there");
            win.repo().git("checkout", "-q", "--", "file.txt");
            win.repo().git("stash", "drop", "-q");

            // An untracked file of the stash that exists again: git restores what it can and says so.
            win.repo().write("brand-new.txt", "stashed untracked\n");
            win.repo().git("stash", "push", "-q", "-u", "-m", "untracked work");
            StashEntry untracked = stashes(async, win).get(0);
            win.repo().write("brand-new.txt", "written again since\n");
            OverlayTestKit.Shown exists = errorDialog(
                    async, tr("stash.untrackedExists"), () -> win.stashes().apply(root, untracked, true));
            assertTrue(exists.content().contains("brand-new.txt"), exists.content());
            assertEquals("written again since\n", Files.readString(root.resolve("brand-new.txt")));
            assertEquals(1, stashCount(win), "a pop that failed keeps the stash");
        }
    }

    @Test
    void aStashCanBeReviewedCopiedAndTurnedIntoABranch(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, dir);
            Path root = win.repo().root;
            win.repo().write("file.txt", "one\ntwo\nstashed edit\n");
            win.repo().write("added.txt", "staged new file\n");
            win.repo().git("add", "added.txt");
            win.repo().write("brand-new.txt", "untracked\n");
            win.repo().git("stash", "push", "-q", "-u", "-m", "three kinds of change");
            StashEntry entry = stashes(async, win).get(0);

            FxTestSupport.runOnFx(() -> win.stashes().copyName(entry));
            assertEquals(
                    "stash@{0}",
                    FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
            assertEquals(tr("status.git.copiedHash", "stash@{0}"), win.w().status());

            // Show: one review tab over the modified, the added and the untracked file.
            FxTestSupport.runOnFx(() -> win.stashes().show(root, entry));
            OverlayTestKit.await(
                    async,
                    "the review tab",
                    () -> win.w().area.selectedTab().getUserData() instanceof com.editora.editor.TabContent content
                            && content.title().equals(tr("stash.review.title", "stash@{0}", 3)));

            // Branch: blank and option-like names are dropped; a real one creates the branch and uses up the stash.
            win.w().open(win.file());
            FxTestSupport.runOnFx(() -> win.stashes().promptBranch(root, entry));
            assertEquals(
                    tr("stash.branch.title"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "   "));
            FxTestSupport.runOnFx(() -> win.stashes().promptBranch(root, entry));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "--force"));
            assertTrue(
                    win.w().status().startsWith(tr("status.git.unsafeRef", "").stripTrailing()),
                    win.w().status());
            assertEquals(1, stashCount(win));

            FxTestSupport.runOnFx(() -> win.stashes().promptBranch(root, entry));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), " rescue "));
            awaitMessage(async, win, tr("stash.branched", "rescue"));
            assertEquals(
                    "rescue", win.repo().git("branch", "--show-current").text().strip());
            assertEquals("one\ntwo\nstashed edit\n", Files.readString(win.file()));
            assertEquals("untracked\n", Files.readString(root.resolve("brand-new.txt")));
            assertEquals(0, stashCount(win), "the stash was applied on the new branch and dropped");

            // A branch name that is taken: git's refusal is shown, and the stash would have been kept.
            win.repo().write("file.txt", "one\ntwo\nstashed edit\nmore\n");
            win.repo().git("stash", "push", "-q", "-m", "second");
            StashEntry second = stashes(async, win).get(0);
            OverlayTestKit.Shown taken = errorDialog(
                    async,
                    tr("status.git.createBranchFailed", "main"),
                    () -> win.stashes().branch(root, second, "main"));
            assertFalse(taken.content().isBlank());
            assertEquals(1, stashCount(win));
        }
    }

    @Test
    void outsideARepositoryTheStashCommandsOnlySayThereIsNone(@TempDir Path dir) throws Exception {
        Path plain = Files.writeString(dir.resolve("plain.txt"), "no repository here\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            FxTestSupport.runOnFx(() -> w.fx.controller.openAndNavigate(plain, 0));
            OverlayTestKit.await(
                    async,
                    "the tab",
                    () -> w.active() != null && plain.equals(w.active().getPath()));
            FxTestSupport.runOnFx(w.git::refresh);
            async.awaitWorker(FxTestSupport.field(w.git.service(), "exec"));
            async.awaitFx();
            GitStashCoordinator stashes = w.git.stashes();

            for (Runnable command : List.<Runnable>of(
                    stashes::showList,
                    stashes::promptStash,
                    stashes::popLatest,
                    stashes::pickToApply,
                    stashes::pickToDrop)) {
                w.clearStatus();
                FxTestSupport.runOnFx(command);
                assertEquals(tr("status.notARepo"), w.status());
            }
            assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.form(w.scene())), "no form was opened");
        }
    }
}
