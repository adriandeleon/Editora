package com.editora.ui;

import javafx.collections.ObservableList;
import javafx.scene.control.Tab;
import javafx.scene.paint.Color;

import com.editora.config.Settings;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * How a window looks: Zen and Expert mode (per window, never written to the shared settings), the session-only
 * chrome flags from the command line, text and UI zoom, the editor colour theme's stylesheet, and the commands that
 * float, maximize or split a tool window or hide the tool stripe. It lived inside {@code MainController}; it is a
 * file of its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowViewModes {

    private final MainController mc;

    WindowViewModes(MainController mc) {
        this.mc = mc;
    }

    /** The editor-theme override stylesheet currently on the scene, or null for the default theme. */
    private @Nullable String currentEditorThemeCss;

    /**
     * Applies the <b>session-only chrome flags</b> — {@code --simple}, {@code --zen}, {@code --expert} — and is
     * called during window construction <em>before the stage is shown</em> ({@code WindowManager.buildWindow}).
     *
     * <p><b>Why not with the other CLI actions:</b> these only shape chrome and depend on nothing the session
     * restore produces, so they must not ride {@link #pendingAfterRestore} alongside the file targets. That
     * runnable fires only once the pulse-paced restore has finished, which left the window on screen in full
     * chrome for the whole restore and then visibly stripped it — a flash that grew with the number of
     * restored files. Applying them pre-{@code show()} means the first frame is already correct.
     *
     * <p>Running before the session exists is safe: there are no buffers yet, and every file restored
     * afterwards picks the mode up because {@link MainController#addBuffer} runs {@link #applyViewSettings} per buffer.
     */
    void applyStartupChrome(boolean zen, boolean expert, boolean simple) {
        applyStartupChrome(zen, expert, simple, false);
    }

    /** As above, with the session-only standalone diff workspace taking precedence over other focus flags. */
    void applyStartupChrome(boolean zen, boolean expert, boolean simple, boolean diffUi) {
        if (simple) {
            // --simple: a session-only override (doesn't change the saved setting).
            mc.chrome.cliSimpleOverride = true;
            mc.editorSettings.applyViewSettingsToAllBuffers(mc.config.getSettings());
            mc.settingsWindow.syncSimpleModeCheck();
        }
        // --zen / --expert: session-only overrides, like --simple. If both were given, Expert wins — the two
        // are mutually exclusive. (applyCliFocusMode stashes the restored tool windows for the quit-time
        // restore, so it must run after init's toolWindows.restore() — which buildWindow guarantees.)
        if (diffUi) {
            mc.chrome.applyCliDiffUiMode();
        } else if (expert) {
            mc.chrome.applyCliFocusMode(true);
        } else if (zen) {
            mc.chrome.applyCliFocusMode(false);
        }
    }

    /**
     * Picks a tool window to open <em>beside</em> whatever holds its side, splitting that side in two.
     *
     * <p>The picker lists only what could actually join a side right now, so an empty list is the honest
     * answer to "nothing is open to split with" rather than a list of choices that would silently replace.
     */
    void showSplitToolWindowPalette() {
        if (mc.toolWindows.getRegisteredToolWindows().stream().noneMatch(mc.toolWindows::canSplitWith)) {
            mc.setStatus(tr("status.toolwindow.nothingToSplit"));
            return;
        }
        mc.navigation.splitToolWindowPalette.show(mc.stage);
    }

    /**
     * Detaches the active tool window into its own stage, or docks a floating one back.
     *
     * <p>Same target rule as maximize — the focused tool window, else the only open one — because the
     * palette necessarily takes focus out of the panel before the command runs.
     */
    void toggleFloatingToolWindow() {
        ToolWindow tw = mc.toolWindows.maximizeTarget();
        if (tw == null) {
            mc.setStatus(tr("status.toolwindow.noMaximizeTarget"));
            return;
        }
        mc.toolWindows.toggleFloating(tw);
        mc.setStatus(tr(
                mc.toolWindows.isFloating(tw) ? "status.toolwindow.floated" : "status.toolwindow.docked",
                tw.getTitle()));
    }

    /**
     * Expands the active tool window over its split, or hands the space back if it already holds it.
     *
     * <p>Session-only and deliberately unpersisted: maximizing is a "let me read this for a moment" gesture,
     * and a window that reopened next launch covering the editor would read as a broken layout.
     */
    void toggleMaximizedToolWindow() {
        ToolWindow tw = mc.toolWindows.maximizeTarget();
        if (tw == null) {
            mc.setStatus(tr("status.toolwindow.noMaximizeTarget"));
            return;
        }
        mc.toolWindows.toggleMaximized(tw);
        mc.setStatus(tr(
                mc.toolWindows.isMaximized(tw) ? "status.toolwindow.maximized" : "status.toolwindow.restored",
                tw.getTitle()));
    }

    void toggleToolStripe() {
        Settings s = mc.config.getSettings();
        s.setShowToolStripe(!s.isShowToolStripe());
        mc.requestSave();
        mc.editorSettings.applyViewSettingsToAllBuffers(s); // → applyChromeVisibility → toolWindows.setStripesEnabled
        mc.settingsWindow.syncToolStripeCheck();
        mc.setStatus(tr("status.toggle.toolStripe", tr(s.isShowToolStripe() ? "common.on" : "common.off")));
    }

    /**
     * Enters/leaves distraction-free Zen mode for <b>this window only</b>. Zen is a per-window
     * <em>effective overlay</em> (like Simple UI mode): it lives in this window's {@link WorkspaceState}
     * and is folded into {@link #applyChromeVisibility}/{@link #applyViewSettings} via {@link #zenActive()},
     * so it hides the chrome + editor view options <em>without mutating the shared, app-wide
     * {@link Settings}</em>. Leaving Zen therefore restores the user's saved prefs exactly (nothing was
     * changed), and one window being in Zen never affects another. Open tool windows are closed on enter
     * and reopened on leave (a per-window UI snapshot, not a pref). Idempotent.
     *
     * <p>An explicit toggle also <b>takes over from a {@code --zen}/{@code --expert} session flag</b> (as
     * {@link #toggleSimpleMode} does for {@code --simple}): the CLI override is cleared and the real state is
     * written, so entering the mode from a {@code --zen} launch makes it stick from then on.
     */
    void setZenMode(boolean on) {
        WorkspaceState ws = mc.config.getWorkspaceState();
        if (mc.chrome.zenActive() == on) {
            return;
        }
        if (on && mc.chrome.expertActive()) {
            setExpertMode(false); // the two focus modes are mutually exclusive
        }
        if (on) {
            ws.setPreZenToolWindows(mc.toolWindows.closeAllOpen());
        }
        mc.chrome.clearCliFocusOverride(); // an explicit toggle takes over from the --zen/--expert session flag
        ws.setZenMode(on);
        mc.toolWindows.setZenStripesHidden(on || mc.chrome.expertActive());
        if (!on) {
            mc.toolWindows.openByIds(ws.getPreZenToolWindows());
            ws.getPreZenToolWindows().clear();
        }
        mc.chrome.applyChromeVisibility();
        mc.editorSettings.applyViewSettingsToAllBuffers(mc.config.getSettings());
        mc.settingsWindow.syncFocusModeChecks();
        mc.requestSave();
        // When entering Zen the status bar is hidden, so this is mostly seen on exit.
        mc.setStatus(tr("status.toggle.zen", tr(on ? "common.on" : "common.off")));
    }

    /**
     * Enters/leaves Expert mode for <b>this window only</b> — a per-window effective overlay exactly like
     * {@link #setZenMode Zen}, except it <em>keeps the line-number gutter and the status bar</em> (see
     * {@link Chrome}). Mutually exclusive with Zen. Open tool windows are closed on enter and reopened on
     * leave (a per-window UI snapshot, not a pref); the saved {@link Settings} are never mutated. Idempotent.
     */
    void setExpertMode(boolean on) {
        WorkspaceState ws = mc.config.getWorkspaceState();
        if (mc.chrome.expertActive() == on) {
            return;
        }
        if (on && mc.chrome.zenActive()) {
            setZenMode(false); // the two focus modes are mutually exclusive
        }
        if (on) {
            ws.setPreExpertToolWindows(mc.toolWindows.closeAllOpen());
        }
        mc.chrome.clearCliFocusOverride(); // an explicit toggle takes over from the --zen/--expert session flag
        ws.setExpertMode(on);
        mc.toolWindows.setZenStripesHidden(on || mc.chrome.zenActive());
        if (!on) {
            mc.toolWindows.openByIds(ws.getPreExpertToolWindows());
            ws.getPreExpertToolWindows().clear();
        }
        mc.chrome.applyChromeVisibility();
        mc.editorSettings.applyViewSettingsToAllBuffers(mc.config.getSettings());
        mc.settingsWindow.syncFocusModeChecks();
        mc.requestSave();
        mc.setStatus(tr("status.toggle.expert", tr(on ? "common.on" : "common.off")));
    }

    /**
     * Routes a Ctrl + mouse-wheel zoom: in Markdown <em>Preview</em> mode it drives the preview's
     * {@code −}/{@code +} zoom (the rendered text), so the editor text zoom is left untouched there;
     * in every other case (Editor or Split) it zooms the editor text. Called from the scene-level
     * scroll filter in {@code App}.
     */
    void zoomFromWheel(javafx.scene.input.ScrollEvent e) {
        int direction = e.getDeltaY() > 0 ? 1 : -1;
        EditorBuffer b = mc.activeBuffer();
        if (b != null && b.getMarkdownViewMode() == EditorBuffer.MarkdownViewMode.PREVIEW) {
            mc.previews.markdownZoom(direction);
        } else {
            textZoom(direction);
        }
    }

    /**
     * Global text zoom: scales every editor's font on top of the configured size. {@code >0} zooms in,
     * {@code <0} out (±10% steps, clamped 50%–300%), {@code 0} resets to 100%. Persisted in Settings
     * (not shown in the Settings window) and reflected in the status bar.
     */
    void textZoom(int direction) {
        // The content zoom zooms whatever the active tab shows: an image or a PDF has a zoom of its own.
        if (PanelViewerButtonCommands.zoomViewer(mc, direction)) {
            return;
        }
        Settings s = mc.config.getSettings();
        double z = s.getFontZoom();
        if (direction > 0) {
            z += 0.1;
        } else if (direction < 0) {
            z -= 0.1;
        } else {
            z = 1.0;
        }
        z = Math.max(0.5, Math.min(3.0, Math.round(z * 10.0) / 10.0)); // snap to a clean 10% grid
        if (z == s.getFontZoom() && direction != 0) {
            return; // already at the clamp limit
        }
        s.setFontZoom(z);
        mc.requestSave();
        // A font zoom changes no feature gate or theme — apply only the fonts/per-buffer view, not the full
        // settings cascade (editor-theme stylesheet swap + ~20 applySupport() calls). #545
        mc.editorSettings.applyFontsAndPerBufferView(s);
        mc.terminalCoordinator.applySettings(); // terminals zoom with the editors
        mc.statusBar.refresh();
        mc.setStatus(tr("status.textZoom", Math.round(z * 100)));
    }

    /**
     * The UI zoom: scales the whole interface, in every window. {@code >0} zooms in, {@code <0} out (10% steps
     * within {@link Settings#MIN_UI_ZOOM}–{@link Settings#MAX_UI_ZOOM}), {@code 0} goes back to 100%. Saved
     * with the settings; the editor's text keeps its own zoom ({@link #textZoom}).
     */
    void uiZoom(int direction) {
        Settings s = mc.config.getSettings();
        int now = s.getUiZoom();
        int next = direction > 0
                ? com.editora.editor.UiZoom.in(now)
                : direction < 0 ? com.editora.editor.UiZoom.out(now) : Settings.DEFAULT_UI_ZOOM;
        if (next == now) {
            mc.setStatus(
                    direction > 0
                            ? tr("status.uiZoom.largest", now)
                            : direction < 0 ? tr("status.uiZoom.smallest", now) : tr("status.uiZoom.default"));
            return;
        }
        s.setUiZoom(next);
        mc.requestSave();
        Themes.applyUiZoom(next);
        if (mc.settingsWindow != null) {
            mc.settingsWindow.syncAll();
        }
        mc.setStatus(tr("status.uiZoom", next));
    }

    /**
     * Applies the editor color theme: swaps the override stylesheet on the scene (Primer Light uses
     * none — the defaults in app.css/syntax.css) and updates each buffer's current-line highlight.
     * Safe to call before the scene exists (the stylesheet swap is skipped until it does).
     */
    void applyEditorTheme(String themeName) {
        if (mc.stage != null && mc.stage.getScene() != null) {
            ObservableList<String> sheets = mc.stage.getScene().getStylesheets();
            String wanted = EditorThemes.stylesheetFor(themeName);
            // Removing and re-adding the same sheet forces a full scene CSS reapply. This runs on every
            // settings apply (most of which don't touch the theme), so only swap when it actually changed.
            if (!java.util.Objects.equals(wanted, currentEditorThemeCss)
                    || (wanted != null && !sheets.contains(wanted))) {
                if (currentEditorThemeCss != null) {
                    sheets.remove(currentEditorThemeCss);
                }
                currentEditorThemeCss = wanted;
                if (wanted != null && !sheets.contains(wanted)) {
                    sheets.add(wanted);
                }
            }
        }
        if (mc.terminalCoordinator != null) { // runs on every settings apply, theme change or not
            mc.terminalCoordinator.applySettings();
            mc.terminalCoordinator.applyTheme();
        }
        Color highlight = EditorThemes.lineHighlightFor(themeName);
        Color mmText = EditorThemes.minimapTextFor(themeName);
        Color mmViewport = EditorThemes.minimapViewportFor(themeName);
        Color editorBg = EditorThemes.editorBackgroundFor(themeName);
        Color editorFg = EditorThemes.editorForegroundFor(themeName);
        for (Tab tab : mc.editorArea.tabs()) {
            EditorBuffer buffer = MainController.bufferOf(tab);
            if (buffer != null) {
                buffer.setLineHighlightColor(highlight);
                buffer.setMinimapColors(mmText, mmViewport);
                buffer.setFoldPreviewColors(editorBg, editorFg);
            }
        }
    }
}
