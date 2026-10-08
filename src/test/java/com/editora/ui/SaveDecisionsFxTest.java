package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.config.PathDisplay;
import com.editora.editor.EditorBuffer;
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
 * The questions a save can put to the user, each answered every way: where to save (the Save As chooser and
 * the typed prompt), whether to overwrite another file, what to do about a file that changed on disk, and
 * whether to rewrite binary data — plus the auto-save toggle. Every test ends on the bytes on disk and on
 * what the buffer still considers unsaved.
 */
@Tag("fx")
class SaveDecisionsFxTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- Save As through the file chooser ----------------------------------------------------------------

    @Test
    void savingAnUntitledBufferAsksWhereAndCancellingWritesNothing() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = untitled(fx, "draft\n");
            List<EditorBuffer> asked = new ArrayList<>();
            AtomicReference<Path> answer = new AtomicReference<>();
            workflows.saveAsTargetChooser = offered -> {
                asked.add(offered);
                return answer.get();
            };

            run(fx, "file.save"); // the chooser is cancelled
            settle(async, workflows);

            assertEquals(List.of(buffer), asked, "the chooser was shown for this buffer");
            assertNull(FxTestSupport.callOnFx(buffer::getPath), "still untitled");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "still unsaved");
            assertEquals(List.of(), filesIn(dir), "nothing was written anywhere");

            Path target = dir.resolve("draft.txt");
            answer.set(target);
            run(fx, "file.save");
            settle(async, workflows);

            assertEquals("draft\n", Files.readString(target));
            assertEquals(target, FxTestSupport.callOnFx(buffer::getPath));
            assertEquals("draft.txt", FxTestSupport.callOnFx(buffer::getTitle));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(List.of("draft.txt"), filesIn(dir));

            // It has a file now: the next save goes there without asking again.
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("more\n"));
            answer.set(dir.resolve("never.txt"));
            run(fx, "file.save");
            settle(async, workflows);
            assertEquals(2, asked.size(), "not asked a third time");
            assertEquals("draft\nmore\n", Files.readString(target));
            assertEquals(List.of("draft.txt"), filesIn(dir));
        }
    }

    @Test
    void saveAsWritesTheCopyReTargetsTheBufferAndLeavesTheOriginalAlone() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path original = Files.writeString(dir.resolve("original.txt"), "A\n");
            Path copy = dir.resolve("copy.txt");
            EditorBuffer buffer = open(async, fx, original);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            AtomicReference<Path> answer = new AtomicReference<>();
            workflows.saveAsTargetChooser = offered -> answer.get();

            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(fx.controller, "onSaveAs")); // cancelled
            settle(async, workflows);
            assertEquals(original, FxTestSupport.callOnFx(buffer::getPath));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("A\n", Files.readString(original));
            assertFalse(Files.exists(copy));

            answer.set(copy);
            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(fx.controller, "onSaveAs"));
            settle(async, workflows);

            assertEquals("A\nedited\n", Files.readString(copy));
            assertEquals("A\n", Files.readString(original), "Save As does not also save the original");
            assertEquals(copy, FxTestSupport.callOnFx(buffer::getPath));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void aSaveAsWhoseWriteFailsPutsTheBufferBackWhereItWas() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path original = Files.writeString(dir.resolve("original.txt"), "A\n");
            Path target = dir.resolve("copy.txt");
            EditorBuffer named = open(async, fx, original);
            FxTestSupport.runOnFx(() -> named.getArea().appendText("edited\n"));
            workflows.saveAsTargetChooser = offered -> target;
            workflows.setDocumentWriter((path, bytes, commit) -> {
                throw new IOException("Disk quota exceeded");
            });

            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(fx.controller, "onSaveAs"));
            settle(async, workflows);

            assertFalse(Files.exists(target));
            assertEquals(
                    original, FxTestSupport.callOnFx(named::getPath), "not left pointing at a file that is not there");
            assertTrue(FxTestSupport.callOnFx(named::isDirty));
            assertEquals("A\n", Files.readString(original));
            assertEquals(tr("status.failedSave", "Disk quota exceeded"), echo(fx));

            EditorBuffer untitled = untitled(fx, "draft\n");
            run(fx, "file.save");
            settle(async, workflows);

            assertFalse(Files.exists(target));
            assertNull(FxTestSupport.callOnFx(untitled::getPath), "untitled again");
            assertTrue(FxTestSupport.callOnFx(untitled::isDirty), "and its text is still unsaved");
            assertEquals("draft\n", FxTestSupport.callOnFx(untitled::getContent));
        }
    }

    @Test
    void saveAsOntoAFileThatIsOpenInAnotherTabIsRefused() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path other = Files.writeString(dir.resolve("other.txt"), "other, saved\n");
            Path mine = Files.writeString(dir.resolve("mine.txt"), "mine\n");
            EditorBuffer otherBuffer = open(async, fx, other);
            FxTestSupport.runOnFx(() -> otherBuffer.getArea().appendText("other, unsaved\n"));
            EditorBuffer buffer = open(async, fx, mine);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            workflows.saveAsTargetChooser = offered -> other;

            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(fx.controller, "onSaveAs"));
            settle(async, workflows);

            assertEquals("other, saved\n", Files.readString(other), "the other tab's file is not replaced");
            assertEquals("other, saved\nother, unsaved\n", FxTestSupport.callOnFx(otherBuffer::getContent));
            assertTrue(FxTestSupport.callOnFx(otherBuffer::isDirty));
            assertEquals(mine, FxTestSupport.callOnFx(buffer::getPath));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("mine\n", Files.readString(mine));
            assertEquals(tr("status.saveAs.cannotReplaceOpenFile", "other.txt"), echo(fx));
        }
    }

    @Test
    void theSaveBeforeCloseReportsWhetherTheChosenFileWasWritten() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            EditorBuffer buffer = untitled(fx, "draft\n");
            AtomicReference<Path> answer = new AtomicReference<>();
            workflows.saveAsTargetChooser = offered -> answer.get();

            assertFalse(
                    FxTestSupport.callOnFx(() -> workflows.saveSynchronously(buffer)),
                    "a cancelled chooser is not a save: the tab must not be closed on it");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            Path target = dir.resolve("kept.txt");
            answer.set(target);
            assertTrue(FxTestSupport.callOnFx(() -> workflows.saveSynchronously(buffer)));
            assertEquals("draft\n", Files.readString(target), "true means the bytes are there already");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    // --- Save As through the typed prompt, and the overwrite question -----------------------------------

    @Test
    void aTypedNameIsSavedBesideTheCurrentFileWithoutAnyQuestion() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path original = Files.writeString(dir.resolve("original.txt"), "A\n");
            EditorBuffer buffer = open(async, fx, original);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));

            run(fx, "file.saveAs");
            assertEquals(original.toString(), promptText(fx), "the prompt starts from the current path");
            answerPrompt(fx, "copy.txt");
            settle(async, workflows);

            Path copy = dir.resolve("copy.txt");
            assertEquals("A\nedited\n", Files.readString(copy));
            assertEquals("A\n", Files.readString(original));
            assertEquals(copy, FxTestSupport.callOnFx(buffer::getPath));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(List.of(), dialogs(), "a new file needs no confirmation");

            // The same path again (the prompt's own suggestion): saved in place, no question either.
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("again\n"));
            run(fx, "file.saveAs");
            answerPrompt(fx, promptText(fx));
            settle(async, workflows);
            assertEquals("A\nedited\nagain\n", Files.readString(copy));
            assertEquals(List.of(), dialogs());
        }
    }

    @Test
    void overwritingAnotherFileIsAskedFirstAndDecliningLeavesEverythingAsItWas() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path original = Files.writeString(dir.resolve("original.txt"), "A\n");
            Path existing = Files.writeString(dir.resolve("existing.txt"), "someone's work\n");
            EditorBuffer buffer = open(async, fx, original);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));
            String question = tr("dialog.saveAs.overwrite.header", "existing.txt");

            CountDownLatch declined = answerDialog(async, question, ButtonBar.ButtonData.CANCEL_CLOSE);
            run(fx, "file.saveAs");
            answerPrompt(fx, "existing.txt");
            async.await(declined, "the overwrite question");
            settle(async, workflows);

            assertEquals("someone's work\n", Files.readString(existing));
            assertEquals(original, FxTestSupport.callOnFx(buffer::getPath));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("A\n", Files.readString(original));

            CountDownLatch agreed = answerDialog(async, question, ButtonBar.ButtonData.OK_DONE);
            run(fx, "file.saveAs");
            answerPrompt(fx, existing.toString());
            async.await(agreed, "the overwrite question, answered yes");
            settle(async, workflows);

            assertEquals("A\nedited\n", Files.readString(existing));
            assertEquals(existing, FxTestSupport.callOnFx(buffer::getPath));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("A\n", Files.readString(original));
        }
    }

    @Test
    void closingTheOverwriteQuestionIsANo() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path original = Files.writeString(dir.resolve("original.txt"), "A\n");
            Path existing = Files.writeString(dir.resolve("existing.txt"), "someone's work\n");
            EditorBuffer buffer = open(async, fx, original);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));

            CountDownLatch closed = answerDialog(async, tr("dialog.saveAs.overwrite.header", "existing.txt"), null);
            run(fx, "file.saveAs");
            answerPrompt(fx, "existing.txt");
            async.await(closed, "the overwrite question");
            settle(async, workflows);

            assertEquals("someone's work\n", Files.readString(existing));
            assertEquals(original, FxTestSupport.callOnFx(buffer::getPath));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void aTypedTargetThatCannotBeUsedChangesNothingAndAsksNothing() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path other = Files.writeString(dir.resolve("other.txt"), "other\n");
            Path mine = Files.writeString(dir.resolve("mine.txt"), "mine\n");
            open(async, fx, other);
            EditorBuffer buffer = open(async, fx, mine);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("edited\n"));

            run(fx, "file.saveAs");
            answerPrompt(fx, "   ");
            settle(async, workflows);
            assertEquals(tr("status.saveAs.invalidPath"), echo(fx));

            run(fx, "file.saveAs");
            answerPrompt(fx, "other.txt"); // exists, and is open in another tab
            settle(async, workflows);
            assertEquals(tr("status.saveAs.cannotReplaceOpenFile", "other.txt"), echo(fx));
            assertEquals(List.of(), dialogs(), "no overwrite question whose yes could not be honoured");

            assertEquals("other\n", Files.readString(other));
            assertEquals("mine\n", Files.readString(mine));
            assertEquals(mine, FxTestSupport.callOnFx(buffer::getPath));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(List.of("mine.txt", "other.txt"), filesIn(dir));
        }
    }

    // --- the file changed on disk since it was loaded ---------------------------------------------------

    @Test
    void aSaveOverAnExternalChangeAsksAndCancelKeepsBothVersionsApart() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path file = Files.writeString(dir.resolve("shared.txt"), "v1\n");
            EditorBuffer buffer = open(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("mine\n"));
            Files.writeString(file, "v2, written by another program\n");

            CountDownLatch cancelled = answerDialog(
                    async, tr("dialog.externalChange.header", "shared.txt"), ButtonBar.ButtonData.CANCEL_CLOSE);
            run(fx, "file.save");
            async.await(cancelled, "the changed-on-disk question");
            settle(async, workflows);

            assertEquals("v2, written by another program\n", Files.readString(file));
            assertEquals("v1\nmine\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(FxTestSupport.callOnFx(() -> workflows.hasPendingSave(buffer)));
        }
    }

    @Test
    void choosingReloadTakesTheDiskVersionAndWritesNothing() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path file = Files.writeString(dir.resolve("shared.txt"), "v1\n");
            EditorBuffer buffer = open(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("mine\n"));
            Files.writeString(file, "v2, written by another program\n");

            CountDownLatch reloaded =
                    answerDialog(async, tr("dialog.externalChange.header", "shared.txt"), ButtonBar.ButtonData.LEFT);
            run(fx, "file.save");
            async.await(reloaded, "the changed-on-disk question");
            settle(async, workflows);

            assertEquals("v2, written by another program\n", Files.readString(file));
            assertEquals("v2, written by another program\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty), "the buffer is the file again");

            // And it is in step with the disk: the next edit saves without another question.
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("then mine\n"));
            run(fx, "file.save");
            settle(async, workflows);
            assertEquals("v2, written by another program\nthen mine\n", Files.readString(file));
            assertEquals(List.of(), dialogs());
        }
    }

    @Test
    void choosingKeepMineOverwritesTheDiskVersion() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            Path file = Files.writeString(dir.resolve("shared.txt"), "v1\n");
            EditorBuffer buffer = open(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("mine\n"));
            Files.writeString(file, "v2, written by another program\n");

            CountDownLatch kept =
                    answerDialog(async, tr("dialog.externalChange.header", "shared.txt"), ButtonBar.ButtonData.OK_DONE);
            run(fx, "file.save");
            async.await(kept, "the changed-on-disk question");
            settle(async, workflows);

            assertEquals("v1\nmine\n", Files.readString(file));
            assertEquals("v1\nmine\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(tr("status.saved", PathDisplay.of(file)), echo(fx));
        }
    }

    // --- rewriting the line endings of a file that holds binary data -----------------------------------

    @Test
    void anEditToMixedBinaryDataIsOnlyWrittenOnceTheWarningIsAccepted() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            byte[] original = installer();
            Path file = Files.write(dir.resolve("installer.run"), original);
            EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
                EditorBuffer loading = new EditorBuffer();
                loading.setPath(file);
                workflows.loadInto(loading, file);
                addBuffer(fx, loading);
                return loading;
            });
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "#"));
            String warning = tr("dialog.saveMixedBinary.header", "installer.run");

            CountDownLatch declined = answerDialog(async, warning, ButtonBar.ButtonData.CANCEL_CLOSE);
            run(fx, "file.save");
            async.await(declined, "the binary-data warning");
            settle(async, workflows);

            assertEquals(hex(original), hex(Files.readAllBytes(file)), "declined: not one byte is changed");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(
                    tr("status.save.cannotSaveMixedBinary", "installer.run", "LF", tr("command.file.saveAs")),
                    echo(fx));
            assertFalse(Files.exists(fx.configDir.resolve(MixedLineEndings.ORIGINALS_FOLDER)), "nothing was set aside");

            // A "no" is not remembered: the next explicit save asks again.
            CountDownLatch accepted = answerDialog(async, warning, ButtonBar.ButtonData.OTHER);
            run(fx, "file.save");
            async.await(accepted, "the binary-data warning, accepted");
            settle(async, workflows);

            byte[] saved = Files.readAllBytes(file);
            assertEquals('#', saved[0]);
            assertFalse(new String(saved, StandardCharsets.ISO_8859_1).contains("\r"), "one line ending throughout");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            try (var copies = Files.list(fx.configDir.resolve(MixedLineEndings.ORIGINALS_FOLDER))) {
                assertEquals(
                        hex(original),
                        hex(Files.readAllBytes(copies.findFirst().orElseThrow())),
                        "the bytes as they were are kept before they are rewritten");
            }
        }
    }

    // --- the auto-save toggle ----------------------------------------------------------------------------

    @Test
    void toggleAutoSaveCyclesTheModeAndEachModeDoesWhatItSays() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FileWorkflowCoordinator workflows = workflows(fx);
            ConfigManager config = FxTestSupport.field(fx.controller, "config");
            Path file = Files.writeString(dir.resolve("auto.txt"), "v1\n");
            EditorBuffer buffer = open(async, fx, file);
            FxTestSupport.runOnFx(() -> config.getSettings().setAutoSaveDelayMillis(100));
            assertEquals(FileWorkflowCoordinator.AUTOSAVE_OFF, FxTestSupport.callOnFx(workflows::autoSaveMode));

            // Off: an edit starts no timer.
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("typed while off\n"));
            assertEquals(Animation.Status.STOPPED, FxTestSupport.callOnFx(workflows.autoSaveIdleTimer::getStatus));

            run(fx, "file.toggleAutoSave"); // -> after delay
            assertEquals(
                    FileWorkflowCoordinator.AUTOSAVE_DELAY, config.getSettings().getAutoSave());
            assertEquals(tr("status.autoSave", tr("autosave.delay")), echo(fx));
            assertEquals("v1\n", Files.readString(file), "switching it on does not itself save");
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("typed with the delay on\n"));
            SaveGuardsFxTest.awaitOnFx(async, "the idle auto-save", () -> !buffer.isDirty());
            assertEquals("v1\ntyped while off\ntyped with the delay on\n", Files.readString(file));

            // An auto-save that is still counting down is called off when the mode changes.
            FxTestSupport.runOnFx(() -> {
                config.getSettings().setAutoSaveDelayMillis(600_000);
                workflows.applyAutoSave();
                buffer.getArea().appendText("typed just before switching\n");
            });
            assertEquals(Animation.Status.RUNNING, FxTestSupport.callOnFx(workflows.autoSaveIdleTimer::getStatus));
            run(fx, "file.toggleAutoSave"); // -> on focus change
            assertEquals(
                    FileWorkflowCoordinator.AUTOSAVE_FOCUS, config.getSettings().getAutoSave());
            assertEquals(tr("status.autoSave", tr("autosave.focus")), echo(fx));
            assertEquals(Animation.Status.STOPPED, FxTestSupport.callOnFx(workflows.autoSaveIdleTimer::getStatus));
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("typed in focus mode\n"));
            assertEquals(
                    Animation.Status.STOPPED,
                    FxTestSupport.callOnFx(workflows.autoSaveIdleTimer::getStatus),
                    "typing starts no countdown in this mode");

            run(fx, "file.toggleAutoSave"); // -> off
            assertEquals(
                    FileWorkflowCoordinator.AUTOSAVE_OFF, config.getSettings().getAutoSave());
            assertEquals(tr("status.autoSave", tr("autosave.off")), echo(fx));
            settle(async, workflows);
            assertEquals(
                    "v1\ntyped while off\ntyped with the delay on\n",
                    Files.readString(file),
                    "nothing typed after the delay mode was left has been written");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));

            run(fx, "file.toggleAutoSave"); // and round again
            assertEquals(
                    FileWorkflowCoordinator.AUTOSAVE_DELAY, config.getSettings().getAutoSave());
            run(fx, "file.toggleAutoSave");
            run(fx, "file.toggleAutoSave"); // leave it off for the teardown
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------

    static FileWorkflowCoordinator workflows(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "fileWorkflows");
    }

    static void run(FxWindowFixture fx, String command) throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        assertTrue(FxTestSupport.callOnFx(() -> registry.run(command)), command + " is a registered command");
    }

    /** Opens {@code file} the way the user does, and waits for its document. */
    static EditorBuffer open(AsyncTestScope async, FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = workflows(fx);
        FxTestSupport.runOnFx(() -> workflows.openPath(file));
        return WindowMcpBridgeFxTest.awaitLoaded(async, fx.controller, file);
    }

    static EditorBuffer untitled(FxWindowFixture fx, String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            addBuffer(fx, buffer);
            buffer.getArea().appendText(content); // typed, so it is unsaved text
            return buffer;
        });
    }

    private static void addBuffer(FxWindowFixture fx, EditorBuffer buffer) {
        FxTestSupport.call(
                fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
    }

    /** The write worker has drained and its completion has run on the FX thread. */
    static void settle(AsyncTestScope async, FileWorkflowCoordinator workflows) throws Exception {
        ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
        async.awaitWorker(worker);
        async.awaitFx();
    }

    static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }

    /**
     * The newest status message in full. The echo line shows one line of at most
     * {@link StatusBar#MAX_ECHO_CHARS} characters; the session's message log keeps the whole text.
     */
    static String lastMessage(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<MessageLog>field(status, "messageLog")
                    .entries()
                    .get(0)
                    .text();
        });
    }

    private static List<String> filesIn(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    /** The card of the in-window prompt. FX thread. */
    private static Node promptCard(FxWindowFixture fx) {
        OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
        assertTrue(overlay.isShowing(), "the Save As prompt is showing");
        StackPane root = FxTestSupport.field(overlay, "overlayRoot");
        return root.getChildren().get(1);
    }

    private static String promptText(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> ((TextField) promptCard(fx).lookup(".text-field")).getText());
    }

    /** Types {@code text} into the Save As prompt and accepts it. */
    private static void answerPrompt(FxWindowFixture fx, String text) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node card = promptCard(fx);
            ((TextField) card.lookup(".text-field")).setText(text);
            Button ok = (Button) card.lookupAll(".button").stream()
                    .filter(node ->
                            node instanceof Button button && tr("dialog.ok").equals(button.getText()))
                    .findFirst()
                    .orElseThrow();
            ok.fire();
        });
    }

    /** The headers of every dialog on screen. */
    static List<String> dialogs() throws Exception {
        return FxTestSupport.callOnFx(() -> List.copyOf(Window.getWindows()).stream()
                .filter(window -> window.isShowing()
                        && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane)
                .map(window -> ((DialogPane) window.getScene().getRoot()).getHeaderText())
                .toList());
    }

    /**
     * Answers the dialog whose header is {@code header} when it appears: presses its button of kind
     * {@code answer}, or — with {@code null} — closes its window as Esc or the title bar's close button does.
     * Any other dialog is left alone, so a test that gets a different question times out instead of answering
     * it.
     */
    static CountDownLatch answerDialog(AsyncTestScope async, String header, ButtonBar.ButtonData answer)
            throws Exception {
        CountDownLatch answered = new CountDownLatch(1);
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (!window.isShowing()
                            || window.getScene() == null
                            || !(window.getScene().getRoot() instanceof DialogPane pane)
                            || !header.equals(pane.getHeaderText())) {
                        continue;
                    }
                    stop();
                    answered.countDown();
                    if (answer == null) {
                        javafx.event.Event.fireEvent(
                                window,
                                new javafx.stage.WindowEvent(window, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
                    } else {
                        ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                                        .filter(type -> type.getButtonData() == answer)
                                        .findFirst()
                                        .orElseThrow()))
                                .fire();
                    }
                    return;
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
        return answered;
    }

    /** A shell script with a gzip-like payload after it: text by its first bytes, CRLF and LF mixed in the tail. */
    private static byte[] installer() {
        StringBuilder script = new StringBuilder("#!/bin/sh\n");
        while (script.length() < 9000) {
            script.append("echo unpacking\n");
        }
        byte[] head = script.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] payload = HexFormat.of().parseHex("1f8b0800000000000003ed0d0a0d0001ff0a0d0d0a8190");
        byte[] all = new byte[head.length + payload.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(payload, 0, all, head.length, payload.length);
        return all;
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
