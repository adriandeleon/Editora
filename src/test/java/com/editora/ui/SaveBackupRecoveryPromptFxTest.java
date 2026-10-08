package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.animation.AnimationTimer;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.InstanceLockTestHooks;
import com.editora.io.SaveBackups;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The question a launch asks about a save that was interrupted while it overwrote a file in place, answered
 * each way: what the file holds afterwards, and whether the backup — the only complete copy while the
 * question is open — is still there.
 */
@Tag("fx")
class SaveBackupRecoveryPromptFxTest {

    @TempDir
    Path dir;

    /** Dialogs a test has already answered, so the next wait is for the next question. FX thread. */
    private final Set<DialogPane> answered = Collections.newSetFromMap(new IdentityHashMap<>());

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- a file that differs from its backup -----------------------------------------------------------

    @Test
    void restorePutsThePreviousContentsBackAndRemovesTheBackup() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path torn = Files.writeString(dir.resolve("torn.txt"), "half a li");
            Path backup = backupOf(fx, torn, "the whole file\nas it was\n", daysAgo(2));

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            DialogPane prompt = awaitPrompt(async);

            assertEquals(tr("dialog.saveBackup.header", torn.toString()), prompt.getHeaderText());
            assertTrue(prompt.getContentText().contains(backup.toString()), "it says where the backup is");
            assertEquals(
                    List.of(
                            tr("dialog.saveBackup.restore"),
                            tr("dialog.saveBackup.keep"),
                            tr("dialog.saveBackup.later")),
                    labels(prompt));
            assertEquals("half a li", Files.readString(torn), "nothing is restored before the answer");
            assertTrue(Files.exists(backup));

            press(prompt, ButtonBar.ButtonData.OK_DONE);
            SaveGuardsFxTest.awaitOnFx(async, "the restore to finish", () -> !Files.exists(backup));

            assertEquals("the whole file\nas it was\n", Files.readString(torn));
            assertEquals(List.of(), leftInBackupFolder(fx), "the backup and its note are gone");
            assertEquals(List.of(), openPrompts(), "and no error was shown");
        }
    }

    @Test
    void keepLeavesTheFileAsItIsAndRemovesTheBackup() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path newer = Files.writeString(dir.resolve("newer.txt"), "rewritten since, and complete\n");
            Path backup = backupOf(fx, newer, "older contents\n", daysAgo(2));

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            DialogPane prompt = awaitPrompt(async);
            press(prompt, ButtonBar.ButtonData.OTHER);
            SaveGuardsFxTest.awaitOnFx(async, "the backup to be removed", () -> !Files.exists(backup));

            assertEquals("rewritten since, and complete\n", Files.readString(newer));
            assertEquals(List.of(), leftInBackupFolder(fx));
        }
    }

    @Test
    void decidingLaterChangesNothingAndTheBackupIsOfferedAgainNextTime() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path torn = Files.writeString(dir.resolve("torn.txt"), "half");
            Path backup = backupOf(fx, torn, "whole\n", daysAgo(2));

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            press(awaitPrompt(async), ButtonBar.ButtonData.CANCEL_CLOSE);
            async.awaitFx();

            assertEquals("half", Files.readString(torn));
            assertEquals("whole\n", Files.readString(backup));
            assertEquals(2, leftInBackupFolder(fx).size(), "the backup and the note naming its file");

            // The next launch: the same question, and this time it is answered.
            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            DialogPane again = awaitPrompt(async);
            assertEquals(tr("dialog.saveBackup.header", torn.toString()), again.getHeaderText());
            press(again, ButtonBar.ButtonData.OK_DONE);
            SaveGuardsFxTest.awaitOnFx(async, "the restore to finish", () -> !Files.exists(backup));
            assertEquals("whole\n", Files.readString(torn));
        }
    }

    @Test
    void closingTheQuestionWithoutAnAnswerIsDecidingLater() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path torn = Files.writeString(dir.resolve("torn.txt"), "half");
            Path backup = backupOf(fx, torn, "whole\n", daysAgo(2));

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            DialogPane prompt = awaitPrompt(async);
            FxTestSupport.runOnFx(() -> {
                answered.add(prompt);
                Window window = prompt.getScene().getWindow(); // its close button, or Esc
                javafx.event.Event.fireEvent(
                        window, new javafx.stage.WindowEvent(window, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            });
            async.awaitFx();
            assertTrue(FxTestSupport.callOnFx(() -> showingPrompts().isEmpty()), "the dialog closed");

            assertEquals("half", Files.readString(torn));
            assertEquals("whole\n", Files.readString(backup), "dismissing the dialog never deletes the only copy");
        }
    }

    // --- a file that is gone ---------------------------------------------------------------------------

    @Test
    void aMissingFileIsRecreatedFromItsBackup() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path gone = dir.resolve("gone.txt");
            Path backup = backupOf(fx, gone, "all that is left\n", daysAgo(2));

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            DialogPane prompt = awaitPrompt(async);

            String missing = tr("dialog.saveBackup.contentMissing", backup.toString(), "WHEN");
            String opening = missing.substring(0, missing.indexOf("WHEN"));
            assertTrue(
                    prompt.getContentText().startsWith(opening),
                    "the text is the one for a file that no longer exists: " + prompt.getContentText());
            assertTrue(prompt.getContentText().contains(backup.toString()));
            assertEquals(
                    List.of(
                            tr("dialog.saveBackup.restore"),
                            tr("dialog.saveBackup.delete"),
                            tr("dialog.saveBackup.later")),
                    labels(prompt),
                    "there is no current file to keep: the other choice deletes the last copy and says so");
            press(prompt, ButtonBar.ButtonData.OK_DONE);
            SaveGuardsFxTest.awaitOnFx(async, "the restore to finish", () -> !Files.exists(backup));

            assertEquals("all that is left\n", Files.readString(gone));
            assertEquals(List.of(), leftInBackupFolder(fx));
        }
    }

    // --- a backup whose file cannot be reached ---------------------------------------------------------

    @Test
    void aBackupWithNoKnownFileIsReportedAndOnlyDeletedOnRequest() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path orphan = Files.createDirectories(backupFolder(fx)).resolve("orphan.txt.1" + SaveBackups.SUFFIX);
            Files.writeString(orphan, "contents of an unknown file\n");
            Files.setLastModifiedTime(orphan, FileTime.from(daysAgo(2)));

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            DialogPane prompt = awaitPrompt(async);

            assertEquals(tr("dialog.saveBackup.headerUnknown"), prompt.getHeaderText());
            assertEquals(
                    List.of(tr("dialog.saveBackup.later"), tr("dialog.saveBackup.delete")),
                    labels(prompt),
                    "there is no file to restore it over, so restoring is not offered");
            press(prompt, ButtonBar.ButtonData.CANCEL_CLOSE);
            async.awaitFx();
            assertEquals("contents of an unknown file\n", Files.readString(orphan), "later: kept");

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            press(awaitPrompt(async), ButtonBar.ButtonData.OTHER);
            SaveGuardsFxTest.awaitOnFx(async, "the backup to be deleted", () -> !Files.exists(orphan));
            assertEquals(List.of(), leftInBackupFolder(fx));
        }
    }

    // --- several backups, and a restore that fails -----------------------------------------------------

    @Test
    void eachBackupIsAskedAboutInTurnAndEachAnswerAppliesToItsOwnFile() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path first = Files.writeString(dir.resolve("first.txt"), "first, torn");
            Path second = Files.writeString(dir.resolve("second.txt"), "second, torn");
            Path third = Files.writeString(dir.resolve("third.txt"), "third, torn");
            Path fine = Files.writeString(dir.resolve("fine.txt"), "same\n");
            Path firstBackup = backupOf(fx, first, "first, whole\n", daysAgo(4));
            Path secondBackup = backupOf(fx, second, "second, whole\n", daysAgo(3));
            Path thirdBackup = backupOf(fx, third, "third, whole\n", daysAgo(2));
            Path redundant = backupOf(fx, fine, "same\n", daysAgo(5));

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));

            DialogPane one = awaitPrompt(async);
            assertEquals(tr("dialog.saveBackup.header", first.toString()), one.getHeaderText(), "oldest first");
            assertEquals(1, openPrompts().size(), "one question at a time");
            assertFalse(Files.exists(redundant), "a backup equal to its file needed no question");
            press(one, ButtonBar.ButtonData.OK_DONE); // restore

            DialogPane two = awaitPrompt(async);
            assertEquals(tr("dialog.saveBackup.header", second.toString()), two.getHeaderText());
            press(two, ButtonBar.ButtonData.CANCEL_CLOSE); // later

            DialogPane three = awaitPrompt(async);
            assertEquals(tr("dialog.saveBackup.header", third.toString()), three.getHeaderText());
            press(three, ButtonBar.ButtonData.OTHER); // keep the file

            SaveGuardsFxTest.awaitOnFx(
                    async,
                    "the restore and the removal to finish",
                    () -> !Files.exists(firstBackup) && !Files.exists(thirdBackup));
            assertEquals("first, whole\n", Files.readString(first));
            assertEquals("second, torn", Files.readString(second));
            assertEquals("second, whole\n", Files.readString(secondBackup));
            assertEquals("third, torn", Files.readString(third));
            assertEquals("same\n", Files.readString(fine));
            assertEquals(List.of(), openPrompts(), "nothing further is asked");
        }
    }

    @Test
    void aRestoreThatFailsSaysSoAndKeepsTheBackup() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            // The file's folder has been replaced by a file: nothing can be written at the recorded path.
            Path blocker = Files.writeString(dir.resolve("was-a-folder"), "not a folder any more\n");
            Path target = blocker.resolve("doc.txt");
            Path backup = backupOf(fx, target, "the only copy\n", daysAgo(2));

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            press(awaitPrompt(async), ButtonBar.ButtonData.OK_DONE);
            DialogPane error = awaitPrompt(async);

            assertEquals(tr("dialog.saveBackup.restoreFailed", target.toString()), error.getHeaderText());
            assertTrue(error.getContentText().contains(backup.toString()), "it says where the contents still are");
            assertEquals("the only copy\n", Files.readString(backup));
            assertEquals(2, leftInBackupFolder(fx).size(), "the backup and its note");
            assertEquals("not a folder any more\n", Files.readString(blocker));
            press(error, ButtonBar.ButtonData.OK_DONE);
        }
    }

    // --- when nothing is asked -------------------------------------------------------------------------

    @Test
    void aSecondInstanceAsksNothingAboutTheFirstOnesBackups() throws Exception {
        Path config = Files.createTempDirectory(dir, "config");
        try (AsyncTestScope async = new AsyncTestScope();
                var first = InstanceLockTestHooks.otherProcessIsPrimary(config)) {
            FxWindowFixture fx =
                    async.own(FxWindowFixture.create(config, com.editora.config.SharedConfig::claimInstance));
            assertFalse(fx.shared.isPrimaryInstance(), "precondition");
            Path torn = Files.writeString(dir.resolve("torn.txt"), "being written by the other editor");
            Path settled = Files.writeString(dir.resolve("fine.txt"), "same\n");
            Path backup = backupOf(fx, torn, "previous\n", daysAgo(2));
            Path redundant = backupOf(fx, settled, "same\n", daysAgo(2));

            SaveBackupRecovery.offerAtStartup(fx.shared, stage(fx));
            SaveBackupRecovery.offerAtStartup(null, stage(fx));
            async.awaitFx();

            assertEquals(List.of(), openPrompts());
            assertTrue(Files.exists(backup));
            assertTrue(Files.exists(redundant), "not even a redundant backup is cleaned up from here");
            assertEquals("being written by the other editor", Files.readString(torn));
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days));
    }

    private static Path backupFolder(FxWindowFixture fx) {
        return fx.configDir.resolve(SaveBackupRecovery.FOLDER);
    }

    /** A backup as an in-place save leaves it: the previous bytes, and a note naming the file. */
    private static Path backupOf(FxWindowFixture fx, Path target, String previous, Instant written) throws IOException {
        Path folder = Files.createDirectories(backupFolder(fx));
        Path backup = Files.createTempFile(folder, target.getFileName() + ".", SaveBackups.SUFFIX);
        Files.writeString(backup, previous);
        Files.writeString(backup.resolveSibling(backup.getFileName() + ".target"), target.toString());
        Files.setLastModifiedTime(backup, FileTime.from(written));
        return backup;
    }

    private static List<String> leftInBackupFolder(FxWindowFixture fx) throws IOException {
        try (var files = Files.list(backupFolder(fx))) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static Stage stage(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "stage");
    }

    /** The next recovery dialog that no test step has answered yet. */
    private DialogPane awaitPrompt(AsyncTestScope async) throws Exception {
        AtomicReference<DialogPane> found = new AtomicReference<>();
        CountDownLatch shown = new CountDownLatch(1);
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (DialogPane pane : showingPrompts()) {
                    if (!answered.contains(pane)) {
                        found.set(pane);
                        shown.countDown();
                        stop();
                        return;
                    }
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
        async.await(shown, "the interrupted-save dialog");
        return found.get();
    }

    private void press(DialogPane pane, ButtonBar.ButtonData data) throws Exception {
        FxTestSupport.runOnFx(() -> {
            answered.add(pane);
            ButtonType type = pane.getButtonTypes().stream()
                    .filter(candidate -> candidate.getButtonData() == data)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + data + " button among " + pane.getButtonTypes()));
            ((Button) pane.lookupButton(type)).fire();
        });
    }

    private List<DialogPane> openPrompts() throws Exception {
        return FxTestSupport.callOnFx(() -> showingPrompts().stream()
                .filter(pane -> !answered.contains(pane))
                .toList());
    }

    private static List<DialogPane> showingPrompts() {
        String title = tr("dialog.saveBackup.title");
        return List.copyOf(Window.getWindows()).stream()
                .filter(window -> window.isShowing()
                        && window instanceof Stage dialog
                        && title.equals(dialog.getTitle())
                        && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane)
                .map(window -> (DialogPane) window.getScene().getRoot())
                .toList();
    }

    private static List<String> labels(DialogPane pane) throws Exception {
        return FxTestSupport.callOnFx(() -> pane.getButtonTypes().stream()
                .map(type -> ((Button) pane.lookupButton(type)).getText())
                .toList());
    }
}
