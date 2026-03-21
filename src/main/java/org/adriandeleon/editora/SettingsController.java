package org.adriandeleon.editora;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.application.Platform;
import org.adriandeleon.editora.settings.CommandPaletteShortcut;
import org.adriandeleon.editora.settings.EditorSettings;
import org.adriandeleon.editora.settings.ReadOnlyOpenRules;
import org.adriandeleon.editora.settings.ToolWindowSide;
import org.adriandeleon.editora.languages.LanguageAnalysis;
import org.adriandeleon.editora.languages.LanguagePreviewSpec;
import org.adriandeleon.editora.theme.EditorTheme;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.scene.layout.StackPane;

public class SettingsController {
    @FXML
    private VBox settingsRoot;

    @FXML
    private TabPane settingsTabPane;

    @FXML
    private ComboBox<EditorTheme> themeComboBox;

    @FXML
    private Label themeHelpLabel;

    @FXML
    private CheckBox wrapTextCheckBox;

    @FXML
    private CheckBox diagnosticsCheckBox;

    @FXML
    private CheckBox miniMapVisibleCheckBox;

    @FXML
    private CheckBox searchBarVisibleCheckBox;

    @FXML
    private CheckBox toolDockVisibleCheckBox;

    @FXML
    private CheckBox bookmarkWindowVisibleCheckBox;

    @FXML
    private CheckBox breadcrumbBarVisibleCheckBox;

    @FXML
    private CheckBox readOnlyOpenEnabledCheckBox;

    @FXML
    private TextField readOnlyOpenPatternField;

    @FXML
    private Button addReadOnlyOpenPatternButton;

    @FXML
    private Button removeReadOnlyOpenPatternButton;

    @FXML
    private TableView<String> readOnlyOpenPatternsTable;

    @FXML
    private TableColumn<String, String> readOnlyOpenPatternColumn;

    @FXML
    private Label readOnlyOpenHelpLabel;

    @FXML
    private ComboBox<ToolWindowSide> toolDockSideComboBox;

    @FXML
    private ComboBox<String> editorFontFamilyComboBox;

    @FXML
    private TextField editorFontSizeField;

    @FXML
    private Label editorFontHelpLabel;

    @FXML
    private TextField commandPaletteShortcutField;

    @FXML
    private Label commandPaletteShortcutHelpLabel;

    @FXML
    private Label pluginsDirectoryLabel;

    @FXML
    private Label persistenceDirectoryLabel;

    @FXML
    private Label persistenceFormatLabel;

    @FXML
    private Button openPersistenceFolderButton;

    @FXML
    private Label availableLanguagesLabel;

    @FXML
    private Label editorControlLabel;

    @FXML
    private Label keyboardShortcutsLabel;

    @FXML
    ComboBox<LanguagePreviewSpec> syntaxPreviewLanguageComboBox;

    @FXML
    Label syntaxPreviewHelpLabel;

    @FXML
    StackPane syntaxPreviewHost;

    @FXML
    Button cancelButton;

    @FXML
    Button applyButton;

    @FXML
    private VBox keyboardShortcutsCard;

    @FXML
    private Tab keyboardShortcutsTab;

    private Consumer<EditorSettings> applyHandler = SettingsController::ignoreSettings;
    private Consumer<EditorTheme> previewThemeHandler = SettingsController::ignoreTheme;
    private Function<LanguagePreviewSpec, LanguageAnalysis> syntaxPreviewHandler = preview -> LanguageAnalysis.plainText(preview == null ? "" : preview.sampleText());
    private Runnable reloadSyntaxBundlesHandler = () -> {
    };
    private Runnable openPersistenceFolderHandler = () -> {
    };
    private Runnable closeHandler = () -> {
    };
    private String commandPaletteShortcut = CommandPaletteShortcut.DEFAULT_VALUE;
    private boolean suppressThemePreview;
    private CodeArea syntaxPreviewArea;
    private final ObservableList<String> readOnlyOpenPatternItems = FXCollections.observableArrayList();

    @FXML
    private void initialize() {
        requireInjectedControls();
        themeComboBox.setItems(FXCollections.observableArrayList(EditorTheme.values()));
        themeComboBox.setCellFactory(ignored -> createThemeCell());
        themeComboBox.setButtonCell(createThemeCell());
        toolDockSideComboBox.setItems(FXCollections.observableArrayList(ToolWindowSide.values()));
        toolDockSideComboBox.setCellFactory(ignored -> createToolWindowSideCell());
        toolDockSideComboBox.setButtonCell(createToolWindowSideCell());
        themeComboBox.valueProperty().addListener(onValueChange(current -> {
            if (suppressThemePreview || current == null) {
                return;
            }
            previewThemeHandler.accept(current);
            updateThemeHelpLabel(current);
        }));
        editorFontFamilyComboBox.setItems(FXCollections.observableArrayList(Font.getFamilies().stream()
                .sorted(Comparator.naturalOrder())
                .toList()));
        editorFontFamilyComboBox.setEditable(true);
        editorControlLabel.setText("RichTextFX CodeArea inside VirtualizedScrollPane");
        configureSyntaxPreview();
        cancelButton.setCancelButton(true);
        applyButton.setDefaultButton(true);
        commandPaletteShortcutField.setEditable(false);
        commandPaletteShortcutField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleShortcutCapture);
        configureReadOnlyOpenPatternTable();
        settingsRoot.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
    }

    void configure(EditorSettings settings,
                   Path pluginsDirectory,
                   Path persistenceDirectory,
                   String persistenceFormat,
                   String availableLanguages,
                   List<LanguagePreviewSpec> syntaxPreviews,
                   Function<LanguagePreviewSpec, LanguageAnalysis> syntaxPreviewHandler,
                   Runnable reloadSyntaxBundlesHandler,
                   Runnable openPersistenceFolderHandler,
                   Consumer<EditorTheme> previewThemeHandler,
                   Consumer<EditorSettings> applyHandler,
                   Runnable closeHandler) {
        this.syntaxPreviewHandler = Objects.requireNonNull(syntaxPreviewHandler);
        this.reloadSyntaxBundlesHandler = Objects.requireNonNull(reloadSyntaxBundlesHandler);
        this.openPersistenceFolderHandler = Objects.requireNonNull(openPersistenceFolderHandler);
        this.previewThemeHandler = Objects.requireNonNull(previewThemeHandler);
        this.applyHandler = Objects.requireNonNull(applyHandler);
        this.closeHandler = Objects.requireNonNull(closeHandler);
        suppressThemePreview = true;
        try {
            themeComboBox.setValue(settings.theme());
        } finally {
            suppressThemePreview = false;
        }
        updateThemeHelpLabel(settings.theme());
        wrapTextCheckBox.setSelected(settings.wrapText());
        diagnosticsCheckBox.setSelected(settings.diagnosticsEnabled());
        miniMapVisibleCheckBox.setSelected(settings.miniMapVisible());
        searchBarVisibleCheckBox.setSelected(settings.searchBarVisible());
        toolDockVisibleCheckBox.setSelected(settings.toolDockVisible());
        bookmarkWindowVisibleCheckBox.setSelected(settings.bookmarkWindowVisible());
        breadcrumbBarVisibleCheckBox.setSelected(settings.breadcrumbBarVisible());
        readOnlyOpenEnabledCheckBox.setSelected(settings.readOnlyOpenEnabled());
        readOnlyOpenPatternItems.setAll(settings.readOnlyOpenPatterns());
        readOnlyOpenPatternField.clear();
        updateReadOnlyOpenPatternControls();
        showReadOnlyOpenInstruction();
        toolDockSideComboBox.setValue(settings.toolDockSide());
        editorFontFamilyComboBox.setValue(settings.editorFontFamily());
        editorFontSizeField.setText(Integer.toString(settings.editorFontSize()));
        showFontInstruction();
        commandPaletteShortcut = settings.commandPaletteShortcut();
        updateShortcutField();
        showShortcutInstruction();
        pluginsDirectoryLabel.setText(pluginsDirectory.toAbsolutePath().toString());
        persistenceDirectoryLabel.setText(persistenceDirectory.toAbsolutePath().toString());
        persistenceFormatLabel.setText(persistenceFormat);
        availableLanguagesLabel.setText(availableLanguages);
        keyboardShortcutsLabel.setText(buildKeyboardShortcutsReference(commandPaletteShortcut));
        updateSyntaxPreviewOptions(syntaxPreviews, availableLanguages);
        syntaxPreviewArea.setStyle(String.format("-fx-font-family: \"%s\"; -fx-font-size: %dpx;",
                settings.editorFontFamily().replace("\"", "\\\""),
                Math.max(10, settings.editorFontSize() - 1)));
    }

    @FXML
    private void onApply() {
        if (CommandPaletteShortcut.isReserved(commandPaletteShortcut)) {
            showShortcutError("That shortcut is already used by another editor action. Choose a different one.");
            commandPaletteShortcutField.requestFocus();
            return;
        }

        String fontFamily = normalizedFontFamily();
        if (fontFamily == null) {
            showFontError("Choose a font family for the editor.");
            editorFontFamilyComboBox.requestFocus();
            return;
        }

        Integer fontSize = parsedFontSize();
        if (fontSize == null) {
            showFontError("Enter a font size between 8 and 72.");
            editorFontSizeField.requestFocus();
            editorFontSizeField.selectAll();
            return;
        }

        showFontInstruction();

        applyHandler.accept(new EditorSettings(
                themeComboBox.getValue(),
                wrapTextCheckBox.isSelected(),
                diagnosticsCheckBox.isSelected(),
                miniMapVisibleCheckBox.isSelected(),
                searchBarVisibleCheckBox.isSelected(),
                toolDockVisibleCheckBox.isSelected(),
                bookmarkWindowVisibleCheckBox.isSelected(),
                breadcrumbBarVisibleCheckBox.isSelected(),
                toolDockSideComboBox.getValue() == null ? EditorSettings.DEFAULT_TOOL_DOCK_SIDE : toolDockSideComboBox.getValue(),
                commandPaletteShortcut,
                fontFamily,
                fontSize,
                readOnlyOpenEnabledCheckBox.isSelected(),
                List.copyOf(readOnlyOpenPatternItems)
        ));
    }

    @FXML
    private void onAddReadOnlyOpenPattern() {
        List<String> candidates = ReadOnlyOpenRules.parsePatternText(readOnlyOpenPatternField.getText());
        if (candidates.isEmpty()) {
            showReadOnlyOpenError("Enter a file name or glob pattern to add.");
            readOnlyOpenPatternField.requestFocus();
            return;
        }

        int addedCount = 0;
        for (String candidate : candidates) {
            if (!readOnlyOpenPatternItems.contains(candidate)) {
                readOnlyOpenPatternItems.add(candidate);
                addedCount++;
            }
        }

        if (addedCount == 0) {
            showReadOnlyOpenError("Those patterns are already in the list.");
            readOnlyOpenPatternField.requestFocus();
            readOnlyOpenPatternField.selectAll();
            return;
        }

        readOnlyOpenPatternField.clear();
        readOnlyOpenPatternsTable.getSelectionModel().clearSelection();
        int firstAddedIndex = Math.max(0, readOnlyOpenPatternItems.size() - addedCount);
        readOnlyOpenPatternsTable.getSelectionModel().select(firstAddedIndex);
        readOnlyOpenPatternsTable.scrollTo(firstAddedIndex);
        showReadOnlyOpenInstruction();
    }

    @FXML
    private void onRemoveReadOnlyOpenPattern() {
        List<String> selectedPatterns = List.copyOf(readOnlyOpenPatternsTable.getSelectionModel().getSelectedItems());
        if (selectedPatterns.isEmpty()) {
            showReadOnlyOpenError("Select one or more patterns to remove.");
            readOnlyOpenPatternsTable.requestFocus();
            return;
        }

        readOnlyOpenPatternItems.removeAll(selectedPatterns);
        readOnlyOpenPatternsTable.getSelectionModel().clearSelection();
        updateReadOnlyOpenPatternControls();
        showReadOnlyOpenInstruction();
    }

    @FXML
    private void onResetCommandPaletteShortcut() {
        commandPaletteShortcut = CommandPaletteShortcut.DEFAULT_VALUE;
        updateShortcutField();
        showShortcutInstruction();
    }

    @FXML
    private void onClose() {
        closeHandler.run();
    }

    @FXML
    void onReloadSyntaxBundles() {
        reloadSyntaxBundlesHandler.run();
    }

    @FXML
    void onOpenPersistenceFolder() {
        openPersistenceFolderHandler.run();
    }

    void focusPrimaryControl() {
        Platform.runLater(() -> {
            if (settingsTabPane != null) {
                settingsTabPane.getSelectionModel().selectFirst();
            }
            themeComboBox.requestFocus();
        });
    }

    void showKeyboardShortcutsSection() {
        Platform.runLater(() -> {
            if (settingsTabPane != null && keyboardShortcutsTab != null) {
                settingsTabPane.getSelectionModel().select(keyboardShortcutsTab);
            }
            if (keyboardShortcutsCard != null) {
                keyboardShortcutsCard.requestFocus();
            }
        });
    }

    void updateSyntaxPreviewOptions(List<LanguagePreviewSpec> previews, String availableLanguages) {
        ComboBox<LanguagePreviewSpec> previewComboBox = Objects.requireNonNull(syntaxPreviewLanguageComboBox);
        LanguagePreviewSpec previousSelection = previewComboBox.getValue();
        List<LanguagePreviewSpec> normalizedPreviews = previews == null ? List.of() : List.copyOf(previews);
        previewComboBox.setItems(FXCollections.observableArrayList(normalizedPreviews));
        if (availableLanguagesLabel != null && availableLanguages != null) {
            availableLanguagesLabel.setText(availableLanguages);
        }

        LanguagePreviewSpec restoredSelection = previousSelection == null
                ? null
                : normalizedPreviews.stream()
                .filter(preview -> preview.displayName().equals(previousSelection.displayName()))
                .findFirst()
                .orElse(null);
        LanguagePreviewSpec selection = restoredSelection != null
                ? restoredSelection
                : normalizedPreviews.isEmpty() ? null : normalizedPreviews.getFirst();
        previewComboBox.setValue(selection);
        refreshSyntaxPreview(selection);
    }

    private ListCell<EditorTheme> createThemeCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(EditorTheme item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getSettingsDisplayName());
            }
        };
    }

    private ListCell<ToolWindowSide> createToolWindowSideCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ToolWindowSide item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        };
    }

    private void configureSyntaxPreview() {
        syntaxPreviewArea = new CodeArea();
        syntaxPreviewArea.getStyleClass().addAll("editor-code-area", "settings-syntax-preview-area");
        syntaxPreviewArea.setEditable(false);
        syntaxPreviewArea.setWrapText(false);
        syntaxPreviewArea.setParagraphGraphicFactory(LineNumberFactory.get(syntaxPreviewArea));
        syntaxPreviewHost.getChildren().setAll(new VirtualizedScrollPane<>(syntaxPreviewArea));
        syntaxPreviewLanguageComboBox.setCellFactory(ignored -> createSyntaxPreviewCell());
        syntaxPreviewLanguageComboBox.setButtonCell(createSyntaxPreviewCell());
        syntaxPreviewLanguageComboBox.valueProperty().addListener(onValueChange(this::refreshSyntaxPreview));
    }

    private void configureReadOnlyOpenPatternTable() {
        readOnlyOpenPatternsTable.setItems(readOnlyOpenPatternItems);
        readOnlyOpenPatternsTable.setEditable(true);
        readOnlyOpenPatternsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        readOnlyOpenPatternsTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        readOnlyOpenPatternsTable.setPlaceholder(new Label("No patterns configured."));
        readOnlyOpenPatternColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue()));
        readOnlyOpenPatternColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        readOnlyOpenPatternColumn.setOnEditCommit(event -> commitReadOnlyOpenPatternEdit(event.getTablePosition().getRow(), event.getNewValue()));
        readOnlyOpenPatternsTable.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<String>) change -> updateReadOnlyOpenPatternControls());
        readOnlyOpenPatternsTable.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                onRemoveReadOnlyOpenPattern();
                event.consume();
            }
        });
        readOnlyOpenPatternField.setOnAction(event -> onAddReadOnlyOpenPattern());
        readOnlyOpenEnabledCheckBox.selectedProperty().addListener(onValueChange(ignored -> updateReadOnlyOpenPatternControls()));
        updateReadOnlyOpenPatternControls();
        showReadOnlyOpenInstruction();
    }

    private void commitReadOnlyOpenPatternEdit(int rowIndex, String newValue) {
        if (rowIndex < 0 || rowIndex >= readOnlyOpenPatternItems.size()) {
            return;
        }

        String replacement = parseSingleReadOnlyOpenPattern(newValue);
        if (replacement == null) {
            showReadOnlyOpenError("Each row must contain exactly one file name or glob pattern.");
            readOnlyOpenPatternsTable.refresh();
            return;
        }

        int duplicateIndex = readOnlyOpenPatternItems.indexOf(replacement);
        if (duplicateIndex >= 0 && duplicateIndex != rowIndex) {
            showReadOnlyOpenError("That pattern is already listed.");
            readOnlyOpenPatternsTable.refresh();
            return;
        }

        readOnlyOpenPatternItems.set(rowIndex, replacement);
        showReadOnlyOpenInstruction();
    }

    private String parseSingleReadOnlyOpenPattern(String value) {
        List<String> parsedPatterns = ReadOnlyOpenRules.parsePatternText(value);
        return parsedPatterns.size() == 1 ? parsedPatterns.getFirst() : null;
    }

    private void updateReadOnlyOpenPatternControls() {
        boolean enabled = readOnlyOpenEnabledCheckBox.isSelected();
        boolean hasSelection = readOnlyOpenPatternsTable != null && !readOnlyOpenPatternsTable.getSelectionModel().getSelectedItems().isEmpty();
        readOnlyOpenPatternField.setDisable(!enabled);
        addReadOnlyOpenPatternButton.setDisable(!enabled);
        removeReadOnlyOpenPatternButton.setDisable(!enabled || !hasSelection);
        readOnlyOpenPatternsTable.setDisable(!enabled);
    }

    private void showReadOnlyOpenInstruction() {
        readOnlyOpenHelpLabel.setText("Add or remove file-name / glob patterns that should open in read-only mode by default. Double-click a row to edit it.");
        readOnlyOpenHelpLabel.getStyleClass().remove("settings-helper-label-error");
    }

    private void showReadOnlyOpenError(String message) {
        readOnlyOpenHelpLabel.setText(message);
        if (!readOnlyOpenHelpLabel.getStyleClass().contains("settings-helper-label-error")) {
            readOnlyOpenHelpLabel.getStyleClass().add("settings-helper-label-error");
        }
    }

    private ListCell<LanguagePreviewSpec> createSyntaxPreviewCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(LanguagePreviewSpec item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName() + (item.description().isBlank() ? "" : " · " + item.description()));
            }
        };
    }

    private void refreshSyntaxPreview(LanguagePreviewSpec previewSpec) {
        if (syntaxPreviewArea == null) {
            return;
        }
        if (previewSpec == null) {
            syntaxPreviewArea.replaceText("No preview languages available.");
            syntaxPreviewArea.setStyleSpans(0, LanguageAnalysis.plainText(syntaxPreviewArea.getText()).highlighting());
            if (syntaxPreviewHelpLabel != null) {
                syntaxPreviewHelpLabel.setText("Add bundled or external TextMate grammars to preview them here.");
            }
            return;
        }

        LanguageAnalysis analysis = syntaxPreviewHandler.apply(previewSpec);
        syntaxPreviewArea.replaceText(previewSpec.sampleText());
        syntaxPreviewArea.setStyleSpans(0, analysis.highlighting());
        syntaxPreviewArea.moveTo(0);
        syntaxPreviewArea.requestFollowCaret();
        if (syntaxPreviewHelpLabel != null) {
            syntaxPreviewHelpLabel.setText("Previewing " + previewSpec.displayName()
                    + (previewSpec.description().isBlank() ? "" : " (" + previewSpec.description() + ")")
                    + ". Reload external grammars from textmate-bundles/ to refresh this list without restarting.");
        }
    }

    private void updateThemeHelpLabel(EditorTheme theme) {
        if (themeHelpLabel == null) {
            return;
        }
        themeHelpLabel.setText("Preview applies live while settings are open. Families: Primer, Nord, Cupertino, Dracula. Selected: "
                + (theme == null ? EditorTheme.defaultTheme().getDisplayName() : theme.getDisplayName()));
    }

    private void handleShortcutCapture(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE || event.getCode() == KeyCode.TAB || event.getCode() == KeyCode.ENTER) {
            return;
        }
        if (event.getCode() == KeyCode.BACK_SPACE || event.getCode() == KeyCode.DELETE) {
            onResetCommandPaletteShortcut();
            event.consume();
            return;
        }
        if (event.getCode().isModifierKey()) {
            event.consume();
            return;
        }

        String capturedShortcut = CommandPaletteShortcut.capture(event);
        if (capturedShortcut == null) {
            showShortcutError("Press Alt or Command/Ctrl plus another key. Optional Shift is supported too.");
            event.consume();
            return;
        }

        commandPaletteShortcut = capturedShortcut;
        updateShortcutField();
        if (CommandPaletteShortcut.isReserved(commandPaletteShortcut)) {
            showShortcutError("That shortcut conflicts with another editor command. Pick a different shortcut.");
        } else {
            showShortcutInstruction();
        }
        event.consume();
    }

    private void updateShortcutField() {
        commandPaletteShortcutField.setText(CommandPaletteShortcut.displayText(commandPaletteShortcut));
    }

    private void showShortcutInstruction() {
        commandPaletteShortcutHelpLabel.setText("Focus the field and press the new shortcut. Backspace/Delete resets to "
                + CommandPaletteShortcut.displayText(CommandPaletteShortcut.DEFAULT_VALUE)
                + ". Alt-only and Command/Ctrl shortcuts are supported. Reserved editor shortcuts are blocked.");
        commandPaletteShortcutHelpLabel.getStyleClass().remove("settings-helper-label-error");
    }

    private void showShortcutError(String message) {
        commandPaletteShortcutHelpLabel.setText(message);
        if (!commandPaletteShortcutHelpLabel.getStyleClass().contains("settings-helper-label-error")) {
            commandPaletteShortcutHelpLabel.getStyleClass().add("settings-helper-label-error");
        }
    }

    private String normalizedFontFamily() {
        String family = editorFontFamilyComboBox.getEditor().getText();
        if (family == null || family.isBlank()) {
            family = editorFontFamilyComboBox.getValue();
        }
        if (family == null || family.isBlank()) {
            return null;
        }
        return family.strip();
    }

    private Integer parsedFontSize() {
        String value = editorFontSizeField.getText();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int size = Integer.parseInt(value.strip());
            return size >= 8 && size <= 72 ? size : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private void showFontInstruction() {
        editorFontHelpLabel.setText("Set the editor font family and size used by all document tabs. Size must be between 8 and 72.");
        editorFontHelpLabel.getStyleClass().remove("settings-helper-label-error");
    }

    private void showFontError(String message) {
        editorFontHelpLabel.setText(message);
        if (!editorFontHelpLabel.getStyleClass().contains("settings-helper-label-error")) {
            editorFontHelpLabel.getStyleClass().add("settings-helper-label-error");
        }
    }

    private void handleKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            onClose();
            event.consume();
        } else if (event.getCode() == KeyCode.ENTER && event.isShortcutDown()) {
            onApply();
            event.consume();
        }
    }

    private String buildKeyboardShortcutsReference(String paletteShortcut) {
        String commandPaletteDisplay = CommandPaletteShortcut.displayText(paletteShortcut);
        return String.join("\n",
                "Shell shortcuts",
                "  Command palette / M-x ...... " + commandPaletteDisplay,
                "  Settings ................... ⌘,",
                "  Search focus ............... ⌘F",
                "  Toggle search .............. ⌥⌘F",
                "  Toggle explorer ............ ⌥⌘E",
                "  Toggle status bar .......... ⌥⌘B",
                "",
                "Find File minibuffer (launch from command palette)",
                "  Open prompt ................ Palette → Find File",
                "  Complete path .............. Tab",
                "  Candidate next / previous .. ⌃N / ⌃P",
                "  Recall prior inputs ........ ⌃R",
                "  Delete path segment ........ ⌃⌫",
                "  Open / descend / cancel .... Enter / Enter / Esc",
                "  Inline preview ............. Highlight a candidate",
                "",
                "Editor-local Emacs shortcuts (inside CodeArea tabs)",
                "  Set mark ................... ⌃Space",
                "  Clear mark ................. ⌃G",
                "  Backward / forward char .... ⌃B / ⌃F",
                "  Previous / next line ....... ⌃P / ⌃N",
                "  Line start / end ........... ⌃A / ⌃E",
                "  Backward / forward word .... ⌥B / ⌥F",
                "  Buffer start / end ......... ⌥< / ⌥>",
                "  Kill region ................ ⌃W",
                "  Copy region ................ ⌥W",
                "  Kill line .................. ⌃K",
                "  Delete char ................ ⌃D",
                "  Kill word forward .......... ⌥D",
                "  Kill word backward ......... ⌥⌫",
                "  Yank clipboard ............. ⌃Y",
                "  Split down ................. ⌃X 2",
                "  Split right ................ ⌃X 3",
                "  Unsplit .................... ⌃X 0",
                "  Other split view ........... ⌃X O",
                "",
                "Read-only mode (active tab)",
                "  Page down / up ............. Space / ⌫");
    }

    private void requireInjectedControls() {
        Objects.requireNonNull(settingsRoot);
        Objects.requireNonNull(themeComboBox);
        Objects.requireNonNull(settingsTabPane);
        Objects.requireNonNull(wrapTextCheckBox);
        Objects.requireNonNull(diagnosticsCheckBox);
        Objects.requireNonNull(miniMapVisibleCheckBox);
        Objects.requireNonNull(searchBarVisibleCheckBox);
        Objects.requireNonNull(toolDockVisibleCheckBox);
        Objects.requireNonNull(bookmarkWindowVisibleCheckBox);
        Objects.requireNonNull(breadcrumbBarVisibleCheckBox);
        Objects.requireNonNull(readOnlyOpenEnabledCheckBox);
        Objects.requireNonNull(readOnlyOpenPatternField);
        Objects.requireNonNull(addReadOnlyOpenPatternButton);
        Objects.requireNonNull(removeReadOnlyOpenPatternButton);
        Objects.requireNonNull(readOnlyOpenPatternsTable);
        Objects.requireNonNull(readOnlyOpenPatternColumn);
        Objects.requireNonNull(readOnlyOpenHelpLabel);
        Objects.requireNonNull(toolDockSideComboBox);
        Objects.requireNonNull(editorFontFamilyComboBox);
        Objects.requireNonNull(editorFontSizeField);
        Objects.requireNonNull(editorFontHelpLabel);
        Objects.requireNonNull(commandPaletteShortcutField);
        Objects.requireNonNull(commandPaletteShortcutHelpLabel);
        Objects.requireNonNull(pluginsDirectoryLabel);
        Objects.requireNonNull(persistenceDirectoryLabel);
        Objects.requireNonNull(persistenceFormatLabel);
        Objects.requireNonNull(openPersistenceFolderButton);
        Objects.requireNonNull(availableLanguagesLabel);
        Objects.requireNonNull(editorControlLabel);
        Objects.requireNonNull(keyboardShortcutsLabel);
        Objects.requireNonNull(syntaxPreviewLanguageComboBox);
        Objects.requireNonNull(syntaxPreviewHelpLabel);
        Objects.requireNonNull(syntaxPreviewHost);
        Objects.requireNonNull(cancelButton);
        Objects.requireNonNull(applyButton);
        Objects.requireNonNull(keyboardShortcutsTab);
    }

    private <T> javafx.beans.value.ChangeListener<T> onValueChange(Consumer<T> consumer) {
        return (observable, ignored, current) -> {
            Objects.requireNonNull(observable);
            consumer.accept(current);
        };
    }

    private static void ignoreSettings(EditorSettings settings) {
        Objects.requireNonNull(settings);
    }

    private static void ignoreTheme(EditorTheme theme) {
        Objects.requireNonNull(theme);
    }
}

