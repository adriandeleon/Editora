package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.stage.Window;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a file closes its tabs in every window, so every window that holds unsaved edits to it has to be
 * asked first — and an unsaved buffer nobody was asked about must never be dropped with the file.
 */
@Tag("fx")
class CrossWindowDeleteFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    @Test
    void cancellingTheOtherWindowsPromptAbortsTheDelete() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController other = secondWindow(fx);
            Path file = Files.writeString(dir.resolve("shared.txt"), "disk baseline");
            EditorBuffer buffer = open(other, file);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("unsaved work in the other window"));
            assertFalse(FxTestSupport.callOnFx(() -> fx.controller.hasFileOpen(file)));
            CountDownLatch promptSeen = new CountDownLatch(1);

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    deleteWithChoice(panel(fx.controller), List.of(file), tr("dialog.cancel"), promptSeen);

            ProjectPanel.DeleteResult outcome = async.await(result);
            assertEquals(0, promptSeen.getCount(), "the window holding the unsaved edits must be asked");
            assertFalse(outcome.prepared());
            assertEquals("disk baseline", Files.readString(file));
            FxTestSupport.runOnFx(() -> {
                assertEquals("unsaved work in the other window", buffer.getContent());
                assertTrue(buffer.isDirty());
                assertFalse(buffer.isDisposed());
                assertNotNull(tabOf(other, buffer));
            });
        }
    }

    @Test
    void discardingInTheOtherWindowLetsTheDeleteCloseItsTab() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController other = secondWindow(fx);
            Path file = Files.writeString(dir.resolve("discard.txt"), "disk baseline");
            EditorBuffer buffer = open(other, file);
            FxTestSupport.runOnFx(() -> buffer.replaceWholeDocument("explicitly discarded"));
            CountDownLatch promptSeen = new CountDownLatch(1);

            CompletableFuture<ProjectPanel.DeleteResult> result =
                    deleteWithChoice(panel(fx.controller), List.of(file), tr("dialog.discard"), promptSeen);

            ProjectPanel.DeleteResult outcome = async.await(result);
            assertEquals(0, promptSeen.getCount(), "the window holding the unsaved edits must be asked");
            assertTrue(outcome.prepared());
            assertEquals(1, outcome.deleted());
            assertFalse(Files.exists(file));
            FxTestSupport.runOnFx(() -> assertNull(tabOf(other, buffer), "the approved discard closes the tab"));
        }
    }

    @Test
    void bothWindowsHoldingUnsavedEditsAreAskedAndOneCancelIsEnough() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController other = secondWindow(fx);
            Path file = Files.writeString(dir.resolve("both.txt"), "disk baseline");
            EditorBuffer here = open(fx.controller, file);
            EditorBuffer there = open(other, file);
            FxTestSupport.runOnFx(() -> {
                here.replaceWholeDocument("edits in the deleting window");
                there.replaceWholeDocument("edits in the other window");
            });
            CountDownLatch firstPrompt = new CountDownLatch(1);
            CountDownLatch secondPrompt = new CountDownLatch(1);

            CompletableFuture<ProjectPanel.DeleteResult> result = FxTestSupport.callOnFx(() -> {
                // Discard the first prompt, cancel the second: each runs in the nested loop of its own alert.
                Platform.runLater(() -> {
                    Platform.runLater(() -> dismissAlert(tr("dialog.cancel"), secondPrompt));
                    dismissAlert(tr("dialog.discard"), firstPrompt);
                });
                return panel(fx.controller).deleteConfirmed(List.of(file));
            });

            ProjectPanel.DeleteResult outcome = async.await(result);
            assertEquals(0, firstPrompt.getCount());
            assertEquals(0, secondPrompt.getCount(), "the second owner is asked as well");
            assertFalse(outcome.prepared());
            assertEquals("disk baseline", Files.readString(file));
            FxTestSupport.runOnFx(() -> {
                assertEquals("edits in the deleting window", here.getContent());
                assertEquals("edits in the other window", there.getContent());
                assertNotNull(tabOf(fx.controller, here));
                assertNotNull(tabOf(other, there));
            });
        }
    }

    /** The guard below every delete path: here the one a language server's workspace edit takes. */
    @Test
    void aDeleteThatAskedNobodyLeavesAnUnsavedBufferOpenInEveryWindow() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            MainController other = secondWindow(fx);
            Path dirty = Files.writeString(dir.resolve("unasked.txt"), "disk baseline");
            Path clean = Files.writeString(dir.resolve("clean.txt"), "nothing to lose");
            EditorBuffer unsaved = open(other, dirty);
            EditorBuffer saved = open(other, clean);
            FxTestSupport.runOnFx(() -> unsaved.replaceWholeDocument("the only copy"));
            Files.delete(dirty);
            Files.delete(clean);

            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(fx.controller, "onProjectFileDeleted", new Class<?>[] {Path.class}, dirty);
                FxTestSupport.call(fx.controller, "onProjectFileDeleted", new Class<?>[] {Path.class}, clean);
            });

            FxTestSupport.runOnFx(() -> {
                assertNotNull(tabOf(other, unsaved), "unsaved text nobody was asked about stays open");
                assertFalse(unsaved.isDisposed());
                assertEquals("the only copy", unsaved.getContent());
                assertTrue(unsaved.isDirty());
                assertNull(tabOf(other, saved), "a clean tab of a deleted file still closes");
            });
        }
    }

    private static MainController secondWindow(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            return (MainController)
                    FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
        });
    }

    private static Tab tabOf(MainController controller, EditorBuffer buffer) {
        EditorArea area = FxTestSupport.field(controller, "editorArea");
        return area.tabs().stream()
                .filter(tab -> tab.getUserData() == buffer)
                .findFirst()
                .orElse(null);
    }

    private static CompletableFuture<ProjectPanel.DeleteResult> deleteWithChoice(
            ProjectPanel panel, List<Path> files, String buttonText, CountDownLatch promptSeen) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Platform.runLater(() -> dismissAlert(buttonText, promptSeen));
            return panel.deleteConfirmed(files);
        });
    }

    private static void dismissAlert(String buttonText, CountDownLatch seen) {
        for (Window window : Window.getWindows().stream().toList()) {
            if (!(window.getScene() != null && window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            pane.getButtonTypes().stream()
                    .filter(type -> buttonText.equals(type.getText()))
                    .findFirst()
                    .ifPresent(type -> {
                        seen.countDown();
                        ((Button) pane.lookupButton(type)).fire();
                    });
        }
    }

    private static ProjectPanel panel(MainController controller) {
        return FxTestSupport.field(controller, "projectPanel");
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
}
