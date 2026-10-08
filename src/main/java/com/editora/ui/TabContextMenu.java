package com.editora.ui;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/** The right-click menu of an editor tab; built lazily on first use by {@link MainController}. */
final class TabContextMenu {
    private TabContextMenu() {}

    static void build(MainController c, Tab tab, EditorBuffer buffer, ContextMenu menu) {
        MenuItem save = LazyContextMenu.item(tr("menu.save"), Icons.save(), () -> c.fileWorkflows.save(buffer));
        MenuItem saveAs = LazyContextMenu.item(tr("menu.saveAs"), Icons.saveAs(), () -> c.fileWorkflows.saveAs(buffer));
        // Print / Export to PDF act on the active buffer, so a right-clicked background tab comes forward first
        // — which is also what the print preview and the Save dialog are then seen to be about.
        MenuItem print = LazyContextMenu.item(tr("menu.print"), Icons.print(), () -> {
            c.activateAndFocusTab(tab);
            c.exports.printCode();
        });
        MenuItem exportPdf = LazyContextMenu.item(tr("menu.exportPdf"), Icons.saveAs(), () -> {
            c.activateAndFocusTab(tab);
            c.exports.exportCodePdf();
        });
        MenuItem close = LazyContextMenu.item(tr("menu.close"), Icons.closeTab(), () -> c.closeTab(tab));
        MenuItem closeOthers =
                LazyContextMenu.item(tr("menu.closeOthers"), Icons.closeOtherTabs(), () -> c.closeOtherTabs(tab));
        MenuItem closeAll = LazyContextMenu.item(tr("menu.closeAll"), Icons.closeAllTabs(), () -> c.closeAllTabs());
        MenuItem closeUnmodified = LazyContextMenu.item(
                tr("menu.closeUnmodified"), Icons.closeUnmodifiedTabs(), () -> c.closeUnmodifiedTabs());
        MenuItem closeLeft =
                LazyContextMenu.item(tr("menu.closeLeft"), Icons.closeTabsLeft(), () -> c.closeTabsToLeft(tab));
        MenuItem closeRight =
                LazyContextMenu.item(tr("menu.closeRight"), Icons.closeTabsRight(), () -> c.closeTabsToRight(tab));
        MenuItem copyPath = LazyContextMenu.item(tr("menu.copyPath"), Icons.copy(), () -> c.copyPath(buffer));
        MenuItem pin = LazyContextMenu.item(tr("menu.pin"), Icons.pin(), () -> c.togglePin(tab));
        MenuItem rename = LazyContextMenu.item(tr("menu.rename"), Icons.edit(), () -> c.renameFile(buffer, tab));
        // Git submenu — mirrors the Project tree's cell "Git" submenu, acting on this tab's file.
        Menu gitMenu = new Menu(tr("project.menu.git"));
        gitMenu.setGraphic(Icons.git());
        MenuItem stage = LazyContextMenu.item(
                tr("project.menu.git.stage"),
                Icons.stageAll(),
                () -> c.git.ifEnabled(() -> c.git.gitStagePath(buffer.getPath())));
        MenuItem unstage = LazyContextMenu.item(
                tr("project.menu.git.unstage"),
                Icons.remove(),
                () -> c.git.ifEnabled(() -> c.git.gitUnstagePath(buffer.getPath())));
        MenuItem revert = LazyContextMenu.item(
                tr("project.menu.git.revert"),
                Icons.undo(),
                () -> c.git.ifEnabled(() -> c.git.gitRevertPath(buffer.getPath())));
        MenuItem ignore = LazyContextMenu.item(
                tr("project.menu.git.addToGitignore"),
                Icons.git(),
                () -> c.git.ifEnabled(() -> c.git.addToGitignore(buffer.getPath())));
        MenuItem diffHead = LazyContextMenu.item(
                tr("project.menu.git.compareHead"),
                Icons.diff(),
                () -> c.git.withRepositoryOf(
                        buffer.getPath(), () -> c.diffCoordinator.diffPathVsHead(buffer.getPath())));
        MenuItem diffBranch = LazyContextMenu.item(
                tr("project.menu.git.compareBranch"),
                Icons.diff(),
                () -> c.git.withRepositoryOf(
                        buffer.getPath(), () -> c.diffCoordinator.diffPathVsBranch(buffer.getPath())));
        MenuItem diffTag = LazyContextMenu.item(
                tr("project.menu.git.compareTag"),
                Icons.diff(),
                () -> c.git.withRepositoryOf(
                        buffer.getPath(), () -> c.diffCoordinator.diffPathVsTag(buffer.getPath())));
        MenuItem diffCommit = LazyContextMenu.item(
                tr("project.menu.git.compareRevision"),
                Icons.diff(),
                () -> c.git.withRepositoryOf(
                        buffer.getPath(), () -> c.diffCoordinator.diffPathVsCommit(buffer.getPath())));
        MenuItem annotate = new MenuItem(tr("project.menu.git.annotate"));
        annotate.setGraphic(Icons.blame());
        annotate.setOnAction(e -> c.git.ifEnabled(() -> {
            c.fileWorkflows.openPath(buffer.getPath());
            c.git.annotateActive();
        }));
        MenuItem history = LazyContextMenu.item(
                tr("project.menu.git.fileHistory"),
                Icons.gitLog(),
                // In the file's own repository, as from the Project tree: a right-click does not select the
                // tab, and the log lists the active repository — which answered "outside repository" for a
                // background tab of another one, and "No commits" for a file of a nested one.
                () -> c.git.activatingRepositoryOf(
                        buffer.getPath(), () -> c.gitWindows.gitFileHistoryForPath(buffer.getPath())));
        gitMenu.getItems()
                .addAll(
                        stage,
                        unstage,
                        revert,
                        ignore,
                        new SeparatorMenuItem(),
                        diffHead,
                        diffBranch,
                        diffTag,
                        diffCommit,
                        annotate,
                        history);
        // "Compare With…" (any two files) and "Open in Diff Viewer" (a .patch/.diff file) are not Git
        // actions, so they stay outside the Git submenu.
        MenuItem compareWith = LazyContextMenu.item(
                tr("menu.compareWith"), Icons.diff(), () -> c.diffCoordinator.compareActiveWithFile());
        MenuItem openPatch = LazyContextMenu.item(
                tr("menu.openInDiffViewer"), Icons.diff(), () -> c.diffCoordinator.openPatchFile(buffer));
        MenuItem reveal = LazyContextMenu.item(
                tr("menu.revealInFileManager"),
                Icons.revealInFiles(),
                () -> c.revealInFileManager(buffer.getPath(), false, c.isLocalBuffer(buffer)));
        MenuItem terminal = LazyContextMenu.item(
                tr("menu.openTerminal"),
                Icons.terminal(),
                () -> c.openTerminalAt(buffer.getPath(), false, c.isLocalBuffer(buffer)));

        menu.getItems()
                .setAll(
                        save,
                        saveAs,
                        new SeparatorMenuItem(),
                        print,
                        exportPdf,
                        new SeparatorMenuItem(),
                        close,
                        closeOthers,
                        closeAll,
                        closeUnmodified,
                        new SeparatorMenuItem(),
                        closeLeft,
                        closeRight,
                        new SeparatorMenuItem(),
                        gitMenu,
                        compareWith,
                        openPatch,
                        new SeparatorMenuItem(),
                        reveal,
                        terminal,
                        copyPath,
                        pin,
                        rename);
        menu.setOnShowing(e -> {
            closeLeft.setDisable(c.eligibleToLeft(tab).isEmpty());
            closeRight.setDisable(c.eligibleToRight(tab).isEmpty());
            boolean hasPath = buffer.getPath() != null;
            // Reveal/terminal only make sense for a saved, local file.
            boolean localPath = hasPath && c.isLocalBuffer(buffer);
            reveal.setDisable(!localPath);
            terminal.setDisable(!localPath);
            copyPath.setDisable(!hasPath);
            rename.setDisable(!hasPath);
            compareWith.setDisable(!hasPath); // not a Git action — works on any two files
            // Only shown for a .patch/.diff file — parses the buffer's own (possibly unsaved) text.
            openPatch.setVisible(hasPath
                    && PatchFiles.isPatchFile(buffer.getPath().getFileName().toString()));
            // The Git submenu is only shown for a saved file (an untitled buffer can't be in a repo) and is
            // greyed out when there's no VCS (Git off / not inside a repo) — mirroring the Project tree.
            gitMenu.setVisible(hasPath);
            GitPathScope scope = hasPath ? c.git.scopeOf(buffer.getPath()) : GitPathScope.NONE;
            gitMenu.setDisable(scope == GitPathScope.NONE);
            // Nothing to revert on a clean file; ignore is for untracked ones. For a file of another
            // repository the status is unknown here, so both stay enabled.
            com.editora.git.GitFileStatus st = c.git.statusFor(buffer.getPath());
            revert.setDisable(scope == GitPathScope.ACTIVE && st == null);
            ignore.setDisable(scope == GitPathScope.ACTIVE && st != com.editora.git.GitFileStatus.UNTRACKED);
            // Save is a no-op for an unchanged, on-disk file; untitled/dirty buffers can always save.
            save.setDisable(hasPath && !buffer.isDirty());
            pin.setText(tr(c.pinned.contains(tab) ? "menu.unpin" : "menu.pin"));
        });
    }
}
