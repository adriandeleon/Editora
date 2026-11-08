package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link LspCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class LspOpsHost implements LspCoordinator.Ops {

    private final MainController mc;

    LspOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void executeCommand(String id) {
        mc.executeCommand(id);
    }

    @Override
    public String homeCollapsed(String absolutePath) {
        return com.editora.config.PathDisplay.collapseHome(absolutePath);
    }

    @Override
    public void openAndGoto(@Nullable Path file, int line0, int col0) {
        mc.openAndGoto(file, line0, col0);
    }

    @Override
    public EditorBuffer openReadOnlyDoc(String title, String content, String language) {
        // The openTechnicalDictionary pattern: an in-memory, read-only, path-less buffer (#665).
        EditorBuffer buffer = new EditorBuffer();
        buffer.setDisplayName(title);
        buffer.setContent(content);
        buffer.setLanguageOverride(language);
        buffer.setViewMode(true); // read-only library source; set first, or the status bar says "Editable" (B16)
        mc.addBuffer(buffer, true);
        return buffer;
    }

    @Override
    public boolean selectBufferTab(EditorBuffer buffer) {
        for (Tab tab : mc.editorArea.tabs()) {
            if (MainController.bufferOf(tab) == buffer) {
                mc.editorArea.select(tab);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean activeEditable() {
        return mc.editing.activeEditable();
    }

    @Override
    public boolean lspFeatureEnabled() {
        return mc.lspEnabled();
    }

    @Override
    public void setLspLoading(boolean loading) {
        mc.statusBar.setLspLoading(loading);
    }

    @Override
    public @Nullable EditorBuffer bufferForPath(@Nullable Path file) {
        return MainController.bufferOf(mc.tabForPath(file));
    }

    @Override
    public List<EditorBuffer> buffersAtOrUnder(Path path) {
        return mc.windowManager == null ? mc.buffersAtOrUnderLocal(path) : mc.windowManager.buffersAtOrUnder(path);
    }

    @Override
    public @Nullable EditorBuffer openBackgroundBuffer(Path file) {
        return mc.openBackgroundBuffer(file);
    }

    @Override
    public void openBackgroundBufferAsync(Path file, java.util.function.Consumer<@Nullable EditorBuffer> done) {
        mc.openBackgroundBufferAsync(file, done);
    }

    @Override
    public boolean saveBuffer(EditorBuffer buffer) {
        return mc.fileWorkflows.saveSynchronously(buffer);
    }

    @Override
    public void closeBackgroundBuffer(EditorBuffer buffer) {
        Tab tab = mc.tabFor(buffer);
        if (tab != null && !buffer.isDirty()) {
            mc.editorArea.remove(tab);
        }
    }

    @Override
    public void fileRenamed(@Nullable Path from, Path to) {
        if (from != null) {
            mc.onProjectFileRenamed(from, to, true); // old URI was closed by the workspace transaction
        }
        if (mc.projectPanel != null) {
            mc.projectPanel.refreshTree();
        }
    }

    @Override
    public void fileCreated(Path file) {
        if (mc.projectPanel != null) {
            mc.projectPanel.refreshTree();
        }
    }

    @Override
    public void fileDeleted(@Nullable Path file) {
        if (file != null) {
            mc.onProjectFileDeleted(file);
        }
        if (mc.projectPanel != null) {
            mc.projectPanel.refreshTree();
        }
    }

    @Override
    public void invalidatePendingWrite(Path file) {
        mc.fileWorkflows.invalidatePendingWrite(file);
    }

    @Override
    public void captureBeforeDestruction(Path file, java.util.function.Consumer<Boolean> completion) {
        mc.historyCoordinator.captureBeforeDeleteDurably(file, capture -> completion.accept(capture.durable()));
    }

    @Override
    public void setStatusBarLsp(@Nullable String label) {
        mc.statusBar.setLsp(label);
    }

    @Override
    public void setStatusBarPython(@Nullable String text, @Nullable String tooltip) {
        mc.statusBar.setPythonInterpreter(text, tooltip);
    }

    @Override
    public void setProblemsAvailable(boolean available) {
        if (mc.problemsToolWindow != null) {
            mc.toolWindows.setAvailable(mc.problemsToolWindow, available);
        }
        if (mc.referencesToolWindow != null) {
            mc.toolWindows.setAvailable(mc.referencesToolWindow, available);
        }
        if (mc.hierarchyToolWindow != null) {
            mc.toolWindows.setAvailable(mc.hierarchyToolWindow, available); // same LSP-managed gate (#682)
        }
    }

    @Override
    public void enableNavigationWindowsByDefault() {
        if (mc.referencesToolWindow != null) {
            mc.toolWindows.setVisibleIfUnset(mc.referencesToolWindow, true);
        }
        if (mc.hierarchyToolWindow != null) {
            mc.toolWindows.setVisibleIfUnset(mc.hierarchyToolWindow, true);
        }
    }

    @Override
    public void openReferencesWindow() {
        if (mc.referencesToolWindow != null) {
            mc.toolWindows.open(mc.referencesToolWindow);
        }
    }

    @Override
    public void openHierarchyWindow() {
        if (mc.hierarchyToolWindow != null) {
            mc.toolWindows.open(mc.hierarchyToolWindow);
        }
    }

    @Override
    public @Nullable StructurePanel structurePanel() {
        return mc.structurePanel;
    }

    @Override
    public com.editora.config.ConfigManager config() {
        return mc.config;
    }

    @Override
    public @Nullable Path lspProjectRoot() {
        return mc.windowProjectRoot(); // this window's project, not the last-focused window's
    }

    @Override
    public void onDetectionSettled() {
        mc.installPrompts.maybeOfferInstall(mc.activeBuffer()); // hide/show the install banner per fresh LSP detection
    }

    @Override
    public void onServerCapabilitiesReady() {
        // A jdtls that ships java-debug (e.g. Homebrew's) advertises the debug commands here —
        // there is no local plugin jar to locate, so this is what un-gates debugging (#711).
        mc.debugCoordinator.refreshJavaDebugAvailability();
        mc.installPrompts.maybeOfferInstall(mc.activeBuffer()); // the install banner may no longer be warranted
        mc.debugCoordinator.memberMarksRefreshAll(); // the outline that places them can now be read
    }

    @Override
    public void onTypingPause(EditorBuffer buffer) {
        mc.debugCoordinator.memberMarksRefresh(buffer); // a method may have moved
    }

    @Override
    public @Nullable Path canonicalize(@Nullable Path file) {
        return file == null ? null : MainController.canonicalPath(file);
    }
}
