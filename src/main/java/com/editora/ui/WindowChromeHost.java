package com.editora.ui;

import java.util.List;

import javafx.css.PseudoClass;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * What {@link WindowChromeCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class WindowChromeHost implements WindowChromeCoordinator.Host {

    private final MainController mc;

    WindowChromeHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public PseudoClass OPEN() {
        return MainController.OPEN;
    }

    @Override
    public BorderPane root() {
        return mc.root;
    }

    @Override
    public EditorArea editorArea() {
        return mc.editorArea;
    }

    @Override
    public MainMenuBar menuBar() {
        return mc.menuBar;
    }

    @Override
    public ToolBar toolBar() {
        return mc.toolBar;
    }

    @Override
    public HBox toolbarRow() {
        return mc.toolbarRow;
    }

    @Override
    public Button newFromTemplateButton() {
        return mc.newFromTemplateButton;
    }

    @Override
    public Button findInFilesButton() {
        return mc.findInFilesButton;
    }

    @Override
    public Button splitVerticalButton() {
        return mc.splitVerticalButton;
    }

    @Override
    public Button splitHorizontalButton() {
        return mc.splitHorizontalButton;
    }

    @Override
    public Button simpleModeButton() {
        return mc.simpleModeButton;
    }

    @Override
    public MenuButton recentButton() {
        return mc.recentButton;
    }

    @Override
    public Button clearRecentButton() {
        return mc.clearRecentButton;
    }

    @Override
    public ConfigManager config() {
        return mc.config;
    }

    @Override
    public CommandPalette palette() {
        return mc.palette;
    }

    @Override
    public StatusBar statusBar() {
        return mc.statusBar;
    }

    @Override
    public @Nullable StructurePanel structurePanelIfBuilt() {
        return mc.structurePanel;
    }

    @Override
    public FileBreadcrumb breadcrumb() {
        return mc.breadcrumb;
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
    public QuickOpen<Project> projectPicker() {
        return mc.projectPicker;
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
    public Switcher switcher() {
        return mc.switcher;
    }

    @Override
    public void openExternalUrl(String url) {
        mc.openExternalUrl(url);
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
    public NavigationCoordinator navigation() {
        return mc.navigation;
    }

    @Override
    public GitWindowCoordinator gitWindows() {
        return mc.gitWindows;
    }

    @Override
    public List<BuildCoordinator> buildCoordinators() {
        return mc.buildCoordinators;
    }

    @Override
    public SearchEverywherePopup.Ops searchEverywhereOps() {
        return mc.searchEverywhereOps;
    }

    @Override
    public IndexCoordinator indexCoordinator() {
        return mc.indexCoordinator;
    }

    @Override
    public NotesCoordinator notesCoordinator() {
        return mc.notesCoordinator;
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
    public void requestSave() {
        mc.requestSave();
    }

    @Override
    public void setZenMode(boolean on) {
        mc.setZenMode(on);
    }

    @Override
    public void setExpertMode(boolean on) {
        mc.setExpertMode(on);
    }
}
