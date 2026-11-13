package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;

import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link PreviewCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class PreviewHost implements PreviewCoordinator.Host {

    private final MainController mc;

    PreviewHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public EditorArea editorArea() {
        return mc.editorArea;
    }

    @Override
    public ConfigManager config() {
        return mc.config;
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
    public ToolWindowManager toolWindows() {
        return mc.toolWindows;
    }

    @Override
    public MarkdownLintPanel markdownLintPanel() {
        return mc.markdownLintPanel();
    }

    @Override
    public void openExternalUrl(String url) {
        mc.openExternalUrl(url);
    }

    @Override
    public @Nullable Path activeProjectRoot() {
        return mc.windowProjectRoot();
    }

    @Override
    public StatusBar statusBar() {
        return mc.statusBar;
    }

    @Override
    public ToolWindow markdownLintToolWindow() {
        return mc.markdownLintToolWindow;
    }

    @Override
    public EditingCoordinator editing() {
        return mc.editing;
    }

    @Override
    public EditorSettingsCoordinator editorSettings() {
        return mc.editorSettings;
    }

    @Override
    public MermaidCoordinator mermaid() {
        return mc.mermaid;
    }

    @Override
    public ExportCoordinator exports() {
        return mc.exports;
    }

    @Override
    public CsvCoordinator csvCoordinator() {
        return mc.csvCoordinator;
    }

    @Override
    public HttpClientCoordinator httpClient() {
        return mc.httpClient;
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
    public @Nullable EditorBuffer bufferOf(@Nullable Tab tab) {
        return mc.bufferOf(tab);
    }

    @Override
    public void requestSave() {
        mc.requestSave();
    }

    @Override
    public boolean appThemeDark() {
        return mc.appThemeDark();
    }

    @Override
    public Tab addContentTab(com.editora.editor.TabContent content) {
        return mc.addContentTab(content, true);
    }

    @Override
    public void installContentTabMenu(Tab tab, javafx.scene.control.MenuItem... own) {
        mc.installContentTabMenu(tab, own);
    }

    @Override
    public void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
        mc.promptText(title, label, initial, onAccept);
    }
}
