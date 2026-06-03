package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.SpellDictionaries;
import com.editora.editor.TextMateHighlighter;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * The Settings window: a left category sidebar + per-category pages, a search box, and a live
 * preview, scalable as Editora grows. Changes are <em>applied live</em> — each control writes its
 * {@link Settings} field and calls {@link #apply()} (persist to {@code settings.toml} + notify the
 * controller), so there is no OK/Cancel; only Reset + Close.
 */
public class SettingsWindow {

    private static final double WIDTH = 860;
    private static final double HEIGHT = 620;

    /** Settings categories shown in the sidebar. Placeholder pages are roadmap features (no settings yet). */
    private enum Category {
        APPEARANCE("Appearance", false),
        EDITOR("Editor", false),
        TOOL_WINDOWS("Tool Windows", false),
        SPELL_CHECK("Spell Check", false),
        APPLICATION("Application", false),
        GIT("Git", false),
        KEYMAPS("Keymaps", true),
        PLUGINS("Plugins", true),
        AI("AI", true),
        ADVANCED("Advanced", false);

        final String display;
        final boolean placeholder;

        Category(String display, boolean placeholder) {
            this.display = display;
            this.placeholder = placeholder;
        }
    }

    /** A searchable settings row: its page, its node (hidden when filtered out), and its keywords. */
    private record SettingRow(Category category, Node node, String keywords, Label section) { }

    private final ConfigManager config;
    private final Consumer<Settings> onApply;
    private final Consumer<Boolean> onToggleZen;
    private final Consumer<Path> onOpenFile;
    private final ToolWindowManager toolWindows;
    private final com.editora.git.GitService gitService;
    private final Stage stage = new Stage();

    // --- controls (same set as before, regrouped into pages) ---
    private ComboBox<String> fontFamily;
    private Spinner<Integer> fontSize;
    private ComboBox<String> themeCombo;
    private ComboBox<String> editorThemeCombo;
    private Spinner<Integer> tabSizeSpinner;
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
    private CheckBox gitCheck;
    private Label gitStatusLabel;
    private CheckBox zenCheck;
    private CheckBox projectShowCheck;
    private ComboBox<ToolWindow.Side> projectSideCombo;
    private ToolWindow projectToolWindowRef;
    // The Commit tool-window-placement row, disabled until Git is enabled.
    private CheckBox commitShowCheck;
    private ComboBox<ToolWindow.Side> commitSideCombo;
    private Button commitMoveUp;
    private Button commitMoveDown;
    private ToolWindow commitToolWindowRef;
    private ComboBox<String> autoSaveCombo;
    private Spinner<Integer> autoSaveDelaySpinner;

    // --- shell ---
    private ListView<Category> sidebar;
    private ScrollPane contentScroll;
    private TextField searchField;
    private final Map<Category, Region> pages = new EnumMap<>(Category.class);
    private final List<SettingRow> rows = new ArrayList<>();
    private final List<Label> sectionLabels = new ArrayList<>();
    private final Set<Category> searchHiddenCats = EnumSet.noneOf(Category.class);

    // --- live preview ---
    private CodeArea preview;
    private String currentPreviewCss; // editor-theme override sheet on the settings scene, or null
    private static final String PREVIEW_SAMPLE = """
            public class Greeter {
                // Editora live preview
                public static void main(String[] args) {
                    String name = "world";
                    System.out.println("Hello, " + name + "!");
                }
            }""";

    private boolean built;
    private boolean loading;

    public SettingsWindow(ConfigManager config, ToolWindowManager toolWindows,
                          com.editora.git.GitService gitService,
                          Consumer<Settings> onApply, Consumer<Boolean> onToggleZen,
                          Consumer<Path> onOpenFile) {
        this.config = config;
        this.toolWindows = toolWindows;
        this.gitService = gitService;
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

        buildControls();
        buildPreview();
        buildPages();

        searchField = new TextField();
        searchField.setPromptText("Search settings…");
        searchField.getStyleClass().add("settings-search");
        searchField.textProperty().addListener((o, a, b) -> filter(b));

        sidebar = new ListView<>();
        sidebar.getStyleClass().add("settings-sidebar");
        sidebar.getItems().setAll(Category.values());
        sidebar.setPrefWidth(180);
        sidebar.setMinWidth(180);
        sidebar.setCellFactory(v -> new CategoryCell());
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                contentScroll.setContent(pages.get(b));
            }
        });

        contentScroll = new ScrollPane();
        contentScroll.setFitToWidth(true);
        contentScroll.getStyleClass().add("settings-content");
        HBox.setHgrow(contentScroll, Priority.ALWAYS);

        HBox body = new HBox(sidebar, contentScroll);
        VBox.setVgrow(body, Priority.ALWAYS);

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, searchField, body, buttons);
        root.setPadding(new Insets(12));
        root.setPrefWidth(WIDTH);
        root.setPrefHeight(HEIGHT);

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        // The live preview needs the editor surface + token colors; the dialog controls keep AtlantaFX.
        scene.getStylesheets().addAll(
                SettingsWindow.class.getResource("/com/editora/styles/app.css").toExternalForm(),
                SettingsWindow.class.getResource("/com/editora/styles/syntax.css").toExternalForm());
        stage.setScene(scene);

        sidebar.getSelectionModel().select(Category.APPEARANCE);
    }

    // --- control construction (logic unchanged from the flat window) -----------------------------

    private void buildControls() {
        fontFamily = new ComboBox<>();
        fontFamily.getItems().setAll(fontFamilyChoices());
        fontFamily.setPrefWidth(220);
        fontFamily.valueProperty().addListener((obs, old, now) -> apply());

        fontSize = new Spinner<>(8, 48, 14);
        fontSize.setEditable(true);
        fontSize.setPrefWidth(90);
        fontSize.valueProperty().addListener((obs, old, now) -> apply());
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
            if (now != null) {
                applyPreviewTheme(now); // keep the preview in sync even on programmatic set
            }
            if (loading || now == null) {
                return;
            }
            config.getSettings().setEditorTheme(now);
            config.getSettings().setEditorThemeUserSet(true);
            apply();
        });

        tabSizeSpinner = new Spinner<>(1, 16, 4);
        tabSizeSpinner.setEditable(true);
        tabSizeSpinner.setPrefWidth(90);
        tabSizeSpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setTabSize(now);
            apply();
        });

        columnRulerCheck = viewCheck("Show 80-column ruler", Settings::setShowColumnRuler);
        lineHighlightCheck = viewCheck("Highlight current line", Settings::setHighlightCurrentLine);
        lineNumbersCheck = viewCheck("Show line numbers", Settings::setShowLineNumbers);
        minimapCheck = viewCheck("Show minimap", Settings::setShowMinimap);
        whitespaceCheck = viewCheck("Show hidden characters (spaces, tabs, EOL)", Settings::setShowWhitespace);

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
            @Override public String toString(String id) {
                return id == null ? "" : spellLanguageName(id);
            }
            @Override public String fromString(String s) {
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

        toolbarCheck = viewCheck("Show toolbar", Settings::setShowToolbar);
        statusBarCheck = viewCheck("Show status bar", Settings::setShowStatusBar);
        tabBarCheck = viewCheck("Show tab bar", Settings::setShowTabBar);
        breadcrumbCheck = viewCheck("Show file breadcrumb", Settings::setShowBreadcrumb);

        projectsCheck = new CheckBox("Enable projects");
        projectsCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setProjectSupport(now);
            apply();
            updateProjectRowEnabled();
        });

        gitCheck = new CheckBox("Enable Git");
        gitCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setGitSupport(now);
            apply();
            updateGitRowEnabled(); // reflect on the Tool Windows page's Commit row
        });

        zenCheck = new CheckBox("Zen mode (distraction-free)");
        zenCheck.selectedProperty().addListener((obs, was, now) -> {
            if (loading) {
                return;
            }
            onToggleZen.accept(now);
            syncViewChecks();
        });

        autoSaveCombo = new ComboBox<>();
        autoSaveCombo.getItems().setAll(
                MainController.AUTOSAVE_OFF, MainController.AUTOSAVE_DELAY, MainController.AUTOSAVE_FOCUS);
        autoSaveCombo.setConverter(new StringConverter<>() {
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
    }

    /** A view-toggle checkbox that writes {@code setter} and applies live. */
    private CheckBox viewCheck(String label, java.util.function.BiConsumer<Settings, Boolean> setter) {
        CheckBox check = new CheckBox(label);
        check.selectedProperty().addListener((obs, was, now) -> {
            setter.accept(config.getSettings(), now);
            apply();
        });
        return check;
    }

    // --- pages -----------------------------------------------------------------------------------

    private void buildPages() {
        pages.put(Category.APPEARANCE, appearancePage());
        pages.put(Category.EDITOR, editorPage());
        pages.put(Category.TOOL_WINDOWS, toolWindowsPage());
        pages.put(Category.SPELL_CHECK, spellPage());
        pages.put(Category.APPLICATION, applicationPage());
        pages.put(Category.GIT, gitPage());
        pages.put(Category.ADVANCED, advancedPage());
        for (Category c : Category.values()) {
            if (c.placeholder) {
                pages.put(c, placeholderPage(c.display));
            }
        }
    }

    private VBox appearancePage() {
        VBox p = page("Appearance");
        Label fontNote = note("Only monospaced fonts are listed.");
        VBox fontBox = new VBox(4, fontFamily, fontNote);
        row(p, Category.APPEARANCE, null, labeled("Font family", fontBox), "font family typeface monospace");
        row(p, Category.APPEARANCE, null, labeled("Font size", fontSize), "font size text");
        row(p, Category.APPEARANCE, null, labeled("Theme", themeCombo), "theme appearance dark light app chrome");
        Label etNote = note("Follows the app theme until you pick one.");
        VBox etBox = new VBox(4, editorThemeCombo, etNote);
        row(p, Category.APPEARANCE, null, labeled("Editor theme", etBox),
                "editor theme syntax colors highlighting");
        Label previewSection = section(p, "Live preview");
        row(p, Category.APPEARANCE, previewSection, preview, "preview sample code");
        return p;
    }

    private VBox editorPage() {
        VBox p = page("Editor");
        Label display = section(p, "Display");
        row(p, Category.EDITOR, display, columnRulerCheck, "80 column ruler guide margin");
        row(p, Category.EDITOR, display, lineHighlightCheck, "highlight current line caret");
        row(p, Category.EDITOR, display, lineNumbersCheck, "line numbers gutter");
        row(p, Category.EDITOR, display, minimapCheck, "minimap overview");
        row(p, Category.EDITOR, display, whitespaceCheck, "hidden characters whitespace spaces tabs eol");
        Label indent = section(p, "Indentation");
        row(p, Category.EDITOR, indent, labeled("Tab size", tabSizeSpinner), "tab size indent width spaces");
        Label saving = section(p, "Saving");
        Label delayLabel = note("delay (seconds)");
        HBox autoSaveBox = new HBox(8, autoSaveCombo, autoSaveDelaySpinner, delayLabel);
        autoSaveBox.setAlignment(Pos.CENTER_LEFT);
        row(p, Category.EDITOR, saving, labeled("Auto save", autoSaveBox),
                "auto save autosave delay inactivity focus");
        return p;
    }

    private VBox spellPage() {
        VBox p = page("Spell Check");
        row(p, Category.SPELL_CHECK, null, spellCheckBox, "spell check spelling enable");
        row(p, Category.SPELL_CHECK, null, labeled("Language", spellLanguageCombo),
                "spell language dictionary english spanish french");
        return p;
    }

    private VBox applicationPage() {
        VBox p = page("Application");
        Label chrome = section(p, "Window chrome");
        row(p, Category.APPLICATION, chrome, toolbarCheck, "toolbar buttons");
        row(p, Category.APPLICATION, chrome, statusBarCheck, "status bar");
        row(p, Category.APPLICATION, chrome, tabBarCheck, "tab bar tabs");
        row(p, Category.APPLICATION, chrome, breadcrumbCheck, "breadcrumb file path");
        Label features = section(p, "Features");
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
        row(p, Category.APPLICATION, features, projectsRow, "projects workspace folder");
        row(p, Category.APPLICATION, features, zenCheck, "zen distraction free focus");
        return p;
    }

    private VBox gitPage() {
        VBox p = page("Git");
        gitStatusLabel = new Label("Checking for git…");
        gitStatusLabel.getStyleClass().add("settings-git-status");
        gitStatusLabel.setWrapText(true);
        gitStatusLabel.setMaxWidth(440);
        row(p, Category.GIT, null, gitStatusLabel, "git command found version installed not found");
        row(p, Category.GIT, null, gitCheck, "git version control vcs enable");
        Label hint = note("Uses your installed git. When off, the status-bar branch, the Commit tool "
                + "window, gutter change markers, and Git commands are all hidden.");
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        row(p, Category.GIT, null, hint, "git version control vcs enable");
        return p;
    }

    /**
     * Probes for the {@code git} command off-thread and updates the Git page: shows the version when
     * found (and enables the checkbox), or "not found" + disables the checkbox when git isn't on PATH.
     */
    private void probeGit() {
        if (gitStatusLabel == null || gitService == null) {
            return;
        }
        gitStatusLabel.getStyleClass().setAll("settings-git-status");
        gitStatusLabel.setText("Checking for git…");
        gitService.version(version -> {
            boolean found = version != null && !version.isBlank();
            gitStatusLabel.getStyleClass().setAll("settings-git-status",
                    found ? "settings-git-found" : "settings-git-missing");
            gitStatusLabel.setText(found
                    ? "✓ git command found — " + version
                    : "✗ git command not found — install git to enable Git integration");
            gitCheck.setDisable(!found);
        });
    }

    /** The tool-window placement page: one row per registered tool window (Show / Side / ▲▼ reorder). */
    private VBox toolWindowsPage() {
        VBox p = page("Tool Windows");
        Label hint = note("Use ▲ ▼ (or drag the stripe icons) to reorder. Some windows appear only in context.");
        p.getChildren().add(hint);

        List<Runnable> moveRefreshers = new ArrayList<>();
        Runnable refreshMoves = () -> moveRefreshers.forEach(Runnable::run);
        for (ToolWindow tw : toolWindows.getRegisteredToolWindows()) {
            CheckBox showCheck = new CheckBox("Show");
            showCheck.setSelected(toolWindows.isVisible(tw));

            ComboBox<ToolWindow.Side> sideCombo = new ComboBox<>();
            sideCombo.getItems().setAll(ToolWindow.Side.values());
            sideCombo.setConverter(new StringConverter<>() {
                @Override public String toString(ToolWindow.Side side) {
                    return side == null ? "" : side.name().charAt(0) + side.name().substring(1).toLowerCase();
                }
                @Override public ToolWindow.Side fromString(String s) {
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
                projectShowCheck = showCheck;
                projectSideCombo = sideCombo;
                projectToolWindowRef = tw;
            } else if ("commit".equals(tw.getId())) {
                commitShowCheck = showCheck;
                commitSideCombo = sideCombo;
                commitMoveUp = moveUp;
                commitMoveDown = moveDown;
                commitToolWindowRef = tw;
            }

            Label title = new Label(tw.getTitle());
            title.setMinWidth(130);
            title.setPrefWidth(130);
            HBox reorder = new HBox(2, moveUp, moveDown);
            HBox rowBox = new HBox(10, title, showCheck, sideCombo, reorder);
            rowBox.setAlignment(Pos.CENTER_LEFT);
            row(p, Category.TOOL_WINDOWS, null, rowBox, "tool window " + tw.getTitle() + " placement side show");
        }
        refreshMoves.run();
        updateProjectRowEnabled();
        updateGitRowEnabled();
        return p;
    }

    private VBox advancedPage() {
        VBox p = page("Advanced");
        Label fileSection = section(p, "Settings file");
        Hyperlink link = new Hyperlink(displaySettingsPath(config.getSettingsFile()));
        link.setTooltip(new Tooltip("Open the settings file in Editora"));
        link.setOnAction(e -> {
            if (onOpenFile != null) {
                onOpenFile.accept(config.getSettingsFile());
            }
        });
        HBox fileRow = new HBox(6, new Label("Path:"), link);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        row(p, Category.ADVANCED, fileSection, fileRow, "settings file path toml config location");

        Label resetSection = section(p, "Reset");
        Button reset = new Button("Reset to Defaults");
        reset.setOnAction(e -> resetAll());
        row(p, Category.ADVANCED, resetSection, reset, "reset defaults restore factory clear");

        Label ioSection = section(p, "Import / Export");
        Label io = new Label("Coming soon.");
        io.getStyleClass().add("settings-coming-soon");
        row(p, Category.ADVANCED, ioSection, io, "import export backup settings");
        return p;
    }

    private VBox placeholderPage(String title) {
        VBox p = page(title);
        Label soon = new Label("Coming soon.");
        soon.getStyleClass().add("settings-coming-soon");
        p.getChildren().add(soon);
        return p;
    }

    // --- page helpers ---

    private VBox page(String title) {
        Label heading = new Label(title);
        heading.getStyleClass().add("settings-page-title");
        VBox box = new VBox(10, heading);
        box.getStyleClass().add("settings-page");
        box.setPadding(new Insets(4, 4, 4, 16));
        return box;
    }

    private Label section(VBox page, String name) {
        Label h = new Label(name);
        h.getStyleClass().add("settings-section");
        page.getChildren().add(h);
        sectionLabels.add(h);
        return h;
    }

    private void row(VBox page, Category cat, Label section, Node node, String keywords) {
        page.getChildren().add(node);
        rows.add(new SettingRow(cat, node, keywords, section));
    }

    private Region labeled(String label, Node control) {
        Label l = new Label(label);
        l.setMinWidth(130);
        l.setPrefWidth(130);
        HBox h = new HBox(10, l, control);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-hint");
        return l;
    }

    // --- search ----------------------------------------------------------------------------------

    /** Whether {@code keywords} matches the search {@code query} (case-insensitive substring). Pure. */
    static boolean matches(String query, String keywords) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return keywords != null
                && keywords.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT).strip());
    }

    private void filter(String query) {
        searchHiddenCats.clear();
        boolean searching = query != null && !query.isBlank();
        if (!searching) {
            rows.forEach(r -> setShown(r.node(), true));
            sectionLabels.forEach(s -> setShown(s, true));
            sidebar.refresh();
            return;
        }
        Set<Category> matched = EnumSet.noneOf(Category.class);
        Set<Label> visibleSections = new HashSet<>();
        for (SettingRow r : rows) {
            boolean m = matches(query, r.keywords());
            setShown(r.node(), m);
            if (m) {
                matched.add(r.category());
                if (r.section() != null) {
                    visibleSections.add(r.section());
                }
            }
        }
        sectionLabels.forEach(s -> setShown(s, visibleSections.contains(s)));
        for (Category c : Category.values()) {
            if (c.placeholder || !matched.contains(c)) {
                searchHiddenCats.add(c);
            }
        }
        sidebar.refresh();
        Category sel = sidebar.getSelectionModel().getSelectedItem();
        if (!matched.isEmpty() && (sel == null || !matched.contains(sel))) {
            for (Category c : Category.values()) {
                if (matched.contains(c)) {
                    sidebar.getSelectionModel().select(c);
                    break;
                }
            }
        }
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private final class CategoryCell extends ListCell<Category> {
        @Override
        protected void updateItem(Category item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setDisable(false);
                return;
            }
            setText(item.display);
            setDisable(searchHiddenCats.contains(item));
        }
    }

    // --- live preview ----------------------------------------------------------------------------

    private void buildPreview() {
        preview = new CodeArea(PREVIEW_SAMPLE);
        preview.getStyleClass().add("editor-area");
        preview.setEditable(false);
        preview.setFocusTraversable(false);
        preview.setShowCaret(org.fxmisc.richtext.Caret.CaretVisibility.OFF);
        preview.setPrefHeight(170);
        preview.setMinHeight(170);
        preview.setWrapText(false);
        try {
            IGrammar g = GrammarRegistry.shared().forLanguageName("java");
            if (g != null) {
                StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(PREVIEW_SAMPLE, g);
                preview.setStyleSpans(0, spans);
            }
        } catch (RuntimeException ignored) {
            // Grammar unavailable: the preview still shows the sample in the theme's plain text color.
        }
    }

    /** Swaps the editor-theme override sheet on the settings scene so the preview recolors to {@code name}. */
    private void applyPreviewTheme(String name) {
        if (stage.getScene() == null) {
            return;
        }
        var sheets = stage.getScene().getStylesheets();
        if (currentPreviewCss != null) {
            sheets.remove(currentPreviewCss);
        }
        currentPreviewCss = EditorThemes.stylesheetFor(name);
        if (currentPreviewCss != null && !sheets.contains(currentPreviewCss)) {
            sheets.add(currentPreviewCss);
        }
    }

    private void updatePreviewFont() {
        if (preview == null || fontFamily.getValue() == null || fontSize.getValue() == null) {
            return;
        }
        preview.setStyle("-fx-font-family: \"" + fontFamily.getValue() + "\"; -fx-font-size: "
                + fontSize.getValue() + "px;");
    }

    // --- reset -----------------------------------------------------------------------------------

    private void resetAll() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Reset all settings to their defaults?", ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle("Reset Settings");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        Settings d = new Settings();
        Settings s = config.getSettings();
        resetAppearanceFields(d, s);
        resetEditorFields(d, s);
        resetSpellFields(d, s);
        resetApplicationFields(d, s);
        commitReset();
    }

    private void resetAppearanceFields(Settings d, Settings s) {
        s.setFontFamily(d.getFontFamily());
        s.setFontSize(d.getFontSize());
        s.setTheme(d.getTheme());
        s.setEditorTheme(d.getEditorTheme());
        s.setEditorThemeUserSet(false);
    }

    private void resetEditorFields(Settings d, Settings s) {
        s.setShowColumnRuler(d.isShowColumnRuler());
        s.setHighlightCurrentLine(d.isHighlightCurrentLine());
        s.setShowLineNumbers(d.isShowLineNumbers());
        s.setShowMinimap(d.isShowMinimap());
        s.setShowWhitespace(d.isShowWhitespace());
        s.setTabSize(d.getTabSize());
        s.setAutoSave(d.getAutoSave());
        s.setAutoSaveDelayMillis(d.getAutoSaveDelayMillis());
    }

    private void resetSpellFields(Settings d, Settings s) {
        s.setSpellCheck(d.isSpellCheck());
        s.setSpellLanguage(d.getSpellLanguage());
    }

    private void resetApplicationFields(Settings d, Settings s) {
        s.setShowToolbar(d.isShowToolbar());
        s.setShowStatusBar(d.isShowStatusBar());
        s.setShowTabBar(d.isShowTabBar());
        s.setShowBreadcrumb(d.isShowBreadcrumb());
        s.setProjectSupport(d.isProjectSupport());
        s.setGitSupport(d.isGitSupport());
    }

    /** Persists + applies a reset, re-themes the app, and reloads the controls + preview. */
    private void commitReset() {
        Settings s = config.getSettings();
        config.save();
        javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(s.getTheme()));
        onApply.accept(s);
        load();
    }

    // --- load + sync (unchanged behavior) --------------------------------------------------------

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
            tabSizeSpinner.getValueFactory().setValue(settings.getTabSize());
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
            gitCheck.setSelected(settings.isGitSupport());
            updateGitRowEnabled();
            zenCheck.setSelected(config.getWorkspaceState().isZenMode());
            String mode = MainController.autoSaveModeOf(settings.getAutoSave());
            autoSaveCombo.setValue(mode);
            autoSaveDelaySpinner.getValueFactory()
                    .setValue(Math.max(1, (int) Math.round(settings.getAutoSaveDelayMillis() / 1000.0)));
            autoSaveDelaySpinner.setDisable(!MainController.AUTOSAVE_DELAY.equals(mode));
        } finally {
            loading = false;
        }
        applyPreviewTheme(EditorThemes.normalize(config.getSettings().getEditorTheme()));
        updatePreviewFont();
        probeGit(); // re-check git availability each time Settings opens
    }

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

    /**
     * Disables the Commit tool-window-placement row when Git is off (the window can't be shown until
     * Git is enabled). Unlike the Project row, the Show checkbox value is left untouched — Git's
     * availability is transient, not the user's persisted visibility preference.
     */
    private void updateGitRowEnabled() {
        if (commitShowCheck == null) {
            return;
        }
        boolean on = config.getSettings().isGitSupport();
        commitShowCheck.setDisable(!on);
        if (!on) {
            commitSideCombo.setDisable(true);
            commitMoveUp.setDisable(true);
            commitMoveDown.setDisable(true);
        } else {
            boolean shown = commitShowCheck.isSelected();
            commitSideCombo.setDisable(!shown);
            commitMoveUp.setDisable(!shown || !toolWindows.canMove(commitToolWindowRef, -1));
            commitMoveDown.setDisable(!shown || !toolWindows.canMove(commitToolWindowRef, 1));
        }
    }

    public void syncProjectsCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            projectsCheck.setSelected(config.getSettings().isProjectSupport());
            updateProjectRowEnabled();
        } finally {
            loading = prev;
        }
    }

    public void syncThemes() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            themeCombo.setValue(config.getSettings().getTheme());
            editorThemeCombo.setValue(EditorThemes.normalize(config.getSettings().getEditorTheme()));
        } finally {
            loading = prev;
        }
        applyPreviewTheme(EditorThemes.normalize(config.getSettings().getEditorTheme()));
    }

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

    // --- monospace font discovery ----------------------------------------------------------------

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

    private static List<String> fontFamilyChoices() {
        List<String> choices = new ArrayList<>(Fonts.BUNDLED);
        for (String family : monospaceFamilies()) {
            if (!choices.contains(family)) {
                choices.add(family);
            }
        }
        return choices;
    }

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
        updatePreviewFont();
    }

    /**
     * Shows the About dialog. Shared by the {@code help.about} command and the toolbar About button.
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
