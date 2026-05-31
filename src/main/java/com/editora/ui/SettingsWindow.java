package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

/** A small settings window. Changes are persisted to settings.json and applied live. */
public class SettingsWindow {

    private static final String APP_NAME = "Editora";
    private static final String APP_VERSION = "1.0.0";
    // Window size (10% larger than the original 520x560).
    private static final double WIDTH = 572;
    private static final double HEIGHT = 616;
    /** Build timestamp baked in by Maven resource filtering (see build-info.properties). */
    private static final String BUILD_TIME = loadBuildTime();

    private final ConfigManager config;
    private final Consumer<Settings> onApply;
    private final ToolWindowManager toolWindows;
    private final Stage stage = new Stage();

    private ComboBox<String> fontFamily;
    private Spinner<Integer> fontSize;
    private ComboBox<String> themeCombo;
    private ComboBox<String> editorThemeCombo;
    private CheckBox columnRulerCheck;
    private CheckBox lineHighlightCheck;
    private CheckBox lineNumbersCheck;
    private CheckBox minimapCheck;
    private CheckBox whitespaceCheck;
    private boolean built;
    private boolean loading;

    public SettingsWindow(ConfigManager config, ToolWindowManager toolWindows, Consumer<Settings> onApply) {
        this.config = config;
        this.toolWindows = toolWindows;
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
            centerOnOwner(owner);
            stage.show();
        }
    }

    /** Positions the window centered over the app's main window. */
    private void centerOnOwner(Window owner) {
        if (owner == null) {
            return;
        }
        stage.setX(owner.getX() + (owner.getWidth() - WIDTH) / 2);
        stage.setY(owner.getY() + (owner.getHeight() - HEIGHT) / 2);
    }

    private void build(Window owner) {
        stage.setTitle("Settings");
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);

        fontFamily = new ComboBox<>();
        fontFamily.getItems().setAll(monospaceFamilies());
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

        themeCombo = new ComboBox<>();
        themeCombo.getItems().setAll(Themes.NAMES);
        themeCombo.setPrefWidth(220);
        themeCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setTheme(now);
            javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(now));
            // The editor theme follows the app theme until the user picks one explicitly.
            if (!config.getSettings().isEditorThemeUserSet()) {
                String match = EditorThemes.defaultFor(now);
                config.getSettings().setEditorTheme(match);
                loading = true;
                editorThemeCombo.setValue(match);
                loading = false;
            }
            apply();
        });

        editorThemeCombo = new ComboBox<>();
        editorThemeCombo.getItems().setAll(EditorThemes.NAMES);
        editorThemeCombo.setPrefWidth(220);
        editorThemeCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setEditorTheme(now);
            config.getSettings().setEditorThemeUserSet(true);
            apply();
        });

        columnRulerCheck = new CheckBox("Show 80-column ruler");
        columnRulerCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setShowColumnRuler(now);
            apply();
        });
        lineHighlightCheck = new CheckBox("Highlight current line");
        lineHighlightCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setHighlightCurrentLine(now);
            apply();
        });
        lineNumbersCheck = new CheckBox("Show line numbers");
        lineNumbersCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setShowLineNumbers(now);
            apply();
        });
        minimapCheck = new CheckBox("Show minimap");
        minimapCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setShowMinimap(now);
            apply();
        });
        whitespaceCheck = new CheckBox("Show hidden characters (spaces, tabs, EOL)");
        whitespaceCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setShowWhitespace(now);
            apply();
        });

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(10);
        Label fontNote = new Label("Only monospaced fonts are listed.");
        fontNote.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        VBox fontFamilyBox = new VBox(4, fontFamily, fontNote);
        form.addRow(0, new Label("Font family:"), fontFamilyBox);
        form.addRow(1, new Label("Font size:"), fontSize);
        form.addRow(2, new Label("Theme:"), themeCombo);
        Label editorThemeNote = new Label("Follows the app theme until you pick one.");
        editorThemeNote.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        VBox editorThemeBox = new VBox(4, editorThemeCombo, editorThemeNote);
        form.addRow(3, new Label("Editor theme:"), editorThemeBox);
        form.add(columnRulerCheck, 1, 4);
        form.add(lineHighlightCheck, 1, 5);
        form.add(lineNumbersCheck, 1, 6);
        form.add(minimapCheck, 1, 7);
        form.add(whitespaceCheck, 1, 8);

        int row = 9;
        if (!toolWindows.getRegisteredToolWindows().isEmpty()) {
            form.add(new Separator(), 0, row++, 2, 1);
            Label heading = new Label("Tool window placement");
            heading.setStyle("-fx-font-weight: bold;");
            form.add(heading, 0, row++, 2, 1);
            for (ToolWindow tw : toolWindows.getRegisteredToolWindows()) {
                CheckBox showCheck = new CheckBox("Show");
                showCheck.setSelected(toolWindows.isVisible(tw));

                ComboBox<ToolWindow.Side> sideCombo = new ComboBox<>();
                sideCombo.getItems().setAll(ToolWindow.Side.values());
                sideCombo.setConverter(new StringConverter<>() {
                    @Override
                    public String toString(ToolWindow.Side side) {
                        if (side == null) {
                            return "";
                        }
                        return side.name().charAt(0) + side.name().substring(1).toLowerCase();
                    }

                    @Override
                    public ToolWindow.Side fromString(String s) {
                        return ToolWindow.Side.valueOf(s.toUpperCase());
                    }
                });
                sideCombo.setValue(toolWindows.currentSide(tw));
                sideCombo.setDisable(!showCheck.isSelected());

                showCheck.selectedProperty().addListener((obs, was, visible) -> {
                    toolWindows.setVisible(tw, visible);
                    sideCombo.setDisable(!visible);
                });
                sideCombo.valueProperty().addListener((obs, old, now) -> {
                    if (now != null) {
                        toolWindows.setSide(tw, now);
                    }
                });

                form.addRow(row++, new Label(tw.getTitle() + ":"), showCheck, sideCombo);
            }
        }

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
        rootBox.setPrefWidth(WIDTH);
        rootBox.setPrefHeight(HEIGHT);

        stage.setScene(new Scene(rootBox, WIDTH, HEIGHT));
    }

    private void load() {
        loading = true;
        try {
            Settings settings = config.getSettings();
            if (!fontFamily.getItems().contains(settings.getFontFamily())) {
                fontFamily.getItems().add(0, settings.getFontFamily());
            }
            fontFamily.setValue(settings.getFontFamily());
            fontSize.getValueFactory().setValue(settings.getFontSize());
            String theme = Themes.normalize(settings.getTheme());
            if (!theme.equals(settings.getTheme())) {
                settings.setTheme(theme);
            }
            themeCombo.setValue(theme);
            String editorTheme = EditorThemes.normalize(settings.getEditorTheme());
            if (!editorTheme.equals(settings.getEditorTheme())) {
                settings.setEditorTheme(editorTheme);
            }
            editorThemeCombo.setValue(editorTheme);
            columnRulerCheck.setSelected(settings.isShowColumnRuler());
            lineHighlightCheck.setSelected(settings.isHighlightCurrentLine());
            lineNumbersCheck.setSelected(settings.isShowLineNumbers());
            minimapCheck.setSelected(settings.isShowMinimap());
            whitespaceCheck.setSelected(settings.isShowWhitespace());
        } finally {
            loading = false;
        }
    }

    /**
     * The installed font families that render as monospaced. JavaFX has no monospace flag, so we
     * compare the advance width of a narrow glyph ("i") against a wide one ("W"): in a fixed-pitch
     * font they are equal.
     */
    private static List<String> monospaceFamilies() {
        Text narrow = new Text("iiiiiiiiii");
        Text wide = new Text("WWWWWWWWWW");
        List<String> families = new ArrayList<>();
        for (String family : Font.getFamilies()) {
            Font font = Font.font(family, 14);
            if (font == null) {
                continue;
            }
            narrow.setFont(font);
            wide.setFont(font);
            if (Math.abs(narrow.getLayoutBounds().getWidth() - wide.getLayoutBounds().getWidth()) < 0.5) {
                families.add(family);
            }
        }
        return families;
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
        if (loading) {
            return;
        }
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
                Built %s
                Settings: ~/.editora-v2/settings.json""".formatted(
                System.getProperty("java.version", "?"),
                System.getProperty("javafx.runtime.version", "?"),
                BUILD_TIME));
        alert.showAndWait();
    }

    /** Reads the Maven-filtered build timestamp; falls back gracefully for unfiltered/dev runs. */
    private static String loadBuildTime() {
        try (InputStream in = SettingsWindow.class.getResourceAsStream("/com/editora/build-info.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(in);
            String time = props.getProperty("build.time", "");
            // Unfiltered (e.g. run straight from an IDE) leaves the literal Maven placeholder.
            return time.isEmpty() || time.startsWith("${") ? "(dev build)" : time;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
