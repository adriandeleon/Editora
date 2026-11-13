package com.editora.ui;

import java.util.function.Consumer;

import com.editora.command.Command;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the buttons of the diff viewer, the Git Log, the GitHub window and Local History that
 * only the mouse reached: each runs the method its button runs, on the active diff tab or the open tool
 * window, and says so on the status line when there is none.
 */
final class DiffGitButtonCommands {

    private DiffGitButtonCommands() {}

    static void register(MainController mc) {
        // Diff toolbar: the active diff tab, as the other diff.* toolbar commands find it.
        mc.registry.register(
                Command.of("diff.undoApplied", () -> mc.diffCoordinator.withActiveDiff(DiffViewerPane::undoApplied)));
        mc.registry.register(
                Command.of("diff.saveResult", () -> mc.diffCoordinator.withActiveDiff(DiffViewerPane::saveResult)));
        mc.registry.register(
                Command.of("diff.exportPatch", () -> mc.diffCoordinator.withActiveDiff(DiffViewerPane::exportPatch)));
        mc.registry.register(Command.of("diff.exitDiffMode", () -> exitDiffMode(mc)));
        mc.registry.register(Command.of(
                "git.log.showBranchLog",
                () -> withGitLog(mc, panel -> {
                    if (!panel.showBranchLog()) {
                        mc.setStatus(tr("status.git.log.notFileHistory"));
                    }
                })));
        mc.registry.register(Command.of(
                "git.log.endSearch",
                () -> withGitLog(mc, panel -> {
                    if (!panel.endSearch()) {
                        mc.setStatus(tr("status.git.log.notSearching"));
                    }
                })));
        mc.registry.register(Command.of("git.log.refresh", () -> withGitLog(mc, GitLogPanel::refreshLog)));
        mc.registry.register(Command.of("github.refreshList", () -> refreshGithubList(mc)));
        mc.registry.register(Command.of("history.backToFile", () -> backToFileHistory(mc)));
    }

    /** What a diff tab's full-UI button runs: the window's own way out of the diff-only mode, when it is in it. */
    private static void exitDiffMode(MainController mc) {
        if (mc.chrome.diffUiActive()) {
            mc.chrome.exitDiffUiMode();
        } else {
            mc.setStatus(tr("status.diff.notDiffOnly"));
        }
    }

    /** Runs {@code op} on the Git Log while its tool window is open (its toolbar is on screen), with Git on. */
    private static void withGitLog(MainController mc, Consumer<GitLogPanel> op) {
        mc.git.ifEnabled(() -> {
            GitLogPanel panel = mc.gitLogPanel;
            if (panel == null || !mc.toolWindows.isOpen(mc.gitLogToolWindow)) {
                mc.setStatus(tr("status.git.log.notOpen"));
            } else {
                op.accept(panel);
            }
        });
    }

    /** The GitHub window's shown list, while the window is open; a closed one has no list to fetch again. */
    private static void refreshGithubList(MainController mc) {
        mc.github.ifEnabled(() -> {
            GitHubPanel panel = mc.githubPanel;
            if (panel == null || !mc.toolWindows.isOpen(mc.githubToolWindow)) {
                mc.setStatus(tr("status.github.notOpen"));
            } else {
                panel.refreshList();
            }
        });
    }

    /** Local History's folder view, when the open window shows one: back to the active file's history. */
    private static void backToFileHistory(MainController mc) {
        // Asked of the open window alone, so that saying there is no folder view builds no panel.
        if (mc.toolWindows.isOpen(mc.fileHistoryToolWindow)
                && mc.historyCoordinator.panel().folderShown() != null) {
            mc.historyCoordinator.panel().showFileView();
        } else {
            mc.setStatus(tr("status.history.notFolderView"));
        }
    }
}
