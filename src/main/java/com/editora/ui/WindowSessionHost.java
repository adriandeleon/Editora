package com.editora.ui;

import java.nio.file.Path;
import java.util.Set;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link WindowSessionCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class WindowSessionHost implements WindowSessionCoordinator.Host {

    private final MainController mc;

    WindowSessionHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public @Nullable Project windowProject() {
        return mc.windowProject;
    }

    @Override
    public boolean isOpen(Path file) {
        return mc.tabForPath(file) != null;
    }

    @Override
    public boolean hasFileTabs() {
        return mc.editorArea.tabs().stream().anyMatch(tab -> mc.tabPath(tab) != null);
    }

    @Override
    public TabPane tabPane() {
        return mc.tabPane;
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
    public void setProjectRoot(@Nullable Path root) {
        mc.setProjectRoot(root);
    }

    @Override
    public ProjectManager projects() {
        return mc.projects;
    }

    @Override
    public ToolWindowManager toolWindows() {
        return mc.toolWindows;
    }

    @Override
    public BookmarkCoordinator bookmarkCoordinator() {
        return mc.bookmarkCoordinator;
    }

    @Override
    public DebugCoordinator debugCoordinator() {
        return mc.debugCoordinator;
    }

    @Override
    public Set<Tab> pinned() {
        return mc.pinned;
    }

    @Override
    public void renderViewerPin(Tab tab) {
        mc.renderViewerPin(tab);
    }

    @Override
    public void showWelcomeIfNoTabs() {
        mc.showWelcomeIfNoTabs();
    }

    @Override
    public void refreshProjectPanelList() {
        mc.refreshProjectPanelList();
    }

    @Override
    public boolean projectsEnabled() {
        return mc.projectsEnabled();
    }

    @Override
    public void updateWindowTitle() {
        mc.updateWindowTitle();
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
    public WindowChromeCoordinator chrome() {
        return mc.chrome;
    }

    @Override
    public FileWorkflowCoordinator fileWorkflows() {
        return mc.fileWorkflows;
    }

    @Override
    public DiffCoordinator diffCoordinator() {
        return mc.diffCoordinator;
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
    public void openBackgroundBuffer(Path target) {
        mc.openBackgroundBufferAsync(target, opened -> {});
    }

    @Override
    public void setStatus(String message) {
        mc.setStatus(message);
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
    public void updateTabMeta(Tab tab, EditorBuffer buffer) {
        mc.updateTabMeta(tab, buffer);
    }

    @Override
    public void clearLoading(EditorBuffer buffer) {
        mc.clearLoading(buffer);
    }

    @Override
    public void discardLoading(EditorBuffer buffer) {
        mc.discardLoading(buffer);
    }

    @Override
    public Tab addPlaceholderTab(RestorePlaceholder placeholder) {
        return mc.addPlaceholderTab(placeholder);
    }

    @Override
    public Tab attachRestoredBuffer(Tab tab, EditorBuffer buffer, boolean shown) {
        return mc.attachRestoredBuffer(tab, buffer, shown);
    }

    @Override
    public @Nullable EditorBuffer bufferOf(@Nullable Tab tab) {
        return mc.bufferOf(tab);
    }

    @Override
    public @Nullable Tab tabForPath(@Nullable Path file) {
        return mc.tabForPath(file);
    }

    @Override
    public @Nullable Path tabPath(Tab tab) {
        return mc.tabPath(tab);
    }

    @Override
    public @Nullable Path windowProjectRoot() {
        return mc.windowProjectRoot();
    }

    @Override
    public void restorePerFileState(EditorBuffer buffer) {
        mc.restorePerFileState(buffer);
    }

    @Override
    public @Nullable Tab tabForBuffer(EditorBuffer buffer) {
        return mc.tabForBuffer(buffer);
    }

    @Override
    public void refreshStatusBar() {
        mc.statusBar.refresh();
    }
}
