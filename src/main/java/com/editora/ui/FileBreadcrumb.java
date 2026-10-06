package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.StackPane;
import javafx.util.Callback;

import atlantafx.base.controls.Breadcrumbs;
import atlantafx.base.controls.Breadcrumbs.BreadCrumbActionEvent;
import atlantafx.base.controls.Breadcrumbs.BreadCrumbItem;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * IntelliJ-style file navigation bar: shows the active file's path as clickable segments (with the home
 * directory collapsed to a single {@code ~} crumb — see {@link BreadcrumbTrail}).
 * Clicking a segment opens a dropdown of that folder's contents (sub-folders first, then files);
 * choosing a folder drills in — it becomes the new trailing crumb and the dropdown reopens on it —
 * and choosing a file opens it via the {@code onOpenFile} callback. Opening a file selects its tab,
 * which resyncs the bar to that file (see {@link #setActiveFile}).
 */
public class FileBreadcrumb extends StackPane {

    /** Single-line bar height (px); comfortably fits the 12px crumb text without clipping. */
    private static final double BAR_HEIGHT = 24;

    private final Consumer<Path> onOpenFile;
    /** The window's open project root (null when none): a file inside it is shown from that folder down. */
    private final java.util.function.Supplier<Path> projectRoot;
    /** Injected by MainController: reveal a crumb in the OS file manager. Args: (path, isDirectory). */
    private BiConsumer<Path, Boolean> onReveal;
    /** Injected by MainController: open a terminal at a crumb's folder. Args: (path, isDirectory). */
    private BiConsumer<Path, Boolean> onOpenTerminal;

    private final Breadcrumbs<Path> breadcrumbs = new Breadcrumbs<>();

    /**
     * Vertically centres the crumbs in the fixed-height bar. {@code fitToHeight} stretches the scroll's
     * content to the viewport, but {@code Breadcrumbs}' skin lays its buttons out at the TOP of whatever
     * height it is given — so all of the bar's slack collected below the text, leaving the row visibly
     * bottom-heavy. A centring holder splits that slack evenly instead.
     */
    private final StackPane crumbHolder = new StackPane(breadcrumbs);

    private final ScrollPane scroll = new ScrollPane(crumbHolder);

    /** The crumb node for the trailing (leaf) segment, used to anchor a re-opened dropdown. */
    private Node leafCrumbNode;

    /** Crumb path → its button node, so a crumb-action click can anchor the dropdown on the clicked crumb.
     *  Rebuilt on each {@link #showPath}. (The AtlantaFX skin owns each crumb button's {@code onAction} —
     *  it overrides any handler the crumb factory sets — so clicks are caught via {@code onCrumbAction}.) */
    private final Map<Path, ButtonBase> crumbNodes = new HashMap<>();

    /** Crumb path → the text to show on it, so the collapsed home crumb reads "~" and not "adl". Rebuilt
     *  alongside {@link #crumbNodes} on each {@link #showPath}. */
    private final Map<Path, String> crumbLabels = new HashMap<>();

    /**
     * The user's home directory, collapsed to a single "~" crumb. Read once: it cannot change while the
     * process runs, and the trail is rebuilt on every tab switch.
     */
    private static final Path HOME = userHome();

    private static Path userHome() {
        try {
            String home = System.getProperty("user.home");
            return home == null || home.isBlank() ? null : Path.of(home);
        } catch (RuntimeException e) {
            return null; // no home, or an unparseable one: show the path in full
        }
    }

    /** The bar is shown only when enabled (the user setting) and a file is active. */
    private boolean enabled;

    private Path currentFile;

    public FileBreadcrumb(Consumer<Path> onOpenFile) {
        this(onOpenFile, () -> null);
    }

    public FileBreadcrumb(Consumer<Path> onOpenFile, java.util.function.Supplier<Path> projectRoot) {
        this.onOpenFile = onOpenFile;
        this.projectRoot = projectRoot;
        getStyleClass().add("file-breadcrumb");

        breadcrumbs.setAutoNavigationEnabled(false);
        breadcrumbs.setCrumbFactory(crumbFactory());
        // The skin overrides each crumb button's onAction (to fire this event), so catch clicks here.
        breadcrumbs.setOnCrumbAction(this::onCrumbAction);

        crumbHolder.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        crumbHolder.getStyleClass().add("file-breadcrumb-holder");
        scroll.setFitToHeight(true);
        scroll.setPannable(true);
        // No visible scrollbars — they'd make the bar taller than one text line. Long paths are
        // reached by panning/trackpad scroll, and setActiveFile auto-scrolls to show the file.
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("file-breadcrumb-scroll");
        scroll.setFocusTraversable(false); // no focus ring on the bar
        // Pin the bar to a single text line. AtlantaFX's crumb/divider nodes report a taller
        // preferred height than the 12px crumb text, so fix the height explicitly (fitToHeight
        // vertically centers the crumbs within it).
        scroll.setMinHeight(BAR_HEIGHT);
        scroll.setPrefHeight(BAR_HEIGHT);
        scroll.setMaxHeight(BAR_HEIGHT);
        setMinHeight(BAR_HEIGHT);
        setPrefHeight(BAR_HEIGHT);
        setMaxHeight(BAR_HEIGHT);
        getChildren().add(scroll);

        setVisible(false);
        setManaged(false);
    }

    /** Injects the "Reveal in File Manager" handler ({@code (path, isDirectory)}) for the crumb menu. */
    public void setOnReveal(BiConsumer<Path, Boolean> onReveal) {
        this.onReveal = onReveal;
    }

    /** Injects the "Open Terminal Here" handler ({@code (path, isDirectory)}) for the crumb menu. */
    public void setOnOpenTerminal(BiConsumer<Path, Boolean> onOpenTerminal) {
        this.onOpenTerminal = onOpenTerminal;
    }

    /** Enables/disables the bar (the user setting). Hidden entirely when disabled. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        updateVisibility();
    }

    /** Points the bar at {@code file} (shown as its home-collapsed path), or clears it when {@code null}. */
    public void setActiveFile(Path file) {
        this.currentFile = file;
        if (file != null) {
            showPath(file.toAbsolutePath());
        }
        updateVisibility();
    }

    private void updateVisibility() {
        boolean show = enabled && currentFile != null;
        setVisible(show);
        setManaged(show);
        if (show) {
            // Keep the trailing (file) crumb visible on long paths.
            Platform.runLater(() -> scroll.setHvalue(scroll.getHmax()));
        }
    }

    /**
     * Rebuilds the crumb trail from {@link BreadcrumbTrail}: the cumulative segments of {@code path}, with
     * the filesystem root ("/" or "C:\\") left out and the home directory collapsed to one {@code ~} crumb —
     * or, for a file inside the open project, starting at the project root.
     */
    private void showPath(Path path) {
        List<BreadcrumbTrail.Crumb> trail = BreadcrumbTrail.of(path, HOME, projectRootOrNull());
        crumbNodes.clear(); // rebuilt by the crumb factory as setSelectedCrumb lays out the new trail
        crumbLabels.clear();
        List<Path> cumulative = new ArrayList<>(trail.size());
        for (BreadcrumbTrail.Crumb crumb : trail) {
            cumulative.add(crumb.path());
            crumbLabels.put(crumb.path(), crumb.label());
        }
        breadcrumbs.setSelectedCrumb(Breadcrumbs.buildTreeModel(cumulative.toArray(new Path[0])));
    }

    private Path projectRootOrNull() {
        try {
            Path root = projectRoot.get();
            return root == null ? null : root.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null; // an unresolvable root just means the full trail
        }
    }

    private Callback<BreadCrumbItem<Path>, ButtonBase> crumbFactory() {
        return item -> {
            Path p = item.getValue();
            Hyperlink link = new Hyperlink(crumbLabel(p));
            link.getStyleClass().add("file-breadcrumb-crumb");
            crumbNodes.put(p, link); // anchor lookup for onCrumbAction (the skin owns the button's onAction)
            if (item.isLast()) {
                leafCrumbNode = link;
            }
            return link;
        };
    }

    /** A crumb was clicked (the skin fires this regardless of auto-navigation): drop its dropdown. */
    private void onCrumbAction(BreadCrumbActionEvent<Path> e) {
        Path p = e.getSelectedCrumb().getValue();
        ButtonBase crumbButton = crumbNodes.get(p);
        Node anchor = crumbButton != null ? crumbButton : breadcrumbs;
        if (anchor instanceof Hyperlink link) {
            link.setVisited(false); // don't leave the crumb showing the "visited" link color
        }
        showCrumbMenu(p, anchor);
    }

    /** The trail's label for {@code p} ("~" for the collapsed home crumb), else its plain file name. */
    private String crumbLabel(Path p) {
        return crumbLabels.getOrDefault(p, BreadcrumbTrail.label(p));
    }

    /**
     * Opens the dropdown for a crumb. What it lists is read on a worker — a {@code readdir} plus a
     * {@code stat} per entry, which on a large or slow folder used to hold the FX thread from the click
     * until the menu appeared — and the menu is shown when the read lands, unless another crumb was clicked
     * meanwhile.
     */
    private void showCrumbMenu(Path crumbPath, Node anchor) {
        long generation = ++menuGeneration;
        java.util.function.BiFunction<Path, Boolean, DirectoryListing> reader = directoryReader;
        Thread.startVirtualThread(() -> {
            boolean isDir = Files.isDirectory(crumbPath);
            Path dir = isDir ? crumbPath : crumbPath.getParent();
            DirectoryListing listing = dir == null ? DirectoryListing.UNREADABLE : reader.apply(dir, false);
            Platform.runLater(() -> {
                if (generation == menuGeneration && listing.readable()) {
                    showCrumbMenu(crumbPath, isDir, listing, anchor);
                } // else: superseded, or an unreadable directory — nothing to show
            });
        });
    }

    private long menuGeneration;

    /** Reads a directory off the FX thread; replaceable so a test can hold or count the read. */
    volatile java.util.function.BiFunction<Path, Boolean, DirectoryListing> directoryReader = DirectoryListing::read;

    /** The menu most recently shown, for tests. */
    ContextMenu lastMenuForTest;

    private void showCrumbMenu(Path crumbPath, boolean isDir, DirectoryListing listing, Node anchor) {
        List<Path> entries = listing.entries(); // folders first, then files — one sequence for paging
        ContextMenu menu = new ContextMenu();
        // Reveal / Open Terminal act on this crumb (local files only — meaningless over SFTP).
        if ((onReveal != null || onOpenTerminal != null) && Vfs.isLocal(crumbPath)) {
            if (onReveal != null) {
                MenuItem reveal = new MenuItem(tr("menu.revealInFileManager"), Icons.revealInFiles());
                reveal.setOnAction(e -> onReveal.accept(crumbPath, isDir));
                menu.getItems().add(reveal);
            }
            if (onOpenTerminal != null) {
                MenuItem terminal = new MenuItem(tr("menu.openTerminal"), Icons.terminal());
                terminal.setOnAction(e -> onOpenTerminal.accept(crumbPath, isDir));
                menu.getItems().add(terminal);
            }
            if (!entries.isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem()); // divider before the folder listing
            }
        }
        List<int[]> pages = BreadcrumbPages.pages(entries.size(), BreadcrumbPages.PAGE_SIZE);
        if (pages.isEmpty()) {
            // Short listing: flat, exactly as before.
            for (Path p : entries) {
                menu.getItems().add(entryItem(p, listing.isDirectory(p), anchor));
            }
        } else {
            for (int[] page : pages) {
                javafx.scene.control.Menu sub = new javafx.scene.control.Menu(BreadcrumbPages.label(
                        entries.get(page[0]).getFileName().toString(),
                        entries.get(page[1] - 1).getFileName().toString()));
                // A page's rows (an item and an icon each) are built when the page is opened. Built up
                // front, a folder of thousands of files paid for every row of every page to show a menu
                // of which the user opens one. The placeholder is what makes the submenu openable.
                sub.getItems().add(new MenuItem(""));
                int from = page[0];
                int to = page[1];
                sub.setOnShowing(e -> {
                    if (sub.getProperties().putIfAbsent("editora.pageBuilt", Boolean.TRUE) != null) {
                        return;
                    }
                    List<MenuItem> rows = new ArrayList<>(to - from);
                    for (int i = from; i < to; i++) {
                        rows.add(entryItem(entries.get(i), listing.isDirectory(entries.get(i)), anchor));
                    }
                    sub.getItems().setAll(rows);
                });
                menu.getItems().add(sub);
            }
        }
        lastMenuForTest = menu;
        if (!menu.getItems().isEmpty()) {
            // The bar sits under the editor, so the menu drops upward from the crumb.
            menu.show(anchor, Side.TOP, 0, 0);
        }
    }

    /**
     * One listing row: a folder drills in, a file opens. Icons match the Project tool window
     * ({@link FileIcons#boxed} folder + per-type file glyphs).
     */
    private MenuItem entryItem(Path p, boolean directory, Node anchor) {
        String name = p.getFileName().toString();
        if (directory) {
            MenuItem mi = new MenuItem(name, FileIcons.boxed(Icons.project()));
            mi.setOnAction(e -> navigateInto(p, anchor));
            return mi;
        }
        MenuItem mi = new MenuItem(name, FileIcons.forFileName(name));
        mi.setOnAction(e -> onOpenFile.accept(p));
        return mi;
    }

    /** Drill into {@code folder}: it becomes the trailing crumb and the dropdown reopens on it. */
    private void navigateInto(Path folder, Node fallbackAnchor) {
        showPath(folder);
        Platform.runLater(() -> {
            scroll.setHvalue(scroll.getHmax());
            showCrumbMenu(folder, leafCrumbNode != null ? leafCrumbNode : fallbackAnchor);
        });
    }
}
