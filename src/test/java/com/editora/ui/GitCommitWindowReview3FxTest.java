package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import com.editora.git.GitConflicts;
import com.editora.git.GitOperation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Commit window against real repositories, for what the third Git review found: a merge's message lost on a
 * repository switch, a merge's {@code --autostash} given back with conflicts at its end, an Amend of a HEAD moved
 * since the last refresh, and an intent-to-add file discarded with unsaved edits.
 */
@Tag("fx")
class GitCommitWindowReview3FxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void forgetTheSession() {
        GitCommitCoordinator.clearSessionForTest();
    }

    // --- U1: a merge's message across a repository switch ----------------------------------------------------

    @Test
    void aMergesMessageIsOfferedInItsRepositoryAfterASwitch(@TempDir Path dir) throws Exception {
        GitTestRepo one = GitTestRepo.init(Files.createDirectory(dir.resolve("one")));
        Path a = one.write("a.txt", "a\n");
        one.commitAll("base");
        GitTestRepo two = GitTestRepo.init(Files.createDirectory(dir.resolve("two")));
        Path b = conflictedMerge(two);

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(a);
            GitPanel panel = showCommitWindow(w);
            FxTestSupport.runOnFx(() -> message(panel).setText("for the first repository"));

            w.open(b);
            GitFeatureFx.await("the second repository", () -> two.root.equals(w.git.repoRoot()));
            GitFeatureFx.await(
                    "the merge's message, though a draft was being typed",
                    () -> message(panel).getText().startsWith("Merge branch 'feature'"));

            w.open(a);
            GitFeatureFx.await(
                    "the first repository's draft back",
                    () -> "for the first repository".equals(message(panel).getText()));
            FxTestSupport.runOnFx(() -> message(panel).setText(""));

            w.open(b);
            GitFeatureFx.await(
                    "the merge's message again, from an empty box",
                    () -> message(panel).getText().startsWith("Merge branch 'feature'"));
        }
    }

    // --- B1: a merge's autostash that conflicts when it is given back -------------------------------------

    @Test
    void aMergeWhoseAutostashConflictsAtItsEndSaysTheChangesAreInTheStash(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path story = repo.write("story.txt", "base\n");
        Path other = repo.write("other.txt", "base\n");
        repo.commitAll("base");
        repo.git("checkout", "-q", "-b", "feature");
        repo.write("story.txt", "feature version\n");
        repo.write("other.txt", "feature version\n");
        repo.commitAll("feature");
        repo.git("checkout", "-q", "main");
        repo.write("story.txt", "main version\n");
        repo.commitAll("main change");
        Files.writeString(other, "uncommitted work\n");
        repo.tryGit("merge", "--autostash", "feature"); // stops on story.txt; the work waits in MERGE_AUTOSTASH
        assertTrue(Files.exists(repo.root.resolve(".git/MERGE_AUTOSTASH")), "git kept the work aside");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(story);
            GitPanel panel = showCommitWindow(w);
            GitFeatureFx.await(
                    "the banner says where the work is",
                    () -> panel.operationTextForTest().contains(tr("gitpanel.operation.autostash")));

            Files.writeString(story, "resolved\n");
            repo.git("add", "story.txt");
            FxTestSupport.runOnFx(w.git::refresh);
            GitFeatureFx.await(
                    "the conflict resolved",
                    () -> w.git.operation().kind() == GitOperation.Kind.MERGE
                            && GitConflicts.unmerged(w.git.status()).isEmpty()
                            && !commitButton(panel).isDisable());

            CountDownLatch said = w.watchStatus(
                    tr("status.git.opAutostashKept", GitCoordinator.operationName(GitOperation.Kind.MERGE))::equals);
            FxTestSupport.runOnFx(w.git::continueOperation);
            async.await(said, "the kept stash reported, not 'Merge continued'");

            assertFalse(repo.git("stash", "list").text().isBlank(), "the work is in the stash");
            assertTrue(Files.readString(other).contains("<<<<<<<"), "and its conflict is in the file");
        }
    }

    // --- U12: Amend replaces the commit it was armed against, or nothing ------------------------------------

    @Test
    void amendAmendsNothingWhenHeadMovedSinceTheLastRefresh(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("story.txt", "base\n");
        repo.commitAll("base");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(file);
            GitPanel panel = showCommitWindow(w);
            FxTestSupport.runOnFx(() -> check(panel).fire());
            GitFeatureFx.await("Amend armed", () -> "base".equals(message(panel).getText()));
            FxTestSupport.runOnFx(() -> message(panel).setText("reworded"));

            repo.write("story.txt", "second\n");
            repo.commitAll("second, from a terminal");
            String moved = repo.git("rev-parse", "--short", "HEAD").text().strip();
            CountDownLatch refused = w.watchStatus(tr("status.git.amendOffHeadMoved", moved)::equals);
            FxTestSupport.runOnFx(() -> assertTrue(panel.commitNow()));
            async.await(refused, "the refusal");

            assertEquals(
                    "second, from a terminal",
                    repo.git("log", "-1", "--format=%s").text().strip());
            assertEquals("2", repo.git("rev-list", "--count", "HEAD").text().strip(), "nothing amended");
            assertFalse(FxTestSupport.callOnFx(() -> check(panel).isSelected()));
            assertEquals("reworded", FxTestSupport.callOnFx(() -> message(panel).getText()), "the message kept");
        }
    }

    // --- U6: unsaved edits of a file a discard deletes -------------------------------------------------------

    @Test
    void discardingAnIntentToAddFileWithUnsavedEditsSaysSoAndClosesIt(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path base = repo.write("base.txt", "base\n");
        repo.commitAll("base");
        Path ita = repo.write("ita.txt", "on disk\n");
        repo.git("add", "-N", "ita.txt");

        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            EditorBuffer buffer = w.open(ita);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("typed, not saved\n"));
            w.open(base);

            javafx.application.Platform.runLater(() -> w.git.discardChanges(List.of("ita.txt"), List.of()));
            assertEquals(
                    tr("dialog.discard.added", "ita.txt") + "\n\n" + tr("dialog.discard.unsavedEdits", 1),
                    answerDialog(ButtonBar.ButtonData.OK_DONE));

            GitFeatureFx.await(
                    "the deleted file's tab closed",
                    () -> w.fx.controller.buffersAtOrUnderLocal(ita).isEmpty());
            assertFalse(Files.exists(ita));
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------

    /** A repository stopped in a conflicted merge of {@code feature} into {@code main}; its conflicted file. */
    private static Path conflictedMerge(GitTestRepo repo) throws Exception {
        Path file = repo.write("story.txt", "base\n");
        repo.commitAll("base");
        repo.git("checkout", "-q", "-b", "feature");
        repo.write("story.txt", "feature version\n");
        repo.commitAll("feature");
        repo.git("checkout", "-q", "main");
        repo.write("story.txt", "main version\n");
        repo.commitAll("main change");
        repo.tryGit("merge", "feature");
        return file;
    }

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

    private static TextArea message(GitPanel panel) {
        return FxTestSupport.field(panel, "message");
    }

    private static CheckBox check(GitPanel panel) {
        return FxTestSupport.field(panel, "amendCheck");
    }

    private static Button commitButton(GitPanel panel) {
        return FxTestSupport.field(panel, "commitButton");
    }

    private static boolean dialogShowing() {
        for (Window window : new ArrayList<>(Window.getWindows())) {
            if (window.getScene() != null && window.getScene().getRoot() instanceof DialogPane) {
                return true;
            }
        }
        return false;
    }

    /** Waits for a dialog, presses its button of {@code buttonData} and returns the dialog's text. */
    private static String answerDialog(ButtonBar.ButtonData buttonData) throws Exception {
        GitFeatureFx.await("the dialog", GitCommitWindowReview3FxTest::dialogShowing);
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
                            ((Button) pane.lookupButton(type)).fire();
                        });
            }
        });
        assertTrue(prompt.get() != null, "the dialog has a " + buttonData + " button");
        return prompt.get();
    }
}
