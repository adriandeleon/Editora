package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.editor.MenuChords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project tree's file menu and its commands ({@link ProjectRowCommands}): every row of the menu is tied to the
 * command that does the same to the selected row, and the command does it, or says why not, as the row would.
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(DisposedPanels.class)
class ProjectRowCommandsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A panel over {@code dir} (holding {@code file}), whose Git and Local History actions are recorded. */
    private record Harness(ProjectPanel panel, TreeView<Path> tree, TreeItem<Path> dirItem, TreeItem<Path> fileItem) {}

    private static Harness harness(Path dir, Path file, Map<String, Path> calls) throws Exception {
        ProjectPanel.FileActions actions = (ProjectPanel.FileActions) java.lang.reflect.Proxy.newProxyInstance(
                ProjectRowCommandsFxTest.class.getClassLoader(),
                new Class<?>[] {ProjectPanel.FileActions.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "gitScope" -> GitPathScope.OTHER;
                    case "localHistoryEnabled" -> true;
                    default -> {
                        if (args != null && args.length == 1 && args[0] instanceof Path p) {
                            calls.put(method.getName(), p);
                        }
                        yield method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                    }
                });
        return FxTestSupport.callOnFx(() -> {
            ProjectPanel panel = DisposedPanels.track(new ProjectPanel(p -> {}, (a, b) -> {}, p -> {}, p -> false));
            panel.setFileActions(actions);
            TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            TreeItem<Path> root = new TreeItem<>(dir);
            TreeItem<Path> fileItem = new TreeItem<>(file);
            root.getChildren().add(fileItem);
            root.setExpanded(true);
            tree.setRoot(root);
            return new Harness(panel, tree, root, fileItem);
        });
    }

    @Test
    void everyRowOfTheFileMenuShowsTheCommandThatDoesItToTheSelectedRow(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "x");
        Harness h = harness(dir, file, new ConcurrentHashMap<>());
        for (boolean isDir : new boolean[] {false, true}) {
            ContextMenu menu = FxTestSupport.callOnFx(() -> (ContextMenu) FxTestSupport.call(
                    h.panel(),
                    "contextMenuFor",
                    new Class<?>[] {TreeItem.class, boolean.class, boolean.class},
                    isDir ? h.dirItem() : h.fileItem(),
                    isDir,
                    false));
            List<String> untied = new ArrayList<>();
            collectUntied(menu.getItems(), untied);
            assertTrue(untied.isEmpty(), (isDir ? "folder" : "file") + " menu rows with no command: " + untied);
        }
    }

    /**
     * The rows that name a command: all but the file types of "New ▸", which Project: New File in Selected Folder…
     * reaches through its picker of the same types.
     */
    private static void collectUntied(List<MenuItem> items, List<String> untied) {
        for (MenuItem item : items) {
            if (item instanceof Menu sub) {
                if (!tr("newfile.menu.new").equals(sub.getText())) {
                    collectUntied(sub.getItems(), untied);
                } else {
                    for (MenuItem row : sub.getItems()) {
                        if (row instanceof Menu || row instanceof javafx.scene.control.SeparatorMenuItem) {
                            continue;
                        }
                        boolean fileType = MenuChords.commandOf(row) == null;
                        assertTrue(
                                fileType || MenuChords.commandOf(row).startsWith("project."),
                                "New ▸ row " + row.getText());
                    }
                }
            } else if (!(item instanceof javafx.scene.control.SeparatorMenuItem)
                    && MenuChords.commandOf(item) == null) {
                untied.add(item.getText());
            }
        }
    }

    @Test
    void aCommandActsOnTheSelectedRowAsItsMenuRowDoes(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "x");
        Map<String, Path> calls = new ConcurrentHashMap<>();
        Harness h = harness(dir, file, calls);
        FxTestSupport.runOnFx(() -> {
            h.tree().getSelectionModel().select(h.fileItem());
            assertNull(h.panel().runRowCommand(ProjectRowCommands.Row.GIT_STAGE));
            assertNull(h.panel().runRowCommand(ProjectRowCommands.Row.COMPARE_HEAD));
            assertNull(h.panel().runRowCommand(ProjectRowCommands.Row.GIT_HISTORY));
            h.tree().getSelectionModel().select(h.dirItem());
            assertNull(h.panel().runRowCommand(ProjectRowCommands.Row.LOCAL_HISTORY));
        });
        assertEquals(file, calls.get("gitStage"));
        assertEquals(file, calls.get("gitCompareWithHead"));
        assertEquals(file, calls.get("gitShowFileHistory"));
        assertEquals(dir, calls.get("showLocalHistory"));
    }

    @Test
    void aCommandSaysWhyWhenTheMenuWouldNotOfferItsRow(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.txt"), "x");
        Map<String, Path> calls = new ConcurrentHashMap<>();
        Harness h = harness(dir, file, calls);
        FxTestSupport.runOnFx(() -> {
            h.tree().getSelectionModel().clearSelection();
            assertEquals(tr("status.project.selectRow"), h.panel().runRowCommand(ProjectRowCommands.Row.RENAME));
            h.tree().getSelectionModel().select(h.dirItem());
            String delete = DebugPanelText.bareTitle(tr("command.project.deleteSelected"));
            assertEquals(
                    tr("status.project.rowIsFolder", delete), h.panel().runRowCommand(ProjectRowCommands.Row.DELETE));
            assertEquals(
                    tr("status.project.rowIsFolder", DebugPanelText.bareTitle(tr("command.project.unstageSelected"))),
                    h.panel().runRowCommand(ProjectRowCommands.Row.GIT_UNSTAGE));
            h.tree().getSelectionModel().select(h.fileItem());
            assertEquals(
                    tr(
                            "status.project.rowIsFile",
                            DebugPanelText.bareTitle(tr("command.project.findInSelectedFolder"))),
                    h.panel().runRowCommand(ProjectRowCommands.Row.FIND_IN_FOLDER));
            assertEquals(tr("status.project.noMoveToUndo"), h.panel().runRowCommand(ProjectRowCommands.Row.UNDO_MOVE));
        });
        assertTrue(calls.isEmpty(), "nothing ran: " + calls);
    }

    // --- every row, on every kind of row: what runs, and what is refused -------------------------------------

    /**
     * A panel over {@code root} holding a folder {@code sub} with a modified, an untracked, a clean and an encrypted
     * file, every hook set and recording what it was asked: the file actions, the marker actions, reveal, terminal,
     * New File (through its picker), New From Template, New Maven Project and the name prompt.
     */
    private static final class Rig {
        final Path root;
        final Path sub;
        final Path modified;
        final Path untracked;
        final Path clean;
        final Path encrypted;
        final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicReference<GitPathScope> scope =
                new java.util.concurrent.atomic.AtomicReference<>(GitPathScope.ACTIVE);
        final java.util.concurrent.atomic.AtomicBoolean localHistory =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicBoolean notes = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicBoolean simple = new java.util.concurrent.atomic.AtomicBoolean(false);
        ProjectPanel panel;
        TreeView<Path> tree;
        TreeItem<Path> rootItem;
        TreeItem<Path> subItem;
        final Map<Path, TreeItem<Path>> items = new java.util.HashMap<>();

        Rig(Path root) throws Exception {
            this.root = root;
            this.sub = Files.createDirectories(root.resolve("sub"));
            this.modified = Files.writeString(sub.resolve("changed.txt"), "x");
            this.untracked = Files.writeString(sub.resolve("new.txt"), "x");
            this.clean = Files.writeString(sub.resolve("clean.txt"), "x");
            this.encrypted = Files.writeString(sub.resolve("secret.txt.gpg"), "x");
        }

        /** The panel with every hook set ({@code wired}), or none of them. */
        Rig build(boolean wired) throws Exception {
            FxTestSupport.runOnFx(() -> {
                panel = DisposedPanels.track(new ProjectPanel(p -> {}, (a, b) -> {}, p -> {}, p -> false));
                tree = FxTestSupport.field(panel, "tree");
                try {
                    java.lang.reflect.Field rootField = ProjectPanel.class.getDeclaredField("root");
                    rootField.setAccessible(true);
                    rootField.set(panel, root);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                rootItem = new TreeItem<>(root);
                subItem = new TreeItem<>(sub);
                rootItem.getChildren().add(subItem);
                for (Path f : List.of(modified, untracked, clean, encrypted)) {
                    TreeItem<Path> item = new TreeItem<>(f);
                    subItem.getChildren().add(item);
                    items.put(f, item);
                }
                items.put(root, rootItem);
                items.put(sub, subItem);
                rootItem.setExpanded(true);
                subItem.setExpanded(true);
                tree.setRoot(rootItem);
                if (!wired) {
                    return;
                }
                panel.setFileActions(recording(ProjectPanel.FileActions.class, (method, args) -> switch (method) {
                    case "gitScope" -> scope.get();
                    case "localHistoryEnabled" -> localHistory.get();
                    default -> null;
                }));
                panel.setMarkerActions(recording(ProjectPanel.MarkerActions.class, (method, args) -> switch (method) {
                    case "personalNotesEnabled" -> notes.get();
                    default -> null;
                }));
                panel.setOnReveal((p, dir) -> calls.add("reveal " + p + " " + dir));
                panel.setOnOpenTerminal((p, dir) -> calls.add("terminal " + p + " " + dir));
                panel.setOnNewFile((d, type) -> calls.add("newFile " + d + " " + type.suggestedFileName()));
                panel.setNewFileTypePicker(chosen -> {
                    calls.add("picker");
                    chosen.accept(com.editora.template.NewFileCatalog.topLevel().get(0));
                });
                panel.setOnNewFromTemplate(d -> calls.add("template " + d));
                panel.setOnNewMavenProject(d -> calls.add("maven " + d));
                panel.setPrompt((title, label, initial, onAccept) -> calls.add("prompt " + title + " " + initial));
                panel.setSimpleMode(simple::get);
                panel.setGitStatus(Map.of(
                        modified, com.editora.git.GitFileStatus.MODIFIED,
                        untracked, com.editora.git.GitFileStatus.UNTRACKED));
            });
            return this;
        }

        /** A proxy of {@code type} that records each call with a path argument as "method path". */
        @SuppressWarnings("unchecked")
        <T> T recording(Class<T> type, java.util.function.BiFunction<String, Object[], Object> answers) {
            return (T) java.lang.reflect.Proxy.newProxyInstance(
                    ProjectRowCommandsFxTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                        Object answer = answers.apply(method.getName(), args);
                        if (answer != null) {
                            return answer;
                        }
                        if (args != null && args.length >= 1 && args[0] instanceof Path p) {
                            calls.add(method.getName() + " " + p);
                        }
                        if (method.getReturnType() == boolean.class) {
                            return Boolean.FALSE;
                        }
                        return method.getReturnType() == List.class ? List.of() : null;
                    });
        }

        /** Selects {@code path}'s row and runs {@code row} on it: null when it ran, else the status line. */
        String run(Path path, ProjectRowCommands.Row row) throws Exception {
            return FxTestSupport.callOnFx(() -> {
                tree.getSelectionModel().clearSelection();
                tree.getSelectionModel().select(items.get(path));
                return panel.runRowCommand(row);
            });
        }

        /** What ran since the last call. */
        List<String> take() {
            List<String> seen = List.copyOf(calls);
            calls.clear();
            return seen;
        }
    }

    private static String title(ProjectRowCommands.Row row) {
        return DebugPanelText.bareTitle(tr("command." + row.id()));
    }

    private static String unavailable(ProjectRowCommands.Row row) {
        return tr("status.project.rowUnavailable", title(row));
    }

    @Test
    void eachFileRowRunsWhatItsMenuRowRunsOnTheSelectedFile(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir).build(true);
        Path f = rig.modified;
        List<String> questions = new java.util.concurrent.CopyOnWriteArrayList<>();
        Confirm.answerWith(q -> {
            questions.add(q.title());
            return false; // Cancel: nothing is deleted
        });
        try {
            for (ProjectRowCommands.Row row : List.of(
                    ProjectRowCommands.Row.RENAME,
                    ProjectRowCommands.Row.DELETE,
                    ProjectRowCommands.Row.ENCRYPT,
                    ProjectRowCommands.Row.BOOKMARK,
                    ProjectRowCommands.Row.NOTE,
                    ProjectRowCommands.Row.REVEAL_IN_FILE_MANAGER,
                    ProjectRowCommands.Row.OPEN_TERMINAL,
                    ProjectRowCommands.Row.LOCAL_HISTORY,
                    ProjectRowCommands.Row.GIT_STAGE,
                    ProjectRowCommands.Row.GIT_UNSTAGE,
                    ProjectRowCommands.Row.GIT_REVERT,
                    ProjectRowCommands.Row.COMPARE_HEAD,
                    ProjectRowCommands.Row.COMPARE_BRANCH,
                    ProjectRowCommands.Row.COMPARE_TAG,
                    ProjectRowCommands.Row.COMPARE_REVISION,
                    ProjectRowCommands.Row.ANNOTATE,
                    ProjectRowCommands.Row.GIT_HISTORY)) {
                assertNull(rig.run(f, row), row + " ran");
            }
            assertNull(rig.run(rig.untracked, ProjectRowCommands.Row.GIT_IGNORE), "an untracked file can be ignored");
        } finally {
            Confirm.answerWith(null);
        }
        assertEquals(List.of(tr("project.deleteFileTitle")), questions, "Delete asked first, as its row does");
        assertTrue(Files.exists(f), "and the answer was Cancel");
        assertEquals(
                List.of(
                        "prompt " + tr("project.renameTitle") + " changed.txt",
                        "encrypt " + f,
                        "addBookmark " + f,
                        "addPersonalNote " + f,
                        "reveal " + f + " false",
                        "terminal " + f + " false",
                        "showLocalHistory " + f,
                        "gitStage " + f,
                        "gitUnstage " + f,
                        "gitRevert " + f,
                        "gitCompareWithHead " + f,
                        "gitCompareWithBranch " + f,
                        "gitCompareWithTag " + f,
                        "gitCompareWithRevision " + f,
                        "gitAnnotate " + f,
                        "gitShowFileHistory " + f,
                        "gitAddToGitignore " + rig.untracked),
                rig.take());
    }

    @Test
    void eachFolderRowRunsWhatItsMenuRowRunsOnTheSelectedFolder(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir).build(true);
        Path d = rig.sub;
        for (ProjectRowCommands.Row row : List.of(
                ProjectRowCommands.Row.NEW_FILE,
                ProjectRowCommands.Row.NEW_FOLDER,
                ProjectRowCommands.Row.NEW_FROM_TEMPLATE,
                ProjectRowCommands.Row.NEW_MAVEN_PROJECT,
                ProjectRowCommands.Row.FIND_IN_FOLDER,
                ProjectRowCommands.Row.LOCAL_HISTORY,
                ProjectRowCommands.Row.REVEAL_IN_FILE_MANAGER,
                ProjectRowCommands.Row.GIT_STAGE,
                ProjectRowCommands.Row.GIT_REVERT,
                ProjectRowCommands.Row.COMPARE_HEAD,
                ProjectRowCommands.Row.GIT_HISTORY)) {
            assertNull(rig.run(d, row), row + " ran");
        }
        String firstType = com.editora.template.NewFileCatalog.topLevel().get(0).suggestedFileName();
        assertEquals(
                List.of(
                        "picker",
                        "newFile " + d + " " + firstType,
                        "prompt " + tr("project.newFolderTitle") + " ",
                        "template " + d,
                        "maven " + d,
                        "findInFolder " + d,
                        "showLocalHistory " + d,
                        "reveal " + d + " true",
                        "gitStage " + d,
                        "gitRevert " + d,
                        "gitCompareWithHead " + d,
                        "gitShowFolderHistory " + d),
                rig.take());

        // In Simple mode the folder menu's New ▸ has File… alone: the command creates a plain file, with no picker.
        rig.simple.set(true);
        assertNull(rig.run(d, ProjectRowCommands.Row.NEW_FILE));
        assertEquals(
                List.of("newFile " + d + " " + com.editora.template.NewFileCatalog.PLAIN.suggestedFileName()),
                rig.take());
    }

    @Test
    void aRowTheMenuWouldNotOfferForTheSelectionIsRefusedWithWhy(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir).build(true);
        for (ProjectRowCommands.Row row : List.of(
                ProjectRowCommands.Row.DELETE,
                ProjectRowCommands.Row.ENCRYPT,
                ProjectRowCommands.Row.GIT_UNSTAGE,
                ProjectRowCommands.Row.GIT_IGNORE,
                ProjectRowCommands.Row.ANNOTATE)) {
            assertEquals(tr("status.project.rowIsFolder", title(row)), rig.run(rig.sub, row));
        }
        for (ProjectRowCommands.Row row : List.of(
                ProjectRowCommands.Row.NEW_FILE,
                ProjectRowCommands.Row.NEW_FOLDER,
                ProjectRowCommands.Row.NEW_FROM_TEMPLATE,
                ProjectRowCommands.Row.NEW_MAVEN_PROJECT,
                ProjectRowCommands.Row.FIND_IN_FOLDER)) {
            assertEquals(tr("status.project.rowIsFile", title(row)), rig.run(rig.clean, row));
        }
        assertEquals(tr("status.project.rootCannotBeRenamed"), rig.run(rig.root, ProjectRowCommands.Row.RENAME));
        assertEquals(
                unavailable(ProjectRowCommands.Row.ENCRYPT),
                rig.run(rig.encrypted, ProjectRowCommands.Row.ENCRYPT),
                "an encrypted file has no Encrypt…");
        // The active repository's status grays these out, as the menu does.
        assertEquals(
                unavailable(ProjectRowCommands.Row.GIT_REVERT),
                rig.run(rig.clean, ProjectRowCommands.Row.GIT_REVERT),
                "nothing to revert in a clean file");
        assertEquals(
                unavailable(ProjectRowCommands.Row.GIT_IGNORE),
                rig.run(rig.modified, ProjectRowCommands.Row.GIT_IGNORE),
                "a tracked file is not added to .gitignore");
        assertEquals(
                unavailable(ProjectRowCommands.Row.GIT_REVERT),
                rig.run(rig.root, ProjectRowCommands.Row.GIT_REVERT),
                "the root is not among the changed folders");
        // Another repository's status is unknown: the action is left to report.
        rig.scope.set(GitPathScope.OTHER);
        assertNull(rig.run(rig.clean, ProjectRowCommands.Row.GIT_REVERT));
        rig.scope.set(GitPathScope.NONE);
        assertEquals(tr("status.project.notInGit"), rig.run(rig.clean, ProjectRowCommands.Row.GIT_STAGE));
        rig.scope.set(GitPathScope.ACTIVE);
        rig.notes.set(false);
        assertEquals(unavailable(ProjectRowCommands.Row.NOTE), rig.run(rig.clean, ProjectRowCommands.Row.NOTE));
        rig.localHistory.set(false);
        assertEquals(
                unavailable(ProjectRowCommands.Row.LOCAL_HISTORY),
                rig.run(rig.clean, ProjectRowCommands.Row.LOCAL_HISTORY));
        rig.simple.set(true);
        for (ProjectRowCommands.Row row : List.of(
                ProjectRowCommands.Row.BOOKMARK,
                ProjectRowCommands.Row.OPEN_TERMINAL,
                ProjectRowCommands.Row.GIT_STAGE)) {
            assertEquals(unavailable(row), rig.run(rig.clean, row), row + " is not in the Simple menu");
        }
        for (ProjectRowCommands.Row row : List.of(
                ProjectRowCommands.Row.NEW_FROM_TEMPLATE,
                ProjectRowCommands.Row.NEW_MAVEN_PROJECT,
                ProjectRowCommands.Row.FIND_IN_FOLDER)) {
            assertEquals(unavailable(row), rig.run(rig.sub, row), row + " is not in the Simple menu");
        }
        assertEquals(List.of("gitRevert " + rig.clean), rig.take(), "only the other repository's Revert ran");
    }

    @Test
    void withNoHookSetEveryRowThatNeedsOneIsUnavailable(@TempDir Path dir) throws Exception {
        Rig rig = new Rig(dir).build(false);
        for (ProjectRowCommands.Row row : List.of(
                ProjectRowCommands.Row.NEW_FILE,
                ProjectRowCommands.Row.NEW_FROM_TEMPLATE,
                ProjectRowCommands.Row.NEW_MAVEN_PROJECT,
                ProjectRowCommands.Row.FIND_IN_FOLDER,
                ProjectRowCommands.Row.BOOKMARK,
                ProjectRowCommands.Row.REVEAL_IN_FILE_MANAGER,
                ProjectRowCommands.Row.OPEN_TERMINAL,
                ProjectRowCommands.Row.LOCAL_HISTORY,
                ProjectRowCommands.Row.GIT_STAGE)) {
            assertEquals(unavailable(row), rig.run(rig.sub, row), row.name());
        }
        for (ProjectRowCommands.Row row : List.of(ProjectRowCommands.Row.ENCRYPT, ProjectRowCommands.Row.NOTE)) {
            assertEquals(unavailable(row), rig.run(rig.clean, row), row.name());
        }
        // Rename and New Folder need no hook of the panel's own, only a prompt: none set, nothing happens.
        assertNull(rig.run(rig.clean, ProjectRowCommands.Row.RENAME));
        assertTrue(rig.take().isEmpty());

        // New File with no picker of types (a window that set none): the plain file, as in Simple mode.
        FxTestSupport.runOnFx(() ->
                rig.panel.setOnNewFile((d, type) -> rig.calls.add("newFile " + d + " " + type.suggestedFileName())));
        assertNull(rig.run(rig.sub, ProjectRowCommands.Row.NEW_FILE));
        assertEquals(
                List.of("newFile " + rig.sub + " " + com.editora.template.NewFileCatalog.PLAIN.suggestedFileName()),
                rig.take());
    }
}
