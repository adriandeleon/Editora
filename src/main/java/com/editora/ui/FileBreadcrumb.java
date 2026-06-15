package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
 * IntelliJ-style file navigation bar: shows the active file's absolute path as clickable segments.
 * Clicking a segment opens a dropdown of that folder's contents (sub-folders first, then files);
 * choosing a folder drills in — it becomes the new trailing crumb and the dropdown reopens on it —
 * and choosing a file opens it via the {@code onOpenFile} callback. Opening a file selects its tab,
 * which resyncs the bar to that file (see {@link #setActiveFile}).
 */
public class FileBreadcrumb extends StackPane {

    /** Single-line bar height (px); comfortably fits the 12px crumb text without clipping. */
    private static final double BAR_HEIGHT = 24;

    private final Consumer<Path> onOpenFile;
    /** Injected by MainController: reveal a crumb in the OS file manager. Args: (path, isDirectory). */
    private BiConsumer<Path, Boolean> onReveal;
    /** Injected by MainController: open a terminal at a crumb's folder. Args: (path, isDirectory). */
    private BiConsumer<Path, Boolean> onOpenTerminal;

    private final Breadcrumbs<Path> breadcrumbs = new Breadcrumbs<>();
    private final ScrollPane scroll = new ScrollPane(breadcrumbs);

    /** The crumb node for the trailing (leaf) segment, used to anchor a re-opened dropdown. */
    private Node leafCrumbNode;

    /** Crumb path → its button node, so a crumb-action click can anchor the dropdown on the clicked crumb.
     *  Rebuilt on each {@link #showPath}. (The AtlantaFX skin owns each crumb button's {@code onAction} —
     *  it overrides any handler the crumb factory sets — so clicks are caught via {@code onCrumbAction}.) */
    private final Map<Path, ButtonBase> crumbNodes = new HashMap<>();

    /** The bar is shown only when enabled (the user setting) and a file is active. */
    private boolean enabled;

    private Path currentFile;

    public FileBreadcrumb(Consumer<Path> onOpenFile) {
        this.onOpenFile = onOpenFile;
        getStyleClass().add("file-breadcrumb");

        breadcrumbs.setAutoNavigationEnabled(false);
        breadcrumbs.setCrumbFactory(crumbFactory());
        // The skin overrides each crumb button's onAction (to fire this event), so catch clicks here.
        breadcrumbs.setOnCrumbAction(this::onCrumbAction);

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

    /** Points the bar at {@code file} (shown as its absolute path), or clears it when {@code null}. */
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
     * Rebuilds the crumb trail as the cumulative segments of {@code path}, starting at the first real
     * segment — the filesystem root ("/" or "C:\\") is not shown as its own crumb.
     */
    private void showPath(Path path) {
        List<Path> cumulative = new ArrayList<>();
        Path acc = path.getRoot();
        for (Path seg : path) {
            acc = acc == null ? seg : acc.resolve(seg);
            cumulative.add(acc);
        }
        if (cumulative.isEmpty()) {
            cumulative.add(path);
        }
        crumbNodes.clear(); // rebuilt by the crumb factory as setSelectedCrumb lays out the new trail
        breadcrumbs.setSelectedCrumb(Breadcrumbs.buildTreeModel(cumulative.toArray(new Path[0])));
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

    private static String crumbLabel(Path p) {
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString();
    }

    private void showCrumbMenu(Path crumbPath, Node anchor) {
        boolean isDir = Files.isDirectory(crumbPath);
        Path dir = isDir ? crumbPath : crumbPath.getParent();
        if (dir == null) {
            return;
        }
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(p -> (Files.isDirectory(p) ? dirs : files).add(p));
        } catch (IOException | RuntimeException ex) {
            return; // unreadable directory — nothing to show
        }
        Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
        dirs.sort(byName);
        files.sort(byName);

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
            if (!dirs.isEmpty() || !files.isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem()); // divider before the folder listing
            }
        }
        for (Path d : dirs) {
            MenuItem mi = new MenuItem(d.getFileName().toString(), Icons.project());
            mi.setOnAction(e -> navigateInto(d, anchor));
            menu.getItems().add(mi);
        }
        for (Path f : files) {
            MenuItem mi = new MenuItem(f.getFileName().toString(), Icons.fileSheet());
            mi.setOnAction(e -> onOpenFile.accept(f));
            menu.getItems().add(mi);
        }
        if (!menu.getItems().isEmpty()) {
            // The bar sits at the bottom, so the menu drops upward from the crumb.
            menu.show(anchor, Side.TOP, 0, 0);
        }
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
