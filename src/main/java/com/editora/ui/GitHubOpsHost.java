package com.editora.ui;

import javafx.scene.control.Tab;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What {@link GitHubCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class GitHubOpsHost implements GitHubCoordinator.WindowOps {

    private final MainController mc;

    GitHubOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void reloadAllFromDiskSilently() {
        mc.reloadAllFromDiskSilently();
    }

    @Override
    public void checkExternalChanges() {
        mc.fileWorkflows.checkExternalChanges();
    }

    @Override
    public com.editora.command.KeymapManager keymap() {
        return mc.keymap;
    }

    @Override
    public void setGitHubWindowAvailable(boolean available) {
        // Repo-scoped (not buffer-scoped): the GitHub window lists the repo's PRs/issues, so it stays
        // available across tab switches (incl. the Welcome tab) as long as the project is a GitHub
        // repo with open activity — unlike the Commit/Git Log windows, which act on the active file.
        if (mc.githubToolWindow != null) {
            mc.toolWindows.setAvailable(mc.githubToolWindow, available);
        }
    }

    @Override
    public void enableGitHubWindowByDefault() {
        if (mc.githubToolWindow != null) {
            mc.toolWindows.setVisibleIfUnset(mc.githubToolWindow, true);
        }
    }

    @Override
    public void toggleGitHubWindow() {
        if (mc.githubToolWindow != null) {
            mc.toolWindows.toggle(mc.githubToolWindow);
        }
    }

    @Override
    public void setStatusBarChecks(com.editora.github.ChecksParser.@Nullable ChecksSummary summary) {
        mc.statusBar.setGitHubChecks(mc.github.checksPrNumber(), summary);
    }

    @Override
    public void addReviewTab(com.editora.editor.TabContent pane) {
        if (pane instanceof PrReviewPane p) {
            p.setFontScale(mc.config.getSettings().getFontZoom());
        }
        mc.addContentTab(pane, true);
    }

    @Override
    public boolean selectTabOf(com.editora.editor.TabContent pane) {
        for (Tab t : mc.editorArea.tabs()) {
            if (t.getUserData() == pane) {
                TabFocus.select(mc.editorArea, t);
                return true;
            }
        }
        return false;
    }

    @Override
    public void reloadGitHubPanel() {
        mc.gitWindows.reloadGithubPanel();
    }

    // The Output console is owner-routed, so passing the GitHub coordinator as the owner
    // gives CI logs their own persistent "CI" tab beside the Maven/npm build tabs.
    @Override
    public void ciLogStarted(String header, Runnable onStop) {
        mc.refreshBuildOutputAvailability(); // a repo with no build tool still gets the CI tab
        mc.toolWindows.open(mc.buildOutputToolWindow);
        mc.buildOutputPanel.started(mc.github, tr("github.ci.tab"), header, com.editora.build.OutputStyle.ci(), onStop);
    }

    @Override
    public void ciLogAppend(String line) {
        mc.buildOutputPanel.appendOutput(mc.github, line, false);
    }

    @Override
    public void ciLogFinished() {
        mc.buildOutputPanel.finished(mc.github, 0);
    }

    @Override
    public void ciLogFailed(String message) {
        mc.buildOutputPanel.failed(mc.github, message);
    }
}
