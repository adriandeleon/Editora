package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the editor holds when the disk copy changes or disappears under it: Enter in the "modified outside
 * Editora" prompt must not discard unsaved edits, and a buffer whose file is gone must not close as "saved".
 */
@Tag("fx")
class ExternalChangeKeepsEditsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    // --- the prompt's keyboard default ------------------------------------------------------------------

    @Test
    void enterKeepsTheEditsWhenTheBufferIsUnsaved() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("dirty.txt"), "on disk\n");
            EditorBuffer buffer = load(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "my unsaved edit "));
            rewrite(file, "changed by another program\n");

            Pressed pressed = pressEnterInNextDialog(async);
            prompt(fx, buffer, file);
            async.await(pressed.done(), "the external-change prompt");
            SaveGuardsFxTest.awaitOnFx(async, "the prompt to close", () -> openDialogs() == 0);
            async.awaitFx();

            assertEquals(tr("dialog.externalChange.keepMine"), pressed.focused().get(), "Keep Mine has the focus");
            assertEquals(
                    tr("dialog.externalChange.keepMine"),
                    pressed.defaultButton().get());
            FxTestSupport.runOnFx(() -> {
                assertEquals("my unsaved edit on disk\n", buffer.getContent());
                assertTrue(buffer.isDirty());
                assertTrue(buffer.getArea().isUndoAvailable());
            });
        }
    }

    @Test
    void enterStillReloadsACleanBuffer() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("clean.txt"), "on disk\n");
            EditorBuffer buffer = load(async, fx, file);
            rewrite(file, "changed by another program\n");

            Pressed pressed = pressEnterInNextDialog(async);
            prompt(fx, buffer, file);
            async.await(pressed.done(), "the external-change prompt");
            SaveGuardsFxTest.awaitOnFx(
                    async, "the reload", () -> "changed by another program\n".equals(buffer.getContent()));

            assertEquals(tr("dialog.externalChange.reload"), pressed.focused().get());
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void reloadCanStillBeChosenForAnUnsavedBuffer() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("chosen.txt"), "on disk\n");
            EditorBuffer buffer = load(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "discard me "));
            rewrite(file, "changed by another program\n");

            CountDownLatch reloaded = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.OTHER);
            prompt(fx, buffer, file);
            async.await(reloaded, "the external-change prompt");
            SaveGuardsFxTest.awaitOnFx(
                    async, "the reload", () -> "changed by another program\n".equals(buffer.getContent()));

            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    // --- the file is gone ---------------------------------------------------------------------------------

    @Test
    void aFileDeletedByAnotherProgramLeavesItsBufferUnsaved() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("gone.txt"), "the only copy lives in the editor\n");
            EditorBuffer buffer = load(async, fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            Files.delete(file);

            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            SaveGuardsFxTest.awaitOnFx(async, "the check to settle", () -> settled(workflows) && buffer.isDirty());

            assertEquals("the only copy lives in the editor\n", FxTestSupport.callOnFx(buffer::getContent));
            assertEquals(tr("status.fileGoneKeptUnsaved", "gone.txt"), FxTestSupport.callOnFx(() -> echo(fx)));
            CloseCoordinator closes = FxTestSupport.field(fx.controller, "closes");
            CountDownLatch asked = SaveGuardsFxTest.pressNextDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE);
            assertFalse(
                    FxTestSupport.callOnFx(() -> closes.confirmClose(tabOf(fx, buffer))),
                    "closing the tab asks, and Cancel keeps it");
            assertEquals(0, asked.getCount());
        }
    }

    @Test
    void autoSaveDoesNotBringADeletedFileBack() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("removed.txt"), "removed on purpose\n");
            EditorBuffer buffer = load(async, fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            Files.delete(file);
            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            SaveGuardsFxTest.awaitOnFx(async, "the check to settle", () -> settled(workflows) && buffer.isDirty());

            FxTestSupport.runOnFx(workflows::autoSaveAllDirty);
            async.awaitWorker(workflows.autoSaveExecutor);
            async.awaitFx();

            assertFalse(Files.exists(file), "a background save must not undo a deletion nobody asked it to undo");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "and the text is still held as unsaved");
        }
    }

    @Test
    void aFileThatComesBackUnchangedIsSavedAgain() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("back.txt"), "same bytes\n");
            EditorBuffer buffer = load(async, fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            Path aside = dir.resolve("back.txt.aside");
            Files.move(file, aside);
            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            SaveGuardsFxTest.awaitOnFx(async, "the check to settle", () -> settled(workflows) && buffer.isDirty());

            Files.move(aside, file);
            rewrite(file, "same bytes\n"); // a branch switched away and back: new time, same bytes
            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            SaveGuardsFxTest.awaitOnFx(async, "the comparison", () -> settled(workflows) && !buffer.isDirty());

            assertEquals(0, openDialogs(), "identical bytes are not an external change");
        }
    }

    @Test
    void anEditMadeWhileTheFileWasGoneStaysUnsavedWhenItComesBack() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("edited.txt"), "same bytes\n");
            EditorBuffer buffer = load(async, fx, file);
            FileWorkflowCoordinator workflows = workflows(fx);
            Path aside = dir.resolve("edited.txt.aside");
            Files.move(file, aside);
            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            SaveGuardsFxTest.awaitOnFx(async, "the check to settle", () -> settled(workflows) && buffer.isDirty());
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "typed while gone "));

            Files.move(aside, file);
            FxTestSupport.runOnFx(workflows::checkExternalChanges);
            SaveGuardsFxTest.awaitOnFx(async, "the check to settle", () -> settled(workflows));
            async.awaitFx();

            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("typed while gone same bytes\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    @Test
    void aGitOperationThatRemovesAnOpenFileLeavesItsBufferUnsavedAndReloadsItWhenItReturns() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("branch-only.txt"), "on this branch\n");
            EditorBuffer buffer = load(async, fx, file);
            Files.delete(file);

            FxTestSupport.runOnFx(fx.controller::reloadAllFromDiskSilently);
            SaveGuardsFxTest.awaitOnFx(async, "the missing file to be noticed", buffer::isDirty);
            assertEquals("on this branch\n", FxTestSupport.callOnFx(buffer::getContent));

            Files.writeString(file, "back, with upstream changes\n");
            FxTestSupport.runOnFx(fx.controller::reloadAllFromDiskSilently);
            SaveGuardsFxTest.awaitOnFx(
                    async,
                    "the returned file to be reloaded",
                    () -> "back, with upstream changes\n".equals(buffer.getContent()));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private record Pressed(
            CountDownLatch done, AtomicReference<String> focused, AtomicReference<String> defaultButton) {}

    /** Presses Enter on whatever has the keyboard focus in the next dialog, as a user mid-typing would. */
    private static Pressed pressEnterInNextDialog(AsyncTestScope async) throws Exception {
        Pressed pressed = new Pressed(new CountDownLatch(1), new AtomicReference<>(), new AtomicReference<>());
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window.getScene() == null
                            || !(window.getScene().getRoot() instanceof DialogPane pane)
                            || !window.isShowing()) {
                        continue;
                    }
                    Node focus = window.getScene().getFocusOwner();
                    if (!(focus instanceof Button focusedButton)) {
                        continue; // the dialog has not settled its initial focus yet
                    }
                    stop();
                    pressed.focused().set(focusedButton.getText());
                    pane.getButtonTypes().stream()
                            .map(type -> (Button) pane.lookupButton(type))
                            .filter(Button::isDefaultButton)
                            .findFirst()
                            .ifPresent(button -> pressed.defaultButton().set(button.getText()));
                    Event.fireEvent(focus, key(KeyEvent.KEY_PRESSED));
                    Event.fireEvent(focus, key(KeyEvent.KEY_RELEASED));
                    pressed.done().countDown();
                    return;
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
        return pressed;
    }

    private static KeyEvent key(javafx.event.EventType<KeyEvent> type) {
        return new KeyEvent(type, "", "", KeyCode.ENTER, false, false, false, false);
    }

    private static void prompt(FxWindowFixture fx, EditorBuffer buffer, Path file) throws Exception {
        FileWorkflowCoordinator workflows = workflows(fx);
        long mtime = Files.getLastModifiedTime(file).toMillis();
        long size = Files.size(file);
        Platform.runLater(() -> workflows.promptExternalChange(tabOf(fx, buffer), buffer, mtime, size));
    }

    private static boolean settled(FileWorkflowCoordinator workflows) {
        java.util.Set<EditorBuffer> pending = FxTestSupport.field(workflows, "verifyingExternalChange");
        return pending.isEmpty();
    }

    private static void rewrite(Path file, String text) throws Exception {
        long before = Files.getLastModifiedTime(file).toMillis();
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(before + 60_000));
    }

    private static int openDialogs() {
        return (int) Window.getWindows().stream()
                .filter(window -> window.getScene() != null && window.getScene().getRoot() instanceof DialogPane)
                .count();
    }

    private static String echo(FxWindowFixture fx) {
        StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.<javafx.scene.control.Label>field(status, "echo").getText();
    }

    private static Tab tabOf(FxWindowFixture fx, EditorBuffer buffer) {
        return (Tab) FxTestSupport.call(fx.controller, "tabForBuffer", new Class<?>[] {EditorBuffer.class}, buffer);
    }

    private static FileWorkflowCoordinator workflows(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "fileWorkflows");
    }

    /**
     * Opens {@code file} in a selected tab and waits for the check that selecting it starts. That check asks
     * the disk on a worker like any other; left running, it would see the change the test makes next and
     * raise the external-change prompt itself — a second prompt beside the one under test, which nobody
     * answers.
     */
    private static EditorBuffer load(AsyncTestScope async, FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = workflows(fx);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer loaded = new EditorBuffer();
            loaded.setPath(file);
            workflows.loadInto(loaded, file);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, loaded, true);
            return loaded;
        });
        SaveGuardsFxTest.awaitOnFx(async, "the tab-switch check to settle", () -> settled(workflows));
        return buffer;
    }
}
