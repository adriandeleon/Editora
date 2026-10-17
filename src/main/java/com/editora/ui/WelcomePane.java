package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.AppInfo;
import com.editora.command.ChordFormat;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.Project;
import com.editora.config.RecentFiles;
import com.editora.editor.TabContent;
import com.editora.vfs.RemoteConnection;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * The VSCode-style start page shown in the editor area at startup when there is no session to restore.
 *
 * <p>A real {@link TabContent}: the controller shows it in a dedicated, closeable "Welcome" tab (at startup
 * with no session, or via {@code view.welcome}), so the tab strip handles activation/switching/closing for
 * free. Closing the last tab does not bring it back; the editor area shows its own hint instead
 * ({@link EditorEmptyState}). Offers Start actions (reused commands), Recent Projects, Recent Files, saved
 * Remote Sites, a Customize block (keymap and theme) and Help links. Built fresh on each {@link #refresh()}
 * so Open-Folder / Clone honor the current Projects/Git toggles.
 *
 * <p>The page is one centered column capped at {@link #COLUMN_WIDTH}: a long path never widens it, it elides
 * from its start instead, so the file name (the part people scan for) always shows. Every px size is
 * multiplied by the text zoom, so zooming scales the layout and not only the words.
 */
public final class WelcomePane extends Region implements TabContent {

    /** Keep the Welcome page compact while leaving the full recent history one disclosure away. */
    static final int COLLAPSED_RECENT_LIMIT = 5;

    /** Widest the content column grows (at 100 % zoom); longer rows elide their folder text. */
    static final double COLUMN_WIDTH = 640;

    /** Narrowest the column gets before the page scrolls sideways (at 100 % zoom). */
    private static final double MIN_COLUMN_WIDTH = 340;

    /** Least space between a Start action's label and its key chord (at 100 % zoom). */
    private static final double KEY_GAP = 32;

    /** Outer margin around the centered content (also the scroll threshold for the horizontal bar). */
    private static final double MARGIN = 48;

    /** Digits 1–9 open the first nine entries of the recent lists. */
    static final int NUMBERED_ENTRIES = 9;

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private final RecentFiles recentFiles;
    private final Supplier<List<Project>> projects;
    private final Consumer<Path> onOpenRecent;
    private final Consumer<String> openUrl;
    private final BooleanSupplier projectsEnabled;
    private final BooleanSupplier gitEnabled;
    /** Saved SFTP sites (most-recent first); when empty the Remote Sites section is hidden. */
    private final Supplier<List<RemoteConnection>> remoteSites;
    /** Picking a site opens the connection form pre-filled for it. */
    private final Consumer<RemoteConnection> onConnectRemote;
    /** The build-commit footer line for dev builds; hidden while there is nothing to show (production, or a
     *  lookup that has not finished). One node, re-homed into each rebuilt footer. */
    private final Label commitLabel = new Label();

    /** Opens a project's window; null hides the Recent Projects section. */
    private Consumer<Project> onOpenProject;
    /** Asks before removing a file from the recent list; null hides the menu item. */
    private Consumer<Path> onRemoveRecent;
    /** The active keymap's and app theme's names for the Customize block; null hides it. */
    private Supplier<String> keymapName;

    private Supplier<String> themeName;
    /** The home directory, written as {@code ~} in folder text. */
    private Path home = homeDirectory();

    /** Text zoom (1.0 = 100 %); every px size on the page is multiplied by it. */
    private double zoom = 1.0;

    /** The entries digits 1–9 open, in page order (rebuilt by {@link #refresh()}). */
    private final List<Hyperlink> numbered = new ArrayList<>();

    private final VBox content = new VBox();
    /** Centers {@code content} and provides the padding; this is what the ScrollPane scrolls. */
    private final StackPane centerHost = new StackPane(content);
    /** Adds vertical/horizontal scrollbars when the content doesn't fit the viewport. */
    private final ScrollPane scroll = new ScrollPane(centerHost);

    public WelcomePane(
            CommandRegistry registry,
            KeymapManager keymap,
            RecentFiles recentFiles,
            Supplier<List<Project>> projects,
            Consumer<Path> onOpenRecent,
            Consumer<String> openUrl,
            BooleanSupplier projectsEnabled,
            BooleanSupplier gitEnabled,
            Supplier<List<RemoteConnection>> remoteSites,
            Consumer<RemoteConnection> onConnectRemote,
            String devCommit) {
        this(
                registry,
                keymap,
                recentFiles,
                projects,
                onOpenRecent,
                openUrl,
                projectsEnabled,
                gitEnabled,
                remoteSites,
                onConnectRemote,
                (CompletableFuture<String>) null);
        showDevCommit(devCommit);
    }

    /**
     * As above, but the build commit arrives later: {@code devCommit} is the (possibly still running) lookup,
     * or {@code null} for none. Finding the commit runs {@code git}, which must not happen on the FX thread
     * that builds this pane — so the footer line simply appears once the answer is in.
     */
    public WelcomePane(
            CommandRegistry registry,
            KeymapManager keymap,
            RecentFiles recentFiles,
            Supplier<List<Project>> projects,
            Consumer<Path> onOpenRecent,
            Consumer<String> openUrl,
            BooleanSupplier projectsEnabled,
            BooleanSupplier gitEnabled,
            Supplier<List<RemoteConnection>> remoteSites,
            Consumer<RemoteConnection> onConnectRemote,
            CompletableFuture<String> devCommit) {
        this.registry = registry;
        this.keymap = keymap;
        this.recentFiles = recentFiles;
        this.projects = projects;
        this.onOpenRecent = onOpenRecent;
        this.openUrl = openUrl;
        this.projectsEnabled = projectsEnabled;
        this.gitEnabled = gitEnabled;
        this.remoteSites = remoteSites;
        this.onConnectRemote = onConnectRemote;
        commitLabel.getStyleClass().add("welcome-footer");
        showDevCommit("");
        if (devCommit != null) {
            devCommit.thenAccept(commit -> Platform.runLater(() -> showDevCommit(commit)));
        }

        getStyleClass().add("welcome-pane");
        content.getStyleClass().add("welcome-content");
        content.setAlignment(Pos.TOP_LEFT);
        content.setFillWidth(true); // sections span the column, so a recent row can elide inside it

        centerHost.getStyleClass().add("welcome-scroll-content");
        centerHost.setAlignment(Pos.TOP_CENTER);
        centerHost.setPadding(new Insets(MARGIN));

        scroll.getStyleClass().add("welcome-scroll");
        scroll.setFitToWidth(true); // fill the viewport so the content stays centered; clamps to minWidth
        scroll.setFitToHeight(false); // let it grow taller than the viewport → vertical scrollbar
        getChildren().add(scroll);
        // 1–9 open the numbered recent entries; typed characters only, so chords and Enter are untouched.
        addEventHandler(KeyEvent.KEY_TYPED, this::onKeyTyped);
        refresh();
    }

    /** Wires the Recent Projects section: picking one opens (or focuses) that project's window. */
    public void setProjectOpener(Consumer<Project> onOpenProject) {
        this.onOpenProject = onOpenProject;
    }

    /** Wires "Remove from recent files" on a recent file's context menu (the caller confirms, then refreshes). */
    public void setRecentRemover(Consumer<Path> onRemoveRecent) {
        this.onRemoveRecent = onRemoveRecent;
    }

    /** Wires the Customize block: the active keymap's and app theme's names. */
    public void setCustomizeSource(Supplier<String> keymapName, Supplier<String> themeName) {
        this.keymapName = keymapName;
        this.themeName = themeName;
    }

    /** For tests: the directory written as {@code ~}. */
    void setHomeDirectory(Path home) {
        this.home = home;
    }

    // --- TabContent ---

    @Override
    public Node node() {
        return this;
    }

    /**
     * Scales the Welcome page to the editor text-zoom factor ({@code Settings.fontZoom}, 1.0 = 100%). The
     * {@code .welcome-*} font sizes are all {@code em}-relative, so setting the root's font size scales the text
     * (#540); the column, icons, logo and gaps are px, so the page is rebuilt with them multiplied by the zoom.
     */
    public void setFontScale(double zoom) {
        double z = Math.max(0.5, zoom);
        setStyle("-fx-font-size: " + z + "em;");
        if (z != this.zoom) {
            this.zoom = z;
            refresh();
        }
    }

    @Override
    public String title() {
        return tr("welcome.tab");
    }

    /** A small app logo for the Welcome tab header (null if the icon resource is missing). */
    @Override
    public Node icon() {
        var in = getClass().getResourceAsStream("/com/editora/icons/icon-16.png");
        if (in == null) {
            return null;
        }
        javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(new javafx.scene.image.Image(in));
        view.setFitWidth(14);
        view.setFitHeight(14);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    /**
     * Focus goes to the first recent entry (Enter opens it), or to New File when there is none, so the page
     * is usable from the keyboard without first tabbing into it. A programmatic focus draws no focus ring.
     */
    @Override
    public void requestFocus() {
        // The pane takes focus now; the entry can take it only once the scroll pane has laid it out (a tab
        // that was just added has not been), so hand it over after this pulse unless focus has moved on.
        super.requestFocus();
        if (focusFirstEntry()) {
            return;
        }
        Platform.runLater(() -> {
            if (getScene() != null && getScene().getFocusOwner() == this) {
                focusFirstEntry();
            }
        });
    }

    /** Whether this window's focus owner is this pane or inside it (true even while the window is inactive). */
    boolean ownsFocus() {
        Node owner = getScene() == null ? null : getScene().getFocusOwner();
        for (Node n = owner; n != null; n = n.getParent()) {
            if (n == this) {
                return true;
            }
        }
        return false;
    }

    private boolean focusFirstEntry() {
        Node target = numbered.isEmpty() ? content.lookup(".welcome-action") : numbered.getFirst();
        if (target == null || target.getScene() == null) {
            return false;
        }
        target.requestFocus();
        return target.getScene().getFocusOwner() == target; // isFocused() is false in an inactive window
    }

    /** Rebuilds every section (call before showing, so toggles/recents/projects are current). */
    public void refresh() {
        // Rebuilding removes the focused link from the scene, and focus would fall to the toolbar. Asks the
        // scene, not isFocusWithin(): a refresh driven from the Settings window runs while this window is not
        // focused, when no node in it reports focus.
        boolean hadFocus = ownsFocus();
        rebuild();
        if (hadFocus) {
            requestFocus();
        }
    }

    private void rebuild() {
        numbered.clear();
        content.setMaxWidth(COLUMN_WIDTH * zoom);
        content.setMinWidth(MIN_COLUMN_WIDTH * zoom);
        // Below this width the content can't fit, so fit-to-width stops shrinking and a horizontal bar shows.
        centerHost.setMinWidth(MIN_COLUMN_WIDTH * zoom + 2 * MARGIN);

        List<Path> files = shownRecentFiles();
        List<Project> recentProjects = shownProjects(files);
        List<RemoteConnection> sites = remoteSites == null ? List.of() : remoteSites.get();
        boolean firstRun = files.isEmpty() && recentProjects.isEmpty();

        List<Node> nodes = new ArrayList<>();
        nodes.add(header());
        nodes.add(section(tr("welcome.start")));
        nodes.add(startActions());
        if (firstRun) {
            addCustomize(nodes); // nothing to come back to yet: setting up is the next thing to do
        }
        if (!recentProjects.isEmpty()) {
            nodes.add(section(tr("welcome.recentProjects")));
            nodes.add(projectList(recentProjects));
        }
        nodes.add(section(tr("welcome.recentFiles")));
        nodes.add(recentList(files));
        if (!sites.isEmpty()) {
            nodes.add(section(tr("welcome.remote")));
            nodes.add(remoteList(sites));
        }
        if (!firstRun) {
            addCustomize(nodes);
        }
        nodes.add(section(tr("welcome.help")));
        nodes.add(helpLinks());
        nodes.add(footer());
        content.getChildren().setAll(nodes);
    }

    // --- Recent projects ---

    private List<Project> shownProjects(List<Path> recent) {
        if (onOpenProject == null || projects == null || !projectsEnabled.getAsBoolean()) {
            return List.of();
        }
        List<Project> all = projects.get().stream()
                .filter(p -> !p.root().isBlank() && !p.id().isEmpty())
                .filter(WelcomePane::rootExists)
                .toList();
        return WelcomeText.recentProjects(
                all, recent, file -> RecentProject.containing(file, all).orElse(null));
    }

    private static boolean rootExists(Project p) {
        try {
            Path root = p.rootPath();
            return !Vfs.isLocal(root) || java.nio.file.Files.isDirectory(root);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private VBox projectList(List<Project> shown) {
        VBox box = new VBox();
        box.getStyleClass().add("welcome-recent");
        addCollapsed(box, shown.stream().map(this::projectRow).toList());
        return box;
    }

    private Node projectRow(Project project) {
        Hyperlink link = new Hyperlink(project.name());
        link.getStyleClass().add("welcome-link");
        link.setOnAction(e -> onOpenProject.accept(project));
        Path root = project.rootPath();
        String rootText = Vfs.displayLabel(root);
        HBox row = recentRowBox(link, folderLabel(Vfs.isLocal(root) ? WelcomeText.dirText(root, home) : rootText));
        Tooltip.install(row, new Tooltip(rootText));
        row.setOnContextMenuRequested(e -> {
            menuFor(null, rootText).show(row, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return row;
    }

    // --- Recent files ---

    private List<Path> shownRecentFiles() {
        // Same filter as the toolbar dropdown: a file that has since been deleted must not be offered here
        // either. Remote entries are kept unchecked — see RecentFiles.showable.
        return recentFiles == null
                ? List.of()
                : RecentFiles.showable(recentFiles.getList(), Vfs::isLocal, java.nio.file.Files::exists);
    }

    private VBox recentList(List<Path> shown) {
        VBox box = new VBox();
        box.getStyleClass().add("welcome-recent");
        if (shown.isEmpty()) {
            Label empty = caption(tr("welcome.noRecent"));
            empty.setWrapText(true);
            box.getChildren().add(empty);
            return box;
        }
        List<Project> availableProjects = projects == null ? List.of() : projects.get();
        addCollapsed(
                box,
                shown.stream().map(path -> recentRow(path, availableProjects)).toList());
        return box;
    }

    private Node recentRow(Path path, List<Project> availableProjects) {
        Hyperlink link = new Hyperlink(path.getFileName().toString());
        link.getStyleClass().add("welcome-link");
        link.setOnAction(e -> onOpenRecent.accept(path));
        boolean local = Vfs.isLocal(path);
        Project project =
                local ? RecentProject.containing(path, availableProjects).orElse(null) : null;
        Label chip = null;
        if (project != null) {
            chip = chip(project.name());
        } else if (!local && Vfs.authorityOf(path) != null) {
            chip = chip(WelcomeText.withoutDefaultPort(Vfs.authorityOf(path))); // a remote file names its host
            chip.setGraphic(scaled(Icons.remote()));
        }
        Path projectRoot =
                project == null ? null : project.rootPath().toAbsolutePath().normalize();
        Label where = folderLabel(WelcomeText.folderText(path, projectRoot, local ? home : null));
        HBox row = chip == null ? recentRowBox(link, where) : recentRowBox(link, chip, where);
        String pathText = Vfs.displayLabel(path);
        Tooltip.install(row, new Tooltip(pathText));
        row.setOnContextMenuRequested(e -> {
            menuFor(path, pathText).show(row, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return row;
    }

    /** Copy Path, plus Remove from recent files for a recent file. */
    private ContextMenu menuFor(Path removable, String pathText) {
        ContextMenu menu = new ContextMenu();
        MenuItem copy = new MenuItem(tr("menu.copyPath"));
        copy.setGraphic(Icons.copy());
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(pathText);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        });
        menu.getItems().add(copy);
        if (removable != null && onRemoveRecent != null) {
            MenuItem remove = new MenuItem(tr("tooltip.removeRecent"));
            remove.setGraphic(Icons.trash());
            remove.setOnAction(e -> onRemoveRecent.accept(removable));
            menu.getItems().add(remove);
        }
        return menu;
    }

    /**
     * One recent row: an entry number (for the first nine), the name, an optional chip, and the folder. The
     * name and chip keep their full width; only the folder shrinks, eliding from its start.
     */
    private HBox recentRowBox(Hyperlink link, Node... rest) {
        link.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(8 * zoom);
        row.getStyleClass().add("welcome-recent-row");
        row.setAlignment(Pos.BASELINE_LEFT);
        if (numbered.size() < NUMBERED_ENTRIES) {
            numbered.add(link);
            Label number = new Label(String.valueOf(numbered.size()));
            number.getStyleClass().add("welcome-entry-number");
            number.setMinWidth(Region.USE_PREF_SIZE);
            link.setAccessibleHelp(tr("welcome.entryNumber", numbered.size()));
            row.getChildren().add(number);
        }
        row.getChildren().add(link);
        for (Node n : rest) {
            if (n.getStyleClass().contains("welcome-recent-dir")) {
                HBox.setHgrow(n, Priority.ALWAYS);
            } else if (n instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
            row.getChildren().add(n);
        }
        return row;
    }

    private static Label folderLabel(String text) {
        Label dir = new Label(text);
        dir.getStyleClass().add("welcome-recent-dir");
        dir.setMinWidth(0);
        dir.setMaxWidth(Double.MAX_VALUE);
        dir.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS); // keep the folder's end, the part that differs
        return dir;
    }

    private static Label chip(String text) {
        Label chip = new Label(text);
        chip.getStyleClass().add("recent-project-name");
        return chip;
    }

    /** Shows the first {@link #COLLAPSED_RECENT_LIMIT} rows, and the rest behind a "Show N more" toggle. */
    private void addCollapsed(VBox box, List<Node> rows) {
        rows.stream().limit(COLLAPSED_RECENT_LIMIT).forEach(box.getChildren()::add);
        if (rows.size() <= COLLAPSED_RECENT_LIMIT) {
            return;
        }
        List<Node> overflow = rows.subList(COLLAPSED_RECENT_LIMIT, rows.size());
        Button disclosure = new Button();
        disclosure.getStyleClass().addAll("flat", "welcome-recent-disclosure");
        disclosure.setContentDisplay(ContentDisplay.RIGHT);
        setRecentDisclosureState(disclosure, overflow.size(), false);
        disclosure.setOnAction(event -> {
            boolean expanding = !box.getChildren().contains(overflow.getFirst());
            if (expanding) {
                box.getChildren().addAll(box.getChildren().indexOf(disclosure), overflow);
            } else {
                box.getChildren().removeAll(overflow);
            }
            setRecentDisclosureState(disclosure, overflow.size(), expanding);
        });
        box.getChildren().add(disclosure);
    }

    private void setRecentDisclosureState(Button disclosure, int hiddenCount, boolean expanded) {
        String text = expanded ? tr("welcome.recent.showFewer") : tr("welcome.recent.showMore", hiddenCount);
        disclosure.setText(text);
        disclosure.setGraphic(scaled(expanded ? Icons.findPrevious() : Icons.findNext()));
        disclosure.setAccessibleText(text);
    }

    // --- Remote sites ---

    /** A quick-connect list of saved SFTP sites; clicking one opens the prefilled connection form. */
    private VBox remoteList(List<RemoteConnection> sites) {
        VBox box = new VBox();
        box.getStyleClass().add("welcome-recent");
        List<Node> rows = new ArrayList<>();
        for (RemoteConnection c : sites) {
            Hyperlink link = new Hyperlink(c.displayLabel());
            link.getStyleClass().add("welcome-link");
            link.setMinWidth(Region.USE_PREF_SIZE);
            link.setOnAction(e -> onConnectRemote.accept(c));
            HBox row = new HBox(8 * zoom, link);
            row.getStyleClass().add("welcome-recent-row");
            String detail = WelcomeText.siteDetail(c);
            if (!detail.isEmpty()) {
                Label where = folderLabel(detail);
                HBox.setHgrow(where, Priority.ALWAYS);
                row.getChildren().add(where);
            }
            row.setAlignment(Pos.BASELINE_LEFT);
            Tooltip.install(row, new Tooltip("sftp://" + c.id()));
            rows.add(row);
        }
        addCollapsed(box, rows);
        return box;
    }

    // --- Customize ---

    private void addCustomize(List<Node> nodes) {
        if (keymapName == null || themeName == null) {
            return;
        }
        nodes.add(section(tr("welcome.customize")));
        VBox box = new VBox(
                customizeRow(tr("welcome.keymap"), keymapName.get(), "keymap.select"),
                customizeRow(tr("welcome.theme"), themeName.get(), "theme.setAppTheme"));
        box.getStyleClass().add("welcome-recent");
        if (keymap != null
                && ChordFormat.styleFor(keymap.activeName(), KeymapManager.isMac()) == ChordFormat.Style.EMACS) {
            // Emacs notation is unfamiliar to most newcomers; say what the chords on this page mean.
            Label hint = caption(tr(KeymapManager.isMac() ? "welcome.notation.emacs.mac" : "welcome.notation.emacs"));
            hint.setWrapText(true);
            box.getChildren().add(hint);
        }
        nodes.add(box);
    }

    private Node customizeRow(String label, String value, String commandId) {
        Label name = new Label(label);
        name.getStyleClass().add("welcome-customize-label");
        name.setMinWidth(96 * zoom);
        Hyperlink link = new Hyperlink(value);
        link.getStyleClass().add("welcome-link");
        link.setAccessibleText(label + ": " + value);
        link.setOnAction(e -> registry.run(commandId));
        HBox row = new HBox(8 * zoom, name, link);
        row.setAlignment(Pos.BASELINE_LEFT);
        return row;
    }

    // --- Help + footer ---

    private Node helpLinks() {
        FlowPane row = new FlowPane(20 * zoom, 4);
        row.getStyleClass().add("welcome-help");
        row.getChildren()
                .addAll(
                        helpLink(tr("welcome.documentation"), () -> registry.run("help.documentation")),
                        helpLink(tr("welcome.whatsNew"), () -> openUrl.accept(AppInfo.releaseNotesUrl())),
                        helpLink(tr("welcome.doctor"), () -> registry.run("view.doctor")));
        return row;
    }

    private static Hyperlink helpLink(String text, Runnable action) {
        Hyperlink link = new Hyperlink(text);
        link.getStyleClass().add("welcome-link");
        link.setOnAction(e -> action.run());
        return link;
    }

    /** App version + home-page link, with the copyright + license beneath. */
    private Node footer() {
        Label version = new Label(tr("welcome.version", AppInfo.VERSION));
        version.getStyleClass().add("welcome-footer");
        Hyperlink home = new Hyperlink(AppInfo.HOMEPAGE.replaceFirst("^https?://", ""));
        home.getStyleClass().addAll("welcome-link", "welcome-footer-link");
        home.setOnAction(e -> openUrl.accept(AppInfo.HOMEPAGE));
        HBox line1 = new HBox(8, version, dotLabel(), home);
        line1.setAlignment(Pos.BASELINE_LEFT);

        Label legal = new Label(AppInfo.LICENSE); // license only here; the author/copyright lives in About
        legal.getStyleClass().add("welcome-footer");

        VBox box = new VBox(4, line1);
        // Build commit on its own line below the version/URL line — dev builds only (hidden otherwise).
        box.getChildren().add(commitLabel);
        box.getChildren().add(legal);
        box.getStyleClass().add("welcome-footer-row");
        return box;
    }

    /** Shows the dev-build commit line, or hides it (taking no space) when {@code commit} is blank. */
    private void showDevCommit(String commit) {
        boolean show = commit != null && !commit.isBlank();
        commitLabel.setText(show ? tr("about.commit", commit) : "");
        commitLabel.setVisible(show);
        commitLabel.setManaged(show);
    }

    private static Label dotLabel() {
        Label dot = new Label("·");
        dot.getStyleClass().add("welcome-footer");
        return dot;
    }

    /** App logo (if available) beside the title + tagline. */
    private Node header() {
        VBox titles = new VBox(2, title(AppInfo.NAME), caption(tr("welcome.tagline")));
        titles.setAlignment(Pos.CENTER_LEFT);
        Node logo = logo();
        if (logo == null) {
            return titles;
        }
        HBox box = new HBox(16 * zoom, logo, titles);
        box.getStyleClass().add("welcome-header");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private Node logo() {
        var in = getClass().getResourceAsStream("/com/editora/icons/icon-128.png");
        if (in == null) {
            return null;
        }
        javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(new javafx.scene.image.Image(in));
        view.setFitWidth(64 * zoom);
        view.setFitHeight(64 * zoom);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    // --- Start actions ---

    private VBox startActions() {
        VBox box = new VBox();
        box.getStyleClass().add("welcome-actions");
        box.setFillWidth(true);
        // As wide as the widest row (label + gap + chord), not the column, so chords sit near their labels.
        box.setMaxWidth(Region.USE_PREF_SIZE);
        box.getChildren().add(action(Icons.newFile(), tr("welcome.newFile"), "file.new", null));
        // Runs the Open dialog. Its own chord when bound; the Emacs keymap binds only find-file (C-x C-f),
        // which opens a file too, so that is the chord to learn there.
        box.getChildren().add(action(Icons.open(), tr("welcome.openFile"), "file.open", "file.find"));
        if (projectsEnabled.getAsBoolean()) {
            box.getChildren().add(action(Icons.openFolder(), tr("welcome.openFolder"), "project.open", null));
        }
        if (gitEnabled.getAsBoolean()) {
            box.getChildren().add(action(Icons.git(), tr("welcome.clone"), "git.clone", null));
        }
        // The Command Palette is always last (it's the gateway to everything else).
        box.getChildren().add(action(Icons.palette(), tr("command.palette.show"), "palette.show", null));
        return box;
    }

    /** {@code commandId} runs on click; its chord is shown, or {@code fallbackChordId}'s when it has none. */
    private Node action(Node icon, String text, String commandId, String fallbackChordId) {
        // Put the icon in a fixed-width column so every label starts at the same x, regardless of icon width.
        StackPane holder = new StackPane(scaled(icon));
        holder.setMinWidth(26 * zoom);
        holder.setPrefWidth(26 * zoom);
        holder.setMaxWidth(26 * zoom);
        holder.setAlignment(Pos.CENTER_LEFT);
        Hyperlink link = new Hyperlink(text);
        link.setGraphic(holder);
        link.getStyleClass().add("welcome-action");
        link.setMinWidth(Region.USE_PREF_SIZE);
        link.setOnAction(e -> registry.run(commandId));

        Region spacer = new Region();
        spacer.setMinWidth(KEY_GAP * zoom);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(link, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("welcome-action-row");
        String shortcut = shortcutFor(commandId);
        if (shortcut == null && fallbackChordId != null) {
            shortcut = shortcutFor(fallbackChordId);
        }
        if (shortcut != null) {
            Label key = new Label(shortcut);
            key.getStyleClass().add("welcome-key");
            key.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(key);
            link.setAccessibleHelp(shortcut);
        }
        // Clicking anywhere on the row triggers the action, not just the link text.
        row.setOnMouseClicked(e -> link.fire());
        return row;
    }

    /** The configured keybinding for {@code commandId} in the active keymap's notation, or null if unbound. */
    private String shortcutFor(String commandId) {
        return keymap == null ? null : keymap.displayChord(commandId);
    }

    // --- Keys ---

    private void onKeyTyped(KeyEvent e) {
        String ch = e.getCharacter();
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || ch == null || ch.length() != 1) {
            return;
        }
        int n = ch.charAt(0) - '0';
        if (n >= 1 && n <= numbered.size()) {
            numbered.get(n - 1).fire();
            e.consume();
        }
    }

    /** The entries digits 1–9 open, in order (for tests). */
    List<Hyperlink> numberedEntries() {
        return List.copyOf(numbered);
    }

    // --- Helpers ---

    /** An icon at the page's zoom. Scale does not change layout bounds, so callers size its cell themselves. */
    private Node scaled(Node icon) {
        icon.setScaleX(zoom);
        icon.setScaleY(zoom);
        return icon;
    }

    private static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("welcome-title");
        return l;
    }

    private static Label caption(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("welcome-caption");
        return l;
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("welcome-section");
        return l;
    }

    private static Path homeDirectory() {
        try {
            String h = System.getProperty("user.home");
            return h == null || h.isBlank() ? null : Path.of(h).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    protected void layoutChildren() {
        // The ScrollPane fills the pane; it centers + scrolls the content (see centerHost).
        scroll.resizeRelocate(0, 0, getWidth(), getHeight());
    }
}
