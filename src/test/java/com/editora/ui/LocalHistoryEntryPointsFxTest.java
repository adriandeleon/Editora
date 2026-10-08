package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.config.HistoryRevision;
import com.editora.editor.EditorBuffer;
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
 * The ways into Local History in a real window: the {@code tool.fileHistory} command (which toggles), a tab's
 * right-click menu, and the differently titled questions for a new label and for renaming one.
 */
@Tag("fx")
class LocalHistoryEntryPointsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Window(GitFeatureFx w, HistoryCoordinator history, ToolWindowManager toolWindows, ToolWindow tw) {
        boolean open() throws Exception {
            return FxTestSupport.callOnFx(() -> toolWindows.isOpen(tw));
        }

        void run(String command) throws Exception {
            FxTestSupport.runOnFx(() -> {
                CommandRegistry registry = FxTestSupport.field(w.fx.controller, "registry");
                assertTrue(registry.run(command), command);
            });
        }
    }

    private static Window window(AsyncTestScope async) throws Exception {
        FxWindowFixture fx = async.own(FxWindowFixture.create());
        GitFeatureFx w = GitFeatureFx.attach(fx);
        return new Window(
                w,
                FxTestSupport.field(fx.controller, "historyCoordinator"),
                FxTestSupport.field(fx.controller, "toolWindows"),
                FxTestSupport.field(fx.controller, "fileHistoryToolWindow"));
    }

    private static void open(AsyncTestScope async, Window win, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> win.w().fx.controller.openAndNavigate(file, 0));
        OverlayTestKit.await(
                async,
                "the tab of " + file.getFileName(),
                () -> win.w().active() != null && file.equals(win.w().active().getPath()));
    }

    /** C11: the command is described as showing or hiding the window, and does both. */
    @Test
    void theToolWindowCommandOpensAndThenCloses(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("notes.txt"), "one\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            open(async, win, file);
            assertFalse(win.open());
            win.run("tool.fileHistory");
            assertTrue(win.open(), "the first run shows it");
            win.run("tool.fileHistory");
            assertFalse(win.open(), "the second run hides it");
            win.run("tool.fileHistory");
            assertTrue(win.open());

            // Other entry points never close it: they ask for a file's history and end with it on screen.
            FxTestSupport.runOnFx(() -> win.history().showForPath(file));
            assertTrue(win.open());
        }
    }

    @Test
    void toggleRunsExactlyOneOfItsTwoActions() {
        StringBuilder ran = new StringBuilder();
        WindowCommandRegistrar.toggleLocalHistory(true, () -> ran.append("close"), () -> ran.append("show"));
        assertEquals("close", ran.toString());
        ran.setLength(0);
        WindowCommandRegistrar.toggleLocalHistory(false, () -> ran.append("close"), () -> ran.append("show"));
        assertEquals("show", ran.toString());
    }

    /**
     * C11 / D6: a tab's menu offers Local History beside Git's, for the tab that was right-clicked — a
     * background tab comes forward, because the tool window follows the active file.
     */
    @Test
    void aTabsMenuShowsThatFilesLocalHistory(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path first = Files.writeString(dir.resolve("first.txt"), "one\n");
        Path second = Files.writeString(dir.resolve("second.txt"), "two\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            open(async, win, first);
            FxTestSupport.runOnFx(() -> win.history().record(first, "one\n", HistoryRevision.REASON_SAVE));
            open(async, win, second);
            MainController c = win.w().fx.controller;

            ContextMenu menu = new ContextMenu();
            MenuItem item = FxTestSupport.callOnFx(() -> {
                EditorBuffer background =
                        (EditorBuffer) FxTestSupport.call(c, "openBufferFor", new Class<?>[] {Path.class}, first);
                TabContextMenu.build(c, new Tab(), background, menu);
                menu.getOnShowing().handle(null);
                List<String> labels =
                        menu.getItems().stream().map(MenuItem::getText).toList();
                int local = labels.indexOf(tr("project.menu.localHistory"));
                assertTrue(local >= 0, labels.toString());
                assertTrue(menu.getItems().get(local + 1) instanceof Menu, "it sits beside the Git submenu");
                assertEquals(tr("project.menu.git"), labels.get(local + 1));
                return menu.getItems().get(local);
            });
            assertTrue(FxTestSupport.callOnFx(item::isVisible));
            assertFalse(FxTestSupport.callOnFx(item::isDisable));

            FxTestSupport.runOnFx(item::fire);
            OverlayTestKit.await(
                    async,
                    "the right-clicked file to come forward",
                    () -> win.w().active() != null
                            && first.equals(win.w().active().getPath()));
            assertTrue(win.open(), "and its Local History is on screen");

            // With the feature off the entry stays where it is, greyed out — as in the Project tree.
            FxTestSupport.runOnFx(() -> {
                win.w().fx.shared.getSettings().setLocalHistory(false);
                menu.getOnShowing().handle(null);
            });
            assertTrue(FxTestSupport.callOnFx(item::isDisable));
        }
    }

    /** C14: "Put Label" asks for a new labelled revision; renaming an existing one is asked as "Edit Label". */
    @Test
    void renamingALabelIsNotTitledPutLabel(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("notes.txt"), "one\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            open(async, win, file);
            FxTestSupport.runOnFx(win.history()::putLabel);
            assertEquals(
                    tr("history.label.title"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "alpha"));
            HistoryCoordinator.Ops ops = FxTestSupport.field(win.history(), "ops");
            OverlayTestKit.await(
                    async,
                    "the labelled revision",
                    () -> ops.historyMap().values().stream().anyMatch(list -> !list.isEmpty()));
            HistoryRevision alpha = FxTestSupport.callOnFx(() -> ops.historyMap().values().stream()
                    .flatMap(List::stream)
                    .findFirst()
                    .orElseThrow());

            FxTestSupport.runOnFx(() -> win.history().editLabel(alpha));
            assertEquals(
                    tr("history.label.editTitle"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            assertNotEquals(tr("history.label.title"), tr("history.label.editTitle"));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene())); // unchanged
        }
    }
}
