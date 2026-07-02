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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
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

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.SpellDictionaries;
import com.editora.editor.TextMateHighlighter;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;

import static com.editora.i18n.Messages.tr;

/**
 * The Settings window: a left category sidebar + per-category pages, a search box, and a live
 * preview, scalable as Editora grows. Changes are <em>applied live</em> — each control writes its
 * {@link Settings} field and calls {@link #apply()} (persist to {@code settings.toml} + notify the
 * controller), so there is no OK/Cancel; only Reset + Close.
 */
public class SettingsWindow {

    private static final double WIDTH = 989;
    private static final double HEIGHT = 784;

    /** Sidebar group headers; every {@link Category} belongs to exactly one, shown in declaration order. */
    private enum Group {
        GENERAL(tr("settings.group.general")),
        EDITOR(tr("settings.group.editor")),
        LANGUAGES_TOOLS(tr("settings.group.languagesTools")),
        VERSION_CONTROL(tr("settings.group.versionControl")),
        SYSTEM(tr("settings.group.system"));

        final String display;

        Group(String display) {
            this.display = display;
        }
    }

    /** Settings categories shown in the sidebar, each grouped under a {@link Group} header (declaration order). */
    private enum Category {
        // General
        APPEARANCE(tr("settings.cat.appearance"), Group.GENERAL),
        INTERFACE(tr("settings.cat.interface"), Group.GENERAL),
        WORKSPACE(tr("settings.cat.workspace"), Group.GENERAL),
        TOOL_WINDOWS(tr("settings.cat.toolWindows"), Group.GENERAL),
        // Editor
        EDITOR(tr("settings.cat.editor"), Group.EDITOR),
        COMPLETION(tr("settings.cat.completion"), Group.EDITOR),
        SNIPPETS(tr("settings.cat.snippets"), Group.EDITOR),
        TEMPLATES(tr("settings.cat.templates"), Group.EDITOR, true),
        TODO(tr("settings.cat.todo"), Group.EDITOR),
        SPELL_CHECK(tr("settings.cat.spellCheck"), Group.EDITOR),
        SEARCH(tr("settings.cat.search"), Group.EDITOR),
        // Languages & Tools
        LSP(tr("settings.cat.lsp"), Group.LANGUAGES_TOOLS, true),
        DEBUG(tr("settings.cat.debug"), Group.LANGUAGES_TOOLS, true),
        MARKDOWN(tr("settings.cat.markdown"), Group.LANGUAGES_TOOLS),
        MERMAID(tr("settings.cat.mermaid"), Group.LANGUAGES_TOOLS),
        WEB(tr("settings.cat.web"), Group.LANGUAGES_TOOLS, true),
        EXTERNAL_TOOLS(tr("settings.cat.externalTools"), Group.LANGUAGES_TOOLS),
        // Version control
        GIT(tr("settings.cat.git"), Group.VERSION_CONTROL, true),
        // System
        KEYMAPS(tr("settings.cat.keymaps"), Group.SYSTEM),
        MACROS(tr("settings.cat.macros"), Group.SYSTEM),
        REMOTE(tr("settings.cat.remote"), Group.SYSTEM, true),
        PLUGINS(tr("settings.cat.plugins"), Group.SYSTEM),
        MCP(tr("settings.cat.mcp"), Group.SYSTEM, true),
        ADVANCED(tr("settings.cat.advanced"), Group.SYSTEM);

        final String display;
        final Group group;
        /** Whether the feature is still beta — the sidebar shows a small "Beta" pill beside its name. */
        final boolean beta;

        Category(String display, Group group) {
            this(display, group, false);
        }

        Category(String display, Group group, boolean beta) {
            this.display = display;
            this.group = group;
            this.beta = beta;
        }
    }

    /** A searchable settings row: its page, its node (hidden when filtered out), and its keywords. */
    private record SettingRow(Category category, Node node, String keywords, Label section) {}

    private final ConfigManager config;
    private final Consumer<Settings> onApply;
    private final Consumer<Boolean> onToggleZen;
    private final Consumer<Path> onOpenFile;
    private final Runnable onExportConfig;
    private final Runnable onShowDebugLog;
    /** Spell Check page: open the bundled technical dictionary (read-only) / the personal dictionary file. */
    private Runnable onOpenTechnicalDictionary;

    private Runnable onOpenPersonalDictionary;
    /** Security-notice confirm shown before the MCP checkbox enables the server; null = no gate. */
    private java.util.function.BooleanSupplier mcpConfirm;

    private final ToolWindowManager toolWindows;
    private final com.editora.git.GitService gitService;
    private final com.editora.mermaid.MermaidService mermaidService;
    private final com.editora.lsp.LspManager lspManager;
    private final com.editora.dap.DapManager dapManager;
    private final Stage stage = new Stage();

    // --- controls (same set as before, regrouped into pages) ---
    private ComboBox<String> languageCombo;
    private ComboBox<String> keymapCombo;
    private ShortcutActions shortcutActions; // keybinding-editor backend (→ MainController)
    private TextField shortcutFilter; // filters the shortcut list
    private VBox shortcutListBox; // rebuilt from shortcutActions.rows() on each change/filter
    private String recordingCommandId; // command id whose row is currently capturing a chord, or null
    private String selectedShortcutId; // command id of the selected row (shows its Record/Reset), or null
    private ComboBox<String> fontFamily;
    private Spinner<Integer> fontSize;
    private ComboBox<String> themeCombo;
    private ComboBox<String> editorThemeCombo;
    private Spinner<Integer> tabSizeSpinner;
    private Spinner<Integer> fillColumnSpinner;
    private Spinner<Integer> largeFileThresholdSpinner;
    private ComboBox<String> indentStyleCombo;
    private CheckBox columnRulerCheck;
    private CheckBox lineHighlightCheck;
    private CheckBox lineNumbersCheck;
    private CheckBox minimapCheck;
    private CheckBox wordWrapCheck;
    private CheckBox adminSaveCheck;
    private CheckBox whitespaceCheck;
    private CheckBox notesCheck;
    private CheckBox noteIndicatorsCheck;
    private CheckBox autocompleteCheck;
    private CheckBox autocompleteProseCheck;
    private CheckBox autocompleteSnippetsCheck;
    private CheckBox autocompleteMermaidCheck;
    private CheckBox completionDocCheck;
    private CheckBox semanticHighlightCheck;
    private CheckBox spellCheckBox;
    private ComboBox<String> spellLanguageCombo;
    /** The Personal Dictionary list on the Spell Check page; refreshed from {@code dictionary.txt} on show. */
    private ListView<String> dictionaryList;
    /** "Enable personal dictionary" checkbox (Settings.personalDictionary). */
    private CheckBox dictEnableCheck;
    /** "Enable technical dictionary" checkbox (Settings.technicalDictionary). */
    private CheckBox techDictEnableCheck;

    private CheckBox toolbarCheck;
    private CheckBox statusBarCheck;
    private CheckBox tabBarCheck;
    private CheckBox breadcrumbCheck;
    private CheckBox simpleModeCheck;
    private CheckBox toolStripeCheck;
    private CheckBox projectHiddenCheck;
    private CheckBox markdownFormatBarCheck;
    private CheckBox lspInstallPromptsCheck;
    private CheckBox markdownLintCheck;
    private CheckBox mathSupportCheck;
    private CheckBox editorConfigCheck;
    private CheckBox logViewerCheck;
    private CheckBox csvGridCheck;
    private CheckBox todoHighlightCheck;
    private javafx.scene.layout.VBox todoPatternsBox;
    private VBox markdownLintRulesBox;
    /** Working copy of the External Tools list, edited live by the master-detail page. */
    private final javafx.collections.ObservableList<com.editora.externaltool.ExternalTool> externalToolItems =
            javafx.collections.FXCollections.observableArrayList();

    private boolean loadingExternalTool = false;

    /** Working copy of the saved SFTP connections, edited live by the Remote master-detail page. */
    private final javafx.collections.ObservableList<com.editora.vfs.RemoteConnection> remoteItems =
            javafx.collections.FXCollections.observableArrayList();

    private boolean loadingRemote = false;

    /** Working copies for the Macros master-detail page. */
    private final javafx.collections.ObservableList<com.editora.macro.Macro> macroItems =
            javafx.collections.FXCollections.observableArrayList();

    private final javafx.collections.ObservableList<com.editora.macro.MacroStep> macroStepItems =
            javafx.collections.FXCollections.observableArrayList();
    private boolean loadingMacro = false;
    private String macroOriginalName; // the saved name of the selected macro (to detect rename)
    /** Re-registers the {@code macro.run.*} commands across windows after a Macros-page edit. */
    private Runnable onMacrosChanged = () -> {};
    /** Shared snippet manager (injected after construction); backs the Snippets management page. */
    private com.editora.snippet.SnippetManager snippetManager;
    /** Working copy of the snippets (bundled + user) for the language selected on the Snippets page. */
    private final javafx.collections.ObservableList<com.editora.snippet.Snippet> snippetItems =
            javafx.collections.FXCollections.observableArrayList();
    /** Names of the shown snippets that are user-owned (a user file entry or an override of a bundled one);
     *  the rest are read-only bundled snippets. Only these are written back to {@code <lang>.json}. */
    private final java.util.Set<String> snippetUserNames = new java.util.HashSet<>();

    private boolean loadingSnippet = false;
    private String currentSnippetLang = "global";
    /** Shared template registry (injected after construction); backs the Templates management page. */
    private com.editora.template.TemplateRegistry templateRegistry;
    /** Working copy of the templates (bundled + user) shown on the Templates page. */
    private final javafx.collections.ObservableList<com.editora.template.Template> templateItems =
            javafx.collections.FXCollections.observableArrayList();
    /** Ids of the shown templates that are user-owned (writable / removable); the rest are read-only bundled. */
    private final java.util.Set<String> templateUserIds = new java.util.HashSet<>();

    private boolean loadingTemplate = false;
    private CheckBox multiCaretCheck;
    private CheckBox projectsCheck;
    private CheckBox gitCheck;
    private CheckBox blameCheck;
    private CheckBox localHistoryCheck;
    private Spinner<Integer> historyMaxPerFileSpinner;
    private Spinner<Integer> historyMaxAgeSpinner;
    private Spinner<Integer> historyMaxTotalSpinner;
    private Label gitStatusLabel;
    private CheckBox mermaidCheck;
    private CheckBox httpCheck;
    private CheckBox htmlPreviewCheck;
    private CheckBox mcpCheck;
    private TextField mmdcPathField;
    private CheckBox debugCheck;
    /** Per-language debug-adapter controls, keyed by language id (java/python/javascript). */
    private final java.util.Map<String, CheckBox> debugEnableChecks = new java.util.LinkedHashMap<>();

    private final java.util.Map<String, TextField> debugCommandFields = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Label> debugStatusLabels = new java.util.LinkedHashMap<>();
    private TextField maidPathField;
    private TextField templateAuthorField;
    private Label mermaidStatusLabel;
    private CheckBox ripgrepCheck;
    private CheckBox searchGitignoreCheck;
    private TextField ripgrepCommandField;
    private Label ripgrepStatusLabel;
    /** Injected probe (MainController): runs {@code rg --version} off-thread, delivers found/not-found on FX. */
    private java.util.function.Consumer<java.util.function.Consumer<Boolean>> ripgrepProbe;

    private com.editora.plugin.PluginManager pluginManager; // shared, injected after construction
    private CheckBox pluginCheck;
    private CheckBox pluginRequireSigCheck;
    private VBox pluginListBox; // rebuilt on each load() from the shared PluginManager's descriptors
    private TextField pluginRegistryField;
    private Label pluginRegistryWarn; // shown when the registry URL isn't the trusted default
    private Runnable onKeymapChanged; // → MainController: reload the shared keymap live
    private Runnable onBrowsePlugins; // → MainController.browsePlugins
    private Runnable onInstallPluginFromFile; // → MainController.installPluginFromDisk
    private Consumer<String> onUninstallPlugin; // id → MainController.uninstallPlugin
    private CheckBox lspCheck;
    /** Per-server LSP controls, keyed by server id (data-driven so adding a server is one descriptor). */
    private final java.util.Map<String, CheckBox> lspEnableChecks = new java.util.LinkedHashMap<>();

    private final java.util.Map<String, TextField> lspCommandFields = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Label> lspStatusLabels = new java.util.LinkedHashMap<>();
    /** Install buttons keyed by install-language ({@code java}/{@code python}/{@code javascript}/{@code mermaid}). */
    private final java.util.Map<String, Button> installButtons = new java.util.HashMap<>();
    /** Injected by MainController: runs {@code InstallCoordinator.installSupport} for the given language key. */
    private java.util.function.Consumer<String> onInstallSupport;
    /** Injected by MainController: runs {@code InstallCoordinator.installServer} for the given LSP server id. */
    private java.util.function.Consumer<String> onInstallServer;

    private CheckBox zenCheck;
    private CheckBox projectShowCheck;
    private ComboBox<ToolWindow.Side> projectSideCombo;
    private ToolWindow projectToolWindowRef;
    private Label projectDisabledNote;
    // The Commit tool-window-placement row, disabled until Git is enabled.
    private CheckBox commitShowCheck;
    private ComboBox<ToolWindow.Side> commitSideCombo;
    private Button commitMoveUp;
    private Button commitMoveDown;
    private ToolWindow commitToolWindowRef;
    private Label commitDisabledNote;
    // The Personal Notes tool-window-placement row, disabled until Personal Notes is enabled.
    private CheckBox notesShowCheck;
    private ComboBox<ToolWindow.Side> notesSideCombo;
    private Button notesMoveUp;
    private Button notesMoveDown;
    private ToolWindow notesToolWindowRef;
    private Label notesDisabledNote;
    // The Problems / Run / Debug rows, disabled until the LSP feature is on (Debug also needs Debugging on).
    private CheckBox problemsShowCheck;
    private ComboBox<ToolWindow.Side> problemsSideCombo;
    private Button problemsMoveUp;
    private Button problemsMoveDown;
    private ToolWindow problemsToolWindowRef;
    private Label problemsDisabledNote;
    private CheckBox runShowCheck;
    private ComboBox<ToolWindow.Side> runSideCombo;
    private Button runMoveUp;
    private Button runMoveDown;
    private ToolWindow runToolWindowRef;
    private Label runDisabledNote;
    private CheckBox debugShowCheck;
    private ComboBox<ToolWindow.Side> debugSideCombo;
    private Button debugMoveUp;
    private Button debugMoveDown;
    private ToolWindow debugToolWindowRef;
    private Label debugDisabledNote;
    private ComboBox<String> autoSaveCombo;
    private CheckBox pdfLineNumbersCheck;
    private CheckBox pdfHighlightCheck;
    private ComboBox<String> pdfPageSizeCombo;
    private Spinner<Integer> autoSaveDelaySpinner;

    // --- shell ---
    private ListView<Object> sidebar; // mixed rows: Group headers + Category items
    private ScrollPane contentScroll;
    private TextField searchField;
    private final Map<Category, Region> pages = new EnumMap<>(Category.class);
    private final List<SettingRow> rows = new ArrayList<>();
    private final List<Label> sectionLabels = new ArrayList<>();
    private final Set<Category> searchHiddenCats = EnumSet.noneOf(Category.class);
    private final Set<Group> searchHiddenGroups = EnumSet.noneOf(Group.class);

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

    public SettingsWindow(
            ConfigManager config,
            ToolWindowManager toolWindows,
            com.editora.git.GitService gitService,
            com.editora.mermaid.MermaidService mermaidService,
            com.editora.lsp.LspManager lspManager,
            com.editora.dap.DapManager dapManager,
            Consumer<Settings> onApply,
            Consumer<Boolean> onToggleZen,
            Consumer<Path> onOpenFile,
            Runnable onExportConfig,
            Runnable onShowDebugLog) {
        this.config = config;
        this.toolWindows = toolWindows;
        this.gitService = gitService;
        this.mermaidService = mermaidService;
        this.lspManager = lspManager;
        this.dapManager = dapManager;
        this.onApply = onApply;
        this.onToggleZen = onToggleZen;
        this.onOpenFile = onOpenFile;
        this.onExportConfig = onExportConfig;
        this.onShowDebugLog = onShowDebugLog;
    }

    /**
     * Injects the shared {@link com.editora.plugin.PluginManager} (set after construction by
     * {@code MainController}). The Plugins page lists its discovered descriptors; safe to call before
     * {@link #build} (the page is built lazily on first {@link #show}).
     */
    public void setPluginManager(com.editora.plugin.PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /** Injects the shared {@link com.editora.snippet.SnippetManager} backing the Snippets management page. */
    public void setSnippetManager(com.editora.snippet.SnippetManager snippetManager) {
        this.snippetManager = snippetManager;
    }

    /** Injects the Spell Check page's "open dictionary file" actions (bundled technical / personal). */
    public void setDictionaryActions(Runnable openTechnical, Runnable openPersonal) {
        this.onOpenTechnicalDictionary = openTechnical;
        this.onOpenPersonalDictionary = openPersonal;
    }

    /** Opens Settings focused on the Snippets page (the {@code snippets.manage} command). */
    public void showSnippets(Window owner) {
        show(owner);
        sidebar.getSelectionModel().select(Category.SNIPPETS);
    }

    /** Injects the shared {@link com.editora.template.TemplateRegistry} backing the Templates page. */
    public void setTemplateRegistry(com.editora.template.TemplateRegistry registry) {
        this.templateRegistry = registry;
    }

    /** Opens Settings focused on the Templates page (the {@code template.manage} command). */
    public void showTemplates(Window owner) {
        show(owner);
        sidebar.getSelectionModel().select(Category.TEMPLATES);
    }

    /** Wires the Plugins-page actions to the controller (browse registry / install zip / uninstall). */
    public void setPluginActions(Runnable onBrowse, Runnable onInstallFromFile, Consumer<String> onUninstall) {
        this.onBrowsePlugins = onBrowse;
        this.onInstallPluginFromFile = onInstallFromFile;
        this.onUninstallPlugin = onUninstall;
    }

    /** Wires the security-notice confirm shown before the MCP checkbox enables the server. */
    public void setMcpConfirm(java.util.function.BooleanSupplier confirm) {
        this.mcpConfirm = confirm;
    }

    /** Wires the Settings Install buttons (LSP/Mermaid pages) to {@code InstallCoordinator.installSupport}.
     *  The argument is the install-language key: {@code java}/{@code python}/{@code javascript}/{@code mermaid}. */
    public void setInstallActions(java.util.function.Consumer<String> onInstallSupport) {
        this.onInstallSupport = onInstallSupport;
    }

    /** Wires the per-LSP-server Install buttons (json/bash/yaml/…) to {@code InstallCoordinator.installServer}. */
    public void setInstallServerActions(java.util.function.Consumer<String> onInstallServer) {
        this.onInstallServer = onInstallServer;
    }

    /** Re-probes tool detection on the LSP/Debugger/Mermaid pages (labels + Install buttons) — called by
     *  MainController after an in-app install completes so the now-installed tools flip to "Installed". */
    public void refreshDetectionStatus() {
        refreshLspStatus();
        refreshDebugStatus();
        refreshMermaidStatus();
    }

    /** The install-language key for an installable LSP server row, or {@code null} (no installer). */
    private static String installLangForServer(String serverId) {
        return switch (serverId) {
            case "java" -> "java";
            case "python" -> "python";
            case "typescript" -> "javascript";
            default -> null;
        };
    }

    /** A small "Install…" button for {@code langKey}, wired to the injected install action + tracked for
     *  enable/disable from the detection refresh. */
    private Button installButton(String langKey) {
        Button b = new Button(tr("settings.install.button"));
        b.getStyleClass().add("settings-install-button");
        b.setOnAction(e -> {
            if (onInstallSupport != null) {
                onInstallSupport.accept(langKey);
            }
        });
        installButtons.put(langKey, b);
        return b;
    }

    /** A per-LSP-server "Install…" button (json/bash/yaml/…), tracked under its server id. */
    private Button installServerButton(String serverId) {
        Button b = new Button(tr("settings.install.button"));
        b.getStyleClass().add("settings-install-button");
        b.setOnAction(e -> {
            if (onInstallServer != null) {
                onInstallServer.accept(serverId);
            }
        });
        installButtons.put(serverId, b);
        return b;
    }

    /** Reflects a tool's detected state on its Install button: disabled + "Installed" when present. */
    private void updateInstallButton(String langKey, boolean installed) {
        Button b = langKey == null ? null : installButtons.get(langKey);
        if (b != null) {
            b.setDisable(installed);
            b.setText(installed ? tr("settings.install.installed") : tr("settings.install.button"));
        }
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
        stage.setTitle(tr("settings.window.title"));
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);

        buildControls();
        buildPreview();
        buildPages();

        searchField = new TextField();
        searchField.setPromptText(tr("settings.search.prompt"));
        searchField.getStyleClass().add("settings-search");
        searchField.textProperty().addListener((o, a, b) -> filter(b));

        sidebar = new ListView<>();
        sidebar.getStyleClass().add("settings-sidebar");
        sidebar.getItems().setAll(sidebarItems());
        sidebar.setPrefWidth(190);
        sidebar.setMinWidth(190);
        sidebar.setCellFactory(v -> new CategoryCell());
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b instanceof Category cat) { // group headers aren't pages
                contentScroll.setContent(pages.get(cat));
            }
        });

        contentScroll = new ScrollPane();
        contentScroll.setFitToWidth(true);
        contentScroll.getStyleClass().add("settings-content");
        HBox.setHgrow(contentScroll, Priority.ALWAYS);

        HBox body = new HBox(sidebar, contentScroll);
        VBox.setVgrow(body, Priority.ALWAYS);

        Button close = new Button(tr("settings.close"));
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
        scene.getStylesheets()
                .addAll(
                        SettingsWindow.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm(),
                        SettingsWindow.class
                                .getResource("/com/editora/styles/syntax.css")
                                .toExternalForm());
        stage.setScene(scene);
        // The content scroll pane is fit-to-width (no horizontal scrollbar), so a too-narrow window would
        // clip the wider rows (label + spinner + unit) with no way to read them. Floor the window size.
        stage.setMinWidth(720);
        stage.setMinHeight(480);

        sidebar.getSelectionModel().select(Category.APPEARANCE);
    }

    // --- control construction (logic unchanged from the flat window) -----------------------------

    private void buildControls() {
        languageCombo = new ComboBox<>();
        languageCombo.getItems().add(""); // "" = automatic (system language)
        languageCombo.getItems().addAll(com.editora.i18n.Messages.available().keySet());
        languageCombo.setPrefWidth(220);
        languageCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String code) {
                return code == null || code.isEmpty()
                        ? tr("settings.language.auto")
                        : com.editora.i18n.Messages.languageName(code);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        languageCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setUiLanguage(now);
            config.save();
            Alert restart = new Alert(Alert.AlertType.INFORMATION, tr("dialog.language.restart"), ButtonType.OK);
            restart.initOwner(stage);
            restart.setTitle(tr("dialog.language.title"));
            restart.setHeaderText(null);
            restart.showAndWait();
        });

        keymapCombo = new ComboBox<>();
        keymapCombo.getItems().addAll(com.editora.command.KeymapManager.AVAILABLE.keySet());
        keymapCombo.setPrefWidth(220);
        keymapCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : com.editora.command.KeymapManager.displayName(id);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        keymapCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setKeymap(now);
            config.save();
            if (onKeymapChanged != null) {
                onKeymapChanged.run(); // reload the shared keymap live across all windows
            }
        });

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
        themeCombo.getItems().setAll(Themes.names());
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
        editorThemeCombo.getItems().setAll(EditorThemes.names());
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

        fillColumnSpinner = new Spinner<>(20, 200, com.editora.editops.Filler.DEFAULT_FILL_COLUMN);
        fillColumnSpinner.setEditable(true);
        fillColumnSpinner.setPrefWidth(90);
        fillColumnSpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setFillColumn(now);
            apply();
        });

        // Line count above which the minimap + LSP auto-disable (highlighting + editing stay); 0 = never.
        largeFileThresholdSpinner = new Spinner<>(0, 10_000_000, 10_000, 1000);
        largeFileThresholdSpinner.setEditable(true);
        largeFileThresholdSpinner.setPrefWidth(120);
        largeFileThresholdSpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setLargeFileThreshold(now);
            apply();
        });

        columnRulerCheck = viewCheck(tr("settings.showRuler"), Settings::setShowColumnRuler);
        lineHighlightCheck = viewCheck(tr("settings.highlightLine"), Settings::setHighlightCurrentLine);
        lineNumbersCheck = viewCheck(tr("settings.showLineNumbers"), Settings::setShowLineNumbers);
        minimapCheck = viewCheck(tr("settings.showMinimap"), Settings::setShowMinimap);
        wordWrapCheck = viewCheck(tr("settings.wordWrap"), Settings::setWordWrap);
        adminSaveCheck = viewCheck(tr("settings.adminSave"), Settings::setAdminSave);
        whitespaceCheck = viewCheck(tr("settings.showWhitespace"), Settings::setShowWhitespace);
        notesCheck = viewCheck(tr("settings.enableNotes"), Settings::setNotesSupport);
        noteIndicatorsCheck = viewCheck(tr("settings.showNoteIndicators"), Settings::setShowNoteIndicators);
        // The note-indicator toggle is only meaningful while Personal Notes is enabled.
        notesCheck.selectedProperty().addListener((obs, was, now) -> {
            noteIndicatorsCheck.setDisable(!now);
            updateNotesRowEnabled(); // reflect on the Tool Windows page's Personal Notes row
        });
        autocompleteCheck = viewCheck(tr("settings.enableAutocomplete"), Settings::setAutocomplete);
        autocompleteProseCheck = viewCheck(tr("settings.autocomplete.prose"), Settings::setAutocompleteProse);
        autocompleteSnippetsCheck = viewCheck(tr("settings.autocomplete.snippets"), Settings::setAutocompleteSnippets);
        autocompleteMermaidCheck = viewCheck(tr("settings.autocomplete.mermaid"), Settings::setAutocompleteMermaid);
        completionDocCheck = viewCheck(tr("settings.completionDoc"), Settings::setCompletionDoc);
        semanticHighlightCheck = viewCheck(tr("settings.semanticHighlight"), Settings::setSemanticHighlight);
        // The per-source toggles are only meaningful while the master switch is on.
        autocompleteCheck.selectedProperty().addListener((obs, was, now) -> {
            autocompleteProseCheck.setDisable(!now);
            autocompleteSnippetsCheck.setDisable(!now);
            autocompleteMermaidCheck.setDisable(!now);
        });

        pdfLineNumbersCheck = viewCheck(tr("settings.pdf.lineNumbers"), Settings::setPdfLineNumbers);
        pdfHighlightCheck = viewCheck(tr("settings.pdf.highlight"), Settings::setPdfSyntaxHighlighting);
        indentStyleCombo = new ComboBox<>();
        indentStyleCombo.getItems().setAll("detect", "space", "tab");
        indentStyleCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String key) {
                return indentStyleName(key);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        indentStyleCombo.setPrefWidth(170);
        indentStyleCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setIndentStyle(now);
            apply();
        });

        pdfPageSizeCombo = new ComboBox<>();
        pdfPageSizeCombo.getItems().setAll("letter", "a4");
        pdfPageSizeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String key) {
                return "a4".equals(key) ? tr("settings.pdf.pageSize.a4") : tr("settings.pdf.pageSize.letter");
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        pdfPageSizeCombo.setPrefWidth(170);
        pdfPageSizeCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setPdfPageSize(now);
            apply();
        });

        spellCheckBox = new CheckBox(tr("settings.enableSpell"));
        spellCheckBox.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setSpellCheck(now);
            if (spellLanguageCombo != null) {
                spellLanguageCombo.setDisable(!now);
            }
            apply();
        });
        dictEnableCheck = new CheckBox(tr("settings.dict.enable"));
        dictEnableCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setPersonalDictionary(now);
            apply();
        });
        techDictEnableCheck = new CheckBox(tr("settings.dict.technical"));
        techDictEnableCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setTechnicalDictionary(now);
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

        toolbarCheck = viewCheck(tr("settings.showToolbar"), Settings::setShowToolbar);
        statusBarCheck = viewCheck(tr("settings.showStatusBar"), Settings::setShowStatusBar);
        tabBarCheck = viewCheck(tr("settings.showTabBar"), Settings::setShowTabBar);
        breadcrumbCheck = viewCheck(tr("settings.showBreadcrumb"), Settings::setShowBreadcrumb);
        simpleModeCheck = viewCheck(tr("settings.simpleMode"), Settings::setSimpleMode);
        toolStripeCheck = viewCheck(tr("settings.showToolStripe"), Settings::setShowToolStripe);
        projectHiddenCheck = viewCheck(tr("settings.projectShowHidden"), Settings::setProjectShowHidden);
        markdownFormatBarCheck = viewCheck(tr("settings.markdownFormatBar"), Settings::setMarkdownFormatBar);
        lspInstallPromptsCheck = viewCheck(tr("settings.lspInstallPrompts"), Settings::setLspInstallPrompts);
        markdownLintCheck = viewCheck(tr("settings.markdownLint"), Settings::setMarkdownLint);
        mathSupportCheck = viewCheck(tr("settings.mathSupport"), Settings::setMathSupport);
        editorConfigCheck = viewCheck(tr("settings.enableEditorConfig"), Settings::setEditorConfigSupport);
        logViewerCheck = viewCheck(tr("settings.logViewer"), Settings::setLogViewer);
        csvGridCheck = viewCheck(tr("settings.csvPreview"), Settings::setCsvPreview);
        todoHighlightCheck = viewCheck(tr("settings.todoHighlight"), Settings::setTodoHighlight);
        multiCaretCheck = viewCheck(tr("settings.multiCaret"), Settings::setMultiCaret);

        projectsCheck = new CheckBox(tr("settings.enableProjects"));
        projectsCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setProjectSupport(now);
            apply();
            updateProjectRowEnabled();
        });

        gitCheck = new CheckBox(tr("settings.enableGit"));
        gitCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setGitSupport(now);
            apply();
            updateGitRowEnabled(); // reflect on the Tool Windows page's Commit row
            blameCheck.setDisable(!now); // inline blame only matters when Git is on
        });

        blameCheck = new CheckBox(tr("settings.git.blameInline"));
        blameCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setGitBlameInline(now);
            apply();
        });

        localHistoryCheck = new CheckBox(tr("settings.enableLocalHistory"));
        historyMaxPerFileSpinner = historySpinner(1, 1000, 50, Settings::setHistoryMaxPerFile);
        historyMaxAgeSpinner = historySpinner(0, 3650, 30, Settings::setHistoryMaxAgeDays);
        historyMaxTotalSpinner = historySpinner(1, 5000, 50, Settings::setHistoryMaxTotalMb);
        localHistoryCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setLocalHistory(now);
            updateHistoryRowsEnabled();
            apply();
        });

        mermaidCheck = new CheckBox(tr("settings.enableMermaid"));
        mermaidCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setMermaidSupport(now);
            apply();
            refreshMermaidStatus();
        });
        mmdcPathField = new TextField();
        mmdcPathField.setPromptText("mmdc");
        mmdcPathField.textProperty().addListener((obs, was, now) -> {
            config.getSettings().setMmdcPath(now);
            apply();
            refreshMermaidStatus();
        });
        maidPathField = new TextField();
        maidPathField.setPromptText(com.editora.mermaid.MermaidService.DEFAULT_MAID);
        maidPathField.textProperty().addListener((obs, was, now) -> {
            config.getSettings().setMaidPath(now);
            apply();
            refreshMermaidStatus();
        });

        ripgrepCheck = new CheckBox(tr("settings.search.useRipgrep"));
        ripgrepCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setRipgrepSearch(now);
            apply();
            refreshRipgrepStatus();
        });
        searchGitignoreCheck = new CheckBox(tr("settings.search.gitignore"));
        searchGitignoreCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setSearchRespectGitignore(now);
            apply();
        });
        ripgrepCommandField = new TextField();
        ripgrepCommandField.setPromptText(com.editora.search.Ripgrep.DEFAULT_COMMAND);
        ripgrepCommandField.textProperty().addListener((obs, was, now) -> {
            config.getSettings().setRipgrepCommand(now);
            apply();
            refreshRipgrepStatus();
        });

        httpCheck = new CheckBox(tr("settings.httpClient.enable"));
        httpCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setHttpClientSupport(now);
            apply();
        });

        htmlPreviewCheck = new CheckBox(tr("settings.htmlPreview.enable"));
        htmlPreviewCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setHtmlPreviewSupport(now);
            apply();
        });

        mcpCheck = new CheckBox(tr("settings.mcp.enable"));
        mcpCheck.selectedProperty().addListener((obs, was, now) -> {
            // A user-initiated enable shows a security notice first; declining reverts the checkbox.
            if (!loading && now && mcpConfirm != null && !mcpConfirm.getAsBoolean()) {
                boolean prev = loading;
                loading = true;
                try {
                    mcpCheck.setSelected(false);
                } finally {
                    loading = prev;
                }
                return;
            }
            config.getSettings().setMcpSupport(now);
            apply();
        });

        pluginCheck = new CheckBox(tr("settings.enablePlugins"));
        pluginCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setPluginSupport(now);
            apply();
        });
        pluginRequireSigCheck = new CheckBox(tr("settings.plugins.requireSignature"));
        pluginRequireSigCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setPluginRequireSignature(now);
            apply();
        });

        templateAuthorField = new TextField();
        templateAuthorField.setPromptText(System.getProperty("user.name", ""));
        templateAuthorField.textProperty().addListener((obs, was, now) -> {
            config.getSettings().setAuthorName(now);
            apply();
        });

        debugCheck = new CheckBox(tr("settings.enableDebug"));
        debugCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDebugSupport(now);
            apply();
            updateDebugRowsEnabled();
            updateLspToolRowsEnabled(); // reflect on the Tool Windows page's Debug row
            refreshDebugStatus();
        });
        for (DebugAdapterUi dbg : debugAdapterUis()) {
            if (dbg.setEnabled() != null) {
                CheckBox enable = new CheckBox(tr(dbg.enableLabelKey()));
                enable.selectedProperty().addListener((obs, was, now) -> {
                    dbg.setEnabled().accept(now);
                    apply();
                    refreshDebugStatus();
                });
                debugEnableChecks.put(dbg.id(), enable);
            }
            TextField field = new TextField();
            field.setPromptText(dbg.commandPrompt());
            field.textProperty().addListener((obs, was, now) -> {
                dbg.setCommand().accept(now);
                apply();
                refreshDebugStatus();
            });
            debugCommandFields.put(dbg.id(), field);
        }

        lspCheck = new CheckBox(tr("settings.enableLsp"));
        lspCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setLspSupport(now);
            apply();
            updateLspRowsEnabled();
            updateLspToolRowsEnabled(); // reflect on the Tool Windows page's Problems/Run/Debug rows
            refreshLspStatus();
        });
        for (LspServerUi srv : lspServerUis()) {
            CheckBox enable = new CheckBox(tr(srv.enableLabelKey()));
            enable.selectedProperty().addListener((obs, was, now) -> {
                srv.setEnabled().accept(now);
                apply();
            });
            TextField field = new TextField();
            field.setPromptText(srv.defaultCommand());
            field.textProperty().addListener((obs, was, now) -> {
                srv.setCommand().accept(now);
                apply();
                refreshLspStatus();
            });
            lspEnableChecks.put(srv.id(), enable);
            lspCommandFields.put(srv.id(), field);
        }

        zenCheck = new CheckBox(tr("settings.zen"));
        zenCheck.selectedProperty().addListener((obs, was, now) -> {
            if (loading) {
                return;
            }
            onToggleZen.accept(now);
            syncViewChecks();
        });

        autoSaveCombo = new ComboBox<>();
        autoSaveCombo
                .getItems()
                .setAll(MainController.AUTOSAVE_OFF, MainController.AUTOSAVE_DELAY, MainController.AUTOSAVE_FOCUS);
        autoSaveCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String key) {
                return key == null ? "" : MainController.autoSaveLabel(key);
            }

            @Override
            public String fromString(String label) {
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
    /** A small editable int spinner that writes {@code setter} + re-applies (skipping the loading phase). */
    private Spinner<Integer> historySpinner(
            int min, int max, int def, java.util.function.BiConsumer<Settings, Integer> setter) {
        Spinner<Integer> s = new Spinner<>(min, max, def);
        s.setEditable(true);
        s.setPrefWidth(100);
        s.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            setter.accept(config.getSettings(), now);
            apply();
        });
        return s;
    }

    /** Local-history retention spinners are only meaningful while the master switch is on. */
    private void updateHistoryRowsEnabled() {
        boolean on = localHistoryCheck.isSelected();
        historyMaxPerFileSpinner.setDisable(!on);
        historyMaxAgeSpinner.setDisable(!on);
        historyMaxTotalSpinner.setDisable(!on);
    }

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
        // General
        pages.put(Category.APPEARANCE, appearancePage());
        pages.put(Category.INTERFACE, interfacePage());
        pages.put(Category.WORKSPACE, workspacePage());
        pages.put(Category.TOOL_WINDOWS, toolWindowsPage());
        // Editor
        pages.put(Category.EDITOR, editorPage());
        pages.put(Category.COMPLETION, completionPage());
        pages.put(Category.SNIPPETS, snippetsPage());
        pages.put(Category.TEMPLATES, templatesPage());
        pages.put(Category.TODO, todoPage());
        pages.put(Category.SPELL_CHECK, spellPage());
        pages.put(Category.SEARCH, searchPage());
        // Languages & Tools
        pages.put(Category.LSP, lspPage());
        pages.put(Category.DEBUG, debugPage());
        pages.put(Category.MARKDOWN, markdownPage());
        pages.put(Category.MERMAID, mermaidPage());
        pages.put(Category.WEB, webPage());
        pages.put(Category.EXTERNAL_TOOLS, externalToolsPage());
        // Version control
        pages.put(Category.GIT, gitPage());
        // System
        pages.put(Category.KEYMAPS, keymapsPage());
        pages.put(Category.MACROS, macrosPage());
        pages.put(Category.REMOTE, remotePage());
        pages.put(Category.PLUGINS, pluginsPage());
        pages.put(Category.MCP, mcpPage());
        pages.put(Category.ADVANCED, advancedPage());
    }

    private VBox appearancePage() {
        VBox p = page(tr("settings.cat.appearance"));
        Label langNote = note(tr("settings.uiLanguage.note"));
        VBox langBox = new VBox(4, languageCombo, langNote);
        row(
                p,
                Category.APPEARANCE,
                null,
                labeled(tr("settings.uiLanguage"), langBox),
                "language interface ui locale translation");
        Label fontNote = note(tr("settings.fontNote"));
        VBox fontBox = new VBox(4, fontFamily, fontNote);
        row(
                p,
                Category.APPEARANCE,
                null,
                labeled(tr("settings.fontFamily"), fontBox),
                "font family typeface monospace");
        row(p, Category.APPEARANCE, null, labeled(tr("settings.fontSize"), fontSize), "font size text");
        row(
                p,
                Category.APPEARANCE,
                null,
                labeled(tr("settings.theme"), themeCombo),
                "theme appearance dark light app chrome");
        Label etNote = note(tr("settings.editorThemeNote"));
        VBox etBox = new VBox(4, editorThemeCombo, etNote);
        row(
                p,
                Category.APPEARANCE,
                null,
                labeled(tr("settings.editorTheme"), etBox),
                "editor theme syntax colors highlighting");
        Label previewSection = section(p, tr("settings.livePreview"));
        row(p, Category.APPEARANCE, previewSection, preview, "preview sample code");
        return p;
    }

    private VBox keymapsPage() {
        VBox p = page(tr("settings.cat.keymaps"));
        Label kmNote = note(tr("settings.keymap.note"));
        VBox kmBox = new VBox(4, keymapCombo, kmNote);
        row(
                p,
                Category.KEYMAPS,
                null,
                labeled(tr("settings.keymap"), kmBox),
                "keymap keybindings shortcuts emacs vim cua sublime vscode intellij");

        // --- Customize shortcuts: searchable list of every command + its current chord ---
        Label sec = section(p, tr("settings.shortcuts.title"));
        shortcutFilter = new TextField();
        shortcutFilter.setPromptText(tr("settings.shortcuts.filter"));
        shortcutFilter.textProperty().addListener((o, was, now) -> refreshShortcuts());
        shortcutListBox = new VBox(2);
        shortcutListBox.getStyleClass().add("shortcut-list");
        ScrollPane scroll = new ScrollPane(shortcutListBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(320);
        scroll.getStyleClass().add("shortcut-scroll");
        Label note = note(tr("settings.shortcuts.note"));
        Button resetAll = new Button(tr("settings.shortcuts.resetAll"));
        resetAll.setOnAction(e -> {
            if (shortcutActions != null) {
                shortcutActions.resetAll();
                refreshShortcuts();
            }
        });
        VBox box = new VBox(6, note, shortcutFilter, scroll, resetAll);
        row(
                p,
                Category.KEYMAPS,
                sec,
                box,
                "shortcut keybinding customize rebind record reset clear key chord accelerator");
        refreshShortcuts();
        return p;
    }

    /** Rebuilds the shortcut list from the backend, honoring the filter. No-op until the backend is set. */
    private void refreshShortcuts() {
        if (shortcutListBox == null || shortcutActions == null) {
            return;
        }
        shortcutListBox.getChildren().clear();
        String q = shortcutFilter == null ? "" : shortcutFilter.getText().trim().toLowerCase(Locale.ROOT);
        for (Shortcut s : shortcutActions.rows()) {
            boolean match = q.isEmpty()
                    || s.title().toLowerCase(Locale.ROOT).contains(q)
                    || s.id().toLowerCase(Locale.ROOT).contains(q)
                    || (s.chord() != null && s.chord().toLowerCase(Locale.ROOT).contains(q));
            if (match) {
                shortcutListBox.getChildren().add(shortcutRow(s));
            }
        }
    }

    /** A single command row — either the static chord + Record/Reset buttons, or a live capture field. */
    private HBox shortcutRow(Shortcut s) {
        HBox row = new HBox(8);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("shortcut-row");
        Label title = new Label(s.title());
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);

        if (s.id().equals(recordingCommandId)) {
            TextField capture = new TextField();
            capture.setEditable(false);
            capture.setPromptText(tr("settings.shortcuts.recording"));
            capture.setPrefWidth(180);
            StringBuilder seq = new StringBuilder();
            capture.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                e.consume();
                if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                    recordingCommandId = null;
                    refreshShortcuts();
                    return;
                }
                String token = com.editora.command.KeyDispatcher.chord(e);
                if (token == null) {
                    return; // modifier-only press
                }
                if (seq.length() > 0) {
                    seq.append(' ');
                }
                seq.append(token);
                capture.setText(seq.toString());
            });
            Button save = new Button(tr("settings.shortcuts.save"));
            save.setDefaultButton(false);
            save.setOnAction(e -> commitRecording(s.id(), seq.toString()));
            Button cancel = new Button(tr("settings.shortcuts.cancel"));
            cancel.setOnAction(e -> {
                recordingCommandId = null;
                refreshShortcuts();
            });
            row.getChildren().addAll(title, capture, save, cancel);
            javafx.application.Platform.runLater(capture::requestFocus);
        } else {
            Label chord = new Label(s.chord() == null ? tr("settings.shortcuts.unbound") : s.chord());
            chord.getStyleClass().add(s.chord() == null ? "shortcut-unbound" : "shortcut-chord");
            chord.setMinWidth(150);
            row.getChildren().addAll(title, chord);
            // Record/Reset are shown only for the selected row (click a row to reveal them), keeping the
            // list uncluttered. Clicking the row selects it; the buttons then act on that command.
            row.getStyleClass().add("shortcut-row-clickable");
            row.setOnMouseClicked(e -> {
                if (!s.id().equals(selectedShortcutId)) {
                    selectedShortcutId = s.id();
                    refreshShortcuts();
                }
            });
            if (s.id().equals(selectedShortcutId)) {
                row.getStyleClass().add("shortcut-row-selected");
                Button record = new Button(tr("settings.shortcuts.record"));
                record.setOnAction(e -> {
                    recordingCommandId = s.id();
                    refreshShortcuts();
                });
                Button reset = new Button(tr("settings.shortcuts.reset"));
                reset.setOnAction(e -> {
                    shortcutActions.reset(s.id());
                    refreshShortcuts();
                });
                row.getChildren().addAll(record, reset);
            }
        }
        return row;
    }

    /** Commits a recorded chord sequence to a command, warning first if it steals another command's chord. */
    private void commitRecording(String commandId, String sequence) {
        recordingCommandId = null;
        rebindWithConflictCheck(commandId, sequence);
        refreshShortcuts();
    }

    /** Rebinds {@code commandId} to {@code sequence}, warning on a conflict; returns whether it bound. Shared
     *  by the Keymaps shortcut editor and the inline Macros keybinding row. */
    private boolean rebindWithConflictCheck(String commandId, String sequence) {
        if (shortcutActions == null) {
            return false;
        }
        String seq = sequence.trim();
        if (seq.isEmpty()) {
            return false;
        }
        String other = shortcutActions.commandUsing(seq);
        if (other != null && !other.equals(commandId)) {
            String otherTitle = shortcutActions.rows().stream()
                    .filter(r -> r.id().equals(other))
                    .map(Shortcut::title)
                    .findFirst()
                    .orElse(other);
            Alert confirm = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    tr("dialog.shortcut.conflict.body", seq, otherTitle),
                    ButtonType.OK,
                    ButtonType.CANCEL);
            confirm.initOwner(stage);
            confirm.setTitle(tr("dialog.shortcut.conflict.title"));
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return false;
            }
        }
        shortcutActions.rebind(commandId, seq);
        return true;
    }

    /** Injects the cross-window macro re-register hook (→ {@code MainController}); used by the Macros page. */
    public void setMacrosChangedHandler(Runnable handler) {
        this.onMacrosChanged = handler == null ? () -> {} : handler;
    }

    private VBox macrosPage() {
        VBox p = page(tr("settings.cat.macros"));
        row(
                p,
                Category.MACROS,
                null,
                macrosEditor(),
                "macros keyboard record replay steps rename delete keybinding command text");
        Label note = note(tr("settings.macro.note"));
        note.setWrapText(true);
        note.setMaxWidth(460);
        row(p, Category.MACROS, null, note, "macros record f3 f4 replay keybinding save");
        return p;
    }

    /** Master-detail editor for saved keyboard macros: list on the left, a name/keybinding/steps form on the right. */
    private javafx.scene.Node macrosEditor() {
        macroItems.setAll(config.getMacroStore().macros);

        ListView<com.editora.macro.Macro> list = new ListView<>(macroItems);
        list.setPrefSize(200, 280);
        list.setPlaceholder(note(tr("settings.macro.empty")));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.editora.macro.Macro m, boolean empty) {
                super.updateItem(m, empty);
                setText(empty || m == null ? null : m.name() + "  (" + m.steps().size() + ")");
            }
        });

        TextField name = new TextField();
        HBox keybinding = new HBox(8);
        keybinding.setAlignment(Pos.CENTER_LEFT);

        ListView<com.editora.macro.MacroStep> steps = new ListView<>(macroStepItems);
        steps.setPrefHeight(180);
        steps.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.editora.macro.MacroStep s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : macroStepLabel(s));
            }
        });
        Label stepKind = new Label();
        stepKind.setMinWidth(Region.USE_PREF_SIZE);
        TextField stepValue = new TextField();
        HBox.setHgrow(stepValue, Priority.ALWAYS);
        stepValue.setDisable(true);
        Runnable commitStep = () -> {
            int i = steps.getSelectionModel().getSelectedIndex();
            com.editora.macro.MacroStep cur = steps.getSelectionModel().getSelectedItem();
            if (i < 0 || cur == null || loadingMacro) {
                return;
            }
            macroStepItems.set(i, new com.editora.macro.MacroStep(cur.kind(), stepValue.getText()));
        };
        stepValue.setOnAction(e -> commitStep.run());
        stepValue.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commitStep.run();
            }
        });
        steps.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            loadingMacro = true;
            try {
                stepValue.setDisable(now == null);
                stepKind.setText(
                        now == null
                                ? ""
                                : tr(now.isCommand() ? "settings.macro.kind.command" : "settings.macro.kind.text"));
                stepValue.setText(now == null ? "" : now.value());
            } finally {
                loadingMacro = false;
            }
        });

        Button stepUp = new Button("▲");
        Button stepDown = new Button("▼");
        stepUp.getStyleClass().addAll("flat", "reorder-button");
        stepDown.getStyleClass().addAll("flat", "reorder-button");
        stepUp.setOnAction(e -> moveStep(steps, -1));
        stepDown.setOnAction(e -> moveStep(steps, 1));
        Button stepRemove = new Button(tr("settings.macro.removeStep"));
        stepRemove.setOnAction(e -> {
            int i = steps.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                macroStepItems.remove(i);
            }
        });
        Button addCmd = new Button(tr("settings.macro.addCommand"));
        addCmd.setOnAction(e -> {
            macroStepItems.add(com.editora.macro.MacroStep.command(""));
            steps.getSelectionModel().selectLast();
            stepValue.requestFocus();
        });
        Button addText = new Button(tr("settings.macro.addText"));
        addText.setOnAction(e -> {
            macroStepItems.add(com.editora.macro.MacroStep.text(""));
            steps.getSelectionModel().selectLast();
            stepValue.requestFocus();
        });
        HBox stepButtons = new HBox(6, addCmd, addText, spacer(), stepUp, stepDown, stepRemove);
        stepButtons.setAlignment(Pos.CENTER_LEFT);

        javafx.scene.layout.GridPane form = new javafx.scene.layout.GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.macro.name"), name);
        formRow(form, 1, tr("settings.macro.keybinding"), keybinding);
        Label stepsLabel = new Label(tr("settings.macro.steps"));
        stepsLabel.getStyleClass().add("settings-section");
        VBox stepEditor = new VBox(6, stepsLabel, steps, new HBox(8, stepKind, stepValue), stepButtons);
        VBox.setVgrow(steps, Priority.ALWAYS);
        form.setDisable(true);
        HBox.setHgrow(form, Priority.ALWAYS);

        // Repopulates the inline keybinding row for the selected macro's command id.
        java.util.function.Consumer<com.editora.macro.Macro> rebuildKeybinding = m -> {
            keybinding.getChildren().clear();
            if (m == null) {
                return;
            }
            String cmdId = com.editora.macro.MacroService.commandIdFor(m.name());
            String chord = currentChordFor(cmdId);
            boolean bound = chord != null && !chord.isBlank();
            Label chordLbl = new Label(bound ? chord : tr("settings.shortcuts.unbound"));
            chordLbl.getStyleClass().add(bound ? "shortcut-chord" : "shortcut-unbound");
            chordLbl.setMinWidth(150);
            Button record = new Button(tr("settings.shortcuts.record"));
            Button clear = new Button(tr("settings.shortcuts.reset"));
            clear.setOnAction(e -> {
                if (shortcutActions != null) {
                    shortcutActions.reset(cmdId);
                }
                rebuildKeybindingFor(keybinding, m, steps);
            });
            record.setOnAction(e -> startMacroCapture(keybinding, cmdId, m, steps));
            keybinding.getChildren().addAll(chordLbl, record, clear);
        };
        macroKeybindingRebuilders.put(keybinding, rebuildKeybinding);

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            loadingMacro = true;
            try {
                form.setDisable(now == null);
                macroOriginalName = now == null ? null : now.name();
                name.setText(now == null ? "" : now.name());
                macroStepItems.setAll(now == null ? java.util.List.of() : now.steps());
                rebuildKeybinding.accept(now);
            } finally {
                loadingMacro = false;
            }
        });

        Button save = new Button(tr("settings.save"));
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> saveMacro(list, name.getText()));
        Button delete = new Button(tr("settings.macro.delete"));
        delete.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.setOnAction(e -> deleteMacro(list));
        HBox formButtons = new HBox(8, delete, spacer(), save);
        formButtons.setAlignment(Pos.CENTER_LEFT);

        VBox right = new VBox(8, form, stepEditor, formButtons);
        VBox.setVgrow(stepEditor, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox left = new VBox(6, list);
        VBox.setVgrow(list, Priority.ALWAYS);

        if (!macroItems.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        HBox box = new HBox(12, left, right);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** Maps a keybinding HBox to its rebuilder so {@code reset}/capture can repopulate it. */
    private final java.util.Map<HBox, java.util.function.Consumer<com.editora.macro.Macro>> macroKeybindingRebuilders =
            new java.util.HashMap<>();

    private void rebuildKeybindingFor(
            HBox keybinding, com.editora.macro.Macro m, ListView<com.editora.macro.MacroStep> steps) {
        var rebuilder = macroKeybindingRebuilders.get(keybinding);
        if (rebuilder != null) {
            rebuilder.accept(m);
        }
    }

    /** Swaps the keybinding row into a live chord-capture field, mirroring the Keymaps recorder. */
    private void startMacroCapture(
            HBox keybinding, String commandId, com.editora.macro.Macro m, ListView<com.editora.macro.MacroStep> steps) {
        keybinding.getChildren().clear();
        TextField capture = new TextField();
        capture.setEditable(false);
        capture.setPromptText(tr("settings.shortcuts.recording"));
        capture.setPrefWidth(180);
        StringBuilder seq = new StringBuilder();
        capture.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            e.consume();
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                rebuildKeybindingFor(keybinding, m, steps);
                return;
            }
            String token = com.editora.command.KeyDispatcher.chord(e);
            if (token == null) {
                return; // modifier-only press
            }
            if (seq.length() > 0) {
                seq.append(' ');
            }
            seq.append(token);
            capture.setText(seq.toString());
        });
        Button save = new Button(tr("settings.shortcuts.save"));
        save.setOnAction(e -> {
            rebindWithConflictCheck(commandId, seq.toString());
            rebuildKeybindingFor(keybinding, m, steps);
        });
        Button cancel = new Button(tr("settings.shortcuts.cancel"));
        cancel.setOnAction(e -> rebuildKeybindingFor(keybinding, m, steps));
        keybinding.getChildren().addAll(capture, save, cancel);
        javafx.application.Platform.runLater(capture::requestFocus);
    }

    private static void moveStep(ListView<com.editora.macro.MacroStep> steps, int delta) {
        int i = steps.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= steps.getItems().size()) {
            return;
        }
        com.editora.macro.MacroStep s = steps.getItems().remove(i);
        steps.getItems().add(j, s);
        steps.getSelectionModel().select(j);
    }

    private static String macroStepLabel(com.editora.macro.MacroStep s) {
        if (s.isCommand()) {
            return "⌘  " + (s.value() == null || s.value().isBlank() ? "…" : s.value());
        }
        String v = s.value() == null ? "" : s.value().replace("\n", "\\n").replace("\t", "\\t");
        return "✎  \"" + v + "\"";
    }

    private String currentChordFor(String commandId) {
        if (shortcutActions == null) {
            return null;
        }
        for (Shortcut s : shortcutActions.rows()) {
            if (s.id().equals(commandId)) {
                return s.chord();
            }
        }
        return null;
    }

    private void saveMacro(ListView<com.editora.macro.Macro> list, String rawName) {
        String newName = rawName == null ? "" : rawName.trim();
        if (newName.isEmpty() || macroOriginalName == null) {
            return;
        }
        com.editora.config.MacroStore store = config.getMacroStore();
        boolean renamed = !macroOriginalName.equals(newName);
        if (renamed && store.find(newName) != null) {
            Alert a = new Alert(Alert.AlertType.WARNING, tr("settings.macro.nameExists", newName), ButtonType.OK);
            a.initOwner(stage);
            a.setHeaderText(null);
            a.showAndWait();
            return;
        }
        String oldChord =
                renamed ? currentChordFor(com.editora.macro.MacroService.commandIdFor(macroOriginalName)) : null;
        com.editora.macro.Macro updated =
                new com.editora.macro.Macro(newName, new java.util.ArrayList<>(macroStepItems));
        if (renamed) {
            store.remove(macroOriginalName);
        }
        store.put(updated);
        config.saveMacros();
        onMacrosChanged.run(); // re-register macro.run.* (incl. the renamed id) in every window
        if (renamed && oldChord != null && !oldChord.isBlank() && shortcutActions != null) {
            shortcutActions.rebind(com.editora.macro.MacroService.commandIdFor(newName), oldChord);
            shortcutActions.reset(com.editora.macro.MacroService.commandIdFor(macroOriginalName));
        }
        macroItems.setAll(store.macros);
        for (com.editora.macro.Macro m : macroItems) {
            if (m.name().equals(newName)) {
                list.getSelectionModel().select(m);
                break;
            }
        }
    }

    private void deleteMacro(ListView<com.editora.macro.Macro> list) {
        com.editora.macro.Macro sel = list.getSelectionModel().getSelectedItem();
        if (sel == null) {
            return;
        }
        com.editora.config.MacroStore store = config.getMacroStore();
        if (shortcutActions != null) {
            shortcutActions.reset(com.editora.macro.MacroService.commandIdFor(sel.name())); // drop its keybinding
        }
        store.remove(sel.name());
        config.saveMacros();
        onMacrosChanged.run();
        macroItems.setAll(store.macros);
        if (!macroItems.isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    private VBox editorPage() {
        VBox p = page(tr("settings.cat.editor"));
        Label display = section(p, tr("settings.section.display"));
        row(p, Category.EDITOR, display, columnRulerCheck, "80 column ruler guide margin");
        row(p, Category.EDITOR, display, lineHighlightCheck, "highlight current line caret");
        row(p, Category.EDITOR, display, lineNumbersCheck, "line numbers gutter");
        row(p, Category.EDITOR, display, minimapCheck, "minimap overview");
        row(p, Category.EDITOR, display, wordWrapCheck, "word wrap soft wrap long lines");
        row(p, Category.EDITOR, display, whitespaceCheck, "hidden characters whitespace spaces tabs eol");
        row(p, Category.EDITOR, display, noteIndicatorsCheck, "personal notes gutter marker highlight indicators");
        row(
                p,
                Category.EDITOR,
                display,
                multiCaretCheck,
                "multiple cursors carets column box selection alt drag vs code");
        Label indent = section(p, tr("settings.section.indentation"));
        row(
                p,
                Category.EDITOR,
                indent,
                labeled(tr("settings.indentStyle"), indentStyleCombo),
                "indent style tabs spaces detect auto width");
        row(
                p,
                Category.EDITOR,
                indent,
                labeled(tr("settings.tabSize"), tabSizeSpinner),
                "tab size indent width spaces");
        row(
                p,
                Category.EDITOR,
                indent,
                labeled(tr("settings.fillColumn"), fillColumnSpinner),
                "fill column wrap paragraph reflow emacs m-q width");
        row(
                p,
                Category.EDITOR,
                indent,
                editorConfigCheck,
                "editorconfig indent style size charset end of line trailing whitespace final newline");
        Label performance = section(p, tr("settings.section.performance"));
        HBox largeFileBox = new HBox(8, largeFileThresholdSpinner, note(tr("settings.largeFileThreshold.unit")));
        largeFileBox.setAlignment(Pos.CENTER_LEFT);
        row(
                p,
                Category.EDITOR,
                performance,
                labeled(tr("settings.largeFileThreshold"), largeFileBox),
                "large file performance minimap lsp lines threshold responsive huge source");
        Label logs = section(p, tr("settings.section.logs"));
        row(
                p,
                Category.EDITOR,
                logs,
                logViewerCheck,
                "log viewer server logs tail follow level highlighting filter apache spring boot");
        Label csv = section(p, tr("settings.section.csv"));
        row(p, Category.EDITOR, csv, csvGridCheck, "csv tsv grid table preview spreadsheet columns");
        Label saving = section(p, tr("settings.section.saving"));
        Label delayLabel = note("delay (seconds)");
        HBox autoSaveBox = new HBox(8, autoSaveCombo, autoSaveDelaySpinner, delayLabel);
        autoSaveBox.setAlignment(Pos.CENTER_LEFT);
        row(
                p,
                Category.EDITOR,
                saving,
                labeled(tr("settings.autoSave"), autoSaveBox),
                "auto save autosave delay inactivity focus");
        row(p, Category.EDITOR, saving, adminSaveCheck, "save administrator root sudo pkexec permission etc linux");
        Label adminSaveNote = note(tr("settings.adminSave.note"));
        adminSaveNote.setWrapText(true);
        adminSaveNote.setMaxWidth(440);
        row(p, Category.EDITOR, saving, adminSaveNote, "save administrator root permission linux pkexec");
        Label pdf = section(p, tr("settings.section.pdf"));
        row(p, Category.EDITOR, pdf, pdfLineNumbersCheck, "pdf export line numbers gutter");
        row(p, Category.EDITOR, pdf, pdfHighlightCheck, "pdf export syntax highlighting colors");
        row(
                p,
                Category.EDITOR,
                pdf,
                labeled(tr("settings.pdf.pageSize"), pdfPageSizeCombo),
                "pdf export page size letter a4 paper");
        return p;
    }

    /** The Markdown settings page: editing (format bar), preview/PDF (math), and linting (enable + per-rule). */
    private Region markdownPage() {
        VBox p = page(tr("settings.cat.markdown"));
        Label editing = section(p, tr("settings.section.markdownEditing"));
        row(
                p,
                Category.MARKDOWN,
                editing,
                markdownFormatBarCheck,
                "markdown format bar selection bold italic toolbar floating");
        Label preview = section(p, tr("settings.section.markdownPreview"));
        row(p, Category.MARKDOWN, preview, mathSupportCheck, "markdown math latex katex formula equation dollar");
        Label lint = section(p, tr("settings.section.markdownLint"));
        row(p, Category.MARKDOWN, lint, markdownLintCheck, "markdown lint linting warnings squiggles rules");
        row(
                p,
                Category.MARKDOWN,
                lint,
                markdownLintRulesEditor(),
                "markdown lint rules MD009 MD040 MD034 disable enable per-rule checklist");
        return p;
    }

    /** The Markdown-lint per-rule checklist: one checkbox per rule (checked = enabled). Toggling writes the
     *  disabled rule codes to {@code Settings.markdownLintDisabledRules} and applies live. */
    private javafx.scene.Node markdownLintRulesEditor() {
        markdownLintRulesBox = new VBox(4);
        rebuildMarkdownLintRules();
        return markdownLintRulesBox;
    }

    private void rebuildMarkdownLintRules() {
        if (markdownLintRulesBox == null) {
            return;
        }
        markdownLintRulesBox.getChildren().clear();
        java.util.Set<String> disabled = new java.util.HashSet<>();
        for (String c : config.getSettings().getMarkdownLintDisabledRules()) {
            if (c != null) {
                disabled.add(c.strip().toUpperCase(java.util.Locale.ROOT));
            }
        }
        // One row per rule: a checkbox whose label is the rule code (fixed-width so codes align) followed by
        // a muted one-line description — a readable vertical list instead of a wrapping grid of bare codes.
        for (com.editora.markdown.MarkdownLint.Rule rule : com.editora.markdown.MarkdownLint.RULES) {
            String code = rule.code();
            Label codeLabel = new Label(code);
            codeLabel.getStyleClass().add("md-lint-code");
            codeLabel.setMinWidth(58);
            Label desc = new Label(tr("mdlint.rule." + code));
            desc.getStyleClass().add("settings-hint");
            HBox label = new HBox(8, codeLabel, desc);
            label.setAlignment(Pos.CENTER_LEFT);

            CheckBox cb = new CheckBox();
            cb.setGraphic(label);
            cb.setSelected(!disabled.contains(code));
            cb.selectedProperty().addListener((o, was, on) -> {
                java.util.List<String> list =
                        new java.util.ArrayList<>(config.getSettings().getMarkdownLintDisabledRules());
                list.removeIf(c -> code.equalsIgnoreCase(c));
                if (!on) {
                    list.add(code);
                }
                config.getSettings().setMarkdownLintDisabledRules(list);
                apply();
            });
            markdownLintRulesBox.getChildren().add(cb);
        }
    }

    /** The TODO/highlight pattern editor: one row per pattern (enabled / name / regex / color / case) plus
     *  an "Add" button. Edits write back to {@code Settings.todoPatterns} and apply live. */
    private javafx.scene.Node todoPatternsEditor() {
        todoPatternsBox = new VBox(4);
        Button add = new Button(tr("settings.todo.add"));
        add.setOnAction(e -> {
            java.util.List<com.editora.todo.TodoPattern> list = mutableTodoPatterns();
            list.add(new com.editora.todo.TodoPattern(
                    tr("settings.todo.newName"),
                    "\\bTODO\\b",
                    com.editora.todo.TodoPatterns.DEFAULT_COLOR,
                    false,
                    true));
            config.getSettings().setTodoPatterns(list);
            rebuildTodoRows();
            apply();
        });
        VBox box = new VBox(6, todoPatternsBox, add);
        rebuildTodoRows();
        return box;
    }

    private java.util.List<com.editora.todo.TodoPattern> mutableTodoPatterns() {
        return new java.util.ArrayList<>(config.getSettings().getTodoPatterns());
    }

    private void rebuildTodoRows() {
        if (todoPatternsBox == null) {
            return;
        }
        todoPatternsBox.getChildren().clear();
        int size = config.getSettings().getTodoPatterns().size();
        for (int i = 0; i < size; i++) {
            todoPatternsBox.getChildren().add(todoRow(i));
        }
    }

    private javafx.scene.Node todoRow(int index) {
        com.editora.todo.TodoPattern p = config.getSettings().getTodoPatterns().get(index);
        CheckBox enabled = new CheckBox();
        enabled.setSelected(p.isEnabled());
        enabled.setTooltip(new Tooltip(tr("settings.todo.enabledTip")));
        TextField name = new TextField(p.getName());
        name.setPromptText(tr("settings.todo.namePrompt"));
        name.setPrefWidth(110);
        TextField regex = new TextField(p.getPattern());
        regex.setPromptText(tr("settings.todo.regexPrompt"));
        HBox.setHgrow(regex, Priority.ALWAYS);
        javafx.scene.control.ColorPicker color = new javafx.scene.control.ColorPicker(parseColor(p.getColor()));
        color.setTooltip(new Tooltip(tr("settings.todo.colorPrompt")));
        color.setPrefWidth(56);
        CheckBox caseSensitive = new CheckBox(tr("settings.todo.case"));
        caseSensitive.setSelected(p.isCaseSensitive());
        Button remove = new Button("✕");
        remove.setTooltip(new Tooltip(tr("settings.todo.removeTip")));

        Runnable commit = () -> {
            java.util.List<com.editora.todo.TodoPattern> cur = mutableTodoPatterns();
            if (index >= cur.size()) {
                return;
            }
            com.editora.todo.TodoPattern up = cur.get(index);
            up.setEnabled(enabled.isSelected());
            up.setName(name.getText());
            up.setPattern(regex.getText());
            up.setColor(toHex(color.getValue()));
            up.setCaseSensitive(caseSensitive.isSelected());
            config.getSettings().setTodoPatterns(cur);
            apply();
        };
        // Checkboxes + the color picker apply immediately; text fields commit on Enter / focus-loss.
        enabled.selectedProperty().addListener((o, a, b) -> commit.run());
        caseSensitive.selectedProperty().addListener((o, a, b) -> commit.run());
        color.valueProperty().addListener((o, a, b) -> commit.run());
        name.setOnAction(e -> commit.run());
        regex.setOnAction(e -> commit.run());
        java.util.function.Consumer<TextField> onBlur =
                tf -> tf.focusedProperty().addListener((o, was, now) -> {
                    if (!now) {
                        commit.run();
                    }
                });
        onBlur.accept(name);
        onBlur.accept(regex);
        remove.setOnAction(e -> {
            java.util.List<com.editora.todo.TodoPattern> cur = mutableTodoPatterns();
            if (index < cur.size()) {
                cur.remove(index);
            }
            config.getSettings().setTodoPatterns(cur);
            rebuildTodoRows();
            apply();
        });
        HBox row = new HBox(6, enabled, name, regex, color, caseSensitive, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static javafx.scene.paint.Color parseColor(String web) {
        try {
            return javafx.scene.paint.Color.web(web == null || web.isBlank() ? "#E5C07B" : web);
        } catch (RuntimeException e) {
            return javafx.scene.paint.Color.web("#E5C07B");
        }
    }

    private static String toHex(javafx.scene.paint.Color c) {
        return String.format(
                "#%02X%02X%02X",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int)
                        Math.round(c.getBlue() * 255));
    }

    private VBox spellPage() {
        VBox p = page(tr("settings.cat.spellCheck"));
        row(p, Category.SPELL_CHECK, null, spellCheckBox, "spell check spelling enable");
        row(
                p,
                Category.SPELL_CHECK,
                null,
                labeled(tr("settings.language"), spellLanguageCombo),
                "spell language dictionary english spanish french");
        // The two dictionary-file links, grouped together near the top (out of the checkbox/list flow).
        Hyperlink techLink = new Hyperlink(tr("settings.dict.openTechnical"));
        techLink.setTooltip(new Tooltip(tr("settings.dict.openTechnicalTip")));
        techLink.setOnAction(e -> {
            if (onOpenTechnicalDictionary != null) {
                onOpenTechnicalDictionary.run();
            }
        });
        Hyperlink personalLink = new Hyperlink(tr("settings.dict.openPersonal"));
        personalLink.setTooltip(new Tooltip(tr("settings.dict.openPersonalTip")));
        personalLink.setOnAction(e -> {
            if (onOpenPersonalDictionary != null) {
                onOpenPersonalDictionary.run();
            }
        });
        HBox dictLinks = new HBox(16, techLink, personalLink);
        dictLinks.setAlignment(Pos.CENTER_LEFT);
        row(
                p,
                Category.SPELL_CHECK,
                null,
                dictLinks,
                "dictionary open technical personal file dictionary.txt bundled terms");

        Label dict = section(p, tr("settings.dict.title"));
        row(
                p,
                Category.SPELL_CHECK,
                dict,
                techDictEnableCheck,
                "technical dictionary terms programming code config async kubernetes enable on off");
        row(p, Category.SPELL_CHECK, dict, dictEnableCheck, "personal dictionary enable on off honor words");
        row(p, Category.SPELL_CHECK, dict, dictionaryEditor(), "personal dictionary words add remove custom ignore");
        Label dictNote = note(tr("settings.dict.note"));
        dictNote.setWrapText(true);
        dictNote.setMaxWidth(440);
        row(p, Category.SPELL_CHECK, dict, dictNote, "dictionary.txt file location global");
        return p;
    }

    /** A simple editor for the global personal dictionary ({@code dictionary.txt}): list + add/remove. */
    private javafx.scene.Node dictionaryEditor() {
        dictionaryList = new ListView<>();
        dictionaryList.setPrefSize(280, 220);
        dictionaryList.setPlaceholder(new Label(tr("settings.dict.empty")));
        refreshDictionaryList();

        TextField input = new TextField();
        input.setPromptText(tr("settings.dict.prompt"));
        HBox.setHgrow(input, Priority.ALWAYS);
        Button add = new Button(tr("settings.dict.add"));
        Runnable doAdd = () -> {
            String w = input.getText().strip().toLowerCase(java.util.Locale.ROOT);
            if (!w.isEmpty()) {
                config.addUserWord(w);
                input.clear();
                refreshDictionaryList();
                dictionaryList.getSelectionModel().select(w);
            }
            input.requestFocus();
        };
        add.setOnAction(e -> doAdd.run());
        input.setOnAction(e -> doAdd.run());

        Button remove = new Button(tr("settings.dict.remove"));
        remove.disableProperty()
                .bind(dictionaryList.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            String sel = dictionaryList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                config.removeUserWord(sel);
                refreshDictionaryList();
            }
        });

        HBox addRow = new HBox(6, input, add);
        addRow.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, dictionaryList, addRow, new HBox(6, remove));
        box.setMaxWidth(440);
        return box;
    }

    /** Reloads the Personal Dictionary list from the shared user-word set (sorted), preserving selection. */
    private void refreshDictionaryList() {
        if (dictionaryList == null) {
            return;
        }
        java.util.List<String> words = new java.util.ArrayList<>(config.getUserDictionary());
        java.util.Collections.sort(words);
        String sel = dictionaryList.getSelectionModel().getSelectedItem();
        dictionaryList.getItems().setAll(words);
        if (sel != null && words.contains(sel)) {
            dictionaryList.getSelectionModel().select(sel);
        }
    }

    /** Re-syncs the "Enable personal dictionary" checkbox after a palette toggle. */
    public void syncPersonalDictionaryCheck() {
        if (dictEnableCheck != null) {
            dictEnableCheck.setSelected(config.getSettings().isPersonalDictionary());
        }
    }

    /** Re-syncs the "Enable technical dictionary" checkbox after a palette toggle. */
    public void syncTechnicalDictionaryCheck() {
        if (techDictEnableCheck != null) {
            techDictEnableCheck.setSelected(config.getSettings().isTechnicalDictionary());
        }
    }

    /** Opens Settings focused on the Spell Check page (the {@code spell.manageDictionary} command). */
    public void showSpellCheck(Window owner) {
        show(owner);
        sidebar.getSelectionModel().select(Category.SPELL_CHECK);
        refreshDictionaryList();
    }

    /** GENERAL ▸ Interface: window-chrome visibility + the Simple/Zen minimal-UI modes. */
    private VBox interfacePage() {
        VBox p = page(tr("settings.cat.interface"));
        Label chrome = section(p, tr("settings.section.chrome"));
        row(p, Category.INTERFACE, chrome, toolbarCheck, "toolbar buttons chrome");
        row(p, Category.INTERFACE, chrome, statusBarCheck, "status bar chrome");
        row(p, Category.INTERFACE, chrome, tabBarCheck, "tab bar tabs chrome");
        row(p, Category.INTERFACE, chrome, breadcrumbCheck, "breadcrumb file path chrome");
        row(p, Category.INTERFACE, chrome, toolStripeCheck, "tool stripe tool windows buttons show hide");
        Label stripeNote = note(tr("settings.toolWindows.stripeNote"));
        stripeNote.setWrapText(true);
        stripeNote.setMaxWidth(440);
        row(p, Category.INTERFACE, chrome, stripeNote, "tool stripe tool windows precedence");
        Label modes = section(p, tr("settings.section.modes"));
        row(p, Category.INTERFACE, modes, simpleModeCheck, "simple minimal ui mode chrome distraction");
        Label simpleNote = note(tr("settings.simpleMode.note"));
        simpleNote.setWrapText(true);
        simpleNote.setMaxWidth(440);
        row(p, Category.INTERFACE, modes, simpleNote, "simple minimal ui mode");
        row(p, Category.INTERFACE, modes, zenCheck, "zen distraction free focus mode");
        return p;
    }

    /** GENERAL ▸ Workspace: project + personal-notes features and local file history. */
    private VBox workspacePage() {
        VBox p = page(tr("settings.cat.workspace"));
        Label features = section(p, tr("settings.section.features"));
        Label projectsInfo = new Label("ⓘ");
        projectsInfo.getStyleClass().add("info-badge");
        Tooltip projectsTip = new Tooltip(tr("settings.projects.tip"));
        projectsTip.setWrapText(true);
        projectsTip.setMaxWidth(380);
        Tooltip.install(projectsInfo, projectsTip);
        HBox projectsRow = new HBox(6, projectsCheck, projectsInfo);
        projectsRow.setAlignment(Pos.CENTER_LEFT);
        row(p, Category.WORKSPACE, features, projectsRow, "projects workspace folder");
        row(p, Category.WORKSPACE, features, projectHiddenCheck, "project tree hidden dot files folders show");
        row(p, Category.WORKSPACE, features, notesCheck, "personal notes annotations enable feature");
        Label history = section(p, tr("settings.section.localHistory"));
        row(
                p,
                Category.WORKSPACE,
                history,
                localHistoryCheck,
                "local file history snapshot version revision restore undo");
        row(
                p,
                Category.WORKSPACE,
                history,
                labeled(tr("settings.history.maxPerFile"), historyMaxPerFileSpinner),
                "local history max revisions per file limit retention");
        row(
                p,
                Category.WORKSPACE,
                history,
                labeled(tr("settings.history.maxAgeDays"), historyMaxAgeSpinner),
                "local history max age days retention prune");
        row(
                p,
                Category.WORKSPACE,
                history,
                labeled(tr("settings.history.maxTotalMb"), historyMaxTotalSpinner),
                "local history max total size megabytes project budget");
        Label histNote = note(tr("settings.history.note"));
        histNote.setWrapText(true);
        histNote.setMaxWidth(440);
        row(p, Category.WORKSPACE, history, histNote, "local history retention");
        return p;
    }

    /** EDITOR ▸ Code Completion: the autocomplete master + per-source sub-toggles, quick-doc, semantic tokens. */
    private VBox completionPage() {
        VBox p = page(tr("settings.cat.completion"));
        Label completion = section(p, tr("settings.section.completion"));
        row(p, Category.COMPLETION, completion, autocompleteCheck, "autocomplete completion suggestions enable popup");
        HBox proseRow = new HBox(autocompleteProseCheck);
        proseRow.setPadding(new Insets(0, 0, 0, 20));
        row(p, Category.COMPLETION, completion, proseRow, "autocomplete prose words dictionary ghost text spelling");
        HBox snippetsRow = new HBox(autocompleteSnippetsCheck);
        snippetsRow.setPadding(new Insets(0, 0, 0, 20));
        row(p, Category.COMPLETION, completion, snippetsRow, "autocomplete snippets popup templates");
        HBox mermaidRow = new HBox(autocompleteMermaidCheck);
        mermaidRow.setPadding(new Insets(0, 0, 0, 20));
        row(p, Category.COMPLETION, completion, mermaidRow, "autocomplete mermaid diagram keywords snippets mmd");
        row(
                p,
                Category.COMPLETION,
                completion,
                completionDocCheck,
                "completion documentation quick doc popup javadoc ctrl q");
        row(
                p,
                Category.COMPLETION,
                completion,
                semanticHighlightCheck,
                "semantic highlighting lsp tokens types parameters fields deprecated");
        // Semantic highlighting comes from LSP: disable it (+ explain why) when LSP is off.
        semanticHighlightCheck
                .disableProperty()
                .bind(lspCheck.selectedProperty().not());
        Label semanticNote = note(tr("settings.semanticHighlight.lspNote"));
        semanticNote.setWrapText(true);
        semanticNote.setMaxWidth(440);
        semanticNote.setPadding(new Insets(0, 0, 0, 20));
        semanticNote.visibleProperty().bind(lspCheck.selectedProperty().not());
        semanticNote.managedProperty().bind(semanticNote.visibleProperty());
        p.getChildren().add(semanticNote); // not a search row, so filter() never fights the visibility binding
        return p;
    }

    /** EDITOR ▸ TODO: in-editor TODO/FIXME highlighting + the per-pattern editor. */
    private VBox todoPage() {
        VBox p = page(tr("settings.cat.todo"));
        Label todoHl = section(p, tr("settings.section.todo"));
        row(p, Category.TODO, todoHl, todoHighlightCheck, "todo fixme highlight patterns tags comments annotations");
        row(p, Category.TODO, todoHl, todoPatternsEditor(), "todo fixme pattern regex color add remove edit");
        return p;
    }

    /** LANGUAGES & TOOLS ▸ Web: HTML live preview + the built-in HTTP client (merged). */
    private VBox webPage() {
        VBox p = page(tr("settings.cat.web"));
        Label preview = section(p, tr("settings.section.htmlPreview"));
        row(p, Category.WEB, preview, htmlPreviewCheck, "html live preview browser serve enable");
        Label previewHint = note(tr("settings.htmlPreview.hint"));
        previewHint.setWrapText(true);
        previewHint.setMaxWidth(440);
        row(p, Category.WEB, preview, previewHint, "html preview browser safari chrome firefox edge server localhost");
        Label http = section(p, tr("settings.section.httpClient"));
        row(p, Category.WEB, http, httpCheck, "http client rest request enable run send");
        Label httpHint = note(tr("settings.httpClient.hint"));
        httpHint.setWrapText(true);
        httpHint.setMaxWidth(440);
        row(p, Category.WEB, http, httpHint, "http rest request response built-in client");
        return p;
    }

    private VBox gitPage() {
        VBox p = page(tr("settings.cat.git"));
        gitStatusLabel = new Label(tr("settings.git.checking"));
        gitStatusLabel.getStyleClass().add("settings-git-status");
        gitStatusLabel.setWrapText(true);
        gitStatusLabel.setMaxWidth(440);
        row(p, Category.GIT, null, gitStatusLabel, "git command found version installed not found");
        row(p, Category.GIT, null, gitCheck, "git version control vcs enable");
        row(p, Category.GIT, null, blameCheck, "git blame annotate inline author history line");
        Label hint = note(tr("settings.git.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        row(p, Category.GIT, null, hint, "git version control vcs enable");
        return p;
    }

    private VBox searchPage() {
        VBox p = page(tr("settings.cat.search"));
        ripgrepStatusLabel = new Label(tr("settings.search.checking"));
        ripgrepStatusLabel.getStyleClass().add("settings-git-status");
        ripgrepStatusLabel.setWrapText(true);
        ripgrepStatusLabel.setMaxWidth(440);
        row(p, Category.SEARCH, null, ripgrepStatusLabel, "search ripgrep rg found installed not found");
        row(p, Category.SEARCH, null, ripgrepCheck, "search ripgrep rg find in files fast");
        row(
                p,
                Category.SEARCH,
                null,
                exePathRow(tr("settings.search.ripgrepPath"), ripgrepCommandField),
                "search ripgrep rg path executable command");
        Label exclude = section(p, tr("settings.search.exclusions"));
        row(
                p,
                Category.SEARCH,
                exclude,
                searchGitignoreCheck,
                "search exclude gitignore ignored target node_modules build dist folders files");
        Label hint = note(tr("settings.search.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        row(p, Category.SEARCH, null, hint, "search ripgrep rg gitignore find in files");
        return p;
    }

    private VBox mermaidPage() {
        VBox p = page(tr("settings.cat.mermaid"));
        mermaidStatusLabel = new Label(tr("settings.mermaid.checking"));
        mermaidStatusLabel.getStyleClass().add("settings-git-status");
        mermaidStatusLabel.setWrapText(true);
        mermaidStatusLabel.setMaxWidth(440);
        row(p, Category.MERMAID, null, mermaidStatusLabel, "mermaid mmdc maid found installed not found");
        row(p, Category.MERMAID, null, installButton("mermaid"), "mermaid mmdc install download cli");
        row(p, Category.MERMAID, null, mermaidCheck, "mermaid diagram enable mmdc render mmd");
        row(
                p,
                Category.MERMAID,
                null,
                exePathRow(tr("settings.mermaid.mmdcPath"), mmdcPathField),
                "mermaid mmdc path executable render");
        row(
                p,
                Category.MERMAID,
                null,
                exePathRow(tr("settings.mermaid.maidPath"), maidPathField),
                "mermaid maid path executable lint validate");
        Label hint = note(tr("settings.mermaid.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        row(p, Category.MERMAID, null, hint, "mermaid install npm mmdc maid cli");
        return p;
    }

    /** Languages offered in the Snippets-page picker (this curated list plus any language that already
     *  has a user snippet file). */
    private static final java.util.List<String> SNIPPET_LANGUAGES = java.util.List.of(
            "global",
            "java",
            "javascript",
            "typescript",
            "python",
            "go",
            "rust",
            "c",
            "cpp",
            "csharp",
            "kotlin",
            "php",
            "ruby",
            "lua",
            "html",
            "css",
            "json",
            "yaml",
            "xml",
            "toml",
            "sql",
            "shell",
            "powershell",
            "batchfile",
            "groovy",
            "ini",
            "markdown",
            "mermaid",
            "dockerfile",
            "terraform");

    private VBox snippetsPage() {
        VBox p = page(tr("settings.cat.snippets"));
        row(p, Category.SNIPPETS, null, snippetsEditor(), "snippets user prefix trigger expansion tab stops body");
        Label help = note(tr("settings.snippet.help"));
        help.setWrapText(true);
        help.setMaxWidth(460);
        row(p, Category.SNIPPETS, null, help, "snippets help tab stop placeholder variable");
        return p;
    }

    /** Master-detail editor: a language picker + the user's snippets for it on the left, a form on the right. */
    private javafx.scene.Node snippetsEditor() {
        // Language picker: the curated list, plus any languages that already have a user file.
        java.util.LinkedHashSet<String> langs = new java.util.LinkedHashSet<>(SNIPPET_LANGUAGES);
        if (snippetManager != null) {
            langs.addAll(snippetManager.userSnippetLanguages());
        }
        ComboBox<String> language = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(langs));
        language.setValue(currentSnippetLang);

        ListView<com.editora.snippet.Snippet> list = new ListView<>(snippetItems);
        list.setPrefSize(200, 280);
        VBox.setVgrow(list, Priority.ALWAYS); // grow the list to fill the page height
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.editora.snippet.Snippet s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label nm =
                        new Label(s.name() == null || s.name().isBlank() ? tr("settings.snippet.unnamed") : s.name());
                HBox.setHgrow(nm, Priority.ALWAYS);
                nm.setMaxWidth(Double.MAX_VALUE);
                HBox cell = new HBox(6, nm);
                cell.setAlignment(Pos.CENTER_LEFT);
                if (!snippetUserNames.contains(s.name())) { // a read-only bundled snippet (until edited)
                    Label tag = new Label(tr("settings.snippet.bundledTag"));
                    tag.getStyleClass().add("snippet-bundled-tag");
                    cell.getChildren().add(tag);
                }
                setText(null);
                setGraphic(cell);
            }
        });

        TextField name = new TextField();
        TextField prefix = new TextField();
        prefix.setPromptText(tr("settings.snippet.prefixPrompt"));
        TextField description = new TextField();
        CodeArea body = new CodeArea();
        body.getStyleClass().addAll("editor-area", "snippet-body");
        body.setWrapText(true);
        // Modest preferred height so the page fits the window; GridPane Vgrow lets it expand when there's room.
        body.setPrefHeight(180);
        installEmacsKeys(body); // basic C-a/C-e/C-f/C-b/C-n/C-p/M-f/M-b/C-d/C-k in the settings scene
        body.plainTextChanges().subscribe(c -> highlightSnippetBody(body, currentSnippetLang));

        javafx.scene.layout.GridPane form = new javafx.scene.layout.GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.snippet.name"), name);
        formRow(form, 1, tr("settings.snippet.prefix"), prefix);
        formRow(form, 2, tr("settings.snippet.description"), description);
        formRow(form, 3, tr("settings.snippet.body"), body);
        javafx.scene.layout.GridPane.setHgrow(body, Priority.ALWAYS);
        javafx.scene.layout.GridPane.setVgrow(body, Priority.ALWAYS);
        form.setDisable(true);
        HBox.setHgrow(form, Priority.ALWAYS);

        Runnable commit = () -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i < 0 || loadingSnippet) {
                return;
            }
            com.editora.snippet.Snippet updated = new com.editora.snippet.Snippet(
                    name.getText().trim(),
                    prefix.getText().trim(),
                    body.getText(),
                    description.getText().trim(),
                    currentSnippetLang);
            snippetUserNames.add(updated.name()); // editing a bundled snippet makes it a user override
            loadingSnippet = true; // replacing at the same index keeps selection; don't reload the fields
            try {
                snippetItems.set(i, updated);
            } finally {
                loadingSnippet = false;
            }
            list.refresh(); // re-render so the "(bundled)" tag drops off the now-overridden row
            saveSnippets();
        };
        // Single-line fields commit on Enter / focus-loss; the body commits on focus-loss (Enter = newline).
        java.util.function.Consumer<TextField> wire = tf -> {
            tf.setOnAction(e -> commit.run());
            tf.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        };
        wire.accept(name);
        wire.accept(prefix);
        wire.accept(description);
        body.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commit.run();
            }
        });

        // Load the form when a *different* row is selected (selectedIndex, so an in-place commit set() is silent).
        list.getSelectionModel().selectedIndexProperty().addListener((o, was, now) -> {
            int i = now == null ? -1 : now.intValue();
            com.editora.snippet.Snippet s = i >= 0 && i < snippetItems.size() ? snippetItems.get(i) : null;
            loadingSnippet = true;
            try {
                form.setDisable(s == null);
                name.setText(s == null ? "" : s.name());
                prefix.setText(s == null ? "" : s.prefix());
                description.setText(s == null ? "" : s.description());
                body.replaceText(s == null ? "" : s.body()); // CodeArea has no setText
            } finally {
                loadingSnippet = false;
            }
        });

        Runnable loadLang = () -> {
            String v = language.getValue();
            currentSnippetLang = v == null || v.isBlank() ? "global" : v.trim();
            loadingSnippet = true;
            try {
                snippetItems.setAll(mergedSnippetsForCurrentLang());
            } finally {
                loadingSnippet = false;
            }
            list.getSelectionModel().clearSelection();
            if (!snippetItems.isEmpty()) {
                list.getSelectionModel().select(0);
            } else {
                form.setDisable(true);
            }
        };
        language.valueProperty().addListener((o, a, b) -> loadLang.run());

        Button add = new Button(tr("settings.snippet.add"));
        add.setOnAction(e -> {
            com.editora.snippet.Snippet s =
                    new com.editora.snippet.Snippet(tr("settings.snippet.newName"), "", "", "", currentSnippetLang);
            snippetUserNames.add(s.name());
            snippetItems.add(s);
            saveSnippets();
            list.getSelectionModel().select(snippetItems.size() - 1);
            name.requestFocus();
            name.selectAll();
        });
        Button remove = new Button(tr("settings.snippet.remove"));
        // Remove only affects user snippets/overrides; a pristine bundled row can't be deleted (it's shipped).
        remove.disableProperty()
                .bind(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> {
                            com.editora.snippet.Snippet s =
                                    list.getSelectionModel().getSelectedItem();
                            return s == null || !snippetUserNames.contains(s.name());
                        },
                        list.getSelectionModel().selectedItemProperty()));
        remove.setOnAction(e -> {
            com.editora.snippet.Snippet s = list.getSelectionModel().getSelectedItem();
            if (s == null || !snippetUserNames.contains(s.name())) {
                return;
            }
            snippetUserNames.remove(s.name());
            snippetItems.remove(s);
            saveSnippets();
            loadLang.run(); // re-derive: a removed override reverts to its bundled snippet
        });
        HBox buttons = new HBox(6, add, remove);
        VBox left = new VBox(6, labeled(tr("settings.snippet.language"), language), list, buttons);
        VBox.setVgrow(left, Priority.ALWAYS);

        // Explicit Save (edits also auto-save on Enter / focus-loss, so nothing is lost on row switch).
        Button save = new Button(tr("settings.save"));
        save.setDefaultButton(false);
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit.run());
        HBox saveRow = new HBox(save);
        saveRow.setAlignment(Pos.CENTER_RIGHT);
        VBox right = new VBox(8, form, saveRow);
        VBox.setVgrow(form, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        loadLang.run();
        HBox box = new HBox(12, left, right);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /**
     * The snippets shown for the current language: the bundled (shipped) ones, with any user file entries
     * overriding the bundled one of the same name and net-new user snippets appended. Rebuilds
     * {@link #snippetUserNames} (the names that are user-owned and therefore writable / removable).
     */
    private java.util.List<com.editora.snippet.Snippet> mergedSnippetsForCurrentLang() {
        snippetUserNames.clear();
        if (snippetManager == null) {
            return java.util.List.of();
        }
        java.util.LinkedHashMap<String, com.editora.snippet.Snippet> userByName = new java.util.LinkedHashMap<>();
        for (com.editora.snippet.Snippet u : snippetManager.userSnippets(currentSnippetLang)) {
            userByName.put(u.name(), u);
            snippetUserNames.add(u.name());
        }
        java.util.List<com.editora.snippet.Snippet> merged = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (com.editora.snippet.Snippet b : snippetManager.bundledSnippets(currentSnippetLang)) {
            merged.add(userByName.getOrDefault(b.name(), b)); // user override wins; else the read-only bundled
            seen.add(b.name());
        }
        for (com.editora.snippet.Snippet u : userByName.values()) {
            if (seen.add(u.name())) {
                merged.add(u); // a user snippet with no bundled counterpart
            }
        }
        return merged;
    }

    private void saveSnippets() {
        if (snippetManager == null) {
            return;
        }
        // Persist only user-owned snippets (overrides + net-new) — never copy the shipped bundled ones.
        java.util.List<com.editora.snippet.Snippet> userOnly = new java.util.ArrayList<>();
        for (com.editora.snippet.Snippet s : snippetItems) {
            if (snippetUserNames.contains(s.name())) {
                userOnly.add(s);
            }
        }
        try {
            snippetManager.saveUserSnippets(currentSnippetLang, userOnly);
        } catch (java.io.IOException e) {
            new Alert(Alert.AlertType.ERROR, tr("settings.snippet.saveFailed", e.getMessage()), ButtonType.OK)
                    .showAndWait();
        }
    }

    private VBox templatesPage() {
        VBox p = page(tr("settings.cat.templates"));
        Label author = section(p, tr("settings.section.templates"));
        row(
                p,
                Category.TEMPLATES,
                author,
                labeled(tr("settings.authorName"), templateAuthorField),
                "author name file templates new from template variable");
        Label list = section(p, tr("settings.section.templatesList"));
        row(p, Category.TEMPLATES, list, templatesEditor(), "templates file new from template scaffold bundled");
        Label help = note(tr("settings.template.help"));
        help.setWrapText(true);
        help.setMaxWidth(460);
        row(p, Category.TEMPLATES, list, help, "templates help variable cursor placeholder");
        return p;
    }

    /** Master-detail editor: the templates (bundled + user) on the left, a form for the selected one. */
    private javafx.scene.Node templatesEditor() {
        ListView<com.editora.template.Template> list = new ListView<>(templateItems);
        list.setPrefSize(200, 280);
        VBox.setVgrow(list, Priority.ALWAYS); // grow the list to fill the page height
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.editora.template.Template t, boolean empty) {
                super.updateItem(t, empty);
                if (empty || t == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label nm = new Label(t.name() == null || t.name().isBlank() ? t.id() : t.name());
                HBox.setHgrow(nm, Priority.ALWAYS);
                nm.setMaxWidth(Double.MAX_VALUE);
                HBox cell = new HBox(6, nm);
                cell.setAlignment(Pos.CENTER_LEFT);
                if (!templateUserIds.contains(t.id())) {
                    Label tag = new Label(tr("settings.template.bundledTag"));
                    tag.getStyleClass().add("snippet-bundled-tag");
                    cell.getChildren().add(tag);
                }
                setText(null);
                setGraphic(cell);
            }
        });

        TextField id = new TextField();
        TextField name = new TextField();
        TextField description = new TextField();
        TextField language = new TextField();
        language.setPromptText(tr("settings.template.languagePrompt"));
        TextField fileName = new TextField();
        fileName.setPromptText(tr("settings.template.fileNamePrompt"));
        CodeArea body = new CodeArea();
        body.getStyleClass().addAll("editor-area", "snippet-body");
        body.setWrapText(true);
        // Modest preferred height so the page fits the window; GridPane Vgrow lets it expand when there's room.
        body.setPrefHeight(180);
        installEmacsKeys(body); // basic Emacs caret movement in the settings scene
        body.plainTextChanges().subscribe(c -> highlightSnippetBody(body, language.getText()));
        // Re-highlight when the template's language changes (even without a body edit).
        language.textProperty().addListener((o, a, b) -> highlightSnippetBody(body, language.getText()));

        javafx.scene.layout.GridPane form = new javafx.scene.layout.GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.template.id"), id);
        formRow(form, 1, tr("settings.template.name"), name);
        formRow(form, 2, tr("settings.template.description"), description);
        formRow(form, 3, tr("settings.template.language"), language);
        formRow(form, 4, tr("settings.template.fileName"), fileName);
        formRow(form, 5, tr("settings.template.body"), body);
        javafx.scene.layout.GridPane.setHgrow(body, Priority.ALWAYS);
        javafx.scene.layout.GridPane.setVgrow(body, Priority.ALWAYS);
        form.setDisable(true);

        Label multiFileNote = note(tr("settings.template.multiFileNote"));
        multiFileNote.setWrapText(true);
        multiFileNote.setMaxWidth(440);
        multiFileNote.setVisible(false);
        multiFileNote.setManaged(false);
        VBox right = new VBox(6, form, multiFileNote);
        HBox.setHgrow(right, Priority.ALWAYS);

        Runnable commit = () -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i < 0 || loadingTemplate) {
                return;
            }
            com.editora.template.Template cur = templateItems.get(i);
            if (cur.isMultiFile() || id.getText().trim().isEmpty()) {
                return; // multi-file templates are read-only here; an id is required
            }
            String newId = id.getText().trim();
            com.editora.template.Template updated = new com.editora.template.Template(
                    newId,
                    name.getText().trim(),
                    description.getText().trim(),
                    language.getText().trim(),
                    fileName.getText().trim(),
                    body.getText(),
                    null);
            String oldId = cur.id();
            if (!oldId.equals(newId) && templateUserIds.contains(oldId)) {
                try {
                    templateRegistry.deleteUserTemplate(oldId); // renamed: drop the old override file
                } catch (java.io.IOException ignored) {
                    // best-effort
                }
                templateUserIds.remove(oldId);
            }
            templateUserIds.add(newId); // editing a bundled template makes it a user override
            loadingTemplate = true;
            try {
                templateItems.set(i, updated);
            } finally {
                loadingTemplate = false;
            }
            list.refresh();
            saveTemplate(updated);
        };
        java.util.function.Consumer<TextField> wire = tf -> {
            tf.setOnAction(e -> commit.run());
            tf.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        };
        wire.accept(id);
        wire.accept(name);
        wire.accept(description);
        wire.accept(language);
        wire.accept(fileName);
        body.focusedProperty().addListener((o, was, now) -> {
            if (!now) {
                commit.run();
            }
        });

        list.getSelectionModel().selectedIndexProperty().addListener((o, was, now) -> {
            int i = now == null ? -1 : now.intValue();
            com.editora.template.Template t = i >= 0 && i < templateItems.size() ? templateItems.get(i) : null;
            boolean multi = t != null && t.isMultiFile();
            loadingTemplate = true;
            try {
                form.setDisable(t == null || multi); // multi-file templates are display-only in this form
                id.setText(t == null ? "" : t.id());
                name.setText(t == null ? "" : t.name());
                description.setText(t == null ? "" : t.description());
                language.setText(t == null ? "" : t.language());
                fileName.setText(t == null || multi ? "" : t.fileName());
                body.replaceText(t == null || multi ? "" : t.body()); // CodeArea has no setText
                multiFileNote.setVisible(multi);
                multiFileNote.setManaged(multi);
            } finally {
                loadingTemplate = false;
            }
        });

        Runnable loadTemplates = () -> {
            loadingTemplate = true;
            try {
                templateItems.setAll(mergedTemplates());
            } finally {
                loadingTemplate = false;
            }
            list.getSelectionModel().clearSelection();
            if (!templateItems.isEmpty()) {
                list.getSelectionModel().select(0);
            } else {
                form.setDisable(true);
            }
        };

        Button add = new Button(tr("settings.template.add"));
        add.setOnAction(e -> {
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (com.editora.template.Template t : templateItems) {
                existing.add(t.id());
            }
            String base = "new-template";
            String nid = base;
            for (int n = 2; existing.contains(nid); n++) {
                nid = base + "-" + n;
            }
            com.editora.template.Template t = new com.editora.template.Template(
                    nid, tr("settings.template.newName"), "", "", "${name}.txt", "${cursor}", null);
            templateUserIds.add(nid);
            templateItems.add(t);
            saveTemplate(t);
            list.getSelectionModel().select(templateItems.size() - 1);
            id.requestFocus();
            id.selectAll();
        });
        Button remove = new Button(tr("settings.template.remove"));
        // Only user templates/overrides can be removed; a pristine bundled template can't be deleted.
        remove.disableProperty()
                .bind(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> {
                            com.editora.template.Template t =
                                    list.getSelectionModel().getSelectedItem();
                            return t == null || !templateUserIds.contains(t.id());
                        },
                        list.getSelectionModel().selectedItemProperty()));
        remove.setOnAction(e -> {
            com.editora.template.Template t = list.getSelectionModel().getSelectedItem();
            if (t == null || !templateUserIds.contains(t.id()) || templateRegistry == null) {
                return;
            }
            try {
                templateRegistry.deleteUserTemplate(t.id());
            } catch (java.io.IOException ex) {
                new Alert(Alert.AlertType.ERROR, tr("settings.template.saveFailed", ex.getMessage()), ButtonType.OK)
                        .showAndWait();
                return;
            }
            templateUserIds.remove(t.id());
            templateItems.remove(t);
            loadTemplates.run(); // re-derive: a removed override reverts to its bundled template
        });
        HBox buttons = new HBox(6, add, remove);
        VBox left = new VBox(6, list, buttons);
        VBox.setVgrow(left, Priority.ALWAYS);

        // Explicit Save (edits also auto-save on Enter / focus-loss); disabled for a read-only row.
        Button save = new Button(tr("settings.save"));
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit.run());
        HBox saveRow = new HBox(save);
        saveRow.setAlignment(Pos.CENTER_RIGHT);
        right.getChildren().add(1, saveRow); // between the form and the multi-file note
        VBox.setVgrow(form, Priority.ALWAYS);

        loadTemplates.run();
        HBox box = new HBox(12, left, right);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /**
     * The templates shown: bundled (shipped) ones, with any user file override of the same id and net-new
     * user templates appended. Rebuilds {@link #templateUserIds} (the ids that are user-owned/writable).
     */
    private java.util.List<com.editora.template.Template> mergedTemplates() {
        templateUserIds.clear();
        if (templateRegistry == null) {
            return java.util.List.of();
        }
        java.util.LinkedHashMap<String, com.editora.template.Template> userById = new java.util.LinkedHashMap<>();
        for (com.editora.template.Template u : templateRegistry.userTemplates()) {
            userById.put(u.id(), u);
            templateUserIds.add(u.id());
        }
        java.util.List<com.editora.template.Template> merged = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (com.editora.template.Template b : templateRegistry.bundledTemplates()) {
            merged.add(userById.getOrDefault(b.id(), b));
            seen.add(b.id());
        }
        for (com.editora.template.Template u : userById.values()) {
            if (seen.add(u.id())) {
                merged.add(u);
            }
        }
        return merged;
    }

    private void saveTemplate(com.editora.template.Template t) {
        if (templateRegistry == null) {
            return;
        }
        try {
            templateRegistry.saveUserTemplate(t);
        } catch (java.io.IOException e) {
            new Alert(Alert.AlertType.ERROR, tr("settings.template.saveFailed", e.getMessage()), ButtonType.OK)
                    .showAndWait();
        }
    }

    private VBox remotePage() {
        VBox p = page(tr("settings.cat.remote"));
        row(
                p,
                Category.REMOTE,
                null,
                remoteConnectionsEditor(),
                "remote sftp ssh connection host user key password saved site server");
        Label note = note(tr("settings.remote.note"));
        note.setWrapText(true);
        note.setMaxWidth(460);
        row(p, Category.REMOTE, null, note, "remote secret password passphrase not stored security");
        return p;
    }

    /** Master-detail editor for saved SFTP sites: a list on the left, a form for the selected site on the right. */
    private javafx.scene.Node remoteConnectionsEditor() {
        remoteItems.setAll(config.getConnections());

        ListView<com.editora.vfs.RemoteConnection> list = new ListView<>(remoteItems);
        list.setPrefSize(190, 220);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.editora.vfs.RemoteConnection c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : c.displayLabel());
            }
        });

        TextField label = new TextField();
        TextField host = new TextField();
        host.setPromptText(tr("remote.hostPrompt"));
        TextField port = new TextField();
        TextField user = new TextField();
        ComboBox<com.editora.vfs.RemoteConnection.AuthMethod> auth =
                new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                        com.editora.vfs.RemoteConnection.AuthMethod.values()));
        auth.setConverter(enumConverter(m -> switch (m) {
            case DEFAULT_KEYS -> tr("remote.auth.defaultKeys");
            case KEY -> tr("remote.auth.key");
            case PASSWORD -> tr("remote.auth.password");
        }));
        TextField keyPath = new TextField();
        keyPath.setPromptText(tr("remote.keyPrompt"));
        HBox.setHgrow(keyPath, Priority.ALWAYS);
        Button keyBrowse = new Button(tr("dialog.clone.browse"));
        keyBrowse.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(tr("remote.keyPrompt"));
            java.io.File f = fc.showOpenDialog(keyBrowse.getScene().getWindow());
            if (f != null) {
                keyPath.setText(f.getAbsolutePath());
            }
        });
        HBox keyRow = new HBox(6, keyPath, keyBrowse);
        // The key-file row only applies to "Private key file" auth.
        auth.valueProperty()
                .addListener((o, a, b) -> keyRow.setDisable(b != com.editora.vfs.RemoteConnection.AuthMethod.KEY));

        javafx.scene.layout.GridPane form = new javafx.scene.layout.GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.remote.label"), label);
        formRow(form, 1, tr("remote.host"), host);
        formRow(form, 2, tr("remote.port"), port);
        formRow(form, 3, tr("remote.user"), user);
        formRow(form, 4, tr("remote.auth"), auth);
        formRow(form, 5, tr("remote.key"), keyRow);
        form.setDisable(true);
        HBox.setHgrow(form, Priority.ALWAYS);

        Runnable commit = () -> {
            int i = list.getSelectionModel().getSelectedIndex();
            com.editora.vfs.RemoteConnection cur = list.getSelectionModel().getSelectedItem();
            if (cur == null || i < 0 || loadingRemote) {
                return;
            }
            int portNum;
            try {
                portNum = Integer.parseInt(port.getText().strip());
            } catch (NumberFormatException ex) {
                portNum = 0; // the record coerces <= 0 to the default SFTP port
            }
            com.editora.vfs.RemoteConnection updated = new com.editora.vfs.RemoteConnection(
                    host.getText().strip(),
                    portNum,
                    user.getText().strip(),
                    auth.getValue() == null
                            ? com.editora.vfs.RemoteConnection.AuthMethod.DEFAULT_KEYS
                            : auth.getValue(),
                    keyPath.getText().strip(),
                    label.getText().strip(),
                    cur.lastPath()); // preserve the remembered path; not edited here
            remoteItems.set(i, updated);
            list.refresh();
            persistRemote();
        };
        auth.valueProperty().addListener((o, a, b) -> commit.run());
        java.util.function.Consumer<TextField> wire = tf -> {
            tf.setOnAction(e -> commit.run());
            tf.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        };
        wire.accept(label);
        wire.accept(host);
        wire.accept(port);
        wire.accept(user);
        wire.accept(keyPath);

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            loadingRemote = true;
            try {
                form.setDisable(now == null);
                label.setText(now == null ? "" : nullToEmpty(now.label()));
                host.setText(now == null ? "" : nullToEmpty(now.host()));
                port.setText(now == null ? "" : String.valueOf(now.port()));
                user.setText(now == null ? "" : nullToEmpty(now.user()));
                auth.setValue(now == null ? null : now.auth());
                keyPath.setText(now == null ? "" : nullToEmpty(now.keyPath()));
                keyRow.setDisable(now == null || now.auth() != com.editora.vfs.RemoteConnection.AuthMethod.KEY);
            } finally {
                loadingRemote = false;
            }
        });

        Button add = new Button(tr("settings.remote.add"));
        add.setOnAction(e -> {
            com.editora.vfs.RemoteConnection c = new com.editora.vfs.RemoteConnection(
                    "",
                    com.editora.vfs.SftpUri.DEFAULT_PORT,
                    System.getProperty("user.name", ""),
                    com.editora.vfs.RemoteConnection.AuthMethod.DEFAULT_KEYS,
                    "",
                    tr("settings.remote.newName"),
                    "");
            remoteItems.add(c);
            persistRemote();
            list.getSelectionModel().select(c);
        });
        Button remove = new Button(tr("settings.remote.remove"));
        remove.setOnAction(e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                remoteItems.remove(i);
                persistRemote();
            }
        });
        HBox buttons = new HBox(6, add, remove);
        VBox left = new VBox(6, list, buttons);

        // Explicit Save (edits also auto-save on Enter / focus-loss, so nothing is lost on row switch).
        Button save = new Button(tr("settings.save"));
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit.run());
        HBox saveRow = new HBox(save);
        saveRow.setAlignment(Pos.CENTER_RIGHT);
        VBox right = new VBox(8, form, saveRow);
        VBox.setVgrow(form, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        if (!remoteItems.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        HBox box = new HBox(12, left, right);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    private void persistRemote() {
        config.setConnections(new java.util.ArrayList<>(remoteItems));
        apply();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Opens Settings focused on the Remote page (the {@code remote.settings} command). */
    public void showRemote(Window owner) {
        show(owner);
        sidebar.getSelectionModel().select(Category.REMOTE);
        remoteItems.setAll(config.getConnections());
    }

    private VBox externalToolsPage() {
        VBox p = page(tr("settings.cat.externalTools"));
        row(
                p,
                Category.EXTERNAL_TOOLS,
                null,
                externalToolsEditor(),
                "external tools command cli macro stdin output console run formatter filter");
        Label macros = note(tr("settings.externalTool.macrosHelp"));
        macros.setWrapText(true);
        macros.setMaxWidth(460);
        row(p, Category.EXTERNAL_TOOLS, null, macros, "external tools macros filepath selection linenumber");
        return p;
    }

    /** Master-detail editor: a list of tools on the left, a form for the selected tool on the right. */
    private javafx.scene.Node externalToolsEditor() {
        externalToolItems.setAll(copyTools(config.getSettings().getExternalTools()));

        ListView<com.editora.externaltool.ExternalTool> list = new ListView<>(externalToolItems);
        list.setPrefSize(170, 220);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.editora.externaltool.ExternalTool t, boolean empty) {
                super.updateItem(t, empty);
                setText(
                        empty || t == null
                                ? null
                                : (t.getName().isBlank() ? tr("settings.externalTool.unnamed") : t.getName()));
            }
        });

        TextField name = new TextField();
        TextField command = new TextField();
        TextField arguments = new TextField();
        TextField workingDir = new TextField();
        workingDir.setPromptText(tr("settings.externalTool.workingDirPrompt"));
        ComboBox<com.editora.externaltool.ExternalTool.StdinSource> stdin =
                new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                        com.editora.externaltool.ExternalTool.StdinSource.values()));
        stdin.setConverter(enumConverter(s -> tr("externalTool.stdin." + s.name())));
        ComboBox<com.editora.externaltool.ExternalTool.OutputTarget> output =
                new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
                        com.editora.externaltool.ExternalTool.OutputTarget.values()));
        output.setConverter(enumConverter(o -> tr("externalTool.output." + o.name())));
        CheckBox enabled = new CheckBox(tr("settings.externalTool.enabled"));

        javafx.scene.layout.GridPane form = new javafx.scene.layout.GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.externalTool.name"), name);
        formRow(form, 1, tr("settings.externalTool.command"), command);
        formRow(form, 2, tr("settings.externalTool.arguments"), arguments);
        formRow(form, 3, tr("settings.externalTool.workingDir"), workingDir);
        formRow(form, 4, tr("settings.externalTool.stdin"), stdin);
        formRow(form, 5, tr("settings.externalTool.output"), output);
        form.add(enabled, 1, 6);
        form.setDisable(true);
        HBox.setHgrow(form, Priority.ALWAYS);

        Runnable commit = () -> {
            com.editora.externaltool.ExternalTool t = list.getSelectionModel().getSelectedItem();
            if (t == null || loadingExternalTool) {
                return;
            }
            t.setName(name.getText());
            t.setCommand(command.getText());
            t.setArguments(arguments.getText());
            t.setWorkingDir(workingDir.getText());
            if (stdin.getValue() != null) {
                t.setStdin(stdin.getValue());
            }
            if (output.getValue() != null) {
                t.setOutput(output.getValue());
            }
            t.setEnabled(enabled.isSelected());
            list.refresh();
            persistExternalTools();
        };
        // Combos + checkbox apply immediately; text fields commit on Enter / focus-loss (the todoRow idiom).
        stdin.valueProperty().addListener((o, a, b) -> commit.run());
        output.valueProperty().addListener((o, a, b) -> commit.run());
        enabled.selectedProperty().addListener((o, a, b) -> commit.run());
        java.util.function.Consumer<TextField> wire = tf -> {
            tf.setOnAction(e -> commit.run());
            tf.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        };
        wire.accept(name);
        wire.accept(command);
        wire.accept(arguments);
        wire.accept(workingDir);

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            loadingExternalTool = true;
            try {
                form.setDisable(now == null);
                name.setText(now == null ? "" : now.getName());
                command.setText(now == null ? "" : now.getCommand());
                arguments.setText(now == null ? "" : now.getArguments());
                workingDir.setText(now == null ? "" : now.getWorkingDir());
                stdin.setValue(now == null ? null : now.getStdin());
                output.setValue(now == null ? null : now.getOutput());
                enabled.setSelected(now != null && now.isEnabled());
            } finally {
                loadingExternalTool = false;
            }
        });

        Button add = new Button(tr("settings.externalTool.add"));
        add.setOnAction(e -> {
            com.editora.externaltool.ExternalTool t = new com.editora.externaltool.ExternalTool(
                    tr("settings.externalTool.newName"),
                    "",
                    "",
                    "",
                    com.editora.externaltool.ExternalTool.StdinSource.NONE,
                    com.editora.externaltool.ExternalTool.OutputTarget.CONSOLE,
                    true);
            externalToolItems.add(t);
            persistExternalTools();
            list.getSelectionModel().select(t);
        });
        Button remove = new Button(tr("settings.externalTool.remove"));
        remove.setOnAction(e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                externalToolItems.remove(i);
                persistExternalTools();
            }
        });
        // Explicit Save (edits also auto-save on Enter / focus-loss + combo/checkbox change).
        Button save = new Button(tr("settings.save"));
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit.run());

        VBox left = new VBox(6, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox right = new VBox(8, form);
        VBox.setVgrow(form, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        if (!externalToolItems.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        HBox top = new HBox(12, left, right);
        top.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(top, Priority.ALWAYS);

        // One bottom bar below the whole editor: Add / Remove on the left, Save aligned on the right.
        HBox buttons = new HBox(6, add, remove, spacer(), save);
        buttons.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, top, buttons);
    }

    /** Deep-copies the persisted tools so the working list edits independently until persisted. */
    private static java.util.List<com.editora.externaltool.ExternalTool> copyTools(
            java.util.List<com.editora.externaltool.ExternalTool> src) {
        java.util.List<com.editora.externaltool.ExternalTool> out = new java.util.ArrayList<>();
        for (com.editora.externaltool.ExternalTool t : src) {
            out.add(new com.editora.externaltool.ExternalTool(
                    t.getName(),
                    t.getCommand(),
                    t.getArguments(),
                    t.getWorkingDir(),
                    t.getStdin(),
                    t.getOutput(),
                    t.isEnabled()));
        }
        return out;
    }

    private void persistExternalTools() {
        config.getSettings().setExternalTools(new java.util.ArrayList<>(externalToolItems));
        apply();
    }

    private static <T> StringConverter<T> enumConverter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }

    private void formRow(javafx.scene.layout.GridPane form, int rowIndex, String labelText, javafx.scene.Node field) {
        Label l = new Label(labelText);
        // A Label's computed min width is just an ellipsis, so a tight GridPane collapses it to "…".
        // Pin its min to its preferred (text) width so the field-row labels stay readable.
        l.setMinWidth(Region.USE_PREF_SIZE);
        form.add(l, 0, rowIndex);
        form.add(field, 1, rowIndex);
        if (field instanceof javafx.scene.layout.Region r) {
            r.setMinWidth(220);
        }
        // A tall multi-line body would otherwise centre its label; align it to the top of the field instead.
        if (field instanceof CodeArea) {
            javafx.scene.layout.GridPane.setValignment(l, javafx.geometry.VPos.TOP);
        }
    }

    /** Re-highlights a snippet/template body {@link CodeArea} for {@code languageName} (plain for global/unknown). */
    private static void highlightSnippetBody(CodeArea area, String languageName) {
        String text = area.getText();
        IGrammar g = null;
        if (languageName != null && !languageName.isBlank() && !"global".equals(languageName)) {
            try {
                g = GrammarRegistry.shared().forLanguageName(languageName);
            } catch (RuntimeException ignored) {
                // No grammar for this language: leave the body unstyled.
            }
        }
        if (g == null) {
            if (!text.isEmpty()) {
                area.clearStyle(0, text.length());
            }
            return;
        }
        try {
            area.setStyleSpans(0, TextMateHighlighter.compute(text, g));
        } catch (RuntimeException ignored) {
            // Tokenizer hiccup: keep the last styling rather than crash the editor.
        }
    }

    /** Installs basic Emacs caret movement on a settings-scene {@link CodeArea} (no global KeyDispatcher there).
     *  Uses absolute-offset {@code moveTo}/{@code deleteText} (robust across RichTextFX versions); each action is
     *  guarded so the key is always consumed (no fall-through to the default behaviour) even at a boundary. */
    private static void installEmacsKeys(CodeArea area) {
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            boolean ctrl = e.isControlDown() && !e.isAltDown() && !e.isMetaDown() && !e.isShiftDown();
            boolean alt = e.isAltDown() && !e.isControlDown() && !e.isMetaDown() && !e.isShiftDown();
            if (!ctrl && !alt) {
                return;
            }
            int caret = area.getCaretPosition();
            int len = area.getLength();
            String text = area.getText();
            if (ctrl) {
                switch (e.getCode()) {
                    case A -> consume(e, () -> area.moveTo(area.getCurrentParagraph(), 0));
                    case E ->
                        consume(e, () -> {
                            int par = area.getCurrentParagraph();
                            area.moveTo(par, area.getParagraphLength(par));
                        });
                    case F ->
                        consume(e, () -> {
                            if (caret < len) {
                                area.moveTo(caret + 1);
                            }
                        });
                    case B ->
                        consume(e, () -> {
                            if (caret > 0) {
                                area.moveTo(caret - 1);
                            }
                        });
                    case N -> consume(e, () -> emacsMoveLine(area, 1));
                    case P -> consume(e, () -> emacsMoveLine(area, -1));
                    case D ->
                        consume(e, () -> {
                            if (caret < len) {
                                area.deleteText(caret, caret + 1);
                            }
                        });
                    case K -> consume(e, () -> emacsKillLine(area));
                    default -> {}
                }
            } else {
                switch (e.getCode()) {
                    case F -> consume(e, () -> area.moveTo(nextWordBoundary(text, caret)));
                    case B -> consume(e, () -> area.moveTo(prevWordBoundary(text, caret)));
                    default -> {}
                }
            }
        });
    }

    private static void consume(javafx.scene.input.KeyEvent e, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // A move/delete at a boundary must not crash key handling.
        }
        e.consume();
    }

    private static void emacsMoveLine(CodeArea area, int delta) {
        int target = area.getCurrentParagraph() + delta;
        if (target < 0 || target >= area.getParagraphs().size()) {
            return;
        }
        area.moveTo(target, Math.min(area.getCaretColumn(), area.getParagraphLength(target)));
    }

    private static void emacsKillLine(CodeArea area) {
        int caret = area.getCaretPosition();
        int rest = area.getParagraphLength(area.getCurrentParagraph()) - area.getCaretColumn();
        if (rest > 0) {
            area.deleteText(caret, caret + rest); // kill to end of line
        } else if (caret < area.getLength()) {
            area.deleteText(caret, caret + 1); // at end-of-line: join the next line up
        }
    }

    /** Offset of the end of the next word at/after {@code i} (Emacs M-f). Pure. */
    private static int nextWordBoundary(String s, int i) {
        int n = s.length();
        while (i < n && !Character.isLetterOrDigit(s.charAt(i))) {
            i++;
        }
        while (i < n && Character.isLetterOrDigit(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Offset of the start of the previous word at/before {@code i} (Emacs M-b). Pure. */
    private static int prevWordBoundary(String s, int i) {
        while (i > 0 && !Character.isLetterOrDigit(s.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && Character.isLetterOrDigit(s.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private VBox mcpPage() {
        VBox p = page(tr("settings.cat.mcp"));
        row(p, Category.MCP, null, mcpCheck, "mcp model context protocol agent server enable ai claude");
        Label hint = note(tr("settings.mcp.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        row(p, Category.MCP, null, hint, "mcp agent llm command palette diagnostics search loopback token endpoint");
        return p;
    }

    private VBox pluginsPage() {
        VBox p = page(tr("settings.cat.plugins"));
        Label warn = note(tr("settings.plugins.note"));
        warn.getStyleClass().add("settings-experimental");
        warn.setWrapText(true);
        warn.setMaxWidth(440);
        row(p, Category.PLUGINS, null, warn, "plugins extensions untrusted security warning");
        row(p, Category.PLUGINS, null, pluginCheck, "plugins extensions enable support");

        Label folderLabel = new Label(tr("settings.plugins.folder"));
        Label folderPath =
                new Label(pluginManager == null ? "" : config.getPluginsDir().toString());
        folderPath.getStyleClass().add("settings-hint");
        folderPath.setWrapText(true);
        folderPath.setMaxWidth(380);
        HBox folderRow = new HBox(6, folderLabel, folderPath);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        row(p, Category.PLUGINS, null, folderRow, "plugins folder directory location path");

        Button reload = new Button(tr("settings.plugins.reload"));
        reload.setOnAction(e -> {
            if (pluginManager != null) {
                pluginManager.discover();
                refreshPluginList();
            }
        });
        row(p, Category.PLUGINS, null, reload, "plugins reload rescan discover refresh");

        Label restart = note(tr("settings.plugins.restart"));
        restart.setWrapText(true);
        restart.setMaxWidth(440);
        row(p, Category.PLUGINS, null, restart, "plugins restart apply");

        // --- Marketplace: a curated GitHub-hosted registry + install-from-disk.
        Label market = section(p, tr("settings.plugins.marketplace"));
        pluginRegistryField = new TextField();
        pluginRegistryField.setPromptText(com.editora.config.Settings.DEFAULT_PLUGIN_REGISTRY);
        // Wide enough to show a full registry URL (the default is ~75 chars); also grows with the page.
        pluginRegistryField.setPrefColumnCount(64);
        pluginRegistryField.setPrefWidth(560);
        pluginRegistryField.setMaxWidth(Double.MAX_VALUE);
        pluginRegistryWarn = new Label();
        pluginRegistryWarn.getStyleClass().add("settings-git-missing"); // amber/red "caution" styling
        pluginRegistryWarn.setWrapText(true);
        pluginRegistryWarn.setMaxWidth(440);
        pluginRegistryField.textProperty().addListener((obs, was, now) -> {
            config.getSettings().setPluginRegistryUrl(now);
            apply();
            updateRegistryWarn();
        });
        Label regNote = note(tr("settings.plugins.registryNote"));
        regNote.setWrapText(true);
        regNote.setMaxWidth(440);
        VBox regBox = new VBox(4, pluginRegistryField, pluginRegistryWarn, regNote);
        row(
                p,
                Category.PLUGINS,
                market,
                labeled(tr("settings.plugins.registryUrl"), regBox),
                "plugins registry url index marketplace github browse");
        Label sigNote = note(tr("settings.plugins.requireSignatureNote"));
        sigNote.setWrapText(true);
        sigNote.setMaxWidth(440);
        VBox sigBox = new VBox(2, pluginRequireSigCheck, sigNote);
        row(p, Category.PLUGINS, market, sigBox, "plugins signature signed verify registry security trust");
        Button browse = new Button(tr("settings.plugins.browse"));
        browse.setOnAction(e -> {
            if (onBrowsePlugins != null) {
                // The browse picker is an in-scene overlay in the MAIN window; hide Settings first so it
                // isn't rendered behind this window (otherwise the click appears to do nothing).
                stage.hide();
                onBrowsePlugins.run();
            }
        });
        Button installFile = new Button(tr("settings.plugins.installFromFile"));
        installFile.setOnAction(e -> {
            if (onInstallPluginFromFile != null) {
                onInstallPluginFromFile.run();
            }
        });
        HBox marketButtons = new HBox(8, browse, installFile);
        marketButtons.setAlignment(Pos.CENTER_LEFT);
        row(p, Category.PLUGINS, market, marketButtons, "plugins browse install file zip marketplace registry");

        Label installed = section(p, tr("settings.plugins.installed"));
        pluginListBox = new VBox(8);
        row(p, Category.PLUGINS, installed, pluginListBox, "plugins installed list enable disable");
        refreshPluginList();
        return p;
    }

    /** Rebuilds the per-plugin enable list from the shared {@link com.editora.plugin.PluginManager}. */
    private void refreshPluginList() {
        if (pluginListBox == null) {
            return;
        }
        pluginListBox.getChildren().clear();
        java.util.List<com.editora.plugin.PluginDescriptor> ds =
                pluginManager == null ? java.util.List.of() : pluginManager.descriptors();
        if (ds.isEmpty()) {
            Label empty = note(tr("settings.plugins.none"));
            empty.setWrapText(true);
            pluginListBox.getChildren().add(empty);
            return;
        }
        boolean master = config.getSettings().isPluginSupport();
        for (com.editora.plugin.PluginDescriptor d : ds) {
            String name = d.manifest().name == null || d.manifest().name.isBlank() ? d.id() : d.manifest().name;
            String ver = d.manifest().version == null ? "" : d.manifest().version;
            String label = ver.isBlank() ? name + "  (" + d.id() + ")" : name + "  " + ver + "  (" + d.id() + ")";
            CheckBox cb = new CheckBox(label);
            cb.setSelected(config.getPluginStore().isEnabled(d.id()));
            cb.setDisable(!master);
            cb.selectedProperty().addListener((obs, was, now) -> {
                if (loading) {
                    return;
                }
                // Enabling arms code on the next launch — disclose capabilities + confirm. Disabling is free.
                if (now && !confirmEnablePlugin(d)) {
                    cb.setSelected(false); // user declined; revert (fires again with now=false → persists off)
                    return;
                }
                config.getPluginStore().setEnabled(d.id(), now);
                config.savePlugins();
            });
            javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button remove = new Button(tr("settings.plugins.remove"));
            remove.getStyleClass().add("settings-link-button");
            remove.setOnAction(ev -> {
                if (onUninstallPlugin != null) {
                    onUninstallPlugin.accept(d.id());
                }
            });
            HBox header = new HBox(8, cb, spacer, remove);
            header.setAlignment(Pos.CENTER_LEFT);
            VBox entry = new VBox(2, header);
            if (d.loadError() != null) {
                Label err = new Label(d.loadError());
                err.getStyleClass().add("settings-git-missing");
                err.setWrapText(true);
                err.setMaxWidth(420);
                err.setPadding(new Insets(0, 0, 0, 20));
                entry.getChildren().add(err);
            }
            pluginListBox.getChildren().add(entry);
        }
    }

    /** Warns (with the host) when the registry URL isn't the bundled default — a phishing-vector guard. */
    private void updateRegistryWarn() {
        if (pluginRegistryWarn == null) {
            return;
        }
        String url = config.getSettings().getPluginRegistryUrl();
        boolean custom = url != null
                && !url.isBlank()
                && !url.strip().equals(com.editora.config.Settings.DEFAULT_PLUGIN_REGISTRY);
        String host = "";
        if (custom) {
            try {
                host = java.net.URI.create(url.strip()).getHost();
            } catch (RuntimeException ignored) {
                host = url.strip();
            }
        }
        pluginRegistryWarn.setText(custom ? tr("settings.plugins.customRegistry", host == null ? "?" : host) : "");
        pluginRegistryWarn.setVisible(custom);
        pluginRegistryWarn.setManaged(custom);
    }

    /** Capability-disclosure confirm before enabling a plugin (mirrors the install gate). */
    private boolean confirmEnablePlugin(com.editora.plugin.PluginDescriptor d) {
        String name = d.manifest().name == null || d.manifest().name.isBlank() ? d.id() : d.manifest().name;
        String body = tr(
                "dialog.plugins.enableBody",
                name,
                d.manifest().version == null ? "" : d.manifest().version,
                PluginCoordinator.pluginCapabilitySummary(d.manifest(), d.hasJavaEntry()));
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, body, ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.plugins.enableTitle"));
        confirm.setHeaderText(tr("dialog.plugins.enableHeader"));
        confirm.getDialogPane().setMinWidth(480);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private VBox debugPage() {
        VBox p = page(tr("settings.cat.debug"));
        Label experimental = note(tr("settings.debug.experimental"));
        experimental.getStyleClass().add("settings-experimental");
        experimental.setWrapText(true);
        experimental.setMaxWidth(440);
        row(p, Category.DEBUG, null, experimental, "debug experimental beta");
        row(p, Category.DEBUG, null, debugCheck, "debug dap breakpoint step variables enable");
        for (DebugAdapterUi dbg : debugAdapterUis()) {
            Label sec = section(p, tr(dbg.sectionKey()));
            CheckBox enable = debugEnableChecks.get(dbg.id());
            if (enable != null) {
                row(p, Category.DEBUG, sec, enable, dbg.keywords());
            }
            Label status = new Label(tr("settings.debug.checking"));
            status.getStyleClass().add("settings-git-status");
            status.setWrapText(true);
            status.setMaxWidth(440);
            debugStatusLabels.put(dbg.id(), status);
            row(p, Category.DEBUG, sec, status, dbg.keywords());
            row(
                    p,
                    Category.DEBUG,
                    sec,
                    exePathRow(tr(dbg.commandLabelKey()), debugCommandFields.get(dbg.id())),
                    dbg.keywords());
        }
        Label hint = note(tr("settings.debug.note"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        row(p, Category.DEBUG, null, hint, "debug install plugin vscode mason jdtls debugpy js-debug");
        return p;
    }

    /** A per-language debug-adapter Settings row group (data-driven, mirroring {@link LspServerUi}).
     *  {@code setEnabled}/{@code getEnabled} are null for java (it has no separate enable flag — it is
     *  gated by the java LSP server). {@code detect} runs the availability probe and reports found. */
    private record DebugAdapterUi(
            String id,
            String sectionKey,
            String enableLabelKey,
            String commandLabelKey,
            String commandPrompt,
            String keywords,
            java.util.function.Consumer<Boolean> setEnabled,
            java.util.function.BooleanSupplier getEnabled,
            java.util.function.Consumer<String> setCommand,
            java.util.function.Supplier<String> getCommand,
            java.util.function.Consumer<Consumer<Boolean>> detect) {}

    /** The three debug adapters (java/python/javascript), in display order. */
    private java.util.List<DebugAdapterUi> debugAdapterUis() {
        return java.util.List.of(
                new DebugAdapterUi(
                        "java",
                        "settings.debug.java",
                        null,
                        "settings.debug.pluginPath",
                        "com.microsoft.java.debug.plugin-*.jar",
                        "debug java jdtls java-debug plugin jar path found",
                        null,
                        null,
                        v -> config.getSettings().setJavaDebugPluginPath(v),
                        () -> config.getSettings().getJavaDebugPluginPath(),
                        cb -> cb.accept(com.editora.dap.DebugAdapterLocator.locate(
                                        config.getSettings().getJavaDebugPluginPath(),
                                        java.nio.file.Path.of(System.getProperty("user.home", "")))
                                .isPresent())),
                new DebugAdapterUi(
                        "python",
                        "settings.debug.python",
                        "settings.debug.enablePython",
                        "settings.debug.pythonCommand",
                        com.editora.dap.DapServerRegistry.DEFAULT_PYTHON_INTERPRETER,
                        "debug python debugpy interpreter command path found",
                        v -> config.getSettings().setPythonDebugEnabled(v),
                        () -> config.getSettings().isPythonDebugEnabled(),
                        v -> config.getSettings().setPythonDebugCommand(v),
                        () -> config.getSettings().getPythonDebugCommand(),
                        cb -> {
                            if (dapManager != null) {
                                dapManager.detectPython(cb);
                            } else {
                                cb.accept(false);
                            }
                        }),
                new DebugAdapterUi(
                        "javascript",
                        "settings.debug.javascript",
                        "settings.debug.enableJs",
                        "settings.debug.jsPath",
                        "dapDebugServer.js",
                        "debug javascript node js-debug vscode dapDebugServer path found",
                        v -> config.getSettings().setJsDebugEnabled(v),
                        () -> config.getSettings().isJsDebugEnabled(),
                        v -> config.getSettings().setJsDebugPath(v),
                        () -> config.getSettings().getJsDebugPath(),
                        cb -> {
                            if (dapManager != null) {
                                dapManager.detectJs(cb);
                            } else {
                                cb.accept(false);
                            }
                        }));
    }

    private VBox lspPage() {
        VBox p = page(tr("settings.cat.lsp"));
        Label experimental = note(tr("settings.lsp.experimental"));
        experimental.getStyleClass().add("settings-experimental");
        experimental.setWrapText(true);
        experimental.setMaxWidth(440);
        row(p, Category.LSP, null, experimental, "lsp experimental beta");
        row(
                p,
                Category.LSP,
                null,
                lspCheck,
                "lsp language server protocol enable java typescript python xml json bash diagnostics");
        row(p, Category.LSP, null, lspInstallPromptsCheck, "lsp install banner prompt offer language support");
        for (LspServerUi srv : lspServerUis()) {
            row(p, Category.LSP, null, lspEnableChecks.get(srv.id()), srv.keywords());
            Label status = new Label(tr("settings.lsp.checking"));
            status.getStyleClass().add("settings-git-status");
            status.setWrapText(true);
            status.setMaxWidth(440);
            lspStatusLabels.put(srv.id(), status);
            row(p, Category.LSP, null, status, srv.keywords());
            String langKey = installLangForServer(srv.id());
            if (langKey != null) {
                row(p, Category.LSP, null, installButton(langKey), srv.keywords() + " install download");
            } else if (com.editora.install.InstallCatalog.installableServerIds().contains(srv.id())) {
                row(p, Category.LSP, null, installServerButton(srv.id()), srv.keywords() + " install download");
            }
            row(
                    p,
                    Category.LSP,
                    null,
                    exePathRow(tr(srv.commandLabelKey()), lspCommandFields.get(srv.id())),
                    srv.keywords());
        }
        Label hint = note(tr("settings.lsp.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        row(p, Category.LSP, null, hint, "lsp install language server jdtls pyright lemminx bash");
        return p;
    }

    /** A configurable LSP server's Settings row (data-driven so adding a server is one descriptor). */
    private record LspServerUi(
            String id,
            String defaultCommand,
            String enableLabelKey,
            String commandLabelKey,
            String statusKey,
            String keywords,
            java.util.function.Consumer<Boolean> setEnabled,
            java.util.function.BooleanSupplier getEnabled,
            java.util.function.Consumer<String> setCommand,
            java.util.function.Supplier<String> getCommand) {}

    /** The six configurable LSP servers, in display order. Lambdas read/write the live {@code Settings}. */
    private java.util.List<LspServerUi> lspServerUis() {
        return java.util.List.of(
                new LspServerUi(
                        "java",
                        com.editora.lsp.LspServerRegistry.DEFAULT_JAVA_COMMAND,
                        "settings.lsp.enableJava",
                        "settings.lsp.javaCommand",
                        "settings.lsp.status",
                        "lsp java jdtls language server found installed not found command path executable",
                        v -> config.getSettings().setJavaLspEnabled(v),
                        () -> config.getSettings().isJavaLspEnabled(),
                        v -> config.getSettings().setJavaLspCommand(v),
                        () -> config.getSettings().getJavaLspCommand()),
                new LspServerUi(
                        "typescript",
                        com.editora.lsp.LspServerRegistry.DEFAULT_TYPESCRIPT_COMMAND,
                        "settings.lsp.enableTypescript",
                        "settings.lsp.typescriptCommand",
                        "settings.lsp.tsStatus",
                        "lsp typescript javascript language server found installed not found command path",
                        v -> config.getSettings().setTypescriptLspEnabled(v),
                        () -> config.getSettings().isTypescriptLspEnabled(),
                        v -> config.getSettings().setTypescriptLspCommand(v),
                        () -> config.getSettings().getTypescriptLspCommand()),
                new LspServerUi(
                        "python",
                        com.editora.lsp.LspServerRegistry.DEFAULT_PYTHON_COMMAND,
                        "settings.lsp.enablePython",
                        "settings.lsp.pythonCommand",
                        "settings.lsp.pyStatus",
                        "lsp python pyright language server found installed not found command path executable",
                        v -> config.getSettings().setPythonLspEnabled(v),
                        () -> config.getSettings().isPythonLspEnabled(),
                        v -> config.getSettings().setPythonLspCommand(v),
                        () -> config.getSettings().getPythonLspCommand()),
                new LspServerUi(
                        "xml",
                        com.editora.lsp.LspServerRegistry.DEFAULT_XML_COMMAND,
                        "settings.lsp.enableXml",
                        "settings.lsp.xmlCommand",
                        "settings.lsp.xmlStatus",
                        "lsp xml lemminx language server found installed not found command path executable",
                        v -> config.getSettings().setXmlLspEnabled(v),
                        () -> config.getSettings().isXmlLspEnabled(),
                        v -> config.getSettings().setXmlLspCommand(v),
                        () -> config.getSettings().getXmlLspCommand()),
                new LspServerUi(
                        "json",
                        com.editora.lsp.LspServerRegistry.DEFAULT_JSON_COMMAND,
                        "settings.lsp.enableJson",
                        "settings.lsp.jsonCommand",
                        "settings.lsp.jsonStatus",
                        "lsp json language server found installed not found command path executable",
                        v -> config.getSettings().setJsonLspEnabled(v),
                        () -> config.getSettings().isJsonLspEnabled(),
                        v -> config.getSettings().setJsonLspCommand(v),
                        () -> config.getSettings().getJsonLspCommand()),
                new LspServerUi(
                        "bash",
                        com.editora.lsp.LspServerRegistry.DEFAULT_BASH_COMMAND,
                        "settings.lsp.enableBash",
                        "settings.lsp.bashCommand",
                        "settings.lsp.bashStatus",
                        "lsp bash shell shellcheck language server found installed not found command path",
                        v -> config.getSettings().setBashLspEnabled(v),
                        () -> config.getSettings().isBashLspEnabled(),
                        v -> config.getSettings().setBashLspCommand(v),
                        () -> config.getSettings().getBashLspCommand()),
                new LspServerUi(
                        "yaml",
                        com.editora.lsp.LspServerRegistry.DEFAULT_YAML_COMMAND,
                        "settings.lsp.enableYaml",
                        "settings.lsp.yamlCommand",
                        "settings.lsp.yamlStatus",
                        "lsp yaml yml language server found installed not found command path executable",
                        v -> config.getSettings().setYamlLspEnabled(v),
                        () -> config.getSettings().isYamlLspEnabled(),
                        v -> config.getSettings().setYamlLspCommand(v),
                        () -> config.getSettings().getYamlLspCommand()),
                new LspServerUi(
                        "go",
                        com.editora.lsp.LspServerRegistry.DEFAULT_GO_COMMAND,
                        "settings.lsp.enableGo",
                        "settings.lsp.goCommand",
                        "settings.lsp.goStatus",
                        "lsp go golang gopls language server found installed not found command path",
                        v -> config.getSettings().setGoLspEnabled(v),
                        () -> config.getSettings().isGoLspEnabled(),
                        v -> config.getSettings().setGoLspCommand(v),
                        () -> config.getSettings().getGoLspCommand()),
                new LspServerUi(
                        "rust",
                        com.editora.lsp.LspServerRegistry.DEFAULT_RUST_COMMAND,
                        "settings.lsp.enableRust",
                        "settings.lsp.rustCommand",
                        "settings.lsp.rustStatus",
                        "lsp rust rust-analyzer cargo language server found installed not found command path",
                        v -> config.getSettings().setRustLspEnabled(v),
                        () -> config.getSettings().isRustLspEnabled(),
                        v -> config.getSettings().setRustLspCommand(v),
                        () -> config.getSettings().getRustLspCommand()),
                new LspServerUi(
                        "php",
                        com.editora.lsp.LspServerRegistry.DEFAULT_PHP_COMMAND,
                        "settings.lsp.enablePhp",
                        "settings.lsp.phpCommand",
                        "settings.lsp.phpStatus",
                        "lsp php phpactor intelephense language server found installed not found command path",
                        v -> config.getSettings().setPhpLspEnabled(v),
                        () -> config.getSettings().isPhpLspEnabled(),
                        v -> config.getSettings().setPhpLspCommand(v),
                        () -> config.getSettings().getPhpLspCommand()),
                new LspServerUi(
                        "ruby",
                        com.editora.lsp.LspServerRegistry.DEFAULT_RUBY_COMMAND,
                        "settings.lsp.enableRuby",
                        "settings.lsp.rubyCommand",
                        "settings.lsp.rubyStatus",
                        "lsp ruby ruby-lsp solargraph language server found installed not found command path",
                        v -> config.getSettings().setRubyLspEnabled(v),
                        () -> config.getSettings().isRubyLspEnabled(),
                        v -> config.getSettings().setRubyLspCommand(v),
                        () -> config.getSettings().getRubyLspCommand()),
                new LspServerUi(
                        "clangd",
                        com.editora.lsp.LspServerRegistry.DEFAULT_CLANGD_COMMAND,
                        "settings.lsp.enableClangd",
                        "settings.lsp.clangdCommand",
                        "settings.lsp.clangdStatus",
                        "lsp c cpp c++ clangd language server found installed not found command path",
                        v -> config.getSettings().setClangdLspEnabled(v),
                        () -> config.getSettings().isClangdLspEnabled(),
                        v -> config.getSettings().setClangdLspCommand(v),
                        () -> config.getSettings().getClangdLspCommand()),
                new LspServerUi(
                        "html",
                        com.editora.lsp.LspServerRegistry.DEFAULT_HTML_COMMAND,
                        "settings.lsp.enableHtml",
                        "settings.lsp.htmlCommand",
                        "settings.lsp.htmlStatus",
                        "lsp html language server found installed not found command path executable",
                        v -> config.getSettings().setHtmlLspEnabled(v),
                        () -> config.getSettings().isHtmlLspEnabled(),
                        v -> config.getSettings().setHtmlLspCommand(v),
                        () -> config.getSettings().getHtmlLspCommand()),
                new LspServerUi(
                        "css",
                        com.editora.lsp.LspServerRegistry.DEFAULT_CSS_COMMAND,
                        "settings.lsp.enableCss",
                        "settings.lsp.cssCommand",
                        "settings.lsp.cssStatus",
                        "lsp css scss less language server found installed not found command path",
                        v -> config.getSettings().setCssLspEnabled(v),
                        () -> config.getSettings().isCssLspEnabled(),
                        v -> config.getSettings().setCssLspCommand(v),
                        () -> config.getSettings().getCssLspCommand()),
                new LspServerUi(
                        "kotlin",
                        com.editora.lsp.LspServerRegistry.DEFAULT_KOTLIN_COMMAND,
                        "settings.lsp.enableKotlin",
                        "settings.lsp.kotlinCommand",
                        "settings.lsp.kotlinStatus",
                        "lsp kotlin language server found installed not found command path executable",
                        v -> config.getSettings().setKotlinLspEnabled(v),
                        () -> config.getSettings().isKotlinLspEnabled(),
                        v -> config.getSettings().setKotlinLspCommand(v),
                        () -> config.getSettings().getKotlinLspCommand()),
                new LspServerUi(
                        "lua",
                        com.editora.lsp.LspServerRegistry.DEFAULT_LUA_COMMAND,
                        "settings.lsp.enableLua",
                        "settings.lsp.luaCommand",
                        "settings.lsp.luaStatus",
                        "lsp lua language server found installed not found command path executable",
                        v -> config.getSettings().setLuaLspEnabled(v),
                        () -> config.getSettings().isLuaLspEnabled(),
                        v -> config.getSettings().setLuaLspCommand(v),
                        () -> config.getSettings().getLuaLspCommand()),
                new LspServerUi(
                        "dockerfile",
                        com.editora.lsp.LspServerRegistry.DEFAULT_DOCKERFILE_COMMAND,
                        "settings.lsp.enableDockerfile",
                        "settings.lsp.dockerfileCommand",
                        "settings.lsp.dockerfileStatus",
                        "lsp dockerfile docker language server found installed not found command path",
                        v -> config.getSettings().setDockerfileLspEnabled(v),
                        () -> config.getSettings().isDockerfileLspEnabled(),
                        v -> config.getSettings().setDockerfileLspCommand(v),
                        () -> config.getSettings().getDockerfileLspCommand()),
                new LspServerUi(
                        "sql",
                        com.editora.lsp.LspServerRegistry.DEFAULT_SQL_COMMAND,
                        "settings.lsp.enableSql",
                        "settings.lsp.sqlCommand",
                        "settings.lsp.sqlStatus",
                        "lsp sql language server found installed not found command path executable",
                        v -> config.getSettings().setSqlLspEnabled(v),
                        () -> config.getSettings().isSqlLspEnabled(),
                        v -> config.getSettings().setSqlLspCommand(v),
                        () -> config.getSettings().getSqlLspCommand()),
                new LspServerUi(
                        "terraform",
                        com.editora.lsp.LspServerRegistry.DEFAULT_TERRAFORM_COMMAND,
                        "settings.lsp.enableTerraform",
                        "settings.lsp.terraformCommand",
                        "settings.lsp.terraformStatus",
                        "lsp terraform hcl terraform-ls language server found installed not found command path",
                        v -> config.getSettings().setTerraformLspEnabled(v),
                        () -> config.getSettings().isTerraformLspEnabled(),
                        v -> config.getSettings().setTerraformLspCommand(v),
                        () -> config.getSettings().getTerraformLspCommand()),
                new LspServerUi(
                        "toml",
                        com.editora.lsp.LspServerRegistry.DEFAULT_TOML_COMMAND,
                        "settings.lsp.enableToml",
                        "settings.lsp.tomlCommand",
                        "settings.lsp.tomlStatus",
                        "lsp toml taplo language server found installed not found command path executable",
                        v -> config.getSettings().setTomlLspEnabled(v),
                        () -> config.getSettings().isTomlLspEnabled(),
                        v -> config.getSettings().setTomlLspCommand(v),
                        () -> config.getSettings().getTomlLspCommand()),
                new LspServerUi(
                        "csharp",
                        com.editora.lsp.LspServerRegistry.DEFAULT_CSHARP_COMMAND,
                        "settings.lsp.enableCsharp",
                        "settings.lsp.csharpCommand",
                        "settings.lsp.csharpStatus",
                        "lsp c# csharp csharp-ls dotnet language server found installed not found command path",
                        v -> config.getSettings().setCsharpLspEnabled(v),
                        () -> config.getSettings().isCsharpLspEnabled(),
                        v -> config.getSettings().setCsharpLspCommand(v),
                        () -> config.getSettings().getCsharpLspCommand()));
    }

    /** A "[label] [path field] [Browse…]" row for picking a CLI executable. */
    private HBox exePathRow(String label, TextField field) {
        Button browse = new Button(tr("settings.mermaid.browse"));
        browse.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(label);
            java.io.File f = fc.showOpenDialog(stage);
            if (f != null) {
                field.setText(f.getAbsolutePath());
            }
        });
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox box = new HBox(6, new Label(label), field, browse);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void refreshMermaidStatus() {
        if (mermaidStatusLabel == null || mermaidService == null) {
            return;
        }
        mermaidStatusLabel.getStyleClass().setAll("settings-git-status");
        mermaidStatusLabel.setText(tr("settings.mermaid.checking"));
        mermaidService.detect(a -> {
            String mmdcState = a.mmdc() ? tr("settings.mermaid.found") : tr("settings.mermaid.notFound");
            String maidState = a.maid() ? tr("settings.mermaid.found") : tr("settings.mermaid.notFound");
            // Color the label green/red like the LSP/Git status labels: green only when both tools are
            // found, red when either is missing.
            mermaidStatusLabel
                    .getStyleClass()
                    .setAll(
                            "settings-git-status",
                            a.mmdc() && a.maid() ? "settings-git-found" : "settings-git-missing");
            mermaidStatusLabel.setText(tr("settings.mermaid.status", mmdcState, maidState));
            updateInstallButton("mermaid", a.mmdc());
        });
    }

    /** Injected by MainController: probes {@code rg} off-thread, delivering found/not-found on the FX thread. */
    public void setRipgrepProbe(java.util.function.Consumer<java.util.function.Consumer<Boolean>> probe) {
        this.ripgrepProbe = probe;
    }

    private void refreshRipgrepStatus() {
        if (ripgrepStatusLabel == null || ripgrepProbe == null) {
            return;
        }
        ripgrepStatusLabel.getStyleClass().setAll("settings-git-status");
        ripgrepStatusLabel.setText(tr("settings.search.checking"));
        ripgrepProbe.accept(this::syncRipgrepStatus);
    }

    /** Update the Settings → Search status label green/red, like the Git/Mermaid status labels. */
    public void syncRipgrepStatus(boolean found) {
        if (ripgrepStatusLabel == null) {
            return;
        }
        ripgrepStatusLabel
                .getStyleClass()
                .setAll("settings-git-status", found ? "settings-git-found" : "settings-git-missing");
        ripgrepStatusLabel.setText(found ? tr("settings.search.found") : tr("settings.search.notFound"));
    }

    /** Re-checks each debug adapter's availability (java plugin jar / debugpy / js-debug+node) and colors
     *  its status label green/red, like the LSP/Mermaid status. java locate is cheap; python/js probe a
     *  subprocess off-thread and call back on the FX thread. */
    private void refreshDebugStatus() {
        if (debugStatusLabels.isEmpty()) {
            return;
        }
        for (DebugAdapterUi dbg : debugAdapterUis()) {
            Label status = debugStatusLabels.get(dbg.id());
            if (status == null) {
                continue;
            }
            status.getStyleClass().setAll("settings-git-status");
            status.setText(tr("settings.debug.checking"));
            dbg.detect().accept(found -> {
                status.getStyleClass()
                        .setAll("settings-git-status", found ? "settings-git-found" : "settings-git-missing");
                status.setText(
                        tr("settings.debug.status", tr(found ? "settings.debug.found" : "settings.debug.notFound")));
            });
        }
    }

    /** The per-language debug enable checkboxes are only meaningful while the master debug toggle is on. */
    private void updateDebugRowsEnabled() {
        boolean on = debugCheck != null && debugCheck.isSelected();
        for (CheckBox c : debugEnableChecks.values()) {
            c.setDisable(!on);
        }
        for (TextField f : debugCommandFields.values()) {
            f.setDisable(!on);
        }
    }

    /** The per-server enable checkboxes are only meaningful while the global LSP toggle is on. */
    private void updateLspRowsEnabled() {
        boolean on = lspCheck != null && lspCheck.isSelected();
        for (CheckBox c : lspEnableChecks.values()) {
            c.setDisable(!on);
        }
    }

    private void refreshLspStatus() {
        if (lspManager == null || lspStatusLabels.isEmpty()) {
            return;
        }
        // The manager caches its probe per command; configure it with the current commands first.
        Settings cs = config.getSettings();
        lspManager.configure(
                cs.isLspSupport(),
                java.util.Map.ofEntries(
                        java.util.Map.entry("java", cs.getJavaLspCommand()),
                        java.util.Map.entry("typescript", cs.getTypescriptLspCommand()),
                        java.util.Map.entry("python", cs.getPythonLspCommand()),
                        java.util.Map.entry("xml", cs.getXmlLspCommand()),
                        java.util.Map.entry("json", cs.getJsonLspCommand()),
                        java.util.Map.entry("bash", cs.getBashLspCommand()),
                        java.util.Map.entry("yaml", cs.getYamlLspCommand()),
                        java.util.Map.entry("go", cs.getGoLspCommand()),
                        java.util.Map.entry("rust", cs.getRustLspCommand()),
                        java.util.Map.entry("php", cs.getPhpLspCommand()),
                        java.util.Map.entry("ruby", cs.getRubyLspCommand()),
                        java.util.Map.entry("clangd", cs.getClangdLspCommand()),
                        java.util.Map.entry("html", cs.getHtmlLspCommand()),
                        java.util.Map.entry("css", cs.getCssLspCommand()),
                        java.util.Map.entry("kotlin", cs.getKotlinLspCommand()),
                        java.util.Map.entry("lua", cs.getLuaLspCommand()),
                        java.util.Map.entry("dockerfile", cs.getDockerfileLspCommand()),
                        java.util.Map.entry("sql", cs.getSqlLspCommand()),
                        java.util.Map.entry("terraform", cs.getTerraformLspCommand()),
                        java.util.Map.entry("toml", cs.getTomlLspCommand()),
                        java.util.Map.entry("csharp", cs.getCsharpLspCommand())));
        for (LspServerUi srv : lspServerUis()) {
            Label status = lspStatusLabels.get(srv.id());
            if (status == null) {
                continue;
            }
            String statusKey = srv.statusKey();
            status.getStyleClass().setAll("settings-git-status");
            status.setText(tr("settings.lsp.checking"));
            String langKey = installLangForServer(srv.id());
            lspManager.detect(srv.id(), found -> {
                status.getStyleClass()
                        .setAll("settings-git-status", found ? "settings-git-found" : "settings-git-missing");
                status.setText(tr(statusKey, found ? tr("settings.lsp.found") : tr("settings.lsp.notFound")));
                updateInstallButton(langKey != null ? langKey : srv.id(), found);
            });
        }
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
        gitStatusLabel.setText(tr("settings.git.checking"));
        gitService.version(version -> {
            boolean found = version != null && !version.isBlank();
            gitStatusLabel
                    .getStyleClass()
                    .setAll("settings-git-status", found ? "settings-git-found" : "settings-git-missing");
            gitStatusLabel.setText(found ? tr("settings.git.found", version) : tr("settings.git.notFound"));
            gitCheck.setDisable(!found);
        });
    }

    /** The tool-window placement page: one row per registered tool window (Show / Side / ▲▼ reorder). */
    private VBox toolWindowsPage() {
        VBox p = page(tr("settings.cat.toolWindows"));
        Label hint = note(tr("settings.toolWindows.hint"));
        p.getChildren().add(hint);
        // The tool-stripe toggle now lives on the Interface page; "show hidden files" on the Workspace page.

        List<Runnable> moveRefreshers = new ArrayList<>();
        Runnable refreshMoves = () -> moveRefreshers.forEach(Runnable::run);
        for (ToolWindow tw : toolWindows.getRegisteredToolWindows()) {
            CheckBox showCheck = new CheckBox(tr("settings.show"));
            showCheck.setSelected(toolWindows.isVisible(tw));

            ComboBox<ToolWindow.Side> sideCombo = new ComboBox<>();
            sideCombo.getItems().setAll(ToolWindow.Side.values());
            sideCombo.setConverter(new StringConverter<>() {
                @Override
                public String toString(ToolWindow.Side side) {
                    return side == null
                            ? ""
                            : side.name().charAt(0) + side.name().substring(1).toLowerCase();
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
            moveUp.setTooltip(new Tooltip(tr("settings.moveEarlier")));
            moveDown.setTooltip(new Tooltip(tr("settings.moveLater")));
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
            } else if ("notes".equals(tw.getId())) {
                notesShowCheck = showCheck;
                notesSideCombo = sideCombo;
                notesMoveUp = moveUp;
                notesMoveDown = moveDown;
                notesToolWindowRef = tw;
            } else if ("problems".equals(tw.getId())) {
                problemsShowCheck = showCheck;
                problemsSideCombo = sideCombo;
                problemsMoveUp = moveUp;
                problemsMoveDown = moveDown;
                problemsToolWindowRef = tw;
            } else if ("run".equals(tw.getId())) {
                runShowCheck = showCheck;
                runSideCombo = sideCombo;
                runMoveUp = moveUp;
                runMoveDown = moveDown;
                runToolWindowRef = tw;
            } else if ("debug".equals(tw.getId())) {
                debugShowCheck = showCheck;
                debugSideCombo = sideCombo;
                debugMoveUp = moveUp;
                debugMoveDown = moveDown;
                debugToolWindowRef = tw;
            }

            Label title = new Label(tw.getTitle());
            title.setMinWidth(130);
            title.setPrefWidth(130);
            HBox reorder = new HBox(2, moveUp, moveDown);
            HBox rowBox = new HBox(10, title, showCheck, sideCombo, reorder);
            rowBox.setAlignment(Pos.CENTER_LEFT);
            // For the context-gated windows, a muted note explaining why the row may be disabled.
            if ("project".equals(tw.getId())) {
                projectDisabledNote = note(tr("settings.toolWindows.projectDisabled"));
                projectDisabledNote.setWrapText(true);
                rowBox.getChildren().add(projectDisabledNote);
            } else if ("commit".equals(tw.getId())) {
                commitDisabledNote = note(tr("settings.toolWindows.commitDisabled"));
                commitDisabledNote.setWrapText(true);
                rowBox.getChildren().add(commitDisabledNote);
            } else if ("notes".equals(tw.getId())) {
                notesDisabledNote = note(tr("settings.toolWindows.notesDisabled"));
                notesDisabledNote.setWrapText(true);
                rowBox.getChildren().add(notesDisabledNote);
            } else if ("problems".equals(tw.getId())) {
                problemsDisabledNote = note(tr("settings.toolWindows.problemsDisabled"));
                problemsDisabledNote.setWrapText(true);
                rowBox.getChildren().add(problemsDisabledNote);
            } else if ("run".equals(tw.getId())) {
                runDisabledNote = note(tr("settings.toolWindows.runDisabled"));
                runDisabledNote.setWrapText(true);
                rowBox.getChildren().add(runDisabledNote);
            } else if ("debug".equals(tw.getId())) {
                debugDisabledNote = note(tr("settings.toolWindows.debugDisabled"));
                debugDisabledNote.setWrapText(true);
                rowBox.getChildren().add(debugDisabledNote);
            }
            row(p, Category.TOOL_WINDOWS, null, rowBox, "tool window " + tw.getTitle() + " placement side show");
        }
        refreshMoves.run();
        updateProjectRowEnabled();
        updateGitRowEnabled();
        updateNotesRowEnabled();
        updateLspToolRowsEnabled();
        return p;
    }

    private VBox advancedPage() {
        VBox p = page(tr("settings.cat.advanced"));
        Label fileSection = section(p, tr("settings.section.file"));
        Hyperlink link = new Hyperlink(displaySettingsPath(config.getSettingsFile()));
        link.setTooltip(new Tooltip(tr("settings.openFileTip")));
        link.setOnAction(e -> {
            if (onOpenFile != null) {
                onOpenFile.accept(config.getSettingsFile());
            }
        });
        HBox fileRow = new HBox(6, new Label(tr("settings.path")), link);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        row(p, Category.ADVANCED, fileSection, fileRow, "settings file path toml config location");

        Path sessionLog = DebugLog.sessionFile(config.getConfigDir());
        Hyperlink sessionLink = new Hyperlink(displaySettingsPath(sessionLog));
        sessionLink.setTooltip(new Tooltip(tr("settings.openFileTip")));
        sessionLink.setOnAction(e -> {
            if (onOpenFile != null && sessionLog != null) {
                onOpenFile.accept(sessionLog);
            }
        });
        HBox sessionRow = new HBox(6, new Label(tr("settings.sessionLog")), sessionLink);
        sessionRow.setAlignment(Pos.CENTER_LEFT);
        row(
                p,
                Category.ADVANCED,
                fileSection,
                sessionRow,
                "session log debug crash report editora-session.log diagnostics");

        Label resetSection = section(p, tr("settings.section.reset"));
        Button reset = new Button(tr("settings.resetDefaults"));
        reset.setOnAction(e -> resetAll());
        row(p, Category.ADVANCED, resetSection, reset, "reset defaults restore factory clear");

        Label ioSection = section(p, tr("settings.section.io"));
        Button exportConfig = new Button(tr("settings.exportConfig"));
        exportConfig.setOnAction(e -> {
            if (onExportConfig != null) {
                onExportConfig.run();
            }
        });
        Label exportHint = note(tr("settings.exportConfig.hint"));
        VBox exportBox = new VBox(4, exportConfig, exportHint);
        row(p, Category.ADVANCED, ioSection, exportBox, "import export backup settings config zip archive");

        Label debugSection = section(p, tr("settings.section.debug"));
        Button debugLog = new Button(tr("settings.debugLog"));
        debugLog.setOnAction(e -> {
            if (onShowDebugLog != null) {
                onShowDebugLog.run();
            }
        });
        Label debugHint = note(tr("settings.debugLog.hint"));
        VBox debugBox = new VBox(4, debugLog, debugHint);
        row(
                p,
                Category.ADVANCED,
                debugSection,
                debugBox,
                "debug log logs errors warnings exceptions diagnostics bug report console");
        return p;
    }

    // --- page helpers ---

    private VBox page(String title) {
        // A Text node (with VISUAL bounds) rather than a Label: a JavaFX Label clips its text to the
        // glyphs' logical (advance-based) bounds, which shaves the outer strokes of bold faces — the
        // bold heading on every Settings page looked like it was missing slivers of letters. A Text node
        // renders the full glyph ink and doesn't self-clip. Styled via .settings-page-title (Text honors
        // -fx-font-* and -fx-fill).
        javafx.scene.text.Text heading = new javafx.scene.text.Text(title);
        heading.setBoundsType(javafx.scene.text.TextBoundsType.VISUAL);
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
        // Floor the width to 130 so short labels line up into a tidy column, but let a longer label grow to
        // its full text (maxWidth = pref) instead of ellipsizing — e.g. "Max size / project (MB)" or any
        // longer translation. (Previously a fixed prefWidth(130) clamped + truncated the longer ones.)
        l.setMinWidth(130);
        l.setMaxWidth(Region.USE_PREF_SIZE);
        HBox h = new HBox(10, l, control);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-hint");
        return l;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    // --- search ----------------------------------------------------------------------------------

    /** Whether {@code keywords} matches the search {@code query} (case-insensitive substring). Pure. */
    static boolean matches(String query, String keywords) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return keywords != null
                && keywords.toLowerCase(Locale.ROOT)
                        .contains(query.toLowerCase(Locale.ROOT).strip());
    }

    /** The sidebar's row model: each group's header followed by its categories, in declaration order. */
    private static List<Object> sidebarItems() {
        List<Object> items = new ArrayList<>();
        Group current = null;
        for (Category c : Category.values()) {
            if (c.group != current) {
                items.add(c.group);
                current = c.group;
            }
            items.add(c);
        }
        return items;
    }

    private void filter(String query) {
        searchHiddenCats.clear();
        searchHiddenGroups.clear();
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
            if (!matched.contains(c)) {
                searchHiddenCats.add(c);
            }
        }
        for (Group g : Group.values()) {
            boolean any = matched.stream().anyMatch(c -> c.group == g);
            if (!any) {
                searchHiddenGroups.add(g);
            }
        }
        sidebar.refresh();
        Object selObj = sidebar.getSelectionModel().getSelectedItem();
        Category sel = (selObj instanceof Category c) ? c : null;
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

    /** Renders a sidebar row: a {@link Group} as a non-selectable header, a {@link Category} as an item. */
    private final class CategoryCell extends ListCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("settings-group-header", "settings-sidebar-item");
            setGraphic(null);
            if (empty || item == null) {
                setText(null);
                setDisable(false);
                setMouseTransparent(false);
                return;
            }
            if (item instanceof Group g) {
                setText(g.display);
                getStyleClass().add("settings-group-header");
                setMouseTransparent(true); // headers can't be selected
                setFocusTraversable(false);
                setDisable(searchHiddenGroups.contains(g));
            } else {
                Category c = (Category) item;
                setText(c.display);
                getStyleClass().add("settings-sidebar-item");
                setMouseTransparent(false);
                setDisable(searchHiddenCats.contains(c));
                if (c.beta) {
                    Label pill = new Label(tr("settings.beta"));
                    pill.getStyleClass().add("settings-beta-pill");
                    setGraphic(pill); // small "Beta" pill beside the feature name
                    setContentDisplay(ContentDisplay.RIGHT);
                    setGraphicTextGap(6);
                }
            }
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
        preview.setStyle(
                "-fx-font-family: \"" + fontFamily.getValue() + "\"; -fx-font-size: " + fontSize.getValue() + "px;");
    }

    // --- reset -----------------------------------------------------------------------------------

    private void resetAll() {
        Alert confirm =
                new Alert(Alert.AlertType.CONFIRMATION, tr("settings.reset.confirm"), ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(stage);
        confirm.setTitle(tr("settings.reset.title"));
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
        s.setShowNoteIndicators(d.isShowNoteIndicators());
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
        s.setNotesSupport(d.isNotesSupport());
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
            refreshDictionaryList(); // pick up words added elsewhere (e.g. "Add to Dictionary") since last open
            Settings settings = config.getSettings();
            if (!fontFamily.getItems().contains(settings.getFontFamily())) {
                fontFamily.getItems().add(0, settings.getFontFamily());
            }
            languageCombo.setValue(settings.getUiLanguage());
            keymapCombo.setValue(settings.getKeymap());
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
            indentStyleCombo.setValue(settings.getIndentStyle());
            fillColumnSpinner.getValueFactory().setValue(settings.getFillColumn());
            largeFileThresholdSpinner.getValueFactory().setValue(settings.getLargeFileThreshold());
            columnRulerCheck.setSelected(settings.isShowColumnRuler());
            lineHighlightCheck.setSelected(settings.isHighlightCurrentLine());
            lineNumbersCheck.setSelected(settings.isShowLineNumbers());
            minimapCheck.setSelected(settings.isShowMinimap());
            wordWrapCheck.setSelected(settings.isWordWrap());
            adminSaveCheck.setSelected(settings.isAdminSave());
            whitespaceCheck.setSelected(settings.isShowWhitespace());
            notesCheck.setSelected(settings.isNotesSupport());
            noteIndicatorsCheck.setSelected(settings.isShowNoteIndicators());
            noteIndicatorsCheck.setDisable(!settings.isNotesSupport());
            autocompleteCheck.setSelected(settings.isAutocomplete());
            autocompleteProseCheck.setSelected(settings.isAutocompleteProse());
            autocompleteSnippetsCheck.setSelected(settings.isAutocompleteSnippets());
            autocompleteMermaidCheck.setSelected(settings.isAutocompleteMermaid());
            autocompleteProseCheck.setDisable(!settings.isAutocomplete());
            autocompleteSnippetsCheck.setDisable(!settings.isAutocomplete());
            autocompleteMermaidCheck.setDisable(!settings.isAutocomplete());
            completionDocCheck.setSelected(settings.isCompletionDoc());
            semanticHighlightCheck.setSelected(settings.isSemanticHighlight());
            pdfLineNumbersCheck.setSelected(settings.isPdfLineNumbers());
            pdfHighlightCheck.setSelected(settings.isPdfSyntaxHighlighting());
            pdfPageSizeCombo.setValue(settings.getPdfPageSize());
            spellCheckBox.setSelected(settings.isSpellCheck());
            dictEnableCheck.setSelected(settings.isPersonalDictionary());
            techDictEnableCheck.setSelected(settings.isTechnicalDictionary());
            spellLanguageCombo.setValue(settings.getSpellLanguage());
            spellLanguageCombo.setDisable(!settings.isSpellCheck());
            toolbarCheck.setSelected(settings.isShowToolbar());
            statusBarCheck.setSelected(settings.isShowStatusBar());
            tabBarCheck.setSelected(settings.isShowTabBar());
            breadcrumbCheck.setSelected(settings.isShowBreadcrumb());
            simpleModeCheck.setSelected(settings.isSimpleMode());
            templateAuthorField.setText(settings.getAuthorNameRaw());
            toolStripeCheck.setSelected(settings.isShowToolStripe());
            projectHiddenCheck.setSelected(settings.isProjectShowHidden());
            markdownFormatBarCheck.setSelected(settings.isMarkdownFormatBar());
            lspInstallPromptsCheck.setSelected(settings.isLspInstallPrompts());
            markdownLintCheck.setSelected(settings.isMarkdownLint());
            mathSupportCheck.setSelected(settings.isMathSupport());
            editorConfigCheck.setSelected(settings.isEditorConfigSupport());
            logViewerCheck.setSelected(settings.isLogViewer());
            csvGridCheck.setSelected(settings.isCsvPreview());
            todoHighlightCheck.setSelected(settings.isTodoHighlight());
            rebuildTodoRows();
            rebuildMarkdownLintRules();
            multiCaretCheck.setSelected(settings.isMultiCaret());
            projectsCheck.setSelected(settings.isProjectSupport());
            updateProjectRowEnabled();
            gitCheck.setSelected(settings.isGitSupport());
            blameCheck.setSelected(settings.isGitBlameInline());
            blameCheck.setDisable(!settings.isGitSupport());
            localHistoryCheck.setSelected(settings.isLocalHistory());
            historyMaxPerFileSpinner.getValueFactory().setValue(settings.getHistoryMaxPerFile());
            historyMaxAgeSpinner.getValueFactory().setValue(settings.getHistoryMaxAgeDays());
            historyMaxTotalSpinner.getValueFactory().setValue(settings.getHistoryMaxTotalMb());
            updateHistoryRowsEnabled();
            updateGitRowEnabled();
            updateNotesRowEnabled();
            updateLspToolRowsEnabled();
            mermaidCheck.setSelected(settings.isMermaidSupport());
            mmdcPathField.setText(settings.getMmdcPath());
            maidPathField.setText(settings.getMaidPath());
            refreshMermaidStatus();
            ripgrepCheck.setSelected(settings.isRipgrepSearch());
            searchGitignoreCheck.setSelected(settings.isSearchRespectGitignore());
            ripgrepCommandField.setText(settings.getRipgrepCommand());
            refreshRipgrepStatus();
            httpCheck.setSelected(settings.isHttpClientSupport());
            htmlPreviewCheck.setSelected(settings.isHtmlPreviewSupport());
            mcpCheck.setSelected(settings.isMcpSupport());
            pluginCheck.setSelected(settings.isPluginSupport());
            if (pluginRequireSigCheck != null) {
                pluginRequireSigCheck.setSelected(settings.isPluginRequireSignature());
            }
            if (pluginRegistryField != null) {
                pluginRegistryField.setText(settings.getPluginRegistryUrl());
                updateRegistryWarn();
            }
            refreshPluginList(); // re-read enabled state + reflect the master gate
            debugCheck.setSelected(settings.isDebugSupport());
            for (DebugAdapterUi dbg : debugAdapterUis()) {
                CheckBox enable = debugEnableChecks.get(dbg.id());
                if (enable != null && dbg.getEnabled() != null) {
                    enable.setSelected(dbg.getEnabled().getAsBoolean());
                }
                TextField field = debugCommandFields.get(dbg.id());
                if (field != null) {
                    field.setText(dbg.getCommand().get());
                }
            }
            updateDebugRowsEnabled();
            refreshDebugStatus();
            lspCheck.setSelected(settings.isLspSupport());
            for (LspServerUi srv : lspServerUis()) {
                CheckBox enable = lspEnableChecks.get(srv.id());
                if (enable != null) {
                    enable.setSelected(srv.getEnabled().getAsBoolean());
                }
                TextField field = lspCommandFields.get(srv.id());
                if (field != null) {
                    field.setText(srv.getCommand().get());
                }
            }
            updateLspRowsEnabled();
            refreshLspStatus();
            zenCheck.setSelected(config.getWorkspaceState().isZenMode());
            String mode = MainController.autoSaveModeOf(settings.getAutoSave());
            autoSaveCombo.setValue(mode);
            autoSaveDelaySpinner.getValueFactory().setValue(Math.max(1, (int)
                    Math.round(settings.getAutoSaveDelayMillis() / 1000.0)));
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
        if (projectDisabledNote != null) {
            projectDisabledNote.setVisible(!on);
            projectDisabledNote.setManaged(!on);
        }
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
        if (commitDisabledNote != null) {
            commitDisabledNote.setVisible(!on);
            commitDisabledNote.setManaged(!on);
        }
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

    /**
     * Disables the Personal Notes tool-window-placement row when the feature is off (the window can't be
     * shown until it's enabled). Like the Commit row, the Show checkbox value is left untouched — notes
     * availability is transient, not the user's persisted visibility preference.
     */
    private void updateNotesRowEnabled() {
        if (notesShowCheck == null) {
            return;
        }
        boolean on = config.getSettings().isNotesSupport();
        notesShowCheck.setDisable(!on);
        if (notesDisabledNote != null) {
            notesDisabledNote.setVisible(!on);
            notesDisabledNote.setManaged(!on);
        }
        if (!on) {
            notesSideCombo.setDisable(true);
            notesMoveUp.setDisable(true);
            notesMoveDown.setDisable(true);
        } else {
            boolean shown = notesShowCheck.isSelected();
            notesSideCombo.setDisable(!shown);
            notesMoveUp.setDisable(!shown || !toolWindows.canMove(notesToolWindowRef, -1));
            notesMoveDown.setDisable(!shown || !toolWindows.canMove(notesToolWindowRef, 1));
        }
    }

    /**
     * Disables the feature-gated tool-window rows when their feature is off: Problems and Run need the LSP
     * feature on; Debug needs Debugging (DAP) on (independent of LSP — Python/JS debugging doesn't use LSP,
     * and {@code MainController.applyDebugGating} makes the Debug window available on {@code debugSupport}
     * alone). Like the Commit/Notes rows, the Show checkbox value is left untouched — availability is
     * transient, not the user's persisted visibility preference.
     */
    private void updateLspToolRowsEnabled() {
        boolean lsp = config.getSettings().isLspSupport();
        updateTransientRow(
                lsp,
                problemsShowCheck,
                problemsSideCombo,
                problemsMoveUp,
                problemsMoveDown,
                problemsDisabledNote,
                problemsToolWindowRef);
        updateTransientRow(lsp, runShowCheck, runSideCombo, runMoveUp, runMoveDown, runDisabledNote, runToolWindowRef);
        updateTransientRow(
                config.getSettings().isDebugSupport(),
                debugShowCheck,
                debugSideCombo,
                debugMoveUp,
                debugMoveDown,
                debugDisabledNote,
                debugToolWindowRef);
    }

    /** Shared logic for a context-gated tool-window row: gray out the controls + show the "disabled" note
     *  when {@code on} is false, else restore the normal show/side/move enabling. */
    private void updateTransientRow(
            boolean on,
            CheckBox show,
            ComboBox<ToolWindow.Side> side,
            Button up,
            Button down,
            Label disabledNote,
            ToolWindow ref) {
        if (show == null) {
            return;
        }
        show.setDisable(!on);
        if (disabledNote != null) {
            disabledNote.setVisible(!on);
            disabledNote.setManaged(!on);
        }
        if (!on) {
            side.setDisable(true);
            up.setDisable(true);
            down.setDisable(true);
        } else {
            boolean shown = show.isSelected();
            side.setDisable(!shown);
            up.setDisable(!shown || !toolWindows.canMove(ref, -1));
            down.setDisable(!shown || !toolWindows.canMove(ref, 1));
        }
    }

    /**
     * Re-reads every control from the live {@link Settings} if the window has been built and is showing.
     * Used by the settings-changing palette commands so an open Settings window tracks a palette change
     * (when it's closed, the next {@link #show} reloads anyway).
     */
    public void syncAll() {
        if (built && stage.isShowing()) {
            load();
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

    /** Injects the live-reload hook run when the keymap picker changes (→ MainController). */
    public void setOnKeymapChanged(Runnable handler) {
        this.onKeymapChanged = handler;
    }

    /** One command row in the keybinding editor: id, localized title, and current effective chord (or null). */
    public record Shortcut(String id, String title, String chord) {}

    /** Backs the keybinding editor; all logic + persistence lives in {@code MainController}. */
    public interface ShortcutActions {
        java.util.List<Shortcut> rows();

        /** The command currently bound to {@code chordSeq}, or null (for conflict warnings). */
        String commandUsing(String chordSeq);

        void rebind(String commandId, String chordSeq);

        void reset(String commandId);

        void resetAll();
    }

    /** Injects the keybinding-editor backend (→ MainController); enables the shortcuts list. */
    public void setShortcutActions(ShortcutActions actions) {
        this.shortcutActions = actions;
        if (built) {
            refreshShortcuts();
        }
    }

    /** Re-selects the keymap combo to match the current setting (after the {@code keymap.select} command). */
    public void syncKeymapCombo() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            keymapCombo.setValue(config.getSettings().getKeymap());
        } finally {
            loading = prev;
        }
    }

    /** Re-reads the inline-blame checkbox from settings (used after the {@code git.toggleBlame} command). */
    public void syncGitBlameCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            blameCheck.setSelected(config.getSettings().isGitBlameInline());
            blameCheck.setDisable(!config.getSettings().isGitSupport());
        } finally {
            loading = prev;
        }
    }

    /** Re-reads the "enable plugins" checkbox from settings (used after the palette toggle command). */
    public void syncPluginsCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            pluginCheck.setSelected(config.getSettings().isPluginSupport());
            refreshPluginList();
        } finally {
            loading = prev;
        }
    }

    /** Re-reads the "show tool stripe" checkbox from settings (used after the palette toggle command). */
    public void syncToolStripeCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            toolStripeCheck.setSelected(config.getSettings().isShowToolStripe());
        } finally {
            loading = prev;
        }
    }

    /** Re-sync the Simple-UI checkbox to the saved setting (after the palette/toolbar toggle or --simple). */
    public void syncSimpleModeCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            simpleModeCheck.setSelected(config.getSettings().isSimpleMode());
        } finally {
            loading = prev;
        }
    }

    /** Re-reads the Markdown "format bar" checkbox from settings (used after the palette toggle command). */
    public void syncMarkdownFormatBarCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            markdownFormatBarCheck.setSelected(config.getSettings().isMarkdownFormatBar());
        } finally {
            loading = prev;
        }
    }

    /** Re-reads the multiple-cursors checkbox from settings (used after the palette toggle command). */
    public void syncMultiCaretCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            multiCaretCheck.setSelected(config.getSettings().isMultiCaret());
        } finally {
            loading = prev;
        }
    }

    /** Re-reads the "show toolbar" checkbox from settings (used after the palette/floating toggle). */
    public void syncToolbarCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            toolbarCheck.setSelected(config.getSettings().isShowToolbar());
        } finally {
            loading = prev;
        }
    }

    /** Re-reads the "Enable LSP" checkbox from settings (used after the {@code view.toggleLsp} command). */
    public void syncLspCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            lspCheck.setSelected(config.getSettings().isLspSupport());
        } finally {
            loading = prev;
        }
        updateLspToolRowsEnabled(); // the Problems/Run/Debug rows are gated by the LSP feature
        refreshLspStatus();
    }

    /** Re-syncs the HTML Live Preview checkbox to the current setting (after a palette toggle). */
    public void syncHtmlPreviewCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            htmlPreviewCheck.setSelected(config.getSettings().isHtmlPreviewSupport());
        } finally {
            loading = prev;
        }
    }

    public void syncMcpCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            mcpCheck.setSelected(config.getSettings().isMcpSupport());
        } finally {
            loading = prev;
        }
    }

    public void syncLogViewerCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            logViewerCheck.setSelected(config.getSettings().isLogViewer());
        } finally {
            loading = prev;
        }
    }

    /** Re-syncs the autocomplete checkboxes to the current settings (used after a palette toggle). */
    public void syncAutocompleteChecks() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            Settings s = config.getSettings();
            autocompleteCheck.setSelected(s.isAutocomplete());
            autocompleteProseCheck.setSelected(s.isAutocompleteProse());
            autocompleteSnippetsCheck.setSelected(s.isAutocompleteSnippets());
            autocompleteMermaidCheck.setSelected(s.isAutocompleteMermaid());
            autocompleteProseCheck.setDisable(!s.isAutocomplete());
            autocompleteSnippetsCheck.setDisable(!s.isAutocomplete());
            autocompleteMermaidCheck.setDisable(!s.isAutocomplete());
            completionDocCheck.setSelected(s.isCompletionDoc());
            semanticHighlightCheck.setSelected(s.isSemanticHighlight());
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
            themeCombo.getItems().setAll(Themes.names()); // pick up user themes reloaded from the config folder
            editorThemeCombo.getItems().setAll(EditorThemes.names());
            themeCombo.setValue(config.getSettings().getTheme());
            editorThemeCombo.setValue(
                    EditorThemes.normalize(config.getSettings().getEditorTheme()));
        } finally {
            loading = prev;
        }
        applyPreviewTheme(EditorThemes.normalize(config.getSettings().getEditorTheme()));
    }

    void syncViewChecks() {
        boolean prev = loading;
        loading = true;
        try {
            Settings s = config.getSettings();
            columnRulerCheck.setSelected(s.isShowColumnRuler());
            lineHighlightCheck.setSelected(s.isHighlightCurrentLine());
            lineNumbersCheck.setSelected(s.isShowLineNumbers());
            minimapCheck.setSelected(s.isShowMinimap());
            wordWrapCheck.setSelected(s.isWordWrap());
            adminSaveCheck.setSelected(s.isAdminSave());
            whitespaceCheck.setSelected(s.isShowWhitespace());
            notesCheck.setSelected(s.isNotesSupport());
            noteIndicatorsCheck.setSelected(s.isShowNoteIndicators());
            noteIndicatorsCheck.setDisable(!s.isNotesSupport());
            spellCheckBox.setSelected(s.isSpellCheck());
            spellLanguageCombo.setValue(s.getSpellLanguage());
            spellLanguageCombo.setDisable(!s.isSpellCheck());
            toolbarCheck.setSelected(s.isShowToolbar());
            statusBarCheck.setSelected(s.isShowStatusBar());
            tabBarCheck.setSelected(s.isShowTabBar());
            projectHiddenCheck.setSelected(s.isProjectShowHidden());
            breadcrumbCheck.setSelected(s.isShowBreadcrumb());
            simpleModeCheck.setSelected(s.isSimpleMode());
            toolStripeCheck.setSelected(s.isShowToolStripe());
            markdownFormatBarCheck.setSelected(s.isMarkdownFormatBar());
            lspInstallPromptsCheck.setSelected(s.isLspInstallPrompts());
            markdownLintCheck.setSelected(s.isMarkdownLint());
            mathSupportCheck.setSelected(s.isMathSupport());
            editorConfigCheck.setSelected(s.isEditorConfigSupport());
            logViewerCheck.setSelected(s.isLogViewer());
            csvGridCheck.setSelected(s.isCsvPreview());
            todoHighlightCheck.setSelected(s.isTodoHighlight());
            rebuildTodoRows();
            rebuildMarkdownLintRules();
            indentStyleCombo.setValue(s.getIndentStyle());
            multiCaretCheck.setSelected(s.isMultiCaret());
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
            if (Math.abs(narrow.getLayoutBounds().getWidth()
                            - wide.getLayoutBounds().getWidth())
                    < 0.5) {
                families.add(family);
            }
        }
        return families;
    }

    /** The editor-font picker choices (bundled monospace families first, then system monospace). */
    public static List<String> fontFamilyChoices() {
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
            int value = Math.max(
                    8,
                    Math.min(48, Integer.parseInt(fontSize.getEditor().getText().trim())));
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
    public static void showAbout(
            Window owner, Path settingsFile, Consumer<Path> openFile, Consumer<String> openUrl, String commit) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle(tr("dialog.about.title", com.editora.AppInfo.NAME));
        alert.setHeaderText(com.editora.AppInfo.NAME + " " + com.editora.AppInfo.VERSION);
        var iconStream = SettingsWindow.class.getResourceAsStream("/com/editora/icons/icon-128.png");
        if (iconStream != null) {
            ImageView logo = new ImageView(new Image(iconStream));
            logo.setFitWidth(72);
            logo.setFitHeight(72);
            alert.setGraphic(logo);
        }

        // Build commit shown only when provided (dev builds); empty/null in production.
        String commitLine = commit == null || commit.isBlank() ? "" : tr("about.commit", commit) + "\n";
        Label info = new Label(tr("about.tagline") + "\n\n"
                + "Java " + System.getProperty("java.version", "?") + "\n"
                + "JavaFX " + System.getProperty("javafx.runtime.version", "?") + "\n"
                + tr("about.built", com.editora.AppInfo.buildTime()) + "\n"
                + commitLine + "\n"
                + com.editora.AppInfo.COPYRIGHT + "\n"
                + com.editora.AppInfo.LICENSE);

        Hyperlink homeLink = new Hyperlink(com.editora.AppInfo.HOMEPAGE.replaceFirst("^https?://", ""));
        homeLink.setPadding(Insets.EMPTY);
        homeLink.setOnAction(e -> {
            if (openUrl != null) {
                openUrl.accept(com.editora.AppInfo.HOMEPAGE);
            }
        });
        HBox homeRow = new HBox(4, new Label(tr("about.homepage")), homeLink);
        homeRow.setAlignment(Pos.CENTER_LEFT);

        Hyperlink settingsLink = new Hyperlink(displaySettingsPath(settingsFile));
        settingsLink.setPadding(Insets.EMPTY);
        settingsLink.setTooltip(new Tooltip(tr("settings.openFileTip")));
        settingsLink.setOnAction(e -> {
            alert.close();
            if (openFile != null) {
                openFile.accept(settingsFile);
            }
        });
        HBox settingsRow = new HBox(4, new Label(tr("settings.aboutSettingsLabel")), settingsLink);
        settingsRow.setAlignment(Pos.CENTER_LEFT);

        alert.getDialogPane().setContent(new VBox(10, info, homeRow, settingsRow));
        alert.showAndWait();
    }

    private static String spellLanguageName(String id) {
        return switch (id) {
            case "en_US" -> tr("spell.lang.en_US");
            case "en_GB" -> tr("spell.lang.en_GB");
            case "es" -> tr("spell.lang.es");
            case "es_MX" -> tr("spell.lang.es_MX");
            case "fr" -> tr("spell.lang.fr");
            default -> id;
        };
    }

    /** Friendly label for a global indent-style id ({@code detect}/{@code space}/{@code tab}); shared with the palette picker. */
    public static String indentStyleName(String id) {
        return switch (id == null ? "detect" : id) {
            case "space" -> tr("settings.indentStyle.space");
            case "tab" -> tr("settings.indentStyle.tab");
            default -> tr("settings.indentStyle.detect");
        };
    }

    /** The given settings-file path with the home dir shown as {@code ~} (derived, never hardcoded). */
    static String displaySettingsPath(Path settingsFile) {
        String path = settingsFile.toString();
        String home = System.getProperty("user.home", "");
        return !home.isEmpty() && path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }
}
