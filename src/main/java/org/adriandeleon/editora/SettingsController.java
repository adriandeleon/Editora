package org.adriandeleon.editora;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.application.Platform;
import org.adriandeleon.editora.settings.CommandPaletteShortcut;
import org.adriandeleon.editora.settings.EditorSettings;
import org.adriandeleon.editora.theme.EditorTheme;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;

public class SettingsController {
    @FXML
    private VBox settingsRoot;

    @FXML
    private ScrollPane settingsScrollPane;

    @FXML
    private ComboBox<EditorTheme> themeComboBox;

    @FXML
    private Label themeHelpLabel;

    @FXML
    private CheckBox wrapTextCheckBox;

    @FXML
    private CheckBox diagnosticsCheckBox;

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
    private Label availableLanguagesLabel;

    @FXML
    private Label editorControlLabel;

    @FXML
    private Label keyboardShortcutsLabel;

    @FXML
    private Button cancelButton;

    @FXML
    private Button applyButton;

    @FXML
    private VBox keyboardShortcutsCard;

    private Consumer<EditorSettings> applyHandler = settings -> {
    };
    private Consumer<EditorTheme> previewThemeHandler = theme -> {
    };
    private Runnable closeHandler = () -> {
    };
    private String commandPaletteShortcut = CommandPaletteShortcut.DEFAULT_VALUE;
    private boolean suppressThemePreview;

    @FXML
    private void initialize() {
        themeComboBox.setItems(FXCollections.observableArrayList(EditorTheme.values()));
        themeComboBox.setCellFactory(listView -> createThemeCell());
        themeComboBox.setButtonCell(createThemeCell());
        themeComboBox.valueProperty().addListener((observable, previous, current) -> {
            if (suppressThemePreview || current == null) {
                return;
            }
            previewThemeHandler.accept(current);
            updateThemeHelpLabel(current);
        });
        editorFontFamilyComboBox.setItems(FXCollections.observableArrayList(Font.getFamilies().stream()
                .sorted(Comparator.naturalOrder())
                .toList()));
        editorFontFamilyComboBox.setEditable(true);
        editorControlLabel.setText("RichTextFX CodeArea inside VirtualizedScrollPane");
        commandPaletteShortcutField.setEditable(false);
        commandPaletteShortcutField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleShortcutCapture);
        settingsRoot.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
    }

    public void configure(EditorSettings settings,
                          Path pluginsDirectory,
                          String availableLanguages,
                          Consumer<EditorTheme> previewThemeHandler,
                          Consumer<EditorSettings> applyHandler,
                          Runnable closeHandler) {
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
        editorFontFamilyComboBox.setValue(settings.editorFontFamily());
        editorFontSizeField.setText(Integer.toString(settings.editorFontSize()));
        showFontInstruction();
        commandPaletteShortcut = settings.commandPaletteShortcut();
        updateShortcutField();
        showShortcutInstruction();
        pluginsDirectoryLabel.setText(pluginsDirectory.toAbsolutePath().toString());
        availableLanguagesLabel.setText(availableLanguages);
        keyboardShortcutsLabel.setText(buildKeyboardShortcutsReference(commandPaletteShortcut));
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
                commandPaletteShortcut,
                fontFamily,
                fontSize
        ));
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

    public void focusPrimaryControl() {
        Platform.runLater(themeComboBox::requestFocus);
    }

    public void showKeyboardShortcutsSection() {
        Platform.runLater(() -> {
            if (settingsScrollPane != null) {
                settingsScrollPane.setVvalue(1.0);
            }
            if (keyboardShortcutsCard != null) {
                keyboardShortcutsCard.requestFocus();
            }
        });
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
                "  Yank clipboard ............. ⌃Y");
    }
}

