package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.ListView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.config.HistoryRevision;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Local History window keeps the user's place in a real window (review U1, U2): relabelling the shown revision,
 * a save of another file in a listed folder, and restoring a deleted file from the folder view all leave the
 * selected revision selected and its diff up.
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
class LocalHistoryKeepsPlaceFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Window(GitFeatureFx w, HistoryCoordinator history, HistoryCoordinator.Ops ops) {}

    private static Window window(AsyncTestScope async) throws Exception {
        FxWindowFixture fx = async.own(FxWindowFixture.create());
        GitFeatureFx w = GitFeatureFx.attach(fx);
        HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
        return new Window(w, history, FxTestSupport.field(history, "ops"));
    }

    private static List<HistoryRevision> revisions(Window win, Path file) {
        List<HistoryRevision> out = new ArrayList<>();
        for (List<HistoryRevision> list : win.ops().historyMap().values()) {
            for (HistoryRevision r : list) {
                if (r.path().equals(file.toString())) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    private static void putLabel(AsyncTestScope async, Window win, String typed) throws Exception {
        FxTestSupport.runOnFx(win.history()::putLabel);
        FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), typed));
        async.awaitFx();
    }

    @Test
    void relabellingTheShownRevisionKeepsItSelectedWithItsDiff(@TempDir Path temp) throws Exception {
        Path file = Files.writeString(temp.toRealPath().resolve("notes.txt"), "one\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            FxTestSupport.runOnFx(() -> win.w().fx.controller.openAndNavigate(file, 0));
            OverlayTestKit.await(
                    async,
                    "the tab",
                    () -> win.w().active() != null
                            && file.equals(win.w().active().getPath()));
            EditorBuffer buffer = FxTestSupport.callOnFx(win.w()::active);
            putLabel(async, win, "alpha");
            OverlayTestKit.await(
                    async, "one revision", () -> revisions(win, file).size() == 1);
            FxTestSupport.runOnFx(() -> buffer.getArea().appendText("two\n"));
            putLabel(async, win, "beta");
            OverlayTestKit.await(
                    async, "two revisions", () -> revisions(win, file).size() == 2);
            FxTestSupport.runOnFx(win.history()::showActive);
            FileHistoryPanel panel = FxTestSupport.callOnFx(win.history()::panel);
            ListView<HistoryRevision> list = FxTestSupport.field(panel, "revisions");
            HistoryRevision alpha = FxTestSupport.callOnFx(() -> list.getItems().stream()
                    .filter(r -> "alpha".equals(r.label()))
                    .findFirst()
                    .orElseThrow());
            FxTestSupport.runOnFx(() -> list.getSelectionModel().select(alpha));
            OverlayTestKit.await(async, "its diff", () -> FxTestSupport.field(panel, "pane") != null);

            FxTestSupport.runOnFx(() -> win.history().editLabel(alpha));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "release"));
            async.awaitFx();
            HistoryRevision selected =
                    FxTestSupport.callOnFx(() -> list.getSelectionModel().getSelectedItem());
            assertNotNull(selected, "still selected");
            assertEquals("release", selected.label());
            assertNotNull(FxTestSupport.field(panel, "pane"), "the diff stays up");
        }
    }

    private static TreeItem<Object> firstRevisionOf(TreeView<Object> tree, String display) {
        return tree.getRoot().getChildren().stream()
                .filter(i ->
                        ((FileHistoryPanel.FileGroup) i.getValue()).display().equals(display))
                .findFirst()
                .orElseThrow()
                .getChildren()
                .get(0);
    }

    private static boolean selectedIsOf(TreeView<Object> tree, Path file) {
        TreeItem<Object> sel = tree.getSelectionModel().getSelectedItem();
        return sel != null
                && sel.getValue() instanceof HistoryRevision r
                && r.path().equals(file.toString());
    }

    @Test
    void aFoldersHistoryKeepsTheSelectedRevisionAcrossASaveAndARestore(@TempDir Path temp) throws Exception {
        Path folder = Files.createDirectories(temp.toRealPath().resolve("src"));
        Path x = Files.writeString(folder.resolve("x.txt"), "x1\n");
        Path y = Files.writeString(folder.resolve("y.txt"), "y1\n");
        Path gone = folder.resolve("gone.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async);
            FxTestSupport.runOnFx(() -> {
                win.history().record(x, "x0\n", HistoryRevision.REASON_SAVE);
                win.history().record(y, "y0\n", HistoryRevision.REASON_SAVE);
                win.history().record(gone, "the only copy\n", HistoryRevision.REASON_DELETE);
            });
            OverlayTestKit.await(
                    async,
                    "recorded",
                    () -> revisions(win, x).size() == 1
                            && revisions(win, y).size() == 1
                            && revisions(win, gone).size() == 1);
            FxTestSupport.runOnFx(() -> win.history().showForPath(folder));
            FileHistoryPanel panel = FxTestSupport.callOnFx(win.history()::panel);
            TreeView<Object> tree = FxTestSupport.field(panel, "folderTree");
            OverlayTestKit.await(
                    async,
                    "the folder's tree",
                    () -> tree.isManaged()
                            && tree.getRoot() != null
                            && tree.getRoot().getChildren().size() == 3);
            FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(firstRevisionOf(tree, "x.txt")));
            OverlayTestKit.await(async, "its diff", () -> FxTestSupport.field(panel, "pane") != null);

            // Another file of the folder is saved.
            FxTestSupport.runOnFx(() -> win.history().record(y, "y2\n", HistoryRevision.REASON_AUTOSAVE));
            OverlayTestKit.await(
                    async, "y's second revision", () -> revisions(win, y).size() == 2);
            OverlayTestKit.await(
                    async,
                    "y's second row",
                    () -> firstRevisionOf(tree, "y.txt")
                                    .getParent()
                                    .getChildren()
                                    .size()
                            == 2);
            assertTrue(FxTestSupport.callOnFx(() -> selectedIsOf(tree, x)), "x's revision is still selected");
            assertNotNull(FxTestSupport.field(panel, "pane"), "and its diff is up");

            // The deleted file is restored from the folder view: the view, its row and its diff stay.
            FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(firstRevisionOf(tree, "gone.txt")));
            FxTestSupport.runOnFx(() -> panel.restoreSelected());
            OverlayTestKit.await(async, "the file to come back", () -> Files.exists(gone));
            OverlayTestKit.await(
                    async,
                    "the row to stop saying deleted",
                    () -> !((FileHistoryPanel.FileGroup) firstRevisionOf(tree, "gone.txt")
                                    .getParent()
                                    .getValue())
                            .deleted());
            async.awaitFx();
            assertTrue(
                    FxTestSupport.callOnFx(() -> selectedIsOf(tree, gone)), "the restored revision is still selected");
            assertNotNull(FxTestSupport.field(panel, "pane"));
            assertNotNull(panel.folderShown());
        }
    }
}
