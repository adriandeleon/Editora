package com.editora.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;

import org.fxmisc.richtext.CodeArea;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/** Controls the main window: tabbed editors, menu actions, palette/find overlays, and status bar. */
public class MainController {

    @FXML
    private BorderPane root;
    @FXML
    private TabPane tabPane;
    @FXML
    private Label statusLabel;
    @FXML
    private VBox bottomBox;

    private Stage stage;
    private ConfigManager config;
    private CommandRegistry registry;
    private CommandPalette palette;
    private FindReplaceBar findBar;

    public void init(Stage stage, ConfigManager config, CommandRegistry registry, KeymapManager keymap) {
        this.stage = stage;
        this.config = config;
        this.registry = registry;
        this.palette = new CommandPalette(registry, keymap);
        this.findBar = new FindReplaceBar(this::activeArea, this::setStatus);
        bottomBox.getChildren().add(0, findBar);
        registerCommands();
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
        Tab tab = new Tab();
        tab.setContent(buffer.getArea());
        tab.setUserData(buffer);
        updateTitle(tab, buffer);
        buffer.dirtyProperty().addListener((obs, was, now) -> updateTitle(tab, buffer));
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        buffer.getArea().requestFocus();
    }

    private void updateTitle(Tab tab, EditorBuffer buffer) {
        tab.setText((buffer.isDirty() ? "• " : "") + buffer.getTitle());
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
        if (buffer == null) {
            return;
        }
        if (buffer.getPath() == null) {
            onSaveAs();
            return;
        }
        writeBuffer(buffer, buffer.getPath());
    }

    @FXML
    private void onSaveAs() {
        EditorBuffer buffer = activeBuffer();
        if (buffer == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save As");
        Path file = pathOf(chooser.showSaveDialog(stage));
        if (file == null) {
            return;
        }
        buffer.setPath(file);
        writeBuffer(buffer, file);
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        if (tab != null) {
            updateTitle(tab, buffer);
        }
    }

    private void writeBuffer(EditorBuffer buffer, Path file) {
        try {
            Files.writeString(file, buffer.getContent());
            buffer.markClean();
            setStatus("Saved " + file);
        } catch (IOException e) {
            setStatus("Failed to save: " + e.getMessage());
        }
    }

    @FXML
    private void onCloseTab() {
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        if (tab != null) {
            tabPane.getTabs().remove(tab);
        }
    }

    @FXML
    private void onQuit() {
        Platform.exit();
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
        findBar.show(false);
    }

    @FXML
    private void onReplace() {
        findBar.show(false);
    }

    @FXML
    private void onPalette() {
        palette.show(stage);
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
        registry.register(Command.of("find.show", "Find", () -> findBar.show(false)));
        registry.register(Command.of("find.showBackward", "Find Backward", () -> findBar.show(true)));
        registry.register(Command.of("find.replace", "Replace", this::onReplace));
        registry.register(Command.of("edit.cut", "Edit: Cut", this::onCut));
        registry.register(Command.of("edit.copy", "Edit: Copy", this::onCopy));
        registry.register(Command.of("edit.paste", "Edit: Paste", this::onPaste));
        registry.register(Command.of("edit.undo", "Edit: Undo", this::onUndo));
        registry.register(Command.of("edit.redo", "Edit: Redo", this::onRedo));
        registry.register(Command.of("edit.cancel", "Cancel", this::cancel));
        registry.register(Command.of("nav.lineStart", "Go: Line Start",
                () -> withArea(a -> a.moveTo(a.getCurrentParagraph(), 0))));
        registry.register(Command.of("nav.lineEnd", "Go: Line End",
                () -> withArea(a -> a.moveTo(a.getCurrentParagraph(), a.getParagraphLength(a.getCurrentParagraph())))));
        registry.register(Command.of("nav.docStart", "Go: Document Start",
                () -> withArea(a -> a.moveTo(0))));
        registry.register(Command.of("nav.docEnd", "Go: Document End",
                () -> withArea(a -> a.moveTo(a.getLength()))));
    }
}
