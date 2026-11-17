package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The row commands of this change, run from a real window's registry as the palette runs them: each acts on the
 * selection of an open window, and with none it says why on the status line and opens nothing (KBD-01, MENU-05).
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
class RowCommandsWindowFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void close() throws Exception {
        if (fx != null) {
            fx.close();
            fx = null;
        }
    }

    private void window(Path dir, boolean projects) throws Exception {
        fx = FxWindowFixture.create(dir, shared -> {
            shared.getSettings().setProjectSupport(projects);
            shared.getSettings().setGitSupport(true);
            shared.getSettings().setDebugSupport(true);
            shared.getSettings().setGithubSupport(true);
            shared.getSettings().setLocalHistory(true);
        });
    }

    /** Runs {@code id} and answers the status line it left. FX thread. */
    private String run(String id) {
        CommandRegistry registry = fx.controller.registry;
        assertTrue(registry.get(id).isPresent(), id + " is registered");
        assertTrue(registry.run(id), id + " ran");
        StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
        MessageLog log = FxTestSupport.field(bar, "messageLog");
        return log.entries().isEmpty() ? "" : log.entries().get(0).text();
    }

    private static String notOffered(String statusKey, String id) {
        return tr(statusKey, DebugPanelText.bareTitle(tr("command." + id)));
    }

    @Test
    void theProjectCommandsSayWhyWithNoTreeOrWithProjectsOff(@TempDir Path dir) throws Exception {
        window(dir, false);
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    tr("palette.disabled.featureOff", tr("command.view.toggleProjects")),
                    run("project.renameSelected"));
            fx.controller.config.getSettings().setProjectSupport(true);
            assertNull(fx.controller.projectPanel, "no project, so no tree was built");
            assertEquals(tr("status.project.selectRow"), run("project.deleteSelected"));
            assertEquals(tr("status.project.noMoveToUndo"), run("project.undoMove"));
            assertNull(fx.controller.projectPanel, "and none was built to answer");
        });
    }

    /** In a project window a command acts on the tree's selected row, as its menu row does. */
    @Test
    void inAProjectWindowACommandActsOnTheTreesSelectedRow(@TempDir Path dir) throws Exception {
        window(dir, true);
        Path root = fx.useProject();
        FxTestSupport.runOnFx(() -> {
            ProjectPanel panel = fx.controller.projectPanel();
            javafx.scene.control.TreeView<Path> tree = FxTestSupport.field(panel, "tree");
            tree.setRoot(new javafx.scene.control.TreeItem<>(root));
            tree.getSelectionModel().select(tree.getRoot());
            assertFalse(fx.controller.toolWindows.isOpen(fx.controller.fileHistoryToolWindow));
            String before = run("project.renameSelected"); // the root: refused
            assertEquals(tr("status.project.rootCannotBeRenamed"), before);
            // What the folder row's Show Local History does: this new folder has none, and the window says so.
            assertEquals(
                    tr("status.history.folderEmpty", root.getFileName().toString()),
                    run("project.localHistoryOfSelected"));
        });
    }

    @Test
    void theGitWindowsRowCommandsNeedARowOfAnOpenWindow(@TempDir Path dir) throws Exception {
        window(dir, false);
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("status.git.selectChangedFile"), run("git.openSelected"));
            assertEquals(tr("status.git.selectChangedFile"), run("git.diffSelected"));
            assertEquals(tr("status.git.nothingToDiscardSelected"), run("git.discardSelected"));
            assertEquals(tr("status.git.selectConflictedFile"), run("git.resolveSelected"));
            assertEquals(tr("status.git.selectConflictedFile"), run("git.acceptTheirsSelected"));
            assertFalse(fx.controller.toolWindows.isOpen(fx.controller.gitLogToolWindow));
            assertEquals(tr("status.git.log.selectFile"), run("git.log.openFile"));
            assertEquals(tr("status.git.log.selectFile"), run("git.log.showFileDiff"));
            assertFalse(
                    fx.controller.toolWindows.isOpen(fx.controller.gitLogToolWindow), "a command never opens the log");
        });
    }

    @Test
    void theStashAndManagerCommandsAskForTheRepositoryAsTheirSiblingsDo(@TempDir Path dir) throws Exception {
        window(dir, false);
        FxTestSupport.runOnFx(() -> {
            // Outside a repository each says what git.unstash and git.remotes say.
            String noRepo = run("git.unstash");
            assertFalse(noRepo.isEmpty());
            for (String id : List.of(
                    "git.stashShow",
                    "git.stashPopChosen",
                    "git.stashBranch",
                    "git.stashCopyName",
                    "git.addRemote",
                    "git.renameRemote",
                    "git.setRemoteUrl",
                    "git.pruneRemote",
                    "git.removeRemote",
                    "git.fetchRemote",
                    "git.addWorktree",
                    "git.openWorktree",
                    "git.pruneWorktrees",
                    "git.removeWorktree")) {
                assertEquals(noRepo, run(id), id);
            }
        });
    }

    @Test
    void theSmallerWindowsRowCommandsNeedTheirWindowOpenAndARow(@TempDir Path dir) throws Exception {
        window(dir, false);
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("status.history.selectRevision"), run("history.restoreSelected"));
            assertEquals(tr("status.history.selectRevision"), run("history.labelSelected"));
            assertEquals(tr("status.history.selectFile"), run("localHistory.purgeSelectedFile"));
            for (String id : List.of("bookmarks.deleteSelected", "notes.editSelected", "test.rerunSelected")) {
                assertEquals(notOffered("status.rows.notOffered", id), run(id), id);
            }
            // Open, but with nothing selected: still not offered.
            fx.controller.toolWindows.open(fx.controller.bookmarksToolWindow);
            assertEquals(
                    notOffered("status.rows.notOffered", "bookmarks.moveSelectedUp"), run("bookmarks.moveSelectedUp"));
        });
    }

    /** With their window open, the commands act on its selection: the Git Log's selected file's path is copied. */
    @Test
    void withTheirWindowOpenTheCommandsActOnItsSelection(@TempDir Path dir) throws Exception {
        window(dir, false);
        FxTestSupport.runOnFx(() -> {
            fx.controller.toolWindows.open(fx.controller.fileHistoryToolWindow);
            assertEquals(tr("status.history.selectRevision"), run("history.restoreSelected"), "open, nothing chosen");
            assertEquals(tr("status.history.selectRevision"), run("history.labelSelected"));
            assertEquals(tr("status.history.selectFile"), run("localHistory.purgeSelectedFile"));
            fx.controller.toolWindows.open(fx.controller.notesToolWindow);
            assertEquals(notOffered("status.rows.notOffered", "notes.deleteSelected"), run("notes.deleteSelected"));

            fx.controller.toolWindows.open(fx.controller.gitLogToolWindow);
            assertEquals(tr("status.git.log.selectFile"), run("git.log.copyFilePath"), "open, nothing chosen");
            GitLogPanel log = fx.controller.gitLogPanel();
            com.editora.git.GitLog.Entry commit =
                    new com.editora.git.GitLog.Entry("abc1234def", "abc1234", "Change", "Ada", "2026-09-10");
            log.setLog(List.of(commit), null);
            assertTrue(log.selectCommit(commit.hash()));
            log.setCommitFiles(List.of(new com.editora.git.GitService.CommitFile('M', "src/A.java", null)));
            javafx.scene.control.ListView<?> files = FxTestSupport.field(log, "files");
            files.getSelectionModel().selectFirst();
            assertEquals(tr("status.copiedPath"), run("git.log.copyFilePath"));
            assertEquals("src/A.java", Clipboard.getSystemClipboard().getString());
        });
    }

    @Test
    void theDebugCommandsSayWhyWhenNothingIsThereToActOn(@TempDir Path dir) throws Exception {
        window(dir, false);
        FxTestSupport.runOnFx(() -> {
            assertEquals(tr("status.debug.noDebugFile"), run("debug.debugFile"));
            assertEquals(tr("status.debug.noMainAtCaret"), run("debug.mainClassAtCaret"));
            assertEquals(tr("status.debug.noWatchAtCaret"), run("debug.addWatchAtCaret"));
            for (String id : List.of("debug.copySelectedValue", "debug.clearConsole", "debug.historyLive")) {
                assertEquals(notOffered("status.debug.rowNotOffered", id), run(id), id + " with the window closed");
            }
            fx.controller.debugCoordinator.panel(); // built, as opening the window builds it
            assertEquals(
                    notOffered("status.debug.rowNotOffered", "debug.resumeSelectedThread"),
                    run("debug.resumeSelectedThread"),
                    "no thread is selected");
        });
    }

    /**
     * With a plain text file open the editor-menu commands still refuse, saying why (no debugger for it, no main
     * method, no paused program); with the Debug window open a row it offers runs: Clear Console clears it.
     */
    @Test
    void theDebugCommandsOnAFileAndInTheOpenDebugWindow(@TempDir Path dir) throws Exception {
        window(dir, false);
        Path notes = java.nio.file.Files.writeString(dir.resolve("notes.txt"), "main\n");
        GitLogReloadFxTest.open(fx.controller, notes);
        FxTestSupport.runOnFx(() -> {
            assertTrue(fx.controller.activeBuffer() != null);
            assertEquals(tr("status.debug.noDebugFile"), run("debug.debugFile"), "a text file is not debugged");
            assertEquals(tr("status.debug.noMainAtCaret"), run("debug.mainClassAtCaret"));
            assertEquals(tr("status.debug.noWatchAtCaret"), run("debug.addWatchAtCaret"), "nothing is paused");

            fx.controller.toolWindows.open(fx.controller.debugToolWindow);
            DebugPanel panel = fx.controller.debugCoordinator.panel();
            panel.appendOutput("program output\n", "stdout");
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            DebugPanel panel = fx.controller.debugCoordinator.panel();
            CodeArea console = FxTestSupport.field(panel, "console");
            assertFalse(console.getText().isEmpty(), "the output arrived");
            assertNotEquals(
                    notOffered("status.debug.rowNotOffered", "debug.clearConsole"),
                    run("debug.clearConsole"),
                    "the row is offered: no refusal");
            assertEquals("", console.getText(), "cleared, as its row clears it");
        });
    }

    @Test
    void theDiffLineAndGithubCommandsSayWhyWithNothingToActOn(@TempDir Path dir) throws Exception {
        window(dir, false);
        FxTestSupport.runOnFx(() -> {
            String noDiff = tr("status.diff.noActiveDiff");
            for (String id :
                    List.of("diff.stageLine", "diff.unstageLine", "diff.revertLine", "diff.openLineInEditor")) {
                assertEquals(noDiff, run(id), id);
            }
            assertEquals(tr("status.github.noRepository"), run("github.openRepository"));
            assertEquals(tr("status.github.noRepository"), run("github.copyRepositoryUrl"));
            fx.controller
                    .githubPanel()
                    .setContext(new GitHubPanel.Context("team/app", "https://github.com/team/app", "main", "alice"));
            ClipboardContent marker = new ClipboardContent();
            marker.putString("before");
            Clipboard.getSystemClipboard().setContent(marker);
            run("github.copyRepositoryUrl");
            assertEquals(
                    "https://github.com/team/app",
                    Clipboard.getSystemClipboard().getString());
            assertEquals(tr("status.github.row.choose"), run("github.openSelected"), "closed: opened and asked");
            assertEquals(tr("status.github.row.none"), run("github.checkoutSelectedPr"), "open, but nothing chosen");
        });
    }

    /** In a diff window the line commands act on the diff, and say so while the caret is on no changed line. */
    @Test
    void theDiffLineCommandsInADiffWindowNeedTheCaretOnAChangedLine(@TempDir Path dir) throws Exception {
        Path left = java.nio.file.Files.writeString(dir.resolve("left.txt"), "same\nold\n");
        Path right = java.nio.file.Files.writeString(dir.resolve("right.txt"), "same\nnew\n");
        fx = FxWindowFixture.createDiff(dir.resolve("config"), left, right, c -> {});
        // The diff is computed off the FX thread: wait for its tab to be the selected one.
        for (int i = 0; i < 500; i++) {
            if (FxTestSupport.callOnFx(() -> new DiffOpsHost(fx.controller).activeDiffPane() != null)) {
                break;
            }
            Thread.sleep(20);
        }
        FxTestSupport.runOnFx(() -> {
            assertTrue(new DiffOpsHost(fx.controller).activeDiffPane() != null, "the diff is open");
            for (String id : List.of("diff.stageLine", "diff.revertLine", "diff.openLineInEditor")) {
                assertEquals(tr("status.diff.noChangedLineAtCaret"), run(id), id + ": a diff of two files offers none");
            }
        });
    }

    /**
     * Edit → Copy and Select All act on a read-only view while it has the keyboard, as its Copy row does; with the
     * keyboard elsewhere they are the editor's, as before.
     */
    @Test
    void copyAndSelectAllActOnAFocusedReadOnlyViewElseOnTheEditor(@TempDir Path dir) throws Exception {
        window(dir, false);
        Stage[] stage = new Stage[1];
        CodeArea[] console = new CodeArea[1];
        FxTestSupport.runOnFx(() -> {
            console[0] = new CodeArea("all of the output");
            ConsoleContextMenu.install(console[0]);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new StackPane(console[0]), 300, 200));
            stage[0].show();
            stage[0].requestFocus();
            console[0].requestFocus();
        });
        try {
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertTrue(stage[0].isFocused(), "the console's window has the keyboard");
                ClipboardContent before = new ClipboardContent();
                before.putString("before");
                Clipboard.getSystemClipboard().setContent(before);
                run("edit.copy");
                assertEquals("all of the output", Clipboard.getSystemClipboard().getString());
                run("edit.selectAll");
                assertEquals(console[0].getLength(), console[0].getSelection().getLength());
            });
            FxTestSupport.runOnFx(() -> stage[0].close());
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                Clipboard.getSystemClipboard().clear();
                ClipboardContent marker = new ClipboardContent();
                marker.putString("untouched");
                Clipboard.getSystemClipboard().setContent(marker);
                assertFalse(ConsoleContextMenu.runInFocusedView(true), "no read-only view has the keyboard now");
                run("edit.copy"); // the editor's Copy, with nothing to copy
                assertEquals("untouched", Clipboard.getSystemClipboard().getString());
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                if (stage[0].isShowing()) {
                    stage[0].close();
                }
            });
        }
    }
}
