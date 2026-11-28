package com.editora.ui;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What a window's commands and tool windows may do right now: the feature gates and context the palette and menu
 * bar grey rows by (with the reason a row is grey), and the availability of the tool windows that follow the active
 * buffer (Run, TODO, Structure and the rest). It lived inside {@code MainController}; it is a file of its own only
 * because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowGates {

    private final MainController mc;

    WindowGates(MainController mc) {
        this.mc = mc;
    }

    /** Whether plugins may be browsed and installed — the master gate (also off in Simple UI mode). */
    private boolean pluginsEnabled() {
        return mc.pluginCoordinator != null && mc.pluginCoordinator.isEnabled();
    }

    /** Snapshot of which optional features are effectively enabled, for {@link Chrome#paletteVisible}. */
    Chrome.PaletteGates paletteGates() {
        Settings s = mc.config.getSettings();
        return new Chrome.PaletteGates(
                s.isProjectSupport(),
                mc.git.isEnabled(),
                mc.github.isEnabled(),
                s.isNotesSupport(),
                s.isMermaidSupport(),
                mc.diagram.isEnabled(),
                mc.typst.isEnabled(),
                mc.buildTools.disabledBuildToolIds(),
                mc.lspEnabled(),
                mc.httpClient.isEnabled(),
                mc.htmlPreview.isEnabled(),
                mc.localHistoryEnabled(),
                mc.mcpSupport.mcpEnabled(),
                pluginsEnabled(),
                externalToolsEnabled(),
                mc.logViewer.isEnabled(),
                s.isTestRunner() && !mc.chrome.simpleModeActive(),
                // debugCoordinator is assigned during init(); the palette can't be shown before then, but
                // the predicate is lazy so guard rather than assume the ordering.
                mc.debugCoordinator != null && mc.debugCoordinator.debugSupportEnabled(),
                mc.aiCoordinator.isEnabled(),
                mc.agentCoordinator.isEnabled(),
                s.isTodoHighlight(),
                s.isSpellCheck(),
                s.isCsvPreview(),
                s.isStructuredPreview(),
                s.isPomPreview(),
                mc.indexCoordinator.isEnabled(),
                mc.previews.markdownLintEnabled(),
                mc.editorSettings.editorConfigEnabled(),
                mc.chrome.simpleModeActive());
    }

    /**
     * The localized explanation for a grayed-out palette row — why the command can't run, and (for a
     * switched-off feature) the localized title of the command that would switch it back on. Null when the
     * command is actionable or there is nothing useful to say.
     *
     * <p>Only called for rows that already failed the enabled predicate, i.e. at most once per <em>visible</em>
     * cell, so re-deriving the gate/context snapshot here costs nothing measurable — unlike the predicate
     * itself, which runs over the whole registry.
     */
    @Nullable
    String disabledCommandReason(String commandId) {
        Chrome.DisabledReason reason = Chrome.disabledReason(commandId, paletteGates(), paletteContext());
        if (reason == null) {
            return jumpPossible(commandId) ? null : tr("status.debug.jumpUnsupported");
        }
        // A command id in the reason is named by its localized title, so the tooltip names what the user would
        // actually type into this very palette. The menu bar says the same sentence for the same command.
        return MainMenuBar.reasonSentence(reason);
    }

    /**
     * Re-evaluates the main menu's enabled/disabled state for the context as it stands now.
     *
     * <p>Cheap (a pure lookup per item, no relabel, no re-measure) and pushed from the places the context
     * can move: a tab switch, Git reporting whether we are in a repo, and a debug session changing state.
     * The menu also recomputes on open, which is exact — but on the macOS system menu bar AppKit owns the
     * popup and that hook may not fire, so these pushes are what keep it honest there.
     */
    void refreshMenuEnablement() {
        if (mc.menuBar != null) {
            mc.menuBar.refreshEnablement();
        }
        if (mc.toolbar != null) {
            mc.toolbar.refreshCommandState(); // the toolbar's buttons take the same answer
        }
    }

    /** Jump to Line is greyed while paused in a debugger that cannot do it (java-debug has no goto). */
    boolean jumpPossible(String commandId) {
        return !"debug.jumpToLine".equals(commandId)
                || mc.focusedDap().state() != com.editora.dap.DapManager.State.SUSPENDED
                || mc.focusedDap().supportsJumpToLine();
    }

    Chrome.PaletteContext paletteContext() {
        EditorBuffer b = mc.activeBuffer();
        // Any session of the window, starting or being built for included.
        boolean debugActive = mc.debugCoordinator != null && mc.debugCoordinator.anySessionLive();
        return new Chrome.PaletteContext(
                b != null,
                mc.git.repoRoot() != null,
                b != null && (b.isMarkdown() || b.isTypst()),
                b != null && b.isCsv(),
                b != null && b.isHttpFile(),
                b != null && b.isTypst(),
                b != null && b.hasPreview(),
                debugActive,
                mc.focusedDap().state() == com.editora.dap.DapManager.State.SUSPENDED,
                mc.debugCoordinator == null || mc.debugCoordinator.restartAvailable(),
                mc.git.operationInProgress(),
                b != null && b.hasExportablePreview() && !b.previewShowsError(),
                b != null
                        || (MainController.imagePaneOf(mc.activeTab()) instanceof ImageViewerPane image
                                && image.printableImage() != null),
                b != null
                        && b.getFocusedArea() != null
                        && b.getFocusedArea().getSelection().getLength() > 0,
                mc.projectPanel != null && mc.projectPanel.isMapOutputAvailable(),
                mc.terminalCoordinator != null && mc.terminalCoordinator.hasTerminals(),
                mc.debugCoordinator != null && mc.debugCoordinator.programRunning(),
                mc.focusedDap().isPostMortem(),
                mc.focusedDap().canRunBackward(),
                mc.focusedDap().isGdbSession());
    }

    /** Shows the Run tool window's stripe button for a runnable file, or once a run has started in this window.
     *
     * <p>The second half is essential even when the active buffer itself is not directly runnable (for
     * example, a project Java or NPM configuration). Otherwise a normal context refresh — including the one
     * a run's own exit triggers — closes the console on the output the user is about to read.
     */
    void updateRunButton() {
        EditorBuffer buffer = mc.activeBuffer();
        boolean http = buffer != null && buffer.isHttpFile();
        boolean runnable = buffer != null && buffer.isRunnable() && !http;
        if (mc.runToolWindow != null) {
            mc.toolWindows.setAvailable(mc.runToolWindow, runnable || mc.runCoordinator.consoleInUse());
        }
        if (http && mc.httpClient.isEnabled()) {
            mc.httpClient.refreshEnvironments(buffer); // the .http file's environment pill and menu
        }
    }

    /** Set when the no-project window hid an open TODO window because the active tab has no TODOs, so it
     *  comes back (unfocused) on the next tab that has some; a user close clears it by never setting it. */
    private boolean todoReopenWhenAvailable;

    /**
     * Gates the TODO window on the active buffer: any buffer in a project window; in the no-project window
     * only a saved one with TODO matches (see {@link TodoCoordinator#availableFor}). Re-run on every tab
     * switch and on the active buffer's TODO re-scans, so it hides and returns as TODOs come and go.
     */
    void updateTodoToolWindow() {
        if (mc.todoToolWindow == null) {
            return;
        }
        boolean available = mc.todoCoordinator.availableFor(mc.activeBuffer());
        if (!available) {
            if (mc.toolWindows.isOpen(mc.todoToolWindow)) {
                todoReopenWhenAvailable = mc.todoCoordinator.activeTabOnly();
            }
            mc.toolWindows.setAvailable(mc.todoToolWindow, false); // reconciles every call (see setAvailable)
            return;
        }
        mc.toolWindows.setAvailable(mc.todoToolWindow, true);
        if (todoReopenWhenAvailable) {
            todoReopenWhenAvailable = false;
            if (!mc.toolWindows.isOpen(mc.todoToolWindow)) {
                mc.toolWindows.open(mc.todoToolWindow, false); // unfocused: the user is typing in or switching tabs
                mc.todoCoordinator.refreshPanelIfOpenQuietly();
            }
        }
    }

    /**
     * Gates the stripe buttons of tool windows whose content comes from the <b>active editor buffer</b>, so
     * they only advertise themselves when there is something to act on — hidden on the Welcome page and other
     * non-buffer tabs (mirroring how Run/Debug/HTTP/Commit already gate their buttons). Structure / File
     * Information / TODO need any buffer; Markdown Lint additionally needs a Markdown buffer (with the feature
     * on); External Tools needs a buffer and to not be in Simple mode. Uses transient
     * {@code setAvailable} (never persisted), so the user's show/hide preference is preserved.
     */
    void updateBufferToolWindows() {
        EditorBuffer b = mc.activeBuffer();
        boolean buffer = b != null;
        if (mc.structureToolWindow != null) {
            mc.toolWindows.setAvailable(mc.structureToolWindow, buffer);
        }
        if (mc.fileInfoToolWindow != null) {
            mc.toolWindows.setAvailable(mc.fileInfoToolWindow, buffer);
        }
        if (mc.undoHistoryToolWindow != null) {
            mc.toolWindows.setAvailable(mc.undoHistoryToolWindow, buffer);
        }
        updateTodoToolWindow();
        mc.reviewCoordinator.activeBufferChanged(); // the Review Comments window follows the active file's repository
        mc.previews.activeBufferChanged(b); // the Markdown Lint window follows the active tab
        mc.indexCoordinator.backlinks.activeBufferChanged(b);
        if (mc.externalToolToolWindow != null) {
            mc.toolWindows.setAvailable(mc.externalToolToolWindow, buffer && externalToolsEnabled());
        }
        // Plugin tool windows act on the active editor — gate them on an open buffer too. Null-guarded: the
        // first setupToolWindows() pass runs before pluginCoordinator is built (it needs toolWindows), and no
        // plugin tool windows exist until applyPlugins() registers them, so an early skip is harmless.
        if (mc.pluginCoordinator != null) {
            mc.pluginCoordinator.gateToolWindows(buffer);
        }
        // Hide Git Commit / Git Log on a tab with no Git context; otherwise the Git coordinator gates them.
        if (!mc.git.windowsAllowed(mc.editorArea.selectedTab())) {
            if (mc.commitToolWindow != null) {
                mc.toolWindows.setAvailable(mc.commitToolWindow, false);
            }
            if (mc.gitLogToolWindow != null) {
                mc.toolWindows.setAvailable(mc.gitLogToolWindow, false);
            }
        }
        // Re-derive the GitHub window's availability (its setGitHubWindowAvailable already ANDs an open buffer).
        if (mc.githubToolWindow != null) {
            mc.github.refreshAvailability();
        }
    }

    // --- External Tools (user-defined CLI commands run on the active file/buffer) ---

    /** True when External Tools are available (always, except in Simple UI mode — the list is empty by default). */
    private boolean externalToolsEnabled() {
        return !mc.chrome.simpleModeActive();
    }
}
