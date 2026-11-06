package com.editora.ui;

import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.jspecify.annotations.Nullable;

/**
 * What {@link EditingCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class EditingHost implements EditingCoordinator.Host {

    private final MainController mc;

    EditingHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public EditorSettingsCoordinator editorSettings() {
        return mc.editorSettings;
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
    public FindReplaceBar findBar() {
        return mc.findBar;
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
    public void updateWindowTitle() {
        mc.updateWindowTitle();
    }

    @Override
    public void navigateToLine(int line) {
        mc.navigateToLine(line);
    }

    @Override
    public GitCoordinator git() {
        return mc.git;
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
    public void refreshPasteState() {
        mc.toolbar.refreshPasteState();
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
    public @Nullable CodeArea activeArea() {
        return mc.activeArea();
    }

    @Override
    public void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
        mc.promptText(title, label, initial, onAccept);
    }

    @Override
    public SelectionPolicy selPolicy() {
        return mc.selPolicy();
    }
}
