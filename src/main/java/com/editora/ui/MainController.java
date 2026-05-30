package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.RecentFiles;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.LanguageRegistry;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javafx.collections.ListChangeListener;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/** Controls the main window: tabbed editors, menu actions, palette/find overlays, and status bar. */
public class MainController {

    private static final PseudoClass OPEN = PseudoClass.getPseudoClass("open");

    @FXML
    private BorderPane root;
    @FXML
    private BorderPane workspace;
    @FXML
    private TabPane tabPane;
    @FXML
    private VBox topBox;
    @FXML
    private VBox bottomBox;
    @FXML
    private Button newButton;
    @FXML
    private Button openButton;
    @FXML
    private Button saveButton;
    @FXML
    private Button saveAsButton;
    @FXML
    private Button undoButton;
    @FXML
    private Button redoButton;
    @FXML
    private Button cutButton;
    @FXML
    private Button copyButton;
    @FXML
    private Button pasteButton;
    @FXML
    private Button findButton;
    @FXML
    private Button splitVerticalButton;
    @FXML
    private Button splitHorizontalButton;
    @FXML
    private Button paletteButton;
    @FXML
    private Button closeTabButton;
    @FXML
    private Button settingsButton;
    @FXML
    private Button aboutButton;
    @FXML
    private Button quitButton;
    @FXML
    private MenuButton recentButton;
    @FXML
    private Button clearRecentButton;

    private Stage stage;
    private ConfigManager config;
    private CommandRegistry registry;
    private KeymapManager keymap;
    private CommandPalette palette;
    private FindReplaceBar findBar;
    private StatusBar statusBar;
    private SettingsWindow settingsWindow;
    private ToolWindowManager toolWindows;
    private ToolWindow projectToolWindow;
    private ToolWindow structureToolWindow;
    private ToolWindow bookmarksToolWindow;
    private ToolWindow fileInfoToolWindow;
    private FileInformationPanel fileInfoPanel;
    private StructurePanel structurePanel;
    private Switcher switcher;
    /** Most-recently-used tab order, head = most recent. */
    private final LinkedList<Tab> mru = new LinkedList<>();
    /** Pinned tabs (identity-based): kept grouped at the front and skipped by bulk-close actions. */
    private final Set<Tab> pinned = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Guards programmatic tab reordering so the MRU list listener doesn't drop the moved tab. */
    private boolean reordering;
    private RecentFiles recentFiles;

    public void init(Stage stage, ConfigManager config, CommandRegistry registry, KeymapManager keymap) {
        this.stage = stage;
        stage.setOnCloseRequest(e -> {
            if (!confirmCloseAllBuffers()) {
                e.consume();
            }
        });
        this.config = config;
        this.registry = registry;
        this.keymap = keymap;
        this.palette = new CommandPalette(registry, keymap);
        this.findBar = new FindReplaceBar(this::activeArea, this::setStatus);
        // Find/replace bar sits between the toolbar and the tabs.
        topBox.getChildren().add(findBar);
        this.statusBar = new StatusBar(this::activeBuffer, registry, config::getSettings);
        bottomBox.getChildren().setAll(statusBar);
        setupToolWindows();
        this.settingsWindow = new SettingsWindow(config, toolWindows, this::applyViewSettingsToAllBuffers);
        this.switcher = new Switcher(() -> List.copyOf(mru),
                tab -> tabPane.getSelectionModel().select(tab),
                this::closeTabFromSwitcher,
                toolWindows);
        setupMruTracking();
        registerCommands();
        setupToolbar();
        setupRecentFiles();
        toolWindows.restore();
    }

    private void setupRecentFiles() {
        recentFiles = new RecentFiles(config.getConfigDir());
        recentButton.setGraphic(Icons.recent());
        recentButton.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        recentButton.setTooltip(new Tooltip("Recent files"));

        // Rebuild the dropdown whenever the recent-files list changes.
        recentFiles.getList().addListener((ListChangeListener<Path>) c -> rebuildRecentMenu());
        rebuildRecentMenu();

        setupButton(clearRecentButton, Icons.trash(), "Clear recent files");
    }

    /** Repopulates the recent-files menu from the persisted list (most-recent first). */
    private void rebuildRecentMenu() {
        recentButton.getItems().clear();
        if (recentFiles.getList().isEmpty()) {
            MenuItem empty = new MenuItem("No recent files");
            empty.setDisable(true);
            recentButton.getItems().add(empty);
            return;
        }
        for (Path path : recentFiles.getList()) {
            recentButton.getItems().add(recentMenuItem(path));
        }
    }

    /** A recent-file menu entry: filename label that opens the file, plus an inline ✕ to remove it. */
    private CustomMenuItem recentMenuItem(Path path) {
        Label name = new Label(path.getFileName().toString());
        Button removeBtn = new Button("✕");
        removeBtn.getStyleClass().addAll("button-icon", "flat", "recent-remove");
        removeBtn.setFocusTraversable(false);
        // Remove just this entry without opening it or closing the menu.
        removeBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            recentFiles.remove(path);
            e.consume();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(8, name, spacer, removeBtn);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        box.setPrefWidth(220);

        CustomMenuItem item = new CustomMenuItem(box);
        item.setOnAction(e -> openPath(path));
        Tooltip.install(box, new Tooltip(path.toString()));
        return item;
    }

    private void setupMruTracking() {
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (now != null) {
                mru.remove(now);
                mru.addFirst(now);
            }
            EditorBuffer buffer = now == null ? null : (EditorBuffer) now.getUserData();
            fileInfoPanel.attach(buffer);
            structurePanel.attach(buffer);
            statusBar.attach(buffer);
            refreshSplitButtons();
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) c -> {
            while (c.next()) {
                // A pin reorder removes+re-adds the same tab; skip cleanup so it isn't forgotten.
                if (c.wasRemoved() && !reordering) {
                    mru.removeAll(c.getRemoved());
                    pinned.removeAll(c.getRemoved());
                }
            }
        });
    }

    private void closeTabFromSwitcher(Tab tab) {
        closeTab(tab);
    }

    private void setupToolWindows() {
        toolWindows = new ToolWindowManager(workspace, tabPane, config, keymap);
        projectToolWindow = new ToolWindow("project", "Project", ToolWindow.Side.RIGHT,
                Icons::project, placeholder("Project tool window\n(content coming soon)"),
                "tool.project");
        structurePanel = new StructurePanel();
        structureToolWindow = new ToolWindow("structure", "Structure", ToolWindow.Side.RIGHT,
                Icons::structure, structurePanel, "tool.structure");
        bookmarksToolWindow = new ToolWindow("bookmarks", "Bookmarks", ToolWindow.Side.RIGHT,
                Icons::bookmark, placeholder("Bookmarks tool window\n(content coming soon)"),
                "tool.bookmarks");
        fileInfoPanel = new FileInformationPanel();
        fileInfoToolWindow = new ToolWindow("file-information", "File Information", ToolWindow.Side.RIGHT,
                Icons::about, fileInfoPanel, "tool.fileInformation");
        toolWindows.register(projectToolWindow);
        toolWindows.register(structureToolWindow);
        toolWindows.register(bookmarksToolWindow);
        toolWindows.register(fileInfoToolWindow);
    }

    private Region placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("tool-window-placeholder");
        label.setWrapText(true);
        StackPane wrapper = new StackPane(label);
        wrapper.setAlignment(javafx.geometry.Pos.CENTER);
        return wrapper;
    }

    private void setupToolbar() {
        setupButton(newButton, Icons.newFile(), "New");
        setupButton(openButton, Icons.open(), "Open (C-x C-f)");
        setupButton(saveButton, Icons.save(), "Save (C-x C-s)");
        setupButton(saveAsButton, Icons.saveAs(), "Save As (C-x C-w)");
        setupButton(undoButton, Icons.undo(), "Undo (C-/)");
        setupButton(redoButton, Icons.redo(), "Redo (C-S-/)");
        setupButton(cutButton, Icons.cut(), "Cut (C-w)");
        setupButton(copyButton, Icons.copy(), "Copy (M-w)");
        setupButton(pasteButton, Icons.paste(), "Paste (C-y)");
        setupButton(findButton, Icons.find(), "Find / Replace (C-s)");
        setupButton(splitVerticalButton, Icons.splitVertical(), "Split Editor — Side by Side (C-x 3)");
        setupButton(splitHorizontalButton, Icons.splitHorizontal(), "Split Editor — Stacked (C-x 2)");
        setupButton(paletteButton, Icons.palette(), "Command Palette (M-x)");
        setupButton(closeTabButton, Icons.closeTab(), "Close Tab (C-x k)");
        setupButton(settingsButton, Icons.settings(), "Settings");
        setupButton(aboutButton, Icons.about(), "About Editora");
        setupButton(quitButton, Icons.quit(), "Quit (C-x C-c)");

        // Reflect open/closed state of the palette and find bar in their toolbar buttons.
        palette.showingProperty().addListener(
                (obs, was, now) -> paletteButton.pseudoClassStateChanged(OPEN, now));
        findBar.visibleProperty().addListener(
                (obs, was, now) -> findButton.pseudoClassStateChanged(OPEN, now));
        refreshSplitButtons();
    }

    @FXML
    private void onSplitVertical() {
        toggleSplit(EditorBuffer.Split.SIDE_BY_SIDE);
    }

    @FXML
    private void onSplitHorizontal() {
        toggleSplit(EditorBuffer.Split.STACKED);
    }

    private void toggleSplit(EditorBuffer.Split orientation) {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        buffer.toggleSplit(orientation);
        refreshSplitButtons();
        setStatus(buffer.getSplit() == EditorBuffer.Split.NONE ? "Editor unsplit" : "Editor split");
    }

    private void unsplit() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.setSplit(EditorBuffer.Split.NONE);
            refreshSplitButtons();
            setStatus("Editor unsplit");
        }
    }

    /** Reflects the active buffer's split state in the toolbar toggle buttons. */
    private void refreshSplitButtons() {
        EditorBuffer buffer = activeBuffer();
        EditorBuffer.Split split = buffer == null ? EditorBuffer.Split.NONE : buffer.getSplit();
        splitVerticalButton.pseudoClassStateChanged(OPEN, split == EditorBuffer.Split.SIDE_BY_SIDE);
        splitHorizontalButton.pseudoClassStateChanged(OPEN, split == EditorBuffer.Split.STACKED);
    }

    private void setupButton(Button button, Node icon, String tooltip) {
        button.setGraphic(icon);
        button.getStyleClass().addAll("button-icon", "flat", "toolbar-button");
        button.setTooltip(new Tooltip(tooltip));
    }

    public void openInitialBuffer() {
        addBuffer(new EditorBuffer());
    }

    public void setStatus(String message) {
        statusBar.setMessage(message);
    }

    private EditorBuffer activeBuffer() {
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        return tab == null ? null : (EditorBuffer) tab.getUserData();
    }

    private CodeArea activeArea() {
        EditorBuffer buffer = activeBuffer();
        return buffer == null ? null : buffer.getFocusedArea();
    }

    private void addBuffer(EditorBuffer buffer) {
        applyViewSettings(buffer);
        buffer.getFoldManager().setOnFoldStateChanged(() -> persistFolds(buffer));
        Tab tab = new Tab();
        tab.setContent(buffer.getNode());
        tab.setUserData(buffer);
        tab.setOnCloseRequest(e -> {
            if (!confirmClose(tab)) {
                e.consume();
            }
        });
        updateTabMeta(tab, buffer);
        buffer.dirtyProperty().addListener((obs, was, now) -> updateTabMeta(tab, buffer));
        installTabMenu(tab, buffer);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        buffer.getArea().requestFocus();
    }

    /** Refreshes a tab's title (pin + dirty markers), style classes, and full-path tooltip. */
    private void updateTabMeta(Tab tab, EditorBuffer buffer) {
        boolean dirty = buffer.isDirty();
        boolean isPinned = pinned.contains(tab);
        tab.setText((dirty ? "• " : "") + buffer.getTitle());
        // Pinned tabs are marked with an SVG pin graphic (matching the toolbar icons), not a glyph.
        tab.setGraphic(isPinned ? Icons.pin() : null);
        toggleClass(tab, "dirty", dirty);
        toggleClass(tab, "pinned", isPinned);
        Path p = buffer.getPath();
        tab.setTooltip(new Tooltip(p != null ? p.toAbsolutePath().toString() : "untitled"));
    }

    private static void toggleClass(Tab tab, String styleClass, boolean on) {
        if (on) {
            if (!tab.getStyleClass().contains(styleClass)) {
                tab.getStyleClass().add(styleClass);
            }
        } else {
            tab.getStyleClass().remove(styleClass);
        }
    }

    // --- File actions ---

    @FXML
    private void onNew() {
        addBuffer(new EditorBuffer());
        setStatus("New buffer");
    }

    @FXML
    private void onOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open File");
        Path file = pathOf(chooser.showOpenDialog(stage));
        if (file != null) {
            openPath(file);
        }
    }

    /** Open a file by path; refreshes recent files and reports status. */
    private void openPath(Path file) {
        Tab existing = tabForPath(file);
        if (existing != null) {
            // Already open — switch to its tab instead of opening a duplicate.
            tabPane.getSelectionModel().select(existing);
            ((EditorBuffer) existing.getUserData()).getArea().requestFocus();
            if (recentFiles != null) {
                recentFiles.add(file);
            }
            setStatus("Already open: " + file.getFileName());
            return;
        }
        try {
            String content = Files.readString(file);
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(content);
            addBuffer(buffer);
            restoreFolds(buffer);
            // Land on the first line: replaceText leaves the caret at the end, and fold restoration
            // moves it to a fold header. Defer so the viewport scroll runs after the tab is laid out.
            Platform.runLater(buffer::goToStart);
            if (recentFiles != null) {
                recentFiles.add(file);
            }
            setStatus("Opened " + file);
        } catch (IOException e) {
            setStatus("Failed to open: " + e.getMessage());
            if (recentFiles != null) {
                recentFiles.remove(file);
            }
        }
    }

    @FXML
    private void onClearRecent() {
        if (recentFiles != null) {
            recentFiles.clear();
            setStatus("Recent files cleared");
        }
    }

    @FXML
    private void onSave() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            save(buffer);
        }
    }

    @FXML
    private void onSaveAs() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            saveAs(buffer);
        }
    }

    /** @return true if the buffer is on disk afterwards (either already saved or just saved). */
    private boolean save(EditorBuffer buffer) {
        if (buffer.getPath() == null) {
            return saveAs(buffer);
        }
        return writeBuffer(buffer, buffer.getPath());
    }

    private boolean saveAs(EditorBuffer buffer) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save As");
        Path file = pathOf(chooser.showSaveDialog(stage));
        if (file == null) {
            return false;
        }
        buffer.setPath(file);
        boolean ok = writeBuffer(buffer, file);
        Tab tab = tabFor(buffer);
        if (tab != null) {
            updateTabMeta(tab, buffer);
        }
        return ok;
    }

    private boolean writeBuffer(EditorBuffer buffer, Path file) {
        try {
            Files.writeString(file, buffer.getContent());
            buffer.markClean();
            setStatus("Saved " + file);
            return true;
        } catch (IOException e) {
            setStatus("Failed to save: " + e.getMessage());
            return false;
        }
    }

    @FXML
    private void onCloseTab() {
        closeTab(activeTab());
    }

    private Tab activeTab() {
        return tabPane.getSelectionModel().getSelectedItem();
    }

    private static EditorBuffer bufferOf(Tab tab) {
        return tab == null ? null : (EditorBuffer) tab.getUserData();
    }

    /** Closes a single tab, confirming first if it is pinned and/or has unsaved changes. */
    private void closeTab(Tab tab) {
        if (tab != null && confirmClose(tab)) {
            tabPane.getTabs().remove(tab);
        }
    }

    /**
     * Closes each tab in {@code targets} (a snapshot), prompting for dirty buffers and stopping if
     * the user cancels — mirroring {@link #confirmCloseAllBuffers()}.
     */
    private void closeTabs(List<Tab> targets) {
        for (Tab tab : targets) {
            EditorBuffer buffer = bufferOf(tab);
            if (buffer != null && !buffer.isDirty()) {
                tabPane.getTabs().remove(tab);
                continue;
            }
            tabPane.getSelectionModel().select(tab);
            if (buffer != null && !confirmCloseIfDirty(buffer)) {
                return; // user cancelled — stop the batch
            }
            tabPane.getTabs().remove(tab);
        }
    }

    /** Non-pinned tabs whose index is less than {@code pivot}'s. */
    private List<Tab> eligibleToLeft(Tab pivot) {
        int idx = tabPane.getTabs().indexOf(pivot);
        List<Tab> out = new ArrayList<>();
        for (int i = 0; i < idx; i++) {
            Tab t = tabPane.getTabs().get(i);
            if (!pinned.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Non-pinned tabs whose index is greater than {@code pivot}'s. */
    private List<Tab> eligibleToRight(Tab pivot) {
        int idx = tabPane.getTabs().indexOf(pivot);
        List<Tab> out = new ArrayList<>();
        for (int i = idx + 1; i < tabPane.getTabs().size(); i++) {
            Tab t = tabPane.getTabs().get(i);
            if (!pinned.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private void closeOtherTabs(Tab keep) {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : tabPane.getTabs()) {
            if (t != keep && !pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    private void closeAllTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : tabPane.getTabs()) {
            if (!pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    private void closeUnmodifiedTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : tabPane.getTabs()) {
            EditorBuffer buffer = bufferOf(t);
            if (!pinned.contains(t) && (buffer == null || !buffer.isDirty())) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    private void closeTabsToLeft(Tab pivot) {
        if (pivot != null) {
            closeTabs(eligibleToLeft(pivot));
        }
    }

    private void closeTabsToRight(Tab pivot) {
        if (pivot != null) {
            closeTabs(eligibleToRight(pivot));
        }
    }

    /** Copies the buffer's absolute path to the system clipboard. */
    private void copyPath(EditorBuffer buffer) {
        if (buffer == null || buffer.getPath() == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(buffer.getPath().toAbsolutePath().toString());
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("Copied path");
    }

    /**
     * Toggles a tab's pinned state. Pinned tabs are kept grouped at the front of the strip (in pin
     * order) and skipped by the bulk-close actions.
     */
    private void togglePin(Tab tab) {
        if (tab == null) {
            return;
        }
        if (pinned.remove(tab)) {
            // Unpinned: move just past the remaining pinned group.
            moveTab(tab, pinned.size());
        } else {
            pinned.add(tab);
            // Pinned: park at the end of the pinned group so multiple pins stay grouped.
            moveTab(tab, pinned.size() - 1);
        }
        updateTabMeta(tab, bufferOf(tab));
        setStatus(pinned.contains(tab) ? "Pinned" : "Unpinned");
    }

    /** Moves {@code tab} to {@code target} without corrupting the MRU (see the reordering guard). */
    private void moveTab(Tab tab, int target) {
        int from = tabPane.getTabs().indexOf(tab);
        if (from < 0) {
            return;
        }
        reordering = true;
        try {
            tabPane.getTabs().remove(tab);
            int clamped = Math.max(0, Math.min(target, tabPane.getTabs().size()));
            tabPane.getTabs().add(clamped, tab);
        } finally {
            reordering = false;
        }
        tabPane.getSelectionModel().select(tab);
    }

    /** Renames the buffer's file on disk and migrates path-keyed state (folds, recent files). */
    private void renameFile(EditorBuffer buffer, Tab tab) {
        if (buffer == null || buffer.getPath() == null) {
            return;
        }
        Path old = buffer.getPath();
        TextInputDialog dialog = new TextInputDialog(old.getFileName().toString());
        dialog.initOwner(stage);
        dialog.setTitle("Rename File");
        dialog.setHeaderText(null);
        dialog.setContentText("New name:");
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            Path target = old.resolveSibling(trimmed);
            if (target.equals(old)) {
                return;
            }
            if (Files.exists(target)) {
                setStatus("Rename failed: " + target.getFileName() + " already exists");
                return;
            }
            try {
                Files.move(old, target);
            } catch (IOException e) {
                setStatus("Rename failed: " + e.getMessage());
                return;
            }
            buffer.setPath(target); // re-detects language/grammar
            // Migrate state keyed by the absolute path string.
            Settings s = config.getSettings();
            List<Integer> folds = s.getFoldedRegions().remove(old.toString());
            if (folds != null) {
                s.getFoldedRegions().put(target.toString(), folds);
            }
            if (recentFiles != null) {
                recentFiles.remove(old);
                recentFiles.add(target);
            }
            config.save();
            updateTabMeta(tab, buffer);
            statusBar.refresh();
            setStatus("Renamed to " + target.getFileName());
        });
    }

    /** Builds and attaches the right-click context menu for a tab. */
    private void installTabMenu(Tab tab, EditorBuffer buffer) {
        MenuItem close = new MenuItem("Close");
        close.setOnAction(e -> closeTab(tab));
        MenuItem closeOthers = new MenuItem("Close Other Tabs");
        closeOthers.setOnAction(e -> closeOtherTabs(tab));
        MenuItem closeAll = new MenuItem("Close All Tabs");
        closeAll.setOnAction(e -> closeAllTabs());
        MenuItem closeUnmodified = new MenuItem("Close Unmodified Tabs");
        closeUnmodified.setOnAction(e -> closeUnmodifiedTabs());
        MenuItem closeLeft = new MenuItem("Close Tabs to the Left");
        closeLeft.setOnAction(e -> closeTabsToLeft(tab));
        MenuItem closeRight = new MenuItem("Close Tabs to the Right");
        closeRight.setOnAction(e -> closeTabsToRight(tab));
        MenuItem copyPath = new MenuItem("Copy Path");
        copyPath.setOnAction(e -> copyPath(buffer));
        MenuItem pin = new MenuItem("Pin Tab");
        pin.setOnAction(e -> togglePin(tab));
        MenuItem rename = new MenuItem("Rename File…");
        rename.setOnAction(e -> renameFile(buffer, tab));

        ContextMenu menu = new ContextMenu(
                close, closeOthers, closeAll, closeUnmodified,
                new SeparatorMenuItem(),
                closeLeft, closeRight,
                new SeparatorMenuItem(),
                copyPath, pin, rename);
        menu.setOnShowing(e -> {
            closeLeft.setDisable(eligibleToLeft(tab).isEmpty());
            closeRight.setDisable(eligibleToRight(tab).isEmpty());
            boolean hasPath = buffer.getPath() != null;
            copyPath.setDisable(!hasPath);
            rename.setDisable(!hasPath);
            pin.setText(pinned.contains(tab) ? "Unpin Tab" : "Pin Tab");
        });
        tab.setContextMenu(menu);
    }

    /**
     * @return true if {@code tab} may close — confirming first if it is pinned, then running the
     *         unsaved-changes check. Used by every single-tab close (the X, the command, the menu).
     */
    private boolean confirmClose(Tab tab) {
        EditorBuffer buffer = bufferOf(tab);
        if (buffer == null) {
            return true;
        }
        if (pinned.contains(tab) && !confirmClosePinned(buffer)) {
            return false;
        }
        return confirmCloseIfDirty(buffer);
    }

    /** @return true if the user confirms closing a pinned tab. */
    private boolean confirmClosePinned(EditorBuffer buffer) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("Pinned tab");
        alert.setHeaderText("Close pinned tab " + buffer.getTitle() + "?");
        alert.setContentText(null);
        ButtonType close = new ButtonType("Close");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(close, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == close;
    }

    /** @return true if the tab is allowed to close (saved, discarded, or wasn't dirty). */
    private boolean confirmCloseIfDirty(EditorBuffer buffer) {
        if (!buffer.isDirty()) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle("Unsaved changes");
        alert.setHeaderText("Save changes to " + buffer.getTitle() + " before closing?");
        alert.setContentText(null);
        ButtonType save = new ButtonType("Save");
        ButtonType discard = new ButtonType("Discard");
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(save, discard, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancel) {
            return false;
        }
        if (result.get() == save) {
            return save(buffer);
        }
        return true; // discard
    }

    private Tab tabFor(EditorBuffer buffer) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getUserData() == buffer) {
                return tab;
            }
        }
        return null;
    }

    /**
     * The tab whose buffer is backed by {@code file}, or {@code null} if it isn't open. Paths are
     * compared as normalized absolute paths so relative vs. absolute (or {@code .}/{@code ..})
     * spellings of the same file still match. Untitled buffers (no path) are skipped.
     */
    private Tab tabForPath(Path file) {
        Path target = file.toAbsolutePath().normalize();
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
            Path p = buffer == null ? null : buffer.getPath();
            if (p != null && p.toAbsolutePath().normalize().equals(target)) {
                return tab;
            }
        }
        return null;
    }

    @FXML
    private void onQuit() {
        if (confirmCloseAllBuffers()) {
            Platform.exit();
        }
    }

    /** Walks every tab and prompts to save/discard each dirty buffer. False = user cancelled. */
    private boolean confirmCloseAllBuffers() {
        for (Tab tab : new ArrayList<>(tabPane.getTabs())) {
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
            if (buffer == null || !buffer.isDirty()) {
                continue;
            }
            tabPane.getSelectionModel().select(tab);
            if (!confirmCloseIfDirty(buffer)) {
                return false;
            }
        }
        return true;
    }

    private void nextBuffer() {
        int count = tabPane.getTabs().size();
        if (count > 1) {
            int idx = (tabPane.getSelectionModel().getSelectedIndex() + 1) % count;
            tabPane.getSelectionModel().select(idx);
        }
    }

    // --- Edit actions (delegate to active CodeArea) ---

    @FXML
    private void onUndo() {
        withArea(CodeArea::undo);
    }

    @FXML
    private void onRedo() {
        withArea(CodeArea::redo);
    }

    @FXML
    private void onCut() {
        withArea(CodeArea::cut);
    }

    @FXML
    private void onCopy() {
        withArea(CodeArea::copy);
    }

    @FXML
    private void onPaste() {
        withArea(CodeArea::paste);
    }

    @FXML
    private void onFind() {
        toggleFind(false);
    }

    /** Shows the find/replace bar, or hides it if it's already open. */
    private void toggleFind(boolean backward) {
        if (findBar.isShown()) {
            findBar.hideBar();
        } else {
            findBar.show(backward);
        }
    }

    @FXML
    private void onPalette() {
        palette.show(stage);
    }

    @FXML
    private void onSettings() {
        settingsWindow.show(stage);
    }

    @FXML
    private void onAbout() {
        SettingsWindow.showAbout(stage);
    }

    private void toggleColumnRuler() {
        Settings s = config.getSettings();
        s.setShowColumnRuler(!s.isShowColumnRuler());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("80-column ruler: " + (s.isShowColumnRuler() ? "on" : "off"));
    }

    private void toggleLineHighlight() {
        Settings s = config.getSettings();
        s.setHighlightCurrentLine(!s.isHighlightCurrentLine());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("Current line highlight: " + (s.isHighlightCurrentLine() ? "on" : "off"));
    }

    private void toggleLineNumbers() {
        Settings s = config.getSettings();
        s.setShowLineNumbers(!s.isShowLineNumbers());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("Line numbers: " + (s.isShowLineNumbers() ? "on" : "off"));
    }

    private void toggleMinimap() {
        Settings s = config.getSettings();
        s.setShowMinimap(!s.isShowMinimap());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("Minimap: " + (s.isShowMinimap() ? "on" : "off"));
    }

    private void toggleWhitespace() {
        Settings s = config.getSettings();
        s.setShowWhitespace(!s.isShowWhitespace());
        config.save();
        applyViewSettingsToAllBuffers(s);
        setStatus("Hidden characters: " + (s.isShowWhitespace() ? "on" : "off"));
    }

    /**
     * Emacs {@code C-x o}: cycles keyboard focus between the editor and any open tool windows.
     * Order: editor, then each open tool window (by side); wraps back to the editor.
     */
    private void otherWindow() {
        List<Node> targets = new ArrayList<>();
        CodeArea area = activeArea();
        if (area != null) {
            targets.add(area);
        }
        for (ToolWindow tw : toolWindows.getOpenToolWindows()) {
            targets.add(tw.getContent());
        }
        if (targets.size() < 2) {
            return; // nothing to switch to
        }
        Node focusOwner = root.getScene() == null ? null : root.getScene().getFocusOwner();
        int current = indexOfContaining(targets, focusOwner);
        int next = current < 0 ? 0 : (current + 1) % targets.size();
        focusWindow(targets.get(next));
    }

    /** Index of the target that contains (or is) the focus owner, or -1 if none. */
    private static int indexOfContaining(List<Node> targets, Node focusOwner) {
        for (int i = 0; i < targets.size(); i++) {
            for (Node n = focusOwner; n != null; n = n.getParent()) {
                if (n == targets.get(i)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void focusWindow(Node target) {
        if (target instanceof StructurePanel structure) {
            structure.focusContent();
        } else {
            target.requestFocus();
        }
    }

    private void foldAll() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.foldAll();
            setStatus("Folded all regions");
        }
    }

    private void unfoldAll() {
        EditorBuffer buffer = activeBuffer();
        if (buffer != null) {
            buffer.unfoldAll();
            setStatus("Unfolded all regions");
        }
    }

    /** Prompts for a 1-based line number and moves the caret there (clamped to the document). */
    private void goToLine() {
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        int total = area.getParagraphs().size();
        TextInputDialog dialog = new TextInputDialog(String.valueOf(area.getCurrentParagraph() + 1));
        dialog.initOwner(stage);
        dialog.setTitle("Go to Line");
        dialog.setHeaderText(null);
        dialog.setContentText("Line (1–" + total + "):");
        dialog.showAndWait().ifPresent(input -> {
            try {
                int target = Math.max(1, Math.min(total, Integer.parseInt(input.trim()))) - 1;
                moveAndFollow(a -> a.moveTo(target, 0));
                setStatus("Line " + (target + 1));
            } catch (NumberFormatException e) {
                setStatus("Not a line number: " + input);
            }
        });
    }

    /** Lets the user override the syntax language/grammar for the active buffer. */
    private void chooseLanguage() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        names.add(LanguageRegistry.plaintext());
        names.addAll(GrammarRegistry.shared().availableLanguageNames());
        String current = names.contains(buffer.getLanguage()) ? buffer.getLanguage() : names.get(0);
        ChoiceDialog<String> dialog = new ChoiceDialog<>(current, names);
        dialog.initOwner(stage);
        dialog.setTitle("Set Language");
        dialog.setHeaderText(null);
        dialog.setContentText("Language:");
        dialog.showAndWait().ifPresent(name -> {
            buffer.setLanguageOverride(name);
            statusBar.refresh();
            setStatus("Language: " + name);
        });
    }

    /** Changes the (persisted) tab width and applies it to every buffer. */
    private void chooseTabSize() {
        Settings s = config.getSettings();
        List<Integer> options = List.of(2, 4, 8);
        ChoiceDialog<Integer> dialog = new ChoiceDialog<>(
                options.contains(s.getTabSize()) ? s.getTabSize() : 4, options);
        dialog.initOwner(stage);
        dialog.setTitle("Tab Size");
        dialog.setHeaderText(null);
        dialog.setContentText("Tab size (columns):");
        dialog.showAndWait().ifPresent(size -> {
            s.setTabSize(size);
            config.save();
            applyViewSettingsToAllBuffers(s);
            statusBar.refresh();
            setStatus("Tab size: " + size);
        });
    }

    /** Converts the active buffer's line endings between LF and CRLF. */
    private void chooseLineEndings() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(buffer.getLineEnding(), List.of("LF", "CRLF"));
        dialog.initOwner(stage);
        dialog.setTitle("Line Endings");
        dialog.setHeaderText(null);
        dialog.setContentText("Line endings:");
        dialog.showAndWait().ifPresent(choice -> {
            buffer.convertLineEndings("CRLF".equals(choice));
            statusBar.refresh();
            setStatus("Line endings: " + choice);
        });
    }

    /** Persists the buffer's collapsed fold regions, keyed by its file path. */
    private void persistFolds(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Integer> lines = buffer.getFoldManager().collapsedStartLines();
        var map = config.getSettings().getFoldedRegions();
        if (lines.isEmpty()) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), lines);
        }
        config.save();
    }

    /** Re-applies a file's saved collapsed fold regions after it is opened. */
    private void restoreFolds(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Integer> saved = config.getSettings().getFoldedRegions().get(file.toString());
        buffer.getFoldManager().applyCollapsedStartLines(saved);
        buffer.markClean();
    }

    private void applyViewSettings(EditorBuffer buffer) {
        Settings s = config.getSettings();
        buffer.setFont(s.getFontFamily(), s.getFontSize());
        buffer.setColumnRulerVisible(s.isShowColumnRuler());
        buffer.setLineHighlightOn(s.isHighlightCurrentLine());
        buffer.setLineNumbersVisible(s.isShowLineNumbers());
        buffer.setMinimapVisible(s.isShowMinimap());
        buffer.setWhitespaceVisible(s.isShowWhitespace());
        buffer.setTabSize(s.getTabSize());
    }

    private void applyViewSettingsToAllBuffers(Settings settings) {
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
            if (buffer != null) {
                applyViewSettings(buffer);
            }
        }
    }

    private void cancel() {
        if (palette.isShown()) {
            palette.hide();
        } else if (findBar.isShown()) {
            findBar.hideBar();
        } else {
            CodeArea area = activeArea();
            if (area != null) {
                area.deselect();
            }
        }
        setStatus("");
    }

    private void withArea(java.util.function.Consumer<CodeArea> action) {
        CodeArea area = activeArea();
        if (area != null) {
            action.accept(area);
        }
    }

    /** Run a navigation action and scroll the viewport to follow the caret. */
    private void moveAndFollow(java.util.function.Consumer<CodeArea> motion) {
        CodeArea area = activeArea();
        if (area == null) {
            return;
        }
        motion.accept(area);
        area.requestFollowCaret();
    }

    private static Path pathOf(java.io.File file) {
        return file == null ? null : file.toPath();
    }

    private void registerCommands() {
        registry.register(Command.of("file.new", "File: New", this::onNew));
        registry.register(Command.of("file.open", "File: Open…", this::onOpen));
        registry.register(Command.of("file.save", "File: Save", this::onSave));
        registry.register(Command.of("file.saveAs", "File: Save As…", this::onSaveAs));
        registry.register(Command.of("buffer.close", "Buffer: Close", this::onCloseTab));
        registry.register(Command.of("buffer.closeOthers", "Buffer: Close Other Tabs",
                () -> closeOtherTabs(activeTab())));
        registry.register(Command.of("buffer.closeAll", "Buffer: Close All Tabs", this::closeAllTabs));
        registry.register(Command.of("buffer.closeUnmodified", "Buffer: Close Unmodified Tabs",
                this::closeUnmodifiedTabs));
        registry.register(Command.of("buffer.closeLeft", "Buffer: Close Tabs to the Left",
                () -> closeTabsToLeft(activeTab())));
        registry.register(Command.of("buffer.closeRight", "Buffer: Close Tabs to the Right",
                () -> closeTabsToRight(activeTab())));
        registry.register(Command.of("buffer.copyPath", "Buffer: Copy Path",
                () -> copyPath(activeBuffer())));
        registry.register(Command.of("buffer.togglePin", "Buffer: Toggle Pin",
                () -> togglePin(activeTab())));
        registry.register(Command.of("buffer.rename", "Buffer: Rename File…",
                () -> renameFile(activeBuffer(), activeTab())));
        registry.register(Command.of("buffer.next", "Buffer: Next", this::nextBuffer));
        registry.register(Command.of("app.quit", "Application: Quit", this::onQuit));
        registry.register(Command.of("palette.show", "Command Palette", this::onPalette));
        registry.register(Command.of("view.settings", "Settings", this::onSettings));
        registry.register(Command.of("view.toggleColumnRuler", "View: Toggle 80-Column Ruler",
                this::toggleColumnRuler));
        registry.register(Command.of("view.toggleLineHighlight", "View: Toggle Current Line Highlight",
                this::toggleLineHighlight));
        registry.register(Command.of("view.toggleLineNumbers", "View: Toggle Line Numbers",
                this::toggleLineNumbers));
        registry.register(Command.of("view.toggleMinimap", "View: Toggle Minimap",
                this::toggleMinimap));
        registry.register(Command.of("view.toggleWhitespace", "View: Toggle Hidden Characters",
                this::toggleWhitespace));
        registry.register(Command.of("view.splitVertical", "View: Split Editor — Side by Side",
                this::onSplitVertical));
        registry.register(Command.of("view.splitHorizontal", "View: Split Editor — Stacked",
                this::onSplitHorizontal));
        registry.register(Command.of("view.unsplit", "View: Unsplit Editor", this::unsplit));
        registry.register(Command.of("view.foldAll", "View: Fold All", this::foldAll));
        registry.register(Command.of("view.unfoldAll", "View: Unfold All", this::unfoldAll));
        registry.register(Command.of("nav.goToLine", "Go: Go to Line…", this::goToLine));
        registry.register(Command.of("buffer.setLanguage", "Buffer: Set Language…", this::chooseLanguage));
        registry.register(Command.of("buffer.setTabSize", "Buffer: Set Tab Size…", this::chooseTabSize));
        registry.register(Command.of("buffer.convertLineEndings", "Buffer: Convert Line Endings (LF/CRLF)…",
                this::chooseLineEndings));
        registry.register(Command.of("window.other", "Window: Other (Editor / Tool Window)",
                this::otherWindow));
        registry.register(Command.of("file.clearRecent", "File: Clear Recent Files", this::onClearRecent));
        registry.register(Command.of("help.about", "About Editora", this::onAbout));
        registry.register(Command.of("tool.project", "Tool Window: Project",
                () -> toolWindows.toggle(projectToolWindow)));
        registry.register(Command.of("tool.structure", "Tool Window: Structure",
                () -> toolWindows.toggle(structureToolWindow)));
        registry.register(Command.of("tool.bookmarks", "Tool Window: Bookmarks",
                () -> toolWindows.toggle(bookmarksToolWindow)));
        registry.register(Command.of("tool.fileInformation", "Tool Window: File Information",
                () -> toolWindows.toggle(fileInfoToolWindow)));
        registry.register(Command.of("switcher.show", "Switcher",
                () -> switcher.show(stage, false)));
        registry.register(Command.of("switcher.showReverse", "Switcher (Reverse)",
                () -> switcher.show(stage, true)));
        registry.register(Command.of("find.show", "Find", () -> toggleFind(false)));
        registry.register(Command.of("find.showBackward", "Find Backward", () -> toggleFind(true)));
        registry.register(Command.of("find.replace", "Replace", () -> toggleFind(false)));
        registry.register(Command.of("edit.cut", "Edit: Cut", this::onCut));
        registry.register(Command.of("edit.copy", "Edit: Copy", this::onCopy));
        registry.register(Command.of("edit.paste", "Edit: Paste", this::onPaste));
        registry.register(Command.of("edit.undo", "Edit: Undo", this::onUndo));
        registry.register(Command.of("edit.redo", "Edit: Redo", this::onRedo));
        registry.register(Command.of("edit.cancel", "Cancel", this::cancel));
        registry.register(Command.of("nav.lineStart", "Go: Line Start",
                () -> moveAndFollow(a -> a.lineStart(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.lineEnd", "Go: Line End",
                () -> moveAndFollow(a -> a.lineEnd(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.docStart", "Go: Document Start",
                () -> moveAndFollow(a -> a.start(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.docEnd", "Go: Document End",
                () -> moveAndFollow(a -> a.end(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.charForward", "Go: Forward Char",
                () -> moveAndFollow(a -> a.moveTo(Math.min(a.getLength(), a.getCaretPosition() + 1)))));
        registry.register(Command.of("nav.charBackward", "Go: Backward Char",
                () -> moveAndFollow(a -> a.moveTo(Math.max(0, a.getCaretPosition() - 1)))));
        registry.register(Command.of("nav.lineDown", "Go: Next Line",
                () -> moveAndFollow(a -> a.nextLine(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.lineUp", "Go: Previous Line",
                () -> moveAndFollow(a -> a.prevLine(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.wordForward", "Go: Forward Word",
                () -> moveAndFollow(a -> a.moveTo(nextWordBoundary(a.getText(), a.getCaretPosition())))));
        registry.register(Command.of("nav.wordBackward", "Go: Backward Word",
                () -> moveAndFollow(a -> a.moveTo(prevWordBoundary(a.getText(), a.getCaretPosition())))));
        registry.register(Command.of("nav.pageDown", "Go: Page Down",
                () -> moveAndFollow(a -> a.nextPage(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.pageUp", "Go: Page Up",
                () -> moveAndFollow(a -> a.prevPage(SelectionPolicy.CLEAR))));
        registry.register(Command.of("edit.deleteChar", "Edit: Delete Forward Char",
                () -> withArea(CodeArea::deleteNextChar)));
        registry.register(Command.of("edit.killWord", "Edit: Kill Forward Word",
                () -> withArea(a -> {
                    int caret = a.getCaretPosition();
                    a.deleteText(caret, nextWordBoundary(a.getText(), caret));
                })));
        registry.register(Command.of("edit.killLine", "Edit: Kill Line",
                () -> withArea(this::killLine)));
    }

    private void killLine(CodeArea a) {
        int caret = a.getCaretPosition();
        int para = a.getCurrentParagraph();
        int eol = a.getAbsolutePosition(para, a.getParagraphLength(para));
        if (caret < eol) {
            a.deleteText(caret, eol);
        } else if (para + 1 < a.getParagraphs().size()) {
            a.deleteText(caret, caret + 1);
        }
    }

    /** Position of the next word boundary at or after {@code from}: skip non-word chars, then word chars. */
    static int nextWordBoundary(String text, int from) {
        int i = from;
        while (i < text.length() && !Character.isLetterOrDigit(text.charAt(i))) {
            i++;
        }
        while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Position of the previous word boundary at or before {@code from}. */
    static int prevWordBoundary(String text, int from) {
        int i = from;
        while (i > 0 && !Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && Character.isLetterOrDigit(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }
}
