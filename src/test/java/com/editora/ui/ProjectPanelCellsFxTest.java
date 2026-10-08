package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import com.editora.git.GitFileStatus;
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
 * What a row of the Project tree shows — unsaved, open, current, Git status, bookmark and note markers — and
 * what its right-click menu and a drop onto it do.
 */
@Tag("fx")
class ProjectPanelCellsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Marker state a test sets per path. */
    private static final class Markers implements ProjectPanel.MarkerActions {
        boolean notesEnabled = true;
        final List<Path> bookmarked = new ArrayList<>();
        final List<Path> noted = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        String tooltip = "";

        @Override
        public boolean personalNotesEnabled() {
            return notesEnabled;
        }

        @Override
        public boolean hasBookmarks(Path file) {
            return bookmarked.contains(file);
        }

        @Override
        public boolean hasPersonalNotes(Path file) {
            return noted.contains(file);
        }

        @Override
        public String personalNotesTooltip(Path path) {
            return tooltip;
        }

        @Override
        public void addBookmark(Path file) {
            calls.add("bookmark " + file.getFileName());
        }

        @Override
        public void addPersonalNote(Path file) {
            calls.add("note " + file.getFileName());
        }
    }

    private static boolean has(TreeCell<Path> cell, String styleClass) {
        return cell.getStyleClass().contains(styleClass);
    }

    private static boolean hasMarker(TreeCell<Path> cell, String styleClass) {
        return !cell.getGraphic().lookupAll("." + styleClass).isEmpty();
    }

    @Test
    void aRowIsStyledAsAFolderOrAFileAndAnEmptiedRowShowsNothing(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                TreeCell<Path> cell = r.cell(r.docs);
                assertTrue(has(cell, "folder-cell") && !has(cell, "file-cell"));
                assertEquals(List.of("docs"), ProjectPanelRig.labels(cell));
                assertEquals("docs", cell.getAccessibleText());
                assertNull(cell.getText(), "the name lives in the graphic, beside the icon");

                cell.updateIndex(r.tree.getRow(r.item(r.alpha)));
                assertTrue(has(cell, "file-cell") && !has(cell, "folder-cell"), "a recycled row swaps its class");
                assertEquals(List.of("alpha.txt"), ProjectPanelRig.labels(cell));

                cell.getStyleClass().add("project-drop-target");
                cell.updateIndex(-1);
                assertNull(cell.getGraphic());
                assertNull(cell.getAccessibleText());
                assertFalse(has(cell, "file-cell") || has(cell, "project-drop-target"), "nothing stale is left");
            });
        }
    }

    @Test
    void anUnsavedFileIsMarkedAndThatWinsOverOpenAndGitStatus(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.modified.add(r.alpha);
                r.open.add(r.alpha);
                r.panel.setGitStatus(Map.of(r.alpha, GitFileStatus.MODIFIED));

                TreeCell<Path> cell = r.cell(r.alpha);

                assertEquals(List.of("• alpha.txt", "◌"), ProjectPanelRig.labels(cell));
                assertEquals("• alpha.txt", cell.getAccessibleText());
                assertTrue(has(cell, "modified-file"));
                assertFalse(has(cell, "project-open-file") || has(cell, "git-status-modified"));
            });
        }
    }

    @Test
    void anOpenFileAndTheCurrentOneCarryDifferentMarkers(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.open.add(r.alpha);
                r.open.add(r.beta);
                r.panel.setActiveFile(r.beta);
                r.panel.setActiveFile(r.beta); // unchanged: nothing to re-render

                TreeCell<Path> open = r.cell(r.alpha);
                assertEquals(List.of("alpha.txt", "◌"), ProjectPanelRig.labels(open));
                assertTrue(has(open, "project-open-file") && !has(open, "project-current-open-file"));

                TreeCell<Path> current = r.cell(r.beta);
                assertEquals(List.of("beta.txt", "◉"), ProjectPanelRig.labels(current));
                assertTrue(has(current, "project-current-open-file") && !has(current, "project-open-file"));

                r.panel.setActiveFile(null);
                assertEquals(
                        List.of("beta.txt", "◌"), ProjectPanelRig.labels(r.cell(r.beta)), "open, no longer current");

                TreeCell<Path> folder = r.cell(r.docs);
                r.open.add(r.docs);
                folder.updateIndex(-1);
                folder.updateIndex(r.tree.getRow(r.item(r.docs)));
                assertEquals(List.of("docs"), ProjectPanelRig.labels(folder), "a folder is never an open file");
            });
        }
    }

    @Test
    void gitStatusColoursAChangedFileAndEveryFolderAboveIt(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.item(r.src).setExpanded(true));
            r.awaitChildren(r.src, 2);
            FxTestSupport.runOnFx(() -> {
                r.panel.setGitStatus(Map.of(r.helper, GitFileStatus.UNTRACKED, r.main, GitFileStatus.MODIFIED));

                TreeCell<Path> changed = r.cell(r.main);
                assertTrue(has(changed, "git-status-modified"));
                assertEquals(
                        List.of("M"),
                        changed.getGraphic().lookupAll(".git-status-letter").stream()
                                .map(n -> ((javafx.scene.control.Label) n).getText())
                                .toList(),
                        "the status letter sits beside the icon");
                assertTrue(has(r.cell(r.src), "git-status-dir-changed"));
                assertTrue(has(r.cell(r.util), "git-status-dir-changed"), "every folder on the way down");
                assertFalse(has(r.cell(r.docs), "git-status-dir-changed"), "a folder with nothing changed");
                assertFalse(has(r.cell(r.root), "git-status-dir-changed"), "the root is not marked");
                TreeCell<Path> clean = r.cell(r.alpha);
                assertTrue(has(clean, "file-cell"));
                assertFalse(clean.getStyleClass().stream().anyMatch(c -> c.startsWith("git-status-")));

                r.panel.setGitStatus(null);
                assertFalse(has(r.cell(r.main), "git-status-modified"), "no status (Git off) clears the colouring");
                assertFalse(has(r.cell(r.src), "git-status-dir-changed"));
            });
        }
    }

    @Test
    void bookmarkAndNoteMarkersFollowTheMarkerStores(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                Markers markers = new Markers();
                r.panel.setMarkerActions(markers);
                assertFalse(hasMarker(r.cell(r.alpha), "project-bookmark-indicator"));

                markers.bookmarked.add(r.alpha);
                markers.noted.add(r.alpha);
                markers.noted.add(r.docs);
                TreeCell<Path> file = r.cell(r.alpha);
                assertTrue(hasMarker(file, "project-bookmark-indicator"));
                assertTrue(hasMarker(file, "project-note-indicator"));
                assertNull(file.getTooltip(), "a file's notes are read in the editor, not from a tooltip");

                TreeCell<Path> folder = r.cell(r.docs);
                assertTrue(hasMarker(folder, "project-note-indicator"));
                assertNull(folder.getTooltip(), "no note text, no tooltip");
                markers.tooltip = "Read the guide first";
                folder = r.cell(r.docs);
                assertNotNull(folder.getTooltip(), "a folder's note is shown where the folder is");

                markers.notesEnabled = false;
                TreeCell<Path> off = r.cell(r.alpha);
                assertTrue(hasMarker(off, "project-bookmark-indicator"));
                assertFalse(hasMarker(off, "project-note-indicator"), "Personal Notes switched off hides its marker");

                r.panel.setMarkerActions(null);
                assertFalse(hasMarker(r.cell(r.alpha), "project-bookmark-indicator"));
            });
        }
    }

    @Test
    void theMenuAddsBookmarksAndNotesAndRevealsAndOpensATerminal(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                ContextMenu bare = r.menu(r.item(r.alpha), false, false);
                assertNull(ProjectPanelRig.entry(bare.getItems(), "project.menu.addBookmark"));
                assertNull(ProjectPanelRig.entry(bare.getItems(), "project.menu.revealInFileManager"));
                assertNull(ProjectPanelRig.entry(bare.getItems(), "project.menu.openTerminal"));
                assertNull(ProjectPanelRig.entry(bare.getItems(), "project.menu.undoMove"), "nothing was moved yet");
                assertNotNull(ProjectPanelRig.entry(bare.getItems(), "project.menu.delete"));

                Markers markers = new Markers();
                List<String> calls = markers.calls;
                r.panel.setMarkerActions(markers);
                r.panel.setOnReveal((path, isDir) -> calls.add("reveal " + path.getFileName() + " " + isDir));
                r.panel.setOnOpenTerminal((path, isDir) -> calls.add("terminal " + path.getFileName() + " " + isDir));

                ContextMenu file = r.menu(r.item(r.alpha), false, false);
                ProjectPanelRig.entry(file.getItems(), "project.menu.addBookmark")
                        .fire();
                MenuItem note = ProjectPanelRig.entry(file.getItems(), "project.menu.addPersonalNote");
                assertFalse(note.isDisable());
                note.fire();
                ProjectPanelRig.entry(file.getItems(), "project.menu.revealInFileManager")
                        .fire();
                ProjectPanelRig.entry(file.getItems(), "project.menu.openTerminal")
                        .fire();
                assertEquals(
                        List.of(
                                "bookmark alpha.txt",
                                "note alpha.txt",
                                "reveal alpha.txt false",
                                "terminal alpha.txt false"),
                        calls);

                calls.clear();
                markers.notesEnabled = false;
                ContextMenu folder = r.menu(r.item(r.docs), true, false);
                assertNull(ProjectPanelRig.entry(folder.getItems(), "project.menu.delete"), "delete is files-only");
                ProjectPanelRig.entry(folder.getItems(), "project.menu.addFolderBookmark")
                        .fire();
                assertTrue(
                        ProjectPanelRig.entry(folder.getItems(), "project.menu.addFolderPersonalNote")
                                .isDisable(),
                        "Personal Notes switched off");
                ProjectPanelRig.entry(folder.getItems(), "project.menu.revealInFileManager")
                        .fire();
                ProjectPanelRig.entry(folder.getItems(), "project.menu.openTerminal")
                        .fire();
                assertEquals(List.of("bookmark docs", "reveal docs true", "terminal docs true"), calls);
            });
        }
    }

    @Test
    void aFoldersGitAndHistoryEntriesFollowTheFeatureTogglesAndItsStatus(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            AtomicReference<GitPathScope> scope = new AtomicReference<>(GitPathScope.ACTIVE);
            AtomicBoolean history = new AtomicBoolean(true);
            ProjectPanel.FileActions actions = (ProjectPanel.FileActions) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {ProjectPanel.FileActions.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "gitScope" -> scope.get();
                        case "localHistoryEnabled" -> history.get();
                        default -> null;
                    });
            FxTestSupport.runOnFx(() -> {
                r.panel.setFileActions(actions);
                ContextMenu menu = r.menu(r.item(r.src), true, false);
                MenuItem localHistory = ProjectPanelRig.entry(menu.getItems(), "project.menu.localHistory");
                MenuItem git = ProjectPanelRig.entry(menu.getItems(), "project.menu.git");
                MenuItem revert = ProjectPanelRig.entry(menu.getItems(), "project.menu.git.revert");
                Runnable showing = () -> menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));

                showing.run();
                assertFalse(localHistory.isDisable() || git.isDisable());
                assertTrue(revert.isDisable(), "nothing under the folder has changed: nothing to revert");

                r.panel.setGitStatus(Map.of(r.main, GitFileStatus.MODIFIED));
                showing.run();
                assertFalse(revert.isDisable(), "a changed file under the folder can be reverted");

                r.panel.setGitStatus(Map.of());
                scope.set(GitPathScope.OTHER);
                showing.run();
                assertFalse(revert.isDisable(), "another repository's status is unknown here: the action decides");

                scope.set(GitPathScope.NONE);
                history.set(false);
                showing.run();
                assertTrue(git.isDisable() && localHistory.isDisable());
            });
        }
    }

    @Test
    void aFilesGitAndHistoryEntriesFollowTheFeatureTogglesAndItsStatus(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            AtomicReference<GitPathScope> scope = new AtomicReference<>(GitPathScope.ACTIVE);
            AtomicBoolean history = new AtomicBoolean(false);
            List<String> calls = new ArrayList<>();
            ProjectPanel.FileActions actions = (ProjectPanel.FileActions) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {ProjectPanel.FileActions.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "gitScope" -> scope.get();
                        case "localHistoryEnabled" -> history.get();
                        default -> {
                            calls.add(method.getName() + " " + ((Path) args[0]).getFileName());
                            yield null;
                        }
                    });
            FxTestSupport.runOnFx(() -> {
                r.panel.setFileActions(actions);
                ContextMenu menu = r.menu(r.item(r.alpha), false, false);
                MenuItem localHistory = ProjectPanelRig.entry(menu.getItems(), "project.menu.localHistory");
                MenuItem git = ProjectPanelRig.entry(menu.getItems(), "project.menu.git");
                MenuItem revert = ProjectPanelRig.entry(menu.getItems(), "project.menu.git.revert");
                MenuItem ignore = ProjectPanelRig.entry(menu.getItems(), "project.menu.git.addToGitignore");
                Runnable showing = () -> menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));

                showing.run();
                assertTrue(localHistory.isDisable(), "Local History switched off");
                assertFalse(git.isDisable());
                assertTrue(revert.isDisable() && ignore.isDisable(), "a clean, tracked file");

                r.panel.setGitStatus(Map.of(r.alpha, GitFileStatus.UNTRACKED));
                showing.run();
                assertFalse(revert.isDisable() || ignore.isDisable(), "a new file can be removed or ignored");

                r.panel.setGitStatus(Map.of(r.alpha, GitFileStatus.MODIFIED));
                showing.run();
                assertFalse(revert.isDisable());
                assertTrue(ignore.isDisable(), "a tracked file cannot be ignored away");

                scope.set(GitPathScope.NONE);
                history.set(true);
                showing.run();
                assertTrue(git.isDisable());
                assertFalse(localHistory.isDisable());

                for (String key : List.of(
                        "project.menu.localHistory",
                        "project.menu.git.stage",
                        "project.menu.git.unstage",
                        "project.menu.git.revert",
                        "project.menu.git.addToGitignore",
                        "project.menu.git.annotate",
                        "project.menu.git.fileHistory")) {
                    ProjectPanelRig.entry(menu.getItems(), key).fire();
                }
                assertEquals(
                        List.of(
                                "showLocalHistory alpha.txt",
                                "gitStage alpha.txt",
                                "gitUnstage alpha.txt",
                                "gitRevert alpha.txt",
                                "gitAddToGitignore alpha.txt",
                                "gitAnnotate alpha.txt",
                                "gitShowFileHistory alpha.txt"),
                        calls);

                calls.clear();
                ContextMenu folder = r.menu(r.item(r.docs), true, false);
                ProjectPanelRig.entry(folder.getItems(), "project.menu.localHistory")
                        .fire();
                ProjectPanelRig.entry(folder.getItems(), "project.menu.git.stage")
                        .fire();
                ProjectPanelRig.entry(folder.getItems(), "project.menu.git.revert")
                        .fire();
                assertEquals(List.of("showLocalHistory docs", "gitStage docs", "gitRevert docs"), calls);
            });
        }
    }

    @Test
    void aRightClickOnARowOpensItsMenuAndOnAnEmptyRowOpensNone(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                r.panel.applyCss();
                r.panel.layout();
                TreeCell<Path> row = shownCell(r, r.alpha);
                TreeCell<Path> folderRow = shownCell(r, r.docs);
                TreeCell<Path> empty = r.tree.getCellFactory().call(r.tree);

                ContextMenuEvent onEmpty = contextEvent();
                empty.getOnContextMenuRequested().handle(onEmpty);
                assertFalse(onEmpty.isConsumed());
                assertTrue(shownMenus().isEmpty());

                ContextMenuEvent onFile = contextEvent();
                row.getOnContextMenuRequested().handle(onFile);
                assertTrue(onFile.isConsumed());
                List<ContextMenu> menus = shownMenus();
                assertEquals(1, menus.size());
                assertNotNull(ProjectPanelRig.entry(menus.get(0).getItems(), "project.menu.delete"), "a file's menu");
                assertNull(ProjectPanelRig.entry(menus.get(0).getItems(), "project.menu.newFolder"));
                menus.get(0).hide();

                folderRow.getOnContextMenuRequested().handle(contextEvent());
                menus = shownMenus();
                assertEquals(1, menus.size());
                assertNotNull(ProjectPanelRig.entry(menus.get(0).getItems(), "project.menu.newFolder"), "a folder's");
                assertNull(ProjectPanelRig.entry(menus.get(0).getItems(), "project.menu.delete"));
                menus.get(0).hide();
            });
        }
    }

    @Test
    void droppingADraggedFileOnAFolderRowMovesItThere(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            Path moved = r.docs.resolve("alpha.txt");
            FxTestSupport.runOnFx(() -> {
                TreeCell<Path> folder = r.cell(r.docs);
                TreeCell<Path> file = r.cell(r.beta);

                folder.getOnDragOver().handle(drag(DragEvent.DRAG_OVER));
                assertFalse(has(folder, "project-drop-target"), "nothing is being dragged");
                DragEvent nothing = drag(DragEvent.DRAG_DROPPED);
                folder.getOnDragDropped().handle(nothing);
                assertFalse(nothing.isDropCompleted());

                dragging(r, List.of(r.alpha)); // what a drag started on alpha.txt carries
                DragEvent over = drag(DragEvent.DRAG_OVER);
                folder.getOnDragOver().handle(over);
                assertTrue(over.isConsumed());
                assertTrue(has(folder, "project-drop-target"), "the folder under the pointer lights up");
                folder.getOnDragOver().handle(drag(DragEvent.DRAG_OVER));
                assertEquals(
                        1,
                        folder.getStyleClass().stream()
                                .filter("project-drop-target"::equals)
                                .count());
                folder.getOnDragExited().handle(drag(DragEvent.DRAG_EXITED));
                assertFalse(has(folder, "project-drop-target"));

                file.getOnDragOver().handle(drag(DragEvent.DRAG_OVER));
                assertFalse(has(file, "project-drop-target"), "a file row is not a place to drop");
                DragEvent onFile = drag(DragEvent.DRAG_DROPPED);
                file.getOnDragDropped().handle(onFile);
                assertFalse(onFile.isDropCompleted());

                TreeCell<Path> rootRow = r.cell(r.root);
                rootRow.getOnDragOver().handle(drag(DragEvent.DRAG_OVER));
                assertFalse(has(rootRow, "project-drop-target"), "alpha.txt already lives in the root");

                DragEvent drop = drag(DragEvent.DRAG_DROPPED);
                folder.getOnDragDropped().handle(drop);
                assertTrue(drop.isDropCompleted() && drop.isConsumed());
                assertTrue(Files.exists(r.alpha), "the move itself runs after the drop event has returned");
            });
            r.await("the dropped file to be moved", () -> Files.exists(moved));
            FxTestSupport.runOnFx(() -> {
                assertFalse(Files.exists(r.alpha));
                assertEquals(List.of(List.of(r.alpha, moved)), r.renamed, "an open tab follows the file");
                assertEquals(List.of(tr("project.moved", 1, "docs")), r.status);

                TreeCell<Path> folder = r.cell(r.docs);
                folder.getStyleClass().add("project-drop-target");
                DragEvent done = drag(DragEvent.DRAG_DONE);
                folder.getOnDragDone().handle(done);
                assertTrue(done.isConsumed());
                assertFalse(has(folder, "project-drop-target"));
                folder.getOnDragOver().handle(drag(DragEvent.DRAG_OVER));
                assertFalse(has(folder, "project-drop-target"), "the drag is over: nothing is carried any more");

                ContextMenu menu = r.menu(r.item(r.beta), false, false);
                ProjectPanelRig.entry(menu.getItems(), "project.menu.undoMove").fire();
                assertTrue(Files.exists(r.alpha) && !Files.exists(moved), "Undo Move puts it back");
                assertEquals(tr("project.moveUndone", 1), r.status.get(r.status.size() - 1));
            });
        }
    }

    @Test
    void nothingIsDraggedFromTheProjectRootOrFromFilterResultsAndNothingDropsOnThem(@TempDir Path dir)
            throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                TreeCell<Path> rootRow = r.cell(r.root);
                MouseEvent detected = dragDetected();
                rootRow.getOnDragDetected().handle(detected);
                assertFalse(detected.isConsumed(), "the project folder itself cannot be dragged away");
                TreeCell<Path> empty = r.tree.getCellFactory().call(r.tree);
                empty.getOnDragDetected().handle(detected);
                assertFalse(detected.isConsumed());
                assertEquals(List.of(), FxTestSupport.field(r.panel, "draggedPaths"));

                r.filter.setText("helper");
            });
            r.await("the search result", () -> r.item(r.helper) != null);
            FxTestSupport.runOnFx(() -> {
                TreeCell<Path> match = r.cell(r.helper);
                MouseEvent detected = dragDetected();
                match.getOnDragDetected().handle(detected);
                assertFalse(detected.isConsumed(), "a flat result list is not a tree to rearrange");

                dragging(r, List.of(r.alpha));
                TreeCell<Path> rootRow = r.cell(r.root);
                rootRow.getOnDragOver().handle(drag(DragEvent.DRAG_OVER));
                assertFalse(has(rootRow, "project-drop-target"));
                DragEvent drop = drag(DragEvent.DRAG_DROPPED);
                rootRow.getOnDragDropped().handle(drop);
                assertFalse(drop.isDropCompleted());
                assertTrue(Files.exists(r.alpha));
            });
        }
    }

    /** The cell the shown tree renders {@code path} with. FX thread, after a layout pass. */
    @SuppressWarnings("unchecked")
    private static TreeCell<Path> shownCell(ProjectPanelRig r, Path path) {
        for (Node node : r.tree.lookupAll(".tree-cell")) {
            if (node instanceof TreeCell<?> cell && path.equals(cell.getItem())) {
                return (TreeCell<Path>) cell;
            }
        }
        throw new AssertionError("no row on screen for " + path);
    }

    private static List<ContextMenu> shownMenus() {
        List<ContextMenu> menus = new ArrayList<>();
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof ContextMenu menu && menu.isShowing()) {
                menus.add(menu);
            }
        }
        return menus;
    }

    /** Puts the panel in the state a drag that started on {@code paths} leaves it in. */
    private static void dragging(ProjectPanelRig r, List<Path> paths) {
        // A drag can only be started from inside the scene's own mouse handling, which a headless test has
        // no way to drive; what it records is this one field.
        try {
            var field = ProjectPanel.class.getDeclaredField("draggedPaths");
            field.setAccessible(true);
            field.set(r.panel, paths);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static DragEvent drag(javafx.event.EventType<DragEvent> type) {
        return new DragEvent(type, null, 0, 0, 0, 0, TransferMode.MOVE, null, null, null);
    }

    private static ContextMenuEvent contextEvent() {
        return new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 200, 200, false, null);
    }

    private static MouseEvent dragDetected() {
        return new MouseEvent(
                MouseEvent.DRAG_DETECTED,
                0,
                0,
                0,
                0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null);
    }
}
