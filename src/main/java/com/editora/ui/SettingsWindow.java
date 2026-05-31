package com.editora.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tooltip;
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

/** A small settings window. Changes are persisted to settings.toml and applied live. */
public class SettingsWindow {

    private static final String APP_NAME = "Editora";
    private static final String APP_VERSION = "1.0.0";
    // Window size; height bumped 20% (708 -> 850) to fit the added view options without scrolling.
    private static final double WIDTH = 658;
    private static final double HEIGHT = 850;
    /** Build timestamp baked in by Maven resource filtering (see build-info.properties). */
    private static final String BUILD_TIME = loadBuildTime();

    private final ConfigManager config;
    private final Consumer<Settings> onApply;
    private final Consumer<Boolean> onToggleZen;
    private final Consumer<Path> onOpenFile;
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
    private CheckBox toolbarCheck;
    private CheckBox statusBarCheck;
    private CheckBox tabBarCheck;
    private CheckBox breadcrumbCheck;
    private CheckBox zenCheck;
    private ComboBox<String> autoSaveCombo;
    private Spinner<Integer> autoSaveDelaySpinner;
    private boolean built;
    private boolean loading;

    public SettingsWindow(ConfigManager config, ToolWindowManager toolWindows,
                          Consumer<Settings> onApply, Consumer<Boolean> onToggleZen,
                          Consumer<Path> onOpenFile) {
        this.config = config;
        this.toolWindows = toolWindows;
        this.onApply = onApply;
        this.onToggleZen = onToggleZen;
        this.onOpenFile = onOpenFile;
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
        fontFamily.getItems().setAll(fontFamilyChoices());
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
        toolbarCheck = new CheckBox("Show toolbar");
        toolbarCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setShowToolbar(now);
            apply();
        });
        statusBarCheck = new CheckBox("Show status bar");
        statusBarCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setShowStatusBar(now);
            apply();
        });

        tabBarCheck = new CheckBox("Show tab bar");
        tabBarCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setShowTabBar(now);
            apply();
        });

        breadcrumbCheck = new CheckBox("Show file breadcrumb");
        breadcrumbCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setShowBreadcrumb(now);
            apply();
        });

        zenCheck = new CheckBox("Zen mode (distraction-free)");
        zenCheck.selectedProperty().addListener((obs, was, now) -> {
            if (loading) {
                return;
            }
            // Zen isn't a plain Settings field: route through the orchestration (it snapshots/hides
            // tool windows and flips the view/chrome prefs off, or restores them). Then re-sync the
            // other checkboxes, which Zen just changed underneath us.
            onToggleZen.accept(now);
            syncViewChecks();
        });

        autoSaveCombo = new ComboBox<>();
        autoSaveCombo.getItems().setAll(
                MainController.AUTOSAVE_OFF, MainController.AUTOSAVE_DELAY, MainController.AUTOSAVE_FOCUS);
        autoSaveCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(String key) {
                return key == null ? "" : MainController.autoSaveLabel(key);
            }
            @Override public String fromString(String label) {
                return label;
            }
        });
        autoSaveCombo.setPrefWidth(170);
        autoSaveCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setAutoSave(now);
            autoSaveDelaySpinner.setDisable(!MainController.AUTOSAVE_DELAY.equals(now));
            apply();
        });

        // Shown in whole seconds (users think in seconds); stored internally as milliseconds.
        autoSaveDelaySpinner = new Spinner<>(1, 300, 1, 1);
        autoSaveDelaySpinner.setEditable(true);
        autoSaveDelaySpinner.setPrefWidth(90);
        autoSaveDelaySpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setAutoSaveDelayMillis(now * 1000);
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
        form.add(toolbarCheck, 1, 9);
        form.add(statusBarCheck, 1, 10);
        form.add(tabBarCheck, 1, 11);
        form.add(breadcrumbCheck, 1, 12);
        form.add(zenCheck, 1, 13);
        Label delayLabel = new Label("delay (seconds)");
        delayLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        HBox autoSaveBox = new HBox(8, autoSaveCombo, autoSaveDelaySpinner, delayLabel);
        autoSaveBox.setAlignment(Pos.CENTER_LEFT);
        form.addRow(14, new Label("Auto save:"), autoSaveBox);

        int row = 15;
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
        about.setOnAction(e -> showAbout(stage, onOpenFile));
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
            toolbarCheck.setSelected(settings.isShowToolbar());
            statusBarCheck.setSelected(settings.isShowStatusBar());
            tabBarCheck.setSelected(settings.isShowTabBar());
            breadcrumbCheck.setSelected(settings.isShowBreadcrumb());
            zenCheck.setSelected(config.getWorkspaceState().isZenMode());
            String mode = MainController.autoSaveModeOf(settings.getAutoSave());
            autoSaveCombo.setValue(mode);
            autoSaveDelaySpinner.getValueFactory()
                    .setValue(Math.max(1, (int) Math.round(settings.getAutoSaveDelayMillis() / 1000.0)));
            autoSaveDelaySpinner.setDisable(!MainController.AUTOSAVE_DELAY.equals(mode));
        } finally {
            loading = false;
        }
    }

    /** Re-syncs the view/chrome checkboxes from the current settings (used after a Zen toggle). */
    private void syncViewChecks() {
        boolean prev = loading;
        loading = true;
        try {
            Settings s = config.getSettings();
            columnRulerCheck.setSelected(s.isShowColumnRuler());
            lineHighlightCheck.setSelected(s.isHighlightCurrentLine());
            lineNumbersCheck.setSelected(s.isShowLineNumbers());
            minimapCheck.setSelected(s.isShowMinimap());
            whitespaceCheck.setSelected(s.isShowWhitespace());
            toolbarCheck.setSelected(s.isShowToolbar());
            statusBarCheck.setSelected(s.isShowStatusBar());
            tabBarCheck.setSelected(s.isShowTabBar());
            breadcrumbCheck.setSelected(s.isShowBreadcrumb());
        } finally {
            loading = prev;
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

    /** Bundled fonts first (always offered), then the other installed monospaced families. */
    private static List<String> fontFamilyChoices() {
        List<String> choices = new ArrayList<>(Fonts.BUNDLED);
        for (String family : monospaceFamilies()) {
            if (!choices.contains(family)) {
                choices.add(family);
            }
        }
        return choices;
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

    /**
     * Shows the About dialog. Shared by the settings window and the {@code help.about} command.
     * The settings-file path is a link that opens that file in the editor via {@code openFile}.
     */
    public static void showAbout(Window owner, Consumer<Path> openFile) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("About " + APP_NAME);
        alert.setHeaderText(APP_NAME + " " + APP_VERSION);
        var iconStream = SettingsWindow.class.getResourceAsStream("/com/editora/icons/icon-128.png");
        if (iconStream != null) {
            ImageView logo = new ImageView(new Image(iconStream));
            logo.setFitWidth(72);
            logo.setFitHeight(72);
            alert.setGraphic(logo);
        }

        Label info = new Label("""
                A keyboard-driven, cross-platform programmer's text editor.

                Java %s
                JavaFX %s
                Built %s""".formatted(
                System.getProperty("java.version", "?"),
                System.getProperty("javafx.runtime.version", "?"),
                BUILD_TIME));

        Hyperlink settingsLink = new Hyperlink(displaySettingsPath());
        settingsLink.setPadding(Insets.EMPTY);
        settingsLink.setTooltip(new Tooltip("Open the settings file in Editora"));
        settingsLink.setOnAction(e -> {
            alert.close();
            if (openFile != null) {
                openFile.accept(ConfigManager.defaultSettingsFile());
            }
        });
        HBox settingsRow = new HBox(4, new Label("Settings:"), settingsLink);
        settingsRow.setAlignment(Pos.CENTER_LEFT);

        alert.getDialogPane().setContent(new VBox(10, info, settingsRow));
        alert.showAndWait();
    }

    /** The settings-file path with the home dir shown as {@code ~} (derived, never hardcoded). */
    private static String displaySettingsPath() {
        String path = ConfigManager.defaultSettingsFile().toString();
        String home = System.getProperty("user.home", "");
        return !home.isEmpty() && path.startsWith(home) ? "~" + path.substring(home.length()) : path;
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
