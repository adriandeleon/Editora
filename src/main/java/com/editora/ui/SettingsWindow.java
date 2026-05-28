package com.editora.ui;

import java.util.function.Consumer;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** A small settings window. Changes are persisted to settings.json and applied live. */
public class SettingsWindow {

    private static final String APP_NAME = "Editora";
    private static final String APP_VERSION = "1.0.0";

    private final ConfigManager config;
    private final Consumer<Settings> onApply;
    private final Stage stage = new Stage();

    private ComboBox<String> fontFamily;
    private Spinner<Integer> fontSize;
    private boolean built;

    public SettingsWindow(ConfigManager config, Consumer<Settings> onApply) {
        this.config = config;
        this.onApply = onApply;
    }

    public void show(Window owner) {
        if (!built) {
            build(owner);
            built = true;
        }
        load();
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            stage.show();
        }
    }

    private void build(Window owner) {
        stage.setTitle("Settings");
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);

        fontFamily = new ComboBox<>();
        fontFamily.getItems().setAll(Font.getFamilies());
        fontFamily.setPrefWidth(220);
        fontFamily.valueProperty().addListener((obs, old, now) -> apply());

        fontSize = new Spinner<>(8, 48, 14);
        fontSize.setEditable(true);
        fontSize.setPrefWidth(90);
        fontSize.valueProperty().addListener((obs, old, now) -> apply());
        // An editable Spinner does not commit typed text to its value automatically; do it on
        // Enter and on focus loss (e.g. when clicking Close) so the typed size is saved.
        fontSize.getEditor().setOnAction(e -> commitFontSize());
        fontSize.getEditor().focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commitFontSize();
            }
        });

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        form.addRow(0, new Label("Font family:"), fontFamily);
        form.addRow(1, new Label("Font size:"), fontSize);

        Button about = new Button("About");
        about.setOnAction(e -> showAbout(stage));
        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox buttons = new HBox(8, about, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox rootBox = new VBox(16, form, buttons);
        rootBox.setPadding(new Insets(16));
        rootBox.setPrefWidth(380);

        stage.setScene(new Scene(rootBox));
    }

    private void load() {
        Settings settings = config.getSettings();
        if (!fontFamily.getItems().contains(settings.getFontFamily())) {
            fontFamily.getItems().add(0, settings.getFontFamily());
        }
        fontFamily.setValue(settings.getFontFamily());
        fontSize.getValueFactory().setValue(settings.getFontSize());
    }

    /** Parses the spinner's editor text, clamps it to range, and commits it to the value. */
    private void commitFontSize() {
        try {
            int value = Math.max(8, Math.min(48, Integer.parseInt(fontSize.getEditor().getText().trim())));
            fontSize.getValueFactory().setValue(value);
            fontSize.getEditor().setText(String.valueOf(value));
        } catch (NumberFormatException e) {
            fontSize.getEditor().setText(String.valueOf(fontSize.getValue()));
        }
    }

    private void apply() {
        if (fontFamily.getValue() == null || fontSize.getValue() == null) {
            return;
        }
        Settings settings = config.getSettings();
        settings.setFontFamily(fontFamily.getValue());
        settings.setFontSize(fontSize.getValue());
        config.save();
        onApply.accept(settings);
    }

    /** Shows the About dialog. Shared by the settings window and the {@code help.about} command. */
    public static void showAbout(Window owner) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("About " + APP_NAME);
        alert.setHeaderText(APP_NAME + " " + APP_VERSION);
        alert.setContentText("""
                A keyboard-driven, cross-platform programmer's text editor.

                Java %s
                JavaFX %s
                Settings: ~/.editora-v2/settings.json""".formatted(
                System.getProperty("java.version", "?"),
                System.getProperty("javafx.runtime.version", "?")));
        alert.showAndWait();
    }
}
