package org.editora;

import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.*;
import org.editora.plugin.PluginManager;
import org.editora.settings.EditorSettings;
import org.editora.settings.SettingsDialog;
import org.editora.ui.FindReplaceDialog;
import org.editora.ui.CodeEditorArea;
import org.editora.ui.CommandPalette;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main JavaFX application for the text editor with plugin support.
 */
public class EditorApplication extends Application {
    
    private static final Logger logger = LoggerFactory.getLogger(EditorApplication.class);
    
    private TabPane tabPane;
    private Stage primaryStage;
    private PluginManager pluginManager;
    private Label statusLabel;
    private int untitledCounter = 1;
    private EditorSettings settings;
    private ToolBar toolBar;
    private VBox topContainer;
    private CommandPalette commandPalette;
    
    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        // Load settings
        settings = EditorSettings.getInstance();

        // Apply theme
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());

        // Create UI components
        BorderPane root = new BorderPane();

        // Tab pane for multiple files
        tabPane = new TabPane();
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> updateStatus());
        
        // Menu bar
        MenuBar menuBar = createMenuBar();

        // Toolbar
        toolBar = createToolBar();

        // Top container (menu + toolbar)
        topContainer = new VBox(menuBar, toolBar);

        // Status bar
        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-padding: 5px;");

        // Layout
        root.setTop(topContainer);
        root.setCenter(tabPane);
        root.setBottom(statusLabel);

        // Initialize plugin manager
        pluginManager = new PluginManager("plugins");
        pluginManager.setTextArea(null); // Will be updated per tab
        pluginManager.setMenuBar(menuBar);
        pluginManager.loadPlugins();

        // Create initial tab
        createNewTab();
        
        // Create scene
        Scene scene = new Scene(root, 1000, 700);

        // Load syntax highlighting CSS
        scene.getStylesheets().add(getClass().getResource("/syntax-highlighting.css").toExternalForm());

        // Initialize command palette
        commandPalette = new CommandPalette(stage);
        initializeCommandPalette();

        // Add keyboard shortcut for command palette (Ctrl+Shift+P)
        scene.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.P) {
                commandPalette.show();
                event.consume();
            }
        });

        // Configure stage
        primaryStage.setTitle("Editora - Text Editor");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> {
            if (!confirmExit()) {
                event.consume(); // Cancel the close
            }
        });
        
        primaryStage.show();
        
        updateStatus();
        logger.info("Editor application started");
    }
    
    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();
        
        // File menu
        Menu fileMenu = new Menu("File");
        
        MenuItem newItem = new MenuItem("New");
        newItem.setGraphic(new FontIcon(MaterialDesignF.FILE_OUTLINE));
        newItem.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN));
        newItem.setOnAction(e -> newFile());

        MenuItem openItem = new MenuItem("Open...");
        openItem.setGraphic(new FontIcon(MaterialDesignF.FOLDER_OPEN_OUTLINE));
        openItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN));
        openItem.setOnAction(e -> openFile());

        MenuItem saveItem = new MenuItem("Save");
        saveItem.setGraphic(new FontIcon(MaterialDesignC.CONTENT_SAVE));
        saveItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN));
        saveItem.setOnAction(e -> saveFile());

        MenuItem saveAsItem = new MenuItem("Save As...");
        saveAsItem.setGraphic(new FontIcon(MaterialDesignC.CONTENT_SAVE_ALL));
        saveAsItem.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        saveAsItem.setOnAction(e -> saveFileAs());

        MenuItem settingsItem = new MenuItem("Settings...");
        settingsItem.setGraphic(new FontIcon(MaterialDesignC.COG));
        settingsItem.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN));
        settingsItem.setOnAction(e -> showSettings());

        MenuItem exitItem = new MenuItem("Exit");
        exitItem.setGraphic(new FontIcon(MaterialDesignE.EXIT_TO_APP));
        exitItem.setOnAction(e -> {
            if (confirmExit()) {
                // Exit is handled in confirmExit()
            }
        });

        fileMenu.getItems().addAll(newItem, openItem, new SeparatorMenuItem(),
            saveItem, saveAsItem, new SeparatorMenuItem(), settingsItem, new SeparatorMenuItem(), exitItem);
        
        // Edit menu
        Menu editMenu = new Menu("Edit");
        
        MenuItem undoItem = new MenuItem("Undo");
        undoItem.setGraphic(new FontIcon(MaterialDesignU.UNDO));
        undoItem.setAccelerator(new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN));
        undoItem.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.undo();
        });

        MenuItem redoItem = new MenuItem("Redo");
        redoItem.setGraphic(new FontIcon(MaterialDesignR.REDO));
        redoItem.setAccelerator(new KeyCodeCombination(KeyCode.Y, KeyCombination.CONTROL_DOWN));
        redoItem.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.redo();
        });

        MenuItem cutItem = new MenuItem("Cut");
        cutItem.setGraphic(new FontIcon(MaterialDesignC.CONTENT_CUT));
        cutItem.setAccelerator(new KeyCodeCombination(KeyCode.X, KeyCombination.CONTROL_DOWN));
        cutItem.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.cut();
        });

        MenuItem copyItem = new MenuItem("Copy");
        copyItem.setGraphic(new FontIcon(MaterialDesignC.CONTENT_COPY));
        copyItem.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN));
        copyItem.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.copy();
        });

        MenuItem pasteItem = new MenuItem("Paste");
        pasteItem.setGraphic(new FontIcon(MaterialDesignC.CONTENT_PASTE));
        pasteItem.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN));
        pasteItem.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.paste();
        });

        MenuItem selectAllItem = new MenuItem("Select All");
        selectAllItem.setGraphic(new FontIcon(MaterialDesignS.SELECT_ALL));
        selectAllItem.setAccelerator(new KeyCodeCombination(KeyCode.A, KeyCombination.CONTROL_DOWN));
        selectAllItem.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.selectAll();
        });

        MenuItem findItem = new MenuItem("Find...");
        findItem.setGraphic(new FontIcon(MaterialDesignM.MAGNIFY));
        findItem.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN));
        findItem.setOnAction(e -> showFindReplace());

        MenuItem replaceItem = new MenuItem("Replace...");
        replaceItem.setGraphic(new FontIcon(MaterialDesignS.SELECT_SEARCH));
        replaceItem.setAccelerator(new KeyCodeCombination(KeyCode.H, KeyCombination.CONTROL_DOWN));
        replaceItem.setOnAction(e -> showFindReplace());

        editMenu.getItems().addAll(undoItem, redoItem, new SeparatorMenuItem(),
            cutItem, copyItem, pasteItem, new SeparatorMenuItem(), selectAllItem,
            new SeparatorMenuItem(), findItem, replaceItem);

        // View menu
        Menu viewMenu = new Menu("View");
        
        MenuItem commandPaletteItem = new MenuItem("Command Palette...");
        commandPaletteItem.setGraphic(new FontIcon(MaterialDesignC.CODE_BRACES));
        commandPaletteItem.setAccelerator(new KeyCodeCombination(KeyCode.P, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
        commandPaletteItem.setOnAction(e -> showCommandPalette());
        
        CheckMenuItem toolbarItem = new CheckMenuItem("Show Toolbar");
        toolbarItem.setSelected(true);
        toolbarItem.setOnAction(e -> toggleToolbar(toolbarItem.isSelected()));
        
        CheckMenuItem lineNumbersItem = new CheckMenuItem("Show Line Numbers");
        lineNumbersItem.setSelected(settings.isShowLineNumbers());
        lineNumbersItem.setOnAction(e -> toggleLineNumbers(lineNumbersItem.isSelected()));
        
        viewMenu.getItems().addAll(commandPaletteItem, new SeparatorMenuItem(), toolbarItem, lineNumbersItem);
        
        // Help menu
        Menu helpMenu = new Menu("Help");
        
        MenuItem aboutItem = new MenuItem("About");
        aboutItem.setOnAction(e -> showAbout());
        
        MenuItem pluginsItem = new MenuItem("Loaded Plugins");
        pluginsItem.setOnAction(e -> showPlugins());
        
        helpMenu.getItems().addAll(aboutItem, pluginsItem);
        
        menuBar.getMenus().addAll(fileMenu, editMenu, viewMenu, helpMenu);
        
        return menuBar;
    }

    private ToolBar createToolBar() {
        ToolBar toolBar = new ToolBar();

        // New file button
        Button newBtn = new Button();
        newBtn.setGraphic(new FontIcon(MaterialDesignF.FILE_OUTLINE));
        newBtn.setTooltip(createTooltip("New (Ctrl+N)"));
        newBtn.setOnAction(e -> newFile());

        // Open file button
        Button openBtn = new Button();
        openBtn.setGraphic(new FontIcon(MaterialDesignF.FOLDER_OPEN_OUTLINE));
        openBtn.setTooltip(createTooltip("Open (Ctrl+O)"));
        openBtn.setOnAction(e -> openFile());

        // Save file button
        Button saveBtn = new Button();
        saveBtn.setGraphic(new FontIcon(MaterialDesignC.CONTENT_SAVE));
        saveBtn.setTooltip(createTooltip("Save (Ctrl+S)"));
        saveBtn.setOnAction(e -> saveFile());

        Separator sep1 = new Separator();

        // Undo button
        Button undoBtn = new Button();
        undoBtn.setGraphic(new FontIcon(MaterialDesignU.UNDO));
        undoBtn.setTooltip(createTooltip("Undo (Ctrl+Z)"));
        undoBtn.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.undo();
        });

        // Redo button
        Button redoBtn = new Button();
        redoBtn.setGraphic(new FontIcon(MaterialDesignR.REDO));
        redoBtn.setTooltip(createTooltip("Redo (Ctrl+Y)"));
        redoBtn.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.redo();
        });

        Separator sep2 = new Separator();

        // Cut button
        Button cutBtn = new Button();
        cutBtn.setGraphic(new FontIcon(MaterialDesignC.CONTENT_CUT));
        cutBtn.setTooltip(createTooltip("Cut (Ctrl+X)"));
        cutBtn.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.cut();
        });

        // Copy button
        Button copyBtn = new Button();
        copyBtn.setGraphic(new FontIcon(MaterialDesignC.CONTENT_COPY));
        copyBtn.setTooltip(createTooltip("Copy (Ctrl+C)"));
        copyBtn.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.copy();
        });

        // Paste button
        Button pasteBtn = new Button();
        pasteBtn.setGraphic(new FontIcon(MaterialDesignC.CONTENT_PASTE));
        pasteBtn.setTooltip(createTooltip("Paste (Ctrl+V)"));
        pasteBtn.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.paste();
        });

        Separator sep3 = new Separator();

        // Word wrap toggle
        ToggleButton wrapBtn = new ToggleButton();
        wrapBtn.setGraphic(new FontIcon(MaterialDesignW.WRAP));
        wrapBtn.setTooltip(createTooltip("Toggle Word Wrap"));
        wrapBtn.setSelected(false);
        wrapBtn.setOnAction(e -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.setWrapText(wrapBtn.isSelected());
        });

        Separator sep4 = new Separator();

        // Command palette button
        Button commandPaletteBtn = new Button();
        commandPaletteBtn.setGraphic(new FontIcon(MaterialDesignC.CODE_BRACES));
        commandPaletteBtn.setTooltip(createTooltip("Command Palette (Ctrl+Shift+P)"));
        commandPaletteBtn.setOnAction(e -> showCommandPalette());

        // Settings button
        Button settingsBtn = new Button();
        settingsBtn.setGraphic(new FontIcon(MaterialDesignC.COG));
        settingsBtn.setTooltip(createTooltip("Settings (Ctrl+,)"));
        settingsBtn.setOnAction(e -> showSettings());

        toolBar.getItems().addAll(
            newBtn, openBtn, saveBtn, sep1,
            undoBtn, redoBtn, sep2,
            cutBtn, copyBtn, pasteBtn, sep3,
            wrapBtn, sep4, commandPaletteBtn, settingsBtn
        );

        return toolBar;
    }

    private Tab createNewTab() {
        CodeEditorArea codeEditorArea = new CodeEditorArea();
        CodeArea codeArea = codeEditorArea.getCodeArea();

        codeArea.setWrapText(settings.isWordWrap());
        codeArea.setStyle(settings.getFontStyle());
        codeArea.textProperty().addListener((obs, oldVal, newVal) -> {
            updateStatus();
            markTabAsModified();
        });

        // Add listener for caret position changes
        codeArea.caretPositionProperty().addListener((obs, oldPos, newPos) -> {
            updateStatus();
        });

        // Show line numbers based on settings
        codeEditorArea.setShowLineNumbers(settings.isShowLineNumbers());

        Tab tab = new Tab("Untitled-" + untitledCounter++);
        tab.setContent(codeEditorArea);
        tab.setClosable(true);

        // Store file reference and state in tab properties
        tab.getProperties().put("file", null);
        tab.getProperties().put("codeArea", codeArea);
        tab.getProperties().put("codeEditorArea", codeEditorArea);
        tab.getProperties().put("modified", false);
        tab.getProperties().put("savedContent", "");

        // Add close request handler to confirm unsaved changes
        tab.setOnCloseRequest(event -> {
            if (isTabModified(tab)) {
                if (!confirmCloseTab(tab)) {
                    event.consume(); // Cancel the close
                }
            }
        });

        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);

        // Note: Plugin manager may need to be updated to work with CodeArea
        // pluginManager.setTextArea(textArea);

        updateStatus();
        logger.info("New tab created: {}", tab.getText());
        return tab;
    }

    private void newFile() {
        createNewTab();
    }

    private CodeArea getCurrentCodeArea() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            return (CodeArea) selectedTab.getProperties().get("codeArea");
        }
        return null;
    }

    // Backward compatibility method - returns CodeArea instead of TextArea
    private CodeArea getCurrentTextArea() {
        return getCurrentCodeArea();
    }

    private File getCurrentFile() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            return (File) selectedTab.getProperties().get("file");
        }
        return null;
    }

    private void setCurrentFile(File file) {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            selectedTab.getProperties().put("file", file);
            selectedTab.setText(file != null ? file.getName() : "Untitled");
        }
    }

    private boolean isTabModified(Tab tab) {
        if (tab == null) return false;
        Boolean modified = (Boolean) tab.getProperties().get("modified");
        return modified != null && modified;
    }

    private void markTabAsModified() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            CodeArea codeArea = (CodeArea) selectedTab.getProperties().get("codeArea");
            String savedContent = (String) selectedTab.getProperties().get("savedContent");

            if (codeArea != null && savedContent != null) {
                boolean modified = !codeArea.getText().equals(savedContent);
                selectedTab.getProperties().put("modified", modified);

                // Update tab title to show modified state
                String baseTitle = getCurrentFile() != null ?
                    getCurrentFile().getName() : selectedTab.getText().replaceFirst("^\\*", "");
                selectedTab.setText(modified ? "*" + baseTitle : baseTitle);
            }
        }
    }

    private void markTabAsSaved() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            CodeArea codeArea = (CodeArea) selectedTab.getProperties().get("codeArea");
            if (codeArea != null) {
                selectedTab.getProperties().put("savedContent", codeArea.getText());
                selectedTab.getProperties().put("modified", false);

                // Update tab title to remove asterisk
                String baseTitle = getCurrentFile() != null ?
                    getCurrentFile().getName() : selectedTab.getText().replaceFirst("^\\*", "");
                selectedTab.setText(baseTitle);
            }
        }
    }

    private boolean confirmCloseTab(Tab tab) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes");
        alert.setHeaderText("Do you want to save changes to " + tab.getText().replaceFirst("^\\*", "") + "?");
        alert.setContentText("Your changes will be lost if you don't save them.");

        ButtonType saveButton = new ButtonType("Save");
        ButtonType dontSaveButton = new ButtonType("Don't Save");
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

        alert.getButtonTypes().setAll(saveButton, dontSaveButton, cancelButton);

        var result = alert.showAndWait();

        if (result.isPresent()) {
            if (result.get() == saveButton) {
                // Select the tab before saving
                tabPane.getSelectionModel().select(tab);
                saveFile();
                return !isTabModified(tab); // Only close if save succeeded
            } else if (result.get() == dontSaveButton) {
                return true; // Close without saving
            }
        }

        return false; // Cancel close
    }
    
    private void openFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open File");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Text Files", "*.txt", "*.java", "*.xml", "*.json", "*.md")
        );
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showOpenDialog(primaryStage);
        if (file != null) {
            try {
                String content = Files.readString(file.toPath());

                // Create new tab for the file
                Tab tab = createNewTab();
                CodeArea codeArea = (CodeArea) tab.getProperties().get("codeArea");
                codeArea.replaceText(0, codeArea.getLength(), content);

                tab.getProperties().put("file", file);
                tab.setText(file.getName());
                tab.getProperties().put("savedContent", content);
                tab.getProperties().put("modified", false);

                updateStatus();
                logger.info("Opened file: {}", file.getAbsolutePath());
            } catch (IOException e) {
                showError("Error opening file", e.getMessage());
                logger.error("Error opening file: {}", file.getAbsolutePath(), e);
            }
        }
    }
    
    private void saveFile() {
        File currentFile = getCurrentFile();
        CodeArea codeArea = getCurrentCodeArea();

        if (codeArea == null) return;

        if (currentFile == null) {
            saveFileAs();
        } else {
            try {
                Files.writeString(currentFile.toPath(), codeArea.getText());
                markTabAsSaved();
                updateStatus();
                logger.info("Saved file: {}", currentFile.getAbsolutePath());
            } catch (IOException e) {
                showError("Error saving file", e.getMessage());
                logger.error("Error saving file: {}", currentFile.getAbsolutePath(), e);
            }
        }
    }
    
    private void saveFileAs() {
        CodeArea codeArea = getCurrentCodeArea();
        if (codeArea == null) return;

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save File As");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Text Files", "*.txt")
        );
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = fileChooser.showSaveDialog(primaryStage);
        if (file != null) {
            try {
                Files.writeString(file.toPath(), codeArea.getText());
                setCurrentFile(file);
                markTabAsSaved();
                updateStatus();
                logger.info("Saved file as: {}", file.getAbsolutePath());
            } catch (IOException e) {
                showError("Error saving file", e.getMessage());
                logger.error("Error saving file: {}", file.getAbsolutePath(), e);
            }
        }
    }
    
    private void updateStatus() {
        CodeArea codeArea = getCurrentCodeArea();
        File currentFile = getCurrentFile();

        if (codeArea == null) {
            statusLabel.setText("No file open");
            return;
        }

        String fileName = currentFile != null ? currentFile.getName() : tabPane.getSelectionModel().getSelectedItem().getText();
        String text = codeArea.getText();

        // Count lines correctly - empty text is 1 line, otherwise count newlines + 1
        int lines = text.isEmpty() ? 1 : (int) text.chars().filter(ch -> ch == '\n').count() + 1;

        // Count characters without newlines
        int chars = (int) text.chars().filter(ch -> ch != '\n' && ch != '\r').count();

        // Get current line and column
        int caretPosition = codeArea.getCaretPosition();
        int currentLine = 1;
        int currentColumn = 1;
        
        for (int i = 0; i < caretPosition && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                currentLine++;
                currentColumn = 1;
            } else {
                currentColumn++;
            }
        }

        statusLabel.setText(String.format("%s | Ln %d, Col %d | Lines: %d | Characters: %d | Plugins: %d",
            fileName, currentLine, currentColumn, lines, chars, pluginManager.getLoadedPlugins().size()));
    }
    
    private void showAbout() {
        String javaVersion = System.getProperty("java.version");
        String javaVendor = System.getProperty("java.vendor");
        String javafxVersion = System.getProperty("javafx.version", "Unknown");
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About Editora");
        alert.setHeaderText("Editora Text Editor");
        alert.setContentText("A simple JavaFX text editor with plugin support.\n\n" +
            "Version: 1.0\n" +
            "Author: Adrian De Leon\n\n" +
            "JDK Version: " + javaVersion + " (" + javaVendor + ")\n" +
            "JavaFX Version: " + javafxVersion);
        alert.getDialogPane().setMinWidth(450);
        alert.showAndWait();
    }
    
    private void showPlugins() {
        StringBuilder pluginInfo = new StringBuilder();
        var plugins = pluginManager.getLoadedPlugins();
        
        if (plugins.isEmpty()) {
            pluginInfo.append("No plugins loaded.\n\n");
            pluginInfo.append("Place plugin JAR files in the 'plugins' directory.");
        } else {
            pluginInfo.append("Loaded Plugins:\n\n");
            for (var plugin : plugins) {
                pluginInfo.append(String.format("• %s v%s\n  %s\n\n", 
                    plugin.getName(), plugin.getVersion(), plugin.getDescription()));
            }
        }
        
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Loaded Plugins");
        alert.setHeaderText("Plugin Information");
        alert.setContentText(pluginInfo.toString());
        alert.getDialogPane().setMinWidth(500);
        alert.showAndWait();
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showSettings() {
        SettingsDialog dialog = new SettingsDialog(this::applySettings);
        dialog.showAndWait();
    }

    private void showFindReplace() {
        CodeArea codeArea = getCurrentCodeArea();
        if (codeArea == null) {
            showError("No File Open", "Please open a file first.");
            return;
        }

        FindReplaceDialog dialog = new FindReplaceDialog(codeArea);
        dialog.openAndFocus();
    }

    private void showCommandPalette() {
        commandPalette.show();
    }

    private void applySettings(EditorSettings settings) {
        // Apply settings to all open tabs
        for (Tab tab : tabPane.getTabs()) {
            CodeArea codeArea = (CodeArea) tab.getProperties().get("codeArea");
            CodeEditorArea codeEditorArea = (CodeEditorArea) tab.getProperties().get("codeEditorArea");

            if (codeArea != null) {
                codeArea.setStyle(settings.getFontStyle());
                codeArea.setWrapText(settings.isWordWrap());
            }

            if (codeEditorArea != null) {
                codeEditorArea.setShowLineNumbers(settings.isShowLineNumbers());
            }
        }

        logger.info("Applied settings to all tabs");
    }

    private boolean confirmExit() {
        // Check if any tabs have unsaved changes
        List<Tab> unsavedTabs = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            if (isTabModified(tab)) {
                unsavedTabs.add(tab);
            }
        }

        // If there are unsaved changes, ask for confirmation
        if (!unsavedTabs.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Unsaved Changes");
            alert.setHeaderText("You have " + unsavedTabs.size() + " file(s) with unsaved changes.");
            alert.setContentText("Do you want to exit without saving?");

            ButtonType saveAllButton = new ButtonType("Save All");
            ButtonType exitButton = new ButtonType("Exit Without Saving");
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            alert.getButtonTypes().setAll(saveAllButton, exitButton, cancelButton);

            var result = alert.showAndWait();

            if (result.isPresent()) {
                if (result.get() == saveAllButton) {
                    // Save all modified tabs
                    for (Tab tab : unsavedTabs) {
                        tabPane.getSelectionModel().select(tab);
                        saveFile();
                        // Check if still modified (user may have cancelled save)
                        if (isTabModified(tab)) {
                            return false; // Cancel exit if save was cancelled
                        }
                    }
                    performExit();
                    return true;
                } else if (result.get() == exitButton) {
                    performExit();
                    return true;
                }
            }
            return false; // Cancel exit
        } else {
            // No unsaved changes, just confirm exit
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Exit");
            alert.setHeaderText("Are you sure you want to exit?");
            alert.setContentText("All open files will be closed.");

            ButtonType exitButton = new ButtonType("Exit");
            ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

            alert.getButtonTypes().setAll(exitButton, cancelButton);

            var result = alert.showAndWait();

            if (result.isPresent() && result.get() == exitButton) {
                performExit();
                return true;
            }
            return false;
        }
    }

    private void performExit() {
        pluginManager.shutdownPlugins();
        Platform.exit();
    }

    private Tooltip createTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(javafx.util.Duration.millis(300));
        tooltip.setHideDelay(javafx.util.Duration.seconds(10));
        return tooltip;
    }

    private void toggleLineNumbers(boolean show) {
        // Update settings
        settings.setShowLineNumbers(show);
        
        // Apply to all open tabs
        for (Tab tab : tabPane.getTabs()) {
            CodeEditorArea codeEditorArea = (CodeEditorArea) tab.getProperties().get("codeEditorArea");
            if (codeEditorArea != null) {
                codeEditorArea.setShowLineNumbers(show);
            }
        }
        
        logger.info("Line numbers " + (show ? "enabled" : "disabled"));
    }

    private void toggleToolbar(boolean show) {
        if (show) {
            if (!topContainer.getChildren().contains(toolBar)) {
                topContainer.getChildren().add(1, toolBar);
            }
        } else {
            topContainer.getChildren().remove(toolBar);
        }
        
        logger.info("Toolbar " + (show ? "shown" : "hidden"));
    }

    private void initializeCommandPalette() {
        // File commands
        commandPalette.addCommand("New File", "Create a new file (Ctrl+N)", this::newFile);
        commandPalette.addCommand("Open File", "Open an existing file (Ctrl+O)", this::openFile);
        commandPalette.addCommand("Save", "Save the current file (Ctrl+S)", this::saveFile);
        commandPalette.addCommand("Save As", "Save the current file with a new name (Ctrl+Shift+S)", this::saveFileAs);
        commandPalette.addCommand("Settings", "Open settings dialog (Ctrl+,)", this::showSettings);
        
        // Edit commands
        commandPalette.addCommand("Undo", "Undo the last action (Ctrl+Z)", () -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.undo();
        });
        commandPalette.addCommand("Redo", "Redo the last undone action (Ctrl+Y)", () -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.redo();
        });
        commandPalette.addCommand("Cut", "Cut selected text (Ctrl+X)", () -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.cut();
        });
        commandPalette.addCommand("Copy", "Copy selected text (Ctrl+C)", () -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.copy();
        });
        commandPalette.addCommand("Paste", "Paste from clipboard (Ctrl+V)", () -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.paste();
        });
        commandPalette.addCommand("Select All", "Select all text (Ctrl+A)", () -> {
            CodeArea ca = getCurrentCodeArea();
            if (ca != null) ca.selectAll();
        });
        commandPalette.addCommand("Find", "Open find dialog (Ctrl+F)", this::showFindReplace);
        commandPalette.addCommand("Replace", "Open replace dialog (Ctrl+H)", this::showFindReplace);
        
        // View commands
        commandPalette.addCommand("Toggle Toolbar", "Show or hide the toolbar", () -> {
            boolean isVisible = topContainer.getChildren().contains(toolBar);
            toggleToolbar(!isVisible);
        });
        commandPalette.addCommand("Toggle Line Numbers", "Show or hide line numbers", () -> {
            boolean isVisible = settings.isShowLineNumbers();
            toggleLineNumbers(!isVisible);
        });
        
        // Help commands
        commandPalette.addCommand("About", "Show about dialog", this::showAbout);
        commandPalette.addCommand("Loaded Plugins", "Show loaded plugins information", this::showPlugins);
        
        logger.info("Command palette initialized with {} commands", commandPalette.getCommandCount());
    }

    @Override
    public void stop() {
        pluginManager.shutdownPlugins();
        logger.info("Editor application stopped");
    }
}
