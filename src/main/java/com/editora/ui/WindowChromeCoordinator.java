package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.Settings;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/** Owns window chrome visibility, focus modes and overlays. */
final class WindowChromeCoordinator {
    interface Host {
        PseudoClass OPEN();

        BorderPane root();

        EditorArea editorArea();

        MainMenuBar menuBar();

        ToolBar toolBar();

        HBox toolbarRow();

        Button newFromTemplateButton();

        Button findInFilesButton();

        Button splitVerticalButton();

        Button splitHorizontalButton();

        Button simpleModeButton();

        MenuButton recentButton();

        Button clearRecentButton();

        ConfigManager config();

        CommandPalette palette();

        StatusBar statusBar();

        FileBreadcrumb breadcrumb();

        SettingsWindow settingsWindow();

        OverlayHost overlayHost();

        QuickOpen<Project> projectPicker();

        ToolWindowManager toolWindows();

        BookmarkCoordinator bookmarkCoordinator();

        Switcher switcher();

        void openExternalUrl(String url);

        EditorSettingsCoordinator editorSettings();

        RunConfigurationCoordinator runConfigurations();

        NavigationCoordinator navigation();

        GitWindowCoordinator gitWindows();

        List<BuildCoordinator> buildCoordinators();

        SearchEverywherePopup.Ops searchEverywhereOps();

        IndexCoordinator indexCoordinator();

        NotesCoordinator notesCoordinator();

        void setStatus(String message);

        EditorBuffer activeBuffer();

        CodeArea activeArea();

        void requestSave();

        void setZenMode(boolean on);

        void setExpertMode(boolean on);
    }

    SearchEverywherePopup searchEverywherePopup;
    private final Host host;

    WindowChromeCoordinator(Host host) {
        this.host = host;
    }

    /** Session-only Simple-UI override from the {@code --simple} CLI flag; OR'd with the saved setting. */
    boolean cliSimpleOverride;

    /** Session-only Zen override from the {@code --zen} CLI flag; OR'd with this window's saved Zen state. */
    boolean cliZenOverride;

    /** Session-only Expert override from the {@code --expert} CLI flag (the {@code --zen} twin). */
    boolean cliExpertOverride;

    /** Session-only standalone diff chrome from {@code --diff-ui}; never written to workspace state. */
    boolean cliDiffUiOverride;

    /**
     * The open tool windows a {@code --zen}/{@code --expert} session override closed, as the persisted
     * {left, right, bottom} ids. Entering a focus mode calls {@link ToolWindowManager#closeAllOpen()}, and
     * {@code close()} persists "nothing open" — so without restoring these at quit, a session-only focus
     * mode would silently lose the user's docked tool windows on the next launch. {@code null} = no CLI
     * focus-mode override is in effect (including after an in-app toggle takes over from the flag).
     */
    String[] cliFocusToolWindows;

    /** Tool windows closed by the transient standalone diff workspace, restored by its toolbar action. */
    List<String> cliDiffUiToolWindows = List.of();

    /** Floating "exit Zen" button overlaid top-right of the window; shown only while in Zen mode. */
    Button zenExitButton;

    /** Floating "exit Expert" button hosted inside the active code viewport; shown only in Expert mode. */
    Button expertExitButton;

    EditorBuffer expertExitButtonBuffer;

    /** Floating "show toolbar" button overlaid top-left; shown only when the toolbar is hidden (not in Zen). */
    Button toolbarRestoreButton;

    /** Shows/hides the toolbar and status bar per the saved settings (hidden nodes also unmanaged so
     *  they don't reserve layout space). Cheap: two visibility flags + one layout pass. */
    void applyChromeVisibility() {
        Settings s = host.config().getSettings();
        // Zen and Simple are per-window effective overlays: they hide chrome without mutating the shared
        // saved prefs, so the prefs return untouched when the mode is left, and one window's Zen never
        // leaks into another (Zen lives in this window's WorkspaceState, not in Settings).
        boolean zen = zenActive();
        boolean expert = expertActive();
        boolean diffUi = diffUiActive();
        boolean focus = zen || expert || diffUi;
        boolean zenLike = zen || diffUi; // standalone diff strips menu + status like Zen
        boolean simple = simpleModeActive();
        // Effective visibility (Chrome, pure + unit-tested): a saved pref AND not hidden by a focus mode/Simple.
        boolean toolbarOn = Chrome.toolbar(s.isShowToolbar(), focus);
        host.toolBar().setVisible(toolbarOn);
        host.toolBar().setManaged(toolbarOn);
        // The bar is TWO containers on one row (see appendFixedTail): the ToolBar's icon cluster and the
        // pinned toolbarTail (project combo, Open Folder, the snapshot/--dev badges, Settings).
        // Hiding only the ToolBar left the tail's icons stranded on an otherwise-stripped window in Zen and
        // Expert, so the row that holds both is what gets hidden. Unmanaged too, or the row's pinned
        // minHeight (stabilizeToolbarHeight) would keep reserving a bar-height strip of empty space.
        if (host.toolbarRow() != null) {
            host.toolbarRow().setVisible(toolbarOn);
            host.toolbarRow().setManaged(toolbarOn);
        }
        // The status bar is hidden by Zen but KEPT by Expert, so it keys on the real zen flag, not focus.
        boolean statusOn = Chrome.statusBar(s.isShowStatusBar(), zenLike);
        host.statusBar().setVisible(statusOn);
        host.statusBar().setManaged(statusOn);
        host.editorArea().setTabHeaderVisible(Chrome.tabBar(s.isShowTabBar(), focus));
        if (host.menuBar() != null) {
            // Expert keeps the command menu available; only Zen suppresses it. This must use the real Zen
            // flag (not `focus`) so a CLI --expert launch has a menu on its very first frame.
            boolean menuOn = Chrome.menuBar(s.isShowMenuBar(), zenLike);
            host.menuBar().node().setVisible(menuOn);
            host.menuBar().node().setManaged(menuOn);
            // Simple UI mode keeps the menu bar but swaps in the reduced table (a no-op when unchanged).
            host.menuBar().setSimple(simple);
            host.menuBar().refresh(); // features and keybindings may have moved since the last apply
        }
        host.breadcrumb().setEnabled(Chrome.breadcrumb(s.isShowBreadcrumb(), focus, simple));
        // Tool stripes (UI only): hidden stripes still let tool windows open via keybinding/palette.
        host.toolWindows().setStripesEnabled(Chrome.toolStripes(s.isShowToolStripe(), focus, simple));
        applySimpleMode();
        updateZenButton();
        updateExpertButton();
        updateToolbarRestoreButton();
    }

    /** True when Simple UI mode is active (the saved setting OR the session-only {@code --simple} flag). */
    boolean simpleModeActive() {
        return host.config().getSettings().isSimpleMode() || cliSimpleOverride;
    }

    /** True when this window is in distraction-free Zen mode — this window's saved state OR the session-only
     *  {@code --zen} flag (which, like {@code --simple}, never touches the saved session). */
    boolean zenActive() {
        return host.config().getWorkspaceState().isZenMode() || cliZenOverride;
    }

    /** True when this window is in Expert mode — like Zen, but keeps line numbers + the status bar. */
    boolean expertActive() {
        return host.config().getWorkspaceState().isExpertMode() || cliExpertOverride;
    }

    /** True only for the transient, command-line standalone diff workspace. */
    boolean diffUiActive() {
        return cliDiffUiOverride;
    }

    /**
     * Simple UI mode: hide the marked toolbar groups + status-bar segments (line numbers/minimap are
     * handled via {@link #applyViewSettings}; the project trio via {@link #applyProjectSupport}). Inert
     * when Simple mode is off (everything returns to its normal, gate-respecting state).
     */
    void applySimpleMode() {
        boolean simple = simpleModeActive();
        // Curated toolbar buttons hidden in Simple mode (project trio + openFolder are gated in
        // applyProjectSupport so its later pass doesn't re-show them). The Open icon is deliberately
        // KEPT so opening a file stays one click away in Simple mode.
        for (Button b : new Button[] {
            host.newFromTemplateButton(),
            host.clearRecentButton(),
            host.findInFilesButton(),
            host.splitVerticalButton(),
            host.splitHorizontalButton()
        }) {
            b.setVisible(!simple);
            b.setManaged(!simple);
        }
        host.recentButton().setVisible(!simple);
        host.recentButton().setManaged(!simple);
        // The run-config group has its own rule (project + launchable), which already folds in Simple mode.
        host.runConfigurations().refreshRunConfigToolbar();
        // Each build-tool button's visibility otherwise follows marker-file detection (BuildCoordinator), not
        // this unconditional show/hide — re-derive it from the cached detection now that isEnabled() (which
        // folds in !simpleModeActive()) may have changed, rather than forcing it shown.
        host.buildCoordinators().forEach(BuildCoordinator::reapplyVisibility);
        collapseToolbarSeparators();
        // The toolbar button reads as "on" while the mode is, like the find/palette/split toggles — Simple
        // mode is a state you are IN, and its own button is the most likely way back out, so it must not look
        // identical whether or not it is engaged. Driven from here rather than from toggleSimpleMode() because
        // this is the one place the effective state is computed (the setting OR the --simple session flag).
        host.simpleModeButton().pseudoClassStateChanged(host.OPEN(), simple);
        host.statusBar().setSimpleMode(simple);
    }

    /**
     * Hide toolbar {@link Separator}s that would be orphaned (leading, trailing, or with no visible
     * control between them and the previous separator), so hiding button groups leaves no stray dividers.
     * Self-correcting — when nothing is hidden, every separator is shown.
     */
    void collapseToolbarSeparators() {
        Separator pending = null; // a separator with a visible item before it, awaiting one after
        boolean visibleSincePending = false;
        for (javafx.scene.Node item : host.toolBar().getItems()) {
            if (item instanceof Separator sep) {
                sep.setVisible(false);
                sep.setManaged(false);
                if (visibleSincePending) {
                    pending = sep;
                    visibleSincePending = false;
                }
            } else if (item.isVisible()) {
                if (pending != null) {
                    pending.setVisible(true);
                    pending.setManaged(true);
                    pending = null;
                }
                visibleSincePending = true;
            }
        }
    }

    /**
     * Installs the floating "exit Zen" button into the scene-root overlay (top-right of the window).
     * Called by {@code App} after the scene is built. Hidden until Zen mode is entered.
     */
    public void installZenOverlay(StackPane sceneRoot) {
        zenExitButton = new Button();
        zenExitButton.setGraphic(Icons.zen());
        zenExitButton.getStyleClass().addAll("zen-exit", "flat");
        zenExitButton.setTooltip(new Tooltip(tr("tooltip.zenExit")));
        zenExitButton.setFocusTraversable(false);
        zenExitButton.setOnAction(e -> host.setZenMode(false));
        StackPane.setAlignment(zenExitButton, Pos.TOP_RIGHT);
        sceneRoot.getChildren().add(zenExitButton);

        // Floating "exit Expert" button (top-right, an "E"): shown only in Expert mode. Mirrors the Zen "Z";
        // the two never coexist (the modes are mutually exclusive).
        expertExitButton = new Button();
        expertExitButton.setGraphic(Icons.expert());
        expertExitButton.getStyleClass().addAll("expert-exit", "flat");
        expertExitButton.setTooltip(new Tooltip(tr("tooltip.expertExit")));
        expertExitButton.setFocusTraversable(false);
        expertExitButton.setOnAction(e -> host.setExpertMode(false));

        // Floating "show toolbar" button (top-right): restores a hidden toolbar. Never coexists with the
        // Zen "Z" (that's shown only in Zen mode, this only when the toolbar is hidden outside Zen).
        toolbarRestoreButton = new Button();
        toolbarRestoreButton.setGraphic(Icons.tools());
        toolbarRestoreButton.getStyleClass().addAll("toolbar-restore", "flat");
        toolbarRestoreButton.setTooltip(new Tooltip(tr("tooltip.showToolbar")));
        toolbarRestoreButton.setFocusTraversable(false);
        toolbarRestoreButton.setOnAction(e -> toggleToolbar());
        StackPane.setAlignment(toolbarRestoreButton, Pos.TOP_RIGHT);
        StackPane.setMargin(toolbarRestoreButton, new javafx.geometry.Insets(8, 12, 0, 0));
        sceneRoot.getChildren().add(toolbarRestoreButton);

        // In-scene overlay host (replaces focus-stealing Popups): the command palette and pickers show
        // their card here so keyboard focus works on every platform. Installed last so it sits on top.
        host.overlayHost().install(sceneRoot);
        wireOverlayHost();

        updateZenButton();
        updateExpertButton();
        updateToolbarRestoreButton();
    }

    /**
     * Injects the shared {@link OverlayHost} into every keyboard picker/popup so they render their card
     * in the main scene (focus works on every platform) instead of a focus-stealing {@link javafx.stage.Popup}.
     * Called once from {@link #installZenOverlay}, after all the field pickers are built in {@link #init}.
     * On-demand pickers (LSP references, spell language) get the host at their construction sites.
     */
    void wireOverlayHost() {
        host.palette().setOverlayHost(host.overlayHost());
        host.palette().setDocsOpener(host::openExternalUrl); // C-h → command docs in the system browser
        host.navigation().recentPalette.setOverlayHost(host.overlayHost());
        host.navigation().structurePalette.setOverlayHost(host.overlayHost());
        host.navigation().openFilesPalette.setOverlayHost(host.overlayHost());
        host.navigation().toolWindowPalette.setOverlayHost(host.overlayHost());
        host.navigation().undoHistoryPalette.setOverlayHost(host.overlayHost());
        host.indexCoordinator().setOverlayHost(host.overlayHost());
        searchEverywherePopup = new SearchEverywherePopup(host.overlayHost(), host.searchEverywhereOps());
        host.navigation().recentLocationsPalette.setOverlayHost(host.overlayHost());
        host.bookmarkCoordinator().wireOverlayHost();
        host.notesCoordinator().wireOverlayHost();
        host.navigation().snippetPalette.setOverlayHost(host.overlayHost());
        host.projectPicker().setOverlayHost(host.overlayHost());
        host.navigation().fileFinder.setOverlayHost(host.overlayHost());
        host.navigation().folderFinder.setOverlayHost(host.overlayHost());
        host.switcher().setOverlayHost(host.overlayHost());
        host.gitWindows().branchPopup.setOverlayHost(host.overlayHost());
        host.statusBar().setOverlayHost(host.overlayHost());
        host.buildCoordinators().forEach(c -> c.setOverlayHost(host.overlayHost()));
    }

    /**
     * Shows the floating "show toolbar" button only when the toolbar is hidden and we're not in Zen mode
     * (Zen hides the whole chrome and the "Z" already restores it). Cheap visibility toggle.
     */
    void updateToolbarRestoreButton() {
        if (toolbarRestoreButton == null) {
            return;
        }
        boolean show = !host.config().getSettings().isShowToolbar()
                && !zenActive()
                && !expertActive()
                && !diffUiActive(); // a focus mode hides the toolbar; its own control restores it
        toolbarRestoreButton.setVisible(show);
        toolbarRestoreButton.setManaged(show);
    }

    /**
     * Shows the floating exit button only while in Zen mode (so it never overlaps normal chrome). When
     * the active file is Markdown its floating preview controls also sit top-right, so the Z is dropped
     * below them to avoid overlapping.
     */
    void updateZenButton() {
        if (zenExitButton == null) {
            return;
        }
        boolean zen = zenActive();
        zenExitButton.setVisible(zen);
        zenExitButton.setManaged(zen);
        EditorBuffer active = host.activeBuffer();
        boolean belowMarkdownControls = zen && active != null && active.hasPreview();
        double top = belowMarkdownControls ? 44 : 8; // clear the Markdown preview toggle when present
        StackPane.setMargin(zenExitButton, new javafx.geometry.Insets(top, 12, 0, 0));
    }

    /**
     * Shows the floating "exit Expert" ("E") button only while in Expert mode. Unlike the scene-root Zen
     * control, Expert's button belongs inside the active code pane so it cannot overlap the title bar or
     * minimap. The shared node is moved when tab selection changes.
     */
    void updateExpertButton() {
        if (expertExitButton == null) {
            return;
        }
        EditorBuffer active = host.activeBuffer();
        if (expertExitButtonBuffer != active) {
            if (expertExitButtonBuffer != null) {
                expertExitButtonBuffer.setExpertExitControl(null);
            }
            expertExitButtonBuffer = active;
        }
        boolean show = expertActive() && active != null;
        expertExitButton.setVisible(show);
        expertExitButton.setManaged(show);
        if (show) {
            active.setExpertExitControl(expertExitButton);
        } else if (active != null) {
            active.setExpertExitControl(null);
        }
    }

    /**
     * Flips the master "Enable plugins" gate. Plugins load only at startup (no hot classloader/UI unload),
     * so this just persists the preference and reports that a restart is needed; the Settings checkbox is
     * re-synced for discoverability.
     */
    void toggleSimpleMode() {
        Settings s = host.config().getSettings();
        cliSimpleOverride = false; // an explicit in-app toggle takes over from the --simple session flag
        s.setSimpleMode(!s.isSimpleMode());
        host.requestSave();
        if (simpleModeActive()) {
            // Entering Simple mode hides the tool stripe, so close any docked tool window too.
            for (ToolWindow tw : host.toolWindows().getOpenToolWindows()) {
                host.toolWindows().close(tw);
            }
        }
        host.editorSettings()
                .applyViewSettingsToAllBuffers(
                        s); // → applyChromeVisibility/applySimpleMode + per-buffer gutter/minimap
        host.settingsWindow().syncSimpleModeCheck();
        host.setStatus(tr("status.toggle.simpleMode", tr(s.isSimpleMode() ? "common.on" : "common.off")));
    }

    void toggleZen() {
        host.setZenMode(!zenActive());
    }

    void toggleExpert() {
        host.setExpertMode(!expertActive());
    }

    /**
     * Applies a {@code --zen} / {@code --expert} <b>session-only</b> focus mode: the same effect as the real
     * mode, but nothing is written to the saved session — quit and relaunch without the flag and the window
     * comes back normal. This mirrors {@code --simple} ({@link #cliSimpleOverride}).
     *
     * <p>Entering a focus mode closes the docked tool windows, and {@link ToolWindowManager#close} <em>persists</em>
     * "nothing open" — so the ids are stashed in {@link #cliFocusToolWindows} and written back at quit
     * ({@link #persistSession}); otherwise a session-only mode would still lose them for good.
     *
     * <p>No-op when this window's <em>saved</em> session already has a focus mode on (nothing to override).
     */
    void applyCliFocusMode(boolean expert) {
        if (zenActive() || expertActive()) {
            return;
        }
        WorkspaceState ws = host.config().getWorkspaceState();
        cliFocusToolWindows =
                new String[] {ws.getOpenLeftToolWindow(), ws.getOpenRightToolWindow(), ws.getOpenBottomToolWindow()};
        if (expert) {
            cliExpertOverride = true;
            ws.setPreExpertToolWindows(host.toolWindows().closeAllOpen()); // the in-app "E" exit reopens from here
        } else {
            cliZenOverride = true;
            ws.setPreZenToolWindows(host.toolWindows().closeAllOpen()); // ditto for the "Z"
        }
        host.toolWindows().setZenStripesHidden(true);
        applyChromeVisibility();
        host.editorSettings().applyViewSettingsToAllBuffers(host.config().getSettings());
        // Deliberately no requestSave(): the flag must leave the saved session untouched.
    }

    /** Applies the chrome-only, session-free workspace used by {@code --diff-ui}. */
    void applyCliDiffUiMode() {
        if (cliDiffUiOverride) {
            return;
        }
        cliDiffUiToolWindows = host.toolWindows().closeAllOpen();
        cliDiffUiOverride = true;
        host.toolWindows().setZenStripesHidden(true);
        applyChromeVisibility();
        host.editorSettings().applyViewSettingsToAllBuffers(host.config().getSettings());
    }

    /** Leaves the standalone presentation while retaining the live diff tab and its loaded content. */
    void exitDiffUiMode() {
        if (!cliDiffUiOverride) {
            return;
        }
        cliDiffUiOverride = false;
        host.toolWindows().setZenStripesHidden(zenActive() || expertActive());
        host.toolWindows().openByIds(cliDiffUiToolWindows);
        cliDiffUiToolWindows = List.of();
        for (DiffViewerPane pane : diffCoordinatorPanes()) {
            pane.setExitDiffUiAction(null);
        }
        applyChromeVisibility();
        host.editorSettings().applyViewSettingsToAllBuffers(host.config().getSettings());
        host.setStatus(tr("status.diff.fullUi"));
    }

    List<DiffViewerPane> diffCoordinatorPanes() {
        List<DiffViewerPane> panes = new ArrayList<>();
        for (Tab tab : host.editorArea().tabs()) {
            if (tab.getUserData() instanceof DiffViewerPane pane) {
                panes.add(pane);
            }
        }
        return panes;
    }

    /** Drops a {@code --zen}/{@code --expert} session override — an in-app toggle now owns the state, so the
     *  quit-time tool-window restore must not fire. */
    void clearCliFocusOverride() {
        cliZenOverride = false;
        cliExpertOverride = false;
        cliFocusToolWindows = null;
    }

    void toggleToolbar() {
        Settings s = host.config().getSettings();
        s.setShowToolbar(!s.isShowToolbar());
        host.requestSave();
        applyChromeVisibility();
        host.settingsWindow().syncToolbarCheck();
        host.setStatus(tr("status.toggle.toolbar", tr(s.isShowToolbar() ? "common.on" : "common.off")));
    }

    void toggleBreadcrumb() {
        Settings s = host.config().getSettings();
        s.setShowBreadcrumb(!s.isShowBreadcrumb());
        host.requestSave();
        applyChromeVisibility();
        host.setStatus(tr("status.toggle.breadcrumb", tr(s.isShowBreadcrumb() ? "common.on" : "common.off")));
    }

    void toggleStatusBar() {
        Settings s = host.config().getSettings();
        s.setShowStatusBar(!s.isShowStatusBar());
        host.requestSave();
        applyChromeVisibility();
        // The status bar may now be hidden, so this message just confirms the toggle while visible.
        host.setStatus(tr("status.toggle.statusBar", tr(s.isShowStatusBar() ? "common.on" : "common.off")));
    }

    void toggleTabBar() {
        Settings s = host.config().getSettings();
        s.setShowTabBar(!s.isShowTabBar());
        host.requestSave();
        applyChromeVisibility();
        host.setStatus(tr("status.toggle.tabBar", tr(s.isShowTabBar() ? "common.on" : "common.off")));
    }

    /**
     * Emacs {@code C-x o}: cycles keyboard focus between the editor and any open tool windows.
     * Order: editor, then each open tool window (by side); wraps back to the editor.
     */
    void otherWindow() {
        List<Node> targets = new ArrayList<>();
        CodeArea area = host.activeArea();
        if (area != null) {
            targets.add(area);
        }
        for (ToolWindow tw : host.toolWindows().getOpenToolWindows()) {
            targets.add(tw.getContent());
        }
        if (targets.size() < 2) {
            return; // nothing to switch to
        }
        Node focusOwner =
                host.root().getScene() == null ? null : host.root().getScene().getFocusOwner();
        int current = indexOfContaining(targets, focusOwner);
        int next = current < 0 ? 0 : (current + 1) % targets.size();
        focusWindow(targets.get(next));
    }

    /** Index of the target that contains (or is) the focus owner, or -1 if none. */
    static int indexOfContaining(List<Node> targets, Node focusOwner) {
        for (int i = 0; i < targets.size(); i++) {
            for (Node n = focusOwner; n != null; n = n.getParent()) {
                if (n == targets.get(i)) {
                    return i;
                }
            }
        }
        return -1;
    }

    static void focusWindow(Node target) {
        if (target instanceof StructurePanel structure) {
            structure.focusContent();
        } else if (target instanceof ToolWindowContent content) {
            // Focus a real focusable child (e.g. the Debug stack list) rather than the panel container —
            // a bare VBox isn't focus-traversable, so requestFocus() on it wouldn't move focus in, leaving
            // the panel's local key shortcuts (e.g. the Debug toolbar keys) inert after C-x o.
            content.focusFirstItem();
        } else {
            target.requestFocus();
        }
    }
}
