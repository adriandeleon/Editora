package com.editora.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import javafx.collections.ListChangeListener;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
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
    private Label statusLabel;
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
    private Button paletteButton;
    @FXML
    private Button closeTabButton;
    @FXML
    private Button settingsButton;
    @FXML
    private Button aboutButton;
    @FXML
    private Button quitButton;

    private Stage stage;
    private ConfigManager config;
    private CommandRegistry registry;
    private CommandPalette palette;
    private FindReplaceBar findBar;
    private SettingsWindow settingsWindow;
    private ToolWindowManager toolWindows;
    private ToolWindow projectToolWindow;
    private ToolWindow bookmarksToolWindow;
    private ToolWindow fileInfoToolWindow;
    private FileInformationPanel fileInfoPanel;
    private Switcher switcher;
    /** Most-recently-used tab order, head = most recent. */
    private final LinkedList<Tab> mru = new LinkedList<>();

    public void init(Stage stage, ConfigManager config, CommandRegistry registry, KeymapManager keymap) {
        this.stage = stage;
        stage.setOnCloseRequest(e -> {
            if (!confirmCloseAllBuffers()) {
                e.consume();
            }
        });
        this.config = config;
        this.registry = registry;
        this.palette = new CommandPalette(registry, keymap);
        this.findBar = new FindReplaceBar(this::activeArea, this::setStatus);
        // Find/replace bar sits between the toolbar and the tabs.
        topBox.getChildren().add(findBar);
        setupToolWindows();
        this.settingsWindow = new SettingsWindow(config, toolWindows, this::applyFontToAllBuffers);
        this.switcher = new Switcher(() -> List.copyOf(mru),
                tab -> tabPane.getSelectionModel().select(tab),
                this::closeTabFromSwitcher,
                toolWindows);
        setupMruTracking();
        registerCommands();
        setupToolbar();
        toolWindows.restore();
    }

    private void setupMruTracking() {
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (now != null) {
                mru.remove(now);
                mru.addFirst(now);
            }
            EditorBuffer buffer = now == null ? null : (EditorBuffer) now.getUserData();
            fileInfoPanel.attach(buffer);
        });
        tabPane.getTabs().addListener((ListChangeListener<Tab>) c -> {
            while (c.next()) {
                if (c.wasRemoved()) {
                    mru.removeAll(c.getRemoved());
                }
            }
        });
    }

    private void closeTabFromSwitcher(Tab tab) {
        EditorBuffer buffer = (EditorBuffer) tab.getUserData();
        if (buffer == null || confirmCloseIfDirty(buffer)) {
            tabPane.getTabs().remove(tab);
        }
    }

    private void setupToolWindows() {
        toolWindows = new ToolWindowManager(workspace, tabPane, config);
        projectToolWindow = new ToolWindow("project", "Project", ToolWindow.Side.RIGHT,
                Icons::project, placeholder("Project tool window\n(content coming soon)"));
        bookmarksToolWindow = new ToolWindow("bookmarks", "Bookmarks", ToolWindow.Side.RIGHT,
                Icons::bookmark, placeholder("Bookmarks tool window\n(content coming soon)"));
        fileInfoPanel = new FileInformationPanel();
        fileInfoToolWindow = new ToolWindow("file-information", "File Information", ToolWindow.Side.RIGHT,
                Icons::about, fileInfoPanel);
        toolWindows.register(projectToolWindow);
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
    }

    private void setupButton(Button button, Node icon, String tooltip) {
        button.setGraphic(icon);
        button.getStyleClass().addAll("button-icon", "flat");
        button.setTooltip(new Tooltip(tooltip));
    }

    public void openInitialBuffer() {
        addBuffer(new EditorBuffer());
    }

    public void setStatus(String message) {
        statusLabel.setText(message);
    }

    private EditorBuffer activeBuffer() {
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        return tab == null ? null : (EditorBuffer) tab.getUserData();
    }

    private CodeArea activeArea() {
        EditorBuffer buffer = activeBuffer();
        return buffer == null ? null : buffer.getArea();
    }

    private void addBuffer(EditorBuffer buffer) {
        Settings settings = config.getSettings();
        buffer.setFont(settings.getFontFamily(), settings.getFontSize());
        Tab tab = new Tab();
        tab.setContent(buffer.getNode());
        tab.setUserData(buffer);
        tab.setOnCloseRequest(e -> {
            if (!confirmCloseIfDirty(buffer)) {
                e.consume();
            }
        });
        updateTitle(tab, buffer);
        buffer.dirtyProperty().addListener((obs, was, now) -> updateTitle(tab, buffer));
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        buffer.getArea().requestFocus();
    }

    private void updateTitle(Tab tab, EditorBuffer buffer) {
        boolean dirty = buffer.isDirty();
        tab.setText((dirty ? "• " : "") + buffer.getTitle());
        if (dirty) {
            if (!tab.getStyleClass().contains("dirty")) {
                tab.getStyleClass().add("dirty");
            }
        } else {
            tab.getStyleClass().remove("dirty");
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
        if (file == null) {
            return;
        }
        try {
            String content = Files.readString(file);
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(content);
            addBuffer(buffer);
            setStatus("Opened " + file);
        } catch (IOException e) {
            setStatus("Failed to open: " + e.getMessage());
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
            updateTitle(tab, buffer);
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
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        if (tab == null) {
            return;
        }
        EditorBuffer buffer = (EditorBuffer) tab.getUserData();
        if (buffer != null && !confirmCloseIfDirty(buffer)) {
            return;
        }
        tabPane.getTabs().remove(tab);
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

    private void applyFontToAllBuffers(Settings settings) {
        for (Tab tab : tabPane.getTabs()) {
            EditorBuffer buffer = (EditorBuffer) tab.getUserData();
            if (buffer != null) {
                buffer.setFont(settings.getFontFamily(), settings.getFontSize());
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

    private static Path pathOf(java.io.File file) {
        return file == null ? null : file.toPath();
    }

    private void registerCommands() {
        registry.register(Command.of("file.new", "File: New", this::onNew));
        registry.register(Command.of("file.open", "File: Open…", this::onOpen));
        registry.register(Command.of("file.save", "File: Save", this::onSave));
        registry.register(Command.of("file.saveAs", "File: Save As…", this::onSaveAs));
        registry.register(Command.of("buffer.close", "Buffer: Close", this::onCloseTab));
        registry.register(Command.of("buffer.next", "Buffer: Next", this::nextBuffer));
        registry.register(Command.of("app.quit", "Application: Quit", this::onQuit));
        registry.register(Command.of("palette.show", "Command Palette", this::onPalette));
        registry.register(Command.of("view.settings", "Settings", this::onSettings));
        registry.register(Command.of("help.about", "About Editora", this::onAbout));
        registry.register(Command.of("tool.project", "Tool Window: Project",
                () -> toolWindows.toggle(projectToolWindow)));
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
                () -> withArea(a -> a.lineStart(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.lineEnd", "Go: Line End",
                () -> withArea(a -> a.lineEnd(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.docStart", "Go: Document Start",
                () -> withArea(a -> a.start(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.docEnd", "Go: Document End",
                () -> withArea(a -> a.end(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.charForward", "Go: Forward Char",
                () -> withArea(a -> a.moveTo(Math.min(a.getLength(), a.getCaretPosition() + 1)))));
        registry.register(Command.of("nav.charBackward", "Go: Backward Char",
                () -> withArea(a -> a.moveTo(Math.max(0, a.getCaretPosition() - 1)))));
        registry.register(Command.of("nav.lineDown", "Go: Next Line",
                () -> withArea(a -> a.nextLine(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.lineUp", "Go: Previous Line",
                () -> withArea(a -> a.prevLine(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.wordForward", "Go: Forward Word",
                () -> withArea(a -> a.moveTo(nextWordBoundary(a.getText(), a.getCaretPosition())))));
        registry.register(Command.of("nav.wordBackward", "Go: Backward Word",
                () -> withArea(a -> a.moveTo(prevWordBoundary(a.getText(), a.getCaretPosition())))));
        registry.register(Command.of("nav.pageDown", "Go: Page Down",
                () -> withArea(a -> a.nextPage(SelectionPolicy.CLEAR))));
        registry.register(Command.of("nav.pageUp", "Go: Page Up",
                () -> withArea(a -> a.prevPage(SelectionPolicy.CLEAR))));
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
