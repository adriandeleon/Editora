package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * The window's {@link CoordinatorHost}: the one adapter shared by the feature coordinators, resolved from
 * {@link MainController} when called. It lived inside {@code MainController} as an inner class; it is a file of
 * its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowServicesHost implements CoordinatorHost {

    private final MainController mc;

    WindowServicesHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public Path configDir() {
        return mc.config.getConfigDir();
    }

    @Override
    public long fileSize(Path file) {
        return FileWorkflowCoordinator.fileSize(file);
    }

    @Override
    public Settings settings() {
        return mc.config.getSettings();
    }

    @Override
    public ProjectScope projectScope() {
        return mc.lspCoordinator.projectScope();
    }

    @Override
    public boolean appThemeDark() {
        return mc.appThemeDark();
    }

    @Override
    public void forEachBuffer(java.util.function.Consumer<EditorBuffer> action) {
        for (Tab tab : mc.editorArea.tabs()) {
            EditorBuffer b = MainController.bufferOf(tab);
            if (b != null) {
                action.accept(b);
            }
        }
    }

    @Override
    public @Nullable EditorBuffer activeBuffer() {
        return mc.activeBuffer();
    }

    @Override
    public boolean isLocalBuffer(EditorBuffer buffer) {
        return mc.isLocalBuffer(buffer);
    }

    @Override
    public void setStatus(@Nullable String message) {
        mc.setStatus(message);
    }

    @Override
    public void setError(@Nullable String message) {
        mc.setError(message);
    }

    @Override
    public AutoCloseable startBackgroundTask(String label) {
        BackgroundTasks.Handle h = mc.backgroundTasks.start(label);
        return h::done;
    }

    @Override
    public String bufferBaseName(EditorBuffer buffer) {
        return ExportCoordinator.bufferBaseName(buffer);
    }

    @Override
    public void requestSave() {
        mc.requestSave();
    }

    @Override
    public void save() {
        mc.config.save();
    }

    @Override
    public void syncSettingsWindow() {
        if (mc.settingsWindow != null) {
            mc.settingsWindow.syncAll();
        }
    }

    @Override
    public void openExternalUrl(String url) {
        mc.openExternalUrl(url);
    }

    @Override
    public void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
        mc.promptText(title, label, initial, onAccept);
    }

    @Override
    public boolean isLspManaged(java.nio.file.Path file) {
        return mc.lspEnabled() && file != null && mc.lspManager.isManaged(file);
    }

    @Override
    public OverlayHost overlayHost() {
        return mc.overlayHost;
    }

    @Override
    public javafx.stage.Window window() {
        return mc.stage;
    }

    @Override
    public void applyAutocomplete() {
        mc.editorSettings.applyAutocomplete();
    }

    @Override
    public void ensurePreviewControls(EditorBuffer buffer) {
        mc.previews.ensurePreviewControls(buffer);
    }

    @Override
    public void restoreMarkdownMode(EditorBuffer buffer) {
        mc.previews.restoreMarkdownMode(buffer);
    }

    @Override
    public boolean simpleModeActive() {
        return mc.chrome.simpleModeActive();
    }
}
