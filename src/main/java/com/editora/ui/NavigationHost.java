package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.geometry.Orientation;
import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.RecentFiles;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * What {@link NavigationCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class NavigationHost implements NavigationCoordinator.Host {

    private final MainController mc;

    NavigationHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public WindowSessionCoordinator sessions() {
        return mc.sessions;
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
    public EditorArea editorArea() {
        return mc.editorArea;
    }

    @Override
    public Stage stage() {
        return mc.stage;
    }

    @Override
    public KeymapManager keymap() {
        return mc.keymap;
    }

    @Override
    public OverlayHost overlayHost() {
        return mc.overlayHost;
    }

    @Override
    public com.editora.snippet.SnippetManager snippets() {
        return mc.snippets;
    }

    @Override
    public ProjectManager projects() {
        return mc.projects;
    }

    @Override
    public @Nullable Project windowProject() {
        return mc.enabledWindowProject();
    }

    @Override
    public ToolWindowManager toolWindows() {
        return mc.toolWindows;
    }

    @Override
    public StructurePanel structurePanel() {
        return mc.structurePanel(); // the structure palette reads the outline: build it
    }

    @Override
    public RecentFiles recentFiles() {
        return mc.recentFiles;
    }

    @Override
    public boolean projectsEnabled() {
        return mc.projectsEnabled();
    }

    @Override
    public void navigateToLine(int line) {
        mc.navigateToLine(line);
    }

    @Override
    public void activateAndFocusTab(Tab tab) {
        mc.activateAndFocusTab(tab);
    }

    @Override
    public List<Tab> openTabsForSwitcher() {
        return mc.openTabsForSwitcher();
    }

    @Override
    public String homeCollapsed(String full) {
        return com.editora.config.PathDisplay.collapseHome(full);
    }

    @Override
    public EditingCoordinator editing() {
        return mc.editing;
    }

    @Override
    public GitCoordinator git() {
        return mc.git;
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
    public void openAndGoto(@Nullable Path file, int line0, int col0) {
        mc.openAndGoto(file, line0, col0);
    }

    @Override
    public java.util.Map<String, String> invertBindings() {
        return mc.invertBindings();
    }

    @Override
    public void splitEditorGroup(Orientation orientation) {
        mc.splitEditorGroup(orientation);
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
    public java.util.List<com.editora.editor.undo.UndoHistory.Checkpoint> undoHistoryCheckpoints() {
        return mc.undoHistoryCheckpoints();
    }

    @Override
    public void restoreUndoCheckpoint(com.editora.editor.undo.UndoHistory.Checkpoint c) {
        mc.restoreUndoCheckpoint(c);
    }

    @Override
    public @Nullable CodeArea activeArea() {
        return mc.activeArea();
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
    public void openRecent(Path file) {
        mc.openRecent(file);
    }

    @Override
    public void openPathPreview(@Nullable Path file) {
        if (file != null) {
            mc.openPathPreview(file);
        }
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
    public void persistFolds(EditorBuffer buffer) {
        mc.persistFolds(buffer);
    }
}
