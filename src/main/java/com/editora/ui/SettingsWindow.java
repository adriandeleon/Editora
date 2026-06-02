package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.SpellDictionaries;

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

    // Window size; bumped 10% in each dimension (658x850 -> 724x935) for a roomier layout.
    private static final double WIDTH = 724;
    private static final double HEIGHT = 935;

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
    private CheckBox spellCheckBox;
    private ComboBox<String> spellLanguageCombo;
    private CheckBox toolbarCheck;
    private CheckBox statusBarCheck;
    private CheckBox tabBarCheck;
    private CheckBox breadcrumbCheck;
    private CheckBox projectsCheck;
    private CheckBox zenCheck;
    // The Project tool-window-placement row, disabled until projects are enabled.
    private CheckBox projectShowCheck;
    private ComboBox<ToolWindow.Side> projectSideCombo;
    private ToolWindow projectToolWindowRef;
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
        spellCheckBox = new CheckBox("Enable spell check");
        spellCheckBox.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setSpellCheck(now);
            if (spellLanguageCombo != null) {
                spellLanguageCombo.setDisable(!now);
            }
            apply();
        });
        spellLanguageCombo = new ComboBox<>();
        spellLanguageCombo.getItems().setAll(SpellDictionaries.available());
        spellLanguageCombo.setPrefWidth(220);
        spellLanguageCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : spellLanguageName(id);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        spellLanguageCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setSpellLanguage(now);
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

        projectsCheck = new CheckBox("Enable projects");
        projectsCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setProjectSupport(now);
            apply();
            updateProjectRowEnabled();
        });
        Label projectsInfo = new Label("ⓘ");
        projectsInfo.getStyleClass().add("info-badge");
        Tooltip projectsTip = new Tooltip(
                "Projects are single-folder workspaces, each remembering its own open files and layout. "
                + "Pick one to switch; \"No Project\" returns to the global session.");
        projectsTip.setWrapText(true);
        projectsTip.setMaxWidth(380);
        Tooltip.install(projectsInfo, projectsTip);
        HBox projectsRow = new HBox(6, projectsCheck, projectsInfo);
        projectsRow.setAlignment(Pos.CENTER_LEFT);

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
        form.add(projectsRow, 1, 13);
        form.add(zenCheck, 1, 14);
        Label delayLabel = new Label("delay (seconds)");
        delayLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-muted;");
        HBox autoSaveBox = new HBox(8, autoSaveCombo, autoSaveDelaySpinner, delayLabel);
        autoSaveBox.setAlignment(Pos.CENTER_LEFT);
        form.addRow(15, new Label("Auto save:"), autoSaveBox);
        form.add(spellCheckBox, 1, 16);
        form.addRow(17, new Label("Spell check language:"), spellLanguageCombo);

        int row = 18;
        List<Runnable> moveRefreshers = new ArrayList<>();
        Runnable refreshMoves = () -> moveRefreshers.forEach(Runnable::run);
        if (!toolWindows.getRegisteredToolWindows().isEmpty()) {
            form.add(new Separator(), 0, row++, 2, 1);
            Label heading = new Label("Tool window placement");
            heading.setStyle("-fx-font-weight: bold;");
            form.add(heading, 0, row++, 2, 1);
            Label orderHint = new Label("Use ▲ ▼ (or drag the stripe icons) to reorder.");
            orderHint.getStyleClass().add("settings-hint");
            form.add(orderHint, 0, row++, 4, 1);
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

                Button moveUp = new Button("▲");
                Button moveDown = new Button("▼");
                moveUp.getStyleClass().addAll("flat", "reorder-button");
                moveDown.getStyleClass().addAll("flat", "reorder-button");
                moveUp.setTooltip(new Tooltip("Move earlier in the stripe"));
                moveDown.setTooltip(new Tooltip("Move later in the stripe"));
                Runnable refreshThisRow = () -> {
                    boolean shown = showCheck.isSelected();
                    moveUp.setDisable(!shown || !toolWindows.canMove(tw, -1));
                    moveDown.setDisable(!shown || !toolWindows.canMove(tw, 1));
                };
                moveRefreshers.add(refreshThisRow);
                moveUp.setOnAction(e -> {
                    toolWindows.move(tw, -1);
                    refreshMoves.run();
                });
                moveDown.setOnAction(e -> {
                    toolWindows.move(tw, 1);
                    refreshMoves.run();
                });
                HBox reorder = new HBox(2, moveUp, moveDown);

                showCheck.selectedProperty().addListener((obs, was, visible) -> {
                    toolWindows.setVisible(tw, visible);
                    sideCombo.setDisable(!visible);
                    refreshMoves.run();
                });
                sideCombo.valueProperty().addListener((obs, old, now) -> {
                    if (now != null) {
                        toolWindows.setSide(tw, now);
                        refreshMoves.run();
                    }
                });

                if ("project".equals(tw.getId())) {
                    projectShowCheck = showCheck; // disabled until projects are enabled
                    projectSideCombo = sideCombo;
                    projectToolWindowRef = tw;
                }
                form.addRow(row++, new Label(tw.getTitle() + ":"), showCheck, sideCombo, reorder);
            }
            refreshMoves.run();
        }
        updateProjectRowEnabled();

        Button about = new Button("About");
        about.setOnAction(e -> showAbout(stage, config.getSettingsFile(), onOpenFile));
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
            spellCheckBox.setSelected(settings.isSpellCheck());
            spellLanguageCombo.setValue(settings.getSpellLanguage());
            spellLanguageCombo.setDisable(!settings.isSpellCheck());
            toolbarCheck.setSelected(settings.isShowToolbar());
            statusBarCheck.setSelected(settings.isShowStatusBar());
            tabBarCheck.setSelected(settings.isShowTabBar());
            breadcrumbCheck.setSelected(settings.isShowBreadcrumb());
            projectsCheck.setSelected(settings.isProjectSupport());
            updateProjectRowEnabled();
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

    /**
     * Enables/disables the "Project" tool-window-placement row to match the "Enable projects" setting:
     * when projects are off the row is unchecked + disabled (you can't show the panel until enabled).
     * Mirrors the real visibility, so the show-checkbox listener's {@code setVisible} is a no-op.
     */
    private void updateProjectRowEnabled() {
        if (projectShowCheck == null) {
            return;
        }
        boolean on = config.getSettings().isProjectSupport();
        boolean visible = on && projectToolWindowRef != null && toolWindows.isVisible(projectToolWindowRef);
        projectShowCheck.setSelected(visible);
        projectShowCheck.setDisable(!on);
        projectSideCombo.setDisable(!visible);
    }

    /** Re-checks the "Enable projects" box from the current setting without re-firing its listener. */
    public void syncProjectsCheck() {
        boolean prev = loading;
        loading = true;
        try {
            projectsCheck.setSelected(config.getSettings().isProjectSupport());
            updateProjectRowEnabled();
        } finally {
            loading = prev;
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
            spellCheckBox.setSelected(s.isSpellCheck());
            spellLanguageCombo.setValue(s.getSpellLanguage());
            spellLanguageCombo.setDisable(!s.isSpellCheck());
            toolbarCheck.setSelected(s.isShowToolbar());
            statusBarCheck.setSelected(s.isShowStatusBar());
            tabBarCheck.setSelected(s.isShowTabBar());
            breadcrumbCheck.setSelected(s.isShowBreadcrumb());
            projectsCheck.setSelected(s.isProjectSupport());
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
    public static void showAbout(Window owner, Path settingsFile, Consumer<Path> openFile) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle("About " + com.editora.AppInfo.NAME);
        alert.setHeaderText(com.editora.AppInfo.NAME + " " + com.editora.AppInfo.VERSION);
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
                com.editora.AppInfo.buildTime()));

        Hyperlink settingsLink = new Hyperlink(displaySettingsPath(settingsFile));
        settingsLink.setPadding(Insets.EMPTY);
        settingsLink.setTooltip(new Tooltip("Open the settings file in Editora"));
        settingsLink.setOnAction(e -> {
            alert.close();
            if (openFile != null) {
                openFile.accept(settingsFile);
            }
        });
        HBox settingsRow = new HBox(4, new Label("Settings:"), settingsLink);
        settingsRow.setAlignment(Pos.CENTER_LEFT);

        alert.getDialogPane().setContent(new VBox(10, info, settingsRow));
        alert.showAndWait();
    }

    /** A friendly display name for a spell-check dictionary id (falls back to the id itself). */
    private static String spellLanguageName(String id) {
        return switch (id) {
            case "en_US" -> "English (US)";
            case "en_GB" -> "English (UK)";
            case "es" -> "Spanish";
            case "fr" -> "French";
            default -> id;
        };
    }

    /** The given settings-file path with the home dir shown as {@code ~} (derived, never hardcoded). */
    static String displaySettingsPath(Path settingsFile) {
        String path = settingsFile.toString();
        String home = System.getProperty("user.home", "");
        return !home.isEmpty() && path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }
}
