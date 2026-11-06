package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.config.RecentFiles;
import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;
import org.jspecify.annotations.Nullable;

/**
 * What {@link FileWorkflowCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class FileWorkflowHost implements FileWorkflowCoordinator.Host {

    private final MainController mc;

    FileWorkflowHost(MainController mc) {
        this.mc = mc;
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
    public FileBreadcrumb breadcrumb() {
        return mc.breadcrumb;
    }

    @Override
    public ProjectPanel projectPanel() {
        return mc.projectPanel; // null until the Project window first opens: callers read, never build
    }

    @Override
    public @Nullable Path projectRoot() {
        return mc.projectRoot();
    }

    @Override
    public HistoryCoordinator historyCoordinator() {
        return mc.historyCoordinator;
    }

    @Override
    public RecentFiles recentFiles() {
        return mc.recentFiles;
    }

    @Override
    public void updateProjectFolderView() {
        mc.updateProjectFolderView();
    }

    @Override
    public EditorSettingsCoordinator editorSettings() {
        return mc.editorSettings;
    }

    @Override
    public PreviewCoordinator previews() {
        return mc.previews;
    }

    @Override
    public GitCoordinator git() {
        return mc.git;
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
    public void refreshBuildTools() {
        mc.refreshBuildTools();
    }

    @Override
    public IndexCoordinator indexCoordinator() {
        return mc.indexCoordinator;
    }

    @Override
    public LspCoordinator lspCoordinator() {
        return mc.lspCoordinator;
    }

    @Override
    public boolean isLocalBuffer(EditorBuffer b) {
        return mc.isLocalBuffer(b);
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
    public Tab addBuffer(EditorBuffer buffer) {
        return mc.addBuffer(buffer);
    }

    @Override
    public Tab addBuffer(EditorBuffer buffer, boolean select) {
        return mc.addBuffer(buffer, select);
    }

    @Override
    public Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings) {
        return mc.addBuffer(buffer, select, resolvePathSettings);
    }

    @Override
    public Tab addContentTab(TabContent content, boolean select) {
        return mc.addContentTab(content, select);
    }

    @Override
    public void updateTabMeta(@Nullable Tab tab, EditorBuffer buffer) {
        if (tab != null) mc.updateTabMeta(tab, buffer);
    }

    @Override
    public void bufferPathChanged(EditorBuffer buffer, @Nullable Path oldPath, boolean oldAlreadyClosed) {
        mc.lspCoordinator.documentPathChanged(buffer, oldPath, oldAlreadyClosed);
        mc.debugCoordinator.bufferPathChanged(buffer, oldPath);
        mc.bookmarkCoordinator.bufferPathChanged(buffer, oldPath); // Save As: its marks go with it
        mc.notesCoordinator.bufferPathChanged(buffer, oldPath);
        mc.historyCoordinator.bufferPathChanged(buffer, oldPath);
        RenamedFileState.copyWorkspace(mc.config.getWorkspaceState(), oldPath, buffer.getPath());
    }

    @Override
    public void promoteTab(Tab tab) {
        mc.promoteTab(tab);
    }

    @Override
    public void finishAsyncOpen(Tab tab, EditorBuffer buffer, FileWorkflowCoordinator.PreparedLoad load) {
        mc.finishAsyncOpen(tab, buffer, load);
    }

    @Override
    public void failAsyncOpen(Tab tab, EditorBuffer buffer, Path file, Exception error) {
        mc.failAsyncOpen(tab, buffer, file, error);
    }

    @Override
    public String autoSaveModeOf(String mode) {
        return mc.autoSaveModeOf(mode);
    }

    @Override
    public String autoSaveLabel(String mode) {
        return mc.autoSaveLabel(mode);
    }

    @Override
    public @Nullable EditorBuffer bufferOf(@Nullable Tab tab) {
        return mc.bufferOf(tab);
    }

    @Override
    public @Nullable EditorBuffer ensureBuffer(Tab tab) {
        return mc.ensureBuffer(tab);
    }

    @Override
    public @Nullable Tab tabFor(EditorBuffer buffer) {
        return mc.tabFor(buffer);
    }

    @Override
    public @Nullable Tab tabForPath(@Nullable Path file) {
        return mc.tabForPath(file);
    }

    @Override
    public boolean openInAnotherWindow(Path file) {
        return mc.windowManager != null && mc.windowManager.openInAnotherWindow(mc, file);
    }

    @Override
    public void fileSaved(Path file) {
        mc.snippetCoordinator.fileSaved(file);
        mc.deployments.fileSaved(file); // a deployment that uploads on save
        if (mc.windowManager != null && mc.windowManager.isSettingsFile(file)) {
            mc.setStatus(mc.windowManager.settingsFileSaved()); // a hand edit applies on save
        }
    }

    @Override
    public void sourceSaved(EditorBuffer buffer) {
        if (mc.debugCoordinator != null) {
            mc.debugCoordinator.sourceSaved(buffer); // hot code replace into a running Java session
        }
    }

    @Override
    public void editorConfigSaved() {
        if (mc.windowManager == null) {
            mc.applyEditorConfigLocal();
        } else {
            mc.windowManager.editorConfigSavedAcrossWindows();
        }
    }

    @Override
    public @Nullable Path tabPath(Tab tab) {
        return mc.tabPath(tab);
    }

    @Override
    public void requestSave() {
        mc.requestSave();
    }

    @Override
    public @Nullable Tab tabForBuffer(EditorBuffer buffer) {
        return mc.tabForBuffer(buffer);
    }

    @Override
    public void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
        mc.promptText(title, label, initial, onAccept);
    }

    @Override
    public @Nullable Path pathOf(java.io.File file) {
        return file == null ? null : file.toPath();
    }

    @Override
    public CoordinatorHost services() {
        return mc.coordinatorHost;
    }

    @Override
    public void refreshStatusBar() {
        mc.statusBar.refresh();
    }

    @Override
    public javafx.scene.Node encryptionStatusItem() {
        return mc.statusBar.encryptionNode();
    }

    @Override
    public void runCommand(String commandId) {
        mc.registry.run(commandId);
    }
}
