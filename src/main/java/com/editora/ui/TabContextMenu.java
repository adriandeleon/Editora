package com.editora.ui;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import com.editora.editor.MenuChords;

import static com.editora.i18n.Messages.tr;

/**
 * The right-click menus of the tab strip, built lazily on first use by {@link MainController}: an editor
 * tab's ({@link #build}) and every other tab's ({@link #buildForContent}).
 *
 * <p>Simple UI mode trims it, as it trims the menu bar, to saving, printing, closing and the file's own
 * place on disk: the Git, Local History, diff, terminal and bulk-close entries are hidden while the mode is on
 * and come back when it is off (the check runs on every showing, so a menu built before a toggle follows it).
 */
final class TabContextMenu {
    private TabContextMenu() {}

    /** Property of a tab's own row: a {@link Runnable} the menu runs each time it opens (to set the row's words). */
    static final Object ON_SHOWING = new Object();

    /**
     * The menu of a tab that is not an editor's: an image, a PDF, a hex view, a diff, a review, a preview, a
     * terminal, the Welcome page. The tab's {@code own} rows come first, then the tab operations every tab has,
     * then, for a viewer's file, its place on disk and its pin.
     */
    static void buildForContent(MainController c, Tab tab, ContextMenu menu, MenuItem... own) {
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
        MenuItem revealInMap = LazyContextMenu.item(tr("project.map.menu.revealInMap"), Icons.locateFile(), () -> {
            java.nio.file.Path file = MainController.tabPath(tab);
            if (file != null && c.projectsEnabled()) {
                c.projectPanel().showInMap(file);
            }
        });
        MenuItem reveal = LazyContextMenu.item(tr("menu.revealInFileManager"), Icons.revealInFiles(), () -> {
            java.nio.file.Path file = MainController.tabPath(tab);
            c.revealInFileManager(file, false, com.editora.vfs.Vfs.isLocal(file));
        });
        MenuItem terminal = LazyContextMenu.item(tr("menu.openTerminal"), Icons.terminal(), () -> {
            java.nio.file.Path file = MainController.tabPath(tab);
            c.openTerminalAt(file, false, com.editora.vfs.Vfs.isLocal(file));
        });
        MenuItem copyPath =
                LazyContextMenu.item(tr("menu.copyPath"), Icons.copy(), () -> c.copyPath(MainController.tabPath(tab)));
        MenuItem pin = LazyContextMenu.item(tr("menu.pin"), Icons.pin(), () -> c.togglePin(tab));
        // Each row a command also does shows that command's chord: the shortcut for "this, on the active tab".
        MenuChords.tag(close, "buffer.close");
        MenuChords.tag(closeOthers, "buffer.closeOthers");
        MenuChords.tag(closeAll, "buffer.closeAll");
        MenuChords.tag(closeUnmodified, "buffer.closeUnmodified");
        MenuChords.tag(closeLeft, "buffer.closeLeft");
        MenuChords.tag(closeRight, "buffer.closeRight");
        MenuChords.tag(revealInMap, "projectMap.revealActiveFile");
        MenuChords.tag(reveal, "file.revealInFileManager");
        MenuChords.tag(terminal, "file.openTerminal");
        MenuChords.tag(copyPath, "buffer.copyPath");
        MenuChords.tag(pin, "buffer.togglePin");
        menu.getItems().setAll(own);
        menu.getItems()
                .addAll(
                        new SeparatorMenuItem(),
                        close,
                        closeOthers,
                        closeAll,
                        closeUnmodified,
                        new SeparatorMenuItem(),
                        closeLeft,
                        closeRight,
                        new SeparatorMenuItem(),
                        revealInMap,
                        reveal,
                        terminal,
                        copyPath,
                        pin);
        menu.setOnShowing(e -> {
            for (MenuItem row : own) {
                if (row.getProperties().get(ON_SHOWING) instanceof Runnable words) {
                    words.run();
                }
            }
            boolean simple = c.chrome.simpleModeActive();
            java.nio.file.Path path = MainController.tabPath(tab);
            boolean hasPath = path != null;
            close.setDisable(!tab.isClosable());
            closeUnmodified.setVisible(!simple);
            closeLeft.setVisible(!simple);
            closeRight.setVisible(!simple);
            closeLeft.setDisable(c.eligibleToLeft(tab).isEmpty());
            closeRight.setDisable(c.eligibleToRight(tab).isEmpty());
            // The file rows are a viewer's: a diff, a terminal or the Welcome page shows no one file.
            revealInMap.setVisible(hasPath && c.projectsEnabled());
            reveal.setVisible(hasPath);
            reveal.setDisable(!com.editora.vfs.Vfs.isLocal(path)); // a remote file has no place in this file manager
            terminal.setVisible(hasPath && !simple);
            copyPath.setVisible(hasPath);
            // As on a file's tab, Unpin stays reachable in Simple mode for a tab pinned before it was turned on.
            pin.setVisible(hasPath && (!simple || c.pinned.contains(tab)));
            pin.setText(tr(c.pinned.contains(tab) ? "menu.unpin" : "menu.pin"));
            LazyContextMenu.collapseSeparators(menu.getItems());
        });
    }

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
        // Encrypt… on an ordinary file; Lock File on an encrypted one (its text leaves memory until Decrypt).
        MenuItem encryption = LazyContextMenu.item(tr("menu.encrypt"), Icons.lock(), () -> {
            if (buffer.encryption().isEncrypted()) {
                c.fileWorkflows.encryption().lockFile(buffer);
            } else {
                c.activateAndFocusTab(tab);
                c.fileWorkflows.encryption().encryptFile();
            }
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
        // Local History, beside Git's history as in the Project tree: for this tab's file, which it brings
        // forward (the tool window follows the active buffer).
        MenuItem localHistory = LazyContextMenu.item(
                tr("project.menu.localHistory"),
                Icons.history(),
                () -> c.historyCoordinator.showForPath(buffer.getPath()));
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
        revert.getStyleClass().add("danger"); // discards the file's changes: a destructive row reads red
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
        // Each row a command also does shows that command's chord — the shortcut for "this, on the active tab".
        MenuChords.tag(save, "file.save");
        MenuChords.tag(saveAs, "file.saveAs");
        MenuChords.tag(print, "editor.print");
        MenuChords.tag(exportPdf, "editor.exportPdf");
        MenuChords.tag(close, "buffer.close");
        MenuChords.tag(closeOthers, "buffer.closeOthers");
        MenuChords.tag(closeAll, "buffer.closeAll");
        MenuChords.tag(closeUnmodified, "buffer.closeUnmodified");
        MenuChords.tag(closeLeft, "buffer.closeLeft");
        MenuChords.tag(closeRight, "buffer.closeRight");
        MenuChords.tag(copyPath, "buffer.copyPath");
        MenuChords.tag(pin, "buffer.togglePin");
        MenuChords.tag(rename, "buffer.rename");
        MenuChords.tag(stage, "git.stageFile");
        MenuChords.tag(unstage, "git.unstageFile");
        MenuChords.tag(revert, "git.discardFile");
        MenuChords.tag(ignore, "git.addToGitignore");
        MenuChords.tag(diffHead, "diff.vsHead");
        MenuChords.tag(diffBranch, "diff.vsBranch");
        MenuChords.tag(diffTag, "diff.vsTag");
        MenuChords.tag(diffCommit, "diff.vsCommit");
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
        // actions, so they stay outside the Git submenu. This tab's file, which need not be the active one.
        MenuItem compareWith = LazyContextMenu.item(
                tr("menu.compareWith"), Icons.diff(), () -> c.diffCoordinator.compareWithFile(buffer.getPath()));
        MenuItem openPatch = LazyContextMenu.item(
                tr("menu.openInDiffViewer"), Icons.diff(), () -> c.diffCoordinator.openPatchFile(buffer));
        MenuItem reveal = LazyContextMenu.item(
                tr("menu.revealInFileManager"),
                Icons.revealInFiles(),
                () -> c.revealInFileManager(buffer.getPath(), false, c.isLocalBuffer(buffer)));
        // Beside Reveal in File Manager: the file's place on the Project Map (Map explore, position 18).
        MenuItem revealInMap = LazyContextMenu.item(tr("project.map.menu.revealInMap"), Icons.locateFile(), () -> {
            java.nio.file.Path file = buffer.getPath();
            if (file != null && c.projectsEnabled()) {
                c.projectPanel().showInMap(file);
            }
        });
        MenuChords.tag(revealInMap, "projectMap.revealActiveFile");
        MenuItem terminal = LazyContextMenu.item(
                tr("menu.openTerminal"),
                Icons.terminal(),
                () -> c.openTerminalAt(buffer.getPath(), false, c.isLocalBuffer(buffer)));
        MenuChords.tag(history, "git.fileHistory");
        MenuChords.tag(compareWith, "diff.compareWith");
        MenuChords.tag(openPatch, "diff.openPatchFile");
        MenuChords.tag(reveal, "file.revealInFileManager");
        MenuChords.tag(terminal, "file.openTerminal");

        menu.getItems()
                .setAll(
                        save,
                        saveAs,
                        encryption,
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
                        localHistory,
                        gitMenu,
                        compareWith,
                        openPatch,
                        new SeparatorMenuItem(),
                        revealInMap,
                        reveal,
                        terminal,
                        copyPath,
                        pin,
                        rename);
        menu.setOnShowing(e -> {
            boolean simple = c.chrome.simpleModeActive();
            boolean encrypted = buffer.encryption().isEncrypted();
            encryption.setText(tr(encrypted ? "menu.lockFile" : "menu.encrypt"));
            // The row is one of two commands, by the file's state: its chord follows the title.
            MenuChords.tag(
                    encryption, encrypted ? EncryptionCoordinator.LOCK_FILE : EncryptionCoordinator.ENCRYPT_FILE);
            encryption.setDisable(encrypted && (buffer.encryption().isLocked() || buffer.getPath() == null));
            closeUnmodified.setVisible(!simple);
            closeLeft.setVisible(!simple);
            closeRight.setVisible(!simple);
            terminal.setVisible(!simple);
            // Unpin stays reachable for a tab pinned before the mode was turned on: a pinned tab survives
            // Close Others / Close All, and Simple mode must not leave it stuck.
            pin.setVisible(!simple || c.pinned.contains(tab));
            closeLeft.setDisable(c.eligibleToLeft(tab).isEmpty());
            closeRight.setDisable(c.eligibleToRight(tab).isEmpty());
            java.nio.file.Path path = buffer.getPath();
            boolean hasPath = path != null;
            // Reveal only makes sense for a saved, local file; a terminal also opens on a remote file's host.
            boolean localPath = hasPath && c.isLocalBuffer(buffer);
            reveal.setDisable(!localPath);
            revealInMap.setVisible(c.projectsEnabled());
            revealInMap.setDisable(!hasPath);
            terminal.setDisable(!hasPath);
            copyPath.setDisable(!hasPath);
            rename.setDisable(!hasPath);
            compareWith.setVisible(!simple);
            compareWith.setDisable(!hasPath); // not a Git action — works on any two files
            // Local History records saved local files only; greyed out while the feature is off.
            localHistory.setVisible(localPath && !simple);
            localHistory.setDisable(!c.historyCoordinator.isEnabled());
            // Only shown for a .patch/.diff file — parses the buffer's own (possibly unsaved) text.
            openPatch.setVisible(!simple
                    && path != null
                    && PatchFiles.isPatchFile(path.getFileName().toString()));
            // The Git submenu is only shown for a saved file (an untitled buffer can't be in a repo) and is
            // greyed out when there's no VCS (Git off / not inside a repo) — mirroring the Project tree.
            gitMenu.setVisible(hasPath && !simple);
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
            LazyContextMenu.collapseSeparators(menu.getItems());
        });
    }
}
