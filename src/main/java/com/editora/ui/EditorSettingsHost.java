package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link EditorSettingsCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class EditorSettingsHost implements EditorSettingsCoordinator.Host {

    private final MainController mc;

    EditorSettingsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public TestNavigationCoordinator testNavigation() {
        return mc.testNavigation;
    }

    @Override
    public FileWorkflowCoordinator fileWorkflows() {
        return mc.fileWorkflows;
    }

    @Override
    public WindowChromeCoordinator chrome() {
        return mc.chrome;
    }

    @Override
    public PreviewCoordinator previews() {
        return mc.previews;
    }

    @Override
    public EditorArea editorArea() {
        return mc.editorArea;
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
    public KeymapManager keymap() {
        return mc.keymap;
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
    public OverlayHost overlayHost() {
        return mc.overlayHost;
    }

    @Override
    public @Nullable WindowManager windowManager() {
        return mc.windowManager;
    }

    @Override
    public BuildOutputPanel buildOutputPanel() {
        return mc.buildOutputPanel;
    }

    @Override
    public DebugCoordinator debugCoordinator() {
        return mc.debugCoordinator;
    }

    @Override
    public HistoryCoordinator historyCoordinator() {
        return mc.historyCoordinator;
    }

    @Override
    public WelcomePane welcomePane() {
        return mc.welcomePane;
    }

    @Override
    public @Nullable Tab welcomeTab() {
        return mc.welcomeTab;
    }

    @Override
    public @Nullable Tab doctorTab() {
        return mc.doctorTab;
    }

    @Override
    public void applyProjectSupport() {
        mc.applyProjectSupport();
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
    public CoordinatorHost coordinatorHost() {
        return mc.coordinatorHost;
    }

    @Override
    public GitCoordinator git() {
        return mc.git;
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
    public HtmlPreviewCoordinator htmlPreview() {
        return mc.htmlPreview;
    }

    @Override
    public LogViewerCoordinator logViewer() {
        return mc.logViewer;
    }

    @Override
    public ExternalToolCoordinator externalToolCoordinator() {
        return mc.externalToolCoordinator;
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
    public void applyAgentSupport() {
        mc.applyAgentSupport();
    }

    @Override
    public AiCoordinator aiCoordinator() {
        return mc.aiCoordinator;
    }

    @Override
    public DoctorCoordinator doctorCoordinator() {
        return mc.doctorCoordinator;
    }

    @Override
    public void applyMcpSupport() {
        mc.applyMcpSupport();
    }

    @Override
    public void applyMainGutter(EditorBuffer buffer) {
        mc.testNavigation.applyMainGutter(buffer);
    }

    @Override
    public java.util.Map<String, String> invertBindings() {
        return mc.invertBindings();
    }

    @Override
    public void setStatus(String message) {
        mc.setStatus(message);
    }

    @Override
    public @Nullable EditorBuffer activeBuffer() {
        return mc.activeBuffer();
    }

    @Override
    public void openPath(Path file) {
        mc.fileWorkflows.openPath(file);
    }

    @Override
    public void openPath(Path file, boolean quietIfOpen) {
        mc.fileWorkflows.openPath(file, quietIfOpen);
    }

    @Override
    public @Nullable EditorBuffer bufferOf(Tab tab) {
        return mc.bufferOf(tab);
    }

    @Override
    public void requestSave() {
        mc.requestSave();
    }

    @Override
    public void promptText(String title, String label, @Nullable String initial, Consumer<String> onAccept) {
        mc.promptText(title, label, initial, onAccept);
    }

    @Override
    public void refreshSpellAllTabs() {
        mc.refreshSpellAllTabs();
    }

    @Override
    public void applyEditorTheme(String themeName) {
        mc.applyEditorTheme(themeName);
    }
}
