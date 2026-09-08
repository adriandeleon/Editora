package com.editora.ui;

import java.util.List;

import javafx.geometry.Orientation;
import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.build.BuildTool;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.editops.KillRing;
import com.editora.editops.Rectangle;
import com.editora.editor.EditorBuffer;
import com.editora.editor.TextNav;
import com.editora.markdown.MarkdownTable;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;

import static com.editora.i18n.Messages.tr;

/** Registers window commands in stable palette order and supplies their feature gates. */
final class WindowCommandRegistrar {
    interface Host {
        WindowChromeCoordinator chrome();

        GitWindowCoordinator gitWindows();

        NavigationCoordinator navigation();

        PreviewCoordinator previews();

        Stage stage();

        ConfigManager config();

        CommandRegistry registry();

        MacroCoordinator macroCoordinator();

        StatusBar statusBar();

        SettingsWindow settingsWindow();

        com.editora.snippet.SnippetManager snippets();

        PluginCoordinator pluginCoordinator();

        ProjectPanel projectPanel();

        WindowManager windowManager();

        QuickOpen<Project> projectPicker();

        ToolWindowManager toolWindows();

        ToolWindow projectToolWindow();

        ToolWindow structureToolWindow();

        ToolWindow bookmarksToolWindow();

        ToolWindow notesToolWindow();

        ToolWindow fileInfoToolWindow();

        ToolWindow undoHistoryToolWindow();

        BookmarkCoordinator bookmarkCoordinator();

        ToolWindow searchToolWindow();

        ToolWindow markdownLintToolWindow();

        RemoteCoordinator remoteCoordinator();

        ToolWindow problemsToolWindow();

        ToolWindow referencesToolWindow();

        ToolWindow hierarchyToolWindow();

        ToolWindow runToolWindow();

        ToolWindow externalToolToolWindow();

        ToolWindow buildOutputToolWindow();

        ToolWindow testResultsToolWindow();

        ToolWindow remoteToolWindow();

        com.editora.dap.DapManager dapManager();

        ToolWindow debugToolWindow();

        DebugCoordinator debugCoordinator();

        InstallCoordinator installCoordinator();

        ToolWindow commitToolWindow();

        GitLogPanel.Actions gitLogOps();

        GitHubPanel githubPanel();

        ToolWindow githubToolWindow();

        HistoryCoordinator historyCoordinator();

        ToolbarCoordinator toolbarCoordinator();

        Switcher switcher();

        void checkForUpdatesNow();

        void openUpdateDownloadPage();

        void showDoctor();

        void showWelcome();

        boolean projectsEnabled();

        void applyProjectSupport();

        void closeProject();

        void deleteProject();

        void deleteProject(Project p);

        void startAceJump();

        void startAceJumpLine();

        void applyMathSupport();

        EditingCoordinator editing();

        TemplateCoordinator templateActions();

        EditorSettingsCoordinator editorSettings();

        RunConfigurationCoordinator runConfigurations();

        GitCoordinator git();

        DiffCoordinator diffCoordinator();

        GitHubCoordinator github();

        MermaidCoordinator mermaid();

        DiagramCoordinator diagram();

        TypstCoordinator typst();

        ExportCoordinator exports();

        HtmlPreviewCoordinator htmlPreview();

        LogViewerCoordinator logViewer();

        MavenProjectCoordinator mavenProjectCoordinator();

        ExternalToolCoordinator externalToolCoordinator();

        List<BuildCoordinator> buildCoordinators();

        void refreshBuildTools();

        IndexCoordinator indexCoordinator();

        TodoCoordinator todoCoordinator();

        CsvCoordinator csvCoordinator();

        SearchCoordinator searchCoordinator();

        RunCoordinator runCoordinator();

        TestRunCoordinator testRunCoordinator();

        LspCoordinator lspCoordinator();

        NotesCoordinator notesCoordinator();

        HttpClientCoordinator httpClient();

        AgentCoordinator agentCoordinator();

        void applyAgentSupport();

        AiCoordinator aiCoordinator();

        void showTrustedFolders();

        void revokeTrustForActiveRoot();

        void ifMcp(Runnable action);

        void toggleMcpSupport();

        void copyMcpEndpoint();

        void ifLsp(Runnable action);

        void toggleLsp();

        void runTestsForContext();

        void runTestAtCaret(boolean classLevel);

        void applyTestRunner();

        void findNextMatch();

        void findPreviousMatch();

        void findReplaceCurrentMatch();

        void findReplaceAllMatches();

        void openDocumentation();

        void onSplitVertical();

        void onSplitHorizontal();

        void unsplit();

        void splitEditorGroup(Orientation orientation);

        void moveTabToNextGroup();

        void focusNextEditorGroup();

        void unsplitEditorGroups();

        void setStatus(String message);

        EditorBuffer activeBuffer();

        void onNew();

        void onOpen();

        void openActiveAsText();

        void openActiveAsHex();

        void onClearRecent();

        void onSave();

        void onSaveAsAdmin();

        void applyAdminSaveSupport();

        void saveAsPrompt(EditorBuffer buffer);

        void applyAutoSave();

        void toggleAutoSave();

        void onCloseTab();

        Tab activeTab();

        void closeOtherTabs(Tab keep);

        void closeAllTabs();

        void closeUnmodifiedTabs();

        void closeTabsToLeft(Tab pivot);

        void closeTabsToRight(Tab pivot);

        void copyPath(EditorBuffer buffer);

        void revealActiveBuffer();

        void openTerminalForActiveBuffer();

        void togglePin(Tab tab);

        void renameFile(EditorBuffer buffer, Tab tab);

        void onQuit();

        void requestSave();

        void nextBuffer();

        void findShowOrNext();

        void findShowOrPrevious();

        void showReplace();

        void updateBufferToolWindows();

        void maybeOfferInstall(EditorBuffer buffer);

        void onPalette();

        void onSettings();

        void onAbout();

        void showSplitToolWindowPalette();

        void toggleFloatingToolWindow();

        void toggleMaximizedToolWindow();

        void toggleToolStripe();

        void withMultiCaret(java.util.function.Consumer<EditorBuffer> action);

        void selectAllOccurrences();

        void selectAllFindMatches();

        void chooseInstallServer();

        void toggleReadOnly();

        void textZoom(int direction);

        void insertSnippetPicker();

        void editUserSnippets();

        void editProjectSettings();

        void showDebugLog();

        void exportConfig();

        void cancel();

        SelectionPolicy selPolicy();
    }

    private final Host host;

    WindowCommandRegistrar(Host host) {
        this.host = host;
    }

    void registerCommands() {
        host.registry().register(Command.of("file.new", host::onNew));
        host.registry().register(Command.of("window.new", () -> {
            if (host.windowManager() != null) {
                host.windowManager().newWindow();
            }
        }));
        host.registry().register(Command.of("file.open", host::onOpen));
        host.registry()
                .register(Command.of(
                        "file.find", () -> host.navigation().fileFinder.show(host.stage())));
        // --- Keyboard macros ---
        host.macroCoordinator().registerCommands(); // macro.* + one macro.run.<slug> per persisted macro
        // --- External Tools ---
        host.mavenProjectCoordinator().registerCommands(host.registry());
        host.registry()
                .register(Command.of(
                        "maven.setArchetypeCatalogUrl",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "maven.setArchetypeCatalogUrl",
                                        () -> host.config().getSettings().getMavenArchetypeCatalogUrl(),
                                        v -> host.config().getSettings().setMavenArchetypeCatalogUrl(v),
                                        () -> {})));
        host.externalToolCoordinator()
                .registerCommands(host.registry()); // externalTool.run/clearOutput/rerunLast + per-tool run.<slug>
        // Project commands no-op when project support is disabled (fully gated).
        host.registry().register(Command.of("project.open", () -> {
            if (host.projectsEnabled()) {
                host.navigation().folderFinder.show(host.stage());
            }
        }));
        host.registry().register(Command.of("project.switch", () -> {
            if (host.projectsEnabled()) {
                host.projectPicker().show(host.stage());
            }
        }));
        host.registry().register(Command.of("project.close", () -> {
            if (host.projectsEnabled()) {
                host.closeProject();
            }
        }));
        host.registry().register(Command.of("project.delete", () -> {
            if (host.projectsEnabled()) {
                host.deleteProject();
            }
        }));
        host.registry().register(Command.of("file.save", host::onSave));
        // Palette / keybinding Save As is keyboard-first: prompt for the path in-scene (the toolbar button's
        // FXML onAction still opens the native file chooser).
        host.registry().register(Command.of("file.saveAs", () -> host.saveAsPrompt(host.activeBuffer())));
        host.registry().register(Command.of("file.saveAsAdmin", host::onSaveAsAdmin));
        host.registry().register(Command.of("buffer.close", host::onCloseTab));
        host.registry().register(Command.of("buffer.closeOthers", () -> host.closeOtherTabs(host.activeTab())));
        host.registry().register(Command.of("buffer.closeAll", host::closeAllTabs));
        host.registry().register(Command.of("buffer.closeUnmodified", host::closeUnmodifiedTabs));
        host.registry().register(Command.of("buffer.closeLeft", () -> host.closeTabsToLeft(host.activeTab())));
        host.registry().register(Command.of("buffer.closeRight", () -> host.closeTabsToRight(host.activeTab())));
        host.registry().register(Command.of("buffer.copyPath", () -> host.copyPath(host.activeBuffer())));
        host.registry().register(Command.of("buffer.togglePin", () -> host.togglePin(host.activeTab())));
        host.registry()
                .register(Command.of("buffer.rename", () -> host.renameFile(host.activeBuffer(), host.activeTab())));
        host.registry().register(Command.of("file.revealInFileManager", host::revealActiveBuffer));
        host.registry().register(Command.of("file.openTerminal", host::openTerminalForActiveBuffer));
        host.registry().register(Command.of("buffer.next", host::nextBuffer));
        host.registry().register(Command.of("app.quit", host::onQuit));
        host.registry().register(Command.of("palette.show", host::onPalette));
        host.registry().register(Command.of("view.settings", host::onSettings));
        host.registry().register(Command.of("keymap.select", host.editorSettings()::chooseKeymap));
        host.registry().register(Command.of("theme.setAppTheme", host.editorSettings()::chooseAppTheme));
        host.registry().register(Command.of("theme.setEditorTheme", host.editorSettings()::chooseEditorTheme));
        host.registry().register(Command.of("theme.reloadUserThemes", host.editorSettings()::reloadUserThemes));
        // Settings palette commands — a command-palette equivalent for every Settings-window control.
        host.registry().register(Command.of("appearance.setFont", host.editorSettings()::chooseFont));
        host.registry()
                .register(Command.of(
                        "appearance.setFontSize",
                        () -> host.editorSettings()
                                .promptIntSetting(
                                        "appearance.setFontSize",
                                        () -> host.config().getSettings().getFontSize(),
                                        6,
                                        72,
                                        v -> host.config().getSettings().setFontSize(v),
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry().register(Command.of("appearance.setUiLanguage", host.editorSettings()::chooseUiLanguage));
        host.registry().register(Command.of("editor.setPdfPageSize", host.editorSettings()::choosePdfPageSize));
        host.registry()
                .register(Command.of(
                        "view.togglePdfLineNumbers",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.togglePdfLineNumbers",
                                        () -> host.config().getSettings().isPdfLineNumbers(),
                                        v -> host.config().getSettings().setPdfLineNumbers(v),
                                        null)));
        host.registry()
                .register(Command.of(
                        "view.togglePdfSyntaxHighlighting",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.togglePdfSyntaxHighlighting",
                                        () -> host.config().getSettings().isPdfSyntaxHighlighting(),
                                        v -> host.config().getSettings().setPdfSyntaxHighlighting(v),
                                        null)));
        host.registry()
                .register(Command.of(
                        "view.toggleEditorConfig",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleEditorConfig",
                                        () -> host.config().getSettings().isEditorConfigSupport(),
                                        v -> host.config().getSettings().setEditorConfigSupport(v),
                                        host.editorSettings()::applyEditorConfigSupport)));
        host.registry().register(Command.of("editorConfig.openActive", host.editorSettings()::openActiveEditorConfig));
        host.registry()
                .register(Command.of(
                        "view.toggleProjectHidden",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleProjectHidden",
                                        () -> host.config().getSettings().isProjectShowHidden(),
                                        v -> host.config().getSettings().setProjectShowHidden(v),
                                        () -> host.projectPanel()
                                                .setShowHidden(host.config()
                                                        .getSettings()
                                                        .isProjectShowHidden()))));
        host.registry()
                .register(Command.of(
                        "view.toggleNoteIndicators",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleNoteIndicators",
                                        () -> host.config().getSettings().isShowNoteIndicators(),
                                        v -> host.config().getSettings().setShowNoteIndicators(v),
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry()
                .register(Command.of(
                        "view.toggleCompletionDoc",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleCompletionDoc",
                                        () -> host.config().getSettings().isCompletionDoc(),
                                        v -> host.config().getSettings().setCompletionDoc(v),
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry()
                .register(Command.of(
                        "view.toggleProjects",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleProjects",
                                        () -> host.config().getSettings().isProjectSupport(),
                                        v -> host.config().getSettings().setProjectSupport(v),
                                        host::applyProjectSupport)));
        host.registry()
                .register(Command.of(
                        "view.toggleNotes",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleNotes",
                                        () -> host.config().getSettings().isNotesSupport(),
                                        v -> host.config().getSettings().setNotesSupport(v),
                                        host.notesCoordinator()::applySupport)));
        host.registry()
                .register(Command.of(
                        "view.toggleLocalHistory",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleLocalHistory",
                                        () -> host.config().getSettings().isLocalHistory(),
                                        v -> host.config().getSettings().setLocalHistory(v),
                                        host.historyCoordinator()::applySupport)));
        host.registry()
                .register(Command.of(
                        "view.toggleGit",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleGit",
                                        () -> host.config().getSettings().isGitSupport(),
                                        v -> host.config().getSettings().setGitSupport(v),
                                        host.git()::applySupport)));
        host.registry()
                .register(Command.of(
                        "git.setCommand",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "git.setCommand",
                                        () -> host.config().getSettings().getGitPath(),
                                        v -> host.config().getSettings().setGitPath(v),
                                        () -> {
                                            host.git().applySupport(); // pushes the command + re-probes availability
                                            host.git().refresh();
                                        })));
        host.registry()
                .register(Command.of(
                        "view.toggleMermaid",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleMermaid",
                                        () -> host.config().getSettings().isMermaidSupport(),
                                        v -> host.config().getSettings().setMermaidSupport(v),
                                        host.mermaid()::applySupport)));
        host.registry()
                .register(Command.of(
                        "view.toggleHttpClient",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleHttpClient",
                                        () -> host.config().getSettings().isHttpClientSupport(),
                                        v -> host.config().getSettings().setHttpClientSupport(v),
                                        host.httpClient()::applySupport)));
        host.registry()
                .register(Command.of(
                        "view.toggleDebug",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleDebug",
                                        () -> host.config().getSettings().isDebugSupport(),
                                        v -> host.config().getSettings().setDebugSupport(v),
                                        host.debugCoordinator()::applySupport)));
        host.registry()
                .register(Command.of(
                        "file.setAutoSaveDelay",
                        () -> host.editorSettings()
                                .promptIntSetting(
                                        "file.setAutoSaveDelay",
                                        () -> Math.max(1, (int) Math.round(
                                                host.config().getSettings().getAutoSaveDelayMillis() / 1000.0)),
                                        1,
                                        3600,
                                        v -> host.config().getSettings().setAutoSaveDelayMillis(v * 1000),
                                        host::applyAutoSave)));
        host.registry()
                .register(Command.of(
                        "app.setAuthorName",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "app.setAuthorName",
                                        () -> host.config().getSettings().getAuthorNameRaw(),
                                        v -> host.config().getSettings().setAuthorName(v),
                                        null)));
        host.registry()
                .register(Command.of(
                        "history.setMaxPerFile",
                        () -> host.editorSettings()
                                .promptIntSetting(
                                        "history.setMaxPerFile",
                                        () -> host.config().getSettings().getHistoryMaxPerFile(),
                                        1,
                                        1000,
                                        v -> host.config().getSettings().setHistoryMaxPerFile(v),
                                        host.historyCoordinator()::applySupport)));
        host.registry()
                .register(Command.of(
                        "history.setMaxAgeDays",
                        () -> host.editorSettings()
                                .promptIntSetting(
                                        "history.setMaxAgeDays",
                                        () -> host.config().getSettings().getHistoryMaxAgeDays(),
                                        1,
                                        3650,
                                        v -> host.config().getSettings().setHistoryMaxAgeDays(v),
                                        host.historyCoordinator()::applySupport)));
        host.registry()
                .register(Command.of(
                        "history.setMaxTotalMb",
                        () -> host.editorSettings()
                                .promptIntSetting(
                                        "history.setMaxTotalMb",
                                        () -> host.config().getSettings().getHistoryMaxTotalMb(),
                                        1,
                                        10000,
                                        v -> host.config().getSettings().setHistoryMaxTotalMb(v),
                                        host.historyCoordinator()::applySupport)));
        host.registry()
                .register(Command.of(
                        "editor.setLargeFileThreshold",
                        () -> host.editorSettings()
                                .promptIntSetting(
                                        "editor.setLargeFileThreshold",
                                        () -> host.config().getSettings().getLargeFileThreshold(),
                                        0,
                                        10_000_000,
                                        v -> host.config().getSettings().setLargeFileThreshold(v),
                                        null))); // applies to newly opened files
        host.registry().register(Command.of("view.toggleLargeFileMode", host.editorSettings()::toggleLargeFileMode));
        host.registry()
                .register(Command.of(
                        "view.toggleRipgrep",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleRipgrep",
                                        () -> host.config().getSettings().isRipgrepSearch(),
                                        v -> host.config().getSettings().setRipgrepSearch(v),
                                        host.searchCoordinator()::applyRipgrepSupport)));
        host.registry()
                .register(Command.of(
                        "search.setRipgrepCommand",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "search.setRipgrepCommand",
                                        () -> host.config().getSettings().getRipgrepCommand(),
                                        v -> host.config().getSettings().setRipgrepCommand(v),
                                        host.searchCoordinator()::applyRipgrepSupport)));
        host.registry()
                .register(Command.of(
                        "view.toggleSearchGitignore",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleSearchGitignore",
                                        () -> host.config().getSettings().isSearchRespectGitignore(),
                                        v -> host.config().getSettings().setSearchRespectGitignore(v),
                                        host.searchCoordinator()::applyRipgrepSupport)));
        host.registry()
                .register(Command.of(
                        "mermaid.setMmdcCommand",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "mermaid.setMmdcCommand",
                                        () -> host.config().getSettings().getMmdcPath(),
                                        v -> host.config().getSettings().setMmdcPath(v),
                                        host.mermaid()::applySupport)));
        host.registry()
                .register(Command.of(
                        "mermaid.setMaidCommand",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "mermaid.setMaidCommand",
                                        () -> host.config().getSettings().getMaidPath(),
                                        v -> host.config().getSettings().setMaidPath(v),
                                        host.mermaid()::applySupport)));
        host.registry()
                .register(Command.of(
                        "plugins.toggleRequireSignature",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "plugins.toggleRequireSignature",
                                        () -> host.config().getSettings().isPluginRequireSignature(),
                                        v -> host.config().getSettings().setPluginRequireSignature(v),
                                        null)));
        host.registry()
                .register(Command.of(
                        "plugins.setRegistryUrl",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "plugins.setRegistryUrl",
                                        () -> host.config().getSettings().getPluginRegistryUrl(),
                                        v -> host.config().getSettings().setPluginRegistryUrl(v),
                                        null)));
        host.registry().register(Command.of("lsp.toggleServer", host.lspCoordinator()::chooseServerToggle));
        host.registry().register(Command.of("lsp.setServerCommand", host.lspCoordinator()::chooseServerCommand));
        host.registry().register(Command.of("debug.toggleAdapter", host.debugCoordinator()::chooseAdapterToggle));
        host.registry().register(Command.of("debug.setAdapterPath", host.debugCoordinator()::chooseAdapterPath));
        host.registry()
                .register(Command.of(
                        "install.javaSupport",
                        () -> host.installCoordinator().installSupport(com.editora.install.InstallCatalog.Lang.JAVA)));
        host.registry()
                .register(Command.of(
                        "install.pythonSupport",
                        () -> host.installCoordinator()
                                .installSupport(com.editora.install.InstallCatalog.Lang.PYTHON)));
        host.registry()
                .register(Command.of(
                        "install.jsSupport",
                        () -> host.installCoordinator()
                                .installSupport(com.editora.install.InstallCatalog.Lang.JAVASCRIPT)));
        host.registry()
                .register(Command.of(
                        "install.mermaidSupport",
                        () -> host.installCoordinator()
                                .installSupport(com.editora.install.InstallCatalog.Lang.MERMAID)));
        host.registry()
                .register(Command.of(
                        "install.typstCli", () -> host.installCoordinator().installTypstCli()));
        host.registry().register(Command.of("install.languageServer", host::chooseInstallServer));
        host.registry().register(Command.of("view.toggleColumnRuler", host.editorSettings()::toggleColumnRuler));
        host.registry().register(Command.of("view.toggleToolStripe", host::toggleToolStripe));
        host.registry().register(Command.of("view.maximizeToolWindow", host::toggleMaximizedToolWindow));
        host.registry().register(Command.of("view.splitToolWindow", host::showSplitToolWindowPalette));
        host.registry().register(Command.of("view.floatToolWindow", host::toggleFloatingToolWindow));
        host.registry().register(Command.of("view.toggleSimpleMode", host.chrome()::toggleSimpleMode));
        host.registry()
                .register(Command.of(
                        "view.customizeToolbar", () -> host.settingsWindow().showToolbar(host.stage())));
        host.registry()
                .register(Command.of(
                        "toolbar.restoreDefault",
                        () -> host.toolbarCoordinator().restoreDefault()));
        host.registry()
                .register(Command.of(
                        "view.toggleInstallPrompts",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleInstallPrompts",
                                        () -> host.config().getSettings().isLspInstallPrompts(),
                                        v -> host.config().getSettings().setLspInstallPrompts(v),
                                        () -> host.maybeOfferInstall(host.activeBuffer()))));
        host.registry().register(Command.of("view.togglePlugins", host.pluginCoordinator()::toggleSupport));
        host.registry().register(Command.of("plugins.browse", host.pluginCoordinator()::browse));
        host.registry().register(Command.of("plugins.installFromDisk", host.pluginCoordinator()::installFromDisk));
        host.registry().register(Command.of("workspace.manageTrust", host::showTrustedFolders));
        host.registry().register(Command.of("workspace.revokeTrust", host::revokeTrustForActiveRoot));
        host.registry().register(Command.of("config.export", host::exportConfig));
        host.registry().register(Command.of("editor.setIndentStyle", host.editorSettings()::chooseIndentStyle));
        host.exports().registerCommands(host.registry());
        host.registry().register(Command.of("markwhen.toggleView", host.previews()::toggleMarkwhenView));
        host.registry().register(Command.of("structured.toggleView", host.previews()::toggleStructuredView));
        // Two file-type-agnostic view-mode toggles replace the former per-type view.toggle*Preview palette
        // commands (Structured/SVG/Crontab/Fstab/Systemd/SshConfig/Dockerfile/GitHubActions): one flips
        // Editor ⇄ full Preview, the other Editor ⇄ Split (editor+preview). Those preview types can still be
        // enabled/disabled per-type via Settings → Editor.
        host.registry()
                .register(Command.of(
                        "view.togglePreview",
                        () -> host.previews().togglePreviewMode(EditorBuffer.MarkdownViewMode.PREVIEW)));
        host.registry()
                .register(Command.of(
                        "view.toggleSplitPreview",
                        () -> host.previews().togglePreviewMode(EditorBuffer.MarkdownViewMode.SPLIT)));
        host.registry().register(Command.of("mermaid.export", host.mermaid()::export));
        host.registry().register(Command.of("diagram.export", host.diagram()::export));
        host.registry()
                .register(Command.of(
                        "view.toggleDiagramSupport",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleDiagramSupport",
                                        () -> host.config().getSettings().isDiagramSupport(),
                                        v -> host.config().getSettings().setDiagramSupport(v),
                                        host.diagram()::applySupport)));
        host.registry()
                .register(Command.of(
                        "diagram.setDotCommand",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "diagram.setDotCommand",
                                        () -> host.config().getSettings().getDotPath(),
                                        v -> host.config().getSettings().setDotPath(v),
                                        host.diagram()::applySupport)));
        host.registry()
                .register(Command.of(
                        "diagram.setPlantumlCommand",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "diagram.setPlantumlCommand",
                                        () -> host.config().getSettings().getPlantumlPath(),
                                        v -> host.config().getSettings().setPlantumlPath(v),
                                        host.diagram()::applySupport)));
        host.registry().register(Command.of("typst.export", host.typst()::export));
        host.registry()
                .register(Command.of(
                        "view.toggleTypstSupport",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleTypstSupport",
                                        () -> host.config().getSettings().isTypstSupport(),
                                        v -> host.config().getSettings().setTypstSupport(v),
                                        () -> {
                                            host.typst().applySupport();
                                            host.editorSettings()
                                                    .applyViewSettingsToAllBuffers(
                                                            host.config().getSettings());
                                        })));
        host.registry()
                .register(Command.of(
                        "typst.setCommand",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "typst.setCommand",
                                        () -> host.config().getSettings().getTypstPath(),
                                        v -> host.config().getSettings().setTypstPath(v),
                                        host.typst()::applySupport)));
        host.registry().register(Command.of("htmlPreview.open", host.htmlPreview()::open));
        host.registry().register(Command.of("htmlPreview.openIn", host.htmlPreview()::openIn));
        host.registry().register(Command.of("view.toggleHtmlPreview", host.htmlPreview()::toggle));
        host.registry().register(Command.of("log.toggleFollow", host.logViewer()::toggleFollowCommand));
        host.registry().register(Command.of("log.viewAsLog", host.logViewer()::viewAsLog));
        host.registry().register(Command.of("log.setLevelFilter", host.logViewer()::setLevelFilter));
        host.registry().register(Command.of("log.setRegexFilter", host.logViewer()::setRegexFilter));
        host.registry().register(Command.of("log.clearFilter", host.logViewer()::clearFilter));
        host.registry().register(Command.of("log.nextError", host.logViewer()::jumpToNextError));
        host.registry().register(Command.of("log.previousError", host.logViewer()::jumpToPreviousError));
        host.registry().register(Command.of("view.toggleLogViewer", host.logViewer()::toggleViewer));
        host.registry()
                .register(Command.of(
                        "view.toggleCsvGrid",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleCsvGrid",
                                        () -> host.config().getSettings().isCsvPreview(),
                                        host.config().getSettings()::setCsvPreview,
                                        () -> {
                                            host.csvCoordinator()
                                                    .applySupport(); // (de)attach the in-editor grid on every CSV
                                            // buffer
                                            host.updateBufferToolWindows();
                                        })));
        // The structured-data preview had a Settings checkbox but no palette command, against the
        // every-setting-is-a-command convention — and once structured.* is gated on it, the palette would
        // otherwise have no way to switch it back on.
        host.registry()
                .register(Command.of(
                        "view.toggleStructuredPreview",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleStructuredPreview",
                                        () -> host.config().getSettings().isStructuredPreview(),
                                        host.config().getSettings()::setStructuredPreview,
                                        () -> {
                                            host.editorSettings()
                                                    .applyViewSettingsToAllBuffers(
                                                            host.config().getSettings());
                                            host.settingsWindow().syncAll();
                                        })));
        host.registry()
                .register(Command.of(
                        "view.toggleStickyScroll",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleStickyScroll",
                                        () -> host.config().getSettings().isStickyScroll(),
                                        host.config().getSettings()::setStickyScroll,
                                        () -> {
                                            host.editorSettings()
                                                    .applyViewSettingsToAllBuffers(
                                                            host.config().getSettings());
                                            host.settingsWindow().syncAll();
                                        })));
        host.registry()
                .register(Command.of(
                        "view.toggleExtendedWindow",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleExtendedWindow",
                                        () -> host.config().getSettings().isExtendedWindow(),
                                        host.config().getSettings()::setExtendedWindow,
                                        () -> {
                                            host.settingsWindow().syncAll();
                                            // Nothing to re-apply live: a stage's style is fixed once it has been
                                            // shown.
                                            host.setStatus(tr("status.extendedWindow.restart"));
                                        })));
        host.registry().register(Command.of("pom.toggleView", host.previews()::togglePomView));
        host.registry()
                .register(Command.of(
                        "view.togglePomPreview",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.togglePomPreview",
                                        () -> host.config().getSettings().isPomPreview(),
                                        host.config().getSettings()::setPomPreview,
                                        () -> {
                                            host.editorSettings()
                                                    .applyViewSettingsToAllBuffers(
                                                            host.config().getSettings());
                                            host.settingsWindow().syncAll();
                                        })));
        host.registry()
                .register(Command.of(
                        "view.toggleBracketColors",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleBracketColors",
                                        () -> host.config().getSettings().isBracketColors(),
                                        host.config().getSettings()::setBracketColors,
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry()
                .register(Command.of(
                        "view.toggleCsvRainbow",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleCsvRainbow",
                                        () -> host.config().getSettings().isCsvRainbow(),
                                        host.config().getSettings()::setCsvRainbow,
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry()
                .register(Command.of(
                        "view.toggleAutoRenameTag",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAutoRenameTag",
                                        () -> host.config().getSettings().isAutoRenameTag(),
                                        host.config().getSettings()::setAutoRenameTag,
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry()
                .register(Command.of(
                        "view.toggleAutoFill",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAutoFill",
                                        () -> host.config().getSettings().isAutoFill(),
                                        host.config().getSettings()::setAutoFill,
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry()
                .register(Command.of(
                        "view.toggleAbbrevMode",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAbbrevMode",
                                        () -> host.config().getSettings().isAbbrevMode(),
                                        host.config().getSettings()::setAbbrevMode,
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry().register(Command.of("edit.expandAbbrev", host.editing()::expandAbbrev));
        host.registry().register(Command.of("edit.defineAbbrev", host.editing()::defineAbbrev));
        host.registry()
                .register(
                        Command.of("abbrev.manage", () -> host.settingsWindow().showAbbreviations(host.stage())));
        host.registry()
                .register(Command.of(
                        "view.toggleAutoCloseTags",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAutoCloseTags",
                                        () -> host.config().getSettings().isAutoCloseTags(),
                                        host.config().getSettings()::setAutoCloseTags,
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry().register(Command.of("mcp.copyEndpoint", () -> host.ifMcp(host::copyMcpEndpoint)));
        host.registry().register(Command.of("view.toggleMcp", host::toggleMcpSupport));
        host.registry()
                .register(Command.of(
                        "view.toggleAiEnabled",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAiEnabled",
                                        () -> host.config().getSettings().isAiEnabled(),
                                        v -> host.config().getSettings().setAiEnabled(v),
                                        () -> {
                                            host.applyAgentSupport();
                                            host.aiCoordinator().applySupport();
                                        })));
        host.registry().register(Command.of("tool.agent", host.agentCoordinator()::toggleToolWindow));
        host.registry().register(Command.of("agent.newSession", host.agentCoordinator()::newSession));
        host.registry().register(Command.of("agent.stop", host.agentCoordinator()::stopTurn));
        host.registry().register(Command.of("agent.selectModel", host.agentCoordinator()::pickModel));
        host.registry().register(Command.of("agent.selectMode", host.agentCoordinator()::pickMode));
        host.registry().register(Command.of("agent.selectClient", host.agentCoordinator()::pickAgentClient));
        host.registry().register(Command.of("agent.resumeSession", host.agentCoordinator()::resumeSessionPicker));
        host.registry()
                .register(Command.of(
                        "view.toggleAgent",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAgent",
                                        () -> host.config().getSettings().isAgentSupport(),
                                        v -> host.config().getSettings().setAgentSupport(v),
                                        host::applyAgentSupport)));
        host.registry()
                .register(Command.of(
                        "agent.setCommand",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "agent.setCommand",
                                        () -> host.config().getSettings().getAgentCommand(),
                                        v -> host.config().getSettings().setAgentCommand(v),
                                        host::applyAgentSupport)));
        host.registry()
                .register(Command.of(
                        "view.toggleAgentContext",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAgentContext",
                                        () -> host.config().getSettings().isAgentIncludeContext(),
                                        v -> host.config().getSettings().setAgentIncludeContext(v),
                                        null))); // read fresh on the next sendPrompt call — nothing cached to re-push
        host.registry().register(Command.of("ai.generateCommitMessage", host.aiCoordinator()::generateCommitMessage));
        host.registry().register(Command.of("ai.explainSelection", host.aiCoordinator()::explainSelection));
        host.registry().register(Command.of("ai.rewriteSelection", host.aiCoordinator()::rewriteSelection));
        host.registry().register(Command.of("ai.cancel", host.aiCoordinator()::cancel));
        host.registry()
                .register(Command.of(
                        "view.toggleAi",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAi",
                                        () -> host.config().getSettings().isAiSupport(),
                                        v -> host.config().getSettings().setAiSupport(v),
                                        null)));
        host.registry()
                .register(Command.of(
                        "ai.setModel",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "ai.setModel",
                                        () -> host.config().getSettings().getAiModel(),
                                        v -> host.config().getSettings().setAiModel(v),
                                        null)));
        host.registry()
                .register(Command.of(
                        "view.toggleAiCompletion",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAiCompletion",
                                        () -> host.config().getSettings().isAiInlineCompletion(),
                                        v -> host.config().getSettings().setAiInlineCompletion(v),
                                        host.editorSettings()::applyAutocomplete)));
        host.registry()
                .register(Command.of(
                        "ai.setCompletionModel",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "ai.setCompletionModel",
                                        () -> host.config().getSettings().getAiCompletionModel(),
                                        v -> host.config().getSettings().setAiCompletionModel(v),
                                        null)));
        host.registry()
                .register(Command.of(
                        "ai.setProvider",
                        () -> host.editorSettings()
                                .chooseSetting(
                                        "ai.setProvider",
                                        () -> List.of("anthropic", "openai"),
                                        id -> tr("settings.ai.provider." + id),
                                        id -> {
                                            host.config().getSettings().setAiProvider(id);
                                            host.requestSave();
                                            host.editorSettings()
                                                    .applyAutocomplete(); // re-gate inline completion (key requirement
                                            // changed)
                                            if (host.settingsWindow() != null) {
                                                host.settingsWindow().syncAll();
                                            }
                                            host.setStatus(tr(
                                                    "status.settingChanged",
                                                    host.editorSettings().commandTitle("ai.setProvider"),
                                                    tr("settings.ai.provider." + id)));
                                        })));
        host.registry()
                .register(Command.of(
                        "ai.setEndpoint",
                        () -> host.editorSettings()
                                .promptStringSetting(
                                        "ai.setEndpoint",
                                        () -> host.config().getSettings().getAiEndpoint(),
                                        v -> host.config().getSettings().setAiEndpoint(v),
                                        null)));
        host.registry().register(Command.of("ai.testConnection", host.aiCoordinator()::testConnection));
        host.registry().register(Command.of("view.toggleLineHighlight", host.editorSettings()::toggleLineHighlight));
        host.registry().register(Command.of("view.toggleLineNumbers", host.editorSettings()::toggleLineNumbers));
        host.registry().register(Command.of("view.toggleMinimap", host.editorSettings()::toggleMinimap));
        host.registry().register(Command.of("view.toggleWordWrap", host.editorSettings()::toggleWordWrap));
        host.registry()
                .register(Command.of(
                        "view.toggleAdminSave",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleAdminSave",
                                        () -> host.config().getSettings().isAdminSave(),
                                        v -> host.config().getSettings().setAdminSave(v),
                                        host::applyAdminSaveSupport)));
        host.registry().register(Command.of("view.toggleWhitespace", host.editorSettings()::toggleWhitespace));
        host.registry().register(Command.of("view.toggleSpellCheck", host.editorSettings()::toggleSpellCheck));
        host.registry().register(Command.of("view.toggleAutocomplete", host.editorSettings()::toggleAutocomplete));
        host.registry()
                .register(Command.of("view.toggleAutocompleteProse", host.editorSettings()::toggleAutocompleteProse));
        host.registry()
                .register(Command.of(
                        "view.toggleAutocompleteSnippets", host.editorSettings()::toggleAutocompleteSnippets));
        host.registry()
                .register(
                        Command.of("view.toggleAutocompleteMermaid", host.editorSettings()::toggleAutocompleteMermaid));
        host.registry().register(Command.of("view.toggleMultiCaret", host.editorSettings()::toggleMultiCaret));
        host.registry()
                .register(Command.of(
                        "view.toggleCopyLineWhenNoSelection",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleCopyLineWhenNoSelection",
                                        () -> host.config().getSettings().isCopyLineWhenNoSelection(),
                                        host.config().getSettings()::setCopyLineWhenNoSelection,
                                        null)));
        host.registry().register(Command.of("edit.copyWithHighlighting", host.editing()::copyWithHighlighting));
        host.registry()
                .register(Command.of(
                        "view.toggleCopyWithHighlighting",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleCopyWithHighlighting",
                                        () -> host.config().getSettings().isCopyWithSyntaxHighlighting(),
                                        host.config().getSettings()::setCopyWithSyntaxHighlighting,
                                        null)));
        host.registry()
                .register(Command.of(
                        "edit.addCaretNextOccurrence",
                        () -> host.withMultiCaret(EditorBuffer::addCaretNextOccurrence)));
        host.registry()
                .register(Command.of("edit.addCaretAbove", () -> host.withMultiCaret(EditorBuffer::addCaretAbove)));
        host.registry()
                .register(Command.of("edit.addCaretBelow", () -> host.withMultiCaret(EditorBuffer::addCaretBelow)));
        host.registry()
                .register(Command.of("edit.collapseCarets", () -> host.withMultiCaret(EditorBuffer::collapseCarets)));
        host.registry().register(Command.of("file.newFileOfType", host.templateActions()::newFileOfTypePicker));
        host.registry()
                .register(
                        Command.of("template.new", () -> host.templateActions().newFromTemplate(null)));
        host.registry()
                .register(Command.of(
                        "template.newInFolder",
                        () -> host.templateActions()
                                .newFromTemplate(host.templateActions().defaultNewDir())));
        host.registry().register(Command.of("project.newFromTemplate", host.templateActions()::newProjectFromTemplate));
        host.registry().register(Command.of("project.editSettings", host::editProjectSettings));
        host.registry().register(Command.of("template.reload", () -> {
            host.templateActions().templates.reload();
            host.setStatus(tr("status.templatesReloaded"));
        }));
        host.registry().register(Command.of("template.editUser", host.templateActions()::editUserTemplates));
        host.registry()
                .register(Command.of(
                        "template.manage", () -> host.settingsWindow().showTemplates(host.stage())));
        host.registry().register(Command.of("spell.setLanguage", host.editorSettings()::chooseSpellLanguage));
        host.registry()
                .register(Command.of(
                        "spell.manageDictionary", () -> host.settingsWindow().showSpellCheck(host.stage())));
        host.registry()
                .register(Command.of("view.togglePersonalDictionary", host.editorSettings()::togglePersonalDictionary));
        host.registry()
                .register(
                        Command.of("view.toggleTechnicalDictionary", host.editorSettings()::toggleTechnicalDictionary));
        host.registry().register(Command.of("view.toggleToolbar", host.chrome()::toggleToolbar));
        host.registry().register(Command.of("view.toggleStatusBar", host.chrome()::toggleStatusBar));
        host.registry().register(Command.of("view.toggleTabBar", host.chrome()::toggleTabBar));
        host.registry().register(Command.of("view.toggleBreadcrumb", host.chrome()::toggleBreadcrumb));
        host.registry().register(Command.of("view.toggleZen", host.chrome()::toggleZen));
        host.registry().register(Command.of("view.toggleExpert", host.chrome()::toggleExpert));
        host.registry().register(Command.of("view.toggleReadOnly", host::toggleReadOnly));
        host.registry().register(Command.of("file.toggleAutoSave", host::toggleAutoSave));
        host.registry()
                .register(Command.of(
                        "recent.jump", () -> host.navigation().recentPalette.show(host.stage())));
        host.registry()
                .register(Command.of(
                        "structure.jump",
                        () -> host.navigation().structurePalette.show(host.stage())));
        host.registry()
                .register(Command.of(
                        "buffer.jump", () -> host.navigation().openFilesPalette.show(host.stage())));
        host.registry()
                .register(Command.of(
                        "tool.jump", () -> host.navigation().toolWindowPalette.show(host.stage())));
        host.registry()
                .register(Command.of(
                        "undoHistory.jump",
                        () -> host.navigation().undoHistoryPalette.show(host.stage())));
        host.registry().register(Command.of("bookmarks.toggle", host.bookmarkCoordinator()::toggleAtCaret));
        host.registry().register(Command.of("bookmarks.editNote", host.bookmarkCoordinator()::editNoteAtCaret));
        host.registry()
                .register(Command.of(
                        "bookmarks.next", () -> host.bookmarkCoordinator().jump(true)));
        host.registry()
                .register(Command.of(
                        "bookmarks.previous", () -> host.bookmarkCoordinator().jump(false)));
        host.registry().register(Command.of("bookmarks.jump", host.bookmarkCoordinator()::openJumpPalette));
        host.registry().register(Command.of("bookmarks.clearFile", host.bookmarkCoordinator()::clearInFile));
        host.registry().register(Command.of("bookmarks.setMnemonic", host.bookmarkCoordinator()::setMnemonicAtCaret));
        // One command per digit, so each is a single chord rather than a chord plus a prompt — which is
        // the entire point of a mnemonic. Explicit titles from one parameterized string, the way the macro
        // and external-tool commands avoid ten near-identical keys. The key deliberately sits OUTSIDE the
        // command.* namespace: it is a template, not the title of a command called
        // "bookmarks.gotoMnemonic", and every real command.* key is required to carry a .desc.
        for (char digit : com.editora.config.BookmarkMnemonics.DIGITS.toCharArray()) {
            String key = String.valueOf(digit);
            host.registry()
                    .register(Command.of(
                            "bookmarks.gotoMnemonic" + key,
                            tr("bookmarks.mnemonic.gotoTitle", key),
                            () -> host.bookmarkCoordinator().gotoMnemonic(key)));
        }
        host.registry()
                .register(Command.of(
                        "notes.add", () -> host.notesCoordinator().ifEnabled(host.notesCoordinator()::addNoteAtCaret)));
        host.registry()
                .register(Command.of(
                        "notes.editNote",
                        () -> host.notesCoordinator().ifEnabled(host.notesCoordinator()::editNoteAtCaret)));
        host.registry()
                .register(Command.of(
                        "notes.toggleResolved",
                        () -> host.notesCoordinator().ifEnabled(host.notesCoordinator()::toggleResolvedAtCaret)));
        host.registry()
                .register(Command.of(
                        "notes.next",
                        () -> host.notesCoordinator()
                                .ifEnabled(() -> host.notesCoordinator().jumpNote(true))));
        host.registry()
                .register(Command.of(
                        "notes.previous",
                        () -> host.notesCoordinator()
                                .ifEnabled(() -> host.notesCoordinator().jumpNote(false))));
        host.registry()
                .register(Command.of(
                        "notes.jump",
                        () -> host.notesCoordinator().ifEnabled(host.notesCoordinator()::openJumpPalette)));
        host.registry()
                .register(Command.of(
                        "notes.search", () -> host.notesCoordinator().ifEnabled(host.notesCoordinator()::searchNotes)));
        host.registry()
                .register(Command.of(
                        "notes.delete",
                        () -> host.notesCoordinator().ifEnabled(host.notesCoordinator()::deleteNoteAtCaret)));
        host.registry()
                .register(Command.of(
                        "notes.export", () -> host.notesCoordinator().ifEnabled(host.notesCoordinator()::exportNotes)));
        host.registry().register(Command.of("snippets.insert", host::insertSnippetPicker));
        host.registry().register(Command.of("snippets.reload", () -> {
            host.snippets().reload();
            host.setStatus(tr("status.snippetsReloaded"));
        }));
        host.registry().register(Command.of("snippets.editUser", host::editUserSnippets));
        host.registry()
                .register(Command.of(
                        "snippets.manage", () -> host.settingsWindow().showSnippets(host.stage())));
        host.registry()
                .register(Command.of(
                        "view.toggleMenuBar",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleMenuBar",
                                        () -> host.config().getSettings().isShowMenuBar(),
                                        host.config().getSettings()::setShowMenuBar,
                                        host.chrome()::applyChromeVisibility)));
        host.registry().register(Command.of("view.splitVertical", host::onSplitVertical));
        host.registry().register(Command.of("view.splitHorizontal", host::onSplitHorizontal));
        host.registry().register(Command.of("view.unsplit", host::unsplit));
        // Editor *groups* — a different axis from the two commands above, which split the active buffer into
        // two views of the same document. These move a tab into its own group so two different files show
        // side by side.
        host.registry()
                .register(Command.of("view.splitEditorRight", () -> host.splitEditorGroup(Orientation.HORIZONTAL)));
        host.registry().register(Command.of("view.splitEditorDown", () -> host.splitEditorGroup(Orientation.VERTICAL)));
        host.registry().register(Command.of("view.moveToNextGroup", host::moveTabToNextGroup));
        host.registry().register(Command.of("view.focusNextGroup", host::focusNextEditorGroup));
        host.registry().register(Command.of("view.unsplitEditorGroups", host::unsplitEditorGroups));
        host.registry()
                .register(Command.of(
                        "view.markdownEditor",
                        () -> host.previews().setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.EDITOR)));
        host.registry()
                .register(Command.of(
                        "view.markdownSplit",
                        () -> host.previews().setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.SPLIT)));
        host.registry()
                .register(Command.of(
                        "view.markdownPreview",
                        () -> host.previews().setActiveMarkdownMode(EditorBuffer.MarkdownViewMode.PREVIEW)));
        host.registry()
                .register(
                        Command.of("view.markdownZoomIn", () -> host.previews().markdownZoom(1)));
        host.registry()
                .register(
                        Command.of("view.markdownZoomOut", () -> host.previews().markdownZoom(-1)));
        host.registry()
                .register(Command.of(
                        "view.markdownZoomReset", () -> host.previews().markdownZoom(0)));
        host.registry()
                .register(Command.of("view.toggleMarkdownPreviewTheme", host.previews()::toggleMarkdownPreviewTheme));
        // Markdown editing (markdown buffers only; no-op with a status elsewhere).
        host.registry()
                .register(Command.of("markdown.bold", () -> host.previews().markdownInline("**")));
        host.registry()
                .register(Command.of("markdown.italic", () -> host.previews().markdownInline("*")));
        host.registry()
                .register(Command.of(
                        "markdown.strikethrough", () -> host.previews().markdownInline("~~")));
        host.registry()
                .register(Command.of("markdown.code", () -> host.previews().markdownInline("`")));
        host.registry()
                .register(Command.of(
                        "markdown.link", () -> host.previews().withMarkdown(EditorBuffer::formatLinkFromClipboard)));
        host.registry()
                .register(Command.of(
                        "markdown.bulletList", () -> host.previews().withMarkdown(EditorBuffer::formatBulletList)));
        host.registry()
                .register(Command.of(
                        "markdown.taskList", () -> host.previews().withMarkdown(EditorBuffer::formatTaskList)));
        host.registry().register(Command.of("markdown.insertTable", host.previews()::markdownInsertTableViaText));
        host.registry()
                .register(
                        Command.of("markdown.tableAddRow", () -> host.previews().withMarkdown(b -> b.tableAddRow())));
        host.registry()
                .register(Command.of(
                        "markdown.tableDeleteRow", () -> host.previews().withMarkdown(b -> b.tableDeleteRow())));
        host.registry()
                .register(Command.of(
                        "markdown.tableAddColumn", () -> host.previews().withMarkdown(b -> b.tableAddColumn())));
        host.registry()
                .register(Command.of(
                        "markdown.tableDeleteColumn", () -> host.previews().withMarkdown(b -> b.tableDeleteColumn())));
        host.registry()
                .register(Command.of(
                        "markdown.tableAlignLeft",
                        () -> host.previews().withMarkdown(b -> b.tableSetAlignment(MarkdownTable.Align.LEFT))));
        host.registry()
                .register(Command.of(
                        "markdown.tableAlignCenter",
                        () -> host.previews().withMarkdown(b -> b.tableSetAlignment(MarkdownTable.Align.CENTER))));
        host.registry()
                .register(Command.of(
                        "markdown.tableAlignRight",
                        () -> host.previews().withMarkdown(b -> b.tableSetAlignment(MarkdownTable.Align.RIGHT))));
        host.registry()
                .register(Command.of(
                        "markdown.headingPromote", () -> host.previews().withMarkdown(b -> b.formatHeading(-1))));
        host.registry()
                .register(Command.of(
                        "markdown.headingDemote", () -> host.previews().withMarkdown(b -> b.formatHeading(1))));
        host.registry().register(Command.of("markdown.openLink", host.previews()::markdownOpenLink));
        // Typst markup formatting (mirrors the markdown.* set; Typst uses *bold*, _emph_, `raw`, = headings).
        host.registry().register(Command.of("typst.bold", () -> host.previews().withTypst(b -> b.formatInline("*"))));
        host.registry().register(Command.of("typst.emph", () -> host.previews().withTypst(b -> b.formatInline("_"))));
        host.registry().register(Command.of("typst.raw", () -> host.previews().withTypst(b -> b.formatInline("`"))));
        host.registry()
                .register(Command.of(
                        "typst.link", () -> host.previews().withTypst(EditorBuffer::formatLinkFromClipboard)));
        host.registry()
                .register(Command.of(
                        "typst.bulletList", () -> host.previews().withTypst(EditorBuffer::formatBulletList)));
        host.registry()
                .register(
                        Command.of("typst.headingPromote", () -> host.previews().withTypst(b -> b.formatHeading(-1))));
        host.registry()
                .register(
                        Command.of("typst.headingDemote", () -> host.previews().withTypst(b -> b.formatHeading(1))));
        host.registry()
                .register(Command.of(
                        "typst.insertTable",
                        () -> host.previews().withTypst(EditorBuffer::insertTypstTableInteractive)));
        host.registry()
                .register(
                        Command.of("typst.outline", () -> host.previews().withTypst(EditorBuffer::insertTypstOutline)));
        host.registry()
                .register(Command.of(
                        "typst.insertImage",
                        () -> host.previews().withTypst(host.editing()::insertTypstImageFromChooser)));
        host.registry().register(Command.of("typst.exportPng", host.typst()::exportPng));
        host.registry().register(Command.of("typst.exportSvg", host.typst()::exportSvg));
        host.registry().register(Command.of("markdown.reflowTable", host.previews()::markdownReflowTable));
        host.registry().register(Command.of("markdown.toc", host.previews()::markdownToc));
        host.registry().register(Command.of("markdown.tableFromCsv", host.previews()::markdownTableFromCsv));
        host.registry().register(Command.of("markdown.tableToCsv", host.previews()::markdownTableToCsv));
        host.registry()
                .register(Command.of(
                        "markdown.tableExportCsv", () -> host.previews().markdownTableExport("csv")));
        host.registry()
                .register(Command.of(
                        "markdown.tableExportExcel", () -> host.previews().markdownTableExport("xlsx")));
        host.registry()
                .register(Command.of(
                        "markdown.tableExportOds", () -> host.previews().markdownTableExport("ods")));
        host.registry().register(Command.of("csv.copyAsMarkdownTable", host.previews()::csvCopyAsMarkdownTable));
        host.registry().register(Command.of("csv.align", host.previews()::csvAlign));
        host.registry().register(Command.of("csv.shrink", host.previews()::csvShrink));
        host.registry().register(Command.of("markdown.toggleFormatBar", host.previews()::toggleMarkdownFormatBar));
        host.registry().register(Command.of("view.textZoomIn", () -> host.textZoom(1)));
        host.registry().register(Command.of("view.textZoomOut", () -> host.textZoom(-1)));
        host.registry().register(Command.of("view.textZoomReset", () -> host.textZoom(0)));
        host.registry().register(Command.of("view.foldAll", host.navigation()::foldAll));
        host.registry().register(Command.of("view.unfoldAll", host.navigation()::unfoldAll));
        host.registry().register(Command.of("view.fold", host.navigation()::foldAtCaret));
        host.registry().register(Command.of("view.unfold", host.navigation()::unfoldAtCaret));
        host.registry().register(Command.of("view.toggleFold", host.navigation()::toggleFoldAtCaret));
        host.registry().register(Command.of("view.foldRecursively", host.navigation()::foldRecursively));
        host.registry().register(Command.of("view.unfoldRecursively", host.navigation()::unfoldRecursively));
        host.registry().register(Command.of("view.gotoParentFold", host.navigation()::gotoParentFold));
        host.registry().register(Command.of("view.gotoNextFold", host.navigation()::gotoNextFold));
        host.registry().register(Command.of("view.gotoPreviousFold", host.navigation()::gotoPreviousFold));
        for (int level = 1; level <= 7; level++) {
            int lvl = level;
            host.registry()
                    .register(Command.of(
                            "view.foldLevel" + lvl, () -> host.navigation().foldLevel(lvl)));
        }
        host.registry()
                .register(Command.of("view.createFoldFromSelection", host.navigation()::createFoldFromSelection));
        host.registry().register(Command.of("view.removeManualFolds", host.navigation()::removeManualFolds));
        host.registry().register(Command.of("view.foldAllExcept", host.navigation()::foldAllExcept));
        host.registry().register(Command.of("view.unfoldAllExcept", host.navigation()::unfoldAllExcept));
        host.registry().register(Command.of("view.foldAllBlockComments", host.navigation()::foldAllBlockComments));
        host.registry().register(Command.of("view.foldAllMarkerRegions", host.navigation()::foldAllMarkerRegions));
        host.registry().register(Command.of("view.unfoldAllMarkerRegions", host.navigation()::unfoldAllMarkerRegions));
        host.registry().register(Command.of("nav.goToLine", host.navigation()::goToLine));
        host.registry().register(Command.of("buffer.setLanguage", host.editorSettings()::chooseLanguage));
        host.registry().register(Command.of("buffer.setTabSize", host.editorSettings()::chooseTabSize));
        host.registry().register(Command.of("buffer.convertLineEndings", host.editorSettings()::chooseLineEndings));
        host.registry().register(Command.of("window.other", host.chrome()::otherWindow));
        // Cross-platform via JavaFX Stage (handles the per-OS window manager specifics on macOS/Linux/Windows).
        host.registry()
                .register(Command.of(
                        "window.maximize",
                        () -> host.stage().setMaximized(!host.stage().isMaximized())));
        host.registry()
                .register(Command.of(
                        "window.fullScreen",
                        () -> host.stage().setFullScreen(!host.stage().isFullScreen())));
        host.registry().register(Command.of("file.clearRecent", host::onClearRecent));
        host.registry().register(Command.of("help.about", host::onAbout));
        host.registry().register(Command.of("help.documentation", host::openDocumentation));
        host.registry().register(Command.of("help.checkForUpdates", host::checkForUpdatesNow));
        host.registry().register(Command.of("update.openDownloadPage", host::openUpdateDownloadPage));
        host.registry()
                .register(Command.of(
                        "view.toggleUpdateCheck",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleUpdateCheck",
                                        () -> host.config().getSettings().isUpdateCheck(),
                                        host.config().getSettings()::setUpdateCheck,
                                        null)));
        host.registry().register(Command.of("view.welcome", host::showWelcome));
        host.registry().register(Command.of("view.doctor", host::showDoctor));
        host.registry().register(Command.of("view.messageLog", host.statusBar()::showMessageLog));
        host.registry().register(Command.of("view.debugLog", host::showDebugLog));
        host.registry().register(Command.of("view.openAsHex", host::openActiveAsHex));
        host.registry().register(Command.of("view.openAsText", host::openActiveAsText));
        host.registry().register(Command.of("tool.project", () -> {
            if (host.projectsEnabled()) {
                host.toolWindows().toggle(host.projectToolWindow());
            }
        }));
        host.registry()
                .register(Command.of("tool.structure", () -> host.toolWindows().toggle(host.structureToolWindow())));
        host.registry()
                .register(Command.of("tool.bookmarks", () -> host.toolWindows().toggle(host.bookmarksToolWindow())));
        host.registry()
                .register(
                        Command.of("tool.undoHistory", () -> host.toolWindows().toggle(host.undoHistoryToolWindow())));
        host.registry()
                .register(Command.of(
                        "tool.notes",
                        () -> host.notesCoordinator()
                                .ifEnabled(() -> host.toolWindows().toggle(host.notesToolWindow()))));
        host.registry()
                .register(Command.of(
                        "tool.fileInformation", () -> host.toolWindows().toggle(host.fileInfoToolWindow())));
        host.registry().register(Command.of("tool.remote", () -> {
            host.remoteCoordinator().refreshPanel();
            host.toolWindows().toggle(host.remoteToolWindow());
        }));
        host.registry()
                .register(Command.of("tool.search", () -> host.toolWindows().toggle(host.searchToolWindow())));
        host.registry().register(Command.of("search.inFiles", host.searchCoordinator()::openToggle));
        host.registry().register(Command.of("search.inFilesPopup", host.searchCoordinator()::showFindInFilesPopup));
        host.todoCoordinator().registerCommands(host.registry()); // tool.todo + todo.refresh + todo.addPattern
        host.csvCoordinator().registerCommands(host.registry()); // csv.exportPdf/print/exportExcel/exportOds
        for (BuildCoordinator c : host.buildCoordinators()) {
            c.registerCommands(host.registry()); // tool.<id> + <id>.showActions/runCustom/stop/rerunLast/refresh
            BuildTool tool = c.tool();
            host.registry()
                    .register(Command.of(
                            tool.toggleCommandId(), // e.g. view.toggleMavenSupport / view.toggleNpmSupport
                            () -> host.editorSettings()
                                    .toggleSetting(
                                            tool.toggleCommandId(),
                                            () -> tool.enabledIn(host.config().getSettings()),
                                            v -> tool.setEnabledIn(host.config().getSettings(), v),
                                            () -> {
                                                host.refreshBuildTools();
                                                if (host.settingsWindow() != null) {
                                                    host.settingsWindow().refreshDetectionStatus();
                                                }
                                            })));
        }
        host.registry()
                .register(
                        Command.of("tool.buildOutput", () -> host.toolWindows().toggle(host.buildOutputToolWindow())));
        host.registry()
                .register(Command.of(
                        "view.toggleTodoHighlight",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleTodoHighlight",
                                        () -> host.config().getSettings().isTodoHighlight(),
                                        v -> host.config().getSettings().setTodoHighlight(v),
                                        host.todoCoordinator()::applyHighlight)));
        host.registry().register(Command.of("todo.setPartColor", host.editorSettings()::chooseTodoPartColor));
        host.registry().register(Command.of("tool.markdownLint", host.previews()::toggleMarkdownLintWindow));
        host.registry().register(Command.of("markdownLint.refresh", () -> {
            if (!host.toolWindows().isOpen(host.markdownLintToolWindow())) {
                host.toolWindows().toggle(host.markdownLintToolWindow());
            }
            host.previews().runMarkdownLintScan();
        }));
        host.registry()
                .register(Command.of(
                        "view.toggleMarkdownLint",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleMarkdownLint",
                                        () -> host.config().getSettings().isMarkdownLint(),
                                        v -> host.config().getSettings().setMarkdownLint(v),
                                        host.previews()::applyMarkdownLint)));
        host.registry().register(Command.of("markdownLint.fix", host.previews()::fixMarkdownLint));
        host.registry().register(Command.of("markdownLint.toggleRule", host.previews()::chooseMarkdownLintRule));
        host.registry()
                .register(Command.of(
                        "view.toggleMath",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleMath",
                                        () -> host.config().getSettings().isMathSupport(),
                                        v -> host.config().getSettings().setMathSupport(v),
                                        host::applyMathSupport)));
        host.registry().register(Command.of("nav.aceJump", host::startAceJump));
        host.registry().register(Command.of("nav.aceJumpLine", host::startAceJumpLine));
        // Run a Java 25 compact source file (also surfaced as the toolbar Run button when one is active).
        host.registry().register(Command.of("file.run", host.runCoordinator()::runActiveFile));
        host.registry().register(Command.of("file.runWithArgs", host.runCoordinator()::runActiveFileWithArgs));
        host.registry().register(Command.of("run.mainClass", host.runCoordinator()::runMainClass));
        host.registry().register(Command.of("run.config", host.runConfigurations()::runSavedConfig));
        // Under `debug.` so Chrome's feature rule gates it with the rest of debugging, exactly like the
        // per-configuration debug.config.<slug> commands below.
        host.registry().register(Command.of("debug.config", host.runConfigurations()::debugSavedConfig));
        host.registry().register(Command.of("run.saveConfig", host.runConfigurations()::saveRunConfig));
        host.registry().register(Command.of("run.deleteConfig", host.runConfigurations()::deleteRunConfig));
        host.registry().register(Command.of("run.editConfigs", host.runConfigurations()::editRunConfigs));
        host.registry().register(Command.of("run.exportConfigs", host.runConfigurations()::exportRunConfigs));
        host.registry().register(Command.of("run.importConfigs", host.runConfigurations()::importRunConfigs));
        host.registry().register(Command.of("run.rerun", host.runCoordinator()::rerunLast));
        host.registry().register(Command.of("run.stop", host.runCoordinator()::stopRun));
        host.registry().register(Command.of("run.clear", host.runCoordinator()::clearConsole));
        host.registry().register(Command.of("tool.run", () -> host.toolWindows().toggle(host.runToolWindow())));
        // Test Results (IntelliJ-style test runner): intercepts a build tool's `test` run. Gated by the
        // "Enable Test Results" setting (default on) + suppressed in Simple UI mode.
        host.registry().register(Command.of("test.run", host::runTestsForContext));
        host.registry().register(Command.of("test.runAtCaret", () -> host.runTestAtCaret(false)));
        host.registry().register(Command.of("test.runClassAtCaret", () -> host.runTestAtCaret(true)));
        host.registry().register(Command.of("test.rerun", host.testRunCoordinator()::rerun));
        host.registry().register(Command.of("test.rerunFailed", host.testRunCoordinator()::rerunFailed));
        host.registry().register(Command.of("test.stop", host.testRunCoordinator()::stop));
        host.registry().register(Command.of("test.showOnlyFailed", host.testRunCoordinator()::showOnlyFailed));
        host.registry().register(Command.of("test.showAllTests", host.testRunCoordinator()::showAllTests));
        host.registry().register(Command.of("test.filterTests", host.testRunCoordinator()::focusFilter));
        host.registry()
                .register(
                        Command.of("tool.testResults", () -> host.toolWindows().toggle(host.testResultsToolWindow())));
        host.registry()
                .register(Command.of(
                        "view.toggleTestRunner",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleTestRunner",
                                        host.config().getSettings()::isTestRunner,
                                        host.config().getSettings()::setTestRunner,
                                        host::applyTestRunner)));
        host.registry()
                .register(Command.of(
                        "tool.externalTools", () -> host.toolWindows().toggle(host.externalToolToolWindow())));
        // HTTP Client (.http via ijhttp). Gated by the "Enable HTTP Client" setting (default off).
        host.registry().register(Command.of("http.runRequest", host.httpClient()::runRequestAtCaret));
        host.registry().register(Command.of("http.runFile", host.httpClient()::runFile));
        host.registry().register(Command.of("http.selectEnvironment", host.httpClient()::selectEnvironment));
        host.registry().register(Command.of("http.importCurl", host.httpClient()::importCurl));
        host.registry().register(Command.of("http.copyAsCurl", host.httpClient()::copyActiveAsCurl));
        host.registry().register(Command.of("http.openResponseInTab", host.httpClient()::openActiveResponseInTab));
        // Debugging (DAP). Gated by the "Enable Java debugging" setting (default off).
        host.registry()
                .register(Command.of(
                        "debug.start", () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::debugStart)));
        host.registry()
                .register(Command.of(
                        "debug.mainClass",
                        () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::debugMainClass)));
        host.registry()
                .register(Command.of(
                        "debug.viaBuild",
                        () -> host.debugCoordinator().ifDebug(host.runConfigurations()::debugViaBuild)));
        host.registry()
                .register(Command.of("debug.stop", () -> host.debugCoordinator().ifDebug(host.dapManager()::stop)));
        host.registry()
                .register(Command.of(
                        "debug.restart", () -> host.debugCoordinator().ifDebug(host.dapManager()::restart)));
        host.registry()
                .register(Command.of(
                        "debug.attach", () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::debugAttach)));
        host.registry()
                .register(Command.of(
                        "debug.continue", () -> host.debugCoordinator().ifDebug(host.dapManager()::resume)));
        host.registry()
                .register(
                        Command.of("debug.pause", () -> host.debugCoordinator().ifDebug(host.dapManager()::pause)));
        host.registry()
                .register(Command.of(
                        "debug.runToCursor",
                        () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::debugRunToCursor)));
        host.registry()
                .register(Command.of(
                        "debug.jumpToLine",
                        () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::debugJumpToLine)));
        // Debugger data inspection (parity with the Debug panel's own controls).
        host.registry()
                .register(Command.of(
                        "debug.evaluate",
                        () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::focusEvaluate)));
        host.registry()
                .register(Command.of(
                        "debug.addWatch", () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::addWatch)));
        host.registry()
                .register(Command.of(
                        "debug.setValue",
                        () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::setSelectedValue)));
        host.registry()
                .register(Command.of(
                        "debug.stepOver", () -> host.debugCoordinator().ifDebug(host.dapManager()::stepOver)));
        host.registry()
                .register(Command.of(
                        "debug.stepInto", () -> host.debugCoordinator().ifDebug(host.dapManager()::stepInto)));
        host.registry()
                .register(Command.of(
                        "debug.stepOut", () -> host.debugCoordinator().ifDebug(host.dapManager()::stepOut)));
        host.registry()
                .register(Command.of(
                        "debug.toggleBreakpoint",
                        () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::toggleBreakpointAtCaret)));
        host.registry()
                .register(Command.of(
                        "debug.editBreakpoint",
                        () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::editBreakpointAtCaret)));
        host.registry()
                .register(Command.of(
                        "debug.toggleExceptionBreakpoints",
                        () -> host.debugCoordinator().ifDebug(host.debugCoordinator()::toggleExceptionBreakpoints)));
        host.registry()
                .register(Command.of(
                        "tool.debug",
                        () -> host.debugCoordinator()
                                .ifDebug(() -> host.toolWindows().toggle(host.debugToolWindow()))));
        // LSP. Gated by the "Enable LSP" setting (default off); commands no-op with a status when off.
        host.registry()
                .register(Command.of(
                        "tool.problems",
                        () -> host.ifLsp(() -> host.toolWindows().toggle(host.problemsToolWindow()))));
        host.registry()
                .register(Command.of(
                        "tool.references",
                        () -> host.ifLsp(() -> host.toolWindows().toggle(host.referencesToolWindow()))));
        host.registry()
                .register(Command.of(
                        "tool.hierarchy",
                        () -> host.ifLsp(() -> host.toolWindows().toggle(host.hierarchyToolWindow()))));
        host.registry().register(Command.of("search.everywhere", host.navigation()::showSearchEverywhere));
        host.registry()
                .register(Command.of(
                        "view.togglePaletteSearchEverywhere",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.togglePaletteSearchEverywhere",
                                        () -> host.config().getSettings().isPaletteUsesSearchEverywhere(),
                                        host.config().getSettings()::setPaletteUsesSearchEverywhere,
                                        null))); // nothing to re-apply: onPalette() reads the setting when it runs
        host.registry().register(Command.of("index.gotoSymbol", host.indexCoordinator()::gotoSymbol));
        host.registry().register(Command.of("index.rebuild", host.indexCoordinator()::rebuild));
        host.registry()
                .register(Command.of(
                        "view.toggleSymbolIndex",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleSymbolIndex",
                                        () -> host.config().getSettings().isSymbolIndex(),
                                        host.config().getSettings()::setSymbolIndex,
                                        () -> {
                                            host.indexCoordinator().applySupport();
                                            host.settingsWindow().syncAll();
                                        })));
        host.registry()
                .register(Command.of("lsp.gotoDefinition", () -> host.ifLsp(host.lspCoordinator()::gotoDefinition)));
        host.registry()
                .register(Command.of("lsp.peekDefinition", () -> host.ifLsp(host.lspCoordinator()::peekDefinition)));
        host.registry()
                .register(Command.of(
                        "lsp.gotoDefinitionInSplit", () -> host.ifLsp(host.navigation()::gotoDefinitionInSplit)));
        host.registry()
                .register(Command.of("lsp.findReferences", () -> host.ifLsp(host.lspCoordinator()::findReferences)));
        host.registry()
                .register(Command.of(
                        "lsp.gotoImplementation", () -> host.ifLsp(host.lspCoordinator()::gotoImplementation)));
        host.registry()
                .register(Command.of(
                        "lsp.gotoTypeDefinition", () -> host.ifLsp(host.lspCoordinator()::gotoTypeDefinition)));
        host.registry()
                .register(Command.of("lsp.gotoDeclaration", () -> host.ifLsp(host.lspCoordinator()::gotoDeclaration)));
        host.registry()
                .register(Command.of("lsp.gotoSymbol", () -> host.ifLsp(host.lspCoordinator()::gotoSymbolInWorkspace)));
        host.registry().register(Command.of("lsp.hover", () -> host.ifLsp(host.lspCoordinator()::showHover)));
        host.registry()
                .register(Command.of("lsp.restartServers", () -> host.ifLsp(host.lspCoordinator()::restartServers)));
        host.registry()
                .register(Command.of("lsp.buildWorkspace", () -> host.ifLsp(host.lspCoordinator()::buildWorkspace)));
        host.registry()
                .register(Command.of("lsp.organizeImports", () -> host.ifLsp(host.lspCoordinator()::organizeImports)));
        host.registry()
                .register(Command.of(
                        "lsp.copyQualifiedName", () -> host.ifLsp(host.lspCoordinator()::copyQualifiedName)));
        host.registry()
                .register(Command.of("lsp.reloadProject", () -> host.ifLsp(host.lspCoordinator()::reloadProject)));
        host.registry()
                .register(Command.of(
                        "lsp.toggleProjectProblems",
                        () -> host.ifLsp(() -> host.lspCoordinator()
                                .setProjectWideProblems(!host.lspCoordinator().isProjectWideProblems()))));
        host.registry()
                .register(Command.of("lsp.formatDocument", () -> host.ifLsp(host.lspCoordinator()::formatDocument)));
        host.registry().register(Command.of("lsp.codeActions", () -> host.ifLsp(host.lspCoordinator()::codeActions)));
        host.registry()
                .register(Command.of(
                        "lsp.signatureHelp",
                        () -> host.ifLsp(() -> host.lspCoordinator().signatureHelp(true))));
        host.registry().register(Command.of("lsp.rename", () -> host.ifLsp(host.lspCoordinator()::rename)));
        host.registry()
                .register(Command.of("lsp.callHierarchy", () -> host.ifLsp(host.lspCoordinator()::callHierarchy)));
        host.registry()
                .register(Command.of("lsp.typeHierarchy", () -> host.ifLsp(host.lspCoordinator()::typeHierarchy)));
        host.registry().register(Command.of("view.toggleLsp", host::toggleLsp));
        host.registry()
                .register(Command.of(
                        "view.toggleSemanticHighlight",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleSemanticHighlight",
                                        () -> host.config().getSettings().isSemanticHighlight(),
                                        host.config().getSettings()::setSemanticHighlight,
                                        host.lspCoordinator()::applySemanticHighlight)));
        host.registry()
                .register(Command.of(
                        "view.toggleOnTypeFormatting",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleOnTypeFormatting",
                                        () -> host.config().getSettings().isLspOnTypeFormatting(),
                                        host.config().getSettings()::setLspOnTypeFormatting,
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry()
                .register(Command.of(
                        "view.togglePasteImports",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.togglePasteImports",
                                        () -> host.config().getSettings().isLspPasteImports(),
                                        host.config().getSettings()::setLspPasteImports,
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry()
                .register(Command.of(
                        "view.toggleSmartSemicolon",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleSmartSemicolon",
                                        () -> host.config().getSettings().isLspSmartSemicolon(),
                                        host.config().getSettings()::setLspSmartSemicolon,
                                        () -> host.editorSettings()
                                                .applyViewSettingsToAllBuffers(
                                                        host.config().getSettings()))));
        host.registry()
                .register(Command.of(
                        "view.toggleInlayHints",
                        () -> host.editorSettings()
                                .toggleSetting(
                                        "view.toggleInlayHints",
                                        () -> host.config().getSettings().isInlayHints(),
                                        host.config().getSettings()::setInlayHints,
                                        host.lspCoordinator()::applyInlayHints)));
        host.registry().register(Command.of("lsp.setInlayHintMode", host.editorSettings()::chooseInlayHintMode));
        host.registry()
                .register(Command.of(
                        "tool.commit",
                        () -> host.git().ifEnabled(() -> host.toolWindows().toggle(host.commitToolWindow()))));
        // Git (native CLI). Gated by the "Enable Git" setting (default off); also no-op when Git is
        // absent / not in a repo. The ifGit wrapper disables the commands + keybindings when Git is off.
        host.registry().register(Command.of("remote.connect", host.remoteCoordinator()::connect));
        host.registry().register(Command.of("remote.openFile", host.remoteCoordinator()::openFile));
        host.registry().register(Command.of("remote.manageConnections", host.remoteCoordinator()::manageConnections));
        host.registry()
                .register(Command.of(
                        "remote.settings", () -> host.settingsWindow().showRemote(host.stage())));
        host.registry().register(Command.of("remote.disconnect", host.remoteCoordinator()::disconnect));
        host.registry().register(Command.of("git.clone", () -> host.git().ifEnabled(host.git()::cloneRepo)));
        host.registry().register(Command.of("git.init", () -> host.git().ifEnabled(host.git()::initRepo)));
        host.registry().register(Command.of("git.commit", () -> host.git().ifEnabled(host.git()::gitCommitFocus)));
        host.registry()
                .register(Command.of("git.stageFile", () -> host.git().ifEnabled(host.git()::gitStageActiveFile)));
        host.registry()
                .register(Command.of("git.unstageFile", () -> host.git().ifEnabled(host.git()::gitUnstageActiveFile)));
        host.registry()
                .register(Command.of("git.discardFile", () -> host.git().ifEnabled(host.git()::gitDiscardActiveFile)));
        host.registry()
                .register(Command.of(
                        "git.stageSelected",
                        () -> host.git().ifEnabled(() -> host.gitWindows().stageSelectedInCommitWindow(true))));
        host.registry()
                .register(Command.of(
                        "git.unstageSelected",
                        () -> host.git().ifEnabled(() -> host.gitWindows().stageSelectedInCommitWindow(false))));
        host.registry()
                .register(Command.of("git.switchBranch", () -> host.git().ifEnabled(host.gitWindows()::chooseBranch)));
        host.registry().register(Command.of("git.newBranch", () -> host.git().ifEnabled(host.git()::newBranch)));
        host.registry()
                .register(Command.of(
                        "git.fetch", () -> host.git().ifEnabled(() -> host.git().gitSync("Fetch", "fetch", "--all"))));
        host.registry()
                .register(Command.of(
                        "git.pull", () -> host.git().ifEnabled(() -> host.git().gitSync("Pull", "pull", "--ff-only"))));
        host.registry().register(Command.of("git.push", () -> host.git().ifEnabled(host.git()::gitPush)));
        // Git Log: act on the commit selected in the Git Log tool window (parity with its right-click menu).
        host.registry()
                .register(Command.of(
                        "git.log.checkout", () -> host.gitWindows().withSelectedCommit(host.gitLogOps()::checkout)));
        host.registry()
                .register(Command.of(
                        "git.log.newBranch", () -> host.gitWindows().withSelectedCommit(host.gitLogOps()::newBranch)));
        host.registry()
                .register(Command.of(
                        "git.log.revert", () -> host.gitWindows().withSelectedCommit(host.gitLogOps()::revert)));
        host.registry()
                .register(Command.of(
                        "git.log.cherryPick",
                        () -> host.gitWindows().withSelectedCommit(host.gitLogOps()::cherryPick)));
        host.registry()
                .register(Command.of(
                        "git.log.reset",
                        () -> host.gitWindows().withSelectedCommit(host.gitWindows()::promptGitReset)));
        host.registry()
                .register(Command.of(
                        "git.log.copyHash", () -> host.gitWindows().withSelectedCommit(host.gitLogOps()::copyHash)));
        host.registry()
                .register(Command.of(
                        "git.refresh",
                        () -> host.git().ifEnabled(() -> {
                            host.git().invalidateCaches();
                            host.git().afterMutation();
                        })));
        // GitHub (native `gh` CLI). Gated by the "Enable GitHub" setting (on by default, inert until gh is
        // found + authenticated). Each flow reports the precise reason when gh isn't usable / not a GitHub repo.
        host.registry().register(Command.of("github.checkoutPr", host.github()::checkoutPr));
        host.registry().register(Command.of("github.viewPrDiff", host.github()::viewPrDiff));
        host.registry().register(Command.of("github.createPr", host.github()::createPr));
        host.registry().register(Command.of("github.submitReview", host.github()::submitReviewPicked));
        host.registry().register(Command.of("github.openOnGitHub", host.github()::openOnGitHub));
        host.registry()
                .register(Command.of(
                        "github.showRuns",
                        () -> host.github().ifEnabled(() -> {
                            host.toolWindows().open(host.githubToolWindow());
                            host.githubPanel().selectRuns();
                            host.github().fetchRuns(host.githubPanel()::setRuns);
                        })));
        host.registry().register(Command.of("github.viewRunLog", host.github()::viewRunLogPicked));
        host.registry().register(Command.of("github.refresh", host.github()::refresh));
        host.registry().register(Command.of("view.toggleGithub", host.github()::toggleSupport));
        host.registry()
                .register(Command.of(
                        "tool.github",
                        () -> host.github().ifEnabled(() -> host.toolWindows().toggle(host.githubToolWindow()))));
        // History / Log, blame, and stash (Core-trio parity with IntelliJ/VSCode).
        host.registry().register(Command.of("tool.gitLog", () -> host.git().ifEnabled(host.gitWindows()::showGitLog)));
        host.registry().register(Command.of("tool.fileHistory", host.historyCoordinator()::showActive));
        host.registry().register(Command.of("history.putLabel", host.historyCoordinator()::putLabel));
        host.registry().register(Command.of("history.recentChanges", host.historyCoordinator()::showRecentChanges));
        host.registry()
                .register(
                        Command.of("git.fileHistory", () -> host.git().ifEnabled(host.gitWindows()::showFileHistory)));
        host.registry().register(Command.of("git.toggleBlame", host.git()::toggleBlame));
        host.registry()
                .register(Command.of("git.blameShowCommit", () -> host.git().ifEnabled(host.git()::blameShowCommit)));
        host.registry().register(Command.of("git.stash", () -> host.git().ifEnabled(host.git()::gitStash)));
        host.registry().register(Command.of("git.stashPop", () -> host.git().ifEnabled(host.git()::gitStashPop)));
        host.registry().register(Command.of("git.unstash", () -> host.git().ifEnabled(host.git()::gitUnstash)));
        host.registry().register(Command.of("git.stashDrop", () -> host.git().ifEnabled(host.git()::gitStashDrop)));
        // Diff viewer + merge. The git-backed diffs are ifGit-gated; "Compare With…" and "Resolve
        // Conflicts" work on any file (no repo needed), so they are not gated.
        host.registry()
                .register(Command.of(
                        "diff.vsHead", () -> host.git().ifEnabled(host.diffCoordinator()::diffActiveVsHead)));
        host.registry()
                .register(Command.of(
                        "diff.reviewUnstaged",
                        () -> host.git().ifEnabled(() -> host.diffCoordinator().reviewGitChanges(false))));
        host.registry()
                .register(Command.of(
                        "diff.reviewStaged",
                        () -> host.git().ifEnabled(() -> host.diffCoordinator().reviewGitChanges(true))));
        // Diff viewer toolbar actions (act on the active diff tab).
        host.registry()
                .register(Command.of(
                        "diff.toggleView",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::toggleViewMode)));
        host.registry()
                .register(Command.of(
                        "diff.applyAll", () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::applyAllChanges)));
        host.registry()
                .register(Command.of(
                        "diff.editResult",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::toggleResultEditing)));
        host.registry()
                .register(Command.of(
                        "diff.swapSides",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::swapComparisonSides)));
        host.registry()
                .register(Command.of(
                        "diff.toggleIgnoreCase",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::toggleIgnoreCase)));
        host.registry()
                .register(Command.of(
                        "diff.toggleSmartAlignment",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::toggleSmartAlignment)));
        host.registry()
                .register(Command.of(
                        "diff.nextChange", () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::goNextChange)));
        host.registry()
                .register(Command.of(
                        "diff.previousChange",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::goPreviousChange)));
        host.registry()
                .register(Command.of(
                        "diff.stageHunk",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::stageCurrentHunk)));
        host.registry()
                .register(Command.of(
                        "diff.unstageHunk",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::unstageCurrentHunk)));
        host.registry()
                .register(Command.of(
                        "diff.revertHunk",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::revertCurrentHunk)));
        host.registry()
                .register(Command.of(
                        "diff.copyHunk", () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::copyCurrentHunk)));
        host.registry()
                .register(Command.of(
                        "diff.openChange",
                        () -> host.diffCoordinator().withActiveDiff(DiffViewerPane::openCurrentChange)));
        host.registry().register(Command.of("diff.compareWith", host.diffCoordinator()::compareActiveWithFile));
        host.registry()
                .register(Command.of("diff.compareClipboard", host.diffCoordinator()::compareActiveWithClipboard));
        host.registry().register(Command.of("diff.compareBlank", host.diffCoordinator()::compareActiveWithBlank));
        host.registry().register(Command.of("diff.compareDirectories", host.diffCoordinator()::compareDirectories));
        host.registry()
                .register(Command.of(
                        "diff.openPatchFile", () -> host.diffCoordinator().openPatchFile(host.activeBuffer())));
        host.registry()
                .register(Command.of(
                        "diff.vsCommit", () -> host.git().ifEnabled(host.diffCoordinator()::diffActiveVsCommit)));
        host.registry().register(Command.of("merge.resolve", host.diffCoordinator()::resolveConflicts));
        host.registry()
                .register(Command.of("switcher.show", () -> host.switcher().show(host.stage(), false)));
        host.registry()
                .register(
                        Command.of("switcher.showReverse", () -> host.switcher().show(host.stage(), true)));
        host.registry().register(Command.of("find.show", host::findShowOrNext));
        host.registry().register(Command.of("find.showBackward", host::findShowOrPrevious));
        host.registry().register(Command.of("find.replace", host::showReplace));
        host.registry().register(Command.of("find.next", host::findNextMatch));
        host.registry().register(Command.of("find.previous", host::findPreviousMatch));
        host.registry().register(Command.of("find.replaceCurrent", host::findReplaceCurrentMatch));
        host.registry().register(Command.of("find.replaceAll", host::findReplaceAllMatches));
        host.registry().register(Command.of("find.selectAllMatches", host::selectAllFindMatches));
        host.registry().register(Command.of("edit.selectAllOccurrences", host::selectAllOccurrences));
        host.registry().register(Command.of("edit.cut", host.editing()::onCut));
        host.registry().register(Command.of("edit.copy", host.editing()::onCopy));
        host.registry().register(Command.of("edit.paste", host.editing()::onPaste));
        host.registry().register(Command.of("edit.yankPop", host.editing()::yankPop));
        host.registry().register(Command.of("edit.yankFromRing", host.editing()::showKillRingPicker));
        host.registry().register(Command.of("edit.killRectangle", host.editing()::killRectangle));
        host.registry().register(Command.of("edit.copyRectangle", host.editing()::copyRectangle));
        host.registry().register(Command.of("edit.yankRectangle", host.editing()::yankRectangle));
        host.registry()
                .register(
                        Command.of("edit.deleteRectangle", () -> host.editing().rectangleEdit(Rectangle::delete)));
        host.registry()
                .register(Command.of("edit.clearRectangle", () -> host.editing().rectangleEdit(Rectangle::clear)));
        host.registry()
                .register(Command.of("edit.openRectangle", () -> host.editing().rectangleEdit(Rectangle::open)));
        host.registry().register(Command.of("edit.stringRectangle", host.editing()::stringRectangle));
        host.registry().register(Command.of("edit.numberRectangle", host.editing()::numberRectangle));
        host.registry().register(Command.of("edit.narrowToRegion", host.editing()::narrowToRegion));
        host.registry().register(Command.of("edit.narrowToDefun", host.editing()::narrowToDefun));
        host.registry().register(Command.of("edit.narrowToFoldRegion", host.editing()::narrowToFoldRegion));
        host.registry().register(Command.of("edit.widen", host.editing()::widenBuffer));
        host.registry().register(Command.of("edit.queryReplace", host.editing()::queryReplace));
        host.registry().register(Command.of("edit.queryReplaceRegexp", host.editing()::queryReplaceRegexp));
        host.registry().register(Command.of("edit.undo", host.editing()::onUndo));
        host.registry().register(Command.of("edit.redo", host.editing()::onRedo));
        host.registry().register(Command.of("edit.cancel", host::cancel));
        host.registry().register(Command.of("edit.completion", host.editing()::triggerCompletion));
        host.registry().register(Command.of("edit.completionDoc", host.editing()::toggleCompletionDoc));
        host.registry().register(Command.of("edit.toggleComment", host.editing()::toggleComment));
        host.registry()
                .register(Command.of(
                        "edit.transposeChars",
                        () -> host.editing().transpose(com.editora.editops.Transposer::transposeChars)));
        host.registry()
                .register(Command.of(
                        "edit.transposeWords",
                        () -> host.editing().transpose(com.editora.editops.Transposer::transposeWords)));
        host.registry()
                .register(Command.of(
                        "edit.transposeLines",
                        () -> host.editing().transpose(com.editora.editops.Transposer::transposeLines)));
        host.registry().register(Command.of("edit.selectAll", host.editing()::selectAll));
        host.registry()
                .register(Command.of(
                        "edit.duplicateLine", () -> host.editing().lineOp(com.editora.editops.LineOps::duplicateLine)));
        host.registry()
                .register(Command.of(
                        "edit.moveLineUp", () -> host.editing().lineOp(com.editora.editops.LineOps::moveLineUp)));
        host.registry()
                .register(Command.of(
                        "edit.moveLineDown", () -> host.editing().lineOp(com.editora.editops.LineOps::moveLineDown)));
        // Emacs fill commands: re-wrap paragraphs to the fill column (M-q / fill-region / set-fill-column).
        host.registry().register(Command.of("edit.fillParagraph", host.editing()::fillParagraph));
        host.registry().register(Command.of("edit.fillRegion", host.editing()::fillRegion));
        host.registry().register(Command.of("edit.setFillColumn", host.editing()::setFillColumn));
        // String manipulation (the String-Manipulation-plugin family): case-style conversions on the
        // selection/token at the caret + whole-line sorts/filters, all also reachable via one picker.
        host.registry().register(Command.of("edit.stringOps", host.editing()::stringOpsPicker));
        host.registry()
                .register(Command.of(
                        "edit.case.cycle", () -> host.editing().caseOp(com.editora.editops.StringCase::cycle)));
        host.registry()
                .register(Command.of(
                        "edit.case.camel",
                        () -> host.editing()
                                .caseOp(s -> com.editora.editops.StringCase.to(
                                        com.editora.editops.StringCase.Style.CAMEL, s))));
        host.registry()
                .register(Command.of(
                        "edit.case.pascal",
                        () -> host.editing()
                                .caseOp(s -> com.editora.editops.StringCase.to(
                                        com.editora.editops.StringCase.Style.PASCAL, s))));
        host.registry()
                .register(Command.of(
                        "edit.case.snake",
                        () -> host.editing()
                                .caseOp(s -> com.editora.editops.StringCase.to(
                                        com.editora.editops.StringCase.Style.SNAKE, s))));
        host.registry()
                .register(Command.of(
                        "edit.case.screamingSnake",
                        () -> host.editing()
                                .caseOp(s -> com.editora.editops.StringCase.to(
                                        com.editora.editops.StringCase.Style.SCREAMING_SNAKE, s))));
        host.registry()
                .register(Command.of(
                        "edit.case.kebab",
                        () -> host.editing()
                                .caseOp(s -> com.editora.editops.StringCase.to(
                                        com.editora.editops.StringCase.Style.KEBAB, s))));
        host.registry()
                .register(Command.of(
                        "edit.case.dot",
                        () -> host.editing()
                                .caseOp(s -> com.editora.editops.StringCase.to(
                                        com.editora.editops.StringCase.Style.DOT, s))));
        host.registry()
                .register(Command.of(
                        "edit.case.swap", () -> host.editing().caseOp(com.editora.editops.StringCase::swapCase)));
        host.registry()
                .register(Command.of(
                        "edit.sortLinesAsc",
                        () -> host.editing().lineTransform(com.editora.editops.LineTransforms::sortAscending)));
        host.registry()
                .register(Command.of(
                        "edit.sortLinesDesc",
                        () -> host.editing().lineTransform(com.editora.editops.LineTransforms::sortDescending)));
        host.registry()
                .register(Command.of(
                        "edit.sortLinesByLength",
                        () -> host.editing().lineTransform(com.editora.editops.LineTransforms::sortByLength)));
        host.registry()
                .register(Command.of(
                        "edit.reverseLines",
                        () -> host.editing().lineTransform(com.editora.editops.LineTransforms::reverse)));
        host.registry()
                .register(Command.of(
                        "edit.shuffleLines",
                        () -> host.editing()
                                .lineTransform(
                                        t -> com.editora.editops.LineTransforms.shuffle(t, new java.util.Random()))));
        host.registry()
                .register(Command.of(
                        "edit.removeDuplicateLines",
                        () -> host.editing().lineTransform(com.editora.editops.LineTransforms::removeDuplicates)));
        host.registry()
                .register(Command.of(
                        "edit.removeEmptyLines",
                        () -> host.editing().lineTransform(com.editora.editops.LineTransforms::removeEmpty)));
        host.registry()
                .register(Command.of(
                        "edit.trimTrailingWhitespace",
                        () -> host.editing().lineTransform(com.editora.editops.LineTransforms::trimTrailing)));
        host.registry().register(Command.of("edit.tabify", host.editing()::tabifyRegion));
        host.registry().register(Command.of("edit.untabify", host.editing()::untabifyRegion));
        host.registry()
                .register(Command.of(
                        "edit.indentationToSpaces", () -> host.editing().convertIndentation(true)));
        host.registry()
                .register(Command.of(
                        "edit.indentationToTabs", () -> host.editing().convertIndentation(false)));
        host.registry().register(Command.of("edit.alignRegexp", host.editing()::alignRegexpRegion));
        host.registry().register(Command.of("edit.occur", host.editing()::occur));
        // C-a: smart line start — first press to the beginning of the line's text (first non-whitespace),
        // a second press toggles to the true line start (column 0).
        host.registry().register(Command.of("nav.lineStart", () -> {
            if (host.editing().multiCaretMove(b -> b.multiMoveLineBoundary(false, host.editing().markActive))) {
                return;
            }
            host.editing()
                    .moveAndFollow(
                            a -> a.moveTo(TextNav.smartLineStart(a.getText(), a.getCaretPosition()), host.selPolicy()));
        }));
        host.registry().register(Command.of("nav.lineEnd", () -> {
            if (host.editing().multiCaretMove(b -> b.multiMoveLineBoundary(true, host.editing().markActive))) {
                return;
            }
            host.editing().moveAndFollow(a -> a.lineEnd(host.selPolicy()));
        }));
        host.registry()
                .register(Command.of(
                        "nav.docStart", () -> host.editing().moveAndFollow(a -> a.start(host.selPolicy()))));
        host.registry()
                .register(Command.of("nav.docEnd", () -> host.editing().moveAndFollow(a -> a.end(host.selPolicy()))));
        host.registry().register(Command.of("nav.charForward", () -> {
            if (host.editing().multiCaretMove(b -> b.multiMoveHorizontal(1, false, host.editing().markActive))) {
                return;
            }
            host.editing()
                    .moveAndFollow(a -> a.moveTo(Math.min(a.getLength(), a.getCaretPosition() + 1), host.selPolicy()));
        }));
        host.registry().register(Command.of("nav.charBackward", () -> {
            if (host.editing().multiCaretMove(b -> b.multiMoveHorizontal(-1, false, host.editing().markActive))) {
                return;
            }
            host.editing().moveAndFollow(a -> a.moveTo(Math.max(0, a.getCaretPosition() - 1), host.selPolicy()));
        }));
        host.registry().register(Command.of("nav.lineDown", () -> host.editing().moveLine(1)));
        host.registry().register(Command.of("nav.lineUp", () -> host.editing().moveLine(-1)));
        host.registry().register(Command.of("nav.wordForward", () -> {
            if (host.editing().multiCaretMove(b -> b.multiMoveHorizontal(1, true, host.editing().markActive))) {
                return;
            }
            host.editing()
                    .moveAndFollow(a -> a.moveTo(
                            host.editing().nextWordBoundary(a.getText(), a.getCaretPosition()), host.selPolicy()));
        }));
        host.registry().register(Command.of("nav.wordBackward", () -> {
            if (host.editing().multiCaretMove(b -> b.multiMoveHorizontal(-1, true, host.editing().markActive))) {
                return;
            }
            host.editing()
                    .moveAndFollow(a -> a.moveTo(
                            host.editing().prevWordBoundary(a.getText(), a.getCaretPosition()), host.selPolicy()));
        }));
        host.registry()
                .register(Command.of(
                        "nav.subwordForward",
                        () -> host.editing()
                                .moveAndFollow(a -> a.moveTo(
                                        TextNav.nextSubwordBoundary(a.getText(), a.getCaretPosition()),
                                        host.selPolicy()))));
        host.registry()
                .register(Command.of(
                        "nav.subwordBackward",
                        () -> host.editing()
                                .moveAndFollow(a -> a.moveTo(
                                        TextNav.prevSubwordBoundary(a.getText(), a.getCaretPosition()),
                                        host.selPolicy()))));
        host.registry()
                .register(Command.of(
                        "edit.deleteSubwordForward", () -> host.editing().deleteSubword(true)));
        host.registry()
                .register(Command.of(
                        "edit.deleteSubwordBackward", () -> host.editing().deleteSubword(false)));
        host.registry().register(Command.of("nav.pageDown", () -> {
            if (!host.previews().pageActivePreview(true)) {
                host.editing().moveAndFollow(a -> a.nextPage(host.selPolicy()));
            }
        }));
        host.registry().register(Command.of("nav.pageUp", () -> {
            if (!host.previews().pageActivePreview(false)) {
                host.editing().moveAndFollow(a -> a.prevPage(host.selPolicy()));
            }
        }));
        host.registry()
                .register(Command.of(
                        "nav.backToIndentation",
                        () -> host.editing()
                                .moveAndFollow(a -> a.moveTo(
                                        TextNav.backToIndentation(a.getText(), a.getCaretPosition()),
                                        host.selPolicy()))));
        host.registry()
                .register(Command.of(
                        "nav.paragraphForward",
                        () -> host.editing()
                                .moveAndFollow(a -> a.moveTo(
                                        TextNav.forwardParagraph(a.getText(), a.getCaretPosition()),
                                        host.selPolicy()))));
        host.registry()
                .register(Command.of(
                        "nav.paragraphBackward",
                        () -> host.editing()
                                .moveAndFollow(a -> a.moveTo(
                                        TextNav.backwardParagraph(a.getText(), a.getCaretPosition()),
                                        host.selPolicy()))));
        host.registry()
                .register(Command.of(
                        "nav.sentenceForward",
                        () -> host.editing()
                                .moveAndFollow(a -> a.moveTo(
                                        TextNav.forwardSentence(a.getText(), a.getCaretPosition()),
                                        host.selPolicy()))));
        host.registry()
                .register(Command.of(
                        "nav.sentenceBackward",
                        () -> host.editing()
                                .moveAndFollow(a -> a.moveTo(
                                        TextNav.backwardSentence(a.getText(), a.getCaretPosition()),
                                        host.selPolicy()))));
        host.registry().register(Command.of("nav.recenter", host.editing()::recenterCaret));
        host.registry().register(Command.of("edit.setMark", host.editing()::setMark));
        host.registry().register(Command.of("edit.exchangePointAndMark", host.editing()::exchangePointAndMark));
        host.registry().register(Command.of("edit.popMark", host.editing()::popMark));
        host.registry().register(Command.of(com.editora.command.KeyDispatcher.UNIVERSAL_ARGUMENT, () -> {}));
        host.registry()
                .register(Command.of("edit.deleteChar", () -> host.editing().withArea(CodeArea::deleteNextChar)));
        host.registry()
                .register(Command.of(
                        "edit.killWord",
                        () -> host.editing()
                                .emacsKill(
                                        (text, caret) -> {
                                            int end = host.editing().nextWordBoundary(text, caret);
                                            return end > caret
                                                    ? new com.editora.editops.EmacsEdits.Edit(caret, end, "", caret)
                                                    : null;
                                        },
                                        KillRing.Direction.FORWARD)));
        host.registry()
                .register(Command.of(
                        "edit.killLine",
                        () -> host.editing().emacsKill(EditingCoordinator::killLineEdit, KillRing.Direction.FORWARD)));
        host.registry()
                .register(Command.of(
                        "edit.backwardKillWord",
                        () -> host.editing()
                                .emacsKill(
                                        com.editora.editops.EmacsEdits::backwardKillWord,
                                        KillRing.Direction.BACKWARD)));
        host.registry()
                .register(Command.of(
                        "edit.upcaseWord", () -> host.editing().emacsEdit(com.editora.editops.EmacsEdits::upcaseWord)));
        host.registry()
                .register(Command.of(
                        "edit.downcaseWord",
                        () -> host.editing().emacsEdit(com.editora.editops.EmacsEdits::downcaseWord)));
        host.registry()
                .register(Command.of(
                        "edit.capitalizeWord",
                        () -> host.editing().emacsEdit(com.editora.editops.EmacsEdits::capitalizeWord)));
        host.registry()
                .register(Command.of("edit.upcaseRegion", () -> host.editing().emacsCaseRegion(true)));
        host.registry()
                .register(Command.of("edit.downcaseRegion", () -> host.editing().emacsCaseRegion(false)));
        host.registry()
                .register(Command.of(
                        "edit.deleteIndentation",
                        () -> host.editing().emacsEdit(com.editora.editops.EmacsEdits::deleteIndentation)));
        host.registry()
                .register(Command.of(
                        "edit.deleteHorizontalSpace",
                        () -> host.editing().emacsEdit(com.editora.editops.EmacsEdits::deleteHorizontalSpace)));
        host.registry()
                .register(Command.of(
                        "edit.justOneSpace",
                        () -> host.editing().emacsEdit(com.editora.editops.EmacsEdits::justOneSpace)));
        host.registry()
                .register(Command.of(
                        "edit.deleteBlankLines",
                        () -> host.editing().emacsEdit(com.editora.editops.EmacsEdits::deleteBlankLines)));
        host.registry()
                .register(Command.of(
                        "edit.openLine", () -> host.editing().emacsEdit(com.editora.editops.EmacsEdits::openLine)));
        host.registry()
                .register(Command.of(
                        "edit.killWholeLine",
                        () -> host.editing()
                                .emacsKill(com.editora.editops.EmacsEdits::killWholeLine, KillRing.Direction.FORWARD)));
        host.registry().register(Command.of("edit.zapToChar", host.editing()::zapToChar));
        host.registry().register(Command.of("edit.killSexp", host.editing()::killSexp));
        host.registry().register(Command.of("edit.markSexp", host.editing()::markSexp));
        host.registry().register(Command.of("edit.markParagraph", host.editing()::markParagraph));
        host.registry().register(Command.of("edit.expandSelection", host.editing()::expandSelection));
        host.registry().register(Command.of("edit.shrinkSelection", host.editing()::shrinkSelection));
        host.registry()
                .register(Command.of(
                        "nav.forwardSexp", () -> host.editing().sexpMove(com.editora.editops.SexpNav::forward)));
        host.registry()
                .register(Command.of(
                        "nav.backwardSexp", () -> host.editing().sexpMove(com.editora.editops.SexpNav::backward)));
        host.registry().register(Command.of("nav.matchingBracket", host.editing()::jumpToMatchingBracket));
        host.registry().register(Command.of("edit.selectToBracket", host.editing()::selectToBracket));
        host.registry().register(Command.of("nav.back", host.navigation()::navBack));
        host.registry().register(Command.of("nav.forward", host.navigation()::navForward));
        host.registry().register(Command.of("nav.recentLocations", host.navigation()::showRecentLocations));
        host.registry().register(Command.of("nav.relatedFile", host.navigation()::gotoRelatedFile));
        host.registry()
                .register(Command.of(
                        "nav.beginningOfDefun",
                        () -> host.editing().sexpMove(com.editora.editops.SexpNav::beginningOfDefun)));
        host.registry()
                .register(Command.of(
                        "nav.endOfDefun", () -> host.editing().sexpMove(com.editora.editops.SexpNav::endOfDefun)));
        host.registry().register(Command.of("nav.moveToWindowLine", host.editing()::moveToWindowLine));
    }
}
