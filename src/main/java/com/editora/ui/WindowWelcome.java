package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;

import com.editora.config.RecentFiles;
import com.editora.editor.ControlNames;
import com.editora.editor.LazyTooltip;
import com.editora.editor.MenuChords;

import static com.editora.i18n.Messages.tr;

/**
 * A window's Welcome and Doctor tabs, a terminal shown as an editor tab, and the toolbar's recent-files menu with
 * its remove and clear questions. The Welcome page and the tabs stay fields of {@link MainController}; this builds
 * and opens them. It lived inside {@code MainController}; it is a file of its own only because of that file's size
 * (see {@link WindowCommandHost}).
 */
final class WindowWelcome {

    private final MainController mc;

    WindowWelcome(MainController mc) {
        this.mc = mc;
    }

    private final RecentFilesCheck recentCheck = new RecentFilesCheck();

    /**
     * The {@link WelcomePane}, built and wired on its first use. The pane is shown in its own real tab
     * (see {@link #addWelcomeTab()}); the tab strip handles activation/switching/closing for free, so
     * there is no overlay or visibility juggling.
     */
    WelcomePane welcomePane() {
        if (mc.welcomePane != null) {
            return mc.welcomePane;
        }
        WelcomePane pane = new WelcomePane(
                mc.registry,
                mc.keymap,
                mc.recentFiles,
                () -> mc.config.projects().list(),
                mc::openRecent,
                mc::openExternalUrl,
                mc::projectsEnabled,
                mc.git::isEnabled,
                mc.config::getConnections, // saved SFTP sites (most-recent first); empty hides the section
                mc.remoteCoordinator::connect, // pick a site → prefilled connect form
                mc.config.isDev() ? com.editora.AppInfo.gitCommitAsync() : null); // commit: --dev only, off-thread
        pane.setProjectOpener(mc::switchToProject);
        pane.setRecentRemover(path -> Platform.runLater(() -> {
            confirmRemoveRecent(path);
            pane.refresh();
        }));
        pane.setCustomizeSource(
                () -> com.editora.command.KeymapManager.displayName(
                        mc.config.getSettings().getKeymap()),
                () -> Themes.label(mc.config.getSettings().getTheme()));
        pane.setKeymapChoice(
                () -> !mc.config.getSettings().isKeymapChosen(),
                mc.editorSettings::applyKeymap, // marks the choice before the switch lands
                () -> {
                    mc.config.getSettings().setKeymapChosen(true); // kept the one there is
                    mc.config.save();
                });
        pane.setUpdateCheckChoice(() -> !mc.config.getSettings().isUpdateCheckAsked(), on -> {
            mc.config.getSettings().chooseUpdateCheck(on);
            mc.config.save();
            if (mc.settingsWindow != null) {
                mc.settingsWindow.syncAll();
            }
            if (on) {
                mc.updates.applyAtStartup(); // the first check, now that it is allowed
            }
        });
        mc.welcomePane = pane;
        return pane;
    }

    /**
     * Opens (or re-selects) the single Welcome tab, refreshing it first so its recents + Open-Folder /
     * Clone actions track the current state. Used at startup with no session and by {@code view.welcome}.
     */
    void addWelcomeTab() {
        WelcomePane welcomePane = welcomePane();
        welcomePane.refresh();
        welcomePane.setFontScale(mc.config.getSettings().getFontZoom()); // match the current text zoom (#540)
        if (mc.welcomeTab != null && mc.editorArea.contains(mc.welcomeTab)) {
            TabFocus.select(
                    mc.editorArea, mc.welcomeTab); // its page, not the toolbar's New button (thirteenth review AK1)
            return;
        }
        Tab tab = mc.addContentTab(welcomePane, true);
        tab.getStyleClass().add("ui-surface-tab"); // the page is on the UI surface, so is its selected tab
        mc.welcomeTab = tab;
        // Welcome is not a document: its menu is the tab operations alone (no Save/Rename/Pin).
        tab.setOnClosed(e -> {
            if (mc.welcomeTab == tab) {
                mc.welcomeTab = null;
            }
        });
    }

    /**
     * Opens (or re-selects) the single Doctor tab and (re)runs its health checks — the {@code view.doctor}
     * command. Mirrors {@link #addWelcomeTab()}: one instance, Close-only context menu, field cleared on
     * close. Probes run off the FX thread; rows fill in as results land.
     */
    void showDoctor() {
        if (mc.doctorTab != null && mc.editorArea.contains(mc.doctorTab)) {
            TabFocus.select(mc.editorArea, mc.doctorTab); // (thirteenth review AK1)
            mc.doctorCoordinator.runChecks();
            return;
        }
        Tab tab = mc.addContentTab(mc.doctorCoordinator.pane(), true);
        tab.getStyleClass().add("ui-surface-tab");
        mc.doctorTab = tab;
        tab.setOnClosed(e -> {
            if (mc.doctorTab == tab) {
                mc.doctorTab = null;
            }
        });
        mc.doctorCoordinator.runChecks();
    }

    /**
     * Shows a terminal moved out of the Terminal tool window as an editor tab, selected, with the keyboard. Its menu
     * acts on the terminal (Rename, Move to Terminal Tool Window) before the tab operations. A close asks what
     * the panel would ask.
     */
    void openTerminalEditorTab(TerminalEditorTab terminal) {
        Tab tab = mc.addContentTab(terminal, true);
        terminal.attach(tab, () -> mc.tabHeaders.contentHeader(tab, terminal));
        tab.setOnCloseRequest(e -> {
            e.consume(); // closed by the terminal panel once it may be
            mc.terminalCoordinator.requestClose(terminal);
        });
        MenuItem rename = new MenuItem(tr("terminal.tab.rename"), Icons.edit());
        rename.setOnAction(e -> mc.terminalCoordinator.rename(terminal));
        MenuChords.tag(rename, "terminal.rename");
        MenuItem back = new MenuItem(tr("terminal.tab.moveToToolWindow"), Icons.dock());
        back.setOnAction(e -> mc.terminalCoordinator.moveToToolWindow(terminal));
        MenuChords.tag(back, "terminal.moveToToolWindow");
        mc.installContentTabMenu(tab, rename, back); // Close there is closeTab, which is this same requestClose
    }

    /** `view.welcome`: opens the Welcome tab (or selects it if already open). */
    void showWelcome() {
        addWelcomeTab();
    }

    /** Opens the Welcome tab when the strip is empty (startup with no session, or after a project swap). */
    void showWelcomeIfNoTabs() {
        if (!mc.sessions.suppressWelcome && mc.editorArea.isEmpty()) {
            addWelcomeTab();
        }
    }

    /**
     * The recent entries still worth offering — see {@link RecentFiles#showable}. Kept here so the toolbar
     * dropdown and the Welcome page filter identically; a deleted file must not be offered by either.
     */
    List<Path> showableRecentFiles() {
        return mc.recentFiles == null ? List.of() : recentCheck.showable(mc.recentFiles.getList());
    }

    /** A shared history list changed (in any window): refresh this window's recent menu and query dropdown. */
    void sharedHistoryChanged() {
        rebuildRecentMenu();
        mc.searchCoordinator.refreshHistory();
    }

    void rebuildRecentMenu() {
        recentCheck.revalidate(mc.recentFiles, this::rebuildRecentMenu); // stats off-thread; re-renders if wrong
        mc.recentButton.getItems().clear();
        List<Path> shown = showableRecentFiles();
        if (shown.isEmpty()) {
            MenuItem empty = com.editora.editor.RowReasons.placeholder(new MenuItem(tr("menu.noRecentFiles")));
            mc.recentButton.getItems().add(empty);
            return;
        }
        RecentFileRow.Columns columns = new RecentFileRow.Columns(); // lines the project pills up
        for (Path path : shown) {
            mc.recentButton.getItems().add(recentMenuItem(path, columns));
        }
        // "Clear recent files" lives here rather than as its own toolbar icon: as a bare trash can beside
        // the file group it read as "delete this file" and sat a slot away from Save. Inside the dropdown it
        // is unambiguous and can't be hit by accident. (The file.clearRecent command + the catalog entry are
        // unchanged, so anyone who wants the icon back can add it in Settings → Toolbar.)
        mc.recentButton.getItems().add(new SeparatorMenuItem());
        MenuItem clear = new MenuItem(tr("tooltip.clearRecent"), Icons.trash());
        clear.getStyleClass().add("danger"); // a destructive row reads red
        clear.setOnAction(e -> onClearRecent());
        MenuChords.tag(clear, "file.clearRecent");
        mc.recentButton.getItems().add(clear);
    }

    /** A recent-file menu entry: filename label that opens the file, project pill, and an inline remove icon. */
    private CustomMenuItem recentMenuItem(Path path, RecentFileRow.Columns columns) {
        Label name = new Label(path.getFileName().toString());
        Label projectName = RecentProject.containing(path, mc.config.projects().list())
                .map(project -> {
                    Label label = new Label(project.name());
                    label.getStyleClass().add("recent-project-name");
                    return label;
                })
                .orElse(null);
        Button removeBtn = new Button();
        removeBtn.setGraphic(Icons.trash());
        removeBtn.getStyleClass().addAll("button-icon", "flat", "recent-remove");
        removeBtn.setFocusTraversable(false);
        ControlNames.tip(removeBtn, tr("tooltip.removeRecent"));
        // Remove just this entry, after a confirmation, without opening it. The dropdown closes first: a
        // modal dialog over an open popup would leave the popup stacked above it. Deferred so the dialog's
        // nested event loop doesn't run inside this mouse press.
        removeBtn.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            e.consume();
            mc.recentButton.hide();
            Platform.runLater(() -> confirmRemoveRecent(path));
        });
        RecentFileRow row = new RecentFileRow(columns, name, projectName, removeBtn);

        CustomMenuItem item = new CustomMenuItem(row);
        item.setOnAction(e -> mc.openRecent(path));
        LazyTooltip.install(row, () -> path.toString());
        return item;
    }

    /** The words of the question before {@code path} is dropped from the recent files. Its answer: Remove (0). */
    static Confirm.Choice removeRecentQuestion(Path path) {
        return Confirm.choice(
                tr("dialog.removeRecent.title"),
                tr("dialog.removeRecent.header", String.valueOf(path.getFileName())),
                path.toString(),
                tr("dialog.removeRecent.remove"));
    }

    /** The words of the question before the recent files are cleared. Its answer: Clear (0). */
    static Confirm.Choice clearRecentQuestion() {
        return Confirm.choice(
                tr("dialog.clearRecent.title"), tr("dialog.clearRecent.header"), null, tr("dialog.clearRecent.clear"));
    }

    /** Asks before dropping {@code path} from the recent files; the full path tells same-named files apart. */
    private void confirmRemoveRecent(Path path) {
        if (mc.recentFiles == null) {
            return;
        }
        Confirm.Choice words = removeRecentQuestion(path);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(mc.stage);
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType remove = new ButtonType(words.answer(0));
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(remove, cancel);
        Dialogs.destructive(alert, remove);
        if (Dialogs.showAndWait(alert).orElse(cancel) == remove) {
            mc.recentFiles.remove(path);
        }
    }

    @FXML
    void onClearRecent() {
        if (mc.recentFiles == null || mc.recentFiles.getList().isEmpty()) {
            return;
        }
        Confirm.Choice words = clearRecentQuestion();
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(mc.stage);
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType clear = new ButtonType(words.answer(0));
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(clear, cancel);
        Dialogs.destructive(alert, clear);
        if (Dialogs.showAndWait(alert).orElse(cancel) == clear) {
            mc.recentFiles.clear();
            mc.setStatus(tr("status.recentCleared"));
        }
    }
}
