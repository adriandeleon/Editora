package org.editora.settings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Settings/Preferences dialog window similar to IntelliJ IDEA.
 * Provides a tree view on the left for categories and settings panels on the right.
 */
public class SettingsDialog extends Stage {
    
    private static final Logger logger = LoggerFactory.getLogger(SettingsDialog.class);
    
    private final EditorSettings settings;
    private final TreeView<String> categoryTree;
    private final StackPane contentPane;
    private final Consumer<EditorSettings> onApply;
    private final TextField searchField;

    // Temporary settings (not saved until Apply/OK)
    private String tempFontFamily;
    private int tempFontSize;
    private boolean tempWordWrap;
    private int tempTabSize;
    private boolean tempShowLineNumbers;

    public SettingsDialog(Consumer<EditorSettings> onApply) {
        this.settings = EditorSettings.getInstance();
        this.onApply = onApply;

        // Load current settings into temp variables
        this.tempFontFamily = settings.getFontFamily();
        this.tempFontSize = settings.getFontSize();
        this.tempWordWrap = settings.isWordWrap();
        this.tempTabSize = settings.getTabSize();
        this.tempShowLineNumbers = settings.isShowLineNumbers();
        
        setTitle("Settings");
        initModality(Modality.APPLICATION_MODAL);
        setWidth(900);
        setHeight(600);
        setResizable(true);

        // Main layout
        BorderPane root = new BorderPane();

        // Search bar at top
        searchField = new TextField();
        searchField.setPromptText("Search settings...");
        searchField.setPrefHeight(35);
        FontIcon searchIcon = new FontIcon(MaterialDesignM.MAGNIFY);
        searchIcon.setIconSize(16);
        searchField.setStyle("-fx-font-size: 13px;");

        HBox searchBar = new HBox(10);
        searchBar.setPadding(new Insets(10));
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.getChildren().addAll(searchIcon, searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // Left side - Category tree
        categoryTree = createCategoryTree();
        categoryTree.setPrefWidth(200);
        categoryTree.setMinWidth(180);
        categoryTree.setMaxWidth(220);

        // Right side - Content area
        contentPane = new StackPane();
        contentPane.setPadding(new Insets(20));

        // Split pane
        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(categoryTree, contentPane);
        splitPane.setDividerPositions(0.2);

        // Bottom buttons
        HBox buttonBar = createButtonBar();

        root.setTop(searchBar);
        root.setCenter(splitPane);
        root.setBottom(buttonBar);

        Scene scene = new Scene(root);
        setScene(scene);

        // Setup search functionality
        setupSearch();

        // Select first item by default
        categoryTree.getSelectionModel().select(categoryTree.getRoot().getChildren().get(0));
    }
    
    private TreeView<String> createCategoryTree() {
        TreeItem<String> root = new TreeItem<>("Settings");
        root.setExpanded(true);
        
        // Editor category
        TreeItem<String> editorCategory = new TreeItem<>("Editor");
        editorCategory.setExpanded(true);
        
        TreeItem<String> fontItem = new TreeItem<>("Font");
        TreeItem<String> generalItem = new TreeItem<>("General");
        
        editorCategory.getChildren().addAll(fontItem, generalItem);
        
        // Appearance category
        TreeItem<String> appearanceCategory = new TreeItem<>("Appearance");
        
        root.getChildren().addAll(editorCategory, appearanceCategory);
        
        TreeView<String> tree = new TreeView<>(root);
        tree.setShowRoot(false);
        
        // Handle selection changes
        tree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.isLeaf()) {
                showSettingsPanel(newVal.getValue());
            }
        });
        
        return tree;
    }

    private void setupSearch() {
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                // Show all items
                showAllTreeItems(categoryTree.getRoot(), true);
                categoryTree.getRoot().getChildren().forEach(item -> item.setExpanded(true));
            } else {
                // Filter tree items
                filterTree(newVal.toLowerCase());
            }
        });
    }

    private void filterTree(String searchText) {
        // First, show all items
        showAllTreeItems(categoryTree.getRoot(), true);

        // Then hide non-matching items
        for (TreeItem<String> category : categoryTree.getRoot().getChildren()) {
            boolean categoryMatches = category.getValue().toLowerCase().contains(searchText);
            boolean hasMatchingChildren = false;

            // Check children
            for (TreeItem<String> item : category.getChildren()) {
                boolean matches = item.getValue().toLowerCase().contains(searchText);
                if (matches) {
                    hasMatchingChildren = true;
                }
            }

            // Show category if it matches or has matching children
            if (categoryMatches || hasMatchingChildren) {
                category.setExpanded(true);
            } else {
                category.setExpanded(false);
            }
        }
    }

    private void showAllTreeItems(TreeItem<String> item, boolean visible) {
        if (item != null) {
            // TreeView doesn't support hiding items directly, but we can collapse/expand
            if (!item.isLeaf()) {
                for (TreeItem<String> child : item.getChildren()) {
                    showAllTreeItems(child, visible);
                }
            }
        }
    }

    private void showSettingsPanel(String category) {
        contentPane.getChildren().clear();
        
        switch (category) {
            case "Font":
                contentPane.getChildren().add(createFontSettingsPanel());
                break;
            case "General":
                contentPane.getChildren().add(createGeneralSettingsPanel());
                break;
            case "Appearance":
                contentPane.getChildren().add(createAppearanceSettingsPanel());
                break;
            default:
                contentPane.getChildren().add(createPlaceholderPanel(category));
        }
    }
    
    private VBox createFontSettingsPanel() {
        VBox panel = new VBox(15);
        panel.setAlignment(Pos.TOP_LEFT);
        
        Label title = new Label("Font");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        
        Separator separator = new Separator();
        
        // Font family selection
        Label fontFamilyLabel = new Label("Font family:");
        ComboBox<String> fontFamilyCombo = new ComboBox<>();
        
        // Get available monospace fonts
        List<String> monospaceFonts = new ArrayList<>();
        monospaceFonts.add("Consolas");
        monospaceFonts.add("Monaco");
        monospaceFonts.add("Courier New");
        monospaceFonts.add("Menlo");
        monospaceFonts.add("DejaVu Sans Mono");
        monospaceFonts.add("Ubuntu Mono");
        monospaceFonts.add("Fira Code");
        monospaceFonts.add("JetBrains Mono");
        monospaceFonts.add("Source Code Pro");
        
        // Add system fonts that are available
        for (String fontName : Font.getFamilies()) {
            if (!monospaceFonts.contains(fontName) && isMonospaceFont(fontName)) {
                monospaceFonts.add(fontName);
            }
        }
        
        fontFamilyCombo.getItems().addAll(monospaceFonts);
        fontFamilyCombo.setValue(tempFontFamily);
        fontFamilyCombo.setPrefWidth(300);
        fontFamilyCombo.setOnAction(e -> tempFontFamily = fontFamilyCombo.getValue());
        
        // Font size selection
        Label fontSizeLabel = new Label("Font size:");
        Spinner<Integer> fontSizeSpinner = new Spinner<>(8, 48, tempFontSize);
        fontSizeSpinner.setEditable(true);
        fontSizeSpinner.setPrefWidth(100);
        fontSizeSpinner.valueProperty().addListener((obs, oldVal, newVal) -> tempFontSize = newVal);
        
        // Preview
        Label previewLabel = new Label("Preview:");
        TextArea previewArea = new TextArea();
        previewArea.setText("The quick brown fox jumps over the lazy dog\n0123456789\n</>{}[]();");
        previewArea.setEditable(false);
        previewArea.setPrefHeight(100);
        previewArea.setWrapText(false);
        
        // Update preview when settings change
        Runnable updatePreview = () -> {
            String style = String.format("-fx-font-family: '%s', monospace; -fx-font-size: %dpx;",
                tempFontFamily, tempFontSize);
            previewArea.setStyle(style);
        };
        
        fontFamilyCombo.setOnAction(e -> {
            tempFontFamily = fontFamilyCombo.getValue();
            updatePreview.run();
        });
        
        fontSizeSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            tempFontSize = newVal;
            updatePreview.run();
        });
        
        updatePreview.run(); // Initial preview
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(fontFamilyLabel, 0, 0);
        grid.add(fontFamilyCombo, 1, 0);
        grid.add(fontSizeLabel, 0, 1);
        grid.add(fontSizeSpinner, 1, 1);
        
        panel.getChildren().addAll(title, separator, grid, previewLabel, previewArea);
        
        return panel;
    }
    
    private VBox createGeneralSettingsPanel() {
        VBox panel = new VBox(15);
        panel.setAlignment(Pos.TOP_LEFT);
        
        Label title = new Label("General Editor Settings");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        
        Separator separator = new Separator();
        
        // Word wrap
        CheckBox wordWrapCheck = new CheckBox("Enable word wrap");
        wordWrapCheck.setSelected(tempWordWrap);
        wordWrapCheck.setOnAction(e -> tempWordWrap = wordWrapCheck.isSelected());

        // Show line numbers
        CheckBox lineNumbersCheck = new CheckBox("Show line numbers");
        lineNumbersCheck.setSelected(tempShowLineNumbers);
        lineNumbersCheck.setOnAction(e -> tempShowLineNumbers = lineNumbersCheck.isSelected());

        // Tab size
        Label tabSizeLabel = new Label("Tab size:");
        Spinner<Integer> tabSizeSpinner = new Spinner<>(1, 16, tempTabSize);
        tabSizeSpinner.setEditable(true);
        tabSizeSpinner.setPrefWidth(100);
        tabSizeSpinner.valueProperty().addListener((obs, oldVal, newVal) -> tempTabSize = newVal);

        HBox tabSizeBox = new HBox(10, tabSizeLabel, tabSizeSpinner);
        tabSizeBox.setAlignment(Pos.CENTER_LEFT);

        panel.getChildren().addAll(title, separator, wordWrapCheck, lineNumbersCheck, tabSizeBox);
        
        return panel;
    }
    
    private VBox createAppearanceSettingsPanel() {
        VBox panel = new VBox(15);
        panel.setAlignment(Pos.TOP_LEFT);
        
        Label title = new Label("Appearance");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        
        Separator separator = new Separator();
        
        Label info = new Label("Additional appearance settings will be available here.");
        info.setStyle("-fx-text-fill: gray;");
        
        panel.getChildren().addAll(title, separator, info);
        
        return panel;
    }
    
    private VBox createPlaceholderPanel(String category) {
        VBox panel = new VBox(15);
        panel.setAlignment(Pos.CENTER);
        
        Label label = new Label("Settings for " + category);
        label.setStyle("-fx-font-size: 16px;");
        
        panel.getChildren().add(label);
        
        return panel;
    }
    
    private HBox createButtonBar() {
        HBox buttonBar = new HBox(10);
        buttonBar.setPadding(new Insets(10));
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");
        
        Button okButton = new Button("OK");
        okButton.setPrefWidth(80);
        okButton.setOnAction(e -> {
            applySettings();
            close();
        });
        
        Button cancelButton = new Button("Cancel");
        cancelButton.setPrefWidth(80);
        cancelButton.setOnAction(e -> close());
        
        Button applyButton = new Button("Apply");
        applyButton.setPrefWidth(80);
        applyButton.setOnAction(e -> applySettings());
        
        buttonBar.getChildren().addAll(okButton, cancelButton, applyButton);
        
        return buttonBar;
    }
    
    private void applySettings() {
        // Save temp settings to actual settings
        settings.setFontFamily(tempFontFamily);
        settings.setFontSize(tempFontSize);
        settings.setWordWrap(tempWordWrap);
        settings.setTabSize(tempTabSize);
        settings.setShowLineNumbers(tempShowLineNumbers);
        settings.saveSettings();

        // Notify callback
        if (onApply != null) {
            onApply.accept(settings);
        }

        logger.info("Settings applied: font={} {}px, wordWrap={}, tabSize={}, lineNumbers={}",
            tempFontFamily, tempFontSize, tempWordWrap, tempTabSize, tempShowLineNumbers);
    }
    
    private boolean isMonospaceFont(String fontName) {
        // Simple heuristic - check if font name contains keywords
        String lower = fontName.toLowerCase();
        return lower.contains("mono") || lower.contains("code") || 
               lower.contains("console") || lower.contains("courier");
    }
}
