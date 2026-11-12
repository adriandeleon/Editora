package com.editora.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.Region;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The window's toolbar: its buttons and their keymap-following tooltips, the fixed tail (run configurations,
 * project switcher, badges, Simple mode and Settings) and the enablement of the edit and split buttons. The
 * {@code @FXML} widgets stay injected into {@link MainController}; the customizable cluster is
 * {@link ToolbarCoordinator}'s. Moved out of {@code MainController} for that file's size.
 */
final class WindowToolbar {

    private final MainController mc;

    ProjectCombo toolbarProjectCombo;
    Region projectToolbarGap;
    /** Id → the existing toolbar widget for each customizable item; built on first use. */
    private java.util.@Nullable Map<String, javafx.scene.Node> toolbarBaseWidgets;

    WindowToolbar(MainController mc) {
        this.mc = mc;
    }

    /** Sets up the buttons, their tooltips and the fixed tail's project group, before the cluster is built. */
    @Initializer
    void install() {
        setupButton(mc.newButton, Icons.newFile(), "file.new");
        setupButton(mc.newFromTemplateButton, Icons.template(), "template.new");
        setupButton(mc.openButton, Icons.open(), "file.find");
        setupButton(mc.openFolderButton, Icons.openFolder(), "project.open");
        setupButton(mc.saveButton, Icons.save(), "file.save");
        setupButton(mc.saveAsButton, Icons.saveAs(), "file.saveAs");
        mc.saveButton.getStyleClass().add("success");
        mc.saveAsButton.getStyleClass().add("success");
        setupButton(mc.undoButton, Icons.undo(), "edit.undo");
        setupButton(mc.redoButton, Icons.redo(), "edit.redo");
        setupButton(mc.cutButton, Icons.cut(), "edit.cut");
        setupButton(mc.copyButton, Icons.copy(), "edit.copy");
        setupButton(mc.pasteButton, Icons.paste(), "edit.paste");
        setupButton(mc.findButton, Icons.find(), "find.show");
        setupButton(mc.findInFilesButton, Icons.findInFiles(), "search.inFiles");
        setupButton(mc.splitVerticalButton, Icons.splitVertical(), "view.splitVertical");
        setupButton(mc.splitHorizontalButton, Icons.splitHorizontal(), "view.splitHorizontal");
        setupButton(mc.runConfigRunButton, Icons.run(), "run.selectedConfig");
        setupButton(mc.runConfigDebugButton, Icons.debug(), "debug.selectedConfig");
        setupButton(mc.runConfigStopButton, Icons.stopSquare(), "run.stop");
        // The kit's one splash of state colour in the toolbar: green play / accent bug / red stop.
        mc.runConfigRunButton.getStyleClass().add("run-config-play");
        mc.runConfigDebugButton.getStyleClass().add("run-config-bug");
        mc.runConfigStopButton.getStyleClass().add("run-config-stop");
        mc.runConfigurations.setupRunConfigCombo();
        setupButton(mc.paletteButton, Icons.palette(), "palette.show");
        setupButton(mc.closeTabButton, Icons.closeTab(), "buffer.close");
        setupButton(mc.simpleModeButton, Icons.simpleMode(), "view.toggleSimpleMode");
        setupButton(mc.settingsButton, Icons.settings(), "view.settings");

        // Reflect open/closed state of the palette and find bar in their toolbar buttons.
        mc.palette
                .showingProperty()
                .addListener((obs, was, now) -> mc.paletteButton.pseudoClassStateChanged(MainController.OPEN, now));
        mc.findBar
                .visibleProperty()
                .addListener((obs, was, now) -> mc.findButton.pseudoClassStateChanged(MainController.OPEN, now));
        // Project switcher (placed after the customizable icon cluster by appendFixedTail); shown when enabled.
        toolbarProjectCombo = new ProjectCombo(mc::switchToProject);
        toolbarProjectCombo.setPrefWidth(184); // 15% longer than the previous 160
        // No "Project:" label: it is the only labelled control on a bar of unlabelled icons, and the combo
        // already reads "No Project" or the project's name. Its explanation moves onto the combo itself —
        // where it was also the one hardcoded English string on the toolbar.
        toolbarProjectCombo.setTooltip(new Tooltip(tr("toolbar.project.tip")));
        toolbarProjectCombo.setOnDeleteProject(mc::deleteProject); // per-item delete in the dropdown
        projectToolbarGap = new Region();
        projectToolbarGap.setMinWidth(78); // ≈ 3 toolbar-icon widths between the icon cluster and the combo
        projectToolbarGap.setPrefWidth(78);
        // The ToolBar is the one that gives when the window narrows: it takes whatever width the pinned tail
        // leaves and moves the icons it cannot fit into its own overflow chevron. Without a zero minimum an
        // HBox would squeeze the tail instead, which is the behaviour this split exists to prevent.
        mc.toolBar.setMinWidth(0);
        // ...and the tail is the one that doesn't. An HBox short of space shrinks EVERY resizable child
        // toward its minimum, sharing the deficit out, so without this the tail would be squeezed alongside
        // the ToolBar instead of the ToolBar absorbing all of it. (Same fix, and the same reason, as
        // StatusBar.pinSegmentWidths.)
        mc.toolbarTail.setMinWidth(Region.USE_PREF_SIZE);
    }

    /** The state the toolbar starts in once the customizable cluster has been built. */
    void initialState() {
        refreshSplitButtons();
        refreshEditState(); // start disabled (no buffer yet)
        refreshPasteState();
        // Pin the toolbar height so it doesn't jump when the (taller) project combo is hidden — by
        // Simple UI mode or by disabling Projects. Measured after the first layout pass (skin + CSS applied).
        Platform.runLater(this::stabilizeToolbarHeight);
    }

    /**
     * Keeps the toolbar a constant height regardless of whether the project combo is showing. The
     * {@link ProjectCombo} is taller than the icon buttons, so toggling its visibility (Simple UI mode, or the
     * Projects feature being off) would otherwise resize the whole bar. Pinning {@code minHeight} to the
     * combo-driven height (measured, no magic number) makes that the bar's floor in every mode; it still grows
     * for any taller child. No-op until the combo has a valid preferred height (after the first layout).
     */
    private void stabilizeToolbarHeight() {
        if (mc.toolbarRow == null || mc.toolbarTail == null || toolbarProjectCombo == null) {
            return;
        }
        double comboH = toolbarProjectCombo.prefHeight(-1); // independent of the combo's current visibility
        if (comboH <= 0) {
            return;
        }
        javafx.geometry.Insets in = mc.toolbarTail.getInsets();
        // The row, not the ToolBar: the combo lives in the tail now, so the ToolBar's own height no longer
        // moves when it is hidden — the row's does.
        mc.toolbarRow.setMinHeight(Math.ceil(comboH + in.getTop() + in.getBottom()));
    }

    /**
     * Fills the fixed, non-customizable toolbar tail — the run-configuration group, the project-combo group
     * (a gap, the combo, and the Open-Folder icon), an optional {@code snapshot}/{@code --dev} badge, and the
     * right-pinned Simple-mode toggle and Settings button. Called by {@link ToolbarCoordinator} after it lays out
     * the customizable cluster.
     *
     * <p>The tail is its <b>own container</b> beside the {@link javafx.scene.control.ToolBar}, not items in
     * it: a ToolBar overflows from its END, so with everything in one bar a narrow window pushed Settings
     * and the project switcher into the chevron while keeping fourteen icons — exactly backwards. The
     * ToolBar now takes the leftover width and overflows its own icons, every one of which is also in the
     * menus and the palette.
     *
     * <p>Re-uses the same field instances, so their show/hide state from Simple mode / Projects survives a
     * rebuild.
     */
    void appendFixedTail() {
        // Cleared here: the coordinator calls this after every rebuild, and the tail is no longer emptied
        // for us by the ToolBar being cleared.
        var items = mc.toolbarTail.getChildren();
        items.clear();

        // Run configurations lead the tail: the selector and its Run/Debug/Stop buttons sit at the right
        // end of the bar, across the ToolBar's slack from the icon cluster, the way an IDE pins its run
        // widget. Here rather than in the customizable cluster because the cluster is what overflows into
        // the chevron when the window narrows, and starting a run is not something to lose to a window
        // width; the group hides itself when there is nothing to launch (refreshRunConfigToolbar).
        items.addAll(mc.runConfigCombo, mc.runConfigRunButton, mc.runConfigDebugButton, mc.runConfigStopButton);

        // Project switcher next (a ~3-icon gap keeps it clear of the run controls), with the open-folder
        // icon immediately to the right of the combobox.
        // Recent is NOT here any more: it is a file action, so it lives in the customizable cluster's file
        // group between Save As and Undo (ToolbarCatalog.defaultLayout), not beside the project controls.
        items.addAll(projectToolbarGap, toolbarProjectCombo, mc.openFolderButton);

        // Snapshot badge when the pom carries -SNAPSHOT, i.e. this was built off master between
        // releases rather than from a release tag — so a test build is obvious without opening About.
        if (com.editora.AppInfo.isSnapshot()) {
            Label snapshotBadge = new Label(tr("badge.snapshot"));
            snapshotBadge.getStyleClass().add("snapshot-badge");
            snapshotBadge.setTooltip(new Tooltip(tr("badge.snapshot.tip", com.editora.AppInfo.VERSION)));
            items.addAll(toolbarGap(14), snapshotBadge);
        }

        // Dev-mode badge (just left of Settings) when running with --dev, so a development instance is
        // visually distinct from the production one.
        if (mc.config.isDev()) {
            Label devBadge = new Label(tr("badge.devMode"));
            devBadge.getStyleClass().add("dev-mode-badge");
            devBadge.setTooltip(
                    new Tooltip(tr("badge.devMode.tip", mc.config.getConfigDir().toString())));
            items.addAll(toolbarGap(14), devBadge);
        }

        // The Simple-mode toggle and Settings close the bar as a pair, set ~2 icons clear of the project
        // controls. The toggle is pinned here, not in the cluster, because it is the way back out of a mode
        // that empties much of that cluster, which a narrow window would overflow. About and Quit were
        // removed: both live in the Help/File menus and the palette, neither is an action anyone takes
        // mid-edit, and Quit sat directly under the window's own close button — a one-icon-high miss
        // between "minimise this window" and "exit the application".
        items.addAll(toolbarGap(40), mc.simpleModeButton, mc.settingsButton);
    }

    /** Id → the existing {@code @FXML} toolbar widget backing each default/special customizable item. */
    java.util.Map<String, javafx.scene.Node> toolbarBaseWidgets() {
        if (toolbarBaseWidgets == null) {
            java.util.Map<String, javafx.scene.Node> m = new java.util.HashMap<>();
            m.put("file.new", mc.newButton);
            m.put("template.new", mc.newFromTemplateButton);
            m.put("file.find", mc.openButton);
            m.put("buffer.close", mc.closeTabButton);
            m.put("file.save", mc.saveButton);
            m.put("file.saveAs", mc.saveAsButton);
            m.put("file.clearRecent", mc.clearRecentButton);
            m.put("toolbar.recent", mc.recentButton);
            m.put("edit.undo", mc.undoButton);
            m.put("edit.redo", mc.redoButton);
            m.put("edit.cut", mc.cutButton);
            m.put("edit.copy", mc.copyButton);
            m.put("edit.paste", mc.pasteButton);
            m.put("find.show", mc.findButton);
            m.put("search.inFiles", mc.findInFilesButton);
            m.put("view.splitVertical", mc.splitVerticalButton);
            m.put("view.splitHorizontal", mc.splitHorizontalButton);
            m.put("palette.show", mc.paletteButton);
            toolbarBaseWidgets = m;
        }
        return toolbarBaseWidgets;
    }

    /** A fixed-width blank gap used to separate the trailing toolbar group without a separator line. */
    private static Region toolbarGap(double width) {
        Region gap = new Region();
        gap.setMinWidth(width);
        gap.setPrefWidth(width);
        return gap;
    }

    /**
     * Reflects the active buffer's split state in the toolbar toggle buttons, and disables them on a tab
     * that can't be split at all — the Welcome/Doctor pages and the image/hex/PDF/diff viewers, none of
     * which is an {@link EditorBuffer}, so {@code MainController.toggleSplit} would silently do nothing there.
     */
    void refreshSplitButtons() {
        EditorBuffer buffer = mc.activeBuffer();
        EditorBuffer.Split split = buffer == null ? EditorBuffer.Split.NONE : buffer.getSplit();
        mc.splitVerticalButton.setDisable(buffer == null);
        mc.splitHorizontalButton.setDisable(buffer == null);
        mc.splitVerticalButton.pseudoClassStateChanged(MainController.OPEN, split == EditorBuffer.Split.SIDE_BY_SIDE);
        mc.splitHorizontalButton.pseudoClassStateChanged(MainController.OPEN, split == EditorBuffer.Split.STACKED);
    }

    /**
     * Sets up a main toolbar button for command {@code commandId}: its glyph, its name (the command's title and
     * live chord, as tooltip and accessible text alike) and its place outside the Tab order, where the command,
     * the palette and the menus reach the action instead.
     */
    void setupButton(Button button, Node icon, String commandId) {
        button.setGraphic(icon);
        button.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        button.setFocusTraversable(false);
        CommandTips.name(button, commandId);
    }

    /** Names every toolbar button again from the current keymap (after a live keymap switch). */
    void refreshToolbarTooltips() {
        CommandTips.refresh();
    }

    /**
     * Enables/disables the state-dependent toolbar edit icons (save/undo/redo/cut/copy) for the active
     * buffer. A non-buffer tab (Welcome) or no buffer ⇒ all disabled. Runs on tab switch and on the
     * buffer's own edit/selection/dirty pulses — no polling. Disabling is cosmetic only; the keybinding
     * commands still work. Clipboard-dependent Paste is handled separately by {@link #refreshPasteState}
     * to keep the system-clipboard read off the per-keystroke path.
     */
    void refreshEditState() {
        if (mc.saveButton == null) {
            return; // toolbar not built yet
        }
        EditorBuffer buffer = mc.activeBuffer();
        CodeArea area = buffer == null ? null : buffer.getFocusedArea();
        boolean editable = buffer != null && buffer.isEditable();
        boolean hasSelection = area != null && area.getSelection().getLength() > 0;
        // The dirty PROPERTY, not isDirty(): the exact answer can compare the whole document (a dirty buffer edited
        // back to its saved length), deferred to the 150 ms settle on purpose; the property's listener re-runs this.
        mc.saveButton.setDisable(buffer == null || !buffer.dirtyProperty().get());
        mc.undoButton.setDisable(area == null || !area.isUndoAvailable());
        mc.redoButton.setDisable(area == null || !area.isRedoAvailable());
        mc.cutButton.setDisable(!hasSelection || !editable);
        mc.copyButton.setDisable(!hasSelection);
    }

    private boolean editStateRefreshPending; // a refreshEditState is queued (edits and selection moves arrive per key)

    /**
     * Coalesces {@link #refreshEditState} to one per event-loop turn (the contract's pending-flag shape): a typed
     * character fires a text change and a selection change, each of which used to re-evaluate five toolbar
     * buttons inside the keystroke. The refresh reads the active buffer when it runs, so it describes the tab shown.
     */
    void scheduleEditStateRefresh() {
        if (editStateRefreshPending) {
            return;
        }
        editStateRefreshPending = true;
        Platform.runLater(() -> {
            editStateRefreshPending = false;
            refreshEditState();
        });
    }

    /**
     * Enables/disables Paste from the system clipboard. Kept separate from {@link #refreshEditState} so
     * the clipboard read happens only on tab switch / window focus-regain / after copy — never per
     * keystroke.
     */
    void refreshPasteState() {
        if (mc.pasteButton == null) {
            return;
        }
        EditorBuffer buffer = mc.activeBuffer();
        boolean editable = buffer != null && buffer.isEditable();
        // hasString() is a round trip to whichever program owns the clipboard (X11, Wayland): asked only
        // when a buffer could take the paste.
        mc.pasteButton.setDisable(!editable || !Clipboard.getSystemClipboard().hasString());
    }

    private final Object pasteRefreshKey = new Object();

    /** {@link #refreshPasteState} after the first painted frame, once however many restored shells land. */
    void schedulePasteStateRefresh() {
        com.editora.perf.AfterFirstPaint.runOnce(pasteRefreshKey, this::refreshPasteState);
    }
}
