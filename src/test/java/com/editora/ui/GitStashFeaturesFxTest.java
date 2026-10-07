package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javafx.scene.input.KeyCode;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitService.CommitFile;
import com.editora.git.StashOptions;
import com.editora.git.StashParser.StashEntry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stash list, the stash options and what a stash that does not apply cleanly reports — against real git. */
@Tag("fx")
class GitStashFeaturesFxTest {

    private static final String BASE = "one\ntwo\nthree\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static List<String> stashList(GitTestRepo repo) throws Exception {
        return repo.git("stash", "list").text().lines().toList();
    }

    private static List<StashEntry> entries(GitFeatureFx w, Path root) throws Exception {
        java.util.concurrent.atomic.AtomicReference<List<StashEntry>> listed =
                new java.util.concurrent.atomic.AtomicReference<>();
        FxTestSupport.runOnFx(() -> w.git.service().stashList(root, listed::set));
        GitFeatureFx.await("the stash list", () -> listed.get() != null);
        return listed.get();
    }

    /**
     * The list shows each stash with its files — the untracked ones of a {@code -u} stash included — and
     * Enter opens them in the review tab: tracked files against the commit the stash was made on, untracked
     * files (kept in the stash's third parent) as additions.
     */
    @Test
    void theListShowsAStashWithItsFilesAndEnterOpensItsChanges(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", BASE);
        repo.commitAll("base");
        repo.write("story.txt", "one\nTWO stashed\nthree\n");
        repo.write("notes/new.txt", "never tracked\n");
        repo.git("stash", "push", "-q", "--include-untracked", "-m", "with untracked");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            FxTestSupport.runOnFx(() -> w.git.stashes().showList());
            GitFeatureFx.await(
                    "the stash list card",
                    () -> w.git.stashes().popup() != null
                            && w.git.stashes().popup().isShown());
            GitStashPopup popup = w.git.stashes().popup();

            StashEntry entry = FxTestSupport.callOnFx(popup::selected);
            assertEquals("stash@{0}", entry.ref());
            assertEquals("with untracked", entry.subject());
            assertEquals("main", entry.branch());
            assertTrue(entry.epochSeconds() > 0, "the row has a date");
            assertEquals(repo.git("rev-parse", "stash@{0}").text().strip(), entry.hash());

            GitFeatureFx.await("the stash's files", () -> popup.shownFiles().size() == 2);
            List<CommitFile> files = FxTestSupport.callOnFx(popup::shownFiles);
            assertEquals(new CommitFile('M', "story.txt", null), files.get(0));
            assertEquals(new CommitFile('?', "notes/new.txt", null), files.get(1), "untracked files are listed too");

            FxTestSupport.runOnFx(() -> GitFeatureFx.press(popup.filterField(), KeyCode.ENTER));
            GitFeatureFx.await("the review tab", () -> w.area.selectedTab().getUserData() instanceof PatchReviewPane);
            assertFalse(FxTestSupport.callOnFx(popup::isShown), "Enter closes the card");
            PatchReviewPane review = (PatchReviewPane) w.activeContent();
            List<DiffViewerPane> panes = FxTestSupport.callOnFx(review::panes);
            assertEquals(2, panes.size());
            assertEquals(BASE, FxTestSupport.<String>field(panes.get(0), "leftText"), "the commit it was made on");
            assertEquals("one\nTWO stashed\nthree\n", FxTestSupport.<String>field(panes.get(0), "rightText"));
            assertEquals("", FxTestSupport.<String>field(panes.get(1), "leftText"));
            assertEquals("never tracked\n", FxTestSupport.<String>field(panes.get(1), "rightText"));
        }
    }

    /** The card is driven from the filter field: arrows move, typing filters, the Menu key opens the actions. */
    @Test
    void theListIsKeyboardDriven(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", BASE);
        repo.commitAll("base");
        repo.write("story.txt", "older\n");
        repo.git("stash", "push", "-q", "-m", "older work");
        repo.write("story.txt", "newer\n");
        repo.git("stash", "push", "-q", "-m", "newer work");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            FxTestSupport.runOnFx(() -> w.git.stashes().showList());
            GitFeatureFx.await(
                    "the stash list card",
                    () -> w.git.stashes().popup() != null
                            && w.git.stashes().popup().isShown());
            GitStashPopup popup = w.git.stashes().popup();
            assertEquals(
                    "newer work", FxTestSupport.callOnFx(() -> popup.selected().subject()));

            FxTestSupport.runOnFx(() -> GitFeatureFx.press(popup.filterField(), KeyCode.DOWN));
            assertEquals(
                    "older work", FxTestSupport.callOnFx(() -> popup.selected().subject()));
            FxTestSupport.runOnFx(() -> GitFeatureFx.press(popup.filterField(), KeyCode.UP));
            assertEquals(
                    "newer work", FxTestSupport.callOnFx(() -> popup.selected().subject()));

            FxTestSupport.runOnFx(() -> GitFeatureFx.press(popup.filterField(), KeyCode.CONTEXT_MENU));
            assertTrue(FxTestSupport.callOnFx(popup::menuShowing), "the Menu key opens the stash's actions");

            FxTestSupport.runOnFx(() -> popup.setFilter("older"));
            assertEquals(1, FxTestSupport.callOnFx(() -> popup.entries().size()));
            assertEquals(
                    "stash@{1}", FxTestSupport.callOnFx(() -> popup.selected().ref()));

            // Copy Name acts on the selected (filtered-to) stash and closes the card.
            CountDownLatch copied = w.watchStatus(tr("status.git.copiedHash", "stash@{1}")::equals);
            FxTestSupport.runOnFx(() -> popup.run(GitStashPopup.Action.COPY));
            async.await(copied, "the stash name on the clipboard");
            assertFalse(FxTestSupport.callOnFx(popup::isShown));
            assertFalse(FxTestSupport.callOnFx(popup::menuShowing));
        }
    }

    /**
     * A pop that conflicts used to be a bare "Git command failed". It is an applied stash with conflicts:
     * the user is told the stash was kept, the open buffer shows the markers, and status lists the conflict.
     */
    @Test
    void aConflictingPopSaysTheStashWasKeptAndShowsTheConflict(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", BASE);
        repo.commitAll("base");
        repo.write("story.txt", "one\nTWO stashed\nthree\n");
        repo.git("stash", "push", "-q", "-m", "mine");
        repo.write("story.txt", "one\nTWO committed\nthree\n");
        repo.commitAll("theirs");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(file);
            CountDownLatch told = w.watchStatus(tr("stash.conflict.pop")::equals);

            FxTestSupport.runOnFx(() -> w.git.stashes().popLatest());
            async.await(told, "the conflict message");

            assertEquals(1, stashList(repo).size(), "git keeps a popped stash that conflicted");
            assertEquals("UU story.txt", repo.git("status", "--short").text().strip());
            GitFeatureFx.await(
                    "the buffer to reload with the markers",
                    () -> buffer.getContent().contains("<<<<<<<"));
            GitFeatureFx.await(
                    "status to list the conflicted file",
                    () -> w.git.status().files().stream()
                            .anyMatch(f -> f.unmerged() && f.path().equals("story.txt")));
        }
    }

    /** Apply keeps the stash, pop drops it; either way the open buffer follows the file. */
    @Test
    void applyKeepsTheStashAndPopDropsIt(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", BASE);
        repo.commitAll("base");
        repo.write("story.txt", "one\nTWO stashed\nthree\n");
        repo.git("stash", "push", "-q", "-m", "mine");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(file);
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);
            StashEntry entry = entries(w, root).get(0);

            CountDownLatch applied = w.watchStatus(tr("stash.applied")::equals);
            FxTestSupport.runOnFx(() -> w.git.stashes().apply(root, entry, false));
            async.await(applied, "stash apply");
            assertEquals(1, stashList(repo).size());
            GitFeatureFx.await("the buffer to reload", () -> buffer.getContent().equals("one\nTWO stashed\nthree\n"));

            repo.git("checkout", "-q", "--", "story.txt");
            CountDownLatch popped = w.watchStatus(tr("stash.popped")::equals);
            FxTestSupport.runOnFx(() -> w.git.stashes().apply(root, entry, true));
            async.await(popped, "stash pop");
            assertEquals(0, stashList(repo).size());
            assertEquals("one\nTWO stashed\nthree\n", Files.readString(file));
        }
    }

    /**
     * {@code stash@{0}} is a position. A stash listed a moment ago and then pushed down by another one must
     * not have the newcomer applied in its name.
     */
    @Test
    void aStashWhoseRefNowNamesAnotherStashIsNotApplied(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", BASE);
        repo.commitAll("base");
        repo.write("story.txt", "listed\n");
        repo.git("stash", "push", "-q", "-m", "listed");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);
            StashEntry listed = entries(w, root).get(0);
            repo.write("story.txt", "pushed from a terminal meanwhile\n");
            repo.git("stash", "push", "-q", "-m", "newcomer");

            CountDownLatch refused = w.watchStatus(tr("stash.moved")::equals);
            FxTestSupport.runOnFx(() -> w.git.stashes().apply(root, listed, true));
            async.await(refused, "the refusal");

            assertEquals(2, stashList(repo).size(), "nothing was popped");
            assertEquals(BASE, Files.readString(file), "nothing was applied");
        }
    }

    /** Branch from stash: a new branch at the stash's base commit, with the stash applied there and dropped. */
    @Test
    void branchFromStashCreatesTheBranchAndDropsTheStash(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", BASE);
        repo.commitAll("base");
        repo.write("story.txt", "one\nTWO stashed\nthree\n");
        repo.git("stash", "push", "-q", "-m", "mine");
        repo.write("story.txt", "one\nTWO committed\nthree\n");
        repo.commitAll("moved on"); // the stash no longer applies to main without a conflict

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(file);
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);
            StashEntry entry = entries(w, root).get(0);
            CountDownLatch branched = w.watchStatus(tr("stash.branched", "rescue")::equals);

            FxTestSupport.runOnFx(() -> w.git.stashes().branch(root, entry, "rescue"));
            async.await(branched, "stash branch");

            assertEquals("rescue", repo.git("branch", "--show-current").text().strip());
            assertEquals(0, stashList(repo).size());
            assertEquals("one\nTWO stashed\nthree\n", Files.readString(file));
            GitFeatureFx.await("the buffer to reload", () -> buffer.getContent().equals("one\nTWO stashed\nthree\n"));
        }
    }

    /**
     * Git stashes the files on disk. An edit still in the editor used to stay behind in the buffer while the
     * file under it was reset — the stash did not hold what the user was looking at.
     */
    @Test
    void stashSavesUnsavedBuffersFirstAndTakesUntrackedFilesWhenAsked(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", BASE);
        repo.commitAll("base");
        Path untracked = repo.write("scratch.txt", "untracked\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(file);
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);
            FxTestSupport.runOnFx(() -> buffer.getArea().replaceText("typed, not saved\n"));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            CountDownLatch stashed = w.watchStatus(tr("stash.pushed")::equals);

            FxTestSupport.runOnFx(() -> w.git.stashes().stash(root, new StashOptions("wip", true, false, false)));
            async.await(stashed, "the stash");

            assertEquals(
                    "typed, not saved\n",
                    repo.git("show", "stash@{0}:story.txt").text());
            assertEquals(BASE, Files.readString(file));
            GitFeatureFx.await(
                    "the buffer to follow the reset file",
                    () -> buffer.getContent().equals(BASE));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(Files.exists(untracked), "--include-untracked took the untracked file");
            assertEquals(
                    "untracked\n", repo.git("show", "stash@{0}^3:scratch.txt").text());
            assertEquals("On main: wip", stashList(repo).get(0).substring("stash@{0}: ".length()));
        }
    }

    @Test
    void stagedOnlyAndKeepIndex(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("staged.txt", "a\n");
        repo.write("unstaged.txt", "b\n");
        repo.commitAll("base");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            Path root = FxTestSupport.callOnFx(w.git::repoRoot);

            // Staged only: the staged change goes into the stash, the unstaged one stays where it is.
            repo.write("staged.txt", "a staged\n");
            repo.git("add", "staged.txt");
            repo.write("unstaged.txt", "b unstaged\n");
            CountDownLatch first = w.watchStatus(tr("stash.pushed")::equals);
            FxTestSupport.runOnFx(() -> w.git.stashes().stash(root, new StashOptions("", false, true, false)));
            async.await(first, "the staged-only stash");
            assertEquals(" M unstaged.txt", repo.git("status", "--short").text().stripTrailing());
            assertEquals(
                    "staged.txt",
                    repo.git("stash", "show", "--name-only", "stash@{0}").text().strip());

            // Keep index: everything is stashed, and what was staged is still staged afterwards.
            repo.write("staged.txt", "a staged again\n");
            repo.git("add", "staged.txt");
            CountDownLatch second = w.watchStatus(tr("stash.pushed")::equals);
            FxTestSupport.runOnFx(() -> w.git.stashes().stash(root, new StashOptions("", false, false, true)));
            async.await(second, "the keep-index stash");
            assertEquals("M  staged.txt", repo.git("status", "--short").text().stripTrailing());

            // Nothing left to stash is said so, not reported as a stash.
            repo.git("reset", "-q", "--hard");
            CountDownLatch nothing = w.watchStatus(tr("stash.nothing")::equals);
            FxTestSupport.runOnFx(() -> w.git.stashes().stash(root, StashOptions.DEFAULT));
            async.await(nothing, "the nothing-to-stash message");
            assertEquals(2, stashList(repo).size());
        }
    }
}
