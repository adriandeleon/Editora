package com.editora.ui;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;

/**
 * Which tabs keep the Git Log and Commit tool windows usable.
 *
 * <p>They are hidden on a tab with no Git context (Welcome, Settings). That used to mean "any tab that is
 * not an editor buffer" — which included the diff and review tabs these two windows open themselves, so
 * double-clicking a commit's file closed the log it was picked from, and Show Diff closed the Commit window.
 *
 * <p>A project window always has a Git context: on a tab without a file (Welcome, Settings, no tab at all)
 * the Git UI falls back to the project root's repository, so the windows stay — they used to vanish there,
 * taking a half-written commit message out of reach.
 */
final class GitWindowGate {

    private GitWindowGate() {}

    /**
     * Whether {@code selected} is an editor buffer, or a diff/review tab opened from the Git windows — or
     * any tab (or none) when a project is open.
     */
    static boolean allows(Tab selected, boolean projectOpen) {
        Object content = selected == null ? null : selected.getUserData();
        return projectOpen || content instanceof EditorBuffer || gitView(content);
    }

    /** Whether {@code content} is a diff or review surface: a tab the Git windows open, with no file of its own. */
    static boolean gitView(Object content) {
        return localGitView(content) || content instanceof PrReviewPane;
    }

    /**
     * A diff, patch or commit review: a tab opened from the local repository's own windows. A pull-request
     * review is not one — it is bound to the repository the pull request came from by
     * {@code GitHubCoordinator}, and deliberately has no Git context of its own.
     */
    static boolean localGitView(Object content) {
        return content instanceof DiffViewerPane
                || content instanceof PatchReviewPane
                || content instanceof DirectoryReviewPane;
    }

    /**
     * Whether a tab pane of {@code window} has a diff, patch or commit-review tab selected. The Git engine asks this when
     * the active tab holds no file and there is no project: the repository such a tab was opened from must
     * stay the active one ({@link GitCoordinator#contextPath}). It looks at the scene because the engine is
     * not told which tab is selected; with split editors, any pane showing such a tab counts.
     */
    static boolean showsGitView(javafx.stage.Window window) {
        javafx.scene.Scene scene = window == null ? null : window.getScene();
        if (scene == null || scene.getRoot() == null) {
            return false;
        }
        for (javafx.scene.Node node : scene.getRoot().lookupAll(".tab-pane")) {
            if (node instanceof javafx.scene.control.TabPane pane) {
                Tab selected = pane.getSelectionModel().getSelectedItem();
                if (selected != null && localGitView(selected.getUserData())) {
                    return true;
                }
            }
        }
        return false;
    }
}
