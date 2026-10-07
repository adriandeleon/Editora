package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.beans.binding.Bindings;
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

import com.editora.build.BuildTool;
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
 * {@link Settings} field and calls {@link #apply()} (persist to {@code settings.json} + notify the
 * controller), so there is no OK/Cancel; only Reset + Close.
 */
public class SettingsWindow {

    /**
     * Preferred size — big enough that the master-detail lists (templates, snippets, external tools, the
     * toolbar layout's two columns) show their default contents without a scrollbar.
     *
     * <p>Clamped against the screen by {@link #preferredSize()}: a fixed size larger than the display
     * would put the Close button off-screen, and this is a window the user cannot resize their way out of
     * if it opens taller than their laptop.
     */
    private static final double WIDTH = 1180;

    private static final double HEIGHT = 940;

    /** Fraction of the screen's usable area the window may occupy before the preferred size is clamped. */
    private static final double MAX_SCREEN_FRACTION = 0.92;

    /** Wide enough to read a path or a command in; SettingRowPane puts the field under its title when the row is narrow. */
    private static final double PATH_FIELD_WIDTH = 320;

    /**
     * The narrowest the window may be made. The pages that put a list beside a form (Snippets, Templates,
     * Macros, Toolbar, Remote) need about this much; below it they scroll sideways rather than collapse.
     */
    private static final double MIN_WIDTH = 900;

    private static final double MIN_HEIGHT = 480;

    /** Sidebar group headers; every {@link Category} belongs to exactly one, shown in declaration order. */
    private enum Group {
        GENERAL(tr("settings.group.general")),
        EDITOR(tr("settings.group.editor")),
        AI(tr("settings.group.ai")),
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
        TOOLBAR(tr("settings.cat.toolbar"), Group.GENERAL),
        WORKSPACE(tr("settings.cat.workspace"), Group.GENERAL),
        TOOL_WINDOWS(tr("settings.cat.toolWindows"), Group.GENERAL),
        // Editor
        EDITOR(tr("settings.cat.editor"), Group.EDITOR),
        COMPLETION(tr("settings.cat.completion"), Group.EDITOR),
        SNIPPETS(tr("settings.cat.snippets"), Group.EDITOR),
        TEMPLATES(tr("settings.cat.templates"), Group.EDITOR),
        TODO(tr("settings.cat.todo"), Group.EDITOR),
        SPELL_CHECK(tr("settings.cat.spellCheck"), Group.EDITOR),
        SEARCH(tr("settings.cat.search"), Group.EDITOR),
        ABBREVIATIONS(tr("settings.cat.abbreviations"), Group.EDITOR),
        // AI (a master enable switch, then the two AI features it gates)
        AI_GENERAL(tr("settings.cat.aiGeneral"), Group.AI, true),
        AGENT(tr("settings.cat.agent"), Group.AI, true),
        AI(tr("settings.cat.ai"), Group.AI, true),
        // Languages & Tools
        LSP(tr("settings.cat.lsp"), Group.LANGUAGES_TOOLS, true),
        DEBUG(tr("settings.cat.debug"), Group.LANGUAGES_TOOLS, true),
        MARKDOWN(tr("settings.cat.markdown"), Group.LANGUAGES_TOOLS),
        MERMAID(tr("settings.cat.mermaid"), Group.LANGUAGES_TOOLS),
        DIAGRAMS(tr("settings.cat.diagrams"), Group.LANGUAGES_TOOLS),
        TYPST(tr("settings.cat.typst"), Group.LANGUAGES_TOOLS),
        BUILD_TOOLS(tr("settings.cat.buildTools"), Group.LANGUAGES_TOOLS),
        WEB(tr("settings.cat.web"), Group.LANGUAGES_TOOLS),
        EXTERNAL_TOOLS(tr("settings.cat.externalTools"), Group.LANGUAGES_TOOLS),
        // Version control
        GIT(tr("settings.cat.git"), Group.VERSION_CONTROL),
        GITHUB(tr("settings.cat.github"), Group.VERSION_CONTROL),
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
    private record SettingRow(Category category, Node node, String keywords, Label section, VBox card) {}

    /** Lazily built search text per row (keywords + shown text); identity-keyed, rows are never removed. */
    private final java.util.Map<SettingRow, String> searchIndex = new java.util.IdentityHashMap<>();

    private Label searchEmpty; // "no settings match …", shown in place of the page

    private final ConfigManager config;
    private final Consumer<Settings> onApply;
    private final Consumer<Boolean> onToggleZen;
    private final Consumer<Boolean> onToggleExpert;
    private final Consumer<Path> onOpenFile;
    private final Runnable onExportConfig;
    private final Runnable onShowDebugLog;
    /** Spell Check page: open the bundled technical dictionary (read-only) / the personal dictionary file. */
    private Runnable onOpenTechnicalDictionary;

    private Runnable onOpenPersonalDictionary;
    /** Security-notice confirm shown before the MCP checkbox enables the server; null = no gate. */
    private java.util.function.BooleanSupplier mcpConfirm;
    /** The Workspace page's trusted-folder list + its backing store accessor (null until wired). */
    private final ListView<String> trustedFoldersList = new ListView<>();

    private TrustActions trustActions;

    private final ToolWindowManager toolWindows;
    private final com.editora.git.GitService gitService;
    private final com.editora.github.GitHubService githubService;
    private final com.editora.mermaid.MermaidService mermaidService;
    private final com.editora.diagram.DiagramService diagramService;
    private final com.editora.typst.TypstService typstService;
    private final java.util.List<BuildCoordinator> buildCoordinators;
    private final com.editora.lsp.LspManager lspManager;
    private final com.editora.dap.DapManager dapManager;
    private final Stage stage = new Stage();

    // --- controls (same set as before, regrouped into pages) ---
    private ComboBox<String> languageCombo;
    private ComboBox<String> keymapCombo;
    private ShortcutActions shortcutActions; // keybinding-editor backend (→ MainController)
    /** Command id -> its row's chord chip, filled in once shortcutActions arrives. */
    private final Map<String, Label> chordChips = new java.util.LinkedHashMap<>();

    private TextField shortcutFilter; // filters the shortcut list
    private VBox shortcutListBox; // rebuilt from shortcutActions.rows() on each change/filter
    private String recordingCommandId; // command id whose row is currently capturing a chord, or null
    private String selectedShortcutId; // command id of the selected row (shows its Record/Reset), or null
    private ScrollPane shortcutScroll;
    private final Map<String, HBox> shortcutRowsById = new HashMap<>(); // the rows currently in the list
    private HBox shortcutTabStop; // the one row Tab lands on; the arrow keys move between rows
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
    private CheckBox inlayHintsCheck;
    private CheckBox codeLensCheck;
    private ComboBox<String> inlayHintModeCombo;
    private CheckBox onTypeFormattingCheck;
    private CheckBox pasteImportsCheck;
    private CheckBox smartSemicolonCheck;
    private CheckBox spellCheckBox;
    private ComboBox<String> spellLanguageCombo;
    /** The Personal Dictionary list on the Spell Check page; refreshed from {@code dictionary.txt} on show. */
    private ListView<String> dictionaryList;
    /** "Enable personal dictionary" checkbox (Settings.personalDictionary). */
    private CheckBox dictEnableCheck;
    /** "Enable technical dictionary" checkbox (Settings.technicalDictionary). */
    private CheckBox techDictEnableCheck;

    private CheckBox menuBarCheck;
    private CheckBox toolbarCheck;
    private CheckBox statusBarCheck;
    private CheckBox tabBarCheck;
    private CheckBox breadcrumbCheck;
    private CheckBox simpleModeCheck;
    private CheckBox toolStripeCheck;

    private CheckBox extendedWindowCheck;
    private CheckBox paletteSearchEverywhereCheck;
    private CheckBox projectHiddenCheck;
    private CheckBox markdownFormatBarCheck;
    private CheckBox lspInstallPromptsCheck;
    private CheckBox markdownLintCheck;
    private CheckBox mathSupportCheck;
    private CheckBox editorConfigCheck;
    private CheckBox logViewerCheck;
    private CheckBox testRunnerCheck;
    private CheckBox csvGridCheck;
    private CheckBox structuredPreviewCheck;
    private CheckBox svgPreviewCheck;
    private CheckBox crontabPreviewCheck;
    private CheckBox fstabPreviewCheck;
    private CheckBox systemdPreviewCheck;
    private CheckBox sshConfigPreviewCheck;
    private CheckBox dockerfilePreviewCheck;
    private CheckBox githubActionsPreviewCheck;
    private CheckBox pomPreviewCheck;
    private CheckBox stickyScrollCheck;
    private CheckBox symbolIndexCheck;
    private CheckBox csvRainbowCheck;
    private CheckBox bracketColorsCheck;
    private CheckBox autoRenameTagCheck;
    private CheckBox autoFillCheck;
    private CheckBox autoCloseTagsCheck;
    private CheckBox todoHighlightCheck;
    private javafx.scene.layout.VBox todoPatternsBox;
    private javafx.scene.control.ColorPicker todoTagColorPicker;
    private javafx.scene.control.ColorPicker todoCriticalColorPicker;
    private javafx.scene.control.ColorPicker todoHighColorPicker;
    private javafx.scene.control.ColorPicker todoMediumColorPicker;
    private javafx.scene.control.ColorPicker todoLowColorPicker;
    private VBox markdownLintRulesBox;
    /** Working copy of the External Tools list, edited live by the master-detail page. */
    /** The External Tools ListView, so {@link #reloadExternalTools} can restore the selection. */
    private ListView<com.editora.externaltool.ExternalTool> externalToolList;

    private final javafx.collections.ObservableList<com.editora.externaltool.ExternalTool> externalToolItems =
            javafx.collections.FXCollections.observableArrayList();

    // Toolbar page state: the current-toolbar token list and the not-yet-added catalog items.
    private final javafx.collections.ObservableList<String> toolbarCurrentItems =
            javafx.collections.FXCollections.observableArrayList();
    private final javafx.collections.ObservableList<String> toolbarAvailableItems =
            javafx.collections.FXCollections.observableArrayList();
    private ToolbarActions toolbarActions; // → MainController/ToolbarCoordinator
    private Runnable refreshToolbarLists; // re-reads the effective layout when the page is shown

    private boolean loadingExternalTool = false;
    private ListView<com.editora.config.Abbreviation> abbrevList;
    private final javafx.collections.ObservableList<com.editora.config.Abbreviation> abbrevItems =
            javafx.collections.FXCollections.observableArrayList();
    private boolean loadingAbbrev = false;
    private CheckBox abbrevModeCheck;

    /** Working copy of the saved SFTP connections, edited live by the Remote master-detail page. */
    private final javafx.collections.ObservableList<com.editora.vfs.RemoteConnection> remoteItems =
            javafx.collections.FXCollections.observableArrayList();

    private boolean loadingRemote = false;
    /** The uncommitted text in the Remote / Abbreviations form, asked for before the list is re-read. */
    private java.util.function.Supplier<PendingFormEdit> remoteFormEdit = PendingFormEdit::none;

    private java.util.function.Supplier<PendingFormEdit> abbrevFormEdit = PendingFormEdit::none;
    /** The Remote ListView, so {@link #reloadRemote} can restore the selection. */
    private ListView<com.editora.vfs.RemoteConnection> remoteList;

    /** Re-read the Snippets / Templates lists from disk, keeping the selected row (set when the page is built). */
    private Runnable reloadSnippets;

    private Runnable reloadTemplates;

    /** Working copies for the Macros master-detail page. */
    private final javafx.collections.ObservableList<com.editora.macro.Macro> macroItems =
            javafx.collections.FXCollections.observableArrayList();

    private final javafx.collections.ObservableList<com.editora.macro.MacroStep> macroStepItems =
            javafx.collections.FXCollections.observableArrayList();
    private boolean loadingMacro = false;
    private String macroOriginalName; // the saved name of the selected macro (to detect rename)
    /** Re-registers the {@code macro.run.*} commands across windows after a Macros-page edit. */
    private Runnable onMacrosChanged = () -> {};
    /** Hooks used by the separate Run Configurations window; kept here as the window-service owner. */
    private Runnable onRunConfigsChanged = () -> {};

    private java.util.function.Supplier<String> runConfigSuggestion;
    private RunConfigurationsWindow runConfigurationsWindow;

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

    /** Why the current language's user snippet file cannot be parsed, or null. While set the page is
     *  read-only for that language: saving would replace the file with what little the page could load. */
    private String snippetFileProblem;
    /** Shared template registry (injected after construction); backs the Templates management page. */
    private com.editora.template.TemplateRegistry templateRegistry;
    /** Working copy of the templates (bundled + user) shown on the Templates page. */
    private final javafx.collections.ObservableList<com.editora.template.Template> templateItems =
            javafx.collections.FXCollections.observableArrayList();
    /** Ids of the shown templates that are user-owned (writable / removable); the rest are read-only bundled. */
    private final java.util.Set<String> templateUserIds = new java.util.HashSet<>();

    private boolean loadingTemplate = false;
    private CheckBox multiCaretCheck;
    private CheckBox copyLineNoSelectionCheck;
    private CheckBox copyWithHighlightingCheck;
    private CheckBox projectsCheck;
    private CheckBox gitCheck;
    private CheckBox blameCheck;
    private CheckBox githubCheck;
    private TextField ghPathField;
    private Label githubStatusLabel;
    private CheckBox updateCheckCheck;
    private CheckBox localHistoryCheck;
    private Spinner<Integer> historyMaxPerFileSpinner;
    private Spinner<Integer> historyMaxAgeSpinner;
    private Spinner<Integer> historyMaxTotalSpinner;
    private Label gitStatusLabel;
    private TextField gitPathField;
    private ComboBox<com.editora.git.GitPullMode> gitPullModeCombo;
    private CheckBox gitAutoFetchCheck;
    private Spinner<Integer> gitAutoFetchSpinner;
    private CheckBox mermaidCheck;
    private CheckBox httpCheck;
    private CheckBox htmlPreviewCheck;
    private CheckBox mcpCheck;
    private CheckBox aiMasterCheck;
    private CheckBox agentCheck;
    private AgentCoordinator agentCoordinator; // injected; backs the AI Agent page's detection status rows
    private ComboBox<String> agentClientCombo;
    private final java.util.Map<String, TextField> agentCommandFields = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Label> agentStatusLabels = new java.util.LinkedHashMap<>();
    private CheckBox agentIncludeContextCheck;
    private CheckBox aiCheck;
    private TextField aiModelField;
    private TextField aiApiKeyField;
    private CheckBox aiInlineCheck;
    private TextField aiCompletionModelField;
    private ComboBox<String> aiProviderCombo;
    private TextField aiEndpointField;
    private Label aiStatusLabel;
    /** Injected by MainController: runs a live connection check, delivering (ok, message) on the FX thread. */
    private java.util.function.Consumer<java.util.function.BiConsumer<Boolean, String>> aiConnectionProbe;
    /** Coalesces rapid field edits into a single connection check (~600 ms after the last keystroke). */
    private final javafx.animation.PauseTransition aiStatusDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));

    private TextField mmdcPathField;
    private CheckBox debugCheck;
    private CheckBox debugProgramConsoleCheck;
    /** Per-language debug-adapter controls, keyed by language id (java/python/javascript). */
    private final java.util.Map<String, CheckBox> debugEnableChecks = new java.util.LinkedHashMap<>();

    private final java.util.Map<String, TextField> debugCommandFields = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Label> debugStatusLabels = new java.util.LinkedHashMap<>();
    private TextField maidPathField;
    private TextField templateAuthorField;
    private Label mermaidStatusLabel;
    private CheckBox diagramCheck;
    private TextField dotPathField;
    private TextField plantumlPathField;
    private CheckBox typstCheck;
    private TextField typstPathField;
    private Label typstStatusLabel;
    private Label diagramStatusLabel;
    private final java.util.Map<BuildTool, CheckBox> buildToolChecks = new java.util.EnumMap<>(BuildTool.class);
    private final java.util.Map<BuildTool, TextField> buildToolCommandFields = new java.util.EnumMap<>(BuildTool.class);
    private final ComboBox<JdkChoice> mavenJdkCombo = new ComboBox<>();
    private final TextField mavenArchetypeCatalogField = new TextField();
    private final java.util.Map<BuildTool, Label> buildToolStatusLabels = new java.util.EnumMap<>(BuildTool.class);
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
    private CheckBox expertCheck;
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
    // Problems follows LSP; Debug follows Debugging. Standalone Run is always configurable.
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
    /** Card containers (UI Kit v1), hidden by the search filter once all of their rows are. */
    private final List<VBox> cards = new ArrayList<>();

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
    /** Re-read each Tool Windows row from the tool-window manager; run by {@link #load} under the flag below. */
    private final List<Runnable> toolWindowRowSyncs = new ArrayList<>();

    private boolean syncingToolWindowRows;

    public SettingsWindow(
            ConfigManager config,
            ToolWindowManager toolWindows,
            com.editora.git.GitService gitService,
            com.editora.github.GitHubService githubService,
            com.editora.mermaid.MermaidService mermaidService,
            com.editora.diagram.DiagramService diagramService,
            com.editora.typst.TypstService typstService,
            java.util.List<BuildCoordinator> buildCoordinators,
            com.editora.lsp.LspManager lspManager,
            com.editora.dap.DapManager dapManager,
            Consumer<Settings> onApply,
            Consumer<Boolean> onToggleZen,
            Consumer<Boolean> onToggleExpert,
            Consumer<Path> onOpenFile,
            Runnable onExportConfig,
            Runnable onShowDebugLog) {
        this.config = config;
        this.toolWindows = toolWindows;
        this.gitService = gitService;
        this.githubService = githubService;
        this.mermaidService = mermaidService;
        this.diagramService = diagramService;
        this.typstService = typstService;
        this.buildCoordinators = buildCoordinators;
        this.lspManager = lspManager;
        this.dapManager = dapManager;
        this.onApply = onApply;
        this.onToggleZen = onToggleZen;
        this.onToggleExpert = onToggleExpert;
        this.onOpenFile = onOpenFile;
        this.onExportConfig = onExportConfig;
        this.onShowDebugLog = onShowDebugLog;
        if (com.editora.AppInfo.isSnapshot()) {
            // Start the (background, bounded) branch lookup now, so About has it without ever waiting on git.
            com.editora.AppInfo.gitBranchAsync();
        }
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

    public void showAbbreviations(Window owner) {
        show(owner);
        sidebar.getSelectionModel().select(Category.ABBREVIATIONS);
    }

    /** Opens Settings focused on the Toolbar customization page (the {@code view.customizeToolbar} command). */
    public void showToolbar(Window owner) {
        show(owner);
        sidebar.getSelectionModel().select(Category.TOOLBAR);
    }

    /** Layout read/write the Toolbar page drives, backed by {@code ToolbarCoordinator} in the controller. */
    public interface ToolbarActions {
        /** The current effective layout (item ids + {@code "|"} separators). */
        java.util.List<String> current();

        /** Persist + apply a new layout (rebuilds every window's toolbar). */
        void apply(java.util.List<String> layout);

        /** Restore the shipped default arrangement. */
        void restoreDefault();
    }

    /** Wires the Toolbar page to the controller's {@code ToolbarCoordinator}. */
    public void setToolbarActions(ToolbarActions actions) {
        this.toolbarActions = actions;
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

    /** Opens Settings focused on the Workspace page (the {@code workspace.manageTrust} command). */
    public void showWorkspace(Window owner) {
        show(owner);
        sidebar.getSelectionModel().select(Category.WORKSPACE);
    }

    /** Opens the project/session-scoped Run Configurations window. */
    public void showRunConfigs(String selectName, Window owner) {
        if (runConfigurationsWindow == null) {
            runConfigurationsWindow = new RunConfigurationsWindow(
                    config, () -> runConfigSuggestion == null ? null : runConfigSuggestion.get(), onRunConfigsChanged);
        }
        runConfigurationsWindow.show(selectName, owner);
    }

    /** Supplies the active Java main class used to seed a newly added configuration. */
    public void setRunConfigSuggestion(java.util.function.Supplier<String> suggestion) {
        this.runConfigSuggestion = suggestion;
    }

    /**
     * Opens Settings focused on the page a Doctor row's "Settings…" action targets. The keys are the
     * Doctor check catalog's stable {@code settingsKey} strings, mapped here so the private
     * {@link Category} enum never leaks; an unknown key just opens Settings unfocused.
     */
    public void showDoctorTarget(String key, Window owner) {
        show(owner);
        Category target =
                switch (key == null ? "" : key) {
                    case "git" -> Category.GIT;
                    case "github" -> Category.GITHUB;
                    case "search" -> Category.SEARCH;
                    case "mermaid" -> Category.MERMAID;
                    case "diagrams" -> Category.DIAGRAMS;
                    case "typst" -> Category.TYPST;
                    case "lsp" -> Category.LSP;
                    case "debug" -> Category.DEBUG;
                    case "web" -> Category.WEB;
                    case "ai" -> Category.AI_GENERAL;
                    case "buildTools" -> Category.BUILD_TOOLS;
                    case "editor" -> Category.EDITOR;
                    default -> null;
                };
        if (target != null) {
            sidebar.getSelectionModel().select(target);
        }
    }

    /** Reads/mutates the trusted-workspace-root store for the Workspace page's Trusted Folders list. */
    public interface TrustActions {
        List<String> trustedRoots();

        void revoke(String root);

        void revokeAll();
    }

    /** Wires the Workspace page's Trusted Folders list to the controller's trust store. */
    public void setTrustActions(TrustActions actions) {
        this.trustActions = actions;
        refreshTrustedFolders();
    }

    /** Repopulates the Trusted Folders list from the store (after a revoke, or a newly granted trust). */
    public void refreshTrustedFolders() {
        trustedFoldersList.getItems().setAll(trustActions == null ? List.of() : trustActions.trustedRoots());
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
        refreshGithubStatus();
        refreshBuildToolStatus();
        refreshAgentClientStatus();
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
        reloadFileBackedEditors();
        // The keymap may have changed while the window was closed (or, the first time, the chips were
        // created after the backend was injected and are still empty).
        refreshShortcuts();
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            centerOnOwner(owner);
            stage.show();
        }
    }

    /** Centres on the owner using the size the window actually has, kept on the owner's screen. */
    private void centerOnOwner(Window owner) {
        javafx.geometry.Dimension2D size = preferredSize(owner);
        WindowPlacement.centerOnOwner(stage, owner, size.getWidth(), size.getHeight());
    }

    private void build(Window owner) {
        stage.setTitle(tr("settings.window.title"));
        // The window is non-modal: a store can change while it is open but in the background.
        stage.focusedProperty().addListener((o, was, now) -> {
            if (now && !loading) {
                reloadStoreBackedEditors();
            }
        });
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDING, e -> commitPendingFields());
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);

        buildControls();
        buildPreview();
        buildPages();

        searchField = new TextField();
        searchField.setPromptText(tr("settings.search.prompt"));
        searchField.setAccessibleText(tr("settings.search.prompt"));
        searchField.getStyleClass().add("settings-search");
        searchField.textProperty().addListener((o, a, b) -> filter(b));

        sidebar = new ListView<>();
        sidebar.getStyleClass().add("settings-sidebar");
        sidebar.getItems().setAll(sidebarItems());
        sidebar.setPrefWidth(216);
        sidebar.setMinWidth(216);
        sidebar.setCellFactory(v -> new CategoryCell());
        // The arrow, Home/End and Page keys only ever land on a category that can be opened: never on a
        // group header, and never on one the running search has disabled.
        sidebar.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isShiftDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                return;
            }
            List<Object> items = sidebar.getItems();
            int from = sidebar.getSelectionModel().getSelectedIndex();
            int page = (int) Math.max(1, sidebar.getHeight() / 34 - 1);
            int target = SettingsSidebarNav.target(
                    items.size(),
                    from,
                    e.getCode(),
                    page,
                    i -> items.get(i) instanceof Category c && !searchHiddenCats.contains(c));
            if (target == SettingsSidebarNav.NOT_A_NAVIGATION_KEY) {
                return;
            }
            e.consume();
            if (target >= 0 && target != from) {
                sidebar.getSelectionModel().clearAndSelect(target);
                sidebar.getFocusModel().focus(target);
                revealSidebarRow(target);
            }
        });
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b instanceof Category) { // group headers aren't pages
                showContent();
                // Every page starts at its top. A ScrollPane keeps its vvalue across a content swap, so
                // opening a short page after scrolling down a long one landed mid-page — and on a page
                // that fits, silently nowhere at all. Deferred: the new content has not been laid out
                // yet, and setting vvalue before that is undone when the scroll range is recomputed.
                contentScroll.setVvalue(0);
                javafx.application.Platform.runLater(() -> contentScroll.setVvalue(0));
            }
        });

        searchEmpty = new Label();
        searchEmpty.getStyleClass().add("settings-search-empty");
        searchEmpty.setWrapText(true);
        searchEmpty.setVisible(false);

        contentScroll = new ScrollPane();
        contentScroll.setFitToWidth(true);
        contentScroll.getStyleClass().add("settings-content");
        HBox.setHgrow(contentScroll, Priority.ALWAYS);

        // The kit puts the search at the top of the nav rail, not spanning the window: search scopes the
        // sidebar (it greys categories and auto-selects the first hit), so it lives with the sidebar.
        VBox nav = new VBox(8, searchField, sidebar);
        nav.getStyleClass().add("settings-nav");
        VBox.setVgrow(sidebar, Priority.ALWAYS);

        HBox body = new HBox(nav, contentScroll);
        VBox.setVgrow(body, Priority.ALWAYS);

        // Kit footer: Reset to Defaults on the left (secondary), Close on the right (the one accent
        // button). Reset also stays on the Advanced page so searching "reset" still finds it.
        Button footReset = new Button(tr("settings.resetDefaults"));
        footReset.setOnAction(e -> resetAll());
        Button close = new Button(tr("settings.close"));
        close.getStyleClass().add("accent");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, footReset, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.getStyleClass().add("settings-footer");

        VBox root = new VBox(0, body, buttons);
        javafx.geometry.Dimension2D size = preferredSize(owner);
        root.setPrefWidth(size.getWidth());
        root.setPrefHeight(size.getHeight());

        Scene scene = new Scene(root, size.getWidth(), size.getHeight());
        // The live preview needs the editor surface + token colors; the dialog controls keep AtlantaFX.
        scene.getStylesheets()
                .addAll(
                        SettingsWindow.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm(),
                        SettingsWindow.class
                                .getResource("/com/editora/styles/syntax.css")
                                .toExternalForm());
        // Escape leaves: first a running search, then the window. A filter, because a ListView (the
        // sidebar, where the focus usually is) swallows Escape — so what really uses the key is exempted
        // by name in escapeIsTaken. An open combo popup or context menu takes the key before the scene.
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != javafx.scene.input.KeyCode.ESCAPE
                    || e.isShortcutDown()
                    || e.isAltDown()
                    || escapeIsTaken(e.getTarget())) {
                return;
            }
            e.consume();
            if (!searchField.getText().isEmpty()) {
                searchField.clear();
            } else {
                stage.close();
            }
        });
        stage.setScene(scene);
        // Floor the window size: the rows wrap, but the list-beside-form pages need MIN_WIDTH.
        stage.setMinWidth(Math.min(MIN_WIDTH, size.getWidth())); // never wider than a small screen allows
        stage.setMinHeight(Math.min(MIN_HEIGHT, size.getHeight()));

        sidebar.getSelectionModel().select(Category.APPEARANCE);
    }

    /**
     * Whether Escape pressed on {@code target} is that control's own: the shortcut recorder (cancels the
     * recording), a snippet/template body (a text editor — not a request to close the window), a cell
     * being edited, or a combo whose popup is open.
     */
    private static boolean escapeIsTaken(Object target) {
        for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
            if (n instanceof CodeArea
                    || n.getStyleClass().contains("shortcut-capture")
                    || (n instanceof javafx.scene.control.Cell<?> cell && cell.isEditing())
                    || (n instanceof javafx.scene.control.ComboBoxBase<?> combo && combo.isShowing())) {
                return true;
            }
        }
        return false;
    }

    /** Scrolls the sidebar just far enough to show row {@code index} (the keyboard moved the selection). */
    private void revealSidebarRow(int index) {
        if (sidebar.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow) {
            javafx.scene.control.IndexedCell<?> first = flow.getFirstVisibleCell();
            javafx.scene.control.IndexedCell<?> last = flow.getLastVisibleCell();
            if (first != null && last != null && index > first.getIndex() && index < last.getIndex()) {
                return; // fully in view already
            }
        }
        // Going up, show the group header above the category too rather than cutting it off.
        boolean up = index > 0 && sidebar.getItems().get(index - 1) instanceof Group;
        sidebar.scrollTo(up ? index - 1 : index);
    }

    /**
     * {@link #WIDTH}×{@link #HEIGHT}, clamped to {@link #MAX_SCREEN_FRACTION} of the <em>visual</em> bounds
     * (which exclude the menu bar / taskbar) of the screen the owner is on. Without the clamp the window
     * would open taller than a laptop display and hide its own Close button.
     */
    private static javafx.geometry.Dimension2D preferredSize(Window owner) {
        return WindowPlacement.clampSize(WIDTH, HEIGHT, WindowPlacement.screenOf(owner), MAX_SCREEN_FRACTION);
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
            Alert restart = Dialogs.styled(
                    new Alert(Alert.AlertType.INFORMATION, tr("dialog.language.restart"), ButtonType.OK));
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
            KeymapLayers.switchKeymap(config.getSettings(), now);
            config.save();
            if (onKeymapChanged != null) {
                onKeymapChanged.run(); // reload the shared keymap live across all windows
            }
            refreshShortcuts(); // the list below and the chord chips show the keymap that is now live
        });

        fontFamily = new ComboBox<>();
        fontFamily.getItems().setAll(fontFamilyChoices());
        fontFamily.setPrefWidth(220);
        fontFamily.valueProperty().addListener((obs, old, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setFontFamily(now);
            apply();
        });

        fontSize = IntSpinners.editable(Settings.MIN_FONT_SIZE, Settings.MAX_FONT_SIZE, 14, 1);
        fontSize.setPrefWidth(90);
        fontSize.valueProperty().addListener((obs, old, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setFontSize(now);
            apply();
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

        tabSizeSpinner = IntSpinners.editable(Settings.MIN_TAB_SIZE, Settings.MAX_TAB_SIZE, 4, 1);
        tabSizeSpinner.setPrefWidth(90);
        tabSizeSpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setTabSize(now);
            apply();
        });

        fillColumnSpinner = IntSpinners.editable(
                Settings.MIN_FILL_COLUMN, Settings.MAX_FILL_COLUMN, com.editora.editops.Filler.DEFAULT_FILL_COLUMN, 1);
        fillColumnSpinner.setPrefWidth(90);
        fillColumnSpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setFillColumn(now);
            apply();
        });

        // Line count above which the minimap + LSP auto-disable (highlighting + editing stay); 0 = never.
        largeFileThresholdSpinner = IntSpinners.editable(0, Settings.MAX_LARGE_FILE_THRESHOLD, 10_000, 1000);
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
        inlayHintsCheck = viewCheck(tr("settings.inlayHints"), Settings::setInlayHints);
        codeLensCheck = viewCheck(tr("settings.codeLens"), Settings::setCodeLens);
        inlayHintModeCombo = new ComboBox<>();
        inlayHintModeCombo.getItems().setAll("literals", "all");
        inlayHintModeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String key) {
                return inlayHintModeName(key);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        inlayHintModeCombo.setMinWidth(170); // no fixed width: it cut off the longer (translated) items
        inlayHintModeCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setInlayHintMode(now);
            apply();
        });
        onTypeFormattingCheck = viewCheck(tr("settings.onTypeFormatting"), Settings::setLspOnTypeFormatting);
        pasteImportsCheck = viewCheck(tr("settings.lspPasteImports"), Settings::setLspPasteImports);
        smartSemicolonCheck = viewCheck(tr("settings.lspSmartSemicolon"), Settings::setLspSmartSemicolon);
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
        indentStyleCombo.setMinWidth(170); // no fixed width: it cut off the longer (translated) items
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
        pdfPageSizeCombo.setMinWidth(170); // no fixed width: it cut off the longer (translated) items
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

        menuBarCheck = viewCheck(tr("settings.showMenuBar"), Settings::setShowMenuBar);
        toolbarCheck = viewCheck(tr("settings.showToolbar"), Settings::setShowToolbar);
        statusBarCheck = viewCheck(tr("settings.showStatusBar"), Settings::setShowStatusBar);
        tabBarCheck = viewCheck(tr("settings.showTabBar"), Settings::setShowTabBar);
        breadcrumbCheck = viewCheck(tr("settings.showBreadcrumb"), Settings::setShowBreadcrumb);
        simpleModeCheck = viewCheck(tr("settings.simpleMode"), Settings::setSimpleMode);
        toolStripeCheck = viewCheck(tr("settings.showToolStripe"), Settings::setShowToolStripe);
        extendedWindowCheck = viewCheck(tr("settings.extendedWindow"), Settings::setExtendedWindow);
        // Not offered on macOS: the menu belongs to the system menu bar there, so the setting could only
        // ever be a switch that does nothing. Disabled rather than hidden, so its note still explains why.
        extendedWindowCheck.setDisable(!ExtendedWindow.supportedOn(System.getProperty("os.name")));
        paletteSearchEverywhereCheck =
                viewCheck(tr("settings.paletteSearchEverywhere"), Settings::setPaletteUsesSearchEverywhere);
        projectHiddenCheck = viewCheck(tr("settings.projectShowHidden"), Settings::setProjectShowHidden);
        markdownFormatBarCheck = viewCheck(tr("settings.markdownFormatBar"), Settings::setMarkdownFormatBar);
        lspInstallPromptsCheck = viewCheck(tr("settings.lspInstallPrompts"), Settings::setLspInstallPrompts);
        markdownLintCheck = viewCheck(tr("settings.markdownLint"), Settings::setMarkdownLint);
        mathSupportCheck = viewCheck(tr("settings.mathSupport"), Settings::setMathSupport);
        editorConfigCheck = viewCheck(tr("settings.enableEditorConfig"), Settings::setEditorConfigSupport);
        logViewerCheck = viewCheck(tr("settings.logViewer"), Settings::setLogViewer);
        testRunnerCheck = viewCheck(tr("settings.testRunner"), Settings::setTestRunner);
        csvGridCheck = viewCheck(tr("settings.csvPreview"), Settings::setCsvPreview);
        structuredPreviewCheck = viewCheck(tr("settings.structuredPreview"), Settings::setStructuredPreview);
        svgPreviewCheck = viewCheck(tr("settings.svgPreview"), Settings::setSvgPreview);
        crontabPreviewCheck = viewCheck(tr("settings.crontabPreview"), Settings::setCrontabPreview);
        fstabPreviewCheck = viewCheck(tr("settings.fstabPreview"), Settings::setFstabPreview);
        systemdPreviewCheck = viewCheck(tr("settings.systemdPreview"), Settings::setSystemdPreview);
        sshConfigPreviewCheck = viewCheck(tr("settings.sshConfigPreview"), Settings::setSshConfigPreview);
        dockerfilePreviewCheck = viewCheck(tr("settings.dockerfilePreview"), Settings::setDockerfilePreview);
        githubActionsPreviewCheck = viewCheck(tr("settings.githubActionsPreview"), Settings::setGithubActionsPreview);
        pomPreviewCheck = viewCheck(tr("settings.pomPreview"), Settings::setPomPreview);
        stickyScrollCheck = viewCheck(tr("settings.stickyScroll"), Settings::setStickyScroll);
        symbolIndexCheck = viewCheck(tr("settings.symbolIndex"), Settings::setSymbolIndex);
        csvRainbowCheck = viewCheck(tr("settings.csvRainbow"), Settings::setCsvRainbow);
        bracketColorsCheck = viewCheck(tr("settings.bracketColors"), Settings::setBracketColors);
        autoRenameTagCheck = viewCheck(tr("settings.autoRenameTag"), Settings::setAutoRenameTag);
        autoFillCheck = viewCheck(tr("settings.autoFill"), Settings::setAutoFill);
        autoCloseTagsCheck = viewCheck(tr("settings.autoCloseTags"), Settings::setAutoCloseTags);
        todoHighlightCheck = viewCheck(tr("settings.todoHighlight"), Settings::setTodoHighlight);
        multiCaretCheck = viewCheck(tr("settings.multiCaret"), Settings::setMultiCaret);
        copyLineNoSelectionCheck =
                viewCheck(tr("settings.copyLineWhenNoSelection"), Settings::setCopyLineWhenNoSelection);
        copyWithHighlightingCheck =
                viewCheck(tr("settings.copyWithSyntaxHighlighting"), Settings::setCopyWithSyntaxHighlighting);

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
            refreshGithubStatus(); // the GitHub page says when it is waiting for Git support
        });

        blameCheck = new CheckBox(tr("settings.git.blameInline"));
        blameCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setGitBlameInline(now);
            apply();
        });

        gitPathField = new TextField();
        gitPathField.setPromptText("git");
        commitOnEnterOrBlur(gitPathField, () -> config.getSettings().getGitPath(), now -> {
            config.getSettings().setGitPath(now);
            apply(); // applySupport pushes the command into GitService
            probeGit();
        });

        gitPullModeCombo = new ComboBox<>();
        gitPullModeCombo.getItems().setAll(com.editora.git.GitPullMode.values());
        gitPullModeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(com.editora.git.GitPullMode mode) {
                return mode == null ? "" : GitCoordinator.pullModeLabel(mode);
            }

            @Override
            public com.editora.git.GitPullMode fromString(String label) {
                return null; // not editable
            }
        });
        gitPullModeCombo.setMinWidth(170); // no fixed width: the translated items differ a lot in length
        gitPullModeCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setGitPullMode(now.id());
            apply();
        });

        gitAutoFetchCheck = new CheckBox(tr("settings.git.autoFetch"));
        gitAutoFetchCheck.selectedProperty().addListener((obs, was, now) -> {
            gitAutoFetchSpinner.setDisable(!now);
            if (loading) {
                return;
            }
            config.getSettings().setGitAutoFetch(now);
            apply(); // applySupport starts or stops the timer
        });
        gitAutoFetchSpinner = IntSpinners.editable(
                1,
                com.editora.config.Settings.MAX_GIT_AUTO_FETCH_MINUTES,
                config.getSettings().getGitAutoFetchMinutes(),
                1);
        gitAutoFetchSpinner.setPrefWidth(90);
        gitAutoFetchSpinner.setAccessibleText(tr("settings.git.autoFetchMinutes"));
        gitAutoFetchSpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setGitAutoFetchMinutes(now);
            apply();
        });

        githubCheck = new CheckBox(tr("settings.enableGithub"));
        githubCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setGithubSupport(now);
            apply();
            ghPathField.setDisable(!now);
            refreshGithubStatus();
        });
        ghPathField = new TextField();
        ghPathField.setPromptText("gh");
        commitOnEnterOrBlur(ghPathField, () -> config.getSettings().getGhPath(), now -> {
            config.getSettings().setGhPath(now);
            apply();
            refreshGithubStatus();
        });

        updateCheckCheck = viewCheck(tr("settings.checkForUpdates"), Settings::setUpdateCheck);

        localHistoryCheck = new CheckBox(tr("settings.enableLocalHistory"));
        historyMaxPerFileSpinner = historySpinner(1, Settings.MAX_HISTORY_PER_FILE, 50, Settings::setHistoryMaxPerFile);
        historyMaxAgeSpinner = historySpinner(0, Settings.MAX_HISTORY_AGE_DAYS, 30, Settings::setHistoryMaxAgeDays);
        historyMaxTotalSpinner = historySpinner(1, Settings.MAX_HISTORY_TOTAL_MB, 50, Settings::setHistoryMaxTotalMb);
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
        commitOnEnterOrBlur(mmdcPathField, () -> config.getSettings().getMmdcPath(), now -> {
            config.getSettings().setMmdcPath(now);
            apply();
            refreshMermaidStatus();
        });
        maidPathField = new TextField();
        maidPathField.setPromptText(com.editora.mermaid.MermaidService.DEFAULT_MAID);
        commitOnEnterOrBlur(maidPathField, () -> config.getSettings().getMaidPath(), now -> {
            config.getSettings().setMaidPath(now);
            apply();
            refreshMermaidStatus();
        });

        diagramCheck = new CheckBox(tr("settings.enableDiagram"));
        diagramCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDiagramSupport(now);
            apply();
            refreshDiagramStatus();
        });
        dotPathField = new TextField();
        dotPathField.setPromptText("dot");
        commitOnEnterOrBlur(dotPathField, () -> config.getSettings().getDotPath(), now -> {
            config.getSettings().setDotPath(now);
            apply();
            refreshDiagramStatus();
        });
        plantumlPathField = new TextField();
        plantumlPathField.setPromptText("plantuml");
        commitOnEnterOrBlur(plantumlPathField, () -> config.getSettings().getPlantumlPath(), now -> {
            config.getSettings().setPlantumlPath(now);
            apply();
            refreshDiagramStatus();
        });

        typstCheck = new CheckBox(tr("settings.enableTypst"));
        typstCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setTypstSupport(now);
            apply();
            refreshTypstStatus();
        });
        typstPathField = new TextField();
        typstPathField.setPromptText("typst");
        commitOnEnterOrBlur(typstPathField, () -> config.getSettings().getTypstPath(), now -> {
            config.getSettings().setTypstPath(now);
            apply();
            refreshTypstStatus();
        });

        for (BuildTool bt : BuildTool.enabled()) {
            CheckBox check = new CheckBox(tr("settings.enableBuildTool", bt.displayName()));
            check.selectedProperty().addListener((obs, was, now) -> {
                bt.setEnabledIn(config.getSettings(), now);
                apply();
                refreshBuildToolStatus();
            });
            buildToolChecks.put(bt, check);
            TextField commandField = new TextField();
            commandField.setPromptText(bt.commandExample());
            commitOnEnterOrBlur(commandField, () -> bt.commandIn(config.getSettings()), now -> {
                bt.setCommandIn(config.getSettings(), now);
                apply();
            });
            buildToolCommandFields.put(bt, commandField);
            if (bt == BuildTool.MAVEN) {
                JdkChoice.configure(mavenJdkCombo, tr("settings.maven.jdk.system"));
                mavenJdkCombo.setId("settings-maven-jdk");
                mavenJdkCombo.valueProperty().addListener((obs, was, now) -> {
                    if (!loading) {
                        config.getSettings().setMavenJdkHome(now == null ? "" : now.home());
                        apply();
                    }
                });
                mavenArchetypeCatalogField.setPromptText(Settings.DEFAULT_MAVEN_ARCHETYPE_CATALOG);
                commitOnEnterOrBlur(
                        mavenArchetypeCatalogField,
                        () -> shownOrBlank(
                                mavenArchetypeCatalogField,
                                config.getSettings().getMavenArchetypeCatalogUrl(),
                                config.getSettings().getMavenArchetypeCatalogUrlRaw()),
                        now -> {
                            config.getSettings().setMavenArchetypeCatalogUrl(now);
                            apply();
                        });
            }
            Label status = new Label(tr("settings.buildTools.notFound", bt.displayName()));
            status.getStyleClass().add("settings-git-status");
            status.setWrapText(true);
            status.setMaxWidth(440);
            buildToolStatusLabels.put(bt, status);
        }

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
        commitOnEnterOrBlur(ripgrepCommandField, () -> config.getSettings().getRipgrepCommand(), now -> {
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

        aiMasterCheck = new CheckBox(tr("settings.ai.masterEnable"));
        aiMasterCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAiEnabled(now);
            apply();
            updateAiSubEnablement(now); // the AI Agent / AI Actions checkboxes are only meaningful while on
        });

        agentCheck = new CheckBox(tr("settings.agent.enable"));
        agentCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAgentSupport(now);
            apply();
        });
        agentClientCombo = new ComboBox<>();
        com.editora.agent.AcpAgentRegistry.all()
                .forEach(d -> agentClientCombo.getItems().add(d.id()));
        agentClientCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : tr("settings.agent.client." + id);
            }

            @Override
            public String fromString(String str) {
                return str;
            }
        });
        agentClientCombo.valueProperty().addListener((obs, was, now) -> {
            if (!loading && now != null && agentCoordinator != null) {
                agentCoordinator.switchAgentClient(com.editora.agent.AcpAgentRegistry.from(now));
                apply(); // run the standard settings-applied hook for consistency with the other rows
            }
        });
        for (AgentClientUi a : agentClientUis()) {
            TextField field = new TextField();
            field.setPromptText(a.defaultCommand());
            commitOnEnterOrBlur(field, a.getCommand(), now -> {
                a.setCommand().accept(now);
                apply();
                if (agentCoordinator != null) {
                    agentCoordinator.invalidateDetection(); // command changed -> re-probe
                }
                refreshAgentClientStatus();
            });
            agentCommandFields.put(a.id(), field);
        }
        agentIncludeContextCheck = new CheckBox(tr("settings.agent.includeContext"));
        agentIncludeContextCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAgentIncludeContext(now);
            apply();
        });

        aiCheck = new CheckBox(tr("settings.ai.enable"));
        aiCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAiSupport(now);
            apply();
            scheduleAiStatus();
        });
        aiProviderCombo = new ComboBox<>();
        aiProviderCombo.getItems().addAll(com.editora.ai.AiProvider.ids());
        aiProviderCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : tr("settings.ai.provider." + id);
            }

            @Override
            public String fromString(String s) {
                return s;
            }
        });
        aiProviderCombo.valueProperty().addListener((obs, was, now) -> {
            if (!loading && now != null) {
                if (was != null) {
                    // Text typed but not yet committed belongs to the provider it was typed for; the fields
                    // are about to be refilled with the new provider's values.
                    commitAiFieldsFor(com.editora.ai.AiProvider.from(was));
                }
                config.getSettings().setAiProvider(now);
                // Keys are per-provider: show the newly-selected provider's key (never carry one provider's
                // credential over to the other, which would send it to that provider's endpoint).
                syncAiProviderFields();
                apply();
                scheduleAiStatus();
            }
        });
        aiStatusDebounce.setOnFinished(e -> refreshAiStatus());
        aiEndpointField = new TextField();
        aiEndpointField.setPromptText(tr("settings.ai.endpointPrompt"));
        commitOnEnterOrBlur(aiEndpointField, () -> config.getSettings().getAiEndpointFor(selectedAiProvider()), now -> {
            config.getSettings().setAiEndpointFor(selectedAiProvider(), now);
            apply();
            scheduleAiStatus();
        });
        aiModelField = new TextField();
        aiModelField.setPromptText("claude-opus-4-8");
        commitOnEnterOrBlur(aiModelField, () -> config.getSettings().getAiModelFor(selectedAiProvider()), now -> {
            config.getSettings().setAiModelFor(selectedAiProvider(), now);
            apply();
            scheduleAiStatus();
        });
        aiApiKeyField = new javafx.scene.control.PasswordField();
        aiApiKeyField.setPromptText(tr("settings.ai.apiKeyPrompt"));
        commitOnEnterOrBlur(aiApiKeyField, () -> config.getSettings().getApiKeyFor(selectedAiProvider()), now -> {
            // Store under the currently-selected provider so each provider keeps its own key.
            config.getSettings().setApiKeyFor(selectedAiProvider(), now);
            apply();
            scheduleAiStatus();
        });
        aiInlineCheck = new CheckBox(tr("settings.ai.inline"));
        aiInlineCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setAiInlineCompletion(now);
            apply();
        });
        aiCompletionModelField = new TextField();
        aiCompletionModelField.setPromptText("claude-haiku-4-5");
        commitOnEnterOrBlur(
                aiCompletionModelField,
                () -> config.getSettings().getAiCompletionModelFor(selectedAiProvider()),
                now -> {
                    config.getSettings().setAiCompletionModelFor(selectedAiProvider(), now);
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
        commitOnEnterOrBlur(templateAuthorField, () -> config.getSettings().getAuthorNameRaw(), now -> {
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
        debugProgramConsoleCheck = new CheckBox(tr("settings.debug.programConsole"));
        debugProgramConsoleCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDebugProgramConsole(now);
            apply();
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
            commitOnEnterOrBlur(field, dbg.getCommand(), now -> {
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
            updateLspToolRowsEnabled(); // reflect on the Tool Windows page's Problems row
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
            commitOnEnterOrBlur(field, srv.getCommand(), now -> {
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
            syncFocusModeChecks();
        });

        expertCheck = new CheckBox(tr("settings.expert"));
        expertCheck.selectedProperty().addListener((obs, was, now) -> {
            if (loading) {
                return;
            }
            onToggleExpert.accept(now);
            syncViewChecks();
            syncFocusModeChecks();
        });

        autoSaveCombo = new ComboBox<>();
        autoSaveCombo
                .getItems()
                .setAll(
                        FileWorkflowCoordinator.AUTOSAVE_OFF,
                        FileWorkflowCoordinator.AUTOSAVE_DELAY,
                        FileWorkflowCoordinator.AUTOSAVE_FOCUS);
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
        autoSaveCombo.setMinWidth(170); // no fixed width: it cut off the longer (translated) items
        autoSaveCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setAutoSave(now);
            autoSaveDelaySpinner.setDisable(!FileWorkflowCoordinator.AUTOSAVE_DELAY.equals(now));
            apply();
        });

        autoSaveDelaySpinner =
                IntSpinners.editable(Settings.MIN_AUTO_SAVE_DELAY_SECONDS, Settings.MAX_AUTO_SAVE_DELAY_SECONDS, 1, 1);
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
        Spinner<Integer> s = IntSpinners.editable(min, max, def, 1);
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
        pages.put(Category.TOOLBAR, toolbarPage());
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
        pages.put(Category.DIAGRAMS, diagramsPage());
        pages.put(Category.TYPST, typstPage());
        pages.put(Category.BUILD_TOOLS, buildToolsPage());
        pages.put(Category.WEB, webPage());
        pages.put(Category.EXTERNAL_TOOLS, externalToolsPage());
        pages.put(Category.ABBREVIATIONS, abbreviationsPage());
        // Version control
        pages.put(Category.GIT, gitPage());
        pages.put(Category.GITHUB, githubPage());
        // System
        pages.put(Category.KEYMAPS, keymapsPage());
        pages.put(Category.MACROS, macrosPage());
        pages.put(Category.REMOTE, remotePage());
        pages.put(Category.PLUGINS, pluginsPage());
        pages.put(Category.MCP, mcpPage());
        pages.put(Category.AI_GENERAL, aiGeneralPage());
        pages.put(Category.AGENT, agentPage());
        pages.put(Category.AI, aiPage());
        pages.put(Category.ADVANCED, advancedPage());
    }

    private VBox appearancePage() {
        VBox p = page(tr("settings.cat.appearance"));
        Card mainCard = card(p, null);
        // The hints are row descriptions, not labels boxed with the combo: such a box is as wide as its
        // (translated) hint and left-aligns the combo inside, so the four combos sat at four x positions.
        controlRow(
                mainCard,
                Category.APPEARANCE,
                tr("settings.uiLanguage"),
                tr("settings.uiLanguage.note"),
                languageCombo,
                "language interface ui locale translation");
        controlRow(
                mainCard,
                Category.APPEARANCE,
                tr("settings.fontFamily"),
                tr("settings.fontNote"),
                fontFamily,
                "font family typeface monospace");
        controlRow(mainCard, Category.APPEARANCE, tr("settings.fontSize"), null, fontSize, "font size text");
        controlRow(
                mainCard,
                Category.APPEARANCE,
                tr("settings.theme"),
                null,
                themeCombo,
                "theme appearance dark light app chrome");
        controlRow(
                mainCard,
                Category.APPEARANCE,
                tr("settings.editorTheme"),
                tr("settings.editorThemeNote"),
                editorThemeCombo,
                "editor theme syntax colors highlighting");
        Card previewSection = card(p, tr("settings.livePreview"));
        cardRow(previewSection, Category.APPEARANCE, preview, "preview sample code");
        return p;
    }

    private VBox keymapsPage() {
        VBox p = page(tr("settings.cat.keymaps"));
        Card mainCard = card(p, null);
        controlRow(
                mainCard,
                Category.KEYMAPS,
                tr("settings.keymap"),
                tr("settings.keymap.note"),
                keymapCombo,
                "keymap keybindings shortcuts emacs vim cua sublime vscode intellij");

        // --- Customize shortcuts: searchable list of every command + its current chord ---
        Card sec = card(p, tr("settings.shortcuts.title"));
        shortcutFilter = new TextField();
        shortcutFilter.setPromptText(tr("settings.shortcuts.filter"));
        shortcutFilter.textProperty().addListener((o, was, now) -> refreshShortcuts());
        shortcutListBox = new VBox(2);
        shortcutListBox.getStyleClass().add("shortcut-list");
        ScrollPane scroll = new ScrollPane(shortcutListBox);
        shortcutScroll = scroll;
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(320);
        scroll.getStyleClass().add("shortcut-scroll");
        Label note = note(tr("settings.shortcuts.note"));
        note.setWrapText(true);
        Button resetAll = new Button(tr("settings.shortcuts.resetAll"));
        resetAll.setOnAction(e -> {
            if (shortcutActions != null && confirmResetAllShortcuts()) {
                shortcutActions.resetAll();
                refreshShortcuts();
            }
        });
        VBox box = new VBox(6, note, shortcutFilter, scroll, resetAll);
        cardRow(
                sec,
                Category.KEYMAPS,
                box,
                "shortcut keybinding customize rebind record reset clear key chord accelerator");
        refreshShortcuts();
        return p;
    }

    /**
     * Asks before every custom binding is dropped. One click used to wipe them all — including the chords
     * given to macros and external tools on other pages — while Reset to Defaults, which keeps them, asked.
     */
    private boolean confirmResetAllShortcuts() {
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION, tr("dialog.shortcut.resetAll.body"), ButtonType.OK, ButtonType.CANCEL));
        confirm.initOwner(stage);
        confirm.setTitle(tr("dialog.shortcut.resetAll.title"));
        confirm.setHeaderText(null);
        confirm.getDialogPane().setMinWidth(460);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * Rebuilds the shortcut list from the backend, honoring the filter, and refills the chord chips on the
     * other pages (both show the live keymap, so they go stale together). The list part is a no-op until the
     * backend is set and the page is built.
     */
    private void refreshShortcuts() {
        refreshShortcuts(null);
    }

    /** {@link #refreshShortcuts()}, then moves keyboard focus to {@code focusId}'s row (rebuilding drops it). */
    private void refreshShortcuts(String focusId) {
        refreshChordChips();
        if (refreshMacroKeybinding != null) {
            refreshMacroKeybinding.run();
        }
        if (shortcutListBox == null || shortcutActions == null) {
            return;
        }
        shortcutListBox.getChildren().clear();
        shortcutRowsById.clear();
        shortcutTabStop = null;
        String q = shortcutFilter == null ? "" : shortcutFilter.getText().trim().toLowerCase(Locale.ROOT);
        for (Shortcut s : shortcutActions.rows()) {
            boolean match = q.isEmpty()
                    || s.title().toLowerCase(Locale.ROOT).contains(q)
                    || s.id().toLowerCase(Locale.ROOT).contains(q)
                    || (s.chord() != null && s.chord().toLowerCase(Locale.ROOT).contains(q));
            if (match) {
                HBox row = shortcutRow(s);
                shortcutRowsById.put(s.id(), row);
                shortcutListBox.getChildren().add(row);
            }
        }
        // One Tab stop for the whole list (the selected row, else the first); the arrow keys move within it.
        // ~450 separate Tab stops would put "Reset all shortcuts" and the footer out of reach instead.
        HBox stop = shortcutRowsById.get(selectedShortcutId);
        if (stop == null && !shortcutListBox.getChildren().isEmpty()) {
            stop = (HBox) shortcutListBox.getChildren().get(0);
        }
        if (stop != null && !stop.getStyleClass().contains("shortcut-row-recording")) {
            stop.setFocusTraversable(true);
            shortcutTabStop = stop;
        }
        HBox focus = focusId == null ? null : shortcutRowsById.get(focusId);
        if (focus != null) {
            javafx.application.Platform.runLater(focus::requestFocus);
        }
    }

    /** A single command row — either the static chord + Record/Reset buttons, or a live capture field. */
    private HBox shortcutRow(Shortcut s) {
        HBox row = new HBox(8);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("shortcut-row");
        Label title = new Label(s.title());
        title.setMaxWidth(Double.MAX_VALUE);
        title.setWrapText(true);
        HBox.setHgrow(title, Priority.ALWAYS);

        if (s.id().equals(recordingCommandId)) {
            row.getStyleClass().add("shortcut-row-recording");
            // Enter saves and Escape cancels from inside the field: it records Tab like any other key, so
            // the Save button beside it cannot be reached from the keyboard.
            TextField capture = ShortcutCapture.field(seq -> commitRecording(s.id(), seq), () -> {
                recordingCommandId = null;
                refreshShortcuts(s.id());
            });
            Button save = new Button(tr("settings.shortcuts.save"));
            save.getStyleClass().add("success");
            save.setDefaultButton(false);
            save.setOnAction(e -> commitRecording(s.id(), capture.getText()));
            Button cancel = new Button(tr("settings.shortcuts.cancel"));
            cancel.setOnAction(e -> {
                recordingCommandId = null;
                refreshShortcuts(s.id());
            });
            row.getChildren().addAll(title, capture, save, cancel);
            javafx.application.Platform.runLater(capture::requestFocus);
        } else {
            String chordText = s.chord() == null ? tr("settings.shortcuts.unbound") : s.chord();
            Label chord = new Label(chordText);
            chord.getStyleClass().add(s.chord() == null ? "shortcut-unbound" : "shortcut-chord");
            chord.setMinWidth(150);
            row.getChildren().addAll(title, chord);
            // Record/Reset are shown only for the selected row, keeping the list uncluttered. A click
            // selects a row; from the keyboard the arrow keys walk the rows and Enter/Space selects.
            row.getStyleClass().add("shortcut-row-clickable");
            row.setAccessibleRole(javafx.scene.AccessibleRole.LIST_ITEM);
            row.setAccessibleText(s.title() + ", " + chordText);
            Button record = new Button(tr("settings.shortcuts.record"));
            Runnable select = () -> {
                if (s.id().equals(selectedShortcutId)) {
                    record.fire(); // a second Enter on the selected row starts recording
                } else {
                    selectedShortcutId = s.id();
                    refreshShortcuts(s.id());
                }
            };
            row.setOnMouseClicked(e -> {
                if (!s.id().equals(selectedShortcutId)) {
                    select.run();
                }
            });
            row.focusedProperty().addListener((o, was, now) -> {
                if (now) {
                    if (shortcutTabStop != null && shortcutTabStop != row) {
                        shortcutTabStop.setFocusTraversable(false);
                    }
                    row.setFocusTraversable(true);
                    shortcutTabStop = row;
                    scrollShortcutIntoView(row);
                }
            });
            row.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                if (e.getTarget() != row || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                    return; // a key pressed on the row's own Record/Reset button is the button's
                }
                List<Node> all = shortcutListBox.getChildren();
                int target =
                        switch (e.getCode()) {
                            case UP -> all.indexOf(row) - 1;
                            case DOWN -> all.indexOf(row) + 1;
                            case HOME -> 0;
                            case END -> all.size() - 1;
                            default -> -1;
                        };
                if (e.getCode() == javafx.scene.input.KeyCode.ENTER
                        || e.getCode() == javafx.scene.input.KeyCode.SPACE) {
                    select.run();
                    e.consume();
                } else if (target >= 0) {
                    if (target < all.size()) {
                        all.get(target).requestFocus();
                    }
                    e.consume(); // also at either end: Down on the last row must not jump out of the list
                }
            });
            if (s.id().equals(selectedShortcutId)) {
                row.getStyleClass().add("shortcut-row-selected");
                record.setOnAction(e -> {
                    recordingCommandId = s.id();
                    refreshShortcuts();
                });
                Button reset = new Button(tr("settings.shortcuts.reset"));
                reset.setOnAction(e -> {
                    shortcutActions.reset(s.id());
                    refreshShortcuts(s.id());
                });
                row.getChildren().addAll(record, reset);
            }
        }
        return row;
    }

    /** Scrolls the shortcut list just far enough to show {@code row} (focus moved to it from the keyboard). */
    private void scrollShortcutIntoView(Node row) {
        if (shortcutScroll == null) {
            return;
        }
        shortcutListBox.applyCss();
        shortcutListBox.layout();
        double contentHeight = shortcutListBox.getHeight();
        double viewHeight = shortcutScroll.getViewportBounds().getHeight();
        if (contentHeight <= viewHeight) {
            return;
        }
        double range = contentHeight - viewHeight;
        double top = shortcutScroll.getVvalue() * range;
        javafx.geometry.Bounds b = row.getBoundsInParent();
        if (b.getMinY() < top) {
            shortcutScroll.setVvalue(b.getMinY() / range);
        } else if (b.getMaxY() > top + viewHeight) {
            shortcutScroll.setVvalue((b.getMaxY() - viewHeight) / range);
        }
    }

    /** Commits a recorded chord sequence to a command, warning first if it steals another command's chord. */
    private void commitRecording(String commandId, String sequence) {
        recordingCommandId = null;
        rebindWithConflictCheck(commandId, sequence);
        refreshShortcuts(commandId);
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
        List<com.editora.command.KeybindingEdits.Conflict> conflicts = shortcutActions.conflicts(seq, commandId);
        if (!conflicts.isEmpty()) {
            StringBuilder affected = new StringBuilder();
            for (var c : conflicts) {
                affected.append("\n   ").append(c.chord()).append("  —  ").append(titleOf(c.commandId()));
            }
            Alert confirm = Dialogs.styled(new Alert(
                    Alert.AlertType.CONFIRMATION,
                    tr("dialog.shortcut.conflict.body", seq, affected.toString()),
                    ButtonType.OK,
                    ButtonType.CANCEL));
            confirm.initOwner(stage);
            confirm.setTitle(tr("dialog.shortcut.conflict.title"));
            confirm.setHeaderText(null);
            confirm.getDialogPane().setMinWidth(460);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return false;
            }
        }
        shortcutActions.rebind(commandId, seq);
        return true;
    }

    /** The display title of a command id (from the shortcut rows), falling back to the id itself. */
    private String titleOf(String commandId) {
        return shortcutActions.rows().stream()
                .filter(r -> r.id().equals(commandId))
                .map(Shortcut::title)
                .findFirst()
                .orElse(commandId);
    }

    /** Injects the cross-window macro re-register hook (→ {@code MainController}); used by the Macros page. */
    public void setMacrosChangedHandler(Runnable handler) {
        this.onMacrosChanged = handler == null ? () -> {} : handler;
    }

    /** Refreshes this window's toolbar selector after the separate editor persists a change. */
    public void setRunConfigsChangedHandler(Runnable handler) {
        this.onRunConfigsChanged = handler == null ? () -> {} : handler;
    }

    /** Re-reads the Macros page's list from the store — called after a macro changes from outside this
     *  window (e.g. a recording auto-saves "unnamed macro" on F4), so an already-open Settings window
     *  reflects it live instead of only on the next time the page is built. Harmless if never opened —
     *  {@link #macroItems} is a plain field, not lazily created with the page. */
    public void refreshMacrosList() {
        macroItems.setAll(config.getMacroStore().macros);
    }

    private VBox macrosPage() {
        VBox p = page(tr("settings.cat.macros"));
        Card mainCard = card(p, null);
        cardRow(
                mainCard,
                Category.MACROS,
                macrosEditor(),
                "macros keyboard record replay steps rename delete keybinding command text");
        Label note = note(tr("settings.macro.note"));
        note.setWrapText(true);
        note.setMaxWidth(460);
        cardRow(mainCard, Category.MACROS, note, "macros record f3 f4 replay keybinding save");
        return p;
    }

    /** Master-detail editor for saved keyboard macros: list on the left, a name/keybinding/steps form on the right. */
    private javafx.scene.Node macrosEditor() {
        macroItems.setAll(config.getMacroStore().macros);

        ListView<com.editora.macro.Macro> list = new ListView<>(macroItems);
        list.setPrefSize(220, 420);
        Label noMacros = note(tr("settings.macro.empty"));
        noMacros.setWrapText(true);
        noMacros.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        noMacros.setMaxWidth(190);
        list.setPlaceholder(noMacros);
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
        nameReorderButtons(stepUp, stepDown);
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
        // Wraps: the five German buttons are wider than the form at the window's minimum width.
        WrapRow stepButtons = new WrapRow(6, 6, addCmd, addText, new HBox(6, stepUp, stepDown), stepRemove);

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
        // No macro selected, no steps to edit: Add Command used to add a step that belonged to nothing.
        stepEditor.disableProperty().bind(form.disabledProperty());
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
                refreshShortcuts();
            });
            record.setOnAction(e -> startMacroCapture(keybinding, cmdId, m, steps));
            keybinding.getChildren().addAll(chordLbl, record, clear);
        };
        macroKeybindingRebuilders.put(keybinding, rebuildKeybinding);
        // The row shows a chord of the live keymap, like the Keymaps list: a keymap switch or a rebind made
        // elsewhere changes it. Left alone while the user is recording a chord in it.
        refreshMacroKeybinding = () -> {
            boolean recording = !keybinding.getChildren().isEmpty()
                    && keybinding.getChildren().get(0) instanceof TextField;
            if (!recording) {
                rebuildKeybinding.accept(list.getSelectionModel().getSelectedItem());
            }
        };

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
        save.getStyleClass().add("success");
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
        keepWidth(left);
        VBox.setVgrow(list, Priority.ALWAYS);

        if (!macroItems.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        HBox box = new HBox(12, left, right);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** Re-reads the Macros page's key-binding row from the live keymap (set once that page is built). */
    private Runnable refreshMacroKeybinding;

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
        Runnable done = () -> {
            rebuildKeybindingFor(keybinding, m, steps);
            refreshShortcuts(); // the Keymaps list and the chord chips show this binding too
        };
        java.util.function.Consumer<String> commit = seq -> {
            rebindWithConflictCheck(commandId, seq);
            done.run();
        };
        TextField capture = ShortcutCapture.field(commit, done);
        Button save = new Button(tr("settings.shortcuts.save"));
        save.getStyleClass().add("success");
        save.setOnAction(e -> commit.accept(capture.getText()));
        Button cancel = new Button(tr("settings.shortcuts.cancel"));
        cancel.setOnAction(e -> done.run());
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

    private void macroWarn(String message) {
        Alert a = Dialogs.styled(new Alert(Alert.AlertType.WARNING, message, ButtonType.OK));
        a.initOwner(stage);
        a.setHeaderText(null);
        a.showAndWait();
    }

    /** True when {@code name} would take a {@code macro.run.<slug>} id another saved macro already uses. */
    private static boolean macroSlugTaken(com.editora.config.MacroStore store, String name) {
        String id = com.editora.macro.MacroService.commandIdFor(name);
        for (com.editora.macro.Macro m : store.macros) {
            if (!m.name().equals(name)
                    && com.editora.macro.MacroService.commandIdFor(m.name()).equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void saveMacro(ListView<com.editora.macro.Macro> list, String rawName) {
        String newName = rawName == null ? "" : rawName.trim();
        if (newName.isEmpty() || macroOriginalName == null) {
            return;
        }
        com.editora.config.MacroStore store = config.getMacroStore();
        boolean renamed = !macroOriginalName.equals(newName);
        if (renamed && store.find(newName) != null) {
            macroWarn(tr("settings.macro.nameExists", newName));
            return;
        }
        // Distinct names can slug to one macro.run.<id> ("my macro" / "my-macro"; any symbol-only name ->
        // "macro"). The store keys by name but commands key by slug, so the second registration silently
        // shadowed the first — the older macro became unreachable by command or keybinding.
        String oldId = com.editora.macro.MacroService.commandIdFor(macroOriginalName);
        String newId = com.editora.macro.MacroService.commandIdFor(newName);
        if (renamed && !newId.equals(oldId) && macroSlugTaken(store, newName)) {
            macroWarn(tr("settings.macro.idExists", newName));
            return;
        }
        String oldChord = renamed ? currentChordFor(oldId) : null;
        com.editora.macro.Macro updated =
                new com.editora.macro.Macro(newName, new java.util.ArrayList<>(macroStepItems));
        if (renamed) {
            store.remove(macroOriginalName);
        }
        store.put(updated);
        config.saveMacros();
        onMacrosChanged.run(); // re-register macro.run.* (incl. the renamed id) in every window
        // Carry the keybinding across only when the command id actually changed. A rename that keeps the
        // slug ("build" -> "Build") leaves oldId == newId, and resetting after rebinding stripped the chord
        // we had just re-added — a macro.run.* id has no base default to fall back to, so it went unbound.
        if (renamed && !newId.equals(oldId) && oldChord != null && !oldChord.isBlank() && shortcutActions != null) {
            shortcutActions.reset(oldId); // drop the old id's override BEFORE binding the new one
            shortcutActions.rebind(newId, oldChord);
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
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("settings.macro.deleteConfirm", sel.name()),
                ButtonType.OK,
                ButtonType.CANCEL));
        confirm.initOwner(stage);
        confirm.setTitle(tr("settings.macro.deleteConfirmTitle"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
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
        Card display = card(p, tr("settings.section.display"));
        checkRow(display, Category.EDITOR, columnRulerCheck, null, "80 column ruler guide margin");
        checkRow(display, Category.EDITOR, lineHighlightCheck, null, "highlight current line caret");
        checkRow(display, Category.EDITOR, lineNumbersCheck, null, "line numbers gutter");
        checkRow(display, Category.EDITOR, minimapCheck, null, "minimap overview");
        checkRow(
                display,
                Category.EDITOR,
                stickyScrollCheck,
                null,
                "sticky scroll pinned scope headers class method context");
        checkRow(
                display,
                Category.EDITOR,
                symbolIndexCheck,
                null,
                "symbol index go to symbol project declarations without language server");
        checkRow(
                display,
                Category.EDITOR,
                bracketColorsCheck,
                null,
                "bracket pair colorization colors nesting depth rainbow brackets parentheses braces vs code");
        checkRow(display, Category.EDITOR, wordWrapCheck, null, "word wrap soft wrap long lines");
        checkRow(display, Category.EDITOR, whitespaceCheck, null, "hidden characters whitespace spaces tabs eol");
        checkRow(
                display,
                Category.EDITOR,
                noteIndicatorsCheck,
                null,
                "personal notes gutter marker highlight indicators");
        checkRow(
                display,
                Category.EDITOR,
                multiCaretCheck,
                null,
                "multiple cursors carets column box selection alt drag vs code");
        checkRow(
                display,
                Category.EDITOR,
                copyLineNoSelectionCheck,
                null,
                "copy cut whole current line empty no selection clipboard vs code");
        checkRow(
                display,
                Category.EDITOR,
                copyWithHighlightingCheck,
                null,
                "copy syntax highlighting html colors paste slack document rich text vs code");
        checkRow(
                display,
                Category.EDITOR,
                autoRenameTagCheck,
                null,
                "auto rename tag html xml paired close mirror vs code");
        checkRow(
                display,
                Category.EDITOR,
                autoFillCheck,
                null,
                "auto fill mode break wrap prose lines fill column emacs");
        checkRow(
                display,
                Category.EDITOR,
                autoCloseTagsCheck,
                null,
                "auto close tags html xml closing tag insert body vs code");
        Card indent = card(p, tr("settings.section.indentation"));
        controlRow(
                indent,
                Category.EDITOR,
                tr("settings.indentStyle"),
                null,
                indentStyleCombo,
                "indent style tabs spaces detect auto width");
        controlRow(
                indent, Category.EDITOR, tr("settings.tabSize"), null, tabSizeSpinner, "tab size indent width spaces");
        controlRow(
                indent,
                Category.EDITOR,
                tr("settings.fillColumn"),
                null,
                fillColumnSpinner,
                "fill column wrap paragraph reflow emacs m-q width");
        checkRow(
                indent,
                Category.EDITOR,
                editorConfigCheck,
                null,
                "editorconfig indent style size charset end of line trailing whitespace final newline");
        Card performance = card(p, tr("settings.section.performance"));
        HBox largeFileBox = new HBox(8, largeFileThresholdSpinner, note(tr("settings.largeFileThreshold.unit")));
        largeFileBox.setAlignment(Pos.CENTER_LEFT);
        controlRow(
                performance,
                Category.EDITOR,
                tr("settings.largeFileThreshold"),
                null,
                largeFileBox,
                "large file performance minimap lsp lines threshold responsive huge source");
        Card logs = card(p, tr("settings.section.logs"));
        checkRow(
                logs,
                Category.EDITOR,
                logViewerCheck,
                null,
                "log viewer server logs tail follow level highlighting filter apache spring boot");
        Card csv = card(p, tr("settings.section.csv"));
        checkRow(csv, Category.EDITOR, csvGridCheck, null, "csv tsv grid table preview spreadsheet columns");
        checkRow(csv, Category.EDITOR, csvRainbowCheck, null, "csv tsv rainbow column colors coloring highlight");
        Card previews = card(p, tr("settings.section.previews"));
        checkRow(
                previews,
                Category.EDITOR,
                structuredPreviewCheck,
                null,
                "json yaml toml openapi swagger tree structured data preview");
        checkRow(previews, Category.EDITOR, svgPreviewCheck, null, "svg image vector graphic preview render");
        checkRow(previews, Category.EDITOR, crontabPreviewCheck, null, "crontab cron schedule preview next run times");
        checkRow(previews, Category.EDITOR, fstabPreviewCheck, null, "fstab mount filesystem preview options decode");
        checkRow(
                previews,
                Category.EDITOR,
                systemdPreviewCheck,
                null,
                "systemd unit service timer preview oncalendar schedule decode");
        checkRow(previews, Category.EDITOR, sshConfigPreviewCheck, null, "ssh config host connection preview decode");
        checkRow(
                previews,
                Category.EDITOR,
                dockerfilePreviewCheck,
                null,
                "dockerfile docker image build stage preview digest");
        checkRow(
                previews,
                Category.EDITOR,
                githubActionsPreviewCheck,
                null,
                "github actions workflow ci yaml preview jobs triggers");
        checkRow(
                previews,
                Category.EDITOR,
                pomPreviewCheck,
                null,
                "maven pom xml preview dependencies plugins properties versions summary");
        Card saving = card(p, tr("settings.section.saving"));
        Label delayLabel = note(tr("settings.autoSave.delay"));
        delayLabel.setLabelFor(autoSaveDelaySpinner);
        HBox autoSaveBox = new HBox(8, autoSaveCombo, autoSaveDelaySpinner, delayLabel);
        autoSaveBox.setAlignment(Pos.CENTER_LEFT);
        controlRow(
                saving,
                Category.EDITOR,
                tr("settings.autoSave"),
                null,
                autoSaveBox,
                "auto save autosave delay inactivity focus");
        checkRow(
                saving,
                Category.EDITOR,
                adminSaveCheck,
                null,
                "save administrator root sudo pkexec permission etc linux");
        Label adminSaveNote = note(tr("settings.adminSave.note"));
        adminSaveNote.setWrapText(true);
        adminSaveNote.setMaxWidth(440);
        cardRow(saving, Category.EDITOR, adminSaveNote, "save administrator root permission linux pkexec");
        Card pdf = card(p, tr("settings.section.pdf"));
        checkRow(pdf, Category.EDITOR, pdfLineNumbersCheck, null, "pdf export line numbers gutter");
        checkRow(pdf, Category.EDITOR, pdfHighlightCheck, null, "pdf export syntax highlighting colors");
        controlRow(
                pdf,
                Category.EDITOR,
                tr("settings.pdf.pageSize"),
                null,
                pdfPageSizeCombo,
                "pdf export page size letter a4 paper");
        return p;
    }

    /** The Markdown settings page: editing (format bar), preview/PDF (math), and linting (enable + per-rule). */
    private Region markdownPage() {
        VBox p = page(tr("settings.cat.markdown"));
        Card editing = card(p, tr("settings.section.markdownEditing"));
        checkRow(
                editing,
                Category.MARKDOWN,
                markdownFormatBarCheck,
                null,
                "markdown format bar selection bold italic toolbar floating");
        Card preview = card(p, tr("settings.section.markdownPreview"));
        checkRow(
                preview,
                Category.MARKDOWN,
                mathSupportCheck,
                null,
                "markdown math latex katex formula equation dollar");
        Card lint = card(p, tr("settings.section.markdownLint"));
        checkRow(lint, Category.MARKDOWN, markdownLintCheck, null, "markdown lint linting warnings squiggles rules");
        cardRow(
                lint,
                Category.MARKDOWN,
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
            cb.setAccessibleText(code + " " + desc.getText()); // its visible name is a graphic, not text
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
        enabled.setAccessibleText(tr("settings.todo.enabledTip") + " — " + p.getName());
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
        remove.setAccessibleText(tr("settings.todo.removeTip") + " — " + p.getName());

        Runnable commit = () -> {
            java.util.List<com.editora.todo.TodoPattern> cur = mutableTodoPatterns();
            if (index >= cur.size()) {
                return;
            }
            com.editora.todo.TodoPattern up = cur.get(index);
            up.setEnabled(enabled.isSelected());
            up.setName(name.getText());
            // A malformed expression is skipped by TodoPatterns.compile, so saving it silently switched the
            // keyword off. Flag the field and keep the stored pattern until the expression is valid.
            String problem = com.editora.todo.TodoPatterns.syntaxProblem(regex.getText());
            regex.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_DANGER, problem != null);
            regex.setTooltip(problem == null ? null : new Tooltip(tr("settings.todo.regexInvalid", problem)));
            if (problem == null) {
                up.setPattern(regex.getText());
            }
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
        Card main = card(p, null);
        checkRow(main, Category.SPELL_CHECK, spellCheckBox, null, "spell check spelling enable");
        controlRow(
                main,
                Category.SPELL_CHECK,
                tr("settings.language"),
                null,
                spellLanguageCombo,
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
        cardRow(
                main,
                Category.SPELL_CHECK,
                dictLinks,
                "dictionary open technical personal file dictionary.txt bundled terms");

        Card dict = card(p, tr("settings.dict.title"));
        checkRow(
                dict,
                Category.SPELL_CHECK,
                techDictEnableCheck,
                null,
                "technical dictionary terms programming code config async kubernetes enable on off");
        checkRow(
                dict,
                Category.SPELL_CHECK,
                dictEnableCheck,
                tr("settings.dict.note"),
                "personal dictionary enable on off honor words dictionary.txt file location global");
        cardRow(dict, Category.SPELL_CHECK, dictionaryEditor(), "personal dictionary words add remove custom ignore");
        return p;
    }

    /** A simple editor for the global personal dictionary ({@code dictionary.txt}): list + add/remove. */
    private javafx.scene.Node dictionaryEditor() {
        dictionaryList = new ListView<>();
        dictionaryList.setPrefSize(300, 380);
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
                apply(); // live-apply like every other control here: re-runs the spell overlays' caches
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
                apply(); // live-apply: the removed word must start being flagged again right away
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
            quietly(() -> dictEnableCheck.setSelected(config.getSettings().isPersonalDictionary()));
        }
    }

    /** Re-syncs the "Enable technical dictionary" checkbox after a palette toggle. */
    public void syncTechnicalDictionaryCheck() {
        if (techDictEnableCheck != null) {
            quietly(() -> techDictEnableCheck.setSelected(config.getSettings().isTechnicalDictionary()));
        }
    }

    /** Runs a control re-sync without its listeners treating it as a user edit (no write, no save, no apply). */
    private void quietly(Runnable sync) {
        boolean prev = loading;
        loading = true;
        try {
            sync.run();
        } finally {
            loading = prev;
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
        Card chrome = card(p, tr("settings.section.chrome"));
        checkRow(chrome, Category.INTERFACE, menuBarCheck, null, "menu bar menubar chrome");
        checkRow(chrome, Category.INTERFACE, toolbarCheck, null, "toolbar buttons chrome");
        checkRow(chrome, Category.INTERFACE, statusBarCheck, null, "status bar chrome");
        checkRow(chrome, Category.INTERFACE, tabBarCheck, null, "tab bar tabs chrome");
        checkRow(chrome, Category.INTERFACE, breadcrumbCheck, null, "breadcrumb file path chrome");
        checkRow(
                chrome,
                Category.INTERFACE,
                toolStripeCheck,
                tr("settings.toolWindows.stripeNote"),
                "tool stripe tool windows buttons show hide precedence");
        checkRow(
                chrome,
                Category.INTERFACE,
                extendedWindowCheck,
                tr("settings.extendedWindow.note"),
                "title bar menu bar merge extended window header chrome row experimental linux");
        Card pickers = card(p, tr("settings.section.pickers"));
        checkRow(
                pickers,
                Category.INTERFACE,
                paletteSearchEverywhereCheck,
                tr("settings.paletteSearchEverywhere.note"),
                "command palette search everywhere m-x picker replace commands files symbols");
        Card modes = card(p, tr("settings.section.modes"));
        checkRow(
                modes,
                Category.INTERFACE,
                simpleModeCheck,
                tr("settings.simpleMode.note"),
                "simple minimal ui mode chrome distraction");
        checkRow(modes, Category.INTERFACE, zenCheck, null, "zen distraction free focus mode");
        checkRow(modes, Category.INTERFACE, expertCheck, null, "expert focus mode keeps line numbers status bar");
        return p;
    }

    /** GENERAL ▸ Workspace: project + personal-notes features and local file history. */
    private VBox workspacePage() {
        VBox p = page(tr("settings.cat.workspace"));
        Card features = card(p, tr("settings.section.features"));
        // The projects toggle keeps its "ⓘ" detail tooltip beside the switch.
        Label projectsInfo = new Label("ⓘ");
        projectsInfo.getStyleClass().add("info-badge");
        Tooltip projectsTip = new Tooltip(tr("settings.projects.tip"));
        projectsTip.setWrapText(true);
        projectsTip.setMaxWidth(380);
        Tooltip.install(projectsInfo, projectsTip);
        cardRow(
                features,
                Category.WORKSPACE,
                settingRow(projectsCheck.getText(), null, new HBox(8, projectsInfo, switchFor(projectsCheck))),
                "projects workspace folder");
        checkRow(features, Category.WORKSPACE, projectHiddenCheck, null, "project tree hidden dot files folders show");
        checkRow(features, Category.WORKSPACE, notesCheck, null, "personal notes annotations enable feature");
        Card history = card(p, tr("settings.section.localHistory"));
        checkRow(
                history,
                Category.WORKSPACE,
                localHistoryCheck,
                tr("settings.history.note"),
                "local file history snapshot version revision restore undo");
        controlRow(
                history,
                Category.WORKSPACE,
                tr("settings.history.maxPerFile"),
                null,
                historyMaxPerFileSpinner,
                "local history max revisions per file limit retention");
        controlRow(
                history,
                Category.WORKSPACE,
                tr("settings.history.maxAgeDays"),
                null,
                historyMaxAgeSpinner,
                "local history max age days retention prune");
        controlRow(
                history,
                Category.WORKSPACE,
                tr("settings.history.maxTotalMb"),
                null,
                historyMaxTotalSpinner,
                "local history max total size megabytes project budget");
        Card updates = card(p, tr("settings.section.updates"));
        checkRow(
                updates,
                Category.WORKSPACE,
                updateCheckCheck,
                tr("settings.updates.note"),
                "check for updates new version github release startup network privacy");

        Card trust = card(p, tr("settings.section.trustedFolders"));
        Label trustNote = note(tr("settings.trustedFolders.note"));
        trustNote.setWrapText(true);
        trustNote.setMaxWidth(440);
        cardRow(trust, Category.WORKSPACE, trustNote, "workspace trust build wrapper mvnw gradlew security");
        trustedFoldersList.setPrefHeight(120);
        trustedFoldersList.setPlaceholder(new Label(tr("settings.trustedFolders.empty")));
        Button revoke = new Button(tr("settings.trustedFolders.revoke"));
        Button revokeAll = new Button(tr("settings.trustedFolders.revokeAll"));
        revoke.disableProperty()
                .bind(trustedFoldersList
                        .getSelectionModel()
                        .selectedItemProperty()
                        .isNull());
        revoke.setOnAction(e -> {
            String sel = trustedFoldersList.getSelectionModel().getSelectedItem();
            if (sel != null && trustActions != null) {
                trustActions.revoke(sel);
                refreshTrustedFolders();
            }
        });
        revokeAll.setOnAction(e -> {
            if (trustActions != null) {
                trustActions.revokeAll();
                refreshTrustedFolders();
            }
        });
        revokeAll.disableProperty().bind(Bindings.isEmpty(trustedFoldersList.getItems()));
        cardRow(trust, Category.WORKSPACE, trustedFoldersList, "workspace trust trusted folders list revoke");
        cardRow(
                trust,
                Category.WORKSPACE,
                new HBox(8, revoke, revokeAll),
                "workspace trust revoke remove trusted folder");
        return p;
    }

    /** EDITOR ▸ Code Completion: the autocomplete master + per-source sub-toggles, quick-doc, semantic tokens. */
    private VBox completionPage() {
        VBox p = page(tr("settings.cat.completion"));
        Card auto = card(p, tr("settings.section.completion"));
        checkRow(
                auto, Category.COMPLETION, autocompleteCheck, null, "autocomplete completion suggestions enable popup");
        // The three sub-sources follow their master directly; their disable state says the rest.
        checkRow(
                auto,
                Category.COMPLETION,
                autocompleteProseCheck,
                null,
                "autocomplete prose words dictionary ghost text spelling");
        checkRow(auto, Category.COMPLETION, autocompleteSnippetsCheck, null, "autocomplete snippets popup templates");
        checkRow(
                auto,
                Category.COMPLETION,
                autocompleteMermaidCheck,
                null,
                "autocomplete mermaid diagram keywords snippets mmd");
        checkRow(
                auto,
                Category.COMPLETION,
                completionDocCheck,
                null,
                "completion documentation quick doc popup javadoc ctrl q");
        Card lsp = card(p, null);
        checkRow(
                lsp,
                Category.COMPLETION,
                semanticHighlightCheck,
                tr("settings.semanticHighlight.lspNote"),
                "semantic highlighting lsp tokens types parameters fields deprecated");
        checkRow(
                lsp,
                Category.COMPLETION,
                inlayHintsCheck,
                null,
                "inlay hints parameter names inferred types lsp annotations");
        inlayHintsCheck.disableProperty().bind(lspCheck.selectedProperty().not());
        checkRow(
                lsp,
                Category.COMPLETION,
                codeLensCheck,
                tr("settings.codeLens.note"),
                "code lens vision references usages implementations count lsp");
        codeLensCheck.disableProperty().bind(lspCheck.selectedProperty().not());
        controlRow(
                lsp,
                Category.COMPLETION,
                tr("settings.inlayHintMode"),
                tr("settings.inlayHintMode.note"),
                inlayHintModeCombo,
                "inlay hints filter parameter names literals noise suppress lsp");
        // Sub-setting of the checkbox above (master/sub pattern): meaningless while hints are off.
        inlayHintModeCombo
                .disableProperty()
                .bind(lspCheck.selectedProperty()
                        .not()
                        .or(inlayHintsCheck.selectedProperty().not()));
        checkRow(
                lsp,
                Category.COMPLETION,
                onTypeFormattingCheck,
                null,
                "on-type formatting reindent semicolon brace enter lsp");
        checkRow(lsp, Category.COMPLETION, pasteImportsCheck, null, "paste import auto imports java jdtls clipboard");
        checkRow(
                lsp,
                Category.COMPLETION,
                smartSemicolonCheck,
                null,
                "smart semicolon detection statement end java jdtls");
        onTypeFormattingCheck.disableProperty().bind(lspCheck.selectedProperty().not());
        semanticHighlightCheck
                .disableProperty()
                .bind(lspCheck.selectedProperty().not());
        return p;
    }

    /** EDITOR ▸ TODO: in-editor TODO/FIXME highlighting + the per-pattern editor. */
    private VBox todoPage() {
        VBox p = page(tr("settings.cat.todo"));
        Card todoHl = card(p, tr("settings.section.todo"));
        checkRow(
                todoHl,
                Category.TODO,
                todoHighlightCheck,
                tr("settings.todoSyntax"),
                "todo fixme highlight patterns tags comments annotations");
        cardRow(todoHl, Category.TODO, todoPatternsEditor(), "todo fixme pattern regex color add remove edit");
        Card partColors = card(p, tr("settings.section.todoPartColors"));
        cardRow(
                partColors,
                Category.TODO,
                todoPartColorsEditor(),
                "todo tag priority critical high medium low part color");
        return p;
    }

    /** The five per-part color pickers ({@code [tag]} + the four {@code (priority)} levels) for a structured
     *  TODO comment; each writes its {@code Settings} field and applies live. */
    private javafx.scene.Node todoPartColorsEditor() {
        Settings s = config.getSettings();
        todoTagColorPicker =
                partColorPicker(s.getTodoTagColor(), c -> config.getSettings().setTodoTagColor(c));
        todoCriticalColorPicker = partColorPicker(
                s.getTodoPriorityCriticalColor(), c -> config.getSettings().setTodoPriorityCriticalColor(c));
        todoHighColorPicker = partColorPicker(
                s.getTodoPriorityHighColor(), c -> config.getSettings().setTodoPriorityHighColor(c));
        todoMediumColorPicker = partColorPicker(
                s.getTodoPriorityMediumColor(), c -> config.getSettings().setTodoPriorityMediumColor(c));
        todoLowColorPicker = partColorPicker(
                s.getTodoPriorityLowColor(), c -> config.getSettings().setTodoPriorityLowColor(c));
        // A GridPane, not a row of HBoxes: the labels differ in width ("Tag" vs "Critical priority") and in
        // every locale, so a per-row minimum width leaves the pickers ragged the moment one label outgrows
        // it. A shared first column sizes itself to the widest label, whatever that turns out to be.
        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        addPartColorRow(grid, 0, tr("settings.todo.part.tag"), todoTagColorPicker);
        addPartColorRow(grid, 1, tr("settings.todo.part.critical"), todoCriticalColorPicker);
        addPartColorRow(grid, 2, tr("settings.todo.part.high"), todoHighColorPicker);
        addPartColorRow(grid, 3, tr("settings.todo.part.medium"), todoMediumColorPicker);
        addPartColorRow(grid, 4, tr("settings.todo.part.low"), todoLowColorPicker);
        return grid;
    }

    private javafx.scene.control.ColorPicker partColorPicker(String web, java.util.function.Consumer<String> setter) {
        javafx.scene.control.ColorPicker cp = new javafx.scene.control.ColorPicker(parseColor(web));
        cp.setPrefWidth(56);
        cp.valueProperty().addListener((o, a, b) -> {
            setter.accept(toHex(b));
            apply();
        });
        return cp;
    }

    private static void addPartColorRow(
            javafx.scene.layout.GridPane grid, int row, String label, javafx.scene.control.ColorPicker picker) {
        Label l = new Label(label);
        grid.add(l, 0, row);
        grid.add(picker, 1, row);
        javafx.scene.layout.GridPane.setValignment(l, javafx.geometry.VPos.CENTER);
    }

    /** Re-reads the five TODO part-color pickers from settings (after a palette color change). */
    public void syncTodoPartColors() {
        Settings s = config.getSettings();
        quietly(() -> {
            setPicker(todoTagColorPicker, s.getTodoTagColor());
            setPicker(todoCriticalColorPicker, s.getTodoPriorityCriticalColor());
            setPicker(todoHighColorPicker, s.getTodoPriorityHighColor());
            setPicker(todoMediumColorPicker, s.getTodoPriorityMediumColor());
            setPicker(todoLowColorPicker, s.getTodoPriorityLowColor());
        });
    }

    private static void setPicker(javafx.scene.control.ColorPicker picker, String web) {
        if (picker != null) {
            picker.setValue(parseColor(web));
        }
    }

    /** LANGUAGES & TOOLS ▸ Web: HTML live preview + the built-in HTTP client (merged). */
    private VBox webPage() {
        VBox p = page(tr("settings.cat.web"));
        Card preview = card(p, tr("settings.section.htmlPreview"));
        checkRow(
                preview,
                Category.WEB,
                htmlPreviewCheck,
                tr("settings.htmlPreview.hint"),
                "html live preview browser serve enable safari chrome firefox edge server localhost");
        Card http = card(p, tr("settings.section.httpClient"));
        checkRow(
                http,
                Category.WEB,
                httpCheck,
                tr("settings.httpClient.hint"),
                "http client rest request enable run send response built-in");
        return p;
    }

    /** Reference implementation of the UI Kit card page — see {@link #card}/{@link #settingRow}. The
     *  cards are untitled (the kit's are too): the page heading already says "Git", and each row names
     *  itself. */
    private VBox gitPage() {
        VBox p = page(tr("settings.cat.git"), tr("settings.git.subtitle"));

        Card c = card(p, null);
        gitStatusLabel = new Label(tr("settings.git.checking"));
        gitStatusLabel.getStyleClass().add("settings-git-status");
        gitStatusLabel.setWrapText(true);
        gitStatusLabel.setMaxWidth(340);
        cardRow(
                c,
                Category.GIT,
                settingRow(tr("settings.enableGit"), tr("settings.git.hint"), switchFor(gitCheck)),
                "git version control vcs enable");
        Button gitBrowse = browseButton(tr("settings.git.command"), gitPathField);
        gitPathField.setPrefWidth(PATH_FIELD_WIDTH);
        cardRow(
                c,
                Category.GIT,
                settingRow(
                        tr("settings.git.command"),
                        tr("settings.git.command.desc"),
                        new HBox(6, gitPathField, gitBrowse)),
                "git command path executable browse");
        cardRow(
                c,
                Category.GIT,
                settingRow(tr("settings.git.detected"), null, gitStatusLabel),
                "git command found version installed not found");

        Card c2 = card(p, null);
        cardRow(
                c2,
                Category.GIT,
                settingRow(
                        tr("settings.git.blameInline"),
                        tr("settings.git.blameInline.desc"),
                        new HBox(8, chordChip("git.toggleBlame"), switchFor(blameCheck))),
                "git blame annotate inline author history line");
        cardRow(
                c2,
                Category.GIT,
                settingRow(tr("settings.git.pullMode"), tr("settings.git.pullMode.desc"), gitPullModeCombo),
                "git pull mode fast-forward ff-only rebase merge diverged");
        Label autoFetchUnit = new Label(tr("settings.git.autoFetchMinutes"));
        autoFetchUnit.setLabelFor(gitAutoFetchSpinner);
        cardRow(
                c2,
                Category.GIT,
                settingRow(
                        tr("settings.git.autoFetch"),
                        tr("settings.git.autoFetch.desc"),
                        new HBox(8, gitAutoFetchSpinner, autoFetchUnit, switchFor(gitAutoFetchCheck))),
                "git fetch automatically auto background interval minutes remote prune");

        row(p, Category.GIT, null, noteBox(tr("settings.liveNote")), "live apply ok palette command");
        return p;
    }

    private VBox githubPage() {
        VBox p = page(tr("settings.cat.github"));
        Card c = card(p, null);
        githubStatusLabel = new Label(tr("settings.github.checking"));
        githubStatusLabel.getStyleClass().add("settings-git-status");
        githubStatusLabel.setWrapText(true);
        githubStatusLabel.setMaxWidth(340);
        checkRow(c, Category.GITHUB, githubCheck, tr("settings.github.hint"), "github gh pull request pr enable");
        Button ghBrowse = browseButton(tr("settings.github.ghPath"), ghPathField);
        ghPathField.setPrefWidth(PATH_FIELD_WIDTH);
        controlRow(
                c,
                Category.GITHUB,
                tr("settings.github.ghPath"),
                null,
                new HBox(6, ghPathField, ghBrowse),
                "github gh path executable command");
        controlRow(
                c,
                Category.GITHUB,
                tr("settings.git.detected"),
                null,
                githubStatusLabel,
                "github gh cli found installed authenticated not found");
        refreshGithubStatus();
        return p;
    }

    private VBox searchPage() {
        VBox p = page(tr("settings.cat.search"));
        Card c = card(p, null);
        ripgrepStatusLabel = new Label(tr("settings.search.checking"));
        ripgrepStatusLabel.getStyleClass().add("settings-git-status");
        ripgrepStatusLabel.setWrapText(true);
        ripgrepStatusLabel.setMaxWidth(340);
        checkRow(c, Category.SEARCH, ripgrepCheck, tr("settings.search.hint"), "search ripgrep rg find in files fast");
        Button rgBrowse = browseButton(tr("settings.search.ripgrepPath"), ripgrepCommandField);
        ripgrepCommandField.setPrefWidth(PATH_FIELD_WIDTH);
        controlRow(
                c,
                Category.SEARCH,
                tr("settings.search.ripgrepPath"),
                null,
                new HBox(6, ripgrepCommandField, rgBrowse),
                "search ripgrep rg path executable command");
        controlRow(
                c,
                Category.SEARCH,
                tr("settings.git.detected"),
                null,
                ripgrepStatusLabel,
                "search ripgrep rg found installed not found");
        Card c2 = card(p, tr("settings.search.exclusions"));
        checkRow(
                c2,
                Category.SEARCH,
                searchGitignoreCheck,
                null,
                "search exclude gitignore ignored target node_modules build dist folders files");
        return p;
    }

    private VBox mermaidPage() {
        VBox p = page(tr("settings.cat.mermaid"));
        Card c = card(p, null);
        mermaidStatusLabel = new Label(tr("settings.mermaid.checking"));
        mermaidStatusLabel.getStyleClass().add("settings-git-status");
        mermaidStatusLabel.setWrapText(true);
        mermaidStatusLabel.setMaxWidth(340);
        checkRow(
                c,
                Category.MERMAID,
                mermaidCheck,
                tr("settings.mermaid.hint"),
                "mermaid diagram enable mmdc render mmd");
        mmdcPathField.setPrefWidth(PATH_FIELD_WIDTH);
        controlRow(
                c,
                Category.MERMAID,
                tr("settings.mermaid.mmdcPath"),
                null,
                new HBox(6, mmdcPathField, browseButton(tr("settings.mermaid.mmdcPath"), mmdcPathField)),
                "mermaid mmdc path executable render");
        maidPathField.setPrefWidth(PATH_FIELD_WIDTH);
        controlRow(
                c,
                Category.MERMAID,
                tr("settings.mermaid.maidPath"),
                null,
                new HBox(6, maidPathField, browseButton(tr("settings.mermaid.maidPath"), maidPathField)),
                "mermaid maid path executable lint validate");
        controlRow(
                c,
                Category.MERMAID,
                tr("settings.git.detected"),
                null,
                new HBox(8, mermaidStatusLabel, installButton("mermaid")),
                "mermaid mmdc maid found installed not found install download cli");
        return p;
    }

    /** LANGUAGES & TOOLS ▸ Diagrams: Graphviz DOT + PlantUML preview (external CLIs). */
    private VBox diagramsPage() {
        VBox p = page(tr("settings.cat.diagrams"));
        Card c = card(p, null);
        diagramStatusLabel = new Label(tr("settings.diagram.checking"));
        diagramStatusLabel.getStyleClass().add("settings-git-status");
        diagramStatusLabel.setWrapText(true);
        diagramStatusLabel.setMaxWidth(340);
        checkRow(
                c,
                Category.DIAGRAMS,
                diagramCheck,
                tr("settings.diagram.hint"),
                "diagram dot graphviz plantuml enable render preview puml gv");
        dotPathField.setPrefWidth(PATH_FIELD_WIDTH);
        controlRow(
                c,
                Category.DIAGRAMS,
                tr("settings.diagram.dotPath"),
                null,
                new HBox(6, dotPathField, browseButton(tr("settings.diagram.dotPath"), dotPathField)),
                "diagram dot graphviz path executable render");
        plantumlPathField.setPrefWidth(PATH_FIELD_WIDTH);
        controlRow(
                c,
                Category.DIAGRAMS,
                tr("settings.diagram.plantumlPath"),
                null,
                new HBox(6, plantumlPathField, browseButton(tr("settings.diagram.plantumlPath"), plantumlPathField)),
                "diagram plantuml puml path executable render");
        controlRow(
                c,
                Category.DIAGRAMS,
                tr("settings.git.detected"),
                null,
                diagramStatusLabel,
                "diagram dot graphviz plantuml found installed not found");
        return p;
    }

    private void refreshDiagramStatus() {
        if (diagramStatusLabel == null || diagramService == null) {
            return;
        }
        diagramStatusLabel.getStyleClass().setAll("settings-git-status");
        diagramStatusLabel.setText(tr("settings.diagram.checking"));
        diagramService.detect(a -> {
            boolean dot = a.has(com.editora.diagram.DiagramKind.DOT);
            boolean puml = a.has(com.editora.diagram.DiagramKind.PLANTUML);
            String dotState = dot ? tr("settings.diagram.found") : tr("settings.diagram.notFound");
            String pumlState = puml ? tr("settings.diagram.found") : tr("settings.diagram.notFound");
            // Green only when both tools are found, red when either is missing (like the Mermaid/LSP rows).
            diagramStatusLabel
                    .getStyleClass()
                    .setAll("settings-git-status", dot && puml ? "settings-git-found" : "settings-git-missing");
            diagramStatusLabel.setText(tr("settings.diagram.status", dotState, pumlState));
        });
    }

    /** LANGUAGES & TOOLS ▸ Typst: multi-page rendered document preview (the external typst CLI). */
    private VBox typstPage() {
        VBox p = page(tr("settings.cat.typst"));
        Card c = card(p, null);
        typstStatusLabel = new Label(tr("settings.typst.checking"));
        typstStatusLabel.getStyleClass().add("settings-git-status");
        typstStatusLabel.setWrapText(true);
        typstStatusLabel.setMaxWidth(340);
        checkRow(
                c,
                Category.TYPST,
                typstCheck,
                tr("settings.typst.hint"),
                "typst document enable render preview typ pdf");
        typstPathField.setPrefWidth(PATH_FIELD_WIDTH);
        controlRow(
                c,
                Category.TYPST,
                tr("settings.typst.path"),
                null,
                new HBox(6, typstPathField, browseButton(tr("settings.typst.path"), typstPathField)),
                "typst path executable render document");
        controlRow(
                c,
                Category.TYPST,
                tr("settings.git.detected"),
                null,
                new HBox(8, typstStatusLabel, installButton("typst")),
                "typst document found installed not found install download cli");
        return p;
    }

    private void refreshTypstStatus() {
        if (typstStatusLabel == null || typstService == null) {
            return;
        }
        typstStatusLabel.getStyleClass().setAll("settings-git-status");
        typstStatusLabel.setText(tr("settings.typst.checking"));
        typstService.detect(present -> {
            typstStatusLabel
                    .getStyleClass()
                    .setAll("settings-git-status", present ? "settings-git-found" : "settings-git-missing");
            typstStatusLabel.setText(present ? tr("settings.typst.found") : tr("settings.typst.notFound"));
            updateInstallButton("typst", present); // "Installed" + disabled when the typst CLI is found
        });
    }

    private VBox buildToolsPage() {
        VBox p = page(tr("settings.cat.buildTools"));
        Card testCard = card(p, tr("settings.section.testRunner"));
        checkRow(
                testCard,
                Category.BUILD_TOOLS,
                testRunnerCheck,
                tr("settings.testRunner.hint"),
                "test results runner junit surefire tree pass fail rerun intellij");
        Card javaCard = card(p, "Java");
        cardRow(
                javaCard,
                Category.BUILD_TOOLS,
                settingRow(tr("settings.maven.jdk"), tr("settings.maven.jdk.hint"), mavenJdkCombo),
                "java jdk default compact source file maven sdkman toolchain run debug");
        for (BuildTool bt : BuildTool.enabled()) {
            Card c = card(p, bt.displayName());
            String kw = bt.id() + " build tool project detected enable command override toolbar";
            checkRow(c, Category.BUILD_TOOLS, buildToolChecks.get(bt), tr("settings." + bt.id() + ".hint"), kw);
            TextField field = buildToolCommandFields.get(bt);
            field.setPrefWidth(PATH_FIELD_WIDTH);
            controlRow(
                    c,
                    Category.BUILD_TOOLS,
                    tr("settings.buildTools.commandOverride"),
                    null,
                    new HBox(6, field, browseButton(tr("settings.buildTools.commandOverride"), field)),
                    kw + " path executable wrapper");
            controlRow(c, Category.BUILD_TOOLS, tr("settings.git.detected"), null, buildToolStatusLabels.get(bt), kw);
            if (bt == BuildTool.MAVEN) {
                mavenArchetypeCatalogField.setPrefWidth(320);
                cardRow(
                        c,
                        Category.BUILD_TOOLS,
                        settingRow(
                                tr("settings.maven.archetypeCatalog"),
                                tr("settings.maven.archetypeCatalog.hint"),
                                mavenArchetypeCatalogField),
                        kw + " archetype catalog url new project wizard");
            }
        }
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
        Card mainCard = card(p, null);
        cardRow(mainCard, Category.SNIPPETS, snippetsEditor(), "snippets user prefix trigger expansion tab stops body");
        Label help = note(tr("settings.snippet.help"));
        help.setWrapText(true);
        help.setMaxWidth(460);
        cardRow(mainCard, Category.SNIPPETS, help, "snippets help tab stop placeholder variable");
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
        list.setPrefSize(220, 420);
        VBox.setVgrow(list, Priority.ALWAYS); // grow the list to fill the page height
        list.setCellFactory(lv -> new ListCell<>() {
            {
                // A ListCell reports its graphic's intrinsic width as its preferred width, so a name
                // plus the "bundled" tag made the list demand more than its viewport and grow a
                // horizontal scrollbar — which then stole the height the last row needed, producing a
                // vertical one too. Asking for nothing lets the row fit the viewport and the name
                // ellipsize instead.
                setPrefWidth(0);
            }

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
                    tag.setMinWidth(Region.USE_PREF_SIZE); // the name gives way, not the tag ("bund…")
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
        CodeArea body = AreaUndo.bounded(new CodeArea());
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
        Label problem = note("");
        problem.setWrapText(true);
        problem.setVisible(false);
        problem.setManaged(false);

        // The form's texts now, and as they were when the selected row was loaded (or last committed).
        java.util.function.Supplier<java.util.List<String>> snippetFormText =
                () -> java.util.Arrays.asList(name.getText(), prefix.getText(), description.getText(), body.getText());
        java.util.concurrent.atomic.AtomicReference<java.util.List<String>> snippetFormLoaded =
                new java.util.concurrent.atomic.AtomicReference<>(snippetFormText.get());
        Runnable commit = () -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i < 0 || loadingSnippet || snippetFileProblem != null) {
                return;
            }
            // "Nothing was edited" is judged against what the form was loaded with, not against the model:
            // a single-line field drops the line breaks of a bundled multi-line description, so a rebuilt
            // snippet never equalled the original and a mere focus loss wrote a user override.
            if (snippetFormText.get().equals(snippetFormLoaded.get())) {
                return; // a field merely lost focus — never rewrite the file for that
            }
            com.editora.snippet.Snippet cur = snippetItems.get(i);
            String newName = name.getText().trim();
            if (newName.isEmpty()) {
                // The file is keyed by name and the save skips a blank one: committing it deleted the
                // snippet. Keep the name it has; the other fields still save.
                newName = cur.name();
                name.setText(newName);
                if (newName == null || newName.isBlank()) {
                    return;
                }
            }
            if (!newName.equals(cur.name())) {
                for (com.editora.snippet.Snippet other : snippetItems) {
                    if (other != cur && newName.equals(other.name())) {
                        // Two rows with one name collapse to a single entry on disk — refuse, as the
                        // External Tools page does for a colliding command id.
                        name.setText(cur.name());
                        macroWarn(tr("settings.snippet.nameExists", newName));
                        return;
                    }
                }
            }
            boolean descriptionEdited =
                    !description.getText().equals(snippetFormLoaded.get().get(2));
            com.editora.snippet.Snippet updated = new com.editora.snippet.Snippet(
                    newName,
                    prefix.getText().trim(),
                    body.getText(),
                    descriptionEdited ? description.getText().trim() : cur.description(),
                    currentSnippetLang);
            snippetFormLoaded.set(snippetFormText.get());
            if (updated.equals(cur)) {
                return; // only whitespace the commit trims away
            }
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
                form.setDisable(s == null || snippetFileProblem != null);
                name.setText(s == null ? "" : s.name());
                prefix.setText(s == null ? "" : s.prefix());
                description.setText(s == null ? "" : s.description());
                body.replaceText(s == null ? "" : s.body()); // CodeArea has no setText
                snippetFormLoaded.set(snippetFormText.get());
            } finally {
                loadingSnippet = false;
            }
        });

        Runnable loadLang = () -> {
            String v = language.getValue();
            currentSnippetLang = v == null || v.isBlank() ? "global" : v.trim();
            snippetFileProblem = snippetManager == null ? null : snippetManager.userFileProblem(currentSnippetLang);
            problem.setText(
                    snippetFileProblem == null
                            ? ""
                            : tr(
                                    "settings.snippet.unreadable",
                                    snippetManager.userFile(currentSnippetLang).getFileName(),
                                    snippetFileProblem));
            problem.setVisible(snippetFileProblem != null);
            problem.setManaged(snippetFileProblem != null);
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
        reloadSnippets = () -> { // another window's Settings may have rewritten this language's file
            com.editora.snippet.Snippet sel = list.getSelectionModel().getSelectedItem();
            loadLang.run();
            for (int k = 0; sel != null && k < snippetItems.size(); k++) {
                if (java.util.Objects.equals(sel.name(), snippetItems.get(k).name())) {
                    list.getSelectionModel().select(k);
                    break;
                }
            }
        };

        Button add = new Button(tr("settings.snippet.add"));
        add.setOnAction(e -> {
            if (snippetFileProblem != null) {
                return;
            }
            // The file is keyed by name: a second "New Snippet" would replace the first on disk.
            java.util.Set<String> taken = new java.util.HashSet<>();
            for (com.editora.snippet.Snippet other : snippetItems) {
                taken.add(other.name());
            }
            String newName = tr("settings.snippet.newName");
            for (int n = 2; taken.contains(newName); n++) {
                newName = tr("settings.snippet.newName") + " " + n;
            }
            com.editora.snippet.Snippet s = new com.editora.snippet.Snippet(newName, "", "", "", currentSnippetLang);
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
        // The picker shares the list's width: boxed with a 130px label column it was squeezed to an arrow.
        Label languageLabel = new Label(tr("settings.snippet.language"));
        languageLabel.setLabelFor(language);
        language.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(language, Priority.ALWAYS);
        HBox languageRow = new HBox(10, languageLabel, language);
        languageRow.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(6, languageRow, list, buttons);
        keepWidth(languageLabel, add, remove, left);
        VBox.setVgrow(left, Priority.ALWAYS);

        // Explicit Save (edits also auto-save on Enter / focus-loss, so nothing is lost on row switch).
        Button save = new Button(tr("settings.save"));
        save.getStyleClass().add("success");
        save.setDefaultButton(false);
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit.run());
        HBox saveRow = new HBox(save);
        saveRow.setAlignment(Pos.CENTER_RIGHT);
        VBox right = new VBox(8, problem, form, saveRow);
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
        if (snippetManager == null || snippetFileProblem != null) {
            return; // never write back over a file that could not be parsed
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
            Dialogs.styled(new Alert(
                            Alert.AlertType.ERROR, tr("settings.snippet.saveFailed", e.getMessage()), ButtonType.OK))
                    .showAndWait();
        }
    }

    private VBox templatesPage() {
        VBox p = page(tr("settings.cat.templates"));
        Card author = card(p, tr("settings.section.templates"));
        controlRow(
                author,
                Category.TEMPLATES,
                tr("settings.authorName"),
                null,
                templateAuthorField,
                "author name file templates new from template variable");
        Card list = card(p, tr("settings.section.templatesList"));
        cardRow(list, Category.TEMPLATES, templatesEditor(), "templates file new from template scaffold bundled");
        Label help = note(tr("settings.template.help"));
        help.setWrapText(true);
        help.setMaxWidth(460);
        cardRow(list, Category.TEMPLATES, help, "templates help variable cursor placeholder");
        return p;
    }

    /** Master-detail editor: the templates (bundled + user) on the left, a form for the selected one. */
    private javafx.scene.Node templatesEditor() {
        ListView<com.editora.template.Template> list = new ListView<>(templateItems);
        list.setPrefSize(250, 420); // "Java Compact Source" + the bundled tag
        VBox.setVgrow(list, Priority.ALWAYS); // grow the list to fill the page height
        list.setCellFactory(lv -> new ListCell<>() {
            {
                // A ListCell reports its graphic's intrinsic width as its preferred width, so a name
                // plus the "bundled" tag made the list demand more than its viewport and grow a
                // horizontal scrollbar — which then stole the height the last row needed, producing a
                // vertical one too. Asking for nothing lets the row fit the viewport and the name
                // ellipsize instead.
                setPrefWidth(0);
            }

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
                    tag.setMinWidth(Region.USE_PREF_SIZE); // the name gives way, not the tag ("bund…")
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
        CodeArea body = AreaUndo.bounded(new CodeArea());
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

        // The form's texts now, and as they were when the selected row was loaded (or last committed).
        java.util.function.Supplier<java.util.List<String>> templateFormText = () -> java.util.Arrays.asList(
                id.getText(),
                name.getText(),
                description.getText(),
                language.getText(),
                fileName.getText(),
                body.getText());
        java.util.concurrent.atomic.AtomicReference<java.util.List<String>> templateFormLoaded =
                new java.util.concurrent.atomic.AtomicReference<>(templateFormText.get());
        Runnable commit = () -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i < 0 || loadingTemplate) {
                return;
            }
            com.editora.template.Template cur = templateItems.get(i);
            if (cur.isMultiFile() || id.getText().trim().isEmpty()) {
                return; // multi-file templates are read-only here; an id is required
            }
            if (templateFormText.get().equals(templateFormLoaded.get())) {
                return; // a field merely lost focus — a bundled template must not become a user override for that
            }
            String newId = id.getText().trim();
            if (!newId.equals(cur.id())) {
                // The id is the file stem: one that is not a plain file name would write outside the
                // templates folder, and one another row uses would overwrite that template's file.
                String problem = null;
                if (!com.editora.template.TemplateRegistry.isValidId(newId)) {
                    problem = tr("settings.template.idInvalid", newId);
                } else {
                    for (com.editora.template.Template other : templateItems) {
                        if (other != cur && newId.equals(other.id())) {
                            problem = tr("settings.template.idExists", newId);
                            break;
                        }
                    }
                }
                if (problem != null) {
                    id.setText(cur.id()); // put the field back
                    macroWarn(problem);
                    return;
                }
            }
            // A field the user did not touch keeps the template's own value: the single-line description field
            // shows a multi-line bundled description flattened, and saving that back would flatten the file.
            java.util.List<String> loaded = templateFormLoaded.get();
            java.util.List<String> typed = templateFormText.get();
            java.util.function.BiFunction<Integer, String, String> value =
                    (k, original) -> typed.get(k).equals(loaded.get(k)) && original != null
                            ? original
                            : typed.get(k).trim();
            templateFormLoaded.set(typed);
            com.editora.template.Template updated = new com.editora.template.Template(
                    newId,
                    value.apply(1, cur.name()),
                    value.apply(2, cur.description()),
                    value.apply(3, cur.language()),
                    value.apply(4, cur.fileName()),
                    body.getText(),
                    null);
            if (updated.equals(new com.editora.template.Template(
                    cur.id(), cur.name(), cur.description(), cur.language(), cur.fileName(), cur.body(), null))) {
                return; // only whitespace the commit trims away: not an edit, so not a user override either
            }
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
                templateFormLoaded.set(templateFormText.get());
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

        reloadTemplates = () -> { // another window's Settings may have added or removed a template
            com.editora.template.Template sel = list.getSelectionModel().getSelectedItem();
            loadTemplates.run();
            for (int k = 0; sel != null && k < templateItems.size(); k++) {
                if (java.util.Objects.equals(sel.id(), templateItems.get(k).id())) {
                    list.getSelectionModel().select(k);
                    break;
                }
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
                Dialogs.styled(new Alert(
                                Alert.AlertType.ERROR,
                                tr("settings.template.saveFailed", ex.getMessage()),
                                ButtonType.OK))
                        .showAndWait();
                return;
            }
            templateUserIds.remove(t.id());
            templateItems.remove(t);
            loadTemplates.run(); // re-derive: a removed override reverts to its bundled template
        });
        HBox buttons = new HBox(6, add, remove);
        VBox left = new VBox(6, list, buttons);
        keepWidth(add, remove, left);
        VBox.setVgrow(left, Priority.ALWAYS);

        // Explicit Save (edits also auto-save on Enter / focus-loss); disabled for a read-only row.
        Button save = new Button(tr("settings.save"));
        save.getStyleClass().add("success");
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
            Dialogs.styled(new Alert(
                            Alert.AlertType.ERROR, tr("settings.template.saveFailed", e.getMessage()), ButtonType.OK))
                    .showAndWait();
        }
    }

    private VBox remotePage() {
        VBox p = page(tr("settings.cat.remote"));
        Card mainCard = card(p, null);
        cardRow(
                mainCard,
                Category.REMOTE,
                remoteConnectionsEditor(),
                "remote sftp ssh connection host user key password saved site server");
        Label note = note(tr("settings.remote.note"));
        note.setWrapText(true);
        note.setMaxWidth(460);
        cardRow(mainCard, Category.REMOTE, note, "remote secret password passphrase not stored security");
        return p;
    }

    /** Master-detail editor for saved SFTP sites: a list on the left, a form for the selected site on the right. */
    private javafx.scene.Node remoteConnectionsEditor() {
        remoteItems.setAll(config.getConnections());

        ListView<com.editora.vfs.RemoteConnection> list = new ListView<>(remoteItems);
        remoteList = list;
        list.setPrefSize(210, 380);
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
        keyPath.setMinWidth(160);
        Button keyBrowse = new Button(tr("dialog.clone.browse"));
        keyBrowse.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(tr("remote.keyPrompt"));
            java.io.File f = fc.showOpenDialog(keyBrowse.getScene().getWindow());
            if (f != null) {
                keyPath.setText(f.getAbsolutePath());
            }
        });
        // Wraps: beside a wide (German) Browse button the field was left about 60px in a narrow window. The
        // button now moves below the field instead, which then has the row to itself.
        WrapRow keyRow = new WrapRow(6, 6, WrapRow.setGrow(keyPath), keyBrowse);
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
        remoteFormEdit = () -> {
            com.editora.vfs.RemoteConnection cur = list.getSelectionModel().getSelectedItem();
            return cur == null
                    ? PendingFormEdit.none()
                    : PendingFormEdit.capture(
                            List.of(label, host, port, user, keyPath),
                            List.of(
                                    nullToEmpty(cur.label()),
                                    nullToEmpty(cur.host()),
                                    String.valueOf(cur.port()),
                                    nullToEmpty(cur.user()),
                                    nullToEmpty(cur.keyPath())));
        };

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
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                remoteItems.remove(i);
                persistRemote();
            }
        });
        HBox buttons = new HBox(6, add, remove);
        VBox left = new VBox(6, list, buttons);
        keepWidth(add, remove, left, keyBrowse);

        // Explicit Save (edits also auto-save on Enter / focus-loss, so nothing is lost on row switch).
        Button save = new Button(tr("settings.save"));
        save.getStyleClass().add("success");
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

    /** Re-reads the saved sites (Connect remembers one; the Remote Sites panel removes one), keeping the selection. */
    private void reloadRemote() {
        if (remoteItems.equals(config.getConnections())) {
            return;
        }
        var selected =
                remoteList == null ? null : remoteList.getSelectionModel().getSelectedItem();
        String selectedId = selected == null ? null : selected.id();
        PendingFormEdit typing = remoteFormEdit.get(); // before the items change: that reloads the form
        remoteItems.setAll(config.getConnections());
        if (remoteList != null && selectedId != null) {
            for (var c : remoteItems) {
                if (selectedId.equals(c.id())) {
                    remoteList.getSelectionModel().select(c);
                    typing.restore(); // the site being edited is still there: what was typed stays typed
                    break;
                }
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Opens Settings focused on the Remote page (the {@code remote.settings} command). */
    public void showRemote(Window owner) {
        show(owner);
        sidebar.getSelectionModel().select(Category.REMOTE);
    }

    private VBox externalToolsPage() {
        VBox p = page(tr("settings.cat.externalTools"));
        Card mainCard = card(p, null);
        cardRow(
                mainCard,
                Category.EXTERNAL_TOOLS,
                externalToolsEditor(),
                "external tools command cli macro stdin output console run formatter filter");
        Label macros = note(tr("settings.externalTool.macrosHelp"));
        macros.setWrapText(true);
        macros.setMaxWidth(460);
        cardRow(mainCard, Category.EXTERNAL_TOOLS, macros, "external tools macros filepath selection linenumber");
        return p;
    }

    /** Master-detail editor: a list of tools on the left, a form for the selected tool on the right. */
    private javafx.scene.Node externalToolsEditor() {
        reloadExternalTools();

        ListView<com.editora.externaltool.ExternalTool> list = new ListView<>(externalToolItems);
        externalToolList = list;
        list.setPrefSize(200, 380);
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
            // Distinct names can produce one externalTool.run.<slug> command ("Format JSON" / "format-json";
            // any two symbol-only names both fall back to "tool"). CommandRegistry.register is a put, so the
            // second tool silently shadowed the first: the palette showed one entry and a keybinding on it
            // always ran the later tool. MacroService refuses the same collision — mirror it.
            if (slugTaken(t, name.getText())) {
                macroWarn(tr("settings.externalTool.idExists", name.getText()));
                name.setText(t.getName()); // put the field back
                return;
            }
            // The command id is derived from the name, so a rename is a new command: carry the key binding
            // across (as saveMacro does) instead of leaving a chord bound to an id nothing registers.
            String oldId = com.editora.externaltool.ExternalTool.commandIdFor(t.getName());
            String newId = com.editora.externaltool.ExternalTool.commandIdFor(name.getText());
            String oldChord = newId.equals(oldId) ? null : currentChordFor(oldId);
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
            persistExternalTools(); // re-registers externalTool.run.* (incl. the renamed id)
            if (!newId.equals(oldId) && shortcutActions != null) {
                shortcutActions.reset(oldId); // drop the old id's override BEFORE binding the new one
                if (oldChord != null && !oldChord.isBlank()) {
                    shortcutActions.rebind(newId, oldChord);
                }
            }
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
            // Two tools named "New Tool" share one externalTool.run.<slug> command: only one would run, and
            // commit then refuses every edit to the other until it is renamed.
            String newName = tr("settings.externalTool.newName");
            for (int n = 2; slugTaken(null, newName); n++) {
                newName = tr("settings.externalTool.newName") + " " + n;
            }
            com.editora.externaltool.ExternalTool t = new com.editora.externaltool.ExternalTool(
                    newName,
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
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                String id = com.editora.externaltool.ExternalTool.commandIdFor(
                        externalToolItems.get(i).getName());
                externalToolItems.remove(i);
                persistExternalTools();
                if (shortcutActions != null) {
                    shortcutActions.reset(id); // drop its key binding, as deleting a macro does
                }
            }
        });
        // Explicit Save (edits also auto-save on Enter / focus-loss + combo/checkbox change).
        Button save = new Button(tr("settings.save"));
        save.getStyleClass().add("success");
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

    /** True when {@code newName} would give {@code tool} the command id another tool already uses. */
    private boolean slugTaken(com.editora.externaltool.ExternalTool tool, String newName) {
        String id = com.editora.externaltool.ExternalTool.commandIdFor(newName);
        for (com.editora.externaltool.ExternalTool other : externalToolItems) {
            if (other != tool
                    && com.editora.externaltool.ExternalTool.commandIdFor(other.getName())
                            .equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Deep-copies the persisted tools so the working list edits independently until persisted. */
    private VBox abbreviationsPage() {
        VBox p = page(tr("settings.cat.abbreviations"));
        Card mainCard = card(p, null);
        abbrevModeCheck = viewCheck(tr("settings.abbrevMode"), Settings::setAbbrevMode);
        abbrevModeCheck.setSelected(config.getSettings().isAbbrevMode());
        checkRow(
                mainCard,
                Category.ABBREVIATIONS,
                abbrevModeCheck,
                tr("settings.abbrev.note"),
                "abbrev abbreviation expand as you type automatic mode");
        cardRow(
                mainCard,
                Category.ABBREVIATIONS,
                abbreviationsEditor(),
                "abbrev abbreviation expand text replacement snippet shortcut dictionary emacs");
        return p;
    }

    /** Master-detail editor for the user abbreviation dictionary (abbrev → expansion). */
    private javafx.scene.Node abbreviationsEditor() {
        reloadAbbrevs();

        ListView<com.editora.config.Abbreviation> list = new ListView<>(abbrevItems);
        abbrevList = list;
        list.setPrefSize(200, 380);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.editora.config.Abbreviation a, boolean empty) {
                super.updateItem(a, empty);
                setText(
                        empty || a == null || a.getAbbreviation().isBlank()
                                ? (empty || a == null ? null : tr("settings.abbrev.unnamed"))
                                : a.getAbbreviation());
            }
        });

        TextField abbrev = new TextField();
        TextField expansion = new TextField();
        javafx.scene.layout.GridPane form = new javafx.scene.layout.GridPane();
        form.setHgap(8);
        form.setVgap(6);
        formRow(form, 0, tr("settings.abbrev.abbreviation"), abbrev);
        formRow(form, 1, tr("settings.abbrev.expansion"), expansion);
        form.setDisable(true);
        HBox.setHgrow(form, Priority.ALWAYS);

        Runnable commit = () -> {
            com.editora.config.Abbreviation a = list.getSelectionModel().getSelectedItem();
            if (a == null || loadingAbbrev) {
                return;
            }
            a.setAbbreviation(abbrev.getText());
            a.setExpansion(expansion.getText());
            list.refresh();
            persistAbbrevs();
        };
        java.util.function.Consumer<TextField> wire = tf -> {
            tf.setOnAction(e -> commit.run());
            tf.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    commit.run();
                }
            });
        };
        wire.accept(abbrev);
        wire.accept(expansion);
        abbrevFormEdit = () -> {
            com.editora.config.Abbreviation cur = list.getSelectionModel().getSelectedItem();
            return cur == null
                    ? PendingFormEdit.none()
                    : PendingFormEdit.capture(
                            List.of(abbrev, expansion), List.of(cur.getAbbreviation(), cur.getExpansion()));
        };

        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            loadingAbbrev = true;
            try {
                form.setDisable(now == null);
                abbrev.setText(now == null ? "" : now.getAbbreviation());
                expansion.setText(now == null ? "" : now.getExpansion());
            } finally {
                loadingAbbrev = false;
            }
        });

        Button add = new Button(tr("settings.abbrev.add"));
        add.setOnAction(e -> {
            com.editora.config.Abbreviation a = new com.editora.config.Abbreviation("", "");
            abbrevItems.add(a);
            persistAbbrevs();
            list.getSelectionModel().select(a);
            abbrev.requestFocus();
        });
        Button remove = new Button(tr("settings.abbrev.remove"));
        remove.disableProperty()
                .bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                abbrevItems.remove(i);
                persistAbbrevs();
            }
        });
        Button save = new Button(tr("settings.save"));
        save.getStyleClass().add("success");
        save.disableProperty().bind(form.disabledProperty());
        save.setOnAction(e -> commit.run());

        VBox left = new VBox(6, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        VBox right = new VBox(8, form);
        VBox.setVgrow(form, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        if (!abbrevItems.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        HBox top = new HBox(12, left, right);
        top.setAlignment(Pos.TOP_LEFT);
        VBox.setVgrow(top, Priority.ALWAYS);
        HBox buttons = new HBox(6, add, remove, spacer(), save);
        buttons.setAlignment(Pos.CENTER_LEFT);
        return new VBox(8, top, buttons);
    }

    private void persistAbbrevs() {
        config.setAbbreviations(new java.util.ArrayList<>(abbrevItems));
        config.saveAbbreviations();
        apply(); // buffers hold a copy of the table: push the new one to every window's open buffers
    }

    private void reloadAbbrevs() {
        if (sameAbbrevs(abbrevItems, config.getAbbreviations())) {
            return;
        }
        var selected =
                abbrevList == null ? null : abbrevList.getSelectionModel().getSelectedItem();
        String selectedKey = selected == null ? null : selected.getAbbreviation();
        PendingFormEdit typing = abbrevFormEdit.get(); // before the items change: that reloads the form
        abbrevItems.setAll(copyAbbrevs(config.getAbbreviations()));
        if (abbrevList != null && selectedKey != null) {
            for (var a : abbrevItems) {
                if (selectedKey.equals(a.getAbbreviation())) {
                    abbrevList.getSelectionModel().select(a);
                    typing.restore(); // the entry being edited is still there: what was typed stays typed
                    break;
                }
            }
        }
    }

    private static java.util.List<com.editora.config.Abbreviation> copyAbbrevs(
            java.util.List<com.editora.config.Abbreviation> src) {
        java.util.List<com.editora.config.Abbreviation> out = new java.util.ArrayList<>();
        for (com.editora.config.Abbreviation a : src) {
            out.add(new com.editora.config.Abbreviation(a.getAbbreviation(), a.getExpansion()));
        }
        return out;
    }

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
            r.setMinWidth(180);
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
        installFocusEscape(area);
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

    /**
     * Lets the keyboard leave a body editor. Tab types a tab there (a snippet body needs it), which made
     * the editor a trap: nothing moved focus on, so the Save button after it was out of reach. As in a
     * JavaFX {@code TextArea}: Shift+Tab goes back, Ctrl+Tab goes on (Ctrl+Shift+Tab back).
     */
    private static void installFocusEscape(CodeArea area) {
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            javafx.scene.TraversalDirection direction =
                    focusEscape(e.getCode(), e.isShiftDown(), e.isControlDown(), e.isAltDown() || e.isMetaDown());
            if (direction != null) {
                e.consume();
                area.requestFocusTraversal(direction);
            }
        });
    }

    /** Where a key takes the focus out of a body editor, or {@code null} when the editor keeps the key. Pure. */
    static javafx.scene.TraversalDirection focusEscape(
            javafx.scene.input.KeyCode code, boolean shift, boolean ctrl, boolean otherModifier) {
        if (code != javafx.scene.input.KeyCode.TAB || otherModifier || (!shift && !ctrl)) {
            return null;
        }
        return shift ? javafx.scene.TraversalDirection.PREVIOUS : javafx.scene.TraversalDirection.NEXT;
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
        Card c = card(p, null);
        checkRow(
                c,
                Category.MCP,
                mcpCheck,
                tr("settings.mcp.hint"),
                "mcp model context protocol agent server enable ai claude llm loopback token endpoint");
        return p;
    }

    /**
     * The AI group's landing page: the master AI kill switch. Off by default; when off, the AI Agent and
     * AI Actions pages' own enable checkboxes are disabled (their settings still exist but have no effect
     * — see {@link AgentCoordinator#isEnabled()} / {@link AiCoordinator#isEnabled()}), mirroring the
     * autocomplete master/sub-toggle pattern. Deliberately doesn't touch the separate MCP Server page —
     * that lets an *external* agent drive Editora, not Editora calling out to AI.
     */
    private VBox aiGeneralPage() {
        VBox p = page(tr("settings.cat.aiGeneral"));
        Card mainCard = card(p, null);
        checkRow(
                mainCard,
                Category.AI_GENERAL,
                aiMasterCheck,
                null,
                "ai enable disable master switch agent actions all");
        Label hint = note(tr("settings.ai.masterHint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        cardRow(mainCard, Category.AI_GENERAL, hint, "ai enable disable master switch agent actions all off default");
        return p;
    }

    /** Disables the AI Agent / AI Actions enable checkboxes while the master AI switch is off — they're
     *  only meaningful when it's on (mirrors {@code autocompleteCheck}'s sub-toggle disabling). */
    private void updateAiSubEnablement(boolean masterOn) {
        agentCheck.setDisable(!masterOn);
        aiCheck.setDisable(!masterOn);
    }

    private VBox agentPage() {
        VBox p = page(tr("settings.cat.agent"));
        Card mainCard = card(p, null);
        checkRow(mainCard, Category.AGENT, agentCheck, null, "ai agent acp claude code chat enable assistant");
        cardRow(
                mainCard,
                Category.AGENT,
                labeledRow(tr("settings.agent.client"), agentClientCombo),
                "ai agent acp active client switch gemini copilot codex qwen opencode claude");
        // One (untitled) card per agent client, mirroring the Language Servers page: the command row
        // names the client, so a title would just repeat it in every locale.
        for (AgentClientUi a : agentClientUis()) {
            Card c = card(p, null);
            Label status = new Label(tr("settings.agent.checking"));
            status.getStyleClass().add("settings-git-status");
            status.setWrapText(true);
            status.setMaxWidth(340);
            agentStatusLabels.put(a.id(), status);
            TextField field = agentCommandFields.get(a.id());
            field.setPrefWidth(PATH_FIELD_WIDTH);
            controlRow(
                    c,
                    Category.AGENT,
                    tr(a.commandLabelKey()),
                    null,
                    new HBox(6, field, browseButton(tr(a.commandLabelKey()), field)),
                    a.keywords());
            controlRow(c, Category.AGENT, tr("settings.git.detected"), null, status, a.keywords());
        }
        checkRow(
                mainCard,
                Category.AGENT,
                agentIncludeContextCheck,
                null,
                "ai agent acp context cursor line selection file attach prompt");
        Label localHint = note(tr("settings.agent.lmstudioHint"));
        localHint.setWrapText(true);
        localHint.setMaxWidth(440);
        cardRow(mainCard, Category.AGENT, localHint, "ai agent lm studio bionic local opencode model endpoint");
        Label codexHint = note(tr("settings.agent.codexHint"));
        codexHint.setWrapText(true);
        codexHint.setMaxWidth(440);
        cardRow(mainCard, Category.AGENT, codexHint, "agent codex acp install login setup");
        Label hint = note(tr("settings.agent.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        cardRow(mainCard, Category.AGENT, hint, "ai agent acp claude code install npm chat tool window");
        return p;
    }

    /** Injected by MainController: backs the AI Agent page's per-client PATH-detection rows + the active-
     *  client combo (which routes through the coordinator's single switch path). */
    void setAgentCoordinator(AgentCoordinator coordinator) {
        this.agentCoordinator = coordinator;
    }

    /** A per-agent-client Settings row (data-driven; mirrors LspServerUi but with no enable flag — exactly
     *  one ACP agent is active at a time, chosen by the combo, not per-client toggles). */
    private record AgentClientUi(
            String id,
            String displayName,
            String defaultCommand,
            String commandLabelKey,
            String keywords,
            java.util.function.Consumer<String> setCommand,
            java.util.function.Supplier<String> getCommand) {}

    private java.util.List<AgentClientUi> agentClientUis() {
        Settings s = config.getSettings();
        return java.util.List.of(
                new AgentClientUi(
                        "claude",
                        "Claude Code",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("claude"),
                        "settings.agent.command.claude",
                        "agent claude code acp command executable path",
                        s::setAgentCommand,
                        s::getAgentCommand),
                new AgentClientUi(
                        "gemini",
                        "Gemini CLI",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("gemini"),
                        "settings.agent.command.gemini",
                        "agent gemini cli google acp command executable path",
                        s::setGeminiAgentCommand,
                        s::getGeminiAgentCommand),
                new AgentClientUi(
                        "copilot",
                        "GitHub Copilot CLI",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("copilot"),
                        "settings.agent.command.copilot",
                        "agent copilot github cli acp command executable path",
                        s::setCopilotAgentCommand,
                        s::getCopilotAgentCommand),
                new AgentClientUi(
                        "codex",
                        "Codex CLI",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("codex"),
                        "settings.agent.command.codex",
                        "agent codex openai cli acp command executable path",
                        s::setCodexAgentCommand,
                        s::getCodexAgentCommand),
                new AgentClientUi(
                        "qwen",
                        "Qwen Code",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("qwen"),
                        "settings.agent.command.qwen",
                        "agent qwen code alibaba cli acp command executable path",
                        s::setQwenAgentCommand,
                        s::getQwenAgentCommand),
                new AgentClientUi(
                        "opencode",
                        "OpenCode",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("opencode"),
                        "settings.agent.command.opencode",
                        "agent opencode sst cli acp command executable path",
                        s::setOpencodeAgentCommand,
                        s::getOpencodeAgentCommand),
                new AgentClientUi(
                        "lmstudio",
                        "LM Studio / Bionic (OpenCode)",
                        com.editora.agent.AcpAgentRegistry.defaultCommandFor("lmstudio"),
                        "settings.agent.command.lmstudio",
                        "agent lm studio bionic local opencode acp command executable path",
                        s::setLmstudioAgentCommand,
                        s::getLmstudioAgentCommand));
    }

    /** Re-probes each ACP agent client's PATH availability (mirrors {@link #refreshLspStatus()}). */
    private void refreshAgentClientStatus() {
        if (agentCoordinator == null || agentStatusLabels.isEmpty()) {
            return;
        }
        for (AgentClientUi a : agentClientUis()) {
            Label status = agentStatusLabels.get(a.id());
            if (status == null) {
                continue;
            }
            status.getStyleClass().setAll("settings-git-status");
            status.setText(tr("settings.agent.checking"));
            agentCoordinator.detect(a.id(), found -> {
                status.getStyleClass()
                        .setAll("settings-git-status", found ? "settings-git-found" : "settings-git-missing");
                status.setText(found ? tr("settings.agent.found") : tr("settings.agent.notFound"));
            });
        }
    }

    /** Injected by MainController: runs a live AI connection check (green/red status on the AI page). */
    public void setAiConnectionProbe(
            java.util.function.Consumer<java.util.function.BiConsumer<Boolean, String>> probe) {
        this.aiConnectionProbe = probe;
    }

    /** Debounced connection re-check after a field edit (avoids a network call per keystroke). */
    private void scheduleAiStatus() {
        if (!loading) {
            aiStatusDebounce.playFromStart();
        }
    }

    /** Fires the live connection check now, showing a "checking…" state until the result lands. */
    private void refreshAiStatus() {
        if (aiStatusLabel == null || aiConnectionProbe == null) {
            return;
        }
        aiStatusLabel.getStyleClass().setAll("settings-git-status");
        aiStatusLabel.setText(tr("settings.ai.checking"));
        aiConnectionProbe.accept(this::syncAiStatus);
    }

    /** Colors the AI status label green/red (the Git/Mermaid/LSP idiom): connected vs the error message. */
    private void syncAiStatus(boolean ok, String message) {
        if (aiStatusLabel == null) {
            return;
        }
        aiStatusLabel.getStyleClass().setAll("settings-git-status", ok ? "settings-git-found" : "settings-git-missing");
        aiStatusLabel.setText(ok ? tr("settings.ai.connected") : tr("settings.ai.connectFailed", message));
    }

    private com.editora.ai.AiProvider selectedAiProvider() {
        return com.editora.ai.AiProvider.from(config.getSettings().getAiProvider());
    }

    /** Reload together: switching providers must not write the previous provider's fields back. */
    /** Stores what the four per-provider fields show under {@code provider} (the caller saves and applies). */
    private void commitAiFieldsFor(com.editora.ai.AiProvider provider) {
        Settings settings = config.getSettings();
        if (provider != com.editora.ai.AiProvider.CODEX) { // disabled for Codex, which owns its own login
            settings.setAiEndpointFor(provider, aiEndpointField.getText());
            settings.setApiKeyFor(provider, aiApiKeyField.getText());
            settings.setAiCompletionModelFor(provider, aiCompletionModelField.getText());
        }
        settings.setAiModelFor(provider, aiModelField.getText());
    }

    private void syncAiProviderFields() {
        boolean previous = loading;
        loading = true;
        try {
            var provider = selectedAiProvider();
            var settings = config.getSettings();
            aiModelField.setText(settings.getAiModelFor(provider));
            aiCompletionModelField.setText(settings.getAiCompletionModelFor(provider));
            aiEndpointField.setText(settings.getAiEndpointFor(provider));
            aiApiKeyField.setText(settings.getApiKeyFor(provider));
        } finally {
            loading = previous;
        }
        updateAiProviderControls();
    }

    /** Codex owns its login/transport; inline completion remains available for HTTP providers. */
    private void updateAiProviderControls() {
        var provider = com.editora.ai.AiProvider.from(aiProviderCombo.getValue());
        boolean codex = provider == com.editora.ai.AiProvider.CODEX;
        boolean anthropic = provider == com.editora.ai.AiProvider.ANTHROPIC;
        aiEndpointField.setDisable(codex);
        aiApiKeyField.setDisable(codex);
        aiInlineCheck.setDisable(codex);
        aiCompletionModelField.setDisable(codex);
        aiModelField.setPromptText(
                anthropic
                        ? AiCoordinator.DEFAULT_MODEL
                        : tr(codex ? "settings.ai.modelDefault" : "settings.ai.localModelPrompt"));
        aiCompletionModelField.setPromptText(
                anthropic
                        ? AiCoordinator.DEFAULT_COMPLETION_MODEL
                        : tr(
                                provider == com.editora.ai.AiProvider.LMSTUDIO
                                        ? "settings.ai.lmstudioCompletionPrompt"
                                        : codex ? "settings.ai.modelDefault" : "settings.ai.localModelPrompt"));
        aiApiKeyField.setPromptText(tr(anthropic ? "settings.ai.apiKeyPrompt" : "settings.ai.localApiKeyPrompt"));
        aiEndpointField.setPromptText(provider.defaultEndpoint());
    }

    private VBox aiPage() {
        VBox p = page(tr("settings.cat.ai"));
        Card mainCard = card(p, null);
        // A switch row like every other page's enable toggle; what it turns on stays in the (i) tooltip.
        HBox aiEnable = new HBox(8, infoIcon(tr("settings.ai.actionsTooltip")), switchFor(aiCheck));
        aiEnable.setAlignment(Pos.CENTER_RIGHT);
        cardRow(
                mainCard,
                Category.AI,
                settingRow(aiCheck.getText(), null, aiEnable),
                "ai actions anthropic claude commit message explain rewrite enable");
        aiStatusLabel = new Label(tr("settings.ai.statusUnknown"));
        aiStatusLabel.getStyleClass().add("settings-git-status");
        aiStatusLabel.setWrapText(true);
        aiStatusLabel.setMaxWidth(440);
        cardRow(mainCard, Category.AI, aiStatusLabel, "ai connection status test green red working reachable");
        cardRow(
                mainCard,
                Category.AI,
                labeledRow(tr("settings.ai.provider"), aiProviderCombo),
                "ai provider anthropic openai local lm studio bionic ollama vllm");
        cardRow(
                mainCard,
                Category.AI,
                labeledRow(tr("settings.ai.endpoint"), aiEndpointField),
                "ai endpoint url local server lm studio ollama port");
        Label providerNote = note(tr("settings.ai.providerNote"));
        providerNote.setWrapText(true);
        providerNote.setMaxWidth(440);
        cardRow(mainCard, Category.AI, providerNote, "ai provider local lm studio no api key endpoint");
        cardRow(
                mainCard,
                Category.AI,
                exePathRow(tr("settings.ai.model"), aiModelField),
                "ai model anthropic claude opus id");
        cardRow(
                mainCard,
                Category.AI,
                exePathRow(tr("settings.ai.apiKey"), aiApiKeyField),
                "ai api key anthropic token credential");
        Label keyNote = note(tr("settings.ai.apiKeyNote"));
        keyNote.setWrapText(true);
        keyNote.setMaxWidth(440);
        cardRow(mainCard, Category.AI, keyNote, "ai api key environment variable plain text security");
        checkRow(
                mainCard,
                Category.AI,
                aiInlineCheck,
                null,
                "ai inline ghost completion suggestion copilot autocomplete");
        cardRow(
                mainCard,
                Category.AI,
                exePathRow(tr("settings.ai.completionModel"), aiCompletionModelField),
                "ai inline completion model haiku fast latency");
        Label inlineNote = note(tr("settings.ai.inlineHint"));
        inlineNote.setWrapText(true);
        inlineNote.setMaxWidth(440);
        cardRow(mainCard, Category.AI, inlineNote, "ai inline ghost completion tab accept cost");
        Label codexHint = note(tr("settings.ai.codexHint"));
        codexHint.setWrapText(true);
        codexHint.setMaxWidth(440);
        cardRow(mainCard, Category.AI, codexHint, "ai codex login acp agent subscription setup");
        Label hint = note(tr("settings.ai.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(440);
        cardRow(mainCard, Category.AI, hint, "ai commit message explain rewrite selection anthropic streaming");
        return p;
    }

    private VBox pluginsPage() {
        VBox p = page(tr("settings.cat.plugins"));
        Card mainCard = card(p, null);
        Label warn = note(tr("settings.plugins.note"));
        warn.getStyleClass().add("settings-experimental");
        warn.setWrapText(true);
        warn.setMaxWidth(440);
        cardRow(mainCard, Category.PLUGINS, warn, "plugins extensions untrusted security warning");
        checkRow(mainCard, Category.PLUGINS, pluginCheck, null, "plugins extensions enable support");

        Label folderLabel = new Label(tr("settings.plugins.folder"));
        Label folderPath =
                new Label(pluginManager == null ? "" : config.getPluginsDir().toString());
        folderPath.getStyleClass().add("settings-hint");
        folderPath.setWrapText(true);
        folderPath.setMaxWidth(380);
        HBox folderRow = new HBox(6, folderLabel, folderPath);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        cardRow(mainCard, Category.PLUGINS, folderRow, "plugins folder directory location path");

        Button reload = new Button(tr("settings.plugins.reload"));
        reload.setOnAction(e -> {
            if (pluginManager != null) {
                pluginManager.discover();
                refreshPluginList();
            }
        });
        cardRow(mainCard, Category.PLUGINS, reload, "plugins reload rescan discover refresh");

        Label restart = note(tr("settings.plugins.restart"));
        restart.setWrapText(true);
        restart.setMaxWidth(440);
        cardRow(mainCard, Category.PLUGINS, restart, "plugins restart apply");

        // --- Marketplace: a curated GitHub-hosted registry + install-from-disk.
        Card market = card(p, tr("settings.plugins.marketplace"));
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
        commitOnEnterOrBlur(
                pluginRegistryField,
                () -> shownOrBlank(
                        pluginRegistryField,
                        config.getSettings().getPluginRegistryUrl(),
                        config.getSettings().getPluginRegistryUrlRaw()),
                now -> {
                    config.getSettings().setPluginRegistryUrl(now);
                    apply();
                    updateRegistryWarn();
                });
        Label regNote = note(tr("settings.plugins.registryNote"));
        regNote.setWrapText(true);
        regNote.setMaxWidth(440);
        VBox regBox = new VBox(4, pluginRegistryField, pluginRegistryWarn, regNote);
        controlRow(
                market,
                Category.PLUGINS,
                tr("settings.plugins.registryUrl"),
                null,
                regBox,
                "plugins registry url index marketplace github browse");
        checkRow(
                market,
                Category.PLUGINS,
                pluginRequireSigCheck,
                tr("settings.plugins.requireSignatureNote"),
                "plugins signature signed verify registry security trust");
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
        cardRow(market, Category.PLUGINS, marketButtons, "plugins browse install file zip marketplace registry");

        Card installed = card(p, tr("settings.plugins.installed"));
        pluginListBox = new VBox(8);
        cardRow(installed, Category.PLUGINS, pluginListBox, "plugins installed list enable disable");
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
        Alert confirm = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, body, ButtonType.OK, ButtonType.CANCEL));
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
        Card master = card(p, null);
        checkRow(
                master,
                Category.DEBUG,
                debugCheck,
                tr("settings.debug.note"),
                "debug dap breakpoint step variables enable install plugin vscode mason jdtls debugpy js-debug");
        // One card per adapter, titled with the language (Java has no per-adapter enable — it rides
        // the Java LSP server; see DebugCoordinator.debugEffectiveFor).
        for (DebugAdapterUi dbg : debugAdapterUis()) {
            Card c = card(p, tr(dbg.sectionKey()));
            CheckBox enable = debugEnableChecks.get(dbg.id());
            if (enable != null) {
                checkRow(c, Category.DEBUG, enable, null, dbg.keywords());
            }
            if ("java".equals(dbg.id())) {
                checkRow(
                        c,
                        Category.DEBUG,
                        debugProgramConsoleCheck,
                        tr("settings.debug.programConsole.hint"),
                        "debug java console input stdin standard input program terminal type");
            }
            Label status = new Label(tr("settings.debug.checking"));
            status.getStyleClass().add("settings-git-status");
            status.setWrapText(true);
            status.setMaxWidth(340);
            debugStatusLabels.put(dbg.id(), status);
            TextField field = debugCommandFields.get(dbg.id());
            field.setPrefWidth(PATH_FIELD_WIDTH);
            controlRow(
                    c,
                    Category.DEBUG,
                    tr(dbg.commandLabelKey()),
                    null,
                    new HBox(6, field, browseButton(tr(dbg.commandLabelKey()), field)),
                    dbg.keywords());
            controlRow(c, Category.DEBUG, tr("settings.git.detected"), null, status, dbg.keywords());
        }
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
        Card master = card(p, null);
        checkRow(
                master,
                Category.LSP,
                lspCheck,
                tr("settings.lsp.hint"),
                "lsp language server protocol enable java typescript python xml json bash diagnostics"
                        + " install jdtls pyright lemminx");
        checkRow(
                master, Category.LSP, lspInstallPromptsCheck, null, "lsp install banner prompt offer language support");
        // One card per server (UI Kit v1). Twenty-one servers × four controls was a ~84-row flat wall in
        // which it took real effort to see which status line belonged to which enable box. The card is
        // deliberately untitled: the server's own enable checkbox already names it in every locale, and a
        // derived title ("Enable Java (jdtls)" minus "Enable ") would not survive translation.
        for (LspServerUi srv : lspServerUis()) {
            Card c = card(p, null);
            cardRow(
                    c,
                    Category.LSP,
                    settingRow(tr(srv.enableLabelKey()), null, switchFor(lspEnableChecks.get(srv.id()))),
                    srv.keywords());
            Label status = new Label(tr("settings.lsp.checking"));
            status.getStyleClass().add("settings-git-status");
            status.setWrapText(true);
            status.setMaxWidth(440);
            lspStatusLabels.put(srv.id(), status);
            cardRow(c, Category.LSP, status, srv.keywords());
            String langKey = installLangForServer(srv.id());
            if (langKey != null) {
                cardRow(c, Category.LSP, installButton(langKey), srv.keywords() + " install download");
            } else if (com.editora.install.InstallCatalog.installableServerIds().contains(srv.id())) {
                cardRow(c, Category.LSP, installServerButton(srv.id()), srv.keywords() + " install download");
            }
            cardRow(
                    c,
                    Category.LSP,
                    exePathRow(tr(srv.commandLabelKey()), lspCommandFields.get(srv.id())),
                    srv.keywords());
        }
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
                        () -> config.getSettings().getCsharpLspCommand()),
                new LspServerUi(
                        "typst",
                        com.editora.lsp.LspServerRegistry.DEFAULT_TYPST_COMMAND,
                        "settings.lsp.enableTypst",
                        "settings.lsp.typstCommand",
                        "settings.lsp.typstStatus",
                        "lsp typst tinymist language server found installed not found command path executable",
                        v -> config.getSettings().setTypstLspEnabled(v),
                        () -> config.getSettings().isTypstLspEnabled(),
                        v -> config.getSettings().setTypstLspCommand(v),
                        () -> config.getSettings().getTypstLspCommand()),
                new LspServerUi(
                        "astro",
                        com.editora.lsp.LspServerRegistry.DEFAULT_ASTRO_COMMAND,
                        "settings.lsp.enableAstro",
                        "settings.lsp.astroCommand",
                        "settings.lsp.astroStatus",
                        "lsp astro astro-ls web components language server found installed command path",
                        v -> config.getSettings().setAstroLspEnabled(v),
                        () -> config.getSettings().isAstroLspEnabled(),
                        v -> config.getSettings().setAstroLspCommand(v),
                        () -> config.getSettings().getAstroLspCommand()),
                new LspServerUi(
                        com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID,
                        com.editora.lsp.LspServerRegistry.DEFAULT_MAVEN_POM_COMMAND,
                        "settings.lsp.enableMavenPom",
                        "settings.lsp.mavenPomCommand",
                        "settings.lsp.mavenPomStatus",
                        "lsp maven pom.xml lemminx dependency completion language server found command path",
                        v -> config.getSettings().setMavenPomLspEnabled(v),
                        () -> config.getSettings().isMavenPomLspEnabled(),
                        v -> config.getSettings().setMavenPomLspCommand(v),
                        () -> config.getSettings().getMavenPomLspCommand()));
    }

    /** A "[label] [path field] [Browse…]" row for picking a CLI executable. */
    /** A simple "label: control" row (no Browse button), used for the AI provider combo + endpoint URL. */
    private HBox labeledRow(String label, javafx.scene.control.Control control) {
        HBox.setHgrow(control, Priority.ALWAYS);
        HBox box = new HBox(6, new Label(label), control);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** The Browse… half of {@link #exePathRow}, for card rows whose title already names the field. */
    private static final Object COMMIT_KEY = new Object();

    /**
     * Wires a text field to take effect on Enter or when focus leaves it (or the window closes), and only if
     * the text differs from the stored value. Applying per keystroke reconfigured the language servers / debug
     * adapters with every half-typed prefix: the running server was shut down on the first key, and a prefix
     * that happened to resolve was launched and killed by the next. For the other fields it saved the settings
     * file, re-applied every window and probed a half-typed executable or URL on each key.
     */
    private void commitOnEnterOrBlur(
            TextField field, java.util.function.Supplier<String> stored, Consumer<String> commit) {
        Runnable run = () -> {
            String text = field.getText() == null ? "" : field.getText();
            String current = stored.get();
            if (!loading && !text.equals(current == null ? "" : current)) {
                commit.accept(text);
            }
        };
        commitFields.add(field);
        field.setOnAction(e -> run.run());
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                run.run();
            }
        });
        field.getProperties().put(COMMIT_KEY, run);
    }

    /** Every {@link #commitOnEnterOrBlur} field, so closing the window can commit the one being typed in. */
    private final List<TextField> commitFields = new ArrayList<>();

    /**
     * Commits whatever is typed but not yet committed. A field commits when focus leaves it, and closing the
     * window (Esc, the title bar, the owner closing) is not a focus change the toolkit reliably reports first.
     */
    private void commitPendingFields() {
        List.copyOf(commitFields).forEach(SettingsWindow::commitNow);
    }

    /**
     * The stored value to compare a URL field against. Such a field shows the URL in force, so an untouched
     * field equals {@code resolved}; once the user empties it to go back to the built-in URL it must be
     * compared with what is stored ({@code raw}), or every later focus loss would save and apply again.
     */
    private static String shownOrBlank(TextField field, String resolved, String raw) {
        String text = field.getText();
        return text == null || text.isBlank() ? raw : resolved;
    }

    /** Commits a {@link #commitOnEnterOrBlur} field whose text was just set for the user (Browse…). */
    private static void commitNow(TextField field) {
        if (field.getProperties().get(COMMIT_KEY) instanceof Runnable run) {
            run.run();
        }
    }

    private Button browseButton(String title, TextField field) {
        Button browse = new Button(tr("settings.mermaid.browse"));
        browse.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(title);
            java.io.File f = fc.showOpenDialog(stage);
            if (f != null) {
                field.setText(f.getAbsolutePath());
                commitNow(field);
            }
        });
        keepWidth(browse);
        return browse;
    }

    /**
     * Pins each control's minimum width to its preferred one, so a narrow window cannot squeeze it: a
     * Labeled's own minimum is the width of an ellipsis, which is how buttons ended up reading "…".
     */
    /** Names a ▲/▼ pair for a screen reader (and a tooltip): on their own they announce as the glyphs. */
    private static void nameReorderButtons(Button up, Button down) {
        up.setAccessibleText(tr("bookmarks.moveUp"));
        up.setTooltip(new Tooltip(tr("bookmarks.moveUp")));
        down.setAccessibleText(tr("bookmarks.moveDown"));
        down.setTooltip(new Tooltip(tr("bookmarks.moveDown")));
    }

    private static void keepWidth(Region... controls) {
        for (Region control : controls) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }
    }

    private HBox exePathRow(String label, TextField field) {
        Button browse = new Button(tr("settings.mermaid.browse"));
        browse.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle(label);
            java.io.File f = fc.showOpenDialog(stage);
            if (f != null) {
                field.setText(f.getAbsolutePath());
                commitNow(field);
            }
        });
        HBox.setHgrow(field, Priority.ALWAYS);
        Label name = new Label(label);
        name.setLabelFor(field);
        keepWidth(name, browse); // the field gives way, not the label or the button
        HBox box = new HBox(6, name, field, browse);
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

    private void refreshGithubStatus() {
        if (githubStatusLabel == null || githubService == null) {
            return;
        }
        if (config.getSettings().isGithubSupport() && !config.getSettings().isGitSupport()) {
            // GitHub rides on the Git integration: say so here rather than report a healthy gh that does nothing.
            githubStatusLabel.getStyleClass().setAll("settings-git-status", "settings-git-missing");
            githubStatusLabel.setText(tr("settings.github.needsGit"));
            return;
        }
        // Re-applying an unchanged command keeps the service's cached answer, so it is shown at once and
        // only replaced when the fresh probe lands — no "Checking…" flash on every Settings sync.
        githubService.setCommand(config.getSettings().getGhPath());
        com.editora.github.GitHubService.Availability cached = githubService.availability();
        if (cached == null) {
            githubStatusLabel.getStyleClass().setAll("settings-git-status");
            githubStatusLabel.setText(tr("settings.github.checking"));
        } else {
            showGithubStatus(cached);
        }
        githubService.detect(this::showGithubStatus);
    }

    private void showGithubStatus(com.editora.github.GitHubService.Availability a) {
        String[] verdict = githubStatus(a);
        githubStatusLabel.getStyleClass().setAll("settings-git-status", verdict[0]);
        githubStatusLabel.setText(verdict[1]);
    }

    /** The GitHub page's status row for a probe result: {@code [style class, text]}. Pure — unit-tested. */
    static String[] githubStatus(com.editora.github.GitHubService.Availability a) {
        String missing = "settings-git-missing";
        if (!a.found()) {
            return new String[] {missing, tr("settings.github.notFound")};
        }
        return switch (a.auth()) {
            case SIGNED_OUT -> new String[] {missing, tr("settings.github.foundNoAuth")};
            case REJECTED -> new String[] {missing, tr("settings.github.foundRejected")};
            // Offline is not a fault of the setup: gh is there and has an account.
            case UNVERIFIED -> new String[] {"settings-git-found", tr("settings.github.foundUnverified", a.version())};
            case SIGNED_IN ->
                a.supportsChecks()
                        ? new String[] {"settings-git-found", tr("settings.github.found", a.version())}
                        : new String[] {
                            missing,
                            tr(
                                    "settings.github.foundOld",
                                    com.editora.github.GhVersion.number(a.version()),
                                    com.editora.github.GhVersion.MINIMUM)
                        };
        };
    }

    /** Reads each build coordinator's currently-cached detection (no subprocess probe needed — "found" just
     *  means the marker file parsed for the active context) and colors that tool's status row green/red.
     *
     * <p>Public because detection is asynchronous: the checkbox that enables a tool applies (and re-reads this)
     * before the re-detect it triggers has landed, so {@code MainController} calls this again from the
     * coordinator's detection callback — otherwise the row keeps saying "not detected" until Settings is
     * reopened. A no-op before the page is built (the label map is empty). */
    public void refreshBuildToolStatus() {
        for (BuildCoordinator c : buildCoordinators) {
            Label label = buildToolStatusLabels.get(c.tool());
            if (label == null) {
                continue;
            }
            boolean found = c.isDetected();
            // "This file is not in a Maven project" is a neutral fact, not a broken tool: no danger pill.
            label.getStyleClass().setAll("settings-git-status", buildToolStatusClass(found));
            String detected = c.detectedLabel();
            label.setText(
                    found
                            ? (detected == null || detected.isBlank()
                                    ? tr(
                                            "settings.buildTools.detected",
                                            c.tool().displayName())
                                    : tr("settings.buildTools.found", detected))
                            : tr("settings.buildTools.notFound", c.tool().displayName()));
        }
    }

    /** Pill style for a build tool's detection row: green when detected, the neutral pill when it simply
     *  does not apply to the active file (red is reserved for something broken). Pure. */
    static String buildToolStatusClass(boolean detected) {
        return detected ? "settings-git-found" : "settings-git-neutral";
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
        if (debugProgramConsoleCheck != null) {
            debugProgramConsoleCheck.setDisable(!on);
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
        // Probe the commands this page shows, without configuring the manager. This window must not
        // (re)configure it: doing so with the global commands replaced a trusted project's override and
        // shut its running server down every time Settings was merely opened.
        java.util.Map<String, String> shown = LspCoordinator.commandsForAllServers(config.getSettings());
        for (LspServerUi srv : lspServerUis()) {
            Label status = lspStatusLabels.get(srv.id());
            if (status == null) {
                continue;
            }
            String statusKey = srv.statusKey();
            status.getStyleClass().setAll("settings-git-status");
            status.setText(tr("settings.lsp.checking"));
            String langKey = installLangForServer(srv.id());
            lspManager.detectCommand(srv.id(), shown.get(srv.id()), found -> {
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
        gitService.setCommand(config.getSettings().getGitPath()); // probe what the setting names
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

    /** A stripe side in the UI language (it used to be the enum constant's name, English in every locale). */
    static String sideName(ToolWindow.Side side) {
        if (side == null) {
            return "";
        }
        return switch (side) {
            case LEFT -> tr("settings.toolWindows.side.left");
            case RIGHT -> tr("settings.toolWindows.side.right");
            case BOTTOM -> tr("settings.toolWindows.side.bottom");
        };
    }

    /** The tool-window placement page: one row per registered tool window (Show / Side / ▲▼ reorder). */
    private VBox toolWindowsPage() {
        // The page hint reads as the subtitle, like the other pages.
        VBox p = page(tr("settings.cat.toolWindows"), tr("settings.toolWindows.hint"));
        Card mainCard = card(p, null);
        // The tool-stripe toggle now lives on the Interface page; "show hidden files" on the Workspace page.

        List<Runnable> moveRefreshers = new ArrayList<>();
        Runnable refreshMoves = () -> moveRefreshers.forEach(Runnable::run);
        for (ToolWindow tw : toolWindows.getStripeToolWindows()) {
            CheckBox showCheck = new CheckBox(tr("settings.show"));
            showCheck.setSelected(toolWindows.isVisible(tw));

            ComboBox<ToolWindow.Side> sideCombo = new ComboBox<>();
            sideCombo.getItems().setAll(ToolWindow.Side.values());
            sideCombo.setConverter(new StringConverter<>() {
                @Override
                public String toString(ToolWindow.Side side) {
                    return sideName(side);
                }

                @Override
                public ToolWindow.Side fromString(String s) {
                    for (ToolWindow.Side side : ToolWindow.Side.values()) {
                        if (sideName(side).equals(s)) {
                            return side;
                        }
                    }
                    return null; // the combo is not editable: nothing types a name into it
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
            moveUp.setAccessibleText(tr("settings.moveEarlier") + " — " + tw.getTitle());
            moveDown.setAccessibleText(tr("settings.moveLater") + " — " + tw.getTitle());
            Runnable refreshThisRow = () -> {
                boolean shown = showCheck.isSelected();
                moveUp.setDisable(!shown || !toolWindows.canMove(tw, -1));
                moveDown.setDisable(!shown || !toolWindows.canMove(tw, 1));
            };
            moveRefreshers.add(refreshThisRow);
            // The row is built once, but the window can be moved or hidden from the main window meanwhile.
            toolWindowRowSyncs.add(() -> {
                showCheck.setSelected(toolWindows.isVisible(tw));
                sideCombo.setValue(toolWindows.currentSide(tw));
                sideCombo.setDisable(!showCheck.isSelected());
                refreshThisRow.run();
            });
            moveUp.setOnAction(e -> {
                toolWindows.move(tw, -1);
                refreshMoves.run();
            });
            moveDown.setOnAction(e -> {
                toolWindows.move(tw, 1);
                refreshMoves.run();
            });

            showCheck.selectedProperty().addListener((obs, was, visible) -> {
                if (syncingToolWindowRows) {
                    return;
                }
                toolWindows.setVisible(tw, visible);
                sideCombo.setDisable(!visible);
                refreshMoves.run();
            });
            sideCombo.valueProperty().addListener((obs, old, now) -> {
                if (now != null && !syncingToolWindowRows) {
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

            // A card row like every other page: the window's name on the left (with the gated ones'
            // explanation as its description), and show / side / reorder pinned right.
            Label title = new Label(tw.getTitle());
            title.getStyleClass().add("settings-row-title");
            VBox main = new VBox(2, title);
            HBox.setHgrow(main, Priority.ALWAYS);
            HBox reorder = new HBox(2, moveUp, moveDown);
            SettingSwitch show = switchFor(showCheck);
            show.setAccessibleText(tw.getTitle()); // this row is hand-built, so name its controls here
            sideCombo.setAccessibleText(tw.getTitle());
            title.setLabelFor(show);
            HBox controls = new HBox(8, show, sideCombo, reorder);
            controls.setAlignment(Pos.CENTER_RIGHT);
            controls.setMinWidth(Region.USE_PREF_SIZE);
            HBox rowBox = new HBox(16, main, controls);
            rowBox.setAlignment(Pos.CENTER_LEFT);
            rowBox.getStyleClass().add("settings-row");
            // For the context-gated windows, a muted note explaining why the row may be disabled.
            if ("project".equals(tw.getId())) {
                projectDisabledNote = note(tr("settings.toolWindows.projectDisabled"));
                projectDisabledNote.setWrapText(true);
                projectDisabledNote.getStyleClass().add("settings-row-desc");
                main.getChildren().add(projectDisabledNote);
            } else if ("commit".equals(tw.getId())) {
                commitDisabledNote = note(tr("settings.toolWindows.commitDisabled"));
                commitDisabledNote.setWrapText(true);
                commitDisabledNote.getStyleClass().add("settings-row-desc");
                main.getChildren().add(commitDisabledNote);
            } else if ("notes".equals(tw.getId())) {
                notesDisabledNote = note(tr("settings.toolWindows.notesDisabled"));
                notesDisabledNote.setWrapText(true);
                notesDisabledNote.getStyleClass().add("settings-row-desc");
                main.getChildren().add(notesDisabledNote);
            } else if ("problems".equals(tw.getId())) {
                problemsDisabledNote = note(tr("settings.toolWindows.problemsDisabled"));
                problemsDisabledNote.setWrapText(true);
                problemsDisabledNote.getStyleClass().add("settings-row-desc");
                main.getChildren().add(problemsDisabledNote);
            } else if ("debug".equals(tw.getId())) {
                debugDisabledNote = note(tr("settings.toolWindows.debugDisabled"));
                debugDisabledNote.setWrapText(true);
                debugDisabledNote.getStyleClass().add("settings-row-desc");
                main.getChildren().add(debugDisabledNote);
            }
            cardRow(mainCard, Category.TOOL_WINDOWS, rowBox, "tool window " + tw.getTitle() + " placement side show");
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
        Card fileSection = card(p, tr("settings.section.file"));
        Hyperlink link = new Hyperlink(displaySettingsPath(config.getSettingsFile()));
        link.setTooltip(new Tooltip(tr("settings.openFileTip")));
        link.setOnAction(e -> {
            if (onOpenFile != null) {
                onOpenFile.accept(config.getSettingsFile());
            }
        });
        Label pathLabel = new Label(tr("settings.path"));
        Label sessionLabel = new Label(tr("settings.sessionLog"));
        keepWidth(pathLabel, sessionLabel); // a long path gives way, not its label
        HBox fileRow = new HBox(6, pathLabel, link);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        cardRow(fileSection, Category.ADVANCED, fileRow, "settings file path toml config location");

        Path sessionLog = DebugLog.sessionFile(config.getConfigDir());
        Hyperlink sessionLink = new Hyperlink(displaySettingsPath(sessionLog));
        sessionLink.setTooltip(new Tooltip(tr("settings.openFileTip")));
        sessionLink.setOnAction(e -> {
            if (onOpenFile != null && sessionLog != null) {
                onOpenFile.accept(sessionLog);
            }
        });
        HBox sessionRow = new HBox(6, sessionLabel, sessionLink);
        sessionRow.setAlignment(Pos.CENTER_LEFT);
        cardRow(
                fileSection,
                Category.ADVANCED,
                sessionRow,
                "session log debug crash report editora-session.log diagnostics");

        Card resetSection = card(p, tr("settings.section.reset"));
        Button reset = new Button(tr("settings.resetDefaults"));
        reset.setOnAction(e -> resetAll());
        cardRow(resetSection, Category.ADVANCED, reset, "reset defaults restore factory clear");

        Card ioSection = card(p, tr("settings.section.io"));
        Button exportConfig = new Button(tr("settings.exportConfig"));
        exportConfig.setOnAction(e -> {
            if (onExportConfig != null) {
                onExportConfig.run();
            }
        });
        Label exportHint = note(tr("settings.exportConfig.hint"));
        exportHint.setWrapText(true);
        VBox exportBox = new VBox(4, exportConfig, exportHint);
        cardRow(ioSection, Category.ADVANCED, exportBox, "import export backup settings config zip archive");

        Card debugSection = card(p, tr("settings.section.debug"));
        Button debugLog = new Button(tr("settings.debugLog"));
        debugLog.setOnAction(e -> {
            if (onShowDebugLog != null) {
                onShowDebugLog.run();
            }
        });
        Label debugHint = note(tr("settings.debugLog.hint"));
        debugHint.setWrapText(true);
        VBox debugBox = new VBox(4, debugLog, debugHint);
        cardRow(
                debugSection,
                Category.ADVANCED,
                debugBox,
                "debug log logs errors warnings exceptions diagnostics bug report console");
        return p;
    }

    /**
     * The Toolbar customization page: an "Available items" list, a "Current toolbar" list (item ids +
     * separators, in bar order), and Add/Remove/Move/Add-Separator/Restore-Default controls. Every edit is
     * live-applied through {@link #toolbarActions} (persist + rebuild every window's toolbar).
     */
    private Region toolbarPage() {
        VBox p = page(tr("settings.cat.toolbar"));
        Card sec = card(p, tr("settings.toolbar.section"));

        Label desc = note(tr("settings.toolbar.note"));
        desc.setWrapText(true);
        desc.setMaxWidth(560);
        cardRow(sec, Category.TOOLBAR, desc, "toolbar customize icons order add remove reorder drag");

        ListView<String> available = new ListView<>(toolbarAvailableItems);
        ListView<String> current = new ListView<>(toolbarCurrentItems);
        available.setCellFactory(v -> toolbarCell());
        current.setCellFactory(v -> toolbarCell());
        // Tall enough for the default toolbar layout (~20 items) to be read without scrolling.
        available.setPrefSize(260, 460);
        current.setPrefSize(260, 460);

        Label availLabel = new Label(tr("settings.toolbar.available"));
        Label curLabel = new Label(tr("settings.toolbar.current"));
        availLabel.getStyleClass().add("settings-section");
        curLabel.getStyleClass().add("settings-section");
        VBox availBox = new VBox(4, availLabel, available);
        VBox curBox = new VBox(4, curLabel, current);
        javafx.scene.layout.VBox.setVgrow(available, Priority.ALWAYS);
        javafx.scene.layout.VBox.setVgrow(current, Priority.ALWAYS);

        Button add = new Button(tr("settings.toolbar.add"));
        Button remove = new Button(tr("settings.toolbar.remove"));
        Button up = new Button("▲");
        Button down = new Button("▼");
        nameReorderButtons(up, down);
        up.getStyleClass().addAll("flat", "reorder-button");
        down.getStyleClass().addAll("flat", "reorder-button");
        add.setMaxWidth(Double.MAX_VALUE);
        remove.setMaxWidth(Double.MAX_VALUE);
        Region midGap = new Region();
        javafx.scene.layout.VBox.setVgrow(midGap, Priority.ALWAYS);
        VBox mid = new VBox(6, add, remove, midGap, up, down);
        mid.setAlignment(Pos.CENTER);
        mid.setMinWidth(Region.USE_PREF_SIZE); // as wide as its (translated) buttons, never "Hinzufüg…"
        keepWidth(availLabel, curLabel);

        HBox lists = new HBox(10, availBox, mid, curBox);
        HBox.setHgrow(availBox, Priority.ALWAYS);
        HBox.setHgrow(curBox, Priority.ALWAYS);
        cardRow(sec, Category.TOOLBAR, lists, "toolbar available current add remove up down move");

        Button sepBtn = new Button(tr("settings.toolbar.addSeparator"));
        Button restore = new Button(tr("settings.toolbar.restoreDefault"));
        HBox bottom = new HBox(6, sepBtn, spacer(), restore);
        cardRow(sec, Category.TOOLBAR, bottom, "toolbar separator restore default reset");

        Runnable refresh = () -> {
            if (toolbarActions == null) {
                return;
            }
            java.util.List<String> cur = toolbarActions.current();
            toolbarCurrentItems.setAll(cur);
            java.util.Set<String> used = new java.util.HashSet<>(cur);
            java.util.List<String> avail = new java.util.ArrayList<>();
            for (com.editora.toolbar.ToolbarCatalog.Item it : com.editora.toolbar.ToolbarCatalog.items()) {
                if (!used.contains(it.id())) {
                    avail.add(it.id());
                }
            }
            toolbarAvailableItems.setAll(avail);
        };
        refreshToolbarLists = refresh;
        refresh.run();

        // Commit the working list, then re-read the effective (sanitized) layout and optionally reselect.
        java.util.function.IntConsumer commit = reselect -> {
            if (toolbarActions != null) {
                toolbarActions.apply(new java.util.ArrayList<>(toolbarCurrentItems));
            }
            refresh.run();
            if (reselect >= 0 && reselect < toolbarCurrentItems.size()) {
                current.getSelectionModel().select(reselect);
            }
        };

        add.setOnAction(e -> {
            String sel = available.getSelectionModel().getSelectedItem();
            if (sel == null) {
                return;
            }
            int at = current.getSelectionModel().getSelectedIndex();
            if (at < 0) {
                toolbarCurrentItems.add(sel);
            } else {
                toolbarCurrentItems.add(at + 1, sel);
            }
            commit.accept(-1);
        });
        remove.setOnAction(e -> {
            int idx = current.getSelectionModel().getSelectedIndex();
            if (idx < 0) {
                return;
            }
            toolbarCurrentItems.remove(idx);
            commit.accept(Math.min(idx, toolbarCurrentItems.size() - 1));
        });
        up.setOnAction(e -> {
            int idx = current.getSelectionModel().getSelectedIndex();
            if (idx > 0) {
                java.util.Collections.swap(toolbarCurrentItems, idx, idx - 1);
                commit.accept(idx - 1);
            }
        });
        down.setOnAction(e -> {
            int idx = current.getSelectionModel().getSelectedIndex();
            if (idx >= 0 && idx < toolbarCurrentItems.size() - 1) {
                java.util.Collections.swap(toolbarCurrentItems, idx, idx + 1);
                commit.accept(idx + 1);
            }
        });
        sepBtn.setOnAction(e -> {
            int at = current.getSelectionModel().getSelectedIndex();
            String s = com.editora.toolbar.ToolbarCatalog.SEPARATOR;
            if (at < 0) {
                toolbarCurrentItems.add(s);
            } else {
                toolbarCurrentItems.add(at + 1, s);
            }
            commit.accept(-1);
        });
        restore.setOnAction(e -> {
            if (toolbarActions != null) {
                toolbarActions.restoreDefault();
            }
            refresh.run();
        });

        return p;
    }

    /** A ListView cell showing a toolbar item's icon + label (a divider label for the separator token). */
    private ListCell<String> toolbarCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String token, boolean empty) {
                super.updateItem(token, empty);
                if (empty || token == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(ToolbarCoordinator.labelFor(token));
                setGraphic(ToolbarCoordinator.iconFor(token));
            }
        };
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

    /** A page with the kit's one-line subtitle under its heading, saying what the page is for. */
    private VBox page(String title, String subtitle) {
        VBox box = page(title);
        if (subtitle != null && !subtitle.isBlank()) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("settings-page-subtitle");
            sub.setWrapText(true);
            sub.setMaxWidth(520);
            box.getChildren().add(sub);
        }
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
        rows.add(new SettingRow(cat, node, keywords, section, null));
    }

    // --- card rows (UI Kit v1) ---------------------------------------------------------------------
    //
    // A card groups related settings into one bordered block, and each row states its setting as a
    // title + a one-line description with the control on the right — instead of a bare CheckBox whose
    // own label has to carry the whole explanation. Pages opt in one at a time; `page`/`section`/`row`
    // above are unchanged, so an unmigrated page renders exactly as before.

    /** A card and the box its rows are appended to (the title, if any, sits above that box). */
    private record Card(VBox box, VBox body) {}

    private Card card(VBox page, String title) {
        VBox body = new VBox();
        body.getStyleClass().add("settings-card-body");
        VBox box = new VBox();
        box.getStyleClass().add("settings-card");
        if (title != null && !title.isBlank()) {
            Label t = new Label(title);
            t.getStyleClass().add("settings-card-title");
            box.getChildren().add(t);
        }
        box.getChildren().add(body);
        page.getChildren().add(box);
        cards.add(box);
        return new Card(box, body);
    }

    /**
     * Appends a row to a card. Mirrors {@link #row}, but the row is registered against its card so the
     * search filter can hide the whole card once every row inside it is filtered out — otherwise a
     * non-matching page would keep showing an empty bordered box.
     */
    private void cardRow(Card card, Category cat, Node node, String keywords) {
        // Any node can be a card row, not just a settingRow() — a page mid-migration still has plain
        // checkboxes, status labels and path fields. Wrap those (rather than adding the class to them)
        // so .settings-row's padding can't collide with the node's own.
        Node row = node;
        if (!node.getStyleClass().contains("settings-row")) {
            HBox h = new HBox(node);
            h.setAlignment(Pos.CENTER_LEFT);
            h.getStyleClass().add("settings-row");
            HBox.setHgrow(node, Priority.ALWAYS); // a full-width control (a path field) keeps filling
            row = h;
        }
        card.body().getChildren().add(row);
        rows.add(new SettingRow(cat, row, keywords, null, card.box()));
    }

    /**
     * A checkbox as a kit card row with zero new i18n: the row title is the checkbox's own localized
     * label, the (optional) description is an existing localized string, and the control is the bound
     * toggle switch. This is what makes migrating a page mechanical rather than a translation project.
     */
    private void checkRow(Card card, Category cat, CheckBox check, String description, String keywords) {
        cardRow(card, cat, settingRow(check.getText(), description, switchFor(check)), keywords);
    }

    /** A labeled field/control as a card row: title left, the control right (kit "Git command" shape). */
    private void controlRow(Card card, Category cat, String title, String description, Node control, String keywords) {
        cardRow(card, cat, settingRow(title, description, control), keywords);
    }

    /**
     * A card row: title + optional description on the left, the control on the right (below the text when
     * the control is too wide to leave the description a readable column — see {@link SettingRowPane}).
     * The title is the control's label for assistive technology, and names an on/off switch outright.
     */
    static Node settingRow(String title, String description, Node control) {
        Label t = new Label(title);
        t.getStyleClass().add("settings-row-title");
        t.setWrapText(true); // a long (translated) title beside a switch wraps; it used to end in "…"
        VBox main = new VBox(2, t);
        if (description != null && !description.isBlank()) {
            Label d = new Label(description);
            d.getStyleClass().add("settings-row-desc");
            d.setWrapText(true);
            main.getChildren().add(d);
        }
        if (control != null) {
            List<SettingSwitch> switches = switchesIn(control);
            t.setLabelFor(switches.isEmpty() ? control : switches.get(0));
            for (SettingSwitch sw : switches) {
                sw.setAccessibleText(title);
                sw.setAccessibleHelp(description);
            }
        }
        SettingRowPane row = new SettingRowPane(main, control);
        row.getStyleClass().add("settings-row");
        return row;
    }

    /** The on/off switches a row's control holds: the control itself, or switches nested in its box. */
    private static List<SettingSwitch> switchesIn(Node control) {
        List<SettingSwitch> found = new ArrayList<>();
        if (control instanceof SettingSwitch sw) {
            found.add(sw);
        } else if (control instanceof javafx.scene.layout.Pane pane) {
            pane.getChildren().forEach(child -> found.addAll(switchesIn(child)));
        }
        return found;
    }

    /**
     * The kit renders a card row's on/off control as a toggle switch, not a checkbox. All of Editora's
     * setting state lives on {@link CheckBox}es (listeners, {@code syncAll}, palette toggles), so rather
     * than rewire any of that, the switch is a <em>view</em>: bidirectionally bound to the checkbox's
     * {@code selectedProperty} (and following its {@code disableProperty}), while the checkbox itself
     * stays out of the scene graph. Every existing writer keeps working untouched. {@link SettingSwitch}
     * is what makes it operable from the keyboard and visible to a screen reader.
     */
    private static SettingSwitch switchFor(CheckBox check) {
        return SettingSwitch.boundTo(check);
    }

    private Region labeled(String label, Node control) {
        Label l = new Label(label);
        // Floor the width to 130 so short labels line up into a tidy column, but let a longer label grow to
        // its full text (maxWidth = pref) instead of ellipsizing — e.g. "Max size / project (MB)" or any
        // longer translation. (Previously a fixed prefWidth(130) clamped + truncated the longer ones.)
        l.setMinWidth(130);
        l.setMaxWidth(Region.USE_PREF_SIZE);
        l.setLabelFor(control);
        HBox h = new HBox(10, l, control);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-hint");
        return l;
    }

    /** The kit's boxed page note (its {@code .set-note}) — a tinted, rounded paragraph, not a bare hint. */
    private static Label noteBox(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-note-box");
        l.setWrapText(true);
        l.setMaxWidth(520);
        return l;
    }

    /** A small "i" info glyph carrying a hover {@link Tooltip} — for a compact detail that would otherwise
     *  need its own {@link #note} line (e.g. listing exactly which commands a toggle enables). */
    private static Node infoIcon(String tooltipText) {
        Node icon = Icons.about();
        icon.getStyleClass().add("settings-info-icon");
        Tooltip tip = new Tooltip(tooltipText);
        tip.setWrapText(true);
        tip.setMaxWidth(320);
        Tooltip.install(icon, tip);
        return icon;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    // --- search ----------------------------------------------------------------------------------

    /**
     * Whether {@code text} matches the search {@code query}: every whitespace-separated word of the query
     * must occur in it, in any order, case-insensitively ("size font" finds "Font size"). Pure.
     */
    static boolean matches(String query, String text) {
        if (query == null || query.isBlank()) {
            return true;
        }
        if (text == null) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String word : query.strip().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!haystack.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Everything a row can be found by: its (English) keywords plus the text it actually shows — title,
     * description, checkbox and button captions, field prompts — so a search in the UI language works.
     */
    static String searchText(String keywords, Node... shown) {
        StringBuilder out = new StringBuilder(keywords == null ? "" : keywords);
        for (Node node : shown) {
            collectText(node, out);
        }
        return out.toString();
    }

    private static void collectText(Node node, StringBuilder out) {
        if (node == null) {
            return;
        }
        if (node instanceof javafx.scene.control.Labeled labeled && labeled.getText() != null) {
            out.append(' ').append(labeled.getText());
        }
        if (node instanceof javafx.scene.control.TextInputControl input && input.getPromptText() != null) {
            out.append(' ').append(input.getPromptText());
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, out);
            }
        }
    }

    /** A row's search text, built on first use (pages are built once; titles never change afterwards). */
    private String searchTextOf(SettingRow r) {
        return searchIndex.computeIfAbsent(r, row -> {
            // A row is also found by the heading it sits under: its card's title or its section label.
            Node cardTitle = row.card() != null
                            && !row.card().getChildren().isEmpty()
                            && row.card().getChildren().get(0) instanceof Label title
                    ? title
                    : null;
            // ... and by the name of its page: typing "Keymaps" or "Build Tools" used to say that no
            // setting matches, because no row on those pages happens to repeat the sidebar's name.
            return searchText(row.keywords(), row.node(), cardTitle, row.section()) + ' ' + row.category().display;
        });
    }

    /** Shows the selected category's page, or the "no settings match" note while a search has no hits. */
    private void showContent() {
        Node content = searchEmpty != null && searchEmpty.isVisible()
                ? searchEmpty
                : sidebar.getSelectionModel().getSelectedItem() instanceof Category cat ? pages.get(cat) : null;
        if (content != null && contentScroll.getContent() != content) {
            contentScroll.setContent(content);
        }
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
            cards.forEach(c -> setShown(c, true));
            searchEmpty.setVisible(false);
            showContent();
            sidebar.refresh();
            return;
        }
        Set<Category> matched = EnumSet.noneOf(Category.class);
        Set<Label> visibleSections = new HashSet<>();
        Set<VBox> visibleCards = new HashSet<>();
        for (SettingRow r : rows) {
            boolean m = matches(query, searchTextOf(r));
            setShown(r.node(), m);
            if (m) {
                matched.add(r.category());
                if (r.section() != null) {
                    visibleSections.add(r.section());
                }
                if (r.card() != null) {
                    visibleCards.add(r.card());
                }
            }
        }
        sectionLabels.forEach(s -> setShown(s, visibleSections.contains(s)));
        cards.forEach(c -> setShown(c, visibleCards.contains(c)));
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
        // No hit anywhere: say so, instead of a blank page beside a fully greyed-out sidebar.
        searchEmpty.setText(tr("settings.search.empty", query.strip()));
        searchEmpty.setVisible(matched.isEmpty());
        showContent();
        Object selObj = sidebar.getSelectionModel().getSelectedItem();
        Category sel = (selObj instanceof Category c) ? c : null;
        Category best = bestMatch(query, matched);
        // A page named by the query wins even over a matching current page: "tool windows" typed on the
        // Interface page (which mentions them) means the Tool Windows page.
        boolean named = best != null && namesPage(query, best.display);
        if (best != null && (sel == null || !matched.contains(sel) || (named && !namesPage(query, sel.display)))) {
            sidebar.getSelectionModel().select(best);
        }
    }

    /**
     * Whether {@code query} is (the start of) a page's sidebar name. Three characters at least: a page is
     * not "named" by the first letter of a word that merely happens to begin like it. Pure.
     */
    static boolean namesPage(String query, String pageName) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return q.length() >= 3
                && pageName != null
                && pageName.toLowerCase(Locale.ROOT).startsWith(q);
    }

    /** The page a search opens: the first matching one named by the query, else the first with a hit. */
    private static Category bestMatch(String query, Set<Category> matched) {
        Category first = null;
        for (Category c : Category.values()) {
            if (matched.contains(c)) {
                if (namesPage(query, c.display)) {
                    return c;
                }
                first = first == null ? c : first;
            }
        }
        return first;
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
                // The kit sets group headers in small caps; JavaFX CSS has no text-transform, so
                // uppercase in code (default locale — the header text is localized).
                setText(g.display.toUpperCase(Locale.getDefault()));
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
        preview = AreaUndo.none(new CodeArea(PREVIEW_SAMPLE));
        preview.getStyleClass().addAll("editor-area", "settings-preview");
        preview.setEditable(false);
        preview.setFocusTraversable(false);
        // No setShowCaret(OFF): a read-only area already hides its caret under the default AUTO, and OFF/ON
        // subscribe the caret to a static RichTextFX stream that then pins the area (and its window) forever.
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
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION, tr("settings.reset.confirm"), ButtonType.OK, ButtonType.CANCEL));
        confirm.initOwner(stage);
        confirm.setTitle(tr("settings.reset.title"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        Settings.resetToDefaults(config.getSettings());
        commitReset();
    }

    /** Persists + applies a reset, re-themes the app, and reloads the controls + preview. */
    private void commitReset() {
        Settings s = config.getSettings();
        config.save();
        javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(s.getTheme()));
        onApply.accept(s);
        load();
        // load() sets the keymap combo under `loading`, so its listener never fires: without this the
        // settings file says "default keymap" while every window keeps dispatching the old one.
        if (onKeymapChanged != null) {
            onKeymapChanged.run();
        }
        refreshShortcuts();
    }

    // --- load + sync (unchanged behavior) --------------------------------------------------------

    /**
     * Re-reads the External Tools list from the live settings. The page is built eagerly in the constructor
     * (one SettingsWindow per MainController), so a startup snapshot went stale the moment another window
     * added a tool — and this window's next save wrote its snapshot back, deleting the other's tool.
     */
    private void reloadExternalTools() {
        if (sameTools(externalToolItems, config.getSettings().getExternalTools())) {
            return; // nothing changed behind the page: keep the rows (and the form's caret) as they are
        }
        var selected = externalToolList == null
                ? null
                : externalToolList.getSelectionModel().getSelectedItem();
        String selectedName = selected == null ? null : selected.getName();
        externalToolItems.setAll(copyTools(config.getSettings().getExternalTools()));
        if (externalToolList != null && selectedName != null) {
            for (var t : externalToolItems) {
                if (selectedName.equals(t.getName())) {
                    externalToolList.getSelectionModel().select(t);
                    break;
                }
            }
        }
    }

    /**
     * Re-reads every list editor whose working copy is written back whole (External Tools, Abbreviations,
     * Remote sites). Each is a snapshot of a shared store that a command, a tool window or another window's
     * Settings can change; saving a stale snapshot deleted whatever had been added since. Runs whenever the
     * window is (re)loaded and whenever it regains focus — the two moments an edit can follow a change made
     * elsewhere. Field edits commit on focus loss, so nothing typed is pending when it runs.
     */
    private void reloadStoreBackedEditors() {
        reloadExternalTools();
        reloadAbbrevs();
        reloadRemote();
    }

    /** The Snippets and Templates pages read files; refreshed when Settings is opened, not on every load. */
    private void reloadFileBackedEditors() {
        if (reloadSnippets != null) {
            reloadSnippets.run();
        }
        if (reloadTemplates != null) {
            reloadTemplates.run();
        }
    }

    private static boolean sameTools(
            java.util.List<com.editora.externaltool.ExternalTool> a,
            java.util.List<com.editora.externaltool.ExternalTool> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            com.editora.externaltool.ExternalTool x = a.get(i);
            com.editora.externaltool.ExternalTool y = b.get(i);
            if (x != y
                    && !(java.util.Objects.equals(x.getName(), y.getName())
                            && java.util.Objects.equals(x.getCommand(), y.getCommand())
                            && java.util.Objects.equals(x.getArguments(), y.getArguments())
                            && java.util.Objects.equals(x.getWorkingDir(), y.getWorkingDir())
                            && x.getStdin() == y.getStdin()
                            && x.getOutput() == y.getOutput()
                            && x.isEnabled() == y.isEnabled())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameAbbrevs(
            java.util.List<com.editora.config.Abbreviation> a, java.util.List<com.editora.config.Abbreviation> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            com.editora.config.Abbreviation x = a.get(i);
            com.editora.config.Abbreviation y = b.get(i);
            if (x != y
                    && !(java.util.Objects.equals(x.getAbbreviation(), y.getAbbreviation())
                            && java.util.Objects.equals(x.getExpansion(), y.getExpansion()))) {
                return false;
            }
        }
        return true;
    }

    private void load() {
        loading = true;
        try {
            reloadStoreBackedEditors(); // a command or another window may have changed a store since the build
            refreshDictionaryList(); // pick up words added elsewhere (e.g. "Add to Dictionary") since last open
            if (refreshToolbarLists != null) {
                refreshToolbarLists.run(); // reflect any on-bar drag customization since the page was built
            }
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
            inlayHintsCheck.setSelected(settings.isInlayHints());
            codeLensCheck.setSelected(settings.isCodeLens());
            inlayHintModeCombo.setValue(settings.getInlayHintMode());
            onTypeFormattingCheck.setSelected(settings.isLspOnTypeFormatting());
            pasteImportsCheck.setSelected(settings.isLspPasteImports());
            smartSemicolonCheck.setSelected(settings.isLspSmartSemicolon());
            pdfLineNumbersCheck.setSelected(settings.isPdfLineNumbers());
            pdfHighlightCheck.setSelected(settings.isPdfSyntaxHighlighting());
            pdfPageSizeCombo.setValue(settings.getPdfPageSize());
            spellCheckBox.setSelected(settings.isSpellCheck());
            dictEnableCheck.setSelected(settings.isPersonalDictionary());
            techDictEnableCheck.setSelected(settings.isTechnicalDictionary());
            spellLanguageCombo.setValue(settings.getSpellLanguage());
            spellLanguageCombo.setDisable(!settings.isSpellCheck());
            menuBarCheck.setSelected(settings.isShowMenuBar());
            toolbarCheck.setSelected(settings.isShowToolbar());
            statusBarCheck.setSelected(settings.isShowStatusBar());
            tabBarCheck.setSelected(settings.isShowTabBar());
            breadcrumbCheck.setSelected(settings.isShowBreadcrumb());
            simpleModeCheck.setSelected(settings.isSimpleMode());
            templateAuthorField.setText(settings.getAuthorNameRaw());
            toolStripeCheck.setSelected(settings.isShowToolStripe());
            extendedWindowCheck.setSelected(settings.isExtendedWindow());
            paletteSearchEverywhereCheck.setSelected(settings.isPaletteUsesSearchEverywhere());
            projectHiddenCheck.setSelected(settings.isProjectShowHidden());
            markdownFormatBarCheck.setSelected(settings.isMarkdownFormatBar());
            lspInstallPromptsCheck.setSelected(settings.isLspInstallPrompts());
            markdownLintCheck.setSelected(settings.isMarkdownLint());
            mathSupportCheck.setSelected(settings.isMathSupport());
            editorConfigCheck.setSelected(settings.isEditorConfigSupport());
            logViewerCheck.setSelected(settings.isLogViewer());
            testRunnerCheck.setSelected(settings.isTestRunner());
            csvGridCheck.setSelected(settings.isCsvPreview());
            structuredPreviewCheck.setSelected(settings.isStructuredPreview());
            svgPreviewCheck.setSelected(settings.isSvgPreview());
            crontabPreviewCheck.setSelected(settings.isCrontabPreview());
            fstabPreviewCheck.setSelected(settings.isFstabPreview());
            systemdPreviewCheck.setSelected(settings.isSystemdPreview());
            sshConfigPreviewCheck.setSelected(settings.isSshConfigPreview());
            dockerfilePreviewCheck.setSelected(settings.isDockerfilePreview());
            githubActionsPreviewCheck.setSelected(settings.isGithubActionsPreview());
            pomPreviewCheck.setSelected(settings.isPomPreview());
            stickyScrollCheck.setSelected(settings.isStickyScroll());
            symbolIndexCheck.setSelected(settings.isSymbolIndex());
            csvRainbowCheck.setSelected(settings.isCsvRainbow());
            bracketColorsCheck.setSelected(settings.isBracketColors());
            autoRenameTagCheck.setSelected(settings.isAutoRenameTag());
            autoFillCheck.setSelected(settings.isAutoFill());
            if (abbrevModeCheck != null) {
                abbrevModeCheck.setSelected(settings.isAbbrevMode());
            }
            autoCloseTagsCheck.setSelected(settings.isAutoCloseTags());
            todoHighlightCheck.setSelected(settings.isTodoHighlight());
            rebuildTodoRows();
            rebuildMarkdownLintRules();
            multiCaretCheck.setSelected(settings.isMultiCaret());
            copyLineNoSelectionCheck.setSelected(settings.isCopyLineWhenNoSelection());
            copyWithHighlightingCheck.setSelected(settings.isCopyWithSyntaxHighlighting());
            projectsCheck.setSelected(settings.isProjectSupport());
            syncingToolWindowRows = true;
            try {
                toolWindowRowSyncs.forEach(Runnable::run);
            } finally {
                syncingToolWindowRows = false;
            }
            updateProjectRowEnabled();
            gitCheck.setSelected(settings.isGitSupport());
            blameCheck.setSelected(settings.isGitBlameInline());
            blameCheck.setDisable(!settings.isGitSupport());
            gitPathField.setText(settings.getGitPath());
            gitPullModeCombo.setValue(com.editora.git.GitPullMode.of(settings.getGitPullMode()));
            gitAutoFetchCheck.setSelected(settings.isGitAutoFetch());
            gitAutoFetchSpinner.getValueFactory().setValue(settings.getGitAutoFetchMinutes());
            gitAutoFetchSpinner.setDisable(!settings.isGitAutoFetch());
            githubCheck.setSelected(settings.isGithubSupport());
            ghPathField.setText(settings.getGhPath());
            ghPathField.setDisable(!settings.isGithubSupport());
            refreshGithubStatus();
            updateCheckCheck.setSelected(settings.isUpdateCheck());
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
            diagramCheck.setSelected(settings.isDiagramSupport());
            dotPathField.setText(settings.getDotPath());
            plantumlPathField.setText(settings.getPlantumlPath());
            refreshDiagramStatus();
            typstCheck.setSelected(settings.isTypstSupport());
            typstPathField.setText(settings.getTypstPath());
            refreshTypstStatus();
            for (BuildTool bt : BuildTool.enabled()) {
                buildToolChecks.get(bt).setSelected(bt.enabledIn(settings));
                buildToolCommandFields.get(bt).setText(bt.commandIn(settings));
                if (bt == BuildTool.MAVEN) {
                    JdkChoice.select(mavenJdkCombo, settings.getMavenJdkHome());
                    mavenArchetypeCatalogField.setText(settings.getMavenArchetypeCatalogUrl());
                }
            }
            refreshBuildToolStatus();
            ripgrepCheck.setSelected(settings.isRipgrepSearch());
            searchGitignoreCheck.setSelected(settings.isSearchRespectGitignore());
            ripgrepCommandField.setText(settings.getRipgrepCommand());
            refreshRipgrepStatus();
            httpCheck.setSelected(settings.isHttpClientSupport());
            htmlPreviewCheck.setSelected(settings.isHtmlPreviewSupport());
            mcpCheck.setSelected(settings.isMcpSupport());
            aiMasterCheck.setSelected(settings.isAiEnabled());
            updateAiSubEnablement(settings.isAiEnabled());
            agentCheck.setSelected(settings.isAgentSupport());
            agentClientCombo.setValue(com.editora.agent.AcpAgentRegistry.from(settings.getAgentClient())
                    .id());
            for (AgentClientUi a : agentClientUis()) {
                TextField field = agentCommandFields.get(a.id());
                if (field != null) {
                    field.setText(a.getCommand().get());
                }
            }
            refreshAgentClientStatus();
            agentIncludeContextCheck.setSelected(settings.isAgentIncludeContext());
            aiCheck.setSelected(settings.isAiSupport());
            aiInlineCheck.setSelected(settings.isAiInlineCompletion());
            aiProviderCombo.setValue(
                    com.editora.ai.AiProvider.from(settings.getAiProvider()).id());
            syncAiProviderFields();
            javafx.application.Platform.runLater(this::refreshAiStatus); // check once the fields are populated
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
            debugProgramConsoleCheck.setSelected(settings.isDebugProgramConsole());
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
            expertCheck.setSelected(config.getWorkspaceState().isExpertMode());
            String mode = MainController.autoSaveModeOf(settings.getAutoSave());
            autoSaveCombo.setValue(mode);
            autoSaveDelaySpinner.getValueFactory().setValue(Math.max(1, (int)
                    Math.round(settings.getAutoSaveDelayMillis() / 1000.0)));
            autoSaveDelaySpinner.setDisable(!FileWorkflowCoordinator.AUTOSAVE_DELAY.equals(mode));
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
     * Disables the feature-gated tool-window rows when their feature is off: Problems needs the LSP
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
        updateTransientRow(true, runShowCheck, runSideCombo, runMoveUp, runMoveDown, null, runToolWindowRef);
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

        /** Existing bindings that binding {@code chordSeq} to {@code commandId} would collide with — an exact
         *  match, a prefix that would shadow multi-key chords, or a shorter chord that makes this unreachable.
         *  Empty when there's no conflict. */
        java.util.List<com.editora.command.KeybindingEdits.Conflict> conflicts(String chordSeq, String commandId);

        void rebind(String commandId, String chordSeq);

        void reset(String commandId);

        void resetAll();
    }

    /**
     * A periwinkle chord chip for {@code commandId} (UI Kit v1: a Settings row teaches the keyboard
     * instead of hiding it). The binding is not known when pages are built — {@link ShortcutActions} is
     * injected afterwards — so the chip starts empty and {@link #refreshChordChips()} fills it in. An
     * unbound command shows nothing at all rather than an empty chip.
     */
    private Label chordChip(String commandId) {
        Label chip = new Label();
        chip.getStyleClass().add("chord-chip");
        setShown(chip, false);
        chordChips.put(commandId, chip);
        return chip;
    }

    /** Fills every {@link #chordChip} from the live keymap; hides the chip for an unbound command. */
    private void refreshChordChips() {
        if (shortcutActions == null || chordChips.isEmpty()) {
            return;
        }
        Map<String, String> byId = new HashMap<>();
        for (Shortcut s : shortcutActions.rows()) {
            if (s.chord() != null && !s.chord().isBlank()) {
                byId.put(s.id(), s.chord());
            }
        }
        chordChips.forEach((id, chip) -> {
            String chord = byId.get(id);
            chip.setText(chord == null ? "" : chord);
            setShown(chip, chord != null);
        });
    }

    /** Injects the keybinding-editor backend (→ MainController); enables the shortcuts list. */
    public void setShortcutActions(ShortcutActions actions) {
        this.shortcutActions = actions;
        refreshShortcuts();
    }

    /**
     * Brings an open Settings window in line with the shared keymap after it was reloaded — by this window or
     * by another one, whose keymap switch or rebind this window's combo, shortcut list, chord chips and Macros
     * key-binding row would otherwise keep showing the old state of.
     */
    public void syncKeymap() {
        if (built && stage.isShowing()) {
            syncKeymapCombo();
        }
    }

    /**
     * Re-reads the list editors backed by a shared store if the window is showing: a store changed (a Connect
     * finished, an abbreviation was defined, another window's Settings saved) — possibly while this window has
     * focus, which the focus listener cannot notice.
     */
    public void syncStoreBackedEditors() {
        if (built && stage.isShowing() && !loading) {
            reloadStoreBackedEditors();
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
        refreshShortcuts();
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
            gitPullModeCombo.setValue(
                    com.editora.git.GitPullMode.of(config.getSettings().getGitPullMode()));
            gitAutoFetchCheck.setSelected(config.getSettings().isGitAutoFetch());
            gitAutoFetchSpinner.getValueFactory().setValue(config.getSettings().getGitAutoFetchMinutes());
            gitAutoFetchSpinner.setDisable(!config.getSettings().isGitAutoFetch());
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
            menuBarCheck.setSelected(config.getSettings().isShowMenuBar());
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
        updateLspToolRowsEnabled(); // refresh the Problems and Debug row gates
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

    public void syncAgentCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            agentCheck.setSelected(config.getSettings().isAgentSupport());
            agentClientCombo.setValue(
                    com.editora.agent.AcpAgentRegistry.from(config.getSettings().getAgentClient())
                            .id());
        } finally {
            loading = prev;
        }
    }

    public void syncAiCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            aiCheck.setSelected(config.getSettings().isAiSupport());
            aiInlineCheck.setSelected(config.getSettings().isAiInlineCompletion());
            aiProviderCombo.setValue(
                    com.editora.ai.AiProvider.from(config.getSettings().getAiProvider())
                            .id());
            syncAiProviderFields();
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
            testRunnerCheck.setSelected(config.getSettings().isTestRunner());
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
            inlayHintsCheck.setSelected(s.isInlayHints());
            codeLensCheck.setSelected(s.isCodeLens());
            inlayHintModeCombo.setValue(s.getInlayHintMode());
            onTypeFormattingCheck.setSelected(s.isLspOnTypeFormatting());
            pasteImportsCheck.setSelected(s.isLspPasteImports());
            smartSemicolonCheck.setSelected(s.isLspSmartSemicolon());
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

    /** Re-reads the Zen and Expert switches: entering one leaves the other, and both have palette commands. */
    void syncFocusModeChecks() {
        if (!built) {
            return;
        }
        quietly(() -> {
            zenCheck.setSelected(config.getWorkspaceState().isZenMode());
            expertCheck.setSelected(config.getWorkspaceState().isExpertMode());
        });
    }

    void syncViewChecks() {
        if (!built) {
            return;
        }
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
            menuBarCheck.setSelected(s.isShowMenuBar());
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
            testRunnerCheck.setSelected(s.isTestRunner());
            csvGridCheck.setSelected(s.isCsvPreview());
            structuredPreviewCheck.setSelected(s.isStructuredPreview());
            svgPreviewCheck.setSelected(s.isSvgPreview());
            crontabPreviewCheck.setSelected(s.isCrontabPreview());
            fstabPreviewCheck.setSelected(s.isFstabPreview());
            systemdPreviewCheck.setSelected(s.isSystemdPreview());
            sshConfigPreviewCheck.setSelected(s.isSshConfigPreview());
            dockerfilePreviewCheck.setSelected(s.isDockerfilePreview());
            githubActionsPreviewCheck.setSelected(s.isGithubActionsPreview());
            pomPreviewCheck.setSelected(s.isPomPreview());
            stickyScrollCheck.setSelected(s.isStickyScroll());
            symbolIndexCheck.setSelected(s.isSymbolIndex());
            csvRainbowCheck.setSelected(s.isCsvRainbow());
            bracketColorsCheck.setSelected(s.isBracketColors());
            autoRenameTagCheck.setSelected(s.isAutoRenameTag());
            autoFillCheck.setSelected(s.isAutoFill());
            if (abbrevModeCheck != null) {
                abbrevModeCheck.setSelected(s.isAbbrevMode());
            }
            autoCloseTagsCheck.setSelected(s.isAutoCloseTags());
            todoHighlightCheck.setSelected(s.isTodoHighlight());
            rebuildTodoRows();
            rebuildMarkdownLintRules();
            indentStyleCombo.setValue(s.getIndentStyle());
            multiCaretCheck.setSelected(s.isMultiCaret());
            copyLineNoSelectionCheck.setSelected(s.isCopyLineWhenNoSelection());
            copyWithHighlightingCheck.setSelected(s.isCopyWithSyntaxHighlighting());
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

    private void apply() {
        if (loading) {
            return;
        }
        // Each control writes its own setting before calling this. Copying the font controls in here made every
        // unrelated change write back whatever they happened to show — a stale value in a window that had not
        // been re-synced, or nothing at all (no save) once the size field had been emptied.
        Settings settings = config.getSettings();
        config.save();
        onApply.accept(settings);
        updatePreviewFont();
    }

    /**
     * Shows the About dialog. Shared by the {@code help.about} command and the toolbar About button.
     * The settings-file path is a link that opens that file in the editor via {@code openFile}.
     */
    public static void showAbout(
            Window owner,
            Path settingsFile,
            Consumer<Path> openFile,
            Consumer<String> openUrl,
            String commit,
            com.editora.update.ReleaseInfo update) {
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.INFORMATION));
        alert.initOwner(owner);
        alert.setTitle(tr("dialog.about.title", com.editora.AppInfo.NAME));
        // The whole dialog is the content: no Alert header band or graphic, so the icon, name, version and
        // tagline read as one block the way a native About panel does.
        alert.setHeaderText(null);
        alert.setGraphic(null);
        alert.getDialogPane().getStyleClass().add("about-dialog");
        // Dialogs.styled above attaches app.css: without it the panel's .about-* rules never apply.

        // For a snapshot build, append the git branch to the version string so a build made from a
        // worktree/feature branch can be told apart from one made off master. Empty for release builds
        // and for packaged snapshots with no git checkout.
        String versionString = com.editora.AppInfo.VERSION;
        if (com.editora.AppInfo.isSnapshot()) {
            String branch = com.editora.AppInfo.gitBranch();
            if (!branch.isBlank()) {
                versionString += " (" + branch + ")";
            }
        }

        // --- header: icon beside name / version / tagline ------------------------------------------
        Label name = new Label(com.editora.AppInfo.NAME);
        name.getStyleClass().add("about-name");
        Label version = new Label(tr("about.version", versionString));
        version.getStyleClass().add("about-version");
        Label tagline = new Label(tr("about.tagline"));
        tagline.getStyleClass().add("about-tagline");
        tagline.setWrapText(true);
        VBox titleBox = new VBox(2, name, version, tagline);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        HBox header = new HBox(16, titleBox);
        var iconStream = SettingsWindow.class.getResourceAsStream("/com/editora/icons/icon-128.png");
        if (iconStream != null) {
            ImageView logo = new ImageView(new Image(iconStream));
            logo.setFitWidth(72);
            logo.setFitHeight(72);
            logo.setPreserveRatio(true);
            header.getChildren().add(0, logo);
        }
        header.setAlignment(Pos.TOP_LEFT);

        // --- copyright + links ----------------------------------------------------------------------
        Label copyright = new Label(com.editora.AppInfo.COPYRIGHT);
        copyright.getStyleClass().add("about-copyright");

        VBox links = new VBox(2);
        links.getChildren().add(aboutLink(com.editora.AppInfo.LICENSE, () -> {
            if (openUrl != null) {
                openUrl.accept(com.editora.AppInfo.HOMEPAGE);
            }
        }));
        links.getChildren().add(aboutLink(tr("about.homepage"), () -> {
            if (openUrl != null) {
                openUrl.accept(com.editora.AppInfo.HOMEPAGE);
            }
        }));
        links.getChildren().add(aboutLink(tr("about.releases"), () -> {
            if (openUrl != null) {
                openUrl.accept(com.editora.AppInfo.RELEASES_PAGE);
            }
        }));
        Hyperlink settingsLink =
                aboutLink(tr("settings.aboutSettingsLabel") + " " + displaySettingsPath(settingsFile), () -> {
                    if (openFile != null) {
                        openFile.accept(settingsFile);
                    }
                });
        settingsLink.setTooltip(new Tooltip(tr("settings.openFileTip")));
        settingsLink.setOnAction(e -> {
            alert.close();
            if (openFile != null) {
                openFile.accept(settingsFile);
            }
        });
        links.getChildren().add(settingsLink);
        // When a newer release is known, an "Update available: X.Y.Z" row linking to that release.
        if (update != null) {
            String updateUrl =
                    update.url() == null || update.url().isBlank() ? com.editora.AppInfo.RELEASES_PAGE : update.url();
            Hyperlink updateLink = aboutLink(tr("about.updateAvailable") + " " + update.version(), () -> {});
            updateLink.getStyleClass().add("about-update");
            updateLink.setOnAction(e -> {
                alert.close();
                if (openUrl != null) {
                    openUrl.accept(updateUrl);
                }
            });
            links.getChildren().add(updateLink);
        }

        // --- environment block ------------------------------------------------------------------------
        String details = environmentDetails(versionString, commit, settingsFile);
        Label env = new Label(details);
        env.getStyleClass().add("about-env");

        // --- footer: Copy details (in the content, so it does not dismiss) --------------------------
        Button copyDetails = new Button(tr("about.copyDetails"));
        copyDetails.setOnAction(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(details);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            copyDetails.setText(tr("about.copied"));
        });
        HBox footer = new HBox(copyDetails);
        footer.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(14, header, copyright, links, env, footer);
        content.getStyleClass().add("about-content");
        alert.getDialogPane().setContent(content);
        // A single Close button; "Copy details" lives in the content so pressing it keeps the panel open.
        alert.getButtonTypes()
                .setAll(new ButtonType(tr("dialog.close"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE));
        alert.showAndWait();
    }

    /** A borderless link row for the About panel. */
    private static Hyperlink aboutLink(String text, Runnable onClick) {
        Hyperlink link = new Hyperlink(text);
        link.setPadding(Insets.EMPTY);
        link.setOnAction(e -> onClick.run());
        return link;
    }

    /** The monospace environment block, also what "Copy details" puts on the clipboard. */
    private static String environmentDetails(String versionString, String commit, Path settingsFile) {
        StringBuilder sb = new StringBuilder();
        sb.append(com.editora.AppInfo.NAME)
                .append(' ')
                .append(versionString)
                .append(" (")
                .append(tr("about.built", com.editora.AppInfo.buildTime()))
                .append(")\n");
        if (commit != null && !commit.isBlank()) {
            sb.append(tr("about.commit", commit)).append('\n');
        }
        sb.append("Java: ")
                .append(System.getProperty("java.version", "?"))
                .append(" (")
                .append(System.getProperty("java.vendor", "?"))
                .append(")\n");
        sb.append("JavaFX: ")
                .append(System.getProperty("javafx.runtime.version", "?"))
                .append('\n');
        sb.append("OS: ")
                .append(System.getProperty("os.name", "?"))
                .append(' ')
                .append(System.getProperty("os.version", "?"))
                .append(" (")
                .append(System.getProperty("os.arch", "?"))
                .append(")\n");
        Path configDir = settingsFile == null ? null : settingsFile.getParent();
        if (configDir != null) {
            sb.append("Config: ").append(displaySettingsPath(configDir));
        }
        return sb.toString();
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

    /** Friendly label for an inlay-hint filter mode ({@code literals}/{@code all}); shared with the palette picker. */
    public static String inlayHintModeName(String id) {
        return "all".equalsIgnoreCase(id) ? tr("settings.inlayHintMode.all") : tr("settings.inlayHintMode.literals");
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
