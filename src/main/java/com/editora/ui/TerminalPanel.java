package com.editora.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import com.editora.command.KeyDispatcher;
import com.editora.terminal.LinkActions;
import com.editora.terminal.RunningProgram;
import com.editora.terminal.SyntaxColors;
import com.editora.terminal.TerminalConnector;
import com.editora.terminal.TerminalCursor;
import com.editora.terminal.TerminalKeys;
import com.editora.terminal.TerminalPalette;
import com.editora.terminal.TerminalPalettes;
import com.editora.terminal.TerminalView;
import com.editora.terminal.ThemeSampler;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * The Terminal tool window's content: one tab per shell, a "+" for another, and the rules that make a focused
 * terminal own the keyboard.
 *
 * <p>Each tab owns a {@link TerminalView} and the shell behind it. Disposal is driven off the tab list rather
 * than the close button (Termina's rule): a tab leaves the list by its ✕, by "Close Terminal", by its shell
 * exiting, or by the window closing, and every one of those must end the shell. Tabs are not reorderable,
 * because a reorder is a remove and an add, which that listener would read as a close.
 */
final class TerminalPanel extends StackPane {

    /** Everything a terminal reads from Settings, as one snapshot the coordinator pushes. */
    record Config(
            String fontFamily,
            double fontSize,
            int scrollback,
            TerminalCursor cursor,
            boolean bell,
            String shell,
            List<String> ideCommands,
            boolean confirmClose) {

        static Config defaults() {
            return new Config("", 13, 5000, TerminalCursor.BLOCK, true, "", TerminalKeys.DEFAULT_IDE_COMMANDS, true);
        }
    }

    /** Opens a shell for a new tab: in {@code directory}, running {@code shell} (blank = the login shell). */
    interface Shells {
        TerminalConnector.Factory open(Path directory, String shell);
    }

    /** Asks before a tab whose shell is running {@code programs} is closed; true closes it. */
    interface CloseGuard {
        boolean confirm(String title, List<String> programs);
    }

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

    private final TabPane tabs = new TabPane();
    private final TerminalFindBar findBar = new TerminalFindBar();
    private final Button newTab = new Button();
    private final Button emptyStart = new Button(tr("terminal.new"));
    private final Map<Tab, TerminalView> views = new LinkedHashMap<>();
    /**
     * Each tab's folder, as {@link Vfs#toStorableString} ("" for home), so a reconnect finds it again: after a
     * dropped connection the old path belongs to a closed file system, and the folder has to be looked up on the
     * new one.
     */
    private final Map<Tab, String> dirs = new LinkedHashMap<>();

    private java.util.function.Function<Path, String> targetLabel = dir -> tr("terminal.target.local");

    /** For a tab's folder, how its shell's paths map to paths here (null: this machine). */
    private java.util.function.Function<Path, java.util.function.Function<String, Path>> shellPaths = dir -> null;

    /** What a shell elsewhere (its folder, its pid there) is running; asked before its tab closes. */
    private java.util.function.BiFunction<Path, Long, List<String>> remotePrograms = (dir, pid) -> List.of();

    private final Shells shells;
    private final CloseGuard closeGuard;
    private final Supplier<Path> directory;
    private Config config = Config.defaults();
    private TerminalPalette palette = TerminalPalettes.EDITORA_DARK;
    private TerminalPalette fallbackPalette = TerminalPalettes.EDITORA_DARK;
    private Consumer<String> onError = message -> {};
    private Supplier<List<MenuItem>> menuExtras = List::of;
    private LinkActions linkActions;

    TerminalPanel(Shells shells, CloseGuard closeGuard, Supplier<Path> directory) {
        this.shells = shells;
        this.closeGuard = closeGuard;
        this.directory = directory;
        getStyleClass().add("terminal-panel");
        tabs.getStyleClass().add("terminal-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        newTab.setGraphic(Icons.plus());
        newTab.getStyleClass().addAll("flat", "terminal-new-tab");
        newTab.setTooltip(new Tooltip(tr("terminal.new")));
        newTab.setFocusTraversable(false);
        newTab.setOnAction(e -> newTerminal());
        StackPane.setAlignment(newTab, Pos.TOP_RIGHT);

        emptyStart.setOnAction(e -> newTerminal());
        StackPane.setAlignment(emptyStart, Pos.CENTER);

        getChildren().addAll(tabs, emptyStart, newTab);
        updateEmpty();

        // The shell owns the keyboard; Editora keeps only what TerminalKeys lets through.
        BiPredicate<String, String> reachesEditora =
                (token, command) -> TerminalKeys.reachesEditora(token, command, config.ideCommands(), MAC);
        getProperties().put(KeyDispatcher.SHELL_KEYS, reachesEditora);
        // Copy and Paste are the terminal's own chords. A filter here runs before the view's, which would
        // otherwise encode Ctrl+Shift+C as ^C.
        addEventFilter(KeyEvent.KEY_PRESSED, this::onClipboardKey);

        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (findBar.isShowing()) {
                placeFindBar(now);
            }
            focusActive();
        });
        findBar.setOnClose(this::focusActive);
        tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) {
                for (Tab removed : change.getRemoved()) {
                    dirs.remove(removed);
                    TerminalView view = views.remove(removed);
                    if (view != null) {
                        view.close();
                    }
                }
            }
            updateEmpty();
        });
    }

    /** How a tab's shell's paths map to paths here, by the tab's folder (null for this machine). */
    void setShellPaths(java.util.function.Function<Path, java.util.function.Function<String, Path>> shellPaths) {
        this.shellPaths = shellPaths == null ? dir -> null : shellPaths;
    }

    /** What a shell elsewhere is running, for the close prompt (a shell here is asked of this machine). */
    void setRemotePrograms(java.util.function.BiFunction<Path, Long, List<String>> remotePrograms) {
        this.remotePrograms = remotePrograms == null ? (dir, pid) -> List.of() : remotePrograms;
    }

    /**
     * A new tab in the selected shell's own directory (as its shell last reported it), on the same machine; in the
     * tab's starting folder when the shell has said nothing.
     */
    void newTerminalHere() {
        Tab tab = activeTab();
        TerminalView view = activeView();
        if (tab == null || view == null) {
            newTerminal();
            return;
        }
        String stored = dirs.getOrDefault(tab, "");
        Path start = stored.isEmpty() ? null : Vfs.parseStorable(stored);
        String cwd = view.getDisplay().getCwd();
        Path here = null;
        if (cwd != null && !cwd.isBlank()) {
            var map = shellPaths.apply(start);
            try {
                here = map == null ? Path.of(cwd) : map.apply(cwd);
            } catch (RuntimeException notAPath) {
                here = null;
            }
        }
        newTerminal(here != null ? here : start);
    }

    /** What a tab's chip says about where its shell runs ("Local", a host name). */
    void setTargetLabel(java.util.function.Function<Path, String> targetLabel) {
        this.targetLabel = targetLabel == null ? dir -> tr("terminal.target.local") : targetLabel;
    }

    /** Where a shell that failed to start says why. */
    void setOnError(Consumer<String> onError) {
        this.onError = onError == null ? message -> {} : onError;
    }

    /** Items for the terminal's right-click menu below its own. */
    void setMenuExtras(Supplier<List<MenuItem>> menuExtras) {
        this.menuExtras = menuExtras == null ? List::of : menuExtras;
        views.values().forEach(v -> v.setMenuExtras(this.menuExtras));
    }

    /** Where a clicked link goes: files into the editor, URLs to the browser. Without it links are inert. */
    void setLinkActions(LinkActions linkActions) {
        this.linkActions = linkActions;
        views.values().forEach(v -> v.setLinkActions(linkActions));
    }

    /** The link actions every tab uses, or null. */
    LinkActions linkActions() {
        return linkActions;
    }

    /** Opens a new tab with a shell in the coordinator's directory, and focuses it. */
    void newTerminal() {
        newTerminal(directory.get());
    }

    /** Opens a new tab with a shell in {@code dir} (null: home), and focuses it. */
    void newTerminal(Path dir) {
        Tab tab = new Tab();
        // The view paints its own grid; the wrapper fills the strip a whole number of rows leaves below it
        // with the same ground. Only the content: the tab strip keeps the window theme's colours.
        StackPane content = new StackPane();
        content.getStyleClass().add("terminal-content");
        content.setStyle(groundStyle());
        tab.setContent(content);
        Label target = new Label(targetLabel.apply(dir));
        target.getStyleClass().add("terminal-target-chip");
        tab.setGraphic(target);
        tab.setTooltip(new Tooltip());
        tab.setOnCloseRequest(e -> {
            if (!mayClose(tab)) {
                e.consume();
            }
        });
        dirs.put(tab, dir == null ? "" : Vfs.toStorableString(dir));
        if (!startIn(tab)) {
            dirs.remove(tab);
            return;
        }
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        focusActive();
    }

    /**
     * Starts a shell for {@code tab} in its folder and puts its view in the tab, replacing one whose connection
     * was lost. Started before it is shown, so a shell that cannot start leaves the tab as it was.
     */
    private boolean startIn(Tab tab) {
        String stored = dirs.getOrDefault(tab, "");
        Path dir = stored.isEmpty() ? null : Vfs.parseStorable(stored);
        if (!stored.isEmpty() && dir == null) {
            onError.accept(tr("terminal.startFailed", tr("terminal.notConnected", stored)));
            return false;
        }
        TerminalView view = new TerminalView(config.fontSize());
        configure(view);
        view.setPalette(palette);
        view.setShellPaths(dir == null ? null : shellPaths.apply(dir));
        view.setOnSessionEnded(() -> shellEnded(tab, view));
        try {
            view.start(shells.open(dir, config.shell()));
        } catch (IOException | RuntimeException e) {
            view.close();
            onError.accept(tr("terminal.startFailed", e.getMessage() == null ? e.toString() : e.getMessage()));
            return false;
        }
        TerminalView previous = views.put(tab, view);
        if (previous != null) {
            previous.close();
        }
        ((StackPane) tab.getContent()).getChildren().setAll(view);
        tab.textProperty().unbind();
        tab.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> titleOr(view.getDisplay().tabTitleProperty().get()),
                        view.getDisplay().tabTitleProperty()));
        tab.getTooltip().textProperty().unbind();
        tab.getTooltip().textProperty().bind(view.getDisplay().tabTooltipProperty());
        return true;
    }

    /**
     * A shell ended: its tab closes, unless the connection to its machine went — then the tab keeps what the shell
     * printed and offers to reconnect, as an {@code ssh} session in a terminal window would leave its text.
     */
    private void shellEnded(Tab tab, TerminalView view) {
        if (views.get(tab) != view) {
            return; // replaced by a reconnect, or already gone
        }
        if (view.getSession() == null || !view.getSession().lostConnection()) {
            tabs.getTabs().remove(tab);
            return;
        }
        Label message = new Label(tr("terminal.connectionLost"));
        Button reconnect = new Button(tr("terminal.reconnect"));
        reconnect.setOnAction(e -> {
            if (startIn(tab)) {
                focusActive();
            }
        });
        HBox banner = new HBox(10, message, reconnect);
        banner.getStyleClass().add("terminal-lost-banner");
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(banner, Pos.TOP_CENTER);
        ((StackPane) tab.getContent()).getChildren().add(banner);
        if (tab.isSelected()) {
            Platform.runLater(reconnect::requestFocus);
        }
    }

    /** Whether {@code tab}'s connection was lost and it is offering to reconnect. */
    boolean showsReconnect(Tab tab) {
        return tab != null
                && ((StackPane) tab.getContent())
                        .getChildren().stream().anyMatch(n -> n.getStyleClass().contains("terminal-lost-banner"));
    }

    /** The selected tab, or null. */
    Tab activeTab() {
        return tabs.getSelectionModel().getSelectedItem();
    }

    /** Closes the selected tab, asking first if its shell is running something. */
    void closeActive() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab != null && mayClose(tab)) {
            tabs.getTabs().remove(tab);
        }
    }

    /** Ends every shell, for the window closing. Asks nothing: the window has already decided. */
    void shutdown() {
        tabs.getTabs().clear();
    }

    boolean hasTerminals() {
        return !views.isEmpty();
    }

    int terminalCount() {
        return views.size();
    }

    /** The selected tab's view, or null. */
    TerminalView activeView() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab == null ? null : views.get(tab);
    }

    void focusActive() {
        TerminalView view = activeView();
        if (view != null) {
            Platform.runLater(view::requestFocus);
        } else if (emptyStart.isVisible()) {
            Platform.runLater(emptyStart::requestFocus);
        }
    }

    /** New settings: font, cursor and bell apply now; scrollback and shell from the next tab. */
    void apply(Config next) {
        this.config = next == null ? Config.defaults() : next;
        views.values().forEach(this::configure);
    }

    /**
     * Re-reads the editor theme's colours from the scene and recolours every terminal; {@code fallback} is used
     * while the panel is not in a scene, or when the theme's colours cannot be read.
     */
    void refreshPalette(TerminalPalette fallback) {
        fallbackPalette = fallback == null ? TerminalPalettes.EDITORA_DARK : fallback;
        SyntaxColors colors = ThemeSampler.sample(this);
        palette = colors == null ? fallbackPalette : TerminalPalettes.derive(colors);
        views.values().forEach(v -> {
            v.setPalette(palette);
            if (v.getParent() != null) {
                v.getParent().setStyle(groundStyle());
            }
        });
    }

    TerminalPalette palette() {
        return palette;
    }

    private String groundStyle() {
        return "-fx-background-color: " + web(palette.background()) + ";";
    }

    private void configure(TerminalView view) {
        view.setFontFamily(config.fontFamily());
        view.setFontSize(config.fontSize());
        view.setPreferredCursor(config.cursor());
        view.setBellEnabled(config.bell());
        view.setScrollbackLines(config.scrollback());
        view.setMenuIcons(TerminalPanel::menuIcon);
        view.setMenuExtras(menuExtras);
        view.setLinkActions(linkActions);
    }

    private boolean mayClose(Tab tab) {
        TerminalView view = views.get(tab);
        if (view == null || !config.confirmClose() || view.getSession() == null) {
            return true;
        }
        long pid = view.getSession().pid();
        long remote = view.getSession().remotePid();
        List<String> programs;
        if (pid > 0) {
            programs = RunningProgram.in(pid);
        } else if (remote > 0) {
            String stored = dirs.getOrDefault(tab, "");
            Path dir = stored.isEmpty() ? null : Vfs.parseStorable(stored);
            programs = dir == null ? List.of() : remotePrograms.apply(dir, remote);
        } else {
            programs = List.of();
        }
        return programs.isEmpty() || closeGuard.confirm(tab.getText(), new ArrayList<>(programs));
    }

    private void onClipboardKey(KeyEvent e) {
        TerminalView view = activeView();
        if (view == null) {
            return;
        }
        if (TerminalKeys.isCopy(e, MAC)) {
            view.copySelection();
            e.consume();
        } else if (TerminalKeys.isPaste(e, MAC)) {
            view.paste();
            e.consume();
        } else if (TerminalKeys.isFind(e, MAC)) {
            find();
            e.consume();
        } else if (TerminalKeys.isPreviousPrompt(e, MAC)) {
            view.previousPrompt();
            e.consume();
        } else if (TerminalKeys.isNextPrompt(e, MAC)) {
            view.nextPrompt();
            e.consume();
        }
    }

    /** Opens the find bar over the selected tab, seeded with a one-line selection. */
    void find() {
        TerminalView view = activeView();
        if (view == null) {
            return;
        }
        placeFindBar(activeTab());
        findBar.open(view, view.selectedText());
    }

    TerminalFindBar findBar() {
        return findBar;
    }

    /** Moves the find bar into {@code tab}'s content and points it at that tab's terminal. */
    private void placeFindBar(Tab tab) {
        if (findBar.getParent() instanceof StackPane old) {
            old.getChildren().remove(findBar);
        }
        TerminalView view = tab == null ? null : views.get(tab);
        if (view == null) {
            findBar.close();
            return;
        }
        StackPane content = (StackPane) tab.getContent();
        StackPane.setAlignment(findBar, Pos.TOP_RIGHT);
        StackPane.setMargin(findBar, new javafx.geometry.Insets(6, 18, 0, 0));
        content.getChildren().add(findBar);
        findBar.bindTo(view);
    }

    private void updateEmpty() {
        boolean empty = tabs.getTabs().isEmpty();
        emptyStart.setVisible(empty);
        emptyStart.setManaged(empty);
        tabs.setVisible(!empty);
        newTab.setVisible(!empty);
    }

    private static String titleOr(String title) {
        return title == null || title.isBlank() ? tr("toolwindow.terminal") : title;
    }

    private static Node menuIcon(String key) {
        return switch (key) {
            case "copy" -> Icons.copy();
            case "paste" -> Icons.paste();
            case "selectAll" -> Icons.selectAll();
            case "openLink" -> Icons.open();
            case "clear" -> Icons.trash();
            default -> null;
        };
    }

    private static String web(Color c) {
        return String.format(
                Locale.ROOT,
                "#%02x%02x%02x",
                Math.round(c.getRed() * 255),
                Math.round(c.getGreen() * 255),
                Math.round(c.getBlue() * 255));
    }
}
