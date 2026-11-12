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
import com.editora.editor.MenuChords;
import com.editora.editor.UiZoom;
import com.editora.terminal.DesktopNotifier;
import com.editora.terminal.LinkActions;
import com.editora.terminal.ProgramRun;
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
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Terminal tool window's content: one tab per shell, a "+" for another, and the rules that make a focused
 * terminal own the keyboard.
 *
 * <p>Each tab owns a {@link TerminalView} and the shell behind it. Disposal is driven off the tab list rather
 * than the close button (Termina's rule): a tab leaves the list by its ✕, by "Close Terminal", by its shell
 * exiting, or by the window closing, and every one of those must end the shell. A reorder is a remove and an add,
 * so it runs with {@code reordering} set, which that listener reads as "nothing closed" (Termina's rule too).
 */
final class TerminalPanel extends StackPane implements ToolWindowContent {

    /** Everything a terminal reads from Settings, as one snapshot the coordinator pushes. */
    record Config(
            String fontFamily,
            double fontSize,
            int scrollback,
            TerminalCursor cursor,
            boolean bell,
            String shell,
            List<String> ideCommands,
            boolean confirmClose,
            boolean typeAhead,
            boolean macOptionIsMeta,
            boolean confirmMultilinePaste,
            boolean copyOnSelect,
            boolean clipboardWrites,
            boolean notifications) {

        Config(
                String fontFamily,
                double fontSize,
                int scrollback,
                TerminalCursor cursor,
                boolean bell,
                String shell,
                List<String> ideCommands,
                boolean confirmClose,
                boolean typeAhead,
                boolean macOptionIsMeta,
                boolean confirmMultilinePaste) {
            this(
                    fontFamily,
                    fontSize,
                    scrollback,
                    cursor,
                    bell,
                    shell,
                    ideCommands,
                    confirmClose,
                    typeAhead,
                    macOptionIsMeta,
                    confirmMultilinePaste,
                    false,
                    true,
                    true);
        }

        Config(
                String fontFamily,
                double fontSize,
                int scrollback,
                TerminalCursor cursor,
                boolean bell,
                String shell,
                List<String> ideCommands,
                boolean confirmClose,
                boolean typeAhead) {
            this(
                    fontFamily,
                    fontSize,
                    scrollback,
                    cursor,
                    bell,
                    shell,
                    ideCommands,
                    confirmClose,
                    typeAhead,
                    false,
                    true);
        }

        Config(
                String fontFamily,
                double fontSize,
                int scrollback,
                TerminalCursor cursor,
                boolean bell,
                String shell,
                List<String> ideCommands,
                boolean confirmClose) {
            this(fontFamily, fontSize, scrollback, cursor, bell, shell, ideCommands, confirmClose, true);
        }

        static Config defaults() {
            return new Config("", 13, 5000, TerminalCursor.BLOCK, true, "", TerminalKeys.DEFAULT_IDE_COMMANDS, true);
        }
    }

    /** Opens a shell for a new tab: in {@code directory}, running {@code shell} (blank = the login shell). */
    interface Shells {
        TerminalConnector.Factory open(@Nullable Path directory, String shell);
    }

    /** Asks before a tab whose shell is running {@code programs} is closed; true closes it. */
    interface CloseGuard {
        boolean confirm(String title, List<String> programs);
    }

    private static final boolean MAC = com.editora.Os.isMac();

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

    private java.util.function.Function<@Nullable Path, String> targetLabel = dir -> tr("terminal.target.local");

    /** For a tab's folder, how its shell's paths map to paths here (null: this machine). */
    private java.util.function.Function<@Nullable Path, java.util.function.@Nullable Function<String, Path>>
            shellPaths = dir -> null;

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
    private @Nullable LinkActions linkActions;

    TerminalPanel(Shells shells, CloseGuard closeGuard, Supplier<Path> directory) {
        this.shells = shells;
        this.closeGuard = closeGuard;
        this.directory = directory;
        getStyleClass().add("terminal-panel");
        tabs.getStyleClass().add("terminal-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        newTab.setGraphic(Icons.plus());
        newTab.getStyleClass().addAll("flat", "terminal-new-tab");
        CommandTips.name(newTab, "terminal.new");
        newTab.setFocusTraversable(false);
        newTab.setOnAction(e -> newTerminal());
        StackPane.setAlignment(newTab, Pos.TOP_RIGHT);

        emptyStart.setOnAction(e -> newTerminal());
        StackPane.setAlignment(emptyStart, Pos.CENTER);

        getChildren().addAll(tabs, emptyStart, newTab);
        updateEmpty();

        // The shell owns the keyboard; Editora keeps only what TerminalKeys lets through. Set on the panel and on
        // each tab's content, which is where the rule is found once a tab has moved into the editor.
        getProperties().put(KeyDispatcher.SHELL_KEYS, reachesEditora);

        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now != null) {
                now.getStyleClass().removeAll(BELL, NOTIFIED);
                TerminalView shown = views.get(now);
                if (shown != null) {
                    shown.repaint(); // not painted while its tab was in the background
                }
            }
            // The find bar follows the selection here, unless it is over a terminal in the editor.
            if (findBar.isShowing() && (findTab == null || !inEditor.contains(findTab))) {
                placeFindBar(now);
            }
            if (!quiet) {
                focusActive();
            }
        });
        findBar.setOnClose(() -> {
            Tab was = findTab;
            if (was != null && inEditor.contains(was)) {
                focus(was);
            } else {
                focusActive();
            }
        });
        tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
            if (reordering) {
                return; // a move is a remove and an add: nothing closed
            }
            while (change.next()) {
                change.getRemoved().forEach(this::release);
            }
            updateEmpty();
            refreshTitles();
            for (Tab tab : tabs.getTabs()) {
                if (tab.getContextMenu() == null) {
                    tab.setContextMenu(tabMenu(tab));
                }
            }
            Platform.runLater(() -> installTabDrag(0));
            if (!hasTerminals() && !shuttingDown && change.getList().isEmpty()) {
                onLastClosed.run();
            }
        });
    }

    /** Which keys a focused terminal lets through to Editora (see {@link KeyDispatcher#SHELL_KEYS}). */
    private final BiPredicate<String, String> reachesEditora =
            (token, command) -> TerminalKeys.reachesEditora(token, command, config.ideCommands(), MAC);

    /**
     * A tab's content: the terminal's ground, its key rule, and the filter for the terminal's own chords. Copy and
     * Paste are the terminal's: the filter runs before the view's, which would otherwise encode Ctrl+Shift+C as ^C.
     * It is the content's rather than the panel's so it goes with a tab moved into the editor.
     */
    private StackPane newContent(Tab tab) {
        StackPane content = new StackPane();
        content.getStyleClass().add("terminal-content");
        content.setStyle(groundStyle());
        content.getProperties().put(KeyDispatcher.SHELL_KEYS, reachesEditora);
        content.addEventFilter(KeyEvent.KEY_PRESSED, e -> onClipboardKey(tab, content, e));
        tab.setContent(content);
        return content;
    }

    /** Ends a closed tab's shell (or program) and forgets it. */
    private void release(Tab removed) {
        java.util.function.IntConsumer killed = programExits.remove(removed);
        if (killed != null) {
            killed.accept(-1); // its tab closed under it: the program ends with it
        }
        dirs.remove(removed);
        titles.remove(removed);
        names.remove(removed);
        runKeys.remove(removed);
        programNames.remove(removed);
        TerminalView pending = connecting.remove(removed);
        if (pending != null) {
            pending.close(); // its connector is closed when it arrives
        }
        TerminalView view = views.remove(removed);
        if (view != null) {
            view.close();
        }
        if (findTab == removed) {
            findBar.close();
            findTab = null;
        }
    }

    // ---------------------------------------------------------------- terminals in the editor

    /**
     * Where a tab moved into the editor is shown. The tab keeps its place in this panel's books (its shell, title,
     * folder, program, close question); only its content is shown in an editor tab instead of the strip here.
     */
    interface EditorHost {
        /** Shows {@code tab}'s content in a new editor tab, selected and focused. */
        void open(Tab tab);

        /** Removes {@code tab}'s editor tab: its shell ended or it was closed, or it went back to the panel. */
        void close(Tab tab);

        /** Whether {@code tab}'s editor tab is the one shown in its group of a window on screen. */
        boolean isShowing(Tab tab);
    }

    private @Nullable EditorHost editorHost;

    /** Tabs shown in the editor rather than this panel, in the order they moved there. */
    private final java.util.Set<Tab> inEditor = new java.util.LinkedHashSet<>();

    void setEditorHost(@Nullable EditorHost editorHost) {
        this.editorHost = editorHost;
    }

    /** The tabs shown in the editor, in the order they moved there; for tests. */
    List<Tab> editorTabsForTest() {
        return List.copyOf(inEditor);
    }

    /** Whether {@code tab} is shown in the editor. */
    boolean isInEditor(Tab tab) {
        return inEditor.contains(tab);
    }

    /**
     * Moves {@code tab} from the strip here into an editor tab, shell and all: nothing restarts, and what it printed
     * stays. The panel hides when it was the last one here, as when the last tab closes.
     */
    void moveToEditor(Tab tab) {
        if (editorHost == null || !tabs.getTabs().contains(tab)) {
            return;
        }
        if (findTab == tab) {
            findBar.close();
            findTab = null;
        }
        reordering = true; // a move, not a close: the shell stays
        try {
            tabs.getTabs().remove(tab);
        } finally {
            reordering = false;
        }
        inEditor.add(tab);
        updateEmpty();
        refreshTitles();
        if (tabs.getTabs().isEmpty() && !shuttingDown) {
            onLastClosed.run();
        }
        editorHost.open(tab);
    }

    /** Moves a tab shown in the editor back to the end of the strip here, selected. */
    void moveToPanel(Tab tab) {
        if (!inEditor.remove(tab)) {
            return;
        }
        if (findTab == tab) {
            findBar.close();
            findTab = null;
        }
        if (editorHost != null) {
            editorHost.close(tab); // the editor tab goes; this tab is no longer in inEditor, so its shell stays
        }
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        refreshTitles();
        focusActive();
    }

    /** Its editor tab was closed some way other than through this panel (Close All, the window): end its shell. */
    void editorTabClosed(Tab tab) {
        if (inEditor.remove(tab)) {
            release(tab);
            refreshTitles();
        }
    }

    /** Closes {@code tab} wherever it is shown: its shell ends and its tab goes. */
    private void remove(Tab tab) {
        if (inEditor.remove(tab)) {
            release(tab);
            if (editorHost != null) {
                editorHost.close(tab);
            }
            refreshTitles();
        } else {
            tabs.getTabs().remove(tab);
        }
    }

    /** Whether {@code tab} is still open, here or in the editor. */
    private boolean owns(Tab tab) {
        return tabs.getTabs().contains(tab) || inEditor.contains(tab);
    }

    /** Every open tab: the strip's in order, then those in the editor. */
    private List<Tab> allTabs() {
        List<Tab> all = new ArrayList<>(tabs.getTabs());
        all.addAll(inEditor);
        return all;
    }

    /** Whether {@code tab} is where the user can see it: selected in the shown panel, or its editor tab shown. */
    private boolean inView(Tab tab) {
        if (inEditor.contains(tab)) {
            return editorHost != null && editorHost.isShowing(tab);
        }
        return tab.isSelected() && isVisible() && getScene() != null;
    }

    /** Whether any terminal is open, in the panel or in the editor. */
    boolean hasAnyTerminals() {
        return !tabs.getTabs().isEmpty() || !inEditor.isEmpty();
    }

    /**
     * The tab the terminal commands act on: one in the editor that has the keyboard, else the panel's selected tab.
     */
    @Nullable
    Tab currentTab() {
        for (Tab tab : inEditor) {
            Node content = tab.getContent();
            var scene = content == null ? null : content.getScene();
            for (Node n = scene == null ? null : scene.getFocusOwner(); n != null; n = n.getParent()) {
                if (n == content) {
                    return tab;
                }
            }
        }
        return activeTab();
    }

    /** Gives the keyboard to {@code tab}'s terminal (its reconnect button while it offers one). */
    void focus(Tab tab) {
        Platform.runLater(() -> {
            TerminalView view = views.get(tab);
            if (view == null) {
                if (connecting.containsKey(tab)) {
                    focusWhenStarted = tab;
                }
                return;
            }
            Node reconnect = tab.getContent() == null ? null : tab.getContent().lookup(".terminal-lost-banner .button");
            if (reconnect != null) {
                reconnect.requestFocus();
            } else {
                view.requestFocus();
            }
        });
    }

    /** How a tab's shell's paths map to paths here, by the tab's folder (null for this machine). */
    void setShellPaths(
            java.util.function.Function<@Nullable Path, java.util.function.@Nullable Function<String, Path>>
                    shellPaths) {
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
        Tab tab = currentTab();
        TerminalView view = tab == null ? null : views.get(tab);
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
    void setTargetLabel(java.util.function.Function<@Nullable Path, String> targetLabel) {
        this.targetLabel = targetLabel == null ? dir -> tr("terminal.target.local") : targetLabel;
    }

    /** Whether Alt/Option is Meta in a terminal: always off macOS; on it, only when the setting says so. */
    static boolean altIsMeta(boolean mac, boolean macOptionIsMeta) {
        return !mac || macOptionIsMeta;
    }

    /** Asks whether to paste several lines into a program that would run each at once; true pastes. */
    private java.util.function.Predicate<String> pasteGuard = text -> true;

    void setPasteGuard(java.util.function.Predicate<String> pasteGuard) {
        this.pasteGuard = pasteGuard == null ? text -> true : pasteGuard;
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
    @Nullable
    LinkActions linkActions() {
        return linkActions;
    }

    /** Opens a new tab with a shell in the coordinator's directory, and focuses it. */
    void newTerminal() {
        newTerminal(directory.get());
    }

    /**
     * Set while a shell starts without taking the keyboard: the one a restored window opens on its own, whose
     * first keystrokes belong to the editor that had them before the restart.
     */
    private boolean quiet;

    /** Opens a new tab with a shell in the coordinator's directory without moving focus. */
    void newTerminalQuietly() {
        quiet = true;
        try {
            newTerminal(directory.get());
        } finally {
            quiet = false;
        }
    }

    /** Opens a new tab with a shell in {@code dir} (null: home), and focuses it. */
    void newTerminal(@Nullable Path dir) {
        // Named while it connects: the host's name says what is coming, where an empty tab said nothing.
        Tab tab = new Tab(dir == null ? tr("toolwindow.terminal") : targetLabel.apply(dir));
        // The view paints its own grid; the wrapper fills the strip a whole number of rows leaves below it
        // with the same ground. Only the content: the tab strip keeps the window theme's colours.
        newContent(tab);
        Label target = new Label(targetLabel.apply(dir));
        target.getStyleClass().add("terminal-target-chip");
        tab.setGraphic(target);
        tab.setTooltip(new Tooltip());
        tab.setOnCloseRequest(e -> {
            e.consume(); // closed by requestClose once it may be
            requestClose(tab);
        });
        dirs.put(tab, dir == null ? "" : Vfs.toStorableString(dir));
        if (!startIn(tab)) {
            dirs.remove(tab);
            return;
        }
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        if (!quiet) {
            focusActive();
        }
    }

    /** Program tabs, with whom to tell when the program ends; told once. */
    private final Map<Tab, java.util.function.IntConsumer> programExits = new LinkedHashMap<>();

    /** What a program tab runs, by name, for the question its close asks while it runs. */
    private final Map<Tab, String> programNames = new LinkedHashMap<>();

    /**
     * Runs a program in a new tab titled {@code title}: {@code connector} opens it at the tab's size. When it ends
     * the tab keeps its text under a banner with the exit code, which {@code onExit} receives on the FX thread (-1
     * when it was ended without one: its tab closed, its connection lost). Throws when it cannot start; no tab
     * is left then.
     */
    ProgramRun runProgram(
            String title, Path dir, TerminalConnector.Factory connector, java.util.function.IntConsumer onExit)
            throws IOException {
        return runProgram(title, title, dir, connector, onExit);
    }

    /** As above; {@code program} names what runs (its executable), for the question closing its tab asks. */
    ProgramRun runProgram(
            String title,
            String program,
            @Nullable Path dir,
            TerminalConnector.Factory connector,
            java.util.function.IntConsumer onExit)
            throws IOException {
        TerminalView view = new TerminalView(config.fontSize());
        configure(view);
        view.setPalette(palette);
        var paths = dir == null ? null : shellPaths.apply(dir);
        if (paths != null) { // a new view maps no paths until told to
            view.setShellPaths(paths);
        }
        Tab tab = new Tab(title);
        String runKey = title + "\u0000" + (dir == null ? "" : Vfs.toStorableString(dir));
        runKeys.put(tab, runKey);
        StackPane content = newContent(tab);
        content.getChildren().add(view);
        Label target = new Label(targetLabel.apply(dir));
        target.getStyleClass().add("terminal-target-chip");
        tab.setGraphic(target);
        tab.setTooltip(new Tooltip(title));
        tab.setOnCloseRequest(e -> {
            e.consume(); // closed by requestClose once it may be
            requestClose(tab);
        });
        view.setOnSessionEnded(() -> programEnded(tab, view));
        markBell(tab, view);
        titles.put(tab, () -> title);
        programExits.put(tab, onExit == null ? code -> {} : onExit);
        if (connector.opensSlowly()) {
            // A program on another machine: the channel opens off the FX thread, as a remote shell's does. A
            // failure arrives later, as the program's end (-1) and the error.
            dirs.put(tab, dir == null ? "" : Vfs.toStorableString(dir));
            programNames.put(tab, program == null || program.isBlank() ? title : program);
            addProgramTab(tab, runKey);
            content.getChildren().clear();
            startProgramSlowly(tab, view, connector, dir);
            return programRun(tab, view);
        }
        try {
            view.start(connector);
        } catch (IOException | RuntimeException e) {
            programExits.remove(tab);
            view.close();
            throw e;
        }
        dirs.put(tab, dir == null ? "" : Vfs.toStorableString(dir));
        views.put(tab, view);
        programNames.put(tab, program == null || program.isBlank() ? title : program);
        addProgramTab(tab, runKey);
        focusActive();
        return programRun(tab, view);
    }

    /** What each program tab runs (its title and folder), so the next run of the same thing can take its place. */
    private final Map<Tab, String> runKeys = new LinkedHashMap<>();

    /**
     * Adds a program tab where the last run of the same thing ended, replacing that tab, as IntelliJ reuses a run's
     * tab: running a configuration five times left five tabs to close. A run still going keeps its tab, and the new
     * one goes at the end.
     */
    private void addProgramTab(Tab tab, String runKey) {
        for (int i = 0; i < tabs.getTabs().size(); i++) {
            Tab old = tabs.getTabs().get(i);
            if (old != tab && runKey.equals(runKeys.get(old)) && !programExits.containsKey(old)) {
                // One change, so the list is never empty in between (that would hide the window); the old
                // tab's view is closed as any removed tab's is.
                tabs.getTabs().set(i, tab);
                tabs.getSelectionModel().select(tab);
                return;
            }
        }
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    /** {@link #startSlowly} for a program tab: connected, it shows the program; failed, the tab goes. */
    private void startProgramSlowly(Tab tab, TerminalView view, TerminalConnector.Factory factory, @Nullable Path dir) {
        StackPane content = (StackPane) tab.getContent();
        Label note = new Label(tr("terminal.connecting", targetLabel.apply(dir)));
        note.getStyleClass().add("terminal-connecting");
        StackPane.setAlignment(note, Pos.CENTER);
        content.getChildren().add(note);
        connecting.put(tab, view);
        view.startAsync(
                factory,
                () -> {
                    connecting.remove(tab);
                    views.put(tab, view);
                    content.getChildren().setAll(view);
                    focusIfAsked(tab, view);
                },
                e -> {
                    connecting.remove(tab);
                    view.close();
                    remove(tab); // tells its starter: the program ended without a code
                    onError.accept(tr("terminal.startFailed", e.getMessage() == null ? e.toString() : e.getMessage()));
                });
    }

    private ProgramRun programRun(Tab tab, TerminalView view) {
        return new ProgramRun() {
            @Override
            public long pid() {
                var session = view.getSession();
                if (session == null) return -1;
                return session.pid() > 0 ? session.pid() : session.remotePid();
            }

            @Override
            public boolean isRunning() {
                return programExits.containsKey(tab);
            }

            @Override
            public void stop() {
                if (view.getSession() != null) {
                    view.getSession().terminate();
                } else if (connecting.get(tab) == view) {
                    remove(tab); // still connecting: it never starts, and its starter is told
                }
            }
        };
    }

    /** A program tab's program ended: the tab stays, saying how, and its starter is told the exit code. */
    private void programEnded(Tab tab, TerminalView view) {
        java.util.function.IntConsumer onExit = programExits.remove(tab);
        if (onExit == null || views.get(tab) != view) {
            return;
        }
        var session = view.getSession();
        Integer code = session == null ? null : session.exitCode();
        boolean lost = session != null && session.lostConnection();
        String text = lost
                ? tr("terminal.connectionLost")
                : code == null ? tr("terminal.program.ended") : tr("run.exited", code);
        Label message = new Label(text);
        HBox banner = UiZoom.spacing(new HBox(message), 10);
        banner.getStyleClass().add("terminal-exit-banner");
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.setMaxHeight(Region.USE_PREF_SIZE);
        banner.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane.setAlignment(banner, Pos.BOTTOM_RIGHT);
        UiZoom.follow(banner, () -> StackPane.setMargin(banner, UiZoom.insets(0, 18, 6, 0)));
        ((StackPane) tab.getContent()).getChildren().add(banner);
        onExit.accept(lost || code == null ? -1 : code);
    }

    /** The banner a program tab shows once its program ended, or null; for tests. */
    @Nullable
    String exitBanner(Tab tab) {
        return ((StackPane) tab.getContent())
                .getChildren().stream()
                        .filter(n -> n.getStyleClass().contains("terminal-exit-banner"))
                        .map(n -> ((Label) ((HBox) n).getChildren().getFirst()).getText())
                        .findFirst()
                        .orElse(null);
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
        if (connecting.containsKey(tab)) {
            return true; // already connecting (a second Reconnect click)
        }
        TerminalView view = new TerminalView(config.fontSize());
        configure(view);
        view.setPalette(palette);
        var paths = dir == null ? null : shellPaths.apply(dir);
        if (paths != null) { // a new view maps no paths until told to
            view.setShellPaths(paths);
        }
        view.setOnSessionEnded(() -> shellEnded(tab, view));
        TerminalConnector.Factory factory;
        try {
            factory = shells.open(dir, config.shell());
            if (!factory.opensSlowly()) {
                view.start(factory);
                install(tab, view);
                return true;
            }
        } catch (IOException | RuntimeException e) {
            view.close();
            onError.accept(tr("terminal.startFailed", e.getMessage() == null ? e.toString() : e.getMessage()));
            return false;
        }
        startSlowly(tab, view, factory, dir);
        return true;
    }

    /** Views whose shells are still connecting, by tab; a tab's current view stays in {@link #views} meanwhile. */
    private final Map<Tab, TerminalView> connecting = new LinkedHashMap<>();

    /**
     * Opens a shell on another machine without holding the FX thread. The tab says it is connecting, over the text
     * of the shell it replaces (a reconnect) or on its own (a new tab). Connected, the new view takes the tab;
     * failed, a new tab goes away and a reconnecting one keeps its banner, and the error says why.
     */
    private void startSlowly(Tab tab, TerminalView view, TerminalConnector.Factory factory, @Nullable Path dir) {
        StackPane content = (StackPane) tab.getContent();
        Label note = new Label(tr("terminal.connecting", targetLabel.apply(dir)));
        note.getStyleClass().add("terminal-connecting");
        StackPane.setAlignment(note, Pos.CENTER);
        if (factory.saysConnecting()) {
            content.getChildren().add(note);
        }
        connecting.put(tab, view);
        view.startAsync(
                factory,
                () -> {
                    connecting.remove(tab);
                    content.getChildren().remove(note);
                    install(tab, view);
                    focusIfAsked(tab, view);
                },
                e -> {
                    connecting.remove(tab);
                    content.getChildren().remove(note);
                    view.close();
                    if (!views.containsKey(tab)) {
                        remove(tab); // a new tab whose shell never came
                    }
                    onError.accept(tr("terminal.startFailed", e.getMessage() == null ? e.toString() : e.getMessage()));
                });
    }

    /** Whether {@code tab}'s shell is still connecting; for tests. */
    boolean isConnecting(Tab tab) {
        return connecting.containsKey(tab);
    }

    /** Puts a started {@code view} in {@code tab}, replacing (and ending) the one there. */
    private void install(Tab tab, TerminalView view) {
        TerminalView previous = views.put(tab, view);
        if (previous != null) {
            previous.close();
        }
        ((StackPane) tab.getContent()).getChildren().setAll(view);
        titles.put(tab, () -> titleOr(view.getDisplay().tabTitleProperty().get()));
        view.getDisplay().tabTitleProperty().addListener(o -> refreshTitles());
        refreshTitles();
        tab.getTooltip().textProperty().unbind();
        tab.getTooltip().textProperty().bind(view.getDisplay().tabTooltipProperty());
        markBell(tab, view);
        markNotifications(tab, view);
    }

    /** Each tab's own title, before same-name tabs are numbered (see {@link #refreshTitles}). */
    private final Map<Tab, Supplier<String>> titles = new LinkedHashMap<>();

    /**
     * Sets every tab's text from its title, numbering repeats in tab order ("project", "project (2)"): eleven
     * tabs in one folder all read "project" and could not be told apart.
     */
    private void refreshTitles() {
        Map<String, Integer> seen = new java.util.HashMap<>();
        for (Tab tab : allTabs()) {
            Supplier<String> title = titles.get(tab);
            if (title == null) {
                continue;
            }
            String renamed = names.get(tab);
            String base = renamed != null ? renamed : title.get();
            int n = seen.merge(base, 1, Integer::sum);
            tab.setText(n == 1 ? base : base + " (" + n + ")");
        }
    }

    /**
     * A bell in a tab out of sight marks the tab until it is looked at; the flash alone happens where nobody is
     * looking.
     */
    private void markBell(Tab tab, TerminalView view) {
        view.setOnBell(() -> {
            if (!inView(tab)) {
                if (!tab.getStyleClass().contains(BELL)) {
                    tab.getStyleClass().add(BELL);
                }
            }
        });
    }

    /** Style class of a tab whose bell rang while it was not in view. */
    static final String BELL = "terminal-bell";

    /** Style class of a tab whose program posted a notification (OSC 9, OSC 777) while it was not in view. */
    static final String NOTIFIED = "terminal-notified";

    /**
     * A program's notification marks its tab when the tab is out of sight, and reaches the desktop unless the
     * terminal is the one in front — its tab selected, in a shown panel of the focused window — or the setting is
     * off. The view decides how often one may be posted; the rest only mark.
     */
    private void markNotifications(Tab tab, TerminalView view) {
        view.setOnNotify((title, body, post) -> {
            boolean inView = inView(tab);
            if (!inView && !tab.getStyleClass().contains(NOTIFIED)) {
                tab.getStyleClass().add(NOTIFIED);
            }
            if (!post || !config.notifications()) {
                return;
            }
            var scene = tab.getContent() == null ? null : tab.getContent().getScene();
            boolean focused = inView
                    && scene != null
                    && scene.getWindow() != null
                    && scene.getWindow().isFocused();
            if (!focused) {
                // Untitled, it is named after the tab, which is how the user knows which terminal spoke.
                DesktopNotifier.post(title.isEmpty() ? tab.getText() : title, body);
            }
        });
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
            remove(tab);
            return;
        }
        Label message = new Label(tr("terminal.connectionLost"));
        Button reconnect = new Button(tr("terminal.reconnect"));
        reconnect.setOnAction(e -> {
            if (startIn(tab)) {
                focusActive();
            }
        });
        HBox banner = UiZoom.spacing(new HBox(message, reconnect), 10);
        banner.getStyleClass().add("terminal-lost-banner");
        banner.setAlignment(Pos.CENTER_LEFT);
        // Above the text, taking rows from it, not over it: what the shell printed last (often the error) is
        // exactly what an overlay covered.
        StackPane content = (StackPane) tab.getContent();
        javafx.scene.layout.VBox column = new javafx.scene.layout.VBox(banner, view);
        javafx.scene.layout.VBox.setVgrow(view, javafx.scene.layout.Priority.ALWAYS);
        content.getChildren().setAll(column);
        if (inView(tab)) {
            Platform.runLater(reconnect::requestFocus);
        }
    }

    /** Whether {@code tab}'s connection was lost and it is offering to reconnect. */
    boolean showsReconnect(Tab tab) {
        return tab != null && tab.getContent().lookup(".terminal-lost-banner") != null;
    }

    /** The selected tab, or null. */
    Tab activeTab() {
        return tabs.getSelectionModel().getSelectedItem();
    }

    /** Closes the selected tab, asking first if its shell is running something. */
    void closeActive() {
        Tab tab = currentTab();
        if (tab != null) {
            requestClose(tab);
        }
    }

    /** Ends every shell, for the window closing. Asks nothing: the window has already decided. */
    void shutdown() {
        shuttingDown = true;
        tabs.getTabs().clear();
        for (Tab tab : List.copyOf(inEditor)) {
            inEditor.remove(tab);
            release(tab); // the window is closing: its editor tabs go with it
        }
    }

    private boolean shuttingDown;

    /** Run when the last tab goes (its shell exited, or it was closed); not when the window closes. */
    private Runnable onLastClosed = () -> {};

    void setOnLastClosed(Runnable onLastClosed) {
        this.onLastClosed = onLastClosed == null ? () -> {} : onLastClosed;
    }

    /** Whether there is a terminal tab, connected or still connecting. */
    boolean hasTerminals() {
        return !tabs.getTabs().isEmpty();
    }

    int terminalCount() {
        return tabs.getTabs().size();
    }

    /** The selected tab's view, or null. */
    @Nullable
    TerminalView activeView() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab == null ? null : views.get(tab);
    }

    /**
     * Gives the keyboard to the selected terminal, or to "New Terminal" when there is none. Decided when it runs,
     * not when asked: the last tab's removal asks before the empty state is shown, and focus then fell on whatever
     * came next in the window (a toolbar button, which Space would press).
     */
    void focusActive() {
        Platform.runLater(() -> {
            TerminalView view = activeView();
            Tab tab = activeTab();
            if (view != null) {
                view.requestFocus();
            } else if (tab != null && connecting.containsKey(tab)) {
                focusWhenStarted = tab; // its shell is still starting: the keyboard goes there once it has
            } else if (!hasTerminals() && emptyStart.isVisible()) {
                emptyStart.requestFocus();
            }
        });
    }

    /**
     * A tab whose shell was asked for the keyboard while it was still starting. Only an asked-for focus follows a
     * start: a shell started quietly (a restored window's) must not take the keyboard when it comes up.
     */
    private @Nullable Tab focusWhenStarted;

    private void focusIfAsked(Tab tab, TerminalView view) {
        if (focusWhenStarted == tab) {
            focusWhenStarted = null;
            if (inEditor.contains(tab) ? inView(tab) : tab.isSelected()) {
                view.requestFocus();
            }
        }
    }

    /**
     * The tool-window manager's way in, on every path that shows the window (stripe, float, jump, the next-window
     * command): focus goes to the terminal, not to the panel, which takes no keys.
     */
    @Override
    public void focusFirstItem() {
        focusActive();
    }

    /** Whether the keyboard is in this panel (a terminal, its find bar, a banner's button). */
    boolean hasFocusWithin() {
        var scene = getScene();
        var owner = scene == null ? null : scene.getFocusOwner();
        for (Node n = owner; n != null; n = n.getParent()) {
            if (n == this) return true;
        }
        return false;
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
        usePalette(colors == null ? fallbackPalette : TerminalPalettes.derive(colors));
    }

    /** Recolours every terminal, and those opened later, with {@code next}: the terminal's own colour scheme. */
    void usePalette(TerminalPalette next) {
        palette = next;
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

    /** Whether the bundled icon face has been asked for (see {@link #loadIconFont}). */
    private boolean iconFontRequested;

    /**
     * Registers the bundled Nerd Font symbols face off the FX thread when the first terminal is made, unless the
     * deferred font load already has: a terminal opened at startup would otherwise draw its icons in whatever the
     * system's fallback list holds until the next font change. Every terminal chooses its icon face again once it is in.
     */
    private void loadIconFont() {
        if (iconFontRequested || Fonts.allLoaded()) {
            return;
        }
        iconFontRequested = true;
        Fonts.loadFamilyAsync(Fonts.ICONS)
                .thenRun(() -> Platform.runLater(() -> {
                    views.values().forEach(TerminalView::iconFontsChanged);
                    connecting.values().forEach(TerminalView::iconFontsChanged);
                }));
    }

    private void configure(TerminalView view) {
        loadIconFont();
        view.setFontFamily(config.fontFamily());
        view.setFontSize(config.fontSize());
        view.setPreferredCursor(config.cursor());
        view.setBellEnabled(config.bell());
        view.setTypeAhead(config.typeAhead());
        // Option composes on macOS unless the user asked for Meta; Alt is Meta everywhere else (AltGr composes).
        view.setAltIsMeta(altIsMeta(MAC, config.macOptionIsMeta()));
        view.setPasteGuard(text -> !config.confirmMultilinePaste() || pasteGuard.test(text));
        view.setCopyOnSelect(config.copyOnSelect());
        view.setClipboardWrites(config.clipboardWrites());
        view.setScrollbackLines(config.scrollback());
        view.setMenuIcons(TerminalPanel::menuIcon);
        view.setMenuExtras(menuExtras);
        if (linkActions != null) { // null until the coordinator sets it
            view.setLinkActions(linkActions);
        }
    }

    /** Tabs whose remote shell is being asked what it runs; a second close request waits for that answer. */
    private final java.util.Set<Tab> asking = new java.util.HashSet<>();

    /**
     * Closes {@code tab} once it may: at once, or after asking when its shell is running something. A shell on
     * another machine is asked off the FX thread — over a slow link, or with the host's one-shot slots busy, that
     * question took up to tens of seconds, and the editor waited on it. The tab stays meanwhile.
     */
    void requestClose(Tab tab) {
        TerminalView view = views.get(tab);
        if (view == null || !config.confirmClose() || view.getSession() == null) {
            remove(tab);
            return;
        }
        if (programNames.containsKey(tab)) {
            // A program tab: its program is what closing ends, while it runs; once it ended there is nothing to ask.
            if (!programExits.containsKey(tab)
                    || closeGuard.confirm(tab.getText(), new ArrayList<>(List.of(programNames.get(tab))))) {
                remove(tab);
            }
            return;
        }
        long pid = view.getSession().pid();
        long remote = view.getSession().remotePid();
        if (pid > 0) {
            closeUnlessDeclined(tab, RunningProgram.in(pid));
            return;
        }
        String stored = dirs.getOrDefault(tab, "");
        Path dir = stored.isEmpty() ? null : Vfs.parseStorable(stored);
        if (remote <= 0 || dir == null) {
            remove(tab);
            return;
        }
        if (!asking.add(tab)) {
            return;
        }
        Thread.ofVirtual().name("terminal-close-ask").start(() -> {
            List<String> programs;
            try {
                programs = remotePrograms.apply(dir, remote);
            } catch (RuntimeException e) {
                programs = List.of();
            }
            List<String> answer = programs;
            Platform.runLater(() -> {
                asking.remove(tab);
                if (owns(tab) && views.get(tab) == view) {
                    closeUnlessDeclined(tab, answer);
                }
            });
        });
    }

    private void closeUnlessDeclined(Tab tab, List<String> programs) {
        if (programs.isEmpty() || closeGuard.confirm(tab.getText(), new ArrayList<>(programs))) {
            remove(tab);
        }
    }

    /**
     * The tabs whose shell or program is running something, as "title (program, …)", for the question a window
     * close or quit asks. A shell on another machine is asked in parallel with the others, all within
     * {@code remoteWait}; one that does not answer in time is left out.
     */
    List<String> busyTabs(java.time.Duration remoteWait) {
        List<String> busy = new ArrayList<>();
        Map<Tab, java.util.concurrent.CompletableFuture<List<String>>> remoteAnswers = new LinkedHashMap<>();
        for (Tab tab : allTabs()) {
            TerminalView view = views.get(tab);
            if (view == null || view.getSession() == null) {
                continue;
            }
            if (programNames.containsKey(tab)) {
                if (programExits.containsKey(tab)) {
                    busy.add(describe(tab, List.of(programNames.get(tab))));
                }
                continue;
            }
            long pid = view.getSession().pid();
            long remote = view.getSession().remotePid();
            String stored = dirs.getOrDefault(tab, "");
            Path dir = stored.isEmpty() ? null : Vfs.parseStorable(stored);
            if (pid > 0) {
                List<String> programs = RunningProgram.in(pid);
                if (!programs.isEmpty()) {
                    busy.add(describe(tab, programs));
                }
            } else if (remote > 0 && dir != null) {
                remoteAnswers.put(
                        tab,
                        java.util.concurrent.CompletableFuture.supplyAsync(
                                () -> remotePrograms.apply(dir, remote),
                                command -> Thread.ofVirtual().start(command)));
            }
        }
        long deadline = System.nanoTime() + remoteWait.toNanos();
        remoteAnswers.forEach((tab, answer) -> {
            try {
                List<String> programs = answer.get(
                        Math.max(0, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                if (!programs.isEmpty()) {
                    busy.add(describe(tab, programs));
                }
            } catch (Exception noAnswer) {
                // slow or gone: not asked about
            }
        });
        return busy;
    }

    private static String describe(Tab tab, List<String> programs) {
        String running = programs.size() <= RunningProgram.MAX_NAMED
                ? String.join(", ", programs)
                : String.join(", ", programs.subList(0, RunningProgram.MAX_NAMED)) + ", …";
        return tab.getText() + " (" + running + ")";
    }

    /** Whether a close of {@code tab} is waiting for its remote shell's answer; for tests. */
    boolean isAskingToClose(Tab tab) {
        return asking.contains(tab);
    }

    private void onClipboardKey(Tab tab, Node content, KeyEvent e) {
        TerminalView view = views.get(tab);
        if (view == null) {
            return;
        }
        // Typed in the find bar, these are the field's: Paste pastes the query, not into the shell.
        for (Node n = e.getTarget() instanceof Node t ? t : null; n != null && n != content; n = n.getParent()) {
            if (n == findBar) {
                return;
            }
        }
        // The strip's own keys (next tab, move tab) are the editor's for a terminal in the editor.
        if (!inEditor.contains(tab) && stripKey(e)) {
            e.consume();
            return;
        }
        if (TerminalKeys.isCopy(e, MAC)) {
            view.copySelection();
            e.consume();
        } else if (TerminalKeys.isPaste(e, MAC)) {
            view.paste();
            e.consume();
        } else if (TerminalKeys.isFind(e, MAC)) {
            find(tab);
            e.consume();
        } else if (TerminalKeys.isPreviousPrompt(e, MAC)) {
            view.previousPrompt();
            e.consume();
        } else if (TerminalKeys.isNextPrompt(e, MAC)) {
            view.nextPrompt();
            e.consume();
        }
    }

    /** Runs the strip's tab-switching and tab-moving chords; true when {@code e} was one. */
    private boolean stripKey(KeyEvent e) {
        if (TerminalKeys.isMoveTabRight(e)) {
            moveActive(1);
        } else if (TerminalKeys.isMoveTabLeft(e)) {
            moveActive(-1);
        } else if (TerminalKeys.isNextTab(e)) {
            selectNext(true);
        } else if (TerminalKeys.isPreviousTab(e)) {
            selectNext(false);
        } else {
            return false;
        }
        return true;
    }

    /** Every tab's started view, in tab order; for tests. */
    List<TerminalView> viewsForTest() {
        return List.copyOf(views.values());
    }

    // ---------------------------------------------------------------- tab menu, rename, reorder

    /** Names the user gave tabs; a named tab keeps its name whatever its shell sets as the title. */
    private final Map<Tab, String> names = new LinkedHashMap<>();

    /** True while a move rewrites the tab list, so its remove is not taken for a close. */
    private boolean reordering;

    /** Asks for a tab's new name: the current one, and where the answer goes. */
    private java.util.function.BiConsumer<String, Consumer<String>> renamer = (current, onName) -> {};

    void setRenamer(java.util.function.BiConsumer<String, Consumer<String>> renamer) {
        this.renamer = renamer == null ? (current, onName) -> {} : renamer;
    }

    /** Renames {@code tab}; a blank name gives it back its shell's title. */
    void rename(Tab tab, String name) {
        if (name == null || name.isBlank()) {
            names.remove(tab);
        } else {
            names.put(tab, name.strip());
        }
        refreshTitles();
    }

    /** Asks for a new name for the current tab ({@code terminal.rename}). */
    void renameActive() {
        Tab tab = currentTab();
        if (tab != null) {
            askRename(tab);
        }
    }

    /** Asks for a new name for {@code tab}. */
    void askRename(Tab tab) {
        renamer.accept(names.getOrDefault(tab, tab.getText()), name -> rename(tab, name));
    }

    /** Moves the selected tab one place left ({@code -1}) or right, keeping it selected. */
    void moveActive(int delta) {
        Tab tab = activeTab();
        if (tab != null) {
            moveTabBy(tab, delta);
        }
    }

    private void moveTabBy(Tab tab, int delta) {
        int from = tabs.getTabs().indexOf(tab);
        moveTab(from, from + delta);
    }

    /** Moves the tab at {@code from} to {@code to} (indices of the list without it), keeping it selected. */
    void moveTab(int from, int to) {
        int count = tabs.getTabs().size();
        if (from < 0 || from >= count || to < 0 || to >= count || from == to) {
            return;
        }
        reordering = true;
        try {
            Tab tab = tabs.getTabs().remove(from);
            tabs.getTabs().add(to, tab);
            tabs.getSelectionModel().select(tab);
        } finally {
            reordering = false;
        }
        refreshTitles();
        focusActive();
    }

    /** A tab's own menu (right-click on its header): the tab it acts on is the clicked one. */
    private javafx.scene.control.ContextMenu tabMenu(Tab tab) {
        MenuItem rename = new MenuItem(tr("terminal.tab.rename"), Icons.edit());
        rename.setOnAction(e -> askRename(tab));
        MenuItem newTab = new MenuItem(tr("terminal.new"), Icons.plus());
        newTab.setOnAction(e -> newTerminal());
        MenuItem close = new MenuItem(tr("menu.close"), Icons.closeTab());
        close.setOnAction(e -> requestClose(tab));
        MenuItem closeOthers = new MenuItem(tr("menu.closeOthers"), Icons.closeOtherTabs());
        closeOthers.setOnAction(e ->
                new ArrayList<>(tabs.getTabs()).stream().filter(t -> t != tab).forEach(this::requestClose));
        MenuItem closeRight = new MenuItem(tr("menu.closeRight"), Icons.closeTabsRight());
        closeRight.setOnAction(e -> {
            int at = tabs.getTabs().indexOf(tab);
            if (at >= 0) {
                new ArrayList<>(tabs.getTabs().subList(at + 1, tabs.getTabs().size())).forEach(this::requestClose);
            }
        });
        MenuItem moveLeft = new MenuItem(tr("terminal.tab.moveLeft"), Icons.chevronLeft());
        moveLeft.setOnAction(e -> moveTabBy(tab, -1));
        MenuItem moveRight = new MenuItem(tr("terminal.tab.moveRight"), Icons.chevronRight());
        moveRight.setOnAction(e -> moveTabBy(tab, 1));
        MenuItem toEditor = new MenuItem(tr("terminal.tab.moveToEditor"), Icons.openInTab());
        toEditor.setOnAction(e -> moveToEditor(tab));
        // Each row a command also does shows that command's chord: the shortcut for "this, on the selected tab"
        // (as the editor's tab menu does).
        MenuChords.tag(rename, "terminal.rename");
        MenuChords.tag(newTab, "terminal.new");
        MenuChords.tag(close, "terminal.close");
        MenuChords.tag(moveLeft, "terminal.moveTabLeft");
        MenuChords.tag(moveRight, "terminal.moveTabRight");
        MenuChords.tag(toEditor, "terminal.moveToEditor");
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(
                rename,
                newTab,
                new javafx.scene.control.SeparatorMenuItem(),
                close,
                closeOthers,
                closeRight,
                new javafx.scene.control.SeparatorMenuItem(),
                moveLeft,
                moveRight,
                toEditor);
        // Per show: how many tabs there are, and where this one sits, change after the menu is built.
        menu.setOnShowing(e -> {
            toEditor.setVisible(editorHost != null);
            int at = tabs.getTabs().indexOf(tab);
            int count = tabs.getTabs().size();
            closeOthers.setDisable(count < 2);
            closeRight.setDisable(at < 0 || at >= count - 1);
            moveLeft.setDisable(at <= 0);
            moveRight.setDisable(at < 0 || at >= count - 1);
        });
        return menu;
    }

    /** The tab being dragged (a Dragboard carries only serialisable data, and a Tab is not). */
    private @Nullable Tab draggedTab;

    private static final javafx.scene.input.DataFormat TAB_DRAG =
            new javafx.scene.input.DataFormat("application/x-editora-terminal-tab");

    private static final String TAB_DRAG_INSTALLED = "editora.terminalTabDrag";

    /**
     * Drag-to-reorder on every tab's whole header (JavaFX has none of its own). The header is JavaFX's node, found
     * from the tab's chip upwards once the skin has built it, so this retries a few pulses.
     */
    private void installTabDrag(int attempt) {
        int found = 0;
        for (Tab tab : tabs.getTabs()) {
            Node header = headerOf(tab);
            if (header == null) {
                continue;
            }
            found++;
            if (header.getProperties().putIfAbsent(TAB_DRAG_INSTALLED, Boolean.TRUE) == null) {
                installTabDrag(tab, header);
            }
        }
        if (found < tabs.getTabs().size() && attempt < 5 && getScene() != null) {
            Platform.runLater(() -> installTabDrag(attempt + 1));
        }
    }

    private static Node headerOf(Tab tab) {
        Node node = tab.getGraphic();
        while (node != null && !node.getStyleClass().contains("tab")) {
            node = node.getParent();
        }
        return node;
    }

    private void installTabDrag(Tab tab, Node header) {
        header.setOnDragDetected(e -> {
            if (tabs.getTabs().size() < 2) {
                return;
            }
            draggedTab = tab;
            var board = header.startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.put(TAB_DRAG, "tab"); // a marker: the tab itself is draggedTab
            board.setContent(content);
            board.setDragView(header.snapshot(null, null));
            e.consume();
        });
        header.setOnDragOver(e -> {
            if (draggedTab == null
                    || e.getGestureSource() == header
                    || !e.getDragboard().hasContent(TAB_DRAG)) {
                return;
            }
            e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
            e.consume();
        });
        header.setOnDragDropped(e -> {
            if (draggedTab == null || !e.getDragboard().hasContent(TAB_DRAG)) {
                return;
            }
            int from = tabs.getTabs().indexOf(draggedTab);
            int over = tabs.getTabs().indexOf(tab);
            if (from >= 0 && over >= 0) {
                boolean after = e.getX() > header.getBoundsInLocal().getWidth() / 2;
                moveTab(
                        from,
                        TerminalTabReorder.insertIndex(
                                from, over, after, tabs.getTabs().size()));
            }
            e.setDropCompleted(true);
            e.consume();
        });
        header.setOnDragDone(e -> draggedTab = null);
    }

    /** The tab pane; for tests. */
    TabPane tabsForTest() {
        return tabs;
    }

    /** Selects the next terminal tab to the right ({@code forward}) or left, wrapping. */
    void selectNext(boolean forward) {
        int count = tabs.getTabs().size();
        if (count < 2) {
            return;
        }
        int at = tabs.getSelectionModel().getSelectedIndex();
        tabs.getSelectionModel().select(Math.floorMod(at + (forward ? 1 : -1), count));
    }

    /** Opens the find bar over the selected tab, seeded with a one-line selection. */
    void find() {
        Tab tab = currentTab();
        if (tab != null) {
            find(tab);
        }
    }

    /** Opens the find bar over {@code tab}, seeded with a one-line selection. */
    void find(Tab tab) {
        TerminalView view = views.get(tab);
        if (view == null) {
            return;
        }
        placeFindBar(tab);
        findBar.openOrNext(view, view.selectedText()); // asked for again while open: the next match
    }

    /** The tab the find bar is over, or null. */
    private @Nullable Tab findTab;

    TerminalFindBar findBar() {
        return findBar;
    }

    /** Moves the find bar into {@code tab}'s content and points it at that tab's terminal. */
    private void placeFindBar(Tab tab) {
        if (findBar.getParent() instanceof StackPane old) {
            old.getChildren().remove(findBar);
        }
        TerminalView view = tab == null ? null : views.get(tab);
        findTab = view == null ? null : tab;
        if (view == null) {
            findBar.close();
            return;
        }
        StackPane content = (StackPane) tab.getContent();
        StackPane.setAlignment(findBar, Pos.TOP_RIGHT);
        UiZoom.follow(findBar, () -> StackPane.setMargin(findBar, UiZoom.insets(6, 18, 0, 0)));
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

    private static @Nullable Node menuIcon(String key) {
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
