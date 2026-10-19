package com.editora.ui;

import java.util.List;

import javafx.geometry.Orientation;
import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;

/**
 * What {@link WindowCommandRegistrar} needs from a window: the adapter between the command table and
 * {@link MainController}, which owns the state. It holds no state of its own and resolves everything when
 * called. It lived inside {@code MainController} as an anonymous class; it is a file of its own only because
 * of that file's size.
 */
final class WindowCommandHost implements WindowCommandRegistrar.Host {

    private final MainController mc;

    WindowCommandHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public WindowChromeCoordinator chrome() {
        return mc.chrome;
    }

    @Override
    public GitWindowCoordinator gitWindows() {
        return mc.gitWindows;
    }

    @Override
    public NavigationCoordinator navigation() {
        return mc.navigation;
    }

    @Override
    public PreviewCoordinator previews() {
        return mc.previews;
    }

    @Override
    public Stage stage() {
        return mc.stage;
    }

    @Override
    public ConfigManager config() {
        return mc.config;
    }

    @Override
    public CommandRegistry registry() {
        return mc.registry;
    }

    @Override
    public MacroCoordinator macroCoordinator() {
        return mc.macroCoordinator;
    }

    @Override
    public StatusBar statusBar() {
        return mc.statusBar;
    }

    @Override
    public SettingsWindow settingsWindow() {
        return mc.settingsWindow;
    }

    @Override
    public com.editora.snippet.SnippetManager snippets() {
        return mc.snippets;
    }

    @Override
    public PluginCoordinator pluginCoordinator() {
        return mc.pluginCoordinator;
    }

    @Override
    public ProjectPanel projectPanel() {
        return mc.projectPanel;
    }

    @Override
    public WindowManager windowManager() {
        return mc.windowManager;
    }

    @Override
    public QuickOpen<Project> projectPicker() {
        return mc.projectPicker;
    }

    @Override
    public ToolWindowManager toolWindows() {
        return mc.toolWindows;
    }

    @Override
    public ToolWindow projectToolWindow() {
        return mc.projectToolWindow;
    }

    @Override
    public ToolWindow structureToolWindow() {
        return mc.structureToolWindow;
    }

    @Override
    public ToolWindow bookmarksToolWindow() {
        return mc.bookmarksToolWindow;
    }

    @Override
    public ToolWindow notesToolWindow() {
        return mc.notesToolWindow;
    }

    @Override
    public ToolWindow fileInfoToolWindow() {
        return mc.fileInfoToolWindow;
    }

    @Override
    public ToolWindow undoHistoryToolWindow() {
        return mc.undoHistoryToolWindow;
    }

    @Override
    public ToolWindow fileHistoryToolWindow() {
        return mc.fileHistoryToolWindow;
    }

    @Override
    public BookmarkCoordinator bookmarkCoordinator() {
        return mc.bookmarkCoordinator;
    }

    @Override
    public ToolWindow searchToolWindow() {
        return mc.searchToolWindow;
    }

    @Override
    public ToolWindow markdownLintToolWindow() {
        return mc.markdownLintToolWindow;
    }

    @Override
    public RemoteCoordinator remoteCoordinator() {
        return mc.remoteCoordinator;
    }

    @Override
    public ContainerCoordinator containerCoordinator() {
        return mc.containerCoordinator;
    }

    @Override
    public ToolWindow problemsToolWindow() {
        return mc.problemsToolWindow;
    }

    @Override
    public ToolWindow referencesToolWindow() {
        return mc.referencesToolWindow;
    }

    @Override
    public ToolWindow hierarchyToolWindow() {
        return mc.hierarchyToolWindow;
    }

    @Override
    public ToolWindow runToolWindow() {
        return mc.runToolWindow;
    }

    @Override
    public ToolWindow externalToolToolWindow() {
        return mc.externalToolToolWindow;
    }

    @Override
    public ToolWindow buildOutputToolWindow() {
        return mc.buildOutputToolWindow;
    }

    @Override
    public ToolWindow testResultsToolWindow() {
        return mc.testResultsToolWindow;
    }

    @Override
    public ToolWindow remoteToolWindow() {
        return mc.remoteToolWindow;
    }

    @Override
    public com.editora.dap.DapManager dapManager() {
        return mc.dapManager;
    }

    @Override
    public ToolWindow debugToolWindow() {
        return mc.debugToolWindow;
    }

    @Override
    public DebugCoordinator debugCoordinator() {
        return mc.debugCoordinator;
    }

    @Override
    public InstallCoordinator installCoordinator() {
        return mc.installCoordinator;
    }

    @Override
    public ToolWindow commitToolWindow() {
        return mc.commitToolWindow;
    }

    @Override
    public GitLogPanel.Actions gitLogOps() {
        return mc.gitLogOps;
    }

    @Override
    public GitHubPanel githubPanel() {
        return mc.githubPanel();
    }

    @Override
    public ToolWindow githubToolWindow() {
        return mc.githubToolWindow;
    }

    @Override
    public HistoryCoordinator historyCoordinator() {
        return mc.historyCoordinator;
    }

    @Override
    public ToolbarCoordinator toolbarCoordinator() {
        return mc.toolbarCoordinator;
    }

    @Override
    public Switcher switcher() {
        return mc.switcher;
    }

    @Override
    public void checkForUpdatesNow() {
        mc.updates.checkNow();
    }

    @Override
    public void openUpdateDownloadPage() {
        mc.updates.openDownloadPage();
    }

    @Override
    public void showDoctor() {
        mc.showDoctor();
    }

    @Override
    public void showWelcome() {
        mc.showWelcome();
    }

    @Override
    public boolean projectsEnabled() {
        return mc.projectsEnabled();
    }

    @Override
    public void applyProjectSupport() {
        mc.applyProjectSupport();
    }

    @Override
    public void closeProject() {
        mc.closeProject();
    }

    @Override
    public void deleteProject() {
        mc.deleteProject();
    }

    @Override
    public void deleteProject(Project p) {
        mc.deleteProject(p);
    }

    @Override
    public void startAceJump() {
        mc.startAceJump();
    }

    @Override
    public void startAceJumpLine() {
        mc.startAceJumpLine();
    }

    @Override
    public void applyMathSupport() {
        mc.applyMathSupport();
    }

    @Override
    public EditingCoordinator editing() {
        return mc.editing;
    }

    @Override
    public TemplateCoordinator templateActions() {
        return mc.templateActions;
    }

    @Override
    public EditorSettingsCoordinator editorSettings() {
        return mc.editorSettings;
    }

    @Override
    public RunConfigurationCoordinator runConfigurations() {
        return mc.runConfigurations;
    }

    @Override
    public DeploymentCoordinator deployments() {
        return mc.deployments;
    }

    @Override
    public GitCoordinator git() {
        return mc.git;
    }

    @Override
    public DiffCoordinator diffCoordinator() {
        return mc.diffCoordinator;
    }

    @Override
    public GitHubCoordinator github() {
        return mc.github;
    }

    @Override
    public MermaidCoordinator mermaid() {
        return mc.mermaid;
    }

    @Override
    public DiagramCoordinator diagram() {
        return mc.diagram;
    }

    @Override
    public TypstCoordinator typst() {
        return mc.typst;
    }

    @Override
    public ExportCoordinator exports() {
        return mc.exports;
    }

    @Override
    public HtmlPreviewCoordinator htmlPreview() {
        return mc.htmlPreview;
    }

    @Override
    public LogViewerCoordinator logViewer() {
        return mc.logViewer;
    }

    @Override
    public MavenProjectCoordinator mavenProjectCoordinator() {
        return mc.mavenProjectCoordinator;
    }

    @Override
    public ExternalToolCoordinator externalToolCoordinator() {
        return mc.externalToolCoordinator;
    }

    @Override
    public List<BuildCoordinator> buildCoordinators() {
        return mc.buildCoordinators;
    }

    @Override
    public void refreshBuildTools() {
        mc.refreshBuildTools();
    }

    @Override
    public IndexCoordinator indexCoordinator() {
        return mc.indexCoordinator;
    }

    @Override
    public TodoCoordinator todoCoordinator() {
        return mc.todoCoordinator;
    }

    @Override
    public TerminalCoordinator terminalCoordinator() {
        return mc.terminalCoordinator;
    }

    @Override
    public CsvCoordinator csvCoordinator() {
        return mc.csvCoordinator;
    }

    @Override
    public SearchCoordinator searchCoordinator() {
        return mc.searchCoordinator;
    }

    @Override
    public RunCoordinator runCoordinator() {
        return mc.runCoordinator;
    }

    @Override
    public TestRunCoordinator testRunCoordinator() {
        return mc.testRunCoordinator;
    }

    @Override
    public LspCoordinator lspCoordinator() {
        return mc.lspCoordinator;
    }

    @Override
    public NotesCoordinator notesCoordinator() {
        return mc.notesCoordinator;
    }

    @Override
    public HttpClientCoordinator httpClient() {
        return mc.httpClient;
    }

    @Override
    public AgentCoordinator agentCoordinator() {
        return mc.agentCoordinator;
    }

    @Override
    public void applyAgentSupport() {
        mc.applyAgentSupport();
    }

    @Override
    public AiCoordinator aiCoordinator() {
        return mc.aiCoordinator;
    }

    @Override
    public void showTrustedFolders() {
        mc.showTrustedFolders();
    }

    @Override
    public void revokeTrustForActiveRoot() {
        mc.revokeTrustForActiveRoot();
    }

    @Override
    public void ifMcp(Runnable action) {
        mc.ifMcp(action);
    }

    @Override
    public void toggleMcpSupport() {
        mc.toggleMcpSupport();
    }

    @Override
    public void copyMcpEndpoint() {
        mc.copyMcpEndpoint();
    }

    @Override
    public void ifLsp(Runnable action) {
        mc.ifLsp(action);
    }

    @Override
    public void toggleLsp() {
        mc.toggleLsp();
    }

    @Override
    public void runTestsForContext() {
        mc.testNavigation.runTestsForContext();
    }

    @Override
    public void runTestAtCaret(boolean classLevel, boolean debug) {
        mc.testNavigation.runTestAtCaret(classLevel, debug);
    }

    @Override
    public void applyTestRunner() {
        mc.testNavigation.applyTestRunner();
    }

    @Override
    public void findNextMatch() {
        mc.findNextMatch();
    }

    @Override
    public void findPreviousMatch() {
        mc.findPreviousMatch();
    }

    @Override
    public void findReplaceCurrentMatch() {
        mc.findReplaceCurrentMatch();
    }

    @Override
    public void findReplaceAllMatches() {
        mc.findReplaceAllMatches();
    }

    @Override
    public void openDocumentation() {
        mc.openDocumentation();
    }

    @Override
    public void onSplitVertical() {
        mc.onSplitVertical();
    }

    @Override
    public void onSplitHorizontal() {
        mc.onSplitHorizontal();
    }

    @Override
    public void unsplit() {
        mc.unsplit();
    }

    @Override
    public void splitEditorGroup(Orientation orientation) {
        mc.splitEditorGroup(orientation);
    }

    @Override
    public void moveTabToNextGroup() {
        mc.moveTabToNextGroup();
    }

    @Override
    public void focusNextEditorGroup() {
        mc.focusNextEditorGroup();
    }

    @Override
    public void unsplitEditorGroups() {
        mc.unsplitEditorGroups();
    }

    @Override
    public void setStatus(String message) {
        mc.setStatus(message);
    }

    @Override
    public EditorBuffer activeBuffer() {
        return mc.activeBuffer();
    }

    @Override
    public void onNew() {
        mc.onNew();
    }

    @Override
    public void onOpen() {
        mc.onOpen();
    }

    @Override
    public void openActiveAsText() {
        mc.openActiveAsText();
    }

    @Override
    public void openActiveAsHex() {
        mc.fileWorkflows.openActiveAsHex();
    }

    @Override
    public void onClearRecent() {
        mc.onClearRecent();
    }

    @Override
    public void onSave() {
        mc.onSave();
    }

    @Override
    public void onSaveAsAdmin() {
        mc.fileWorkflows.onSaveAsAdmin();
    }

    @Override
    public void applyAdminSaveSupport() {
        mc.fileWorkflows.applyAdminSaveSupport();
    }

    @Override
    public void saveAsPrompt(EditorBuffer buffer) {
        mc.fileWorkflows.saveAsPrompt(buffer);
    }

    @Override
    public void applyAutoSave() {
        mc.fileWorkflows.applyAutoSave();
    }

    @Override
    public EncodingCommands encodings() {
        return mc.fileWorkflows.encodings();
    }

    @Override
    public EncryptionCoordinator encryption() {
        return mc.fileWorkflows.encryption();
    }

    @Override
    public RegisterCommands registers() {
        return mc.registers;
    }

    @Override
    public void saveAll() {
        mc.fileWorkflows.saveAll();
    }

    @Override
    public void revertFile() {
        mc.fileWorkflows.revert();
    }

    @Override
    public void reopenClosedTab() {
        mc.reopenClosedTab();
    }

    @Override
    public void toggleAutoSave() {
        mc.fileWorkflows.toggleAutoSave();
    }

    @Override
    public void onCloseTab() {
        mc.onCloseTab();
    }

    @Override
    public Tab activeTab() {
        return mc.activeTab();
    }

    @Override
    public void closeOtherTabs(Tab keep) {
        mc.closeOtherTabs(keep);
    }

    @Override
    public void closeAllTabs() {
        mc.closeAllTabs();
    }

    @Override
    public void closeUnmodifiedTabs() {
        mc.closeUnmodifiedTabs();
    }

    @Override
    public void closeTabsToLeft(Tab pivot) {
        mc.closeTabsToLeft(pivot);
    }

    @Override
    public void closeTabsToRight(Tab pivot) {
        mc.closeTabsToRight(pivot);
    }

    @Override
    public void copyPath(EditorBuffer buffer) {
        mc.copyPath(buffer);
    }

    @Override
    public void revealActiveBuffer() {
        mc.revealActiveBuffer();
    }

    @Override
    public void openTerminalForActiveBuffer() {
        mc.openTerminalForActiveBuffer();
    }

    @Override
    public void togglePin(Tab tab) {
        mc.togglePin(tab);
    }

    @Override
    public void renameFile(EditorBuffer buffer, Tab tab) {
        mc.renameFile(buffer, tab);
    }

    @Override
    public boolean onQuit() {
        return mc.onQuit();
    }

    @Override
    public void requestSave() {
        mc.requestSave();
    }

    @Override
    public void nextBuffer() {
        mc.nextBuffer();
    }

    @Override
    public void findShowOrNext() {
        mc.findShowOrNext();
    }

    @Override
    public void findShowOrPrevious() {
        mc.findShowOrPrevious();
    }

    @Override
    public void showReplace() {
        mc.showReplace();
    }

    @Override
    public void updateBufferToolWindows() {
        mc.updateBufferToolWindows();
    }

    @Override
    public void maybeOfferInstall(EditorBuffer buffer) {
        mc.installPrompts.maybeOfferInstall(buffer);
    }

    @Override
    public void onPalette() {
        mc.onPalette();
    }

    @Override
    public void onSettings() {
        mc.onSettings();
    }

    @Override
    public void onAbout() {
        mc.onAbout();
    }

    @Override
    public void showSplitToolWindowPalette() {
        mc.showSplitToolWindowPalette();
    }

    @Override
    public void toggleFloatingToolWindow() {
        mc.toggleFloatingToolWindow();
    }

    @Override
    public void toggleMaximizedToolWindow() {
        mc.toggleMaximizedToolWindow();
    }

    @Override
    public void toggleToolStripe() {
        mc.toggleToolStripe();
    }

    @Override
    public void withMultiCaret(java.util.function.Consumer<EditorBuffer> action) {
        mc.withMultiCaret(action);
    }

    @Override
    public void selectAllOccurrences() {
        mc.selectAllOccurrences();
    }

    @Override
    public void selectAllFindMatches() {
        mc.selectAllFindMatches();
    }

    @Override
    public void chooseInstallServer() {
        mc.installPrompts.chooseInstallServer();
    }

    @Override
    public void toggleReadOnly() {
        mc.toggleReadOnly();
    }

    @Override
    public void textZoom(int direction) {
        mc.textZoom(direction);
    }

    @Override
    public SnippetCoordinator snippetCoordinator() {
        return mc.snippetCoordinator;
    }

    @Override
    public void editProjectSettings() {
        mc.editProjectSettings();
    }

    @Override
    public void showDebugLog() {
        mc.showDebugLog();
    }

    @Override
    public void exportConfig() {
        mc.exportConfig();
    }

    @Override
    public void importConfig() {
        if (mc.windowManager != null) {
            // The last dialog's "Restart now" quits and relaunches the way Settings' own Restart Now does.
            ConfigImportFlow.restart = () -> mc.registry.run("app.restart");
            new ConfigImportFlow(mc.stage, mc.config.shared(), mc.windowManager, mc::setStatus, mc.backgroundTasks())
                    .start();
        }
    }

    @Override
    public void cancel() {
        mc.cancel();
    }

    @Override
    public SelectionPolicy selPolicy() {
        return mc.selPolicy();
    }
}
