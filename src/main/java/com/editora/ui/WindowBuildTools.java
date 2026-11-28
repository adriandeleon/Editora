package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;

import com.editora.build.BuildTool;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A window's build tools (Maven, npm, Gradle and the rest): it makes one {@link BuildCoordinator} per tool with the
 * trust and tool-window hooks it needs, answers which tools are detected or switched off, resolves the command Run
 * and Debug start a tool with, and points the Git and GitHub services' command logs at their own tabs of the shared
 * Output window. The coordinators and tool windows stay fields of {@link MainController}. It lived inside
 * {@code MainController}; it is a file of its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowBuildTools {

    private final MainController mc;

    WindowBuildTools(MainController mc) {
        this.mc = mc;
    }

    /**
     * The argv prefix Run and Debug start {@code tool} with from {@code root}: the tool's tasks tree decides
     * (its wrapper, the override, the PATH command) and asks for trust as it would for its own run; null when
     * trust was declined. With the tool's support turned off there is no tree — then the user's own command
     * (the override, else the PATH command), never a repository's wrapper, which nothing has gated.
     */
    @Nullable
    List<String> buildExecutableFor(BuildTool tool, java.nio.file.@Nullable Path root) {
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.tool() == tool && root != null) {
                return c.trustedExecutable(root);
            }
        }
        String hostTool = com.editora.process.ExecTargets.remoteTool(root, tool.id());
        return com.editora.build.BuildExecutable.resolve(
                List.of(),
                hostTool != null
                        ? hostTool
                        : mc.coordinatorHost.projectScope().buildCommand(tool, mc.config.getSettings()),
                tool.commandExample());
    }

    List<BuildCoordinator> createBuildCoordinators() {
        List<BuildCoordinator> list = new ArrayList<>();
        for (BuildTool tool : BuildTool.enabled()) {
            list.add(new BuildCoordinator(
                    tool,
                    mc.coordinatorHost,
                    // Trust, the custom-task history and the build-file link live in BuildTrustOps.
                    new BuildTrustOps(
                            () -> mc.config,
                            () -> mc.stage,
                            mc.trust::saveTrustDecision,
                            () -> {
                                if (mc.settingsWindow != null) {
                                    mc.settingsWindow.refreshTrustedFolders();
                                }
                            },
                            mc::requestSave,
                            file -> mc.fileWorkflows.openPath(file)) {
                        @Override
                        public java.nio.file.@Nullable Path projectRoot() {
                            return mc.windowProject != null ? mc.windowProject.rootPath() : null;
                        }

                        @Override
                        public void openTasks() {
                            ToolWindow tw = mc.buildToolWindows.get(tool);
                            if (tw != null) {
                                mc.toolWindows.open(tw, true);
                            }
                        }

                        @Override
                        public void toggleTasks() {
                            ToolWindow tw = mc.buildToolWindows.get(tool);
                            if (tw != null) {
                                mc.toolWindows.toggle(tw);
                            }
                        }

                        @Override
                        public void openConsole() {
                            if (mc.buildOutputToolWindow != null) {
                                mc.toolWindows.open(mc.buildOutputToolWindow, true);
                            }
                        }

                        @Override
                        public void setToolWindowsAvailable(boolean available) {
                            ToolWindow tasks = mc.buildToolWindows.get(tool);
                            if (tasks != null) {
                                mc.toolWindows.setAvailable(tasks, available);
                            }
                            // The shared console is available when *any* build tool is currently detected
                            // (or once a Git/GitHub command has written a transcript tab into it).
                            refreshBuildOutputAvailability();
                            // Detection is what decides whether the project is launchable at all.
                            mc.runConfigurations.refreshRunConfigToolbar();
                            // A JVM marker (pom/build.gradle) appearing/vanishing flips the JUnit test gutter and
                            // the project main-method gutter — re-gate every open buffer (both no-op on an
                            // unchanged flag).
                            if (tool == BuildTool.MAVEN || tool == BuildTool.GRADLE) {
                                mc.coordinatorHost.forEachBuffer(mc.testNavigation::applyTestGutter);
                                mc.coordinatorHost.forEachBuffer(mc.testNavigation::applyMainGutter);
                            }
                            // Detection is async, so an open Settings page read its status row before this
                            // landed — restate it now rather than leaving a stale "not detected".
                            if (mc.settingsWindow != null) {
                                mc.settingsWindow.refreshBuildToolStatus();
                            }
                        }
                    },
                    mc.buildOutputPanel));
        }
        return list;
    }

    // --- Git / GitHub CLI transcripts (their own Output tabs) ------------------------------

    /**
     * Owner keys for the two CLI-transcript tabs. Deliberately <em>not</em> the coordinators themselves:
     * {@code github} already owns the streaming "CI" tab, and reusing it would make a {@code gh} command
     * wipe the CI log (and vice versa) — {@code started()} clears its console, a transcript must not be.
     */
    private final Object gitConsoleOwner = new Object();

    private final Object ghConsoleOwner = new Object();

    /**
     * Points each native-CLI service at its own Output tab, so the commands Editora runs on the user's
     * behalf are inspectable rather than invisible. The services decide <em>what</em> to report (git: the
     * user-initiated writes only, since status/diff re-run constantly); this only decides where it lands.
     * Both sinks are called on a service worker thread, hence the marshal.
     */
    void installCommandLogs() {
        // A clone runs for minutes: its command and progress are shown while it runs, not once it is over.
        mc.git
                .service()
                .setCommandLog(new GitConsoleLog(
                        entry -> logCliCommand(gitConsoleOwner, tr("console.tab.git"), entry),
                        argv -> {
                            mc.buildOutputPanel.commandStarted(
                                    gitConsoleOwner, tr("console.tab.git"), argv, mc.git::cancelNetworkCommand);
                            showCliTranscript(gitConsoleOwner);
                        },
                        (line, transientLine) ->
                                mc.buildOutputPanel.commandProgress(gitConsoleOwner, line, transientLine)));
        mc.github
                .service()
                .setCommandLog(entry ->
                        Platform.runLater(() -> logCliCommand(ghConsoleOwner, tr("console.tab.github"), entry)));
    }

    private void logCliCommand(Object owner, String tabTitle, com.editora.process.CommandLog.Entry entry) {
        mc.buildOutputPanel.logCommand(owner, tabTitle, entry);
        showCliTranscript(GitConsoleLog.raisesConsole(entry) ? owner : null);
    }

    private void showCliTranscript(@Nullable Object owner) {
        // The console's stripe is gated on a build tool being detected; a repo with no build file still has
        // git, so the first logged command is what makes the window reachable there.
        refreshBuildOutputAvailability();
        // A Git network command (as it starts) and a Git command that failed bring their transcript forward;
        // a local command that worked does not (GitConsoleLog.raisesConsole), nor do GitHub's queries.
        if (owner == gitConsoleOwner && mc.buildOutputToolWindow != null) {
            mc.buildOutputPanel.selectTab(owner);
            mc.toolWindows.open(mc.buildOutputToolWindow);
        }
    }

    /**
     * The shared Output console is available whenever something can write into it: a detected build tool, a
     * tab already written (a finished build, a CI log), or <b>a Git repository</b> — Git and GitHub log every
     * command they run into their own transcript tabs there, and in a repo with no build file the stripe
     * button is the only way to reach them.
     */
    void refreshBuildOutputAvailability() {
        if (mc.buildOutputToolWindow != null) {
            mc.toolWindows.setAvailable(
                    mc.buildOutputToolWindow, anyBuildDetected() || inGitRepo() || mc.buildOutputPanel.hasTabs());
        }
    }

    /** Whether the active context is inside a Git repository (and Git is switched on). */
    private boolean inGitRepo() {
        return mc.git != null && mc.git.isAvailable();
    }

    /** Whether any enabled build tool currently has a detected marker file (drives the shared console stripe). */
    private boolean anyBuildDetected() {
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.isEnabled() && c.isDetected()) {
                return true;
            }
        }
        return false;
    }

    /** The coordinator for a specific build tool (for the palette gate / Settings status reads). */
    @Nullable
    BuildCoordinator buildCoordinator(BuildTool tool) {
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.tool() == tool) {
                return c;
            }
        }
        return null;
    }

    /** Re-detects the marker file for every build tool (Maven/npm/…) for the active context. */
    void refreshBuildTools() {
        mc.buildCoordinators.forEach(BuildCoordinator::refresh);
    }

    /** The ids of build tools whose commands should be hidden from the palette (disabled / Simple mode). */
    java.util.Set<String> disabledBuildToolIds() {
        java.util.Set<String> disabled = new java.util.HashSet<>();
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (!c.isEnabled()) {
                disabled.add(c.tool().id());
            }
        }
        return disabled;
    }
}
