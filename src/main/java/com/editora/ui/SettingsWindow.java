package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.beans.binding.Bindings;
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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.editora.build.BuildTool;
import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.ControlNames;
import com.editora.editor.UiZoom;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.terminal.TerminalThemes;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.jspecify.annotations.Nullable;

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
    static final double PATH_FIELD_WIDTH = 320;
    /** Wide enough to show a whole {@code https://github.com/<owner>/<repository>.git}. */
    static final double REPO_URL_FIELD_WIDTH = 480;

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
    enum Category {
        // General
        APPEARANCE(tr("settings.cat.appearance"), Group.GENERAL, SettingsIcons::appearance),
        INTERFACE(tr("settings.cat.interface"), Group.GENERAL, SettingsIcons::interfaceWindow),
        TOOLBAR(tr("settings.cat.toolbar"), Group.GENERAL, SettingsIcons::toolbar),
        WORKSPACE(tr("settings.cat.workspace"), Group.GENERAL, SettingsIcons::workspace),
        TOOL_WINDOWS(tr("settings.cat.toolWindows"), Group.GENERAL, SettingsIcons::toolWindows),
        // Editor
        EDITOR(tr("settings.cat.editor"), Group.EDITOR, SettingsIcons::editor),
        COMPLETION(tr("settings.cat.completion"), Group.EDITOR, SettingsIcons::completion),
        SNIPPETS(tr("settings.cat.snippets"), Group.EDITOR, SettingsIcons::snippets),
        TEMPLATES(tr("settings.cat.templates"), Group.EDITOR, SettingsIcons::templates),
        TODO(tr("settings.cat.todo"), Group.EDITOR, SettingsIcons::todo),
        SPELL_CHECK(tr("settings.cat.spellCheck"), Group.EDITOR, SettingsIcons::spellCheck),
        SEARCH(tr("settings.cat.search"), Group.EDITOR, SettingsIcons::search),
        ABBREVIATIONS(tr("settings.cat.abbreviations"), Group.EDITOR, SettingsIcons::abbreviations),
        // AI (a master enable switch, then the two AI features it gates)
        AI_GENERAL(tr("settings.cat.aiGeneral"), Group.AI, SettingsIcons::ai, true),
        AGENT(tr("settings.cat.agent"), Group.AI, SettingsIcons::agent, true),
        AI(tr("settings.cat.ai"), Group.AI, SettingsIcons::aiActions, true),
        // Languages & Tools
        LSP(tr("settings.cat.lsp"), Group.LANGUAGES_TOOLS, SettingsIcons::lsp),
        DEBUG(tr("settings.cat.debug"), Group.LANGUAGES_TOOLS, SettingsIcons::debug),
        MARKDOWN(tr("settings.cat.markdown"), Group.LANGUAGES_TOOLS, SettingsIcons::markdown),
        MERMAID(tr("settings.cat.mermaid"), Group.LANGUAGES_TOOLS, SettingsIcons::mermaid),
        DIAGRAMS(tr("settings.cat.diagrams"), Group.LANGUAGES_TOOLS, SettingsIcons::diagrams),
        TYPST(tr("settings.cat.typst"), Group.LANGUAGES_TOOLS, SettingsIcons::typst),
        BUILD_TOOLS(tr("settings.cat.buildTools"), Group.LANGUAGES_TOOLS, SettingsIcons::buildTools),
        WEB(tr("settings.cat.web"), Group.LANGUAGES_TOOLS, SettingsIcons::web),
        EXTERNAL_TOOLS(tr("settings.cat.externalTools"), Group.LANGUAGES_TOOLS, SettingsIcons::externalTools),
        TERMINAL(tr("settings.cat.terminal"), Group.LANGUAGES_TOOLS, SettingsIcons::terminal),
        // Version control
        GIT(tr("settings.cat.git"), Group.VERSION_CONTROL, SettingsIcons::git),
        GITHUB(tr("settings.cat.github"), Group.VERSION_CONTROL, SettingsIcons::github),
        // System
        KEYMAPS(tr("settings.cat.keymaps"), Group.SYSTEM, SettingsIcons::keymaps),
        MACROS(tr("settings.cat.macros"), Group.SYSTEM, SettingsIcons::macros),
        REMOTE(tr("settings.cat.remote"), Group.SYSTEM, SettingsIcons::remote, true),
        ENCRYPTION(tr("settings.cat.encryption"), Group.SYSTEM, SettingsIcons::encryption),
        PLUGINS(tr("settings.cat.plugins"), Group.SYSTEM, SettingsIcons::plugins),
        SYNC(tr("settings.cat.sync"), Group.SYSTEM, SettingsIcons::sync, true),
        MCP(tr("settings.cat.mcp"), Group.SYSTEM, SettingsIcons::mcp, true),
        ADVANCED(tr("settings.cat.advanced"), Group.SYSTEM, SettingsIcons::advanced);

        final String display;
        final Group group;
        /** A fresh copy of the glyph shown left of the name (see {@link SettingsIcons}). */
        final Supplier<Node> icon;
        /** Whether the feature is still beta — the sidebar shows a small "Beta" pill beside its name. */
        final boolean beta;

        Category(String display, Group group, Supplier<Node> icon) {
            this(display, group, icon, false);
        }

        Category(String display, Group group, Supplier<Node> icon, boolean beta) {
            this.display = display;
            this.group = group;
            this.icon = icon;
            this.beta = beta;
        }
    }

    /** Finds and filters the rows below (built with the pages); see {@link SettingsSearch}. */
    private SettingsSearch search;

    private final SettingsFileNotes fileNotes = new SettingsFileNotes(); // Advanced: hand edits of settings.json

    private Label searchEmpty; // "no settings match …", shown in place of the page

    private final ConfigManager config;
    private final Consumer<Settings> onApply;
    private final Consumer<Boolean> onToggleZen;
    private final Consumer<Boolean> onToggleExpert;
    private final Consumer<Path> onOpenFile;
    private final Runnable onExportConfig;
    /** Runs a command by id (Import Configuration…, Python: Select Interpreter); null until wired. */
    @Nullable
    Consumer<String> runCommand;

    void setRunCommand(Consumer<String> runCommand) {
        this.runCommand = runCommand;
    }

    private final Runnable onShowDebugLog;

    /** The Workspace page's trusted-folder list + its backing store accessor (null until wired). */
    private final ListView<String> trustedFoldersList = new ListView<>();

    private @Nullable TrustActions trustActions;

    private final ToolWindowManager toolWindows;
    private final com.editora.git.GitService gitService;
    final com.editora.github.GitHubService githubService;
    private final java.util.List<BuildCoordinator> buildCoordinators;
    private final com.editora.lsp.LspManager lspManager;
    private final com.editora.dap.DapManager dapManager;
    final Stage stage = new Stage();

    // --- controls (same set as before, regrouped into pages) ---
    private ComboBox<String> languageCombo;
    private VBox languageControl; // the combo + its "restart to apply" note

    @Nullable
    ShortcutActions shortcutActions; // keybinding-editor backend (→ MainController)

    private ComboBox<String> fontFamily;
    private Spinner<Integer> fontSize;
    private ComboBox<String> themeCombo;
    private Spinner<Integer> uiZoom;
    private ComboBox<String> editorThemeCombo;
    /** "Text zoom is 160% …  Reset zoom", in its own row under Font size; shown only while zoomed. */
    private @Nullable HBox zoomNote;

    private Spinner<Integer> tabSizeSpinner;
    private Spinner<Integer> fillColumnSpinner;
    private Spinner<Integer> largeFileThresholdSpinner;
    private ComboBox<String> indentStyleCombo;
    private CheckBox columnRulerCheck;
    private CheckBox lineHighlightCheck;
    private CheckBox lineNumbersCheck;
    private CheckBox minimapCheck;
    private CheckBox wordWrapCheck;
    private CheckBox wordWrapProseCheck;
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
    private CheckBox lightbulbCheck;
    private CheckBox problemCounterCheck;
    private ComboBox<String> inlayHintModeCombo;
    private CheckBox onTypeFormattingCheck;
    private CheckBox javaOrganizeImportsOnSaveCheck;
    private CheckBox javaFormatOnSaveCheck;
    private CheckBox javaCleanupOnSaveCheck;
    /** "4 chosen": how many clean-ups Clean Up File and clean-up on save run. */
    private final Label javaCleanupsCount = new Label();

    private CheckBox pasteImportsCheck;
    private CheckBox smartSemicolonCheck;

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
    CheckBox markdownFormatBarCheck;
    CheckBox lspInstallPromptsCheck;
    CheckBox markdownLintCheck;
    CheckBox mathSupportCheck;
    private CheckBox editorConfigCheck;
    private CheckBox logViewerCheck;
    private CheckBox testRunnerCheck;
    private CheckBox buildBeforeRunCheck;
    /** Settings → LSP: lemminx-maven may search Maven Central when completing coordinates (Maven C10, D13). */
    CheckBox mavenCentralIndexCheck;

    CheckBox javaDownloadSourcesCheck;

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
    private CheckBox htmlMarkupChecksCheck;
    private CheckBox todoHighlightCheck;
    private @Nullable TodoSettingsPane todoSettings;
    /** The External Tools page's editor (see {@link ExternalToolsEditor}); null until that page is built. */
    private @Nullable ExternalToolsEditor externalToolsEditor;

    private @Nullable ToolbarSettingsPane toolbarSettings;
    private @Nullable ToolbarActions toolbarActions; // → MainController/ToolbarCoordinator
    private @Nullable Runnable refreshToolbarLists; // re-reads the effective layout when the page is shown

    private @Nullable AbbreviationsEditor abbreviationsEditor;
    private @Nullable CheckBox abbrevModeCheck;

    /** The Remote page's site editor; null until that page is built. */
    private @Nullable RemoteSitesPane remoteSites;
    /** Opens the Connect form pre-filled with a saved site (the Remote page's Connect… button). */
    private @Nullable Consumer<com.editora.vfs.RemoteConnection> onConnectRemote;

    void setRemoteConnect(Consumer<com.editora.vfs.RemoteConnection> connect) {
        this.onConnectRemote = connect;
    }

    /** Hooks used by the separate Run Configurations window; kept here as the window-service owner. */
    private Runnable onRunConfigsChanged = () -> {};

    private java.util.function.@Nullable Supplier<String> runConfigSuggestion;
    private java.util.function.@Nullable Supplier<com.editora.config.RunConfiguration> runConfigScriptSuggestion;
    private java.util.function.@Nullable Supplier<com.editora.config.RunConfiguration> runConfigTypeSuggestion;
    private java.util.function.@Nullable Supplier<java.nio.file.Path> runConfigProjectRoot;
    private @Nullable RunConfigurationsWindow runConfigurationsWindow;

    CheckBox snippetTabExpansionCheck;
    private @Nullable CheckBox remoteExecutionCheck;
    private @Nullable TextField containerEngineField;

    private CheckBox multiCaretCheck;
    private CheckBox copyLineNoSelectionCheck;
    private CheckBox copyWithHighlightingCheck;
    private CheckBox projectsCheck;
    private CheckBox updateCheckCheck;
    private CheckBox localHistoryCheck;
    private CheckBox crashRecoveryCheck;
    private Spinner<Integer> historyMaxPerFileSpinner;
    private Spinner<Integer> historyMaxAgeSpinner;
    private Spinner<Integer> historyMaxTotalSpinner;

    private final java.util.Map<BuildTool, CheckBox> buildToolChecks = new java.util.EnumMap<>(BuildTool.class);
    private final java.util.Map<BuildTool, TextField> buildToolCommandFields = new java.util.EnumMap<>(BuildTool.class);
    private final ComboBox<JdkChoice> mavenJdkCombo = new ComboBox<>();
    private final TextField mavenArchetypeCatalogField = new TextField();
    private final java.util.Map<BuildTool, Label> buildToolStatusLabels = new java.util.EnumMap<>(BuildTool.class);
    private CheckBox ripgrepCheck;
    private TextField encryptionGpgPathField;
    private @Nullable Label encryptionGpgStatus;
    private CheckBox encryptionIncludeSelfCheck;
    private ComboBox<String> encryptionPromptCombo;
    private CheckBox encryptionLspCheck;
    /** Bumped per gpg probe, so a slow answer for a path the user has since changed is dropped. */
    private int encryptionProbeGen;

    private CheckBox searchGitignoreCheck;
    private TextField ripgrepCommandField;
    private @Nullable Label ripgrepStatusLabel;
    /** Injected probe (MainController): runs {@code rg --version} off-thread, delivers found/not-found on FX. */
    private java.util.function.@Nullable Consumer<java.util.function.Consumer<Boolean>> ripgrepProbe;

    @Nullable
    Runnable onKeymapChanged; // → MainController: reload the shared keymap live

    /** Install buttons keyed by install-language ({@code java}/{@code python}/{@code javascript}/{@code mermaid}). */
    final java.util.Map<String, Button> installButtons = new java.util.HashMap<>();
    /** Injected by MainController: runs {@code InstallCoordinator.installSupport} for the given language key. */
    private java.util.function.@Nullable Consumer<String> onInstallSupport;

    private CheckBox zenCheck;
    private CheckBox expertCheck;
    private @Nullable ToolWindowsSettingsPane toolWindowSettings;
    /** The Tool Windows page's card for each side; its rows are laid out again after every change. */
    private final Map<ToolWindow.Side, Card> toolWindowCards = new EnumMap<>(ToolWindow.Side.class);

    private ComboBox<String> autoSaveCombo;
    private CheckBox pdfLineNumbersCheck;
    private CheckBox pdfHighlightCheck;
    private CheckBox pdfPageFooterCheck;
    private ComboBox<String> pdfPageSizeCombo;
    private ComboBox<String> pdfOrientationCombo;
    private ComboBox<String> pdfMarginsCombo;
    private ComboBox<Integer> pdfCodeFontSizeCombo;
    private Spinner<Integer> autoSaveDelaySpinner;

    // --- shell ---
    ListView<Object> sidebar; // mixed rows: Group headers + Category items
    private ScrollPane contentScroll;
    private TextField searchField;
    private final Map<Category, Region> pages = new EnumMap<>(Category.class);
    final List<SettingsSearch.Row> rows = new ArrayList<>();
    private final List<Label> sectionLabels = new ArrayList<>();
    /** Card containers (UI Kit v1), hidden by the search filter once all of their rows are. */
    private final List<VBox> cards = new ArrayList<>();

    private final Set<Category> searchHiddenCats = EnumSet.noneOf(Category.class);
    private final Set<Group> searchHiddenGroups = EnumSet.noneOf(Group.class);

    // --- live preview ---
    private CodeArea preview;
    private @Nullable String currentPreviewCss; // editor-theme override sheet on the settings scene, or null
    private static final String PREVIEW_SAMPLE = """
            public class Greeter {
                // Editora live preview
                public static void main(String[] args) {
                    String name = "world";
                    System.out.println("Hello, " + name + "!");
                }
            }""";

    private boolean built;
    boolean loading;

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
        this.buildCoordinators = buildCoordinators;
        this.lspManager = lspManager;
        this.dapManager = dapManager;
        this.onApply = onApply;
        this.onToggleZen = onToggleZen;
        this.onToggleExpert = onToggleExpert;
        this.onOpenFile = onOpenFile;
        this.onExportConfig = onExportConfig;
        this.onShowDebugLog = onShowDebugLog;
        this.syncPage = new SettingsSyncPage(this, config);
        this.pluginsPage = new SettingsPluginsPage(this, config);
        this.aiPages = new SettingsAiPages(this, config);
        this.debugPage = new SettingsDebugPage(this, config, dapManager);
        this.lspPage = new SettingsLspPage(this, config, lspManager);
        this.keymapsPage = new SettingsKeymapsPage(this, config);
        this.snippetPages = new SettingsSnippetPages(this, config);
        this.markdownPage = new SettingsMarkdownPage(this, config);
        this.gitPages = new SettingsGitPages(this, config, gitService, githubService);
        this.renderPages = new SettingsRenderToolPages(this, config, mermaidService, diagramService, typstService);
        this.spellPage = new SettingsSpellPage(this, config);
        this.webPage = new SettingsWebPage(this, config);
        if (com.editora.AppInfo.isSnapshot()) {
            // Start the (background, bounded) branch lookup now, so About has it without ever waiting on git.
            com.editora.AppInfo.gitBranchAsync();
        }
    }

    /** Settings → Plugins; see {@link SettingsPluginsPage}. */
    private final SettingsPluginsPage pluginsPage;

    /**
     * Injects the shared {@link com.editora.plugin.PluginManager} (set after construction by
     * {@code MainController}). The Plugins page lists its discovered descriptors; safe to call before
     * {@link #build} (the page is built lazily on first {@link #show}).
     */
    public void setPluginManager(com.editora.plugin.@Nullable PluginManager pluginManager) {
        pluginsPage.setPluginManager(pluginManager);
    }

    /** Where a message about something this window did goes: the owning window's status bar. */
    public void setStatusSink(Consumer<String> onStatus) {
        this.onStatus = onStatus;
    }

    private @Nullable Consumer<String> onStatus;

    /**
     * Changes the three Local History retention limits on behalf of this window's spinners. Lowering one
     * deletes revisions, so the implementation ({@code HistoryCoordinator.changeLimits}) writes the settings
     * only once it knows nothing would go or the user confirmed what would; {@code done} gets whether it did.
     */
    @FunctionalInterface
    public interface HistoryLimits {
        void change(int maxPerFile, int maxAgeDays, int maxTotalMb, Window owner, Consumer<Boolean> done);
    }

    public void setHistoryLimits(HistoryLimits historyLimits) {
        this.historyLimits = historyLimits;
    }

    private @Nullable HistoryLimits historyLimits;

    /** Opens Settings focused on the Snippets page (the {@code snippets.manage} command). */
    public void showSnippets(Window owner) {
        showAt(Category.SNIPPETS, owner);
    }

    /** Opens Settings on the Sync page (the {@code sync.setup} command and the status-bar marker). */
    public void showSync(Window owner) {
        showAt(Category.SYNC, owner);
    }

    public void showAbbreviations(Window owner) {
        showAt(Category.ABBREVIATIONS, owner);
    }

    /** Opens Settings focused on the Toolbar customization page (the {@code view.customizeToolbar} command). */
    public void showToolbar(Window owner) {
        showAt(Category.TOOLBAR, owner);
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

    /** Opens Settings focused on the Templates page (the {@code template.manage} command). */
    public void showTemplates(Window owner) {
        showAt(Category.TEMPLATES, owner);
    }

    /** Wires the Plugins-page actions to the controller (browse registry / install zip / uninstall). */
    public void setPluginActions(
            Runnable onBrowse,
            Runnable onInstallFromFile,
            Consumer<com.editora.plugin.PluginDescriptor> onUninstall,
            java.util.function.Predicate<com.editora.plugin.PluginDescriptor> onEnable,
            Consumer<com.editora.plugin.PluginDescriptor> onDisable) {
        pluginsPage.setPluginActions(onBrowse, onInstallFromFile, onUninstall, onEnable, onDisable);
    }

    /** Opens Settings focused on the Workspace page (the {@code workspace.manageTrust} command). */
    public void showWorkspace(Window owner) {
        showAt(Category.WORKSPACE, owner);
    }

    /** Opens the project/session-scoped Run Configurations window. */
    public void showRunConfigs(String selectName, Window owner) {
        if (runConfigurationsWindow == null) {
            runConfigurationsWindow = new RunConfigurationsWindow(
                    config,
                    () -> runConfigSuggestion == null ? null : runConfigSuggestion.get(),
                    () -> runConfigScriptSuggestion == null ? null : runConfigScriptSuggestion.get(),
                    () -> runConfigTypeSuggestion == null ? null : runConfigTypeSuggestion.get(),
                    () -> runConfigProjectRoot == null ? null : runConfigProjectRoot.get(),
                    onRunConfigsChanged);
            runConfigurationsWindow.setEditDeployments(editDeployments);
        }
        runConfigurationsWindow.show(selectName, owner);
    }

    /** What the Run Configurations window's Run on → Deployments… opens. */
    private java.util.function.@Nullable Consumer<String> editDeployments;

    public void setEditDeployments(java.util.function.Consumer<String> editDeployments) {
        this.editDeployments = editDeployments;
        if (runConfigurationsWindow != null) {
            runConfigurationsWindow.setEditDeployments(editDeployments);
        }
    }

    /** The project's deployments changed: the Run Configurations window's Run on choices follow. */
    public void refreshRunConfigDeployments() {
        if (runConfigurationsWindow != null) {
            runConfigurationsWindow.refreshDeployments();
        }
    }

    /** Supplies the active Java main class used to seed a newly added configuration. */
    public void setRunConfigSuggestion(java.util.function.Supplier<String> suggestion) {
        this.runConfigSuggestion = suggestion;
    }

    /**
     * Supplies a configuration for the active Python or shell script (Add proposes it before a Java one) and
     * the project root a browsed script is made relative to.
     */
    public void setRunConfigScriptSuggestion(
            java.util.function.Supplier<com.editora.config.RunConfiguration> suggestion,
            java.util.function.Supplier<java.nio.file.Path> projectRoot) {
        this.runConfigScriptSuggestion = suggestion;
        this.runConfigProjectRoot = projectRoot;
    }

    /**
     * Supplies a blank configuration of the type that launches the active file (a makefile, a
     * {@code package.json}): what Add starts from when neither a script nor a main class is on offer.
     */
    public void setRunConfigTypeSuggestion(
            java.util.function.Supplier<com.editora.config.RunConfiguration> suggestion) {
        this.runConfigTypeSuggestion = suggestion;
    }

    /**
     * Opens Settings focused on the page a Doctor row's "Settings…" action targets. The keys are the
     * Doctor check catalog's stable {@code settingsKey} strings, mapped here so the private
     * {@link Category} enum never leaks; an unknown key just opens Settings unfocused.
     */
    public void showDoctorTarget(String key, Window owner) {
        Category target = switch (key == null ? "" : key) {
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
            case "remote" -> Category.REMOTE;
            case "encryption" -> Category.ENCRYPTION;
            case "plugins" -> Category.PLUGINS;
            default -> null;
        };
        showAt(target, owner);
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

    /** Opens a web page (the Diagrams page's "How to install"); injected by MainController. */
    @Nullable
    Consumer<String> openUrl;

    public void setOpenUrl(Consumer<String> openUrl) {
        this.openUrl = openUrl;
    }

    /** Re-probes tool detection on the LSP/Debugger/Mermaid pages (labels + Install buttons) — called by
     *  MainController after an in-app install completes so the now-installed tools flip to "Installed". */
    public void refreshDetectionStatus() {
        lspPage.refreshLspStatus();
        debugPage.refreshDebugStatus();
        renderPages.refreshMermaidStatus();
        gitPages.refreshGithubStatus();
        refreshBuildToolStatus();
        aiPages.refreshAgentClientStatus();
    }

    public void show(Window owner) {
        if (!stage.isShowing()) {
            gitProbedFor = null; // git may have been installed or removed while the window was closed
        }
        if (!built) {
            build(owner);
            built = true;
        }
        load();
        reloadFileBackedEditors();
        // The keymap may have changed while the window was closed (or, the first time, the chips were
        // created after the backend was injected and are still empty).
        keymapsPage.refreshShortcuts();
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            if (!placed) { // later opens come back where the user left the window
                centerOnOwner(owner);
                placed = true;
            }
            stage.show();
        }
        // A search left in the box is kept, but selected: typing replaces it rather than appending to it.
        searchField.requestFocus();
        searchField.selectAll();
    }

    /**
     * Opens Settings on {@code target} (null: wherever it was). A search left in the box from an earlier visit
     * is cleared first — it would hide every row of the page this command asked for.
     */
    private void showAt(@Nullable Category target, Window owner) {
        show(owner);
        searchField.clear();
        if (target != null) {
            sidebar.getSelectionModel().select(target);
        }
    }

    private boolean placed; // centred once; afterwards the window keeps the place the user gave it

    /** Centres on the owner using the size the window actually has, kept on the owner's screen. */
    private void centerOnOwner(Window owner) {
        javafx.geometry.Dimension2D size = preferredSize(owner);
        WindowPlacement.centerOnOwner(
                stage,
                owner,
                Double.isNaN(stage.getWidth()) ? size.getWidth() : stage.getWidth(),
                Double.isNaN(stage.getHeight()) ? size.getHeight() : stage.getHeight());
    }

    @Initializer
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
        search =
                new SettingsSearch(rows, sectionLabels, cards, List.of(Category.values()), c -> ((Category) c).display);
        if (keymapsPage.shortcutFilter != null) {
            search.setListSource(new SettingsSearch.ListSource(
                    Category.KEYMAPS, keymapsPage.shortcutFilter, keymapsPage::shortcutMatches));
        }
        modified = new SettingsModified(controlOwners());

        searchField = new TextField();
        searchField.setPromptText(tr("settings.search.prompt"));
        searchField.setAccessibleText(tr("settings.search.prompt"));
        searchField.getStyleClass().add("settings-search");
        searchField.textProperty().addListener((o, a, b) -> filter(b));

        sidebar = new ListView<>();
        sidebar.getStyleClass().add("settings-sidebar");
        sidebar.getItems().setAll(sidebarItems());
        UiZoom.minWidth(UiZoom.prefWidth(sidebar, 216), 216);
        sidebar.setCellFactory(v -> new CategoryCell());
        sidebar.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b instanceof Category) { // group headers aren't pages
                showContent();
                if (b == Category.MARKDOWN) {
                    markdownPage.refreshMarkdownLintConfigNote(); // the active file may have changed since last shown
                }
                // Every page starts at its top. A ScrollPane keeps its vvalue across a content swap, so
                // opening a short page after scrolling down a long one landed mid-page — and on a page
                // that fits, silently nowhere at all. Deferred: the new content has not been laid out
                // yet, and setting vvalue before that is undone when the scroll range is recomputed.
                contentScroll.setVvalue(0);
                javafx.application.Platform.runLater(() -> {
                    contentScroll.setVvalue(0);
                    // A command or a search can pick a page far down the list: keep its row in view.
                    revealSidebarRow(sidebar.getSelectionModel().getSelectedIndex());
                });
            }
        });

        searchEmpty = new Label();
        searchEmpty.getStyleClass().add("settings-search-empty");
        searchEmpty.setWrapText(true);
        searchEmpty.setVisible(false);

        contentScroll = new ScrollPane() {
            // A page is never wider than the viewport beside a vertical scrollbar, so a short page keeps the
            // room a long page's scrollbar takes and the cards' right edges (and the switches on them) sit at
            // the same x on every page. The cap is set here, before the skin lays out, not from the bar's
            // visibility: the skin flips that inside this same pass, and the relayout a padding change then
            // asked for could be lost — the MCP page ended a scrollbar's width further right on macOS.
            @Override
            protected void layoutChildren() {
                if (getContent() instanceof Region page) {
                    page.setMaxWidth(Math.max(0, getWidth() - snappedLeftInset() - snappedRightInset() - barWidth()));
                }
                super.layoutChildren();
            }

            private double barWidth() {
                for (Node n : getChildrenUnmodifiable()) {
                    if (n instanceof javafx.scene.control.ScrollBar bar
                            && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                        return snapSizeX(bar.prefWidth(-1));
                    }
                }
                return 0; // no skin yet
            }
        };
        contentScroll.setFitToWidth(true);
        contentScroll.getStyleClass().add("settings-content");
        HBox.setHgrow(contentScroll, Priority.ALWAYS);

        // The kit puts the search at the top of the nav rail, not spanning the window: search scopes the
        // sidebar (it greys categories and auto-selects the first hit), so it lives with the sidebar.
        modifiedOnly.setTooltip(ControlNames.tooltip(tr("settings.search.modifiedOnly.tip")));
        modifiedOnly.setAccessibleHelp(tr("settings.search.modifiedOnly.tip"));
        modifiedOnly.setOnAction(e -> filter(searchField.getText()));
        modifiedOnly.getStyleClass().add(atlantafx.base.theme.Styles.SMALL); // under the search: it filters it
        VBox nav = UiZoom.spacing(new VBox(searchField, modifiedOnly, sidebar), 8);
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
        // Said once, for every page: there is nothing to confirm (it used to be a note on the Git page only).
        Label live = new Label(tr("settings.liveNote"));
        live.getStyleClass().add("settings-hint");
        // A restart-only setting changed this run: say so, with the way to apply it, in place of the live note.
        restartNote.getStyleClass().add("settings-restart-note");
        Button restartNow = new Button(tr("settings.restart.now"));
        restartNow.setOnAction(e -> {
            if (onRestart != null) {
                onRestart.run();
            }
        });
        restartBox.getChildren().setAll(restartNote, restartNow);
        restartBox.setAlignment(Pos.CENTER_LEFT);
        live.visibleProperty().bind(restartBox.visibleProperty().not());
        live.managedProperty().bind(live.visibleProperty());
        SettingsSearch.setShown(restartBox, false);
        HBox buttons = UiZoom.spacing(new HBox(footReset, spacer, restartBox, live, close), 8);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.getStyleClass().add("settings-footer");

        VBox root = new VBox(0, scopeUi.bar(), body, buttons);
        scopeUi.setRows(() -> rows.stream().map(SettingsSearch.Row::node).toList());
        registerScopedControls();
        javafx.geometry.Dimension2D size = preferredSize(owner);
        root.setPrefWidth(size.getWidth());
        root.setPrefHeight(size.getHeight());

        Scene scene = new Scene(root, size.getWidth(), size.getHeight());
        // The live preview needs the editor surface + token colors; the dialog controls keep AtlantaFX.
        scene.getStylesheets()
                .addAll(
                        com.editora.editor.UiZoom.sheet(SettingsWindow.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm()),
                        com.editora.editor.UiZoom.sheet(SettingsWindow.class
                                .getResource("/com/editora/styles/syntax.css")
                                .toExternalForm()));
        SettingsKeys.install(
                scene,
                stage,
                searchField,
                sidebar,
                i -> sidebar.getItems().get(i) instanceof Category c && !searchHiddenCats.contains(c),
                item -> item instanceof Group);
        stage.setScene(scene);
        // The list-and-form pages commit a field when it loses the focus, and closing the window does not
        // take the focus away: text typed last (an abbreviation's expansion, a site's host) was dropped.
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDING, e -> root.requestFocus());
        // Floor the window size: the rows wrap, but the list-beside-form pages need MIN_WIDTH.
        // Never wider than a small screen allows.
        UiZoom.follow(stage, () -> stage.setMinWidth(Math.min(UiZoom.px(MIN_WIDTH), size.getWidth())));
        UiZoom.follow(stage, () -> stage.setMinHeight(Math.min(UiZoom.px(MIN_HEIGHT), size.getHeight())));

        sidebar.getSelectionModel().select(Category.APPEARANCE);
    }

    private void revealSidebarRow(int index) {
        SettingsKeys.reveal(sidebar, index, item -> item instanceof Group);
    }

    /**
     * {@link #WIDTH}×{@link #HEIGHT}, clamped to {@link #MAX_SCREEN_FRACTION} of the <em>visual</em> bounds
     * (which exclude the menu bar / taskbar) of the screen the owner is on. Without the clamp the window
     * would open taller than a laptop display and hide its own Close button.
     */
    private static javafx.geometry.Dimension2D preferredSize(Window owner) {
        return WindowPlacement.clampSize(
                UiZoom.px(WIDTH), UiZoom.px(HEIGHT), WindowPlacement.screenOf(owner), MAX_SCREEN_FRACTION);
    }

    // --- control construction (logic unchanged from the flat window) -----------------------------

    private void buildControls() {
        languageCombo = new ComboBox<>();
        languageCombo.getItems().add(""); // "" = automatic (system language)
        languageCombo.getItems().addAll(com.editora.i18n.Messages.available().keySet());
        UiZoom.prefWidth(languageCombo, 220);
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
        });
        // No dialog per change (the arrow keys change a closed combo one language at a time): the row says
        // it, for as long as the choice resolves to another language than the one this run uses. (The system
        // language is user.language: start-up can change the default Locale, so that is not asked.)
        Label restartNote = new Label(tr("dialog.language.restart"));
        restartNote.getStyleClass().addAll("settings-row-desc", "settings-restart-note");
        restartNote
                .visibleProperty()
                .bind(Bindings.createBooleanBinding(
                        () -> languageCombo.getValue() != null
                                && !com.editora.i18n.Messages.resolve(
                                                languageCombo.getValue(),
                                                com.editora.i18n.Messages.available()
                                                        .keySet(),
                                                System.getProperty("user.language"))
                                        .equals(com.editora.i18n.Messages.current()),
                        languageCombo.valueProperty()));
        restartNote.managedProperty().bind(restartNote.visibleProperty());
        languageControl = UiZoom.spacing(new VBox(languageCombo, restartNote), 4);
        languageControl.setAlignment(Pos.CENTER_RIGHT);

        keymapsPage.buildControls();

        fontFamily = new ComboBox<>();
        fontFamily.getItems().setAll(fontFamilyChoices());
        UiZoom.prefWidth(fontFamily, 220);
        fontFamily.setCellFactory(v ->
                labelledCell(name -> Fonts.BUNDLED.contains(name) ? tr("settings.fontFamily.bundled", name) : name));
        fontFamily.valueProperty().addListener((obs, old, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setFontFamily(now);
            apply();
        });

        fontSize = IntSpinners.editable(Settings.MIN_FONT_SIZE, Settings.MAX_FONT_SIZE, 14, 1);
        UiZoom.prefWidth(fontSize, 90);
        fontSize.valueProperty().addListener((obs, old, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setFontSize(now);
            apply();
        });

        uiZoom = IntSpinners.editable(
                Settings.MIN_UI_ZOOM, Settings.MAX_UI_ZOOM, Settings.DEFAULT_UI_ZOOM, com.editora.editor.UiZoom.STEP);
        UiZoom.prefWidth(uiZoom, 90);
        uiZoom.valueProperty().addListener((obs, old, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setUiZoom(now);
            Themes.applyUiZoom(config.getSettings().getUiZoom());
            apply();
        });

        themeCombo = new ComboBox<>();
        themeCombo.getItems().setAll(appThemeChoices());
        UiZoom.prefWidth(themeCombo, 220);
        ThemeGroups.install(themeCombo, name -> name);
        themeCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null || ThemeGroups.isHeading(now)) {
                return;
            }
            config.getSettings().setTheme(now);
            Themes.applyAppTheme(now);
            if (!config.getSettings().isEditorThemeUserSet()) {
                config.getSettings().setEditorTheme(EditorThemes.defaultFor(now));
                applyPreviewTheme(config.getSettings().getEditorTheme());
            }
            apply();
        });

        // The first item, "", is "Automatic": the editor theme follows the app theme, as on a fresh install.
        // Once another was picked there used to be no way back short of resetting every setting.
        editorThemeCombo = new ComboBox<>();
        editorThemeCombo.getItems().setAll(editorThemeChoices());
        UiZoom.prefWidth(editorThemeCombo, 220);
        ThemeGroups.install(editorThemeCombo, SettingsWindow::editorThemeLabel);
        editorThemeCombo.setButtonCell(editorThemeCell());
        editorThemeCombo.valueProperty().addListener((obs, was, now) -> {
            if (ThemeGroups.isHeading(now)) {
                return; // passing over a group title on the way to the theme below it
            }
            if (now != null) {
                applyPreviewTheme(
                        now.isEmpty()
                                ? EditorThemes.defaultFor(config.getSettings().getTheme())
                                : now);
            }
            if (loading || now == null) {
                return;
            }
            boolean automatic = now.isEmpty();
            config.getSettings()
                    .setEditorTheme(
                            automatic
                                    ? EditorThemes.defaultFor(
                                            config.getSettings().getTheme())
                                    : now);
            config.getSettings().setEditorThemeUserSet(!automatic);
            apply();
        });

        tabSizeSpinner = IntSpinners.editable(Settings.MIN_TAB_SIZE, Settings.MAX_TAB_SIZE, 4, 1);
        UiZoom.prefWidth(tabSizeSpinner, 90);
        tabSizeSpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null || scopeUi.write("editor.tabSize", now)) {
                return;
            }
            config.getSettings().setTabSize(now);
            apply();
        });

        fillColumnSpinner = IntSpinners.editable(
                Settings.MIN_FILL_COLUMN, Settings.MAX_FILL_COLUMN, com.editora.editops.Filler.DEFAULT_FILL_COLUMN, 1);
        UiZoom.prefWidth(fillColumnSpinner, 90);
        fillColumnSpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setFillColumn(now);
            apply();
        });

        // Line count above which the minimap + LSP auto-disable (highlighting + editing stay); 0 = never.
        largeFileThresholdSpinner = IntSpinners.editable(0, Settings.MAX_LARGE_FILE_THRESHOLD, 10_000, 1000);
        UiZoom.prefWidth(largeFileThresholdSpinner, 120);
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
        wordWrapCheck = viewCheck(tr("settings.wordWrap"), (st, on) -> {
            if (!scopeUi.write("editor.wordWrap", on)) {
                st.setWordWrap(on);
            }
        });
        wordWrapProseCheck = viewCheck(tr("settings.wordWrapProse"), (st, on) -> {
            if (!scopeUi.write("editor.wordWrapProse", on)) {
                st.setWordWrapProse(on);
            }
        });
        markdownPage.buildMarkdownControls();
        adminSaveCheck = viewCheck(tr("settings.adminSave"), Settings::setAdminSave);
        // Windows has no prompt Editora can elevate one save through. The row stays, disabled, and its note says so.
        adminSaveCheck.setDisable(!com.editora.process.ElevatedSave.supportedOnOs(com.editora.Os.current()));
        whitespaceCheck = viewCheck(tr("settings.showWhitespace"), Settings::setShowWhitespace);
        notesCheck = viewCheck(tr("settings.enableNotes"), Settings::setNotesSupport);
        noteIndicatorsCheck = viewCheck(tr("settings.showNoteIndicators"), Settings::setShowNoteIndicators);
        // The note-indicator toggle is only meaningful while Personal Notes is enabled.
        notesCheck.selectedProperty().addListener((obs, was, now) -> {
            refreshToolWindowRows(); // reflect on the Tool Windows page's Personal Notes row
        });
        autocompleteCheck = viewCheck(tr("settings.enableAutocomplete"), Settings::setAutocomplete);
        autocompleteProseCheck = viewCheck(tr("settings.autocomplete.prose"), Settings::setAutocompleteProse);
        autocompleteSnippetsCheck = viewCheck(tr("settings.autocomplete.snippets"), Settings::setAutocompleteSnippets);
        snippetTabExpansionCheck = viewCheck(tr("settings.snippet.tabExpansion"), Settings::setSnippetTabExpansion);
        autocompleteMermaidCheck = viewCheck(tr("settings.autocomplete.mermaid"), Settings::setAutocompleteMermaid);
        completionDocCheck = viewCheck(tr("settings.completionDoc"), Settings::setCompletionDoc);
        semanticHighlightCheck = viewCheck(tr("settings.semanticHighlight"), Settings::setSemanticHighlight);
        inlayHintsCheck = viewCheck(tr("settings.inlayHints"), Settings::setInlayHints);
        codeLensCheck = viewCheck(tr("settings.codeLens"), Settings::setCodeLens);
        lightbulbCheck = viewCheck(tr("settings.lspLightbulb"), Settings::setLspLightbulb);
        problemCounterCheck = viewCheck(tr("settings.problemCounter"), Settings::setProblemCounter);
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
        UiZoom.minWidth(inlayHintModeCombo, 170); // no fixed width: it cut off the longer (translated) items
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
        javaOrganizeImportsOnSaveCheck =
                viewCheck(tr("settings.javaOrganizeImportsOnSave"), Settings::setJavaOrganizeImportsOnSave);
        javaFormatOnSaveCheck = viewCheck(tr("settings.javaFormatOnSave"), Settings::setJavaFormatOnSave);
        javaCleanupOnSaveCheck = viewCheck(tr("settings.javaCleanupOnSave"), Settings::setJavaCleanupOnSave);
        // The per-source toggles are only meaningful while the master switch is on.
        autocompleteCheck.selectedProperty().addListener((obs, was, now) -> {
            autocompleteProseCheck.setDisable(!now);
            autocompleteSnippetsCheck.setDisable(!now);
            autocompleteMermaidCheck.setDisable(!now);
        });

        pdfLineNumbersCheck = viewCheck(tr("settings.pdf.lineNumbers"), Settings::setPdfLineNumbers);
        pdfHighlightCheck = viewCheck(tr("settings.pdf.highlight"), Settings::setPdfSyntaxHighlighting);
        pdfPageFooterCheck = viewCheck(tr("settings.pdf.pageFooter"), Settings::setPdfPageFooter);
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
        UiZoom.minWidth(indentStyleCombo, 170); // no fixed width: it cut off the longer (translated) items
        indentStyleCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null || scopeUi.write("editor.indentStyle", now)) {
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
        UiZoom.minWidth(pdfPageSizeCombo, 170); // no fixed width: it cut off the longer (translated) items
        pdfPageSizeCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setPdfPageSize(now);
            apply();
        });
        pdfOrientationCombo =
                pdfChoiceCombo(Settings.PDF_ORIENTATIONS, "settings.pdf.orientation.", Settings::setPdfOrientation);
        pdfMarginsCombo = pdfChoiceCombo(Settings.PDF_MARGINS, "settings.pdf.margins.", Settings::setPdfMargins);
        pdfCodeFontSizeCombo = new ComboBox<>();
        pdfCodeFontSizeCombo.getItems().setAll(Settings.PDF_CODE_FONT_SIZES);
        pdfCodeFontSizeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer points) {
                return points == null ? "" : tr("settings.pdf.codeFontSize.points", points);
            }

            @Override
            public @Nullable Integer fromString(String label) {
                return null; // not editable
            }
        });
        UiZoom.minWidth(pdfCodeFontSizeCombo, 170);
        pdfCodeFontSizeCombo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setPdfCodeFontSize(now);
            apply();
        });

        spellPage.buildControls();

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
        extendedWindowCheck.setDisable(!ExtendedWindow.supportedOn(com.editora.Os.current()));
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
        buildBeforeRunCheck = viewCheck(tr("settings.buildBeforeRun"), Settings::setBuildBeforeRun);
        mavenCentralIndexCheck = viewCheck(tr("settings.lsp.mavenCentralIndex"), Settings::setMavenPomCentralIndex);
        javaDownloadSourcesCheck = viewCheck(tr("settings.lsp.javaDownloadSources"), Settings::setJavaDownloadSources);
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
        htmlMarkupChecksCheck = viewCheck(tr("settings.htmlMarkupChecks"), Settings::setHtmlMarkupChecks);
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
            refreshToolWindowRows();
        });

        gitPages.buildControls();

        updateCheckCheck = viewCheck(tr("settings.checkForUpdates"), Settings::setUpdateCheck);

        crashRecoveryCheck = viewCheck(tr("settings.crashRecovery"), Settings::setCrashRecovery);
        localHistoryCheck = new CheckBox(tr("settings.enableLocalHistory"));
        historyMaxPerFileSpinner = historySpinner(1, Settings.MAX_HISTORY_PER_FILE, 50, Settings::setHistoryMaxPerFile);
        historyMaxAgeSpinner = historySpinner(0, Settings.MAX_HISTORY_AGE_DAYS, 30, Settings::setHistoryMaxAgeDays);
        historyMaxTotalSpinner = historySpinner(1, Settings.MAX_HISTORY_TOTAL_MB, 50, Settings::setHistoryMaxTotalMb);
        localHistoryCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setLocalHistory(now);
            updateHistoryRowsEnabled();
            apply();
        });

        renderPages.buildControls();

        for (BuildTool bt : BuildTool.enabled()) {
            CheckBox check = new CheckBox(tr("settings.enableBuildTool", bt.displayName()));
            check.selectedProperty().addListener((obs, was, now) -> {
                bt.setEnabledIn(config.getSettings(), now);
                apply();
                refreshBuildToolStatus();
                refreshBuildCommandStatus(bt);
            });
            buildToolChecks.put(bt, check);
            TextField commandField = new TextField();
            commandField.setPromptText(bt.commandExample());
            String buildKey = "build." + ProjectScope.toolId(bt);
            commitOnEnterOrBlur(
                    commandField, () -> scopeUi.shown(buildKey, bt.commandIn(config.getSettings())), now -> {
                        if (!scopeUi.write(buildKey, now)) {
                            bt.setCommandIn(config.getSettings(), now);
                        }
                        apply();
                        refreshBuildCommandStatus(bt);
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
            UiZoom.maxWidth(status, 440);
            buildToolStatusLabels.put(bt, status);
        }

        encryptionGpgPathField = new TextField();
        encryptionGpgPathField.setPromptText("gpg");
        commitOnEnterOrBlur(encryptionGpgPathField, () -> config.getSettings().getEncryptionGpgPath(), now -> {
            config.getSettings().setEncryptionGpgPath(now);
            apply();
            refreshEncryptionStatus();
        });
        encryptionIncludeSelfCheck = new CheckBox(tr("settings.encryption.includeSelf"));
        encryptionIncludeSelfCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setEncryptionIncludeSelf(now);
            apply();
        });
        encryptionPromptCombo = new ComboBox<>();
        encryptionPromptCombo
                .getItems()
                .setAll(
                        com.editora.config.Settings.PASSPHRASE_PROMPT_AGENT,
                        com.editora.config.Settings.PASSPHRASE_PROMPT_EDITORA);
        encryptionPromptCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String id) {
                return id == null ? "" : tr("settings.encryption.prompt." + id);
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });
        encryptionPromptCombo.valueProperty().addListener((obs, was, now) -> {
            if (now != null && !now.equals(config.getSettings().getEncryptionPassphrasePrompt())) {
                config.getSettings().setEncryptionPassphrasePrompt(now);
                apply();
            }
        });
        encryptionLspCheck = new CheckBox(tr("settings.encryption.lsp"));
        encryptionLspCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setEncryptionLanguageServers(now);
            apply();
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
        commitOnEnterOrBlur(ripgrepCommandField, () -> config.getSettings().getRipgrepCommand(), now -> {
            config.getSettings().setRipgrepCommand(now);
            apply();
            refreshRipgrepStatus();
        });

        webPage.buildControls();

        aiPages.buildControls();

        pluginsPage.buildControls();

        snippetPages.buildControls();

        debugPage.buildControls();

        lspPage.buildControls();

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
        UiZoom.minWidth(autoSaveCombo, 170); // no fixed width: it cut off the longer (translated) items
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
        UiZoom.prefWidth(autoSaveDelaySpinner, 90);
        autoSaveDelaySpinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            config.getSettings().setAutoSaveDelayMillis(now * 1000);
            apply();
        });
    }

    /** A small editable int spinner that writes {@code setter} + re-applies (skipping the loading phase). */
    private Spinner<Integer> historySpinner(
            int min, int max, int def, java.util.function.BiConsumer<Settings, Integer> setter) {
        Spinner<Integer> s = IntSpinners.editable(min, max, def, 1);
        UiZoom.prefWidth(s, 100);
        s.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            if (historyLimits == null) {
                setter.accept(config.getSettings(), now);
                apply();
                return;
            }
            // Not written here: a lower limit deletes revisions in every project the moment it is applied,
            // and stepping the spinner back up does not bring them back. The handler writes the settings
            // once it knows nothing would go, or after the user confirmed what would.
            historyLimits.change(
                    historyMaxPerFileSpinner.getValue(),
                    historyMaxAgeSpinner.getValue(),
                    historyMaxTotalSpinner.getValue(),
                    stage,
                    applied -> {
                        if (applied) {
                            apply();
                        }
                        showHistoryLimits(); // declined: back to the limits in force
                    });
        });
        return s;
    }

    /** Shows the stored retention limits in the three spinners, without that counting as an edit. */
    private void showHistoryLimits() {
        Settings settings = config.getSettings();
        boolean was = loading;
        loading = true;
        try {
            historyMaxPerFileSpinner.getValueFactory().setValue(settings.getHistoryMaxPerFile());
            historyMaxAgeSpinner.getValueFactory().setValue(settings.getHistoryMaxAgeDays());
            historyMaxTotalSpinner.getValueFactory().setValue(settings.getHistoryMaxTotalMb());
        } finally {
            loading = was;
        }
    }

    /** Local-history retention spinners are only meaningful while the master switch is on. */
    private void updateHistoryRowsEnabled() {
        boolean on = localHistoryCheck.isSelected();
        historyMaxPerFileSpinner.setDisable(!on);
        historyMaxAgeSpinner.setDisable(!on);
        historyMaxTotalSpinner.setDisable(!on);
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
        // General
        pages.put(Category.APPEARANCE, appearancePage());
        pages.put(Category.INTERFACE, interfacePage());
        pages.put(Category.TOOLBAR, toolbarPage());
        pages.put(Category.WORKSPACE, workspacePage());
        pages.put(Category.TOOL_WINDOWS, toolWindowsPage());
        // Editor
        pages.put(Category.EDITOR, editorPage());
        pages.put(Category.COMPLETION, completionPage());
        pages.put(Category.SNIPPETS, snippetPages.snippetsPage());
        pages.put(Category.TEMPLATES, snippetPages.templatesPage());
        pages.put(Category.TODO, todoPage());
        pages.put(Category.TERMINAL, terminalPage());
        pages.put(Category.SPELL_CHECK, spellPage.build());
        pages.put(Category.SEARCH, searchPage());
        // Languages & Tools
        pages.put(Category.LSP, lspPage.build());
        pages.put(Category.DEBUG, debugPage.build());
        pages.put(Category.MARKDOWN, markdownPage.build());
        pages.put(Category.MERMAID, renderPages.mermaidPage());
        pages.put(Category.DIAGRAMS, renderPages.diagramsPage());
        pages.put(Category.TYPST, renderPages.typstPage());
        pages.put(Category.BUILD_TOOLS, buildToolsPage());
        pages.put(Category.WEB, webPage.build());
        pages.put(Category.EXTERNAL_TOOLS, externalToolsPage());
        pages.put(Category.ABBREVIATIONS, abbreviationsPage());
        // Version control
        pages.put(Category.GIT, gitPages.gitPage());
        pages.put(Category.GITHUB, gitPages.githubPage());
        // System
        pages.put(Category.KEYMAPS, keymapsPage.keymapsPage());
        pages.put(Category.MACROS, keymapsPage.macrosPage());
        pages.put(Category.REMOTE, remotePage());
        pages.put(Category.ENCRYPTION, encryptionPage());
        pages.put(Category.PLUGINS, pluginsPage.build());
        pages.put(Category.SYNC, syncPage.build());
        pages.put(Category.MCP, aiPages.mcpPage());
        pages.put(Category.AI_GENERAL, aiPages.aiGeneralPage());
        pages.put(Category.AGENT, aiPages.agentPage());
        pages.put(Category.AI, aiPages.aiPage());
        pages.put(Category.ADVANCED, advancedPage());
        // Each title gets its "?" link to the page's docs; a beta page also the sidebar's Beta pill.
        for (Category c : Category.values()) {
            if (pages.get(c) instanceof VBox page
                    && !page.getChildren().isEmpty()
                    && page.getChildren().get(0) instanceof javafx.scene.text.Text title) {
                page.getChildren().remove(0);
                HBox heading = UiZoom.spacing(new HBox(title), 8);
                if (c.beta) {
                    Label pill = new Label(tr("settings.beta"));
                    pill.getStyleClass().addAll("settings-beta-pill", "settings-title-pill");
                    heading.getChildren().add(pill);
                }
                Hyperlink help = new Hyperlink("?");
                help.getStyleClass().add("settings-help-link");
                Icons.name(help, tr("settings.help", c.display));
                help.setOnAction(e -> {
                    if (openUrl != null) {
                        openUrl.accept(SettingsFrameBits.helpUrl(c.name()));
                    }
                });
                heading.getChildren().add(help);
                heading.setAlignment(Pos.CENTER_LEFT);
                page.getChildren().add(0, heading);
            }
        }
    }

    private VBox appearancePage() {
        VBox p = page(tr("settings.cat.appearance"), tr("settings.appearance.subtitle"));
        Card mainCard = card(p, null);
        // The hints are row descriptions, not labels boxed with the combo: such a box is as wide as its
        // (translated) hint and left-aligns the combo inside, so the four combos sat at four x positions.
        controlRow(
                mainCard,
                Category.APPEARANCE,
                tr("settings.uiLanguage"),
                tr("settings.uiLanguage.note"),
                languageControl,
                "language interface ui locale translation");
        controlRow(
                mainCard,
                Category.APPEARANCE,
                tr("settings.fontFamily"),
                tr("settings.fontNote"),
                fontFamily,
                "font family typeface monospace");
        controlRow(mainCard, Category.APPEARANCE, tr("settings.fontSize"), null, fontSize, "font size text");
        // Text zoom (status bar −/+, the zoom commands) scales the size above; without this the field and the
        // preview showed 18 while the editor drew 29 px.
        Hyperlink resetZoom = new Hyperlink(tr("settings.fontZoom.reset"));
        resetZoom.setOnAction(e -> {
            config.getSettings().setFontZoom(1.0);
            apply();
        });
        zoomNote = UiZoom.spacing(new HBox(note(""), resetZoom), 8);
        zoomNote.setAlignment(Pos.CENTER_LEFT);
        zoomNote.getStyleClass().add("settings-row");
        mainCard.body().getChildren().add(zoomNote); // not a search row: it shows only while zoomed
        controlRow(
                mainCard,
                Category.APPEARANCE,
                tr("settings.uiZoom"),
                tr("settings.uiZoomNote"),
                uiZoom,
                "interface ui zoom scale size larger smaller menus toolbar percent");
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
        Hyperlink openThemes = new Hyperlink(tr("settings.userThemes.open"));
        openThemes.setOnAction(e -> {
            Path dir = config.getConfigDir().resolve("themes");
            try {
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.createDirectories(config.getConfigDir().resolve("editor-themes"));
            } catch (java.io.IOException ex) {
                return; // a config folder that cannot be written to has no themes to show
            }
            com.editora.process.DesktopActions.reveal(dir, true, msg -> {});
        });
        Hyperlink reloadThemes = new Hyperlink(tr("settings.userThemes.reload"));
        reloadThemes.setOnAction(e -> {
            UserThemes.load(config.getConfigDir());
            syncThemes();
        });
        controlRow(
                mainCard,
                Category.APPEARANCE,
                tr("settings.userThemes"),
                tr("settings.userThemes.desc"),
                UiZoom.spacing(new HBox(openThemes, reloadThemes), 12),
                "user themes custom stylesheet css folder reload own theme");
        Card previewSection = card(p, tr("settings.livePreview"));
        cardRow(previewSection, Category.APPEARANCE, preview, "preview sample code");
        return p;
    }

    /**
     * Answers {@link #askBeforeLoss} (given the title and the question) in place of its dialog; null, the
     * default, shows the dialog. Set by tests.
     */
    java.util.function.@Nullable BiPredicate<String, String> confirmLoss;

    /**
     * Asks before a step that loses something. The red button carries {@code action}, the step's own verb, and
     * Cancel is the default answer.
     */
    boolean askBeforeLoss(String title, String body, String action) {
        java.util.function.BiPredicate<String, String> answer = confirmLoss;
        if (answer != null) {
            return answer.test(title, body);
        }
        Alert confirm = Confirm.dialog(stage, new Confirm.Question(title, null, body, action));
        UiZoom.minWidth(confirm.getDialogPane(), 460);
        return Confirm.confirmed(confirm, confirm.showAndWait());
    }

    /** Refreshes this window's toolbar selector after the separate editor persists a change. */
    public void setRunConfigsChangedHandler(Runnable handler) {
        this.onRunConfigsChanged = handler == null ? () -> {} : handler;
    }

    private VBox editorPage() {
        VBox p = page(tr("settings.cat.editor"), tr("settings.editor.subtitle"));
        Card display = card(p, tr("settings.section.display"));
        checkRow(display, Category.EDITOR, columnRulerCheck, null, "80 column ruler guide margin");
        display.body()
                .getChildren()
                .add(SettingsDependents.note(columnRulerCheck.selectedProperty(), tr("settings.showRuler")));
        int firstRulerRow = rows.size();
        // A value that does not read as columns keeps the current ones (textRow shows what was kept).
        textRow(
                display,
                Category.EDITOR,
                "rulers",
                "80, 120",
                st -> com.editora.config.RulerColumns.format(scopeUi.shown("editor.rulers", st.getRulers())),
                (st, v) -> { // only called once the problem check below passed: v parses
                    List<Integer> columns = com.editora.config.RulerColumns.parse(v);
                    if (columns != null && !scopeUi.write("editor.rulers", columns)) {
                        st.setRulers(columns);
                    }
                },
                v -> rulerProblem(com.editora.config.RulerColumns.problem(v)),
                "rulers ruler columns column margin line length 80 100 120");
        textRow(
                display,
                Category.EDITOR,
                "languageRulers",
                "java: 100; python: 79, 88",
                st -> com.editora.config.RulerColumns.formatByLanguage(
                        scopeUi.shown("editor.languageRulers", st.getLanguageRulers())),
                (st, v) -> {
                    java.util.Map<String, List<Integer>> by = com.editora.config.RulerColumns.parseByLanguage(v);
                    if (by != null && !scopeUi.write("editor.languageRulers", by.isEmpty() ? null : by)) {
                        st.setLanguageRulers(by);
                    }
                },
                v -> rulerProblem(com.editora.config.RulerColumns.byLanguageProblem(
                        v, SpellFileTypesEditor.languageNames(config.getSettings()))),
                "rulers per language ruler columns margin line length");
        SettingsDependents.bind(columnRulerCheck.selectedProperty(), rowsSince(firstRulerRow));
        // Simple UI mode hides some of what this card shows, whatever the switches below say.
        Label simpleOverrides = note(tr("settings.simpleModeOverrides"));
        simpleOverrides.setWrapText(true);
        simpleOverrides.getStyleClass().add("settings-row-desc");
        simpleOverrides.visibleProperty().bind(simpleModeCheck.selectedProperty());
        simpleOverrides.managedProperty().bind(simpleOverrides.visibleProperty());
        display.body().getChildren().add(simpleOverrides);
        switchRow(
                display,
                Category.EDITOR,
                "showIndentGuides",
                true,
                Settings::isShowIndentGuides,
                Settings::setShowIndentGuides,
                "indent guides indentation lines vertical levels");
        checkRow(display, Category.EDITOR, lineHighlightCheck, null, "highlight current line caret");
        checkRow(display, Category.EDITOR, lineNumbersCheck, null, "line numbers gutter");
        checkRow(display, Category.EDITOR, minimapCheck, null, "minimap overview");
        checkRow(
                display,
                Category.EDITOR,
                stickyScrollCheck,
                tr("settings.stickyScroll.desc"),
                "sticky scroll pinned scope headers class method context");
        checkRow(
                display,
                Category.EDITOR,
                bracketColorsCheck,
                null,
                "bracket pair colorization colors nesting depth rainbow brackets parentheses braces vs code");
        checkRow(display, Category.EDITOR, wordWrapCheck, null, "word wrap soft wrap long lines code");
        checkRow(
                display,
                Category.EDITOR,
                wordWrapProseCheck,
                null,
                "word wrap soft wrap long lines markdown plain text prose paragraph");
        switchRow(
                display,
                Category.EDITOR,
                "typewriterMode",
                true,
                Settings::isTypewriterMode,
                Settings::setTypewriterMode,
                "typewriter mode centre center caret line middle scrolling focus writing");
        checkRow(display, Category.EDITOR, whitespaceCheck, null, "hidden characters whitespace spaces tabs eol");
        int noteIndicatorsRow = rows.size();
        checkRow(
                display,
                Category.EDITOR,
                noteIndicatorsCheck,
                tr("settings.showNoteIndicators.desc"),
                "personal notes highlight start marker indicators");
        SettingsDependents.bind(notesCheck.selectedProperty(), rowsSince(noteIndicatorsRow));
        display.body()
                .getChildren()
                .add(SettingsDependents.note(notesCheck.selectedProperty(), tr("settings.enableNotes")));
        switchRow(
                display,
                Category.EDITOR,
                "statusBarWordCount",
                true,
                Settings::isStatusBarWordCount,
                Settings::setStatusBarWordCount,
                "status bar word count words reading time markdown plain text");
        Card editing = card(p, tr("settings.section.editing"));
        checkRow(
                editing,
                Category.EDITOR,
                multiCaretCheck,
                null,
                "multiple cursors carets column box selection alt drag vs code");
        checkRow(
                editing,
                Category.EDITOR,
                autoRenameTagCheck,
                null,
                "auto rename tag html xml paired close mirror vs code");
        checkRow(
                editing,
                Category.EDITOR,
                autoCloseTagsCheck,
                null,
                "auto close tags html xml closing tag insert body vs code");
        choiceRow( // what a typed `=` and a completed attribute get, server or not (HTML review 3 C204)
                editing,
                Category.EDITOR,
                "htmlAttributeQuotes",
                true,
                Settings.HTML_ATTRIBUTE_QUOTES,
                Settings::getHtmlAttributeQuotes,
                Settings::setHtmlAttributeQuotes,
                "html attribute quotes double single empty none value equals typed completed completion auto create vs code");
        checkRow(
                editing,
                Category.EDITOR,
                htmlMarkupChecksCheck,
                tr("settings.htmlMarkupChecks.note"),
                "html markup check validation unclosed element stray end tag broken link missing file lint");
        checkRow(
                editing,
                Category.EDITOR,
                autoFillCheck,
                tr("settings.autoFill.desc"),
                "auto fill mode break wrap prose lines fill column emacs");
        checkRow(
                editing,
                Category.EDITOR,
                symbolIndexCheck,
                tr("settings.symbolIndex.desc"),
                "symbol index go to symbol project declarations without language server");
        Card clipboard = card(p, tr("settings.section.clipboard"));
        checkRow(
                clipboard,
                Category.EDITOR,
                copyLineNoSelectionCheck,
                null,
                "copy cut whole current line empty no selection clipboard vs code");
        checkRow(
                clipboard,
                Category.EDITOR,
                copyWithHighlightingCheck,
                null,
                "copy syntax highlighting html colors paste slack document rich text vs code");
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
                tr("settings.fillColumn.desc"),
                fillColumnSpinner,
                "fill column wrap paragraph reflow emacs m-q width");
        checkRow(
                indent,
                Category.EDITOR,
                editorConfigCheck,
                null,
                "editorconfig indent style size charset end of line trailing whitespace final newline");
        Card saving = card(p, tr("settings.section.saving"));
        Label delayLabel = note(tr("settings.autoSave.delay"));
        delayLabel.setLabelFor(autoSaveDelaySpinner);
        HBox autoSaveBox = UiZoom.spacing(new HBox(autoSaveCombo, autoSaveDelaySpinner, delayLabel), 8);
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
                tr("settings.adminSave.note"),
                "save administrator root sudo pkexec permission etc linux polkit");
        // Set per project only (there is no user setting: .editorconfig decides elsewhere).
        controlRow(
                saving,
                Category.EDITOR,
                tr("settings.scope.trimTrailingWhitespace"),
                tr("settings.scope.projectOnly"),
                trimOnSaveCombo,
                "trim trailing whitespace save project editorconfig");
        controlRow(
                saving,
                Category.EDITOR,
                tr("settings.scope.insertFinalNewline"),
                tr("settings.scope.projectOnly"),
                finalNewlineCombo,
                "final newline end of file save project editorconfig");
        Card performance = card(p, tr("settings.section.performance"));
        HBox largeFileBox = new HBox(largeFileThresholdSpinner, note(tr("settings.largeFileThreshold.unit")));
        UiZoom.spacing(largeFileBox, 8).setAlignment(Pos.CENTER_LEFT);
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
        Card pdf = card(p, tr("settings.section.pdf"));
        checkRow(
                pdf,
                Category.EDITOR,
                pdfLineNumbersCheck,
                tr("settings.pdf.lineNumbers.desc"),
                "pdf export print line numbers gutter");
        checkRow(
                pdf,
                Category.EDITOR,
                pdfHighlightCheck,
                tr("settings.pdf.highlight.desc"),
                "pdf export print syntax highlighting colors");
        checkRow(
                pdf,
                Category.EDITOR,
                pdfPageFooterCheck,
                tr("settings.pdf.pageFooter.desc"),
                "pdf export print page footer document name page number");
        controlRow(
                pdf,
                Category.EDITOR,
                tr("settings.pdf.pageSize"),
                tr("settings.pdf.pageSize.desc"),
                pdfPageSizeCombo,
                "pdf export page size letter a4 paper print page setup");
        controlRow(
                pdf,
                Category.EDITOR,
                tr("settings.pdf.orientation"),
                tr("settings.pdf.orientation.desc"),
                pdfOrientationCombo,
                "pdf export orientation portrait landscape page");
        controlRow(
                pdf,
                Category.EDITOR,
                tr("settings.pdf.margins"),
                tr("settings.pdf.margins.desc"),
                pdfMarginsCombo,
                "pdf export margin margins normal narrow wide page");
        controlRow(
                pdf,
                Category.EDITOR,
                tr("settings.pdf.codeFontSize"),
                tr("settings.pdf.codeFontSize.desc"),
                pdfCodeFontSizeCombo,
                "pdf export code font size text size points source");
        return p;
    }

    /**
     * A combo over the fixed {@code keys} of a PDF page setting, shown as {@code tr(prefix + key)} and
     * written through {@code setter} — the shape of the "PDF page size" combo.
     */
    private ComboBox<String> pdfChoiceCombo(
            List<String> keys, String prefix, java.util.function.BiConsumer<Settings, String> setter) {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().setAll(keys);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String key) {
                return key == null ? "" : tr(prefix + key);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        UiZoom.minWidth(combo, 170);
        combo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            setter.accept(config.getSettings(), now);
            apply();
        });
        return combo;
    }

    /** Shows the stored Markdown values in their controls without writing anything back. */
    void syncMarkdownControls(Settings s) {
        boolean prev = loading;
        loading = true;
        try {
            wordWrapProseCheck.setSelected(s.isWordWrapProse());
            markdownPage.showValues(s);
            settingSyncs.forEach(sync -> sync.accept(s));
            markdownPage.refreshMarkdownLintConfigNote();
        } finally {
            loading = prev;
        }
    }

    /** Shows a stored value in its control; run by {@link #syncMarkdownControls} for every row built below. */
    final java.util.List<Consumer<Settings>> settingSyncs = new java.util.ArrayList<>();

    /** The control of each row built below, by its setting key (a switch row's is its bound checkbox). */
    final Map<String, javafx.scene.control.Control> settingControls = new java.util.HashMap<>();

    /**
     * An on/off row for one boolean setting: titled {@code settings.<key>} (described by
     * {@code settings.<key>.desc} when {@code described}), written on change, shown again on every sync.
     */
    CheckBox switchRow(
            Card card,
            Category cat,
            String key,
            boolean described,
            java.util.function.Predicate<Settings> get,
            java.util.function.BiConsumer<Settings, Boolean> set,
            String keywords) {
        CheckBox check = viewCheck(tr("settings." + key), set);
        settingControls.put(key, check);
        checkRow(card, cat, check, described ? tr("settings." + key + ".desc") : null, keywords);
        settingSyncs.add(st -> check.setSelected(get.test(st)));
        return check;
    }

    /**
     * Terminal ▸ Theme: "Editor theme" (blank, the default), then the terminal's own colour schemes under the Light
     * and Dark headings the theme pickers use. A change recolours open terminals at once.
     */
    private void terminalThemeRow(Card card, Category cat) {
        ComboBox<String> combo = new ComboBox<>();
        List<String> choices = new ArrayList<>();
        choices.add(TerminalThemes.FOLLOW_EDITOR);
        choices.addAll(ThemeGroups.grouped(TerminalThemes.NAMES, TerminalThemes::isDark, Set.of()));
        combo.getItems().setAll(choices);
        UiZoom.prefWidth(combo, 220);
        java.util.function.Function<String, String> label =
                name -> name.isEmpty() ? tr("settings.terminal.theme.editor") : name;
        ThemeGroups.install(combo, label);
        combo.setButtonCell(labelledCell(label));
        settingControls.put("terminal.theme", combo);
        combo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null || ThemeGroups.isHeading(now)) {
                return;
            }
            config.getSettings().setTerminalTheme(now);
            apply();
        });
        controlRow(
                card,
                cat,
                tr("settings.terminal.theme"),
                tr("settings.terminal.theme.desc"),
                combo,
                "terminal theme colour color scheme palette ansi solarized dracula nord gruvbox tango campbell dark light");
        settingSyncs.add(st -> combo.setValue(TerminalThemes.normalize(st.getTerminalTheme())));
    }

    /** A row choosing one of {@code values}, each labelled {@code settings.<key>.<value>}. */
    void choiceRow(
            Card card,
            Category cat,
            String key,
            boolean described,
            java.util.List<String> values,
            java.util.function.Function<Settings, String> get,
            java.util.function.BiConsumer<Settings, String> set,
            String keywords) {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().setAll(values);
        combo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : tr("settings." + key + "." + value);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        UiZoom.minWidth(combo, 170);
        settingControls.put(key, combo);
        combo.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            set.accept(config.getSettings(), now);
            apply();
        });
        controlRow(
                card, cat, tr("settings." + key), described ? tr("settings." + key + ".desc") : null, combo, keywords);
        settingSyncs.add(st -> combo.setValue(get.apply(st)));
    }

    /**
     * A sub-heading inside a card that groups the rows after it ({@code text}); found by {@code keywords}, so a
     * search that finds any row under it can name the group too (the XML card's Validation / Formatting /
     * Structure, XML review 2 C9).
     */
    void cardSubheading(Card card, Category cat, String text, String keywords) {
        Label h = new Label(text);
        h.getStyleClass().addAll("settings-row", "settings-card-subheading");
        h.setMaxWidth(Double.MAX_VALUE);
        cardRow(card, cat, h, keywords);
    }

    /** A row for a size in pixels where 0 means "the built-in one" (described by {@code settings.<key>.desc}). */
    void intRow(
            Card card,
            Category cat,
            String key,
            int max,
            int step,
            java.util.function.ToIntFunction<Settings> get,
            java.util.function.BiConsumer<Settings, Integer> set,
            String keywords) {
        Spinner<Integer> spinner = IntSpinners.editable(0, max, 0, step);
        UiZoom.prefWidth(spinner, 100);
        spinner.setAccessibleText(tr("settings." + key));
        settingControls.put(key, spinner);
        spinner.valueProperty().addListener((obs, was, now) -> {
            if (loading || now == null) {
                return;
            }
            set.accept(config.getSettings(), now);
            // What is kept may not be what was typed (a size below the smallest is raised to it): show it.
            int kept = get.applyAsInt(config.getSettings());
            if (kept != now) {
                boolean prev = loading;
                loading = true;
                spinner.getValueFactory().setValue(kept);
                loading = prev;
            }
            apply();
        });
        controlRow(card, cat, tr("settings." + key), tr("settings." + key + ".desc"), spinner, keywords);
        settingSyncs.add(st -> spinner.getValueFactory().setValue(get.applyAsInt(st)));
    }

    /** A row for a short text value, committed on Enter or when focus leaves (described by {@code .desc}). */
    void textRow(
            Card card,
            Category cat,
            String key,
            String prompt,
            java.util.function.Function<Settings, String> get,
            java.util.function.BiConsumer<Settings, String> set,
            String keywords) {
        textRow(card, cat, key, prompt, get, set, null, keywords);
    }

    /**
     * As above, with {@code problem} saying why typed text cannot be kept (null when it can). Text it rejects
     * is not saved: the field turns red with the reason as its tooltip and keeps the text until it is fixed.
     */
    void textRow(
            Card card,
            Category cat,
            String key,
            String prompt,
            java.util.function.Function<Settings, String> get,
            java.util.function.BiConsumer<Settings, String> set,
            java.util.function.@Nullable Function<String, @Nullable String> problem,
            String keywords) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        UiZoom.prefWidth(field, 220);
        settingControls.put(key, field);
        commitOnEnterOrBlur(field, () -> get.apply(config.getSettings()), now -> {
            String why = problem == null ? null : problem.apply(now);
            showProblem(field, why);
            if (why != null) {
                return;
            }
            set.accept(config.getSettings(), now);
            field.setText(get.apply(config.getSettings())); // as kept: a name that is not one reads as the default
            apply();
        });
        controlRow(card, cat, tr("settings." + key), tr("settings." + key + ".desc"), field, keywords);
        settingSyncs.add(st -> {
            field.setText(get.apply(st));
            showProblem(field, null);
        });
        fullValueTip(field, tr("settings." + key));
    }

    /**
     * Names {@code field} for a screen reader, unless it has a name, and makes its whole value its tooltip while it
     * is too long to read in the field and holds no problem (XML/pom review 3, C28; review 4, C36).
     */
    static void fullValueTip(TextField field, String name) {
        if (field.getAccessibleText() == null) {
            field.setAccessibleText(name);
        }
        field.textProperty().addListener((o, was, now) -> {
            if (!(field instanceof javafx.scene.control.PasswordField) // a secret is never shown
                    && !field.getPseudoClassStates().contains(atlantafx.base.theme.Styles.STATE_DANGER)) {
                showProblem(field, null);
            }
        });
    }

    /** Widens text row {@code key}'s field: a list the usual 220 px shows only the start of (XML review 3, C28). */
    void widenField(String key, double width) {
        if (settingControls.get(key) instanceof TextField field) {
            UiZoom.prefWidth(field, width);
        }
    }

    /** The field message for a typed ruler list that cannot be read, or null. */
    private static @Nullable String rulerProblem(com.editora.config.RulerColumns.@Nullable Problem problem) {
        if (problem == null) {
            return null;
        }
        return switch (problem.kind()) {
            case NOT_A_COLUMN ->
                tr("settings.rulers.notAColumn", problem.part(), com.editora.config.RulerColumns.MAX_COLUMN);
            case NO_LANGUAGE -> tr("settings.rulers.noLanguage", problem.part());
            case UNKNOWN_LANGUAGE -> tr("settings.rulers.unknownLanguage", problem.part());
        };
    }

    /** Marks {@code field} as holding text that is not kept ({@code why} != null), with the reason, or clears it. */
    private static void showProblem(TextField field, @Nullable String why) {
        field.pseudoClassStateChanged(atlantafx.base.theme.Styles.STATE_DANGER, why != null);
        // No problem: a value too long to read in the field is the tooltip, whole (XML/pom review 3, C28)
        String text = field.getText();
        String tip = why != null ? why : text != null && text.length() > 24 ? text : null;
        Tooltip tooltip = tip == null ? null : ControlNames.tooltip(tip);
        if (tooltip != null) {
            tooltip.setWrapText(true);
            tooltip.setMaxWidth(UiZoom.px(480));
        }
        field.setTooltip(tooltip);
        field.setAccessibleHelp(why);
    }

    /** Settings → Web; see {@link SettingsWebPage}. */
    final SettingsWebPage webPage;

    /** Settings → Spell Check; see {@link SettingsSpellPage}. */
    private final SettingsSpellPage spellPage;

    /** Injects the Spell Check page's "open dictionary file" actions (bundled technical / personal). */
    public void setDictionaryActions(Runnable openTechnical, Runnable openPersonal) {
        spellPage.setDictionaryActions(openTechnical, openPersonal);
    }

    /** Re-reads the Personal Dictionary list: a word was added, ignored or reloaded while the page is open. */
    public void syncDictionaryList() {
        spellPage.syncDictionaryList();
    }

    /** Re-syncs the file-type ticks after {@code spell.toggleForLanguage} (or another window) changed them. */
    public void syncSpellFileTypes() {
        spellPage.syncSpellFileTypes();
    }

    /** Re-syncs the "Enable personal dictionary" checkbox after a palette toggle. */
    public void syncPersonalDictionaryCheck() {
        spellPage.syncPersonalDictionaryCheck();
    }

    /** Re-syncs the "Enable technical dictionary" checkbox after a palette toggle. */
    public void syncTechnicalDictionaryCheck() {
        spellPage.syncTechnicalDictionaryCheck();
    }

    /** Runs a control re-sync without its listeners treating it as a user edit (no write, no save, no apply). */
    void quietly(Runnable sync) {
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
        showAt(Category.SPELL_CHECK, owner);
        spellPage.refreshDictionaryList();
    }

    /** GENERAL ▸ Interface: window-chrome visibility + the Simple/Zen minimal-UI modes. */
    private VBox interfacePage() {
        VBox p = page(tr("settings.cat.interface"), tr("settings.interface.subtitle"));
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
        checkRow(modes, Category.INTERFACE, zenCheck, tr("settings.zen.note"), "zen distraction free focus mode");
        checkRow(
                modes,
                Category.INTERFACE,
                expertCheck,
                tr("settings.expert.note"),
                "expert focus mode keeps line numbers status bar");
        return p;
    }

    /** GENERAL ▸ Workspace: project + personal-notes features and local file history. */
    private VBox workspacePage() {
        VBox p = page(tr("settings.cat.workspace"), tr("settings.workspace.subtitle"));
        Card features = card(p, tr("settings.section.features"));
        checkRow(features, Category.WORKSPACE, projectsCheck, tr("settings.projects.tip"), "projects workspace folder");
        checkRow(features, Category.WORKSPACE, projectHiddenCheck, null, "project tree hidden dot files folders show");
        checkRow(features, Category.WORKSPACE, notesCheck, null, "personal notes annotations enable feature");
        checkRow(
                features,
                Category.WORKSPACE,
                crashRecoveryCheck,
                tr("settings.crashRecovery.note"),
                "crash recovery unsaved edits restore backup hot exit power loss kill");
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
                tr("settings.history.maxAgeDays.note"),
                historyMaxAgeSpinner,
                "local history max age days retention prune");
        controlRow(
                history,
                Category.WORKSPACE,
                tr("settings.history.maxTotalMb"),
                null,
                historyMaxTotalSpinner,
                "local history max total size megabytes project budget");
        return p;
    }

    /** EDITOR ▸ Code Completion: the autocomplete master + per-source sub-toggles, quick-doc, semantic tokens. */
    private VBox completionPage() {
        VBox p = page(tr("settings.cat.completion"), tr("settings.completion.subtitle"));
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
        Card lsp = card(p, tr("settings.section.lspFeatures"));
        // Every row here needs a language server: greyed out, with the reason, while LSP is off.
        lsp.body()
                .getChildren()
                .add(SettingsDependents.note(lspPage.lspCheck.selectedProperty(), tr("settings.enableLsp")));
        int firstLspRow = rows.size();
        checkRow(
                lsp,
                Category.COMPLETION,
                semanticHighlightCheck,
                null,
                "semantic highlighting lsp tokens types parameters fields deprecated");
        checkRow(
                lsp,
                Category.COMPLETION,
                inlayHintsCheck,
                null,
                "inlay hints parameter names inferred types lsp annotations");
        checkRow(
                lsp,
                Category.COMPLETION,
                codeLensCheck,
                tr("settings.codeLens.note"),
                "code lens vision references usages implementations count lsp");
        checkRow(
                lsp,
                Category.COMPLETION,
                lightbulbCheck,
                tr("settings.lspLightbulb.note"),
                "lightbulb bulb gutter quick fix intention code actions alt enter lsp");
        checkRow(
                lsp,
                Category.COMPLETION,
                problemCounterCheck,
                tr("settings.problemCounter.note"),
                "problem counter errors warnings count widget inspection next previous lsp");
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
                .bind(inlayHintsCheck.selectedProperty().not());
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
        checkRow(
                lsp,
                Category.COMPLETION,
                javaOrganizeImportsOnSaveCheck,
                tr("settings.javaOnSave.note"),
                "organize optimize imports on save actions java jdtls");
        checkRow(lsp, Category.COMPLETION, javaFormatOnSaveCheck, null, "format reformat on save actions java jdtls");
        checkRow(lsp, Category.COMPLETION, javaCleanupOnSaveCheck, null, "clean up cleanup on save actions java jdtls");
        javafx.scene.control.Button chooseCleanups =
                new javafx.scene.control.Button(tr("settings.javaCleanups.choose"));
        chooseCleanups.setOnAction(
                e -> JavaCleanupsDialog.show(stage, config.getSettings().getJavaCleanups(), ids -> {
                    config.getSettings().setJavaCleanups(ids);
                    showJavaCleanupsCount();
                    apply();
                }));
        controlRow(
                lsp,
                Category.COMPLETION,
                tr("settings.javaCleanups"),
                tr("settings.javaCleanups.note"),
                UiZoom.spacing(new HBox(javaCleanupsCount, chooseCleanups), 10),
                "clean up cleanups override final redundant modifiers lambda java jdtls");
        textRow(
                lsp,
                Category.COMPLETION,
                "javaExcludedTypes",
                "java.awt.*, com.sun.*",
                st -> String.join(", ", st.getJavaExcludedTypes()),
                (st, v) -> st.setJavaExcludedTypes(java.util.Arrays.stream(v.split("[,\\s]+"))
                        .filter(t -> !t.isBlank())
                        .toList()),
                "exclude excluded types auto import completion filter awt java jdtls");
        SettingsDependents.bind(lspPage.lspCheck.selectedProperty(), rowsSince(firstLspRow));
        return p;
    }

    /** EDITOR ▸ TODO: in-editor TODO/FIXME highlighting + the per-pattern editor. */
    private VBox todoPage() {
        todoSettings = new TodoSettingsPane(config::getSettings, this::apply);
        VBox p = page(tr("settings.cat.todo"), tr("settings.todo.subtitle"));
        Card todoHl = card(p, tr("settings.section.todo"));
        checkRow(
                todoHl,
                Category.TODO,
                todoHighlightCheck,
                tr("settings.todoSyntax"),
                "todo fixme highlight patterns tags comments annotations");
        cardRow(todoHl, Category.TODO, todoSettings.patternsEditor(), "todo fixme pattern regex color add remove edit");
        Card partColors = card(p, tr("settings.section.todoPartColors"));
        cardRow(
                partColors,
                Category.TODO,
                todoSettings.partColorsEditor(),
                "todo tag priority critical high medium low part color");
        return p;
    }

    /** LANGUAGES & TOOLS ▸ Terminal: the integrated terminal's shell, its look, and the keys Editora keeps. */
    private VBox terminalPage() {
        VBox p = page(tr("settings.cat.terminal"), tr("settings.terminal.subtitle"));
        Category cat = Category.TERMINAL;
        Card shell = card(p, tr("settings.section.terminalShell"));
        textRow(
                shell,
                cat,
                "terminal.shell",
                "$SHELL",
                Settings::getTerminalShell,
                Settings::setTerminalShell,
                "terminal shell bash zsh fish powershell cmd login");
        switchRow(
                shell,
                cat,
                "terminal.shellIntegration",
                true,
                Settings::isTerminalShellIntegration,
                Settings::setTerminalShellIntegration,
                "terminal shell integration osc prompt marks cwd directory bash zsh");
        switchRow(
                shell,
                cat,
                "terminal.typeAhead",
                true,
                Settings::isTerminalTypeAhead,
                Settings::setTerminalTypeAhead,
                "terminal type ahead typeahead prediction latency ssh slow remote echo");
        switchRow(
                shell,
                cat,
                "terminal.useExternal",
                true,
                Settings::isTerminalUseExternal,
                Settings::setTerminalUseExternal,
                "terminal open in terminal here external system application gnome konsole iterm");
        Card display = card(p, tr("settings.section.terminalDisplay"));
        terminalThemeRow(display, cat);
        intRow(
                display,
                cat,
                "terminal.fontSize",
                72,
                1,
                Settings::getTerminalFontSize,
                Settings::setTerminalFontSize,
                "terminal font text size zoom");
        choiceRow(
                display,
                cat,
                "terminal.cursor",
                false,
                java.util.List.of("block", "underline", "bar"),
                Settings::getTerminalCursor,
                Settings::setTerminalCursor,
                "terminal cursor block underline bar caret shape");
        switchRow(
                display,
                cat,
                "terminal.bell",
                false,
                Settings::isTerminalBell,
                Settings::setTerminalBell,
                "terminal bell flash beep visual");
        intRow(
                display,
                cat,
                "terminal.scrollback",
                Settings.TERMINAL_SCROLLBACK_MAX,
                500,
                Settings::getTerminalScrollback,
                Settings::setTerminalScrollback,
                "terminal scrollback history lines buffer");
        Card keys = card(p, tr("settings.section.terminalKeys"));
        textRow(
                keys,
                cat,
                "terminal.ideCommands",
                "",
                st -> String.join(", ", st.getTerminalIdeCommands()),
                (st, text) -> st.setTerminalIdeCommands(java.util.Arrays.stream(text.split("[,\\s]+"))
                        .filter(id -> !id.isBlank())
                        .toList()),
                "terminal keys shortcuts commands shell keyboard ctrl escape allow");
        switchRow(
                keys,
                cat,
                "terminal.macOptionIsMeta",
                true,
                Settings::isTerminalMacOptionIsMeta,
                Settings::setTerminalMacOptionIsMeta,
                "terminal option alt meta macos mac compose characters readline");
        switchRow(
                keys,
                cat,
                "terminal.confirmMultilinePaste",
                true,
                Settings::isTerminalConfirmMultilinePaste,
                Settings::setTerminalConfirmMultilinePaste,
                "terminal paste multiple lines newline confirm warning bracketed");
        switchRow(
                keys,
                cat,
                "terminal.copyOnSelect",
                true,
                Settings::isTerminalCopyOnSelect,
                Settings::setTerminalCopyOnSelect,
                "terminal copy select selection clipboard automatic primary middle click");
        switchRow(
                keys,
                cat,
                "terminal.clipboardWrites",
                true,
                Settings::isTerminalClipboardWrites,
                Settings::setTerminalClipboardWrites,
                "terminal clipboard osc 52 program copy yank remote ssh");
        switchRow(
                keys,
                cat,
                "terminal.notifications",
                true,
                Settings::isTerminalNotifications,
                Settings::setTerminalNotifications,
                "terminal notification desktop osc 9 777 alert finished build");
        switchRow(
                keys,
                cat,
                "terminal.confirmClose",
                false,
                Settings::isTerminalConfirmClose,
                Settings::setTerminalConfirmClose,
                "terminal close confirm running program ask kill");
        return p;
    }

    /** Re-reads the five TODO part-color pickers from settings (after a palette color change). */
    public void syncTodoPartColors() {
        if (todoSettings != null) {
            todoSettings.syncPartColors();
        }
    }

    /**
     * The objects that hold this window's controls: the window and the page classes it builds. {@link
     * SettingsModified} finds the controls there by field name.
     */
    List<Object> controlOwners() {
        return List.of(
                this,
                syncPage,
                pluginsPage,
                aiPages,
                debugPage,
                lspPage,
                keymapsPage,
                snippetPages,
                markdownPage,
                gitPages,
                renderPages,
                spellPage,
                webPage);
    }

    /** Settings → AI, AI Agent, AI Actions and MCP Server; see {@link SettingsAiPages}. */
    private final SettingsAiPages aiPages;

    /** Wires the security-notice confirm shown before the MCP checkbox enables the server. */
    public void setMcpConfirm(java.util.function.BooleanSupplier confirm) {
        aiPages.setMcpConfirm(confirm);
    }

    void setMcpActions(McpActions actions) {
        aiPages.setMcpActions(actions);
    }

    /** Injected by MainController: backs the AI Agent page's per-client PATH-detection rows + the active-
     *  client combo (which routes through the coordinator's single switch path). */
    void setAgentCoordinator(AgentCoordinator coordinator) {
        aiPages.setAgentCoordinator(coordinator);
    }

    /** Injected by MainController: runs a live AI connection check (green/red status on the AI page). */
    public void setAiConnectionProbe(
            java.util.function.BiConsumer<Boolean, java.util.function.BiConsumer<Boolean, String>> probe) {
        aiPages.setAiConnectionProbe(probe);
    }

    /** Settings → Mermaid, Diagrams and Typst; see {@link SettingsRenderToolPages}. */
    private final SettingsRenderToolPages renderPages;

    /** Settings → Git and GitHub; see {@link SettingsGitPages}. */
    private final SettingsGitPages gitPages;

    /** Settings → Markdown; see {@link SettingsMarkdownPage}. */
    private final SettingsMarkdownPage markdownPage;

    /**
     * What the active file's project markdownlint config does to the rule list below, as one localized line
     * ("" when none applies). Not observable: read again whenever the Markdown page is shown or synced.
     */
    public void setMarkdownLintConfigNote(java.util.function.Supplier<String> note) {
        markdownPage.setMarkdownLintConfigNote(note);
    }

    /** Settings → Snippets and Templates; see {@link SettingsSnippetPages}. */
    private final SettingsSnippetPages snippetPages;

    /** Injects the shared {@link com.editora.snippet.SnippetManager} backing the Snippets management page. */
    public void setSnippetManager(com.editora.snippet.SnippetManager snippetManager) {
        snippetPages.setSnippetManager(snippetManager);
    }

    /** Injects the shared {@link com.editora.template.TemplateRegistry} backing the Templates page. */
    public void setTemplateRegistry(com.editora.template.TemplateRegistry registry) {
        snippetPages.setTemplateRegistry(registry);
    }

    /** Another window (or a save in the editor) changed a user snippet file: the open Snippets page re-reads it. */
    public void reloadSnippetsPage() {
        snippetPages.reloadSnippetsPage();
    }

    /** Settings → Keymaps and Macros; see {@link SettingsKeymapsPage}. */
    final SettingsKeymapsPage keymapsPage;

    /** Injects the cross-window macro re-register hook (→ {@code MainController}); used by the Macros page. */
    public void setMacrosChangedHandler(Runnable handler) {
        keymapsPage.setMacrosChangedHandler(handler);
    }

    /** Re-reads the Macros page's list from the store — called after a macro changes from outside this
     *  window (e.g. a recording that was just stopped), so an already-open Settings window reflects it live
     *  instead of only on the next time the page is built. Harmless if the page was never opened. */
    public void refreshMacrosList() {
        keymapsPage.refreshMacrosList();
    }

    /** Settings → Language Servers; see {@link SettingsLspPage}. */
    private final SettingsLspPage lspPage;

    /** Installs a pinned command-line tool (shellcheck/shfmt) from the Bash card's Install buttons. */
    public void setInstallToolActions(java.util.function.Consumer<String> onInstallTool) {
        lspPage.setInstallToolActions(onInstallTool);
    }

    /** Wires the per-LSP-server Install buttons (json/bash/yaml/…) to {@code InstallCoordinator.installServer}. */
    public void setInstallServerActions(java.util.function.Consumer<String> onInstallServer) {
        lspPage.setInstallServerActions(onInstallServer);
    }

    /** Settings → Debugging; see {@link SettingsDebugPage}. */
    final SettingsDebugPage debugPage;

    /** Settings → Sync; see {@link SettingsSyncPage}. */
    private final SettingsSyncPage syncPage;

    /** Injects the application's settings sync (null in a window that has none, e.g. a test double). */
    void setSettingsSync(@Nullable SettingsSync sync) {
        syncPage.setSettingsSync(sync);
    }

    @Nullable
    SettingsSync settingsSync() {
        return syncPage.settingsSync();
    }

    /** The user changed synced data on a page of this window. */
    void markSyncDirty() {
        syncPage.markSyncDirty();
    }

    private VBox encryptionPage() {
        VBox p = page(tr("settings.cat.encryption"), tr("settings.encryption.subtitle"));
        Card c = card(p, tr("settings.encryption.gnupg"));
        encryptionGpgStatus = new Label(tr("settings.search.checking"));
        encryptionGpgStatus.getStyleClass().add("settings-git-status");
        encryptionGpgStatus.setWrapText(true);
        UiZoom.maxWidth(encryptionGpgStatus, 340);
        Button browse = browseButton(tr("settings.encryption.gpgPath"), encryptionGpgPathField);
        UiZoom.prefWidth(encryptionGpgPathField, PATH_FIELD_WIDTH);
        controlRow(
                c,
                Category.ENCRYPTION,
                tr("settings.encryption.gpgPath"),
                tr("settings.encryption.gpgPath.desc"),
                UiZoom.spacing(new HBox(encryptionGpgPathField, browse), 6),
                "encryption gnupg gpg pgp openpgp path executable command");
        controlRow(
                c,
                Category.ENCRYPTION,
                tr("settings.git.detected"),
                null,
                encryptionGpgStatus,
                "encryption gnupg gpg found installed not found version");
        Card c2 = card(p, tr("settings.encryption.behaviour"));
        checkRow(
                c2,
                Category.ENCRYPTION,
                encryptionIncludeSelfCheck,
                tr("settings.encryption.includeSelf.desc"),
                "encryption encrypt to self me my key recipient include");
        controlRow(
                c2,
                Category.ENCRYPTION,
                tr("settings.encryption.prompt"),
                tr("settings.encryption.prompt.desc"),
                encryptionPromptCombo,
                "encryption passphrase password pinentry prompt agent dialog ask");
        checkRow(
                c2,
                Category.ENCRYPTION,
                encryptionLspCheck,
                tr("settings.encryption.lsp.desc"),
                "encryption language server lsp privacy sensitive decrypted");
        return p;
    }

    /** Shows which gpg Encryption would run, and its version, probed off the FX thread. */
    private void refreshEncryptionStatus() {
        Label status = encryptionGpgStatus;
        if (status == null) {
            return;
        }
        int gen = ++encryptionProbeGen;
        String configured = config.getSettings().getEncryptionGpgPath();
        status.getStyleClass().setAll("settings-git-status");
        status.setText(tr("settings.search.checking"));
        Thread.ofVirtual().name("settings-gpg-probe").start(() -> {
            String exe = com.editora.crypto.Gpg.resolve(configured);
            String version = new com.editora.crypto.Gpg(exe, java.util.Map.of()).version();
            javafx.application.Platform.runLater(() -> {
                if (gen != encryptionProbeGen) {
                    return;
                }
                boolean found = version != null;
                status.getStyleClass()
                        .setAll("settings-git-status", found ? "settings-git-found" : "settings-git-missing");
                status.setText(
                        found ? tr("settings.encryption.found", version, exe) : tr("settings.encryption.notFound"));
            });
        });
    }

    private VBox searchPage() {
        VBox p = page(tr("settings.cat.search"), tr("settings.search.subtitle"));
        Card c = card(p, null);
        ripgrepStatusLabel = new Label(tr("settings.search.checking"));
        ripgrepStatusLabel.getStyleClass().add("settings-git-status");
        ripgrepStatusLabel.setWrapText(true);
        UiZoom.maxWidth(ripgrepStatusLabel, 340);
        checkRow(c, Category.SEARCH, ripgrepCheck, tr("settings.search.hint"), "search ripgrep rg find in files fast");
        Button rgBrowse = browseButton(tr("settings.search.ripgrepPath"), ripgrepCommandField);
        UiZoom.prefWidth(ripgrepCommandField, PATH_FIELD_WIDTH);
        controlRow(
                c,
                Category.SEARCH,
                tr("settings.search.ripgrepPath"),
                null,
                UiZoom.spacing(new HBox(ripgrepCommandField, rgBrowse), 6),
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
                tr("settings.search.gitignore.desc"),
                "search exclude gitignore ignored target node_modules build dist folders files");
        return p;
    }

    /**
     * The program a build tool's runs use when the project has no wrapper — the override, else the tool's
     * command — and whether it is there (a typo in the override used to give no sign: the project row is about
     * the project, not the command). Only a status: the override is saved either way.
     */
    private void refreshBuildCommandStatus(BuildTool bt) {
        ToolStatusRow status = buildToolCommandStatus.get(bt);
        if (status != null) {
            // npm: the manager the active project uses (pnpm, yarn, bun), not always npm (review E9).
            String command = bt == BuildTool.NPM ? npmManagerName() : bt.commandExample();
            String override = bt.commandIn(config.getSettings());
            if (bt == BuildTool.NPM && !com.editora.build.NpmPackageManager.overrideApplies(override, command)) {
                override = "";
            }
            List<String> argv = com.editora.build.BuildExecutable.resolve(List.of(), override, command);
            String found = ToolStatusRow.pathOf(argv);
            status.tool(ToolStatusRow.toolName(argv, command));
            status.show(!found.isEmpty(), found, bt.enabledIn(config.getSettings()));
        }
    }

    private final Map<BuildTool, ToolStatusRow> buildToolCommandStatus = new java.util.EnumMap<>(BuildTool.class);

    /** The package manager of the npm project detected for the active file, else npm. */
    private String npmManagerName() {
        for (BuildCoordinator c : buildCoordinators) {
            if (c.tool() == BuildTool.NPM && c.detectedManager() != null) {
                return c.detectedManager().manager();
            }
        }
        return "npm";
    }

    /** The kit's tool rows (D13): "<title> [command][Browse…]" (greyed with {@code master}), then "Detected". */
    void toolRows(
            Card c,
            Category cat,
            String title,
            @Nullable String description,
            @Nullable TextField field,
            @Nullable CheckBox master,
            ToolStatusRow status,
            String keywords) {
        if (field == null) {
            return; // the callers' maps hold a field for every row they build
        }
        UiZoom.prefWidth(field, PATH_FIELD_WIDTH);
        fullValueTip(field, title); // a 111-character server command was cut off, unnamed (XML/pom review 4, C36)
        HBox path = UiZoom.spacing(new HBox(field, browseButton(title, field)), 6);
        controlRow(c, cat, title, description, master == null ? path : dependent(master, path), keywords + " path");
        controlRow(c, cat, tr("settings.git.detected"), null, status.node(), keywords + " found not found install");
    }

    /** The Install… action of the LSP, Debugging, Mermaid and Typst rows (the injected installer, if any). */
    void installSupport(String key) {
        if (onInstallSupport != null) {
            onInstallSupport.accept(key);
        }
    }

    private VBox buildToolsPage() {
        VBox p = page(tr("settings.cat.buildTools"), tr("settings.buildTools.subtitle"));
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
        checkRow(
                javaCard,
                Category.BUILD_TOOLS,
                buildBeforeRunCheck,
                tr("settings.buildBeforeRun.hint"),
                "java build before run debug compile maven gradle launch main class stale");
        for (BuildTool bt : BuildTool.enabled()) {
            if (bt == BuildTool.NPM) {
                // One Node.js for Run, npm scripts, Debug, the language servers and HTTP scripts (review E102):
                // beside npm, never greyed out by another feature's switch.
                Card nodeCard = card(p, "Node.js");
                UiZoom.prefWidth(debugPage.nodePathField, PATH_FIELD_WIDTH);
                String node = tr("settings.debug.nodePath");
                controlRow(
                        nodeCard,
                        Category.BUILD_TOOLS,
                        node,
                        tr("settings.debug.nodePath.hint"),
                        UiZoom.spacing(
                                new HBox(debugPage.nodePathField, browseButton(node, debugPage.nodePathField)), 6),
                        "node nodejs node.js runtime nvm nvmrc version run debug npm typescript executable path");
            }
            Card c = card(p, bt.displayName());
            String kw = bt.id() + " build tool project detected enable command override toolbar";
            CheckBox enable = buildToolChecks.get(bt);
            if (enable != null) { // build() makes one per enabled tool
                checkRow(c, Category.BUILD_TOOLS, enable, tr("settings." + bt.id() + ".hint"), kw);
            }
            ToolStatusRow status = new ToolStatusRow(bt.commandExample());
            buildToolCommandStatus.put(bt, status);
            String title = tr("settings.buildTools.commandOverride");
            toolRows(
                    c,
                    Category.BUILD_TOOLS,
                    title,
                    null,
                    buildToolCommandFields.get(bt),
                    buildToolChecks.get(bt),
                    status,
                    kw);
            controlRow(
                    c,
                    Category.BUILD_TOOLS,
                    tr("settings.buildTools.project"),
                    null,
                    buildToolStatusLabels.get(bt),
                    kw);
            if (bt == BuildTool.MAVEN) {
                // pom.xml editing support (lemminx-maven) is an LSP server: say so here, where Maven users look.
                Hyperlink pomLsp = new Hyperlink(tr("settings.maven.pomLspLink"));
                pomLsp.setWrapText(true);
                pomLsp.setOnAction(e -> sidebar.getSelectionModel().select(Category.LSP));
                cardRow(c, Category.BUILD_TOOLS, pomLsp, kw + " pom.xml editing completion lemminx lsp install");
                UiZoom.prefWidth(mavenArchetypeCatalogField, PATH_FIELD_WIDTH + 120); // the default URL in full
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

    private VBox remotePage() {
        VBox p = page(tr("settings.cat.remote"), tr("settings.remote.subtitle"));
        Card mainCard = card(p, null);
        // Built first: the host section under each site follows it.
        remoteExecutionCheck = viewCheck(tr("settings.remote.execution"), Settings::setRemoteExecution);
        remoteSites = new RemoteSitesPane(config, this::apply, remoteExecutionCheck.selectedProperty(), site -> {
            if (onConnectRemote != null) {
                onConnectRemote.accept(site);
            }
        });
        cardRow(
                mainCard,
                Category.REMOTE,
                remoteSites.node(),
                "remote sftp ssh connection host user key password saved site server");
        Label note = note(tr("settings.remote.note"));
        note.setWrapText(true);
        UiZoom.maxWidth(note, 460);
        cardRow(mainCard, Category.REMOTE, note, "remote secret password passphrase not stored security");
        Card execCard = card(p, null);
        cardRow(
                execCard,
                Category.REMOTE,
                settingRow(
                        tr("settings.remote.execution"),
                        tr("settings.remote.execution.desc"),
                        switchFor(remoteExecutionCheck)),
                "remote run command execute ssh host server build git");
        containerEngineField = new TextField();
        containerEngineField.setPromptText("docker");
        UiZoom.prefWidth(containerEngineField, PATH_FIELD_WIDTH);
        commitOnEnterOrBlur(containerEngineField, () -> config.getSettings().getContainerEngine(), now -> {
            config.getSettings().setContainerEngine(now);
            apply();
        });
        controlRow(
                execCard,
                Category.REMOTE,
                tr("settings.remote.containerEngine"),
                tr("settings.remote.containerEngineHint"),
                containerEngineField,
                "container docker podman engine dev container devcontainer run tools");
        return p;
    }

    /** Opens Settings focused on the Remote page (the {@code remote.settings} command). */
    public void showRemote(Window owner) {
        showAt(Category.REMOTE, owner);
    }

    private VBox externalToolsPage() {
        VBox p = page(tr("settings.cat.externalTools"), tr("settings.externalTools.subtitle"));
        Card mainCard = card(p, null);
        cardRow(
                mainCard,
                Category.EXTERNAL_TOOLS,
                externalTools().node(),
                "external tools command cli macro stdin output console run formatter filter");
        Label macros = note(tr("settings.externalTool.macrosHelp"));
        macros.setWrapText(true);
        UiZoom.maxWidth(macros, 460);
        cardRow(mainCard, Category.EXTERNAL_TOOLS, macros, "external tools macros filepath selection linenumber");
        return p;
    }

    private ExternalToolsEditor externalTools() {
        externalToolsEditor = new ExternalToolsEditor(new ExternalToolsEditor.Hooks(
                () -> config.getSettings().getExternalTools(),
                tools -> {
                    config.getSettings().setExternalTools(tools);
                    apply(); // re-registers externalTool.run.*
                },
                keymapsPage::macroWarn,
                this::confirmDelete,
                keymapsPage::currentChordFor,
                (id, chord) -> {
                    if (shortcutActions != null) {
                        shortcutActions.rebind(id, chord);
                    }
                },
                id -> {
                    if (shortcutActions != null) {
                        shortcutActions.reset(id);
                    }
                },
                (field, commit) -> {
                    commitFields.add(field);
                    field.getProperties().put(COMMIT_KEY, commit);
                },
                () -> stage));
        return externalToolsEditor;
    }

    /** "Delete “name”?" with Cancel as the default button, so Enter does not delete. */
    private boolean confirmDelete(String name) {
        ButtonType delete = Dialogs.action(tr("confirm.action.delete"));
        Alert a = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("settings.externalTool.confirmDelete", name),
                delete,
                Dialogs.cancel()));
        a.initOwner(stage);
        a.setHeaderText(null);
        Dialogs.destructive(a, delete);
        return a.showAndWait().orElse(Dialogs.cancelOf(a)) == delete;
    }

    /** Deep-copies the persisted tools so the working list edits independently until persisted. */
    private VBox abbreviationsPage() {
        VBox p = page(tr("settings.cat.abbreviations"), tr("settings.abbrev.subtitle"));
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
                (abbreviationsEditor = new AbbreviationsEditor(
                                config,
                                this::apply,
                                keymapsPage::macroWarn,
                                name -> askBeforeLoss(
                                        tr("settings.lists.deleteTitle"),
                                        tr("settings.lists.deleteConfirm", name),
                                        tr("confirm.action.delete"))))
                        .node(),
                "abbrev abbreviation expand text replacement snippet shortcut dictionary emacs");
        return p;
    }

    private static <T> StringConverter<T> enumConverter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public @Nullable T fromString(String s) {
                return null;
            }
        };
    }

    /** The MCP page's link to the app-wide server: its endpoint ({@code ""} when not running), and two actions. */
    record McpActions(java.util.function.Supplier<String> endpoint, Runnable copyCommand, Runnable newToken) {}

    /**
     * The plugin picker is an overlay in the main window, which this window would cover: hidden when the picker
     * actually opens, so a registry that fails to load leaves Settings where it was, with the reason.
     *
     * @return whether it was showing (and is now hidden): the caller shows it again with {@link #restoreAfterPicker}
     */
    boolean hideForMainWindowPicker() {
        if (built && stage.isShowing()) {
            stage.hide();
            return true;
        }
        return false;
    }

    /** Shows this window again on the Plugins page once the picker it stepped aside for is done. */
    void restoreAfterPicker() {
        if (!built || stage.isShowing()) {
            return;
        }
        pluginsPage.forgetListKey();
        load();
        stage.show();
        sidebar.getSelectionModel().select(Category.PLUGINS);
        stage.toFront();
    }

    /** Says what a plugin action started here did, under the Browse buttons (error: in the warning colour). */
    void showPluginStatus(String message, boolean error) {
        pluginsPage.showPluginStatus(message, error);
    }

    /** This window's stage while it is open (the owner for plugin dialogs started here), else null. */
    @Nullable
    Window stageIfShowing() {
        return built && stage.isShowing() ? stage : null;
    }

    /** The page's "Turn on “X” to change these settings." note, shown while {@code master} is off. */
    void dependsOn(VBox page, CheckBox master) {
        page.getChildren().add(SettingsDependents.note(master.selectedProperty(), master.getText()));
    }

    /** {@code row} greyed while {@code master} is off (the {@link SettingsDependents} rule). */
    static <T extends Node> T dependent(CheckBox master, T row) {
        SettingsDependents.bind(master.selectedProperty(), row);
        return row;
    }

    /** A simple "label: control" row (no Browse button), used for the AI provider combo + endpoint URL. */
    HBox labeledRow(String label, javafx.scene.control.Control control) {
        HBox.setHgrow(control, Priority.ALWAYS);
        HBox box = UiZoom.spacing(new HBox(new Label(label), control), 6);
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
    void commitOnEnterOrBlur(TextField field, java.util.function.Supplier<String> stored, Consumer<String> commit) {
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
        field.getProperties().put(PENDING_KEY, (java.util.function.BooleanSupplier) () ->
                !java.util.Objects.toString(field.getText(), "").equals(java.util.Objects.toString(stored.get(), "")));
    }

    private static final Object PENDING_KEY = new Object();

    /** The commit-on-blur field the user is typing in, holding text not committed yet; null when none. */
    private @Nullable TextField typingField() {
        return stage.getScene() != null
                        && stage.getScene().getFocusOwner() instanceof TextField f
                        && f.getProperties().get(PENDING_KEY) instanceof java.util.function.BooleanSupplier pending
                        && pending.getAsBoolean()
                ? f
                : null;
    }

    /** Every {@link #commitOnEnterOrBlur} field, so closing the window can commit the one being typed in. */
    private final List<TextField> commitFields = new ArrayList<>();

    /**
     * Commits whatever is typed but not yet committed. A field commits when focus leaves it, and closing the
     * window (Esc, the title bar, the owner closing) is not a focus change the toolkit reliably reports first.
     */
    void commitPendingFields() {
        List.copyOf(commitFields).forEach(SettingsWindow::commitNow);
    }

    /**
     * The stored value to compare a URL field against. Such a field shows the URL in force, so an untouched
     * field equals {@code resolved}; once the user empties it to go back to the built-in URL it must be
     * compared with what is stored ({@code raw}), or every later focus loss would save and apply again.
     */
    static String shownOrBlank(TextField field, String resolved, String raw) {
        String text = field.getText();
        return text == null || text.isBlank() ? raw : resolved;
    }

    /** Commits a {@link #commitOnEnterOrBlur} field whose text was just set for the user (Browse…). */
    private static void commitNow(TextField field) {
        if (field.getProperties().get(COMMIT_KEY) instanceof Runnable run) {
            run.run();
        }
    }

    Button browseButton(String title, TextField field) {
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
    static void keepWidth(Region... controls) {
        for (Region control : controls) {
            control.setMinWidth(Region.USE_PREF_SIZE);
        }
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
            if (found && c.detectedManager() != null) {
                label.setText(label.getText() + " · " + c.detectedManager().describe()); // "pnpm (pnpm-lock.yaml)"
            }
            // What a build from the detected root actually runs as — the project's wrapper and its trust, the
            // override, or the PATH command — not just whether `mvn` is installed (review B32).
            String executable = found ? c.executableSummary() : null;
            if (executable != null && !executable.isBlank()) {
                label.setText(label.getText() + " · " + executable);
            }
            refreshBuildCommandStatus(c.tool());
        }
    }

    /**
     * Pill style for a tool's Found/Not found row: green when found; red only when the feature that needs it
     * is on — a tool missing for a feature that is off is a fact, not a fault, so it gets the neutral pill. Pure.
     */
    static String pillClass(boolean found, boolean featureOn) {
        return found ? "settings-git-found" : featureOn ? "settings-git-missing" : "settings-git-neutral";
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
        Card layout = card(p, null);
        Button restore = new Button(tr("settings.toolbar.restoreDefault"));
        restore.setOnAction(e -> {
            if (toolWindowSettings != null) { // made below, before the page shows
                toolWindowSettings.restoreDefaultLayout();
            }
        });
        cardRow(
                layout,
                Category.TOOL_WINDOWS,
                settingRow(tr("settings.toolWindows.layout"), tr("settings.toolWindows.layoutNote"), restore),
                "tool windows layout restore default reset project window");
        for (ToolWindow.Side side : ToolWindow.Side.values()) {
            toolWindowCards.put(side, card(p, sideName(side)));
        }
        toolWindowSettings = new ToolWindowsSettingsPane(toolWindows, config::getSettings, this::placeToolWindowRows);
        for (ToolWindowsSettingsPane.Row row : toolWindowSettings.rows()) {
            cardRow(
                    sideCard(toolWindows.currentSide(row.window())),
                    Category.TOOL_WINDOWS,
                    row.node(),
                    "tool window " + row.window().getTitle() + " placement side show");
        }
        toolWindowSettings.load();
        return p;
    }

    /** The Tool Windows page's card for {@code side}: the page makes one per side before any row is placed. */
    private Card sideCard(ToolWindow.Side side) {
        Card card = toolWindowCards.get(side);
        if (card == null) {
            throw new IllegalStateException("no Tool Windows card for " + side);
        }
        return card;
    }

    /**
     * Puts the Tool Windows rows under the card of their side, in stripe order, and moves each row's search
     * entry to that card (so a search hides a side's card once none of its rows match).
     */
    private void placeToolWindowRows(Map<ToolWindow.Side, List<Node>> bySide) {
        bySide.forEach((side, nodes) -> {
            Card card = sideCard(side);
            List<Node> shown = new ArrayList<>(nodes);
            if (shown.isEmpty()) {
                Label none = note(tr("settings.toolWindows.noneOnSide"));
                none.getStyleClass().add("settings-row");
                shown.add(none);
            }
            card.body().getChildren().setAll(shown);
            for (int i = 0; i < rows.size(); i++) {
                SettingsSearch.Row r = rows.get(i);
                if (nodes.contains(r.node()) && r.card() != card.box()) {
                    rows.set(i, new SettingsSearch.Row(r.page(), r.node(), r.keywords(), r.section(), card.box()));
                }
            }
        });
    }

    /**
     * Updates and Trusted Folders, on the Advanced page: neither is about the workspace, where they used to
     * sit and where nobody looked for them.
     */
    private void updatesAndTrustCards(VBox p) {
        Card updates = card(p, tr("settings.section.updates"));
        checkRow(
                updates,
                Category.ADVANCED,
                updateCheckCheck,
                tr("settings.updates.note"),
                "check for updates new version github release startup network privacy");

        Card trust = card(p, tr("settings.section.trustedFolders"));
        Label trustNote = note(tr("settings.trustedFolders.note"));
        trustNote.setWrapText(true);
        UiZoom.maxWidth(trustNote, 440);
        cardRow(trust, Category.ADVANCED, trustNote, "workspace trust build wrapper mvnw gradlew security");
        UiZoom.prefHeight(trustedFoldersList, 120);
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
        cardRow(trust, Category.ADVANCED, trustedFoldersList, "workspace trust trusted folders list revoke");
        cardRow(
                trust,
                Category.ADVANCED,
                UiZoom.spacing(new HBox(revoke, revokeAll), 8),
                "workspace trust revoke remove trusted folder");
    }

    private VBox advancedPage() {
        VBox p = page(tr("settings.cat.advanced"), tr("settings.advanced.subtitle"));
        Card fileSection = card(p, tr("settings.section.file"));
        Hyperlink link = new Hyperlink(displaySettingsPath(config.getSettingsFile()));
        link.setTooltip(ControlNames.tooltip(tr("settings.openFileTip")));
        link.setOnAction(e -> {
            if (onOpenFile != null) {
                onOpenFile.accept(config.getSettingsFile());
            }
        });
        Label pathLabel = new Label(tr("settings.path"));
        Label sessionLabel = new Label(tr("settings.sessionLog"));
        keepWidth(pathLabel, sessionLabel); // a long path gives way, not its label
        HBox fileRow = UiZoom.spacing(new HBox(pathLabel, link), 6);
        fileRow.setAlignment(Pos.CENTER_LEFT);
        cardRow(fileSection, Category.ADVANCED, fileRow, "settings file path toml config location");
        cardRow(fileSection, Category.ADVANCED, fileNotes.node(), "hand edit json reload apply restart unknown key");

        Path sessionLog = DebugLog.sessionFile(config.getConfigDir());
        Hyperlink sessionLink = new Hyperlink(sessionLog == null ? "" : displaySettingsPath(sessionLog));
        sessionLink.setTooltip(ControlNames.tooltip(tr("settings.openFileTip")));
        sessionLink.setOnAction(e -> {
            if (onOpenFile != null && sessionLog != null) {
                onOpenFile.accept(sessionLog);
            }
        });
        HBox sessionRow = UiZoom.spacing(new HBox(sessionLabel, sessionLink), 6);
        sessionRow.setAlignment(Pos.CENTER_LEFT);
        cardRow(
                fileSection,
                Category.ADVANCED,
                sessionRow,
                "session log debug crash report editora-session.log diagnostics");

        Card resetSection = card(p, tr("settings.section.reset"));
        Button reset = new Button(tr("settings.resetDefaults"));
        reset.setOnAction(e -> resetAll());
        Button restore = new Button(tr("settings.restore.button"));
        restore.setOnAction(e -> resetFlow().restore(stage));
        cardRow(
                resetSection,
                Category.ADVANCED,
                UiZoom.spacing(new HBox(reset, restore), 8),
                "reset defaults restore factory clear backup undo");

        Card ioSection = card(p, tr("settings.section.io"));
        Button exportConfig = new Button(tr("settings.exportConfig"));
        exportConfig.setOnAction(e -> {
            if (onExportConfig != null) {
                onExportConfig.run();
            }
        });
        Label exportHint = note(tr("settings.exportConfig.hint"));
        exportHint.setWrapText(true);
        VBox exportBox = UiZoom.spacing(new VBox(exportConfig, exportHint), 4);
        cardRow(ioSection, Category.ADVANCED, exportBox, "export backup settings config zip archive");
        Button importConfig = new Button(tr("settings.importConfig"));
        importConfig.setOnAction(e -> {
            if (runCommand != null) {
                runCommand.accept("config.import");
            }
        });
        Label importHint = note(tr("settings.importConfig.hint"));
        importHint.setWrapText(true);
        cardRow(
                ioSection,
                Category.ADVANCED,
                UiZoom.spacing(new VBox(importConfig, importHint), 4),
                "import restore backup settings config zip archive move computer");

        Card debugSection = card(p, tr("settings.section.debug"));
        Button debugLog = new Button(tr("settings.debugLog"));
        debugLog.setOnAction(e -> {
            if (onShowDebugLog != null) {
                onShowDebugLog.run();
            }
        });
        Label debugHint = note(tr("settings.debugLog.hint"));
        debugHint.setWrapText(true);
        VBox debugBox = UiZoom.spacing(new VBox(debugLog, debugHint), 4);
        cardRow(
                debugSection,
                Category.ADVANCED,
                debugBox,
                "application log debug log logs errors warnings exceptions diagnostics bug report console");
        updatesAndTrustCards(p); // moved from Workspace (B20)
        return p;
    }

    /**
     * The Toolbar customization page: an "Available items" list, a "Current toolbar" list (item ids +
     * separators, in bar order), and Add/Remove/Move/Add-Separator/Restore-Default controls. Every edit is
     * live-applied through {@link #toolbarActions} (persist + rebuild every window's toolbar).
     */
    private Region toolbarPage() {
        VBox p = page(tr("settings.cat.toolbar"), tr("settings.toolbar.subtitle"));
        // The on/off switch for the bar itself, also on the Interface page: it is what people look for here.
        checkRow(card(p, null), Category.TOOLBAR, toolbarCheck, null, "show hide toolbar buttons");
        Card sec = card(p, tr("settings.toolbar.section"));
        Label desc = note(tr("settings.toolbar.note"));
        desc.setWrapText(true);
        UiZoom.maxWidth(desc, 560);
        cardRow(sec, Category.TOOLBAR, desc, "toolbar customize icons order add remove reorder drag");
        toolbarSettings = new ToolbarSettingsPane(() -> toolbarActions);
        refreshToolbarLists = toolbarSettings::refresh;
        cardRow(sec, Category.TOOLBAR, toolbarSettings.lists(), "toolbar available current add remove up down move");
        cardRow(sec, Category.TOOLBAR, toolbarSettings.bottomButtons(), "toolbar separator restore default reset");
        return p;
    }

    // --- page helpers ---

    VBox page(String title) {
        // A Text node (with VISUAL bounds) rather than a Label: a JavaFX Label clips its text to the
        // glyphs' logical (advance-based) bounds, which shaves the outer strokes of bold faces — the
        // bold heading on every Settings page looked like it was missing slivers of letters. A Text node
        // renders the full glyph ink and doesn't self-clip. Styled via .settings-page-title (Text honors
        // -fx-font-* and -fx-fill).
        javafx.scene.text.Text heading = new javafx.scene.text.Text(title);
        heading.setBoundsType(javafx.scene.text.TextBoundsType.VISUAL);
        heading.getStyleClass().add("settings-page-title");
        VBox box = UiZoom.spacing(new VBox(heading), 10);
        box.getStyleClass().add("settings-page");
        return UiZoom.padding(box, 4, 4, 4, 16);
    }

    /** A page with the kit's one-line subtitle under its heading, saying what the page is for. */
    VBox page(String title, String subtitle) {
        VBox box = page(title);
        if (subtitle != null && !subtitle.isBlank()) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("settings-page-subtitle");
            sub.setWrapText(true);
            UiZoom.maxWidth(sub, 520);
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

    void row(VBox page, Category cat, @Nullable Label section, Node node, String keywords) {
        page.getChildren().add(node);
        rows.add(new SettingsSearch.Row(cat, node, keywords, section, null));
    }

    // --- card rows (UI Kit v1) ---------------------------------------------------------------------
    //
    // A card groups related settings into one bordered block, and each row states its setting as a
    // title + a one-line description with the control on the right — instead of a bare CheckBox whose
    // own label has to carry the whole explanation. Pages opt in one at a time; `page`/`section`/`row`
    // above are unchanged, so an unmigrated page renders exactly as before.

    /** A card and the box its rows are appended to (the title, if any, sits above that box). */
    record Card(VBox box, VBox body) {}

    Card card(VBox page, @Nullable String title) {
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
    void cardRow(Card card, Category cat, Node node, String keywords) {
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
        rows.add(new SettingsSearch.Row(cat, row, keywords, null, card.box()));
    }

    /**
     * A checkbox as a kit card row with zero new i18n: the row title is the checkbox's own localized
     * label, the (optional) description is an existing localized string, and the control is the bound
     * toggle switch. This is what makes migrating a page mechanical rather than a translation project.
     */
    void checkRow(Card card, Category cat, CheckBox check, @Nullable String description, String keywords) {
        cardRow(card, cat, settingRow(check.getText(), description, switchFor(check)), keywords);
    }

    /** The nodes of the rows added since {@code from} (a {@code rows.size()} taken before adding them). */
    Node[] rowsSince(int from) {
        return rows.subList(from, rows.size()).stream()
                .map(SettingsSearch.Row::node)
                .toArray(Node[]::new);
    }

    /** A labeled field/control as a card row: title left, the control right (kit "Git command" shape). */
    void controlRow(
            Card card,
            Category cat,
            String title,
            @Nullable String description,
            @Nullable Node control,
            String keywords) {
        cardRow(card, cat, settingRow(title, description, control), keywords);
    }

    /**
     * A card row: title + optional description on the left, the control on the right (below the text when
     * the control is too wide to leave the description a readable column — see {@link SettingRowPane}).
     * The title is the control's label for assistive technology, and names an on/off switch outright.
     */
    static Node settingRow(String title, @Nullable String description, @Nullable Node control) {
        Label t = new Label(title);
        t.getStyleClass().add("settings-row-title");
        t.setWrapText(true); // a long (translated) title beside a switch wraps; it used to end in "…"
        VBox main = UiZoom.spacing(new VBox(t), 2);
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
    static SettingSwitch switchFor(CheckBox check) {
        return SettingSwitch.boundTo(check);
    }

    private Region labeled(String label, Node control) {
        Label l = new Label(label);
        // Floor the width to 130 so short labels line up into a tidy column, but let a longer label grow to
        // its full text (maxWidth = pref) instead of ellipsizing — e.g. "Max size / project (MB)" or any
        // longer translation. (Previously a fixed prefWidth(130) clamped + truncated the longer ones.)
        UiZoom.minWidth(l, 130).setMaxWidth(Region.USE_PREF_SIZE);
        l.setLabelFor(control);
        HBox h = UiZoom.spacing(new HBox(l, control), 10);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-hint");
        return l;
    }

    /** The kit's boxed page note (its {@code .set-note}) — a tinted, rounded paragraph, not a bare hint. */
    static Label noteBox(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-note-box");
        l.setWrapText(true);
        return UiZoom.maxWidth(l, 520);
    }

    /** A small "i" info glyph carrying a hover {@link Tooltip} — for a compact detail that would otherwise
     *  need its own {@link #note} line (e.g. listing exactly which commands a toggle enables). */
    static Node infoIcon(String tooltipText) {
        Node icon = Icons.about();
        icon.getStyleClass().add("settings-info-icon");
        Tooltip tip = ControlNames.tooltip(tooltipText);
        tip.setWrapText(true);
        UiZoom.follow(icon, () -> tip.setMaxWidth(UiZoom.px(320)));
        Tooltip.install(icon, tip);
        return icon;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
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

    private void filter(String typed) {
        searchHiddenCats.clear();
        searchHiddenGroups.clear();
        // "Modified" (or @modified typed in the search) lists only the rows that differ from their defaults.
        boolean onlyModified = modifiedOnly.isSelected() || SettingsSearch.wantsModified(typed);
        search.setRowFilter(
                onlyModified
                        ? r -> SettingRowState.peek(r.node()) instanceof SettingRowState st && st.isModified()
                        : null);
        String query = SettingsSearch.withoutModifiedToken(typed);
        SettingsSearch.Result found = search.filter(query);
        keymapsPage.pushShortcutQuery(query);
        if (found != null) {
            for (Category c : Category.values()) {
                if (!found.pages().contains(c)) {
                    searchHiddenCats.add(c);
                }
            }
            for (Group g : Group.values()) {
                if (found.pages().stream().noneMatch(c -> ((Category) c).group == g)) {
                    searchHiddenGroups.add(g);
                }
            }
        }
        sidebar.refresh();
        // No hit anywhere: say so, instead of a blank page beside a fully greyed-out sidebar.
        searchEmpty.setText(
                found == null
                        ? ""
                        : query.isBlank()
                                ? tr("settings.search.noneModified")
                                : tr("settings.search.empty", query.strip()));
        searchEmpty.setVisible(found != null && found.pages().isEmpty());
        showContent();
        Object sel = sidebar.getSelectionModel().getSelectedItem();
        if (found != null && found.best() != null && !found.ties(sel)) {
            sidebar.getSelectionModel().select(found.best());
        }
    }

    /** Renders a sidebar row: a {@link Group} as a non-selectable header, a {@link Category} as an item. */
    private final class CategoryCell extends ListCell<Object> {
        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("settings-group-header", "settings-sidebar-item");
            setGraphic(null);
            setContentDisplay(ContentDisplay.LEFT); // a reused category row was GRAPHIC_ONLY
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
                setText(c.display); // hidden by GRAPHIC_ONLY, but still the row's accessible name
                getStyleClass().add("settings-sidebar-item");
                setMouseTransparent(false);
                setDisable(searchHiddenCats.contains(c));
                // A ListCell has one graphic, and the row needs two beside its name — the category's
                // glyph before it, the modified-row count and the "Beta" pill after — so the name is a
                // Label in the row and the cell shows only that.
                Label name = new Label(c.display);
                name.getStyleClass().add("settings-sidebar-name");
                name.setMinWidth(0); // ellipsize rather than push the badges off the rail
                HBox row = UiZoom.spacing(new HBox(c.icon.get(), name), 8);
                row.setAlignment(Pos.CENTER_LEFT);
                Node badges = SettingsFrameBits.sidebarBadges(c.beta, modifiedPerPage.getOrDefault(c, 0));
                if (badges != null) {
                    // 6 px from the name, as before
                    UiZoom.follow(badges, () -> HBox.setMargin(badges, UiZoom.insets(0, 0, 0, -2)));
                    row.getChildren().add(badges);
                }
                setGraphic(row);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
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
        double zoom = config.getSettings().getFontZoom();
        int shown = Math.max(1, (int) Math.round(fontSize.getValue() * zoom)); // as the editor computes it
        preview.setStyle("-fx-font-family: \"" + fontFamily.getValue() + "\"; -fx-font-size: " + shown + "px;");
        if (zoomNote != null) {
            boolean zoomed = Math.abs(zoom - 1.0) > 1e-9;
            zoomNote.setVisible(zoomed);
            zoomNote.setManaged(zoomed);
            ((Label) zoomNote.getChildren().getFirst())
                    .setText(tr(
                            "settings.fontZoom.note", String.valueOf(Math.round(zoom * 100)), String.valueOf(shown)));
        }
    }

    // --- reset -----------------------------------------------------------------------------------

    private void resetAll() {
        resetFlow().resetAll(stage);
    }

    /** Reset to Defaults and Restore from backup, applied through {@link #commitReset}. */
    private SettingsResetFlow resetFlow() {
        return new SettingsResetFlow(config, this::commitReset, text -> {
            if (onStatus != null) {
                onStatus.accept(text);
            }
        });
    }

    /** Persists + applies a reset, re-themes the app, and reloads the controls + preview. */
    private void commitReset() {
        Settings s = config.getSettings();
        config.save();
        Themes.applyUiZoom(s.getUiZoom());
        Themes.applyAppTheme(s.getTheme());
        onApply.accept(s);
        load();
        // load() sets the keymap combo under `loading`, so its listener never fires: without this the
        // settings file says "default keymap" while every window keeps dispatching the old one.
        if (onKeymapChanged != null) {
            onKeymapChanged.run();
        }
        keymapsPage.refreshShortcuts();
    }

    // --- load + sync (unchanged behavior) --------------------------------------------------------

    /**
     * Re-reads every list editor whose working copy is written back whole (External Tools, Abbreviations,
     * Remote sites). Each is a snapshot of a shared store that a command, a tool window or another window's
     * Settings can change; saving a stale snapshot deleted whatever had been added since. Runs whenever the
     * window is (re)loaded and whenever it regains focus — the two moments an edit can follow a change made
     * elsewhere. Field edits commit on focus loss, so nothing typed is pending when it runs.
     */
    private void reloadStoreBackedEditors() {
        if (externalToolsEditor != null) {
            externalToolsEditor.reload();
        }
        if (abbreviationsEditor != null) {
            abbreviationsEditor.reload();
        }
        if (remoteSites != null) {
            remoteSites.reload();
        }
    }

    /** Re-reads the Snippets and Templates pages after a settings sync wrote those files. */
    void syncFileBackedEditors() {
        if (built && stage.isShowing() && !loading) {
            reloadFileBackedEditors();
        }
    }

    /** The Snippets and Templates pages read files; refreshed when Settings is opened, not on every load. */
    private void reloadFileBackedEditors() {
        snippetPages.reload();
    }

    /**
     * Project scope (F4): the User / Project switch, the two save rules that exist only per project, and how
     * this window reaches its project (its {@link LspCoordinator}, which owns the window's project scope).
     */
    final ProjectScopeSettings scopeUi = new ProjectScopeSettings(
            this::projectScope, this::apply, this::isLoading, this::load, this::trustProject, () -> stage);

    private final ComboBox<String> trimOnSaveCombo = scopeUi.projectOnlyCombo("editor.trimTrailingWhitespace");
    private final ComboBox<String> finalNewlineCombo = scopeUi.projectOnlyCombo("editor.insertFinalNewline");

    @Nullable
    LspCoordinator projectLsp;

    /** How the User / Project bar names the window's project: the name the main window's title shows. */
    void setProjectName(java.util.function.Supplier<String> projectName) {
        scopeUi.setProjectName(projectName);
    }

    /** Injected by MainController: the window's language-server coordinator, which owns its project scope. */
    void setProjectLsp(LspCoordinator lsp) {
        this.projectLsp = lsp;
        lspPage.setLspProjectNotes(id -> SettingsBashRows.projectNote(lsp, id));
    }

    private ProjectScope projectScope() {
        return projectLsp == null ? ProjectScope.NONE : projectLsp.projectScope();
    }

    private boolean isLoading() {
        return loading;
    }

    private void trustProject() {
        if (projectLsp != null) {
            projectLsp.trustProjectSettings(); // asks first; applies the project's values once trusted
        }
        load();
    }

    /** Registers every project-scopable control with the User / Project switch. */
    private void registerScopedControls() {
        scopeUi.register("editor.indentStyle", indentStyleCombo, v -> indentStyleCombo.setValue((String) v));
        scopeUi.register(
                "editor.tabSize",
                tabSizeSpinner,
                v -> tabSizeSpinner.getValueFactory().setValue((Integer) v));
        scopeUi.register("editor.rulers", settingControls.get("rulers"), v -> {});
        scopeUi.register("editor.languageRulers", settingControls.get("languageRulers"), v -> {});
        scopeUi.register("editor.wordWrap", wordWrapCheck, v -> wordWrapCheck.setSelected((Boolean) v));
        scopeUi.register("editor.wordWrapProse", wordWrapProseCheck, v -> wordWrapProseCheck.setSelected((Boolean) v));
        scopeUi.register(
                "editor.spellLanguage",
                spellPage.spellLanguageCombo,
                v -> spellPage.spellLanguageCombo.setValue((String) v));
        buildToolCommandFields.forEach((bt, field) ->
                scopeUi.register("build." + ProjectScope.toolId(bt), field, v -> field.setText((String) v)));
        debugPage.debugCommandFields.forEach(
                (id, field) -> scopeUi.register("debug." + id, field, v -> field.setText((String) v)));
        lspPage.lspCommandFields.forEach(
                (id, field) -> scopeUi.register("lspCommands." + id, field, v -> field.setText((String) v)));
        lspPage.lspEnableChecks.forEach(
                (id, check) -> scopeUi.register("lspEnabled." + id, check, v -> check.setSelected((Boolean) v)));
    }

    private void load() {
        // Another window's change re-reads every control: the field being typed in keeps what was typed.
        TextField typing = typingField();
        String typed = typing == null ? null : typing.getText();
        int caret = typing == null ? 0 : typing.getCaretPosition();
        loading = true;
        try {
            reloadStoreBackedEditors(); // a command or another window may have changed a store since the build
            spellPage.refreshDictionaryList(); // pick up words added elsewhere (e.g. "Add to Dictionary") since last
            // open
            if (refreshToolbarLists != null) {
                refreshToolbarLists.run(); // reflect any on-bar drag customization since the page was built
            }
            Settings settings = config.getSettings();
            if (!fontFamily.getItems().contains(settings.getFontFamily())) {
                fontFamily.getItems().add(0, settings.getFontFamily());
            }
            languageCombo.setValue(settings.getUiLanguage());
            keymapsPage.keymapCombo.setValue(settings.getKeymap());
            fontFamily.setValue(settings.getFontFamily());
            fontSize.getValueFactory().setValue(settings.getFontSize());
            uiZoom.getValueFactory().setValue(settings.getUiZoom());
            String theme = Themes.normalize(settings.getTheme());
            if (!theme.equals(settings.getTheme())) {
                settings.setTheme(theme);
            }
            themeCombo.setValue(theme);
            String editorTheme = EditorThemes.normalize(settings.getEditorTheme());
            if (!editorTheme.equals(settings.getEditorTheme())) {
                settings.setEditorTheme(editorTheme);
            }
            editorThemeCombo.setValue(settings.isEditorThemeUserSet() ? editorTheme : "");
            tabSizeSpinner.getValueFactory().setValue(settings.getTabSize());
            indentStyleCombo.setValue(settings.getIndentStyle());
            fillColumnSpinner.getValueFactory().setValue(settings.getFillColumn());
            largeFileThresholdSpinner.getValueFactory().setValue(settings.getLargeFileThreshold());
            columnRulerCheck.setSelected(settings.isShowColumnRuler());
            lineHighlightCheck.setSelected(settings.isHighlightCurrentLine());
            lineNumbersCheck.setSelected(settings.isShowLineNumbers());
            minimapCheck.setSelected(settings.isShowMinimap());
            wordWrapCheck.setSelected(settings.isWordWrap());
            syncMarkdownControls(settings);
            adminSaveCheck.setSelected(settings.isAdminSave());
            whitespaceCheck.setSelected(settings.isShowWhitespace());
            notesCheck.setSelected(settings.isNotesSupport());
            noteIndicatorsCheck.setSelected(settings.isShowNoteIndicators());
            autocompleteCheck.setSelected(settings.isAutocomplete());
            autocompleteProseCheck.setSelected(settings.isAutocompleteProse());
            autocompleteSnippetsCheck.setSelected(settings.isAutocompleteSnippets());
            snippetTabExpansionCheck.setSelected(settings.isSnippetTabExpansion());
            if (remoteExecutionCheck != null && containerEngineField != null) { // the Remote page makes both
                remoteExecutionCheck.setSelected(settings.isRemoteExecution());
                containerEngineField.setText(settings.getContainerEngine());
            }
            autocompleteMermaidCheck.setSelected(settings.isAutocompleteMermaid());
            autocompleteProseCheck.setDisable(!settings.isAutocomplete());
            autocompleteSnippetsCheck.setDisable(!settings.isAutocomplete());
            autocompleteMermaidCheck.setDisable(!settings.isAutocomplete());
            completionDocCheck.setSelected(settings.isCompletionDoc());
            semanticHighlightCheck.setSelected(settings.isSemanticHighlight());
            inlayHintsCheck.setSelected(settings.isInlayHints());
            codeLensCheck.setSelected(settings.isCodeLens());
            lightbulbCheck.setSelected(settings.isLspLightbulb());
            problemCounterCheck.setSelected(settings.isProblemCounter());
            inlayHintModeCombo.setValue(settings.getInlayHintMode());
            onTypeFormattingCheck.setSelected(settings.isLspOnTypeFormatting());
            pasteImportsCheck.setSelected(settings.isLspPasteImports());
            smartSemicolonCheck.setSelected(settings.isLspSmartSemicolon());
            javaOrganizeImportsOnSaveCheck.setSelected(settings.isJavaOrganizeImportsOnSave());
            javaFormatOnSaveCheck.setSelected(settings.isJavaFormatOnSave());
            javaCleanupOnSaveCheck.setSelected(settings.isJavaCleanupOnSave());
            showJavaCleanupsCount();
            pdfLineNumbersCheck.setSelected(settings.isPdfLineNumbers());
            pdfHighlightCheck.setSelected(settings.isPdfSyntaxHighlighting());
            pdfPageFooterCheck.setSelected(settings.isPdfPageFooter());
            pdfPageSizeCombo.setValue(settings.getPdfPageSize());
            pdfOrientationCombo.setValue(settings.getPdfOrientation());
            pdfMarginsCombo.setValue(settings.getPdfMargins());
            pdfCodeFontSizeCombo.setValue(settings.getPdfCodeFontSize());
            spellPage.load(settings);
            menuBarCheck.setSelected(settings.isShowMenuBar());
            toolbarCheck.setSelected(settings.isShowToolbar());
            statusBarCheck.setSelected(settings.isShowStatusBar());
            tabBarCheck.setSelected(settings.isShowTabBar());
            breadcrumbCheck.setSelected(settings.isShowBreadcrumb());
            simpleModeCheck.setSelected(settings.isSimpleMode());
            snippetPages.templateAuthorField.setText(settings.getAuthorNameRaw());
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
            buildBeforeRunCheck.setSelected(settings.isBuildBeforeRun());
            mavenCentralIndexCheck.setSelected(settings.isMavenPomCentralIndex());
            javaDownloadSourcesCheck.setSelected(settings.isJavaDownloadSources());
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
            htmlMarkupChecksCheck.setSelected(settings.isHtmlMarkupChecks());
            todoHighlightCheck.setSelected(settings.isTodoHighlight());
            if (todoSettings != null) {
                todoSettings.load();
            }
            markdownPage.rebuildMarkdownLintRules();
            multiCaretCheck.setSelected(settings.isMultiCaret());
            copyLineNoSelectionCheck.setSelected(settings.isCopyLineWhenNoSelection());
            copyWithHighlightingCheck.setSelected(settings.isCopyWithSyntaxHighlighting());
            projectsCheck.setSelected(settings.isProjectSupport());
            if (toolWindowSettings != null) {
                toolWindowSettings.load();
            }
            gitPages.loadGit(settings);
            syncPage.load(settings);
            gitPages.loadGithub(settings);
            updateCheckCheck.setSelected(settings.isUpdateCheck());
            localHistoryCheck.setSelected(settings.isLocalHistory());
            crashRecoveryCheck.setSelected(settings.isCrashRecovery());
            historyMaxPerFileSpinner.getValueFactory().setValue(settings.getHistoryMaxPerFile());
            historyMaxAgeSpinner.getValueFactory().setValue(settings.getHistoryMaxAgeDays());
            historyMaxTotalSpinner.getValueFactory().setValue(settings.getHistoryMaxTotalMb());
            updateHistoryRowsEnabled();
            renderPages.load(settings);
            for (BuildTool bt : BuildTool.enabled()) {
                CheckBox enable = buildToolChecks.get(bt);
                TextField command = buildToolCommandFields.get(bt);
                if (enable != null && command != null) { // build() makes both for every enabled tool
                    enable.setSelected(bt.enabledIn(settings));
                    command.setText(bt.commandIn(settings));
                }
                refreshBuildCommandStatus(bt);
                if (bt == BuildTool.MAVEN) {
                    JdkChoice.select(mavenJdkCombo, settings.getMavenJdkHome());
                    mavenArchetypeCatalogField.setText(settings.getMavenArchetypeCatalogUrl());
                }
            }
            refreshBuildToolStatus();
            encryptionGpgPathField.setText(settings.getEncryptionGpgPath());
            encryptionIncludeSelfCheck.setSelected(settings.isEncryptionIncludeSelf());
            encryptionPromptCombo.setValue(settings.getEncryptionPassphrasePrompt());
            encryptionLspCheck.setSelected(settings.isEncryptionLanguageServers());
            refreshEncryptionStatus();
            ripgrepCheck.setSelected(settings.isRipgrepSearch());
            searchGitignoreCheck.setSelected(settings.isSearchRespectGitignore());
            ripgrepCommandField.setText(settings.getRipgrepCommand());
            refreshRipgrepStatus();
            webPage.load(settings);
            aiPages.load(settings);
            pluginsPage.load(settings);
            debugPage.load(settings);
            lspPage.load(settings);
            zenCheck.setSelected(config.getWorkspaceState().isZenMode());
            expertCheck.setSelected(config.getWorkspaceState().isExpertMode());
            String mode = MainController.autoSaveModeOf(settings.getAutoSave());
            autoSaveCombo.setValue(mode);
            autoSaveDelaySpinner.getValueFactory().setValue(Math.max(1, (int)
                    Math.round(settings.getAutoSaveDelayMillis() / 1000.0)));
            autoSaveDelaySpinner.setDisable(!FileWorkflowCoordinator.AUTOSAVE_DELAY.equals(mode));
            scopeUi.showProjectValues(); // Project mode: the project's values where its file sets them
            if (typing != null) {
                typing.setText(typed);
                typing.positionCaret(caret);
            }
        } finally {
            loading = false;
        }
        scopeUi.refresh();
        applyPreviewTheme(EditorThemes.normalize(config.getSettings().getEditorTheme()));
        updatePreviewFont();
        fileNotes.show(config.shared().settingsFileIssues());
        refreshModified();
        // Once per open and per command: a sync from another window used to spawn `git --version` each time.
        String gitCommand = config.getSettings().getGitPath();
        if (!java.util.Objects.equals(gitCommand, gitProbedFor)) {
            gitProbedFor = gitCommand;
            gitPages.probeGit();
        }
    }

    private @Nullable String gitProbedFor; // the git command last probed while this window is open; null = probe again

    /** Re-applies the Tool Windows rows' feature gates (Projects, Git, Notes, LSP, Debugging changed). */
    void refreshToolWindowRows() {
        if (toolWindowSettings != null) {
            toolWindowSettings.refresh();
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
            refreshToolWindowRows();
        } finally {
            loading = prev;
        }
    }

    /** Injects the live-reload hook run when the keymap picker changes (→ MainController). */
    public void setOnKeymapChanged(Runnable handler) {
        this.onKeymapChanged = handler;
    }

    /** One command row in the keybinding editor: id, localized title, and current effective chord (or null). */
    public record Shortcut(
            String id, String title, @Nullable String chord) {}

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

        /** {@code chordSeq} in the active keymap's notation — what the list shows ({@code Ctrl+S}, {@code ⌘S}). */
        default String display(String chordSeq) {
            return chordSeq;
        }

        /** Unbinds {@code commandId}: its own bindings and its keymap defaults. */
        default void clear(String commandId) {}

        /** The default chords of {@code commandId} another command's override now holds (Reset takes them back). */
        default java.util.List<com.editora.command.KeybindingEdits.Conflict> takenDefaults(String commandId) {
            return java.util.List.of();
        }

        /** Drops every binding of a command that is gone (a deleted macro), in every keymap. */
        default void forget(String commandId) {
            reset(commandId);
        }
    }

    /** Injects the keybinding-editor backend (→ MainController); enables the shortcuts list. */
    public void setShortcutActions(ShortcutActions actions) {
        this.shortcutActions = actions;
        keymapsPage.refreshShortcuts();
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
            keymapsPage.keymapCombo.setValue(config.getSettings().getKeymap());
        } finally {
            loading = prev;
        }
        keymapsPage.refreshShortcuts();
    }

    /** Re-reads the inline-blame checkbox from settings (used after the {@code git.toggleBlame} command). */
    public void syncGitBlameCheck() {
        if (!built) {
            return;
        }
        gitPages.syncGitBlameCheck();
    }

    /** Re-reads the "enable plugins" checkbox from settings (used after the palette toggle command). */
    public void syncPluginsCheck() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            pluginsPage.syncCheck();
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
        lspPage.syncLspCheck();
    }

    /** Re-syncs the HTML Live Preview checkbox to the current setting (after a palette toggle). */
    public void syncHtmlPreviewCheck() {
        if (!built) {
            return;
        }
        webPage.syncHtmlPreviewCheck();
    }

    public void syncMcpCheck() {
        if (!built) {
            return;
        }
        aiPages.syncMcpCheck();
    }

    public void syncAgentCheck() {
        if (!built) {
            return;
        }
        aiPages.syncAgentCheck();
    }

    public void syncAiCheck() {
        if (!built) {
            return;
        }
        aiPages.syncAiCheck();
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
            buildBeforeRunCheck.setSelected(config.getSettings().isBuildBeforeRun());
            mavenCentralIndexCheck.setSelected(config.getSettings().isMavenPomCentralIndex());
            javaDownloadSourcesCheck.setSelected(config.getSettings().isJavaDownloadSources());
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
            lightbulbCheck.setSelected(s.isLspLightbulb());
            problemCounterCheck.setSelected(s.isProblemCounter());
            inlayHintModeCombo.setValue(s.getInlayHintMode());
            onTypeFormattingCheck.setSelected(s.isLspOnTypeFormatting());
            pasteImportsCheck.setSelected(s.isLspPasteImports());
            smartSemicolonCheck.setSelected(s.isLspSmartSemicolon());
            javaOrganizeImportsOnSaveCheck.setSelected(s.isJavaOrganizeImportsOnSave());
            javaFormatOnSaveCheck.setSelected(s.isJavaFormatOnSave());
            javaCleanupOnSaveCheck.setSelected(s.isJavaCleanupOnSave());
            showJavaCleanupsCount();
        } finally {
            loading = prev;
        }
    }

    /** A list cell showing {@code label.apply(item)} for each item. */
    private static ListCell<String> labelledCell(java.util.function.Function<String, String> label) {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : label.apply(item));
            }
        };
    }

    /** The app themes under their Light / Dark / Your themes headings. */
    private static List<String> appThemeChoices() {
        return ThemeGroups.grouped(
                Themes.names(),
                name -> Themes.themeFor(name).isDarkMode(),
                UserThemes.controlThemes().keySet());
    }

    /** "" (Automatic), then the editor themes under their Light / Dark / Your themes headings. */
    private static List<String> editorThemeChoices() {
        Set<String> user = new java.util.HashSet<>(UserThemes.controlThemes().keySet());
        user.addAll(UserThemes.editorThemes().keySet());
        List<String> choices = new ArrayList<>();
        choices.add("");
        choices.addAll(ThemeGroups.grouped(
                EditorThemes.names(),
                name -> EditorThemes.editorBackgroundFor(name).getBrightness() < 0.5,
                user));
        return choices;
    }

    /** "Automatic" for "", else the theme's name. */
    private static String editorThemeLabel(String name) {
        return name.isEmpty() ? tr("settings.editorTheme.auto") : name;
    }

    /** The editor-theme combo's button: "Automatic" for "", else the theme's name. */
    private ListCell<String> editorThemeCell() {
        return labelledCell(SettingsWindow::editorThemeLabel);
    }

    public void syncThemes() {
        if (!built) {
            return;
        }
        boolean prev = loading;
        loading = true;
        try {
            themeCombo.getItems().setAll(appThemeChoices()); // pick up user themes reloaded from the config folder
            editorThemeCombo.getItems().setAll(editorThemeChoices());
            themeCombo.setValue(config.getSettings().getTheme());
            editorThemeCombo.setValue(
                    config.getSettings().isEditorThemeUserSet()
                            ? EditorThemes.normalize(config.getSettings().getEditorTheme())
                            : "");
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
            syncMarkdownControls(s);
            adminSaveCheck.setSelected(s.isAdminSave());
            whitespaceCheck.setSelected(s.isShowWhitespace());
            notesCheck.setSelected(s.isNotesSupport());
            noteIndicatorsCheck.setSelected(s.isShowNoteIndicators());
            spellPage.spellCheckBox.setSelected(s.isSpellCheck());
            spellPage.spellLanguageCombo.setValue(s.getSpellLanguage());
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
            buildBeforeRunCheck.setSelected(s.isBuildBeforeRun());
            mavenCentralIndexCheck.setSelected(s.isMavenPomCentralIndex());
            javaDownloadSourcesCheck.setSelected(s.isJavaDownloadSources());
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
            htmlMarkupChecksCheck.setSelected(s.isHtmlMarkupChecks());
            todoHighlightCheck.setSelected(s.isTodoHighlight());
            if (todoSettings != null) {
                todoSettings.rebuildRows();
            }
            markdownPage.rebuildMarkdownLintRules();
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

    /** The editor-font picker choices (bundled monospace families first, then system monospace). */
    public static List<String> fontFamilyChoices() {
        return FontChoices.forThisMachine();
    }

    private void showJavaCleanupsCount() {
        javaCleanupsCount.setText(tr(
                "settings.javaCleanups.count",
                config.getSettings().getJavaCleanups().size()));
    }

    void apply() {
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
        refreshModified();
    }

    /** Marks the rows that differ from their defaults, counts them per page, and updates the restart note. */
    private void refreshModified() {
        if (modified == null) {
            return;
        }
        modifiedPerPage = modified.refresh(
                rows,
                config.getSettings(),
                keys -> {
                    SettingsModified.reset(config.getSettings(), keys);
                    commitReset(); // saves, applies, re-reads every control
                },
                scopeUi::marksRow); // Project mode marks its own rows (the project's values)
        List<String> restart = RestartPending.changed(config.getSettings());
        restartNote.setText(tr("settings.restart.pending", restart.size()));
        SettingsSearch.setShown(restartBox, !restart.isEmpty());
        sidebar.refresh();
        if (modifiedOnly.isSelected() || SettingsSearch.wantsModified(searchField.getText())) {
            filter(searchField.getText()); // a row put back to its default leaves the list
        }
    }

    private SettingsModified modified; // which rows differ from their defaults (built with the pages)
    private Map<Object, Integer> modifiedPerPage = Map.of();
    private final javafx.scene.control.ToggleButton modifiedOnly =
            new javafx.scene.control.ToggleButton(tr("settings.search.modifiedOnly"));
    private final Label restartNote = new Label();
    private final HBox restartBox = UiZoom.spacing(new HBox(), 8);

    @Nullable
    Runnable onRestart;

    /** What "Restart now" runs (the {@code app.restart} command: quit after the usual questions, start again). */
    public void setOnRestart(Runnable onRestart) {
        this.onRestart = onRestart;
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
