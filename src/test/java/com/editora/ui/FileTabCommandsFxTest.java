package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** File: Save All, File: Revert File and Buffer: Reopen Closed Tab through the real window. */
@Tag("fx")
class FileTabCommandsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    // --- Save All -------------------------------------------------------------------------------------------

    @Test
    void saveAllWritesEveryModifiedFileInEveryGroupAndSkipsUntitled(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
            Path b = Files.writeString(dir.resolve("b.txt"), "b\n");
            EditorBuffer bufA = open(async, fx, a);
            EditorBuffer bufB = open(async, fx, b);
            CommandRegistry registry = registry(fx);
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            FxTestSupport.runOnFx(() -> registry.run("view.splitEditorRight")); // b moves to a second group
            assertEquals(2, (int) FxTestSupport.callOnFx(area::groupCount), "precondition: two groups");
            FxTestSupport.callOnFx(() -> {
                bufA.getArea().appendText("edit a\n");
                bufB.getArea().appendText("edit b\n");
                FxTestSupport.invoke(fx.controller, "onNew");
                activeBuffer(fx).getArea().replaceText("untitled text");
                return null;
            });

            FxTestSupport.runOnFx(() -> registry.run("file.saveAll"));
            assertTrue(waitFor(() -> tr("status.saveAll.partial", "2", "0", "1").equals(echo(fx))), echo(fx));

            assertEquals("a\nedit a\n", Files.readString(a));
            assertEquals("b\nedit b\n", Files.readString(b));
            assertFalse(FxTestSupport.callOnFx(bufA::isDirty));
            assertFalse(FxTestSupport.callOnFx(bufB::isDirty));
            assertTrue(FxTestSupport.callOnFx(() -> activeBuffer(fx).isDirty()), "the untitled buffer stays unsaved");
        }
    }

    @Test
    void saveAllSaysSoWhenEverythingWasSavedOrNothingNeededIt(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
            EditorBuffer buffer = open(async, fx, a);
            CommandRegistry registry = registry(fx);

            FxTestSupport.runOnFx(() -> registry.run("file.saveAll"));
            assertEquals(tr("status.saveAll.none"), echo(fx));

            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("x\n"));
            FxTestSupport.runOnFx(() -> registry.run("file.saveAll"));
            assertTrue(waitFor(() -> tr("status.saveAll.done", "1").equals(echo(fx))), echo(fx));
            assertEquals("a\nx\n", Files.readString(a));
        }
    }

    // --- Revert ---------------------------------------------------------------------------------------------

    @Test
    void revertAsksBeforeDiscardingUnsavedChanges(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("doc.txt"), "one\ntwo\n");
            EditorBuffer buffer = open(async, fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "unsaved\n"));
            FileWorkflowCoordinator workflows = workflows(fx);
            CommandRegistry registry = registry(fx);

            workflows.confirmRevert = b -> false;
            FxTestSupport.runOnFx(() -> registry.run("file.revert"));
            async.awaitFx();
            assertEquals("unsaved\none\ntwo\n", FxTestSupport.callOnFx(buffer::getContent), "declined");

            workflows.confirmRevert = b -> true;
            FxTestSupport.runOnFx(() -> registry.run("file.revert"));
            assertTrue(waitFor(() -> "one\ntwo\n".equals(buffer.getContent())));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertTrue(waitFor(() -> tr("status.revert.done", "doc.txt").equals(echo(fx))), echo(fx));
        }
    }

    @Test
    void revertingACleanBufferReadsTheDiskWithoutAsking(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("doc.txt"), "old\n");
            EditorBuffer buffer = open(async, fx, file);
            Files.writeString(file, "changed outside\n");
            FileWorkflowCoordinator workflows = workflows(fx);
            workflows.confirmRevert = b -> {
                throw new AssertionError("a clean buffer has nothing to lose");
            };
            CommandRegistry registry = registry(fx);

            FxTestSupport.runOnFx(() -> registry.run("file.revert"));

            assertTrue(waitFor(() -> "changed outside\n".equals(buffer.getContent())));
        }
    }

    @Test
    void revertExplainsWhyThereIsNothingToRevertTo(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            CommandRegistry registry = registry(fx);
            FxTestSupport.callOnFx(() -> {
                FxTestSupport.invoke(fx.controller, "onNew");
                return null;
            });
            String untitled = FxTestSupport.callOnFx(() -> activeBuffer(fx).getTitle());
            FxTestSupport.runOnFx(() -> registry.run("file.revert"));
            assertEquals(tr("status.revert.untitled", untitled), echo(fx));

            Path file = Files.writeString(dir.resolve("gone.txt"), "x\n");
            open(async, fx, file);
            Files.delete(file);
            FxTestSupport.runOnFx(() -> registry.run("file.revert"));
            assertEquals(tr("status.revert.missing", "gone.txt"), echo(fx));
        }
    }

    // --- Reopen Closed Tab ----------------------------------------------------------------------------------

    @Test
    void reopenClosedTabBringsTheFileBackWithItsCaret(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("doc.txt"), "first line\nsecond line\n");
            EditorBuffer buffer = open(async, fx, file);
            CommandRegistry registry = registry(fx);
            FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(14));
            FxTestSupport.runOnFx(() -> registry.run("buffer.close"));
            assertNull(FxTestSupport.callOnFx(() -> tabFor(fx, file)), "closed");

            FxTestSupport.runOnFx(() -> registry.run("buffer.reopenClosed"));

            assertTrue(waitFor(() -> {
                EditorBuffer now = activeBuffer(fx);
                return now != null
                        && file.equals(now.getPath())
                        && !now.isLoading()
                        && now.getArea().getCaretPosition() == 14;
            }));
            FxTestSupport.runOnFx(() -> registry.run("buffer.reopenClosed"));
            assertEquals(tr("status.reopenClosed.none"), echo(fx));
        }
    }

    @Test
    void reopenClosedTabSkipsADeletedFileAndFocusesOneOpenAgain(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
            Path b = Files.writeString(dir.resolve("b.txt"), "b\n");
            Path c = Files.writeString(dir.resolve("c.txt"), "c\n");
            open(async, fx, a);
            open(async, fx, b);
            open(async, fx, c);
            MainController controller = fx.controller;
            FxTestSupport.runOnFx(() -> {
                controller.closeTab(tabFor(fx, a));
                controller.closeTab(tabFor(fx, b));
                controller.closeTab(tabFor(fx, c));
            });
            Files.delete(c);
            open(async, fx, b); // reopened by hand meanwhile

            FxTestSupport.runOnFx(controller::reopenClosedTab); // c is gone; b is open: select it
            assertEquals(b, FxTestSupport.callOnFx(() -> activeBuffer(fx).getPath()));

            FxTestSupport.runOnFx(controller::reopenClosedTab); // then a
            assertTrue(waitFor(
                    () -> activeBuffer(fx) != null && a.equals(activeBuffer(fx).getPath())));
        }
    }

    @Test
    void onlyTabsTheUserClosesAreRemembered(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path a = Files.writeString(dir.resolve("a.txt"), "a\n");
            open(async, fx, a);
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            // A tab removed by the window itself (window close, project switch, a view swap) is not a close.
            FxTestSupport.runOnFx(() -> area.remove(tabFor(fx, a)));
            assertEquals(0, (int) FxTestSupport.callOnFx(() -> fx.controller.closedTabs.size()));
        }
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private static EditorBuffer open(AsyncTestScope async, FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = workflows(fx);
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        CountDownLatch loaded = new CountDownLatch(1);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            workflows.openPath(file);
            EditorBuffer opening = (EditorBuffer) area.selectedTab().getUserData();
            workflows.afterBufferLoad(opening, loaded::countDown);
            return opening;
        });
        async.await(loaded, "document load");
        async.awaitFx();
        return buffer;
    }

    private static FileWorkflowCoordinator workflows(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "fileWorkflows");
    }

    private static CommandRegistry registry(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "registry");
    }

    private static EditorBuffer activeBuffer(FxWindowFixture fx) {
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        Tab tab = area.selectedTab();
        return tab != null && tab.getUserData() instanceof EditorBuffer b ? b : null;
    }

    private static Tab tabFor(FxWindowFixture fx, Path file) {
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        return area.tabs().stream()
                .filter(t -> t.getUserData() instanceof EditorBuffer b && file.equals(b.getPath()))
                .findFirst()
                .orElse(null);
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }

    /** Polls a condition on the FX thread: saves and reloads finish on a worker and come back later. */
    private static boolean waitFor(Callable<Boolean> onFx) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            if (Boolean.TRUE.equals(FxTestSupport.callOnFx(onFx))) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }
}
