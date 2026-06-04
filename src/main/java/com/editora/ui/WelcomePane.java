package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.AppInfo;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import com.editora.config.RecentFiles;
import com.editora.editor.TabContent;

/**
 * The VSCode-style empty state shown in the editor area when no file tabs are open (startup with no
 * session to restore, or after the last tab is closed) — replacing the old empty "Untitled" buffer.
 *
 * <p>A real {@link TabContent}: the controller shows it in a dedicated, closeable "Welcome" tab (at
 * startup with no session, via {@code view.welcome}, or by reopening), so the tab strip handles
 * activation/switching/closing for free. Offers Start actions (reused commands) and a Recent-files
 * list. Built fresh on each {@link #refresh()} so Open-Folder / Clone honor the current Projects/Git
 * toggles.
 */
public final class WelcomePane extends Region implements TabContent {

    /** Width of the Start-actions column, so keybinding labels right-align in a tidy column. */
    private static final double ACTIONS_WIDTH = 380;

    private final CommandRegistry registry;
    private final KeymapManager keymap;
    private final RecentFiles recentFiles;
    private final Consumer<Path> onOpenRecent;
    private final Consumer<String> openUrl;
    private final BooleanSupplier projectsEnabled;
    private final BooleanSupplier gitEnabled;
    /** Short build commit shown in the footer for dev builds; "" hides it (production). */
    private final String devCommit;

    /** Outer margin around the centered content (also the scroll threshold for the horizontal bar). */
    private static final double MARGIN = 48;

    private final VBox content = new VBox();
    /** Centers {@code content} and provides the padding; this is what the ScrollPane scrolls. */
    private final StackPane centerHost = new StackPane(content);
    /** Adds vertical/horizontal scrollbars when the content doesn't fit the viewport. */
    private final ScrollPane scroll = new ScrollPane(centerHost);

    public WelcomePane(CommandRegistry registry, KeymapManager keymap, RecentFiles recentFiles,
                       Consumer<Path> onOpenRecent, Consumer<String> openUrl,
                       BooleanSupplier projectsEnabled, BooleanSupplier gitEnabled, String devCommit) {
        this.registry = registry;
        this.keymap = keymap;
        this.recentFiles = recentFiles;
        this.onOpenRecent = onOpenRecent;
        this.openUrl = openUrl;
        this.projectsEnabled = projectsEnabled;
        this.gitEnabled = gitEnabled;
        this.devCommit = devCommit == null ? "" : devCommit;

        getStyleClass().add("welcome-pane");
        content.getStyleClass().add("welcome-content");
        content.setAlignment(Pos.TOP_LEFT);
        content.setFillWidth(false);
        content.setMaxWidth(Region.USE_PREF_SIZE); // keep its natural width so it can be centered, not stretched

        centerHost.getStyleClass().add("welcome-scroll-content");
        centerHost.setAlignment(Pos.TOP_CENTER);
        centerHost.setPadding(new Insets(MARGIN));
        // Below this width the content can't fit, so fit-to-width stops shrinking and a horizontal bar shows.
        centerHost.setMinWidth(ACTIONS_WIDTH + 2 * MARGIN);

        scroll.getStyleClass().add("welcome-scroll");
        scroll.setFitToWidth(true);   // fill the viewport so the content stays centered; clamps to minWidth
        scroll.setFitToHeight(false); // let it grow taller than the viewport → vertical scrollbar
        getChildren().add(scroll);
        refresh();
    }

    // --- TabContent ---

    @Override
    public Node node() {
        return this;
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

    /** Rebuilds the Start actions + Recent list (call before showing, so toggles/recents are current). */
    public void refresh() {
        content.getChildren().setAll(
                header(),
                section(tr("welcome.start")),
                startActions(),
                section(tr("welcome.recent")),
                recentList(),
                footer());
    }

    /** App version + home-page link, with the copyright + license beneath. */
    private Node footer() {
        Label version = new Label(tr("welcome.version", AppInfo.VERSION));
        version.getStyleClass().add("welcome-footer");
        Hyperlink home = new Hyperlink(AppInfo.HOMEPAGE.replaceFirst("^https?://", ""));
        home.getStyleClass().add("welcome-link");
        home.setOnAction(e -> openUrl.accept(AppInfo.HOMEPAGE));
        HBox line1 = new HBox(8, version, dotLabel(), home);
        line1.setAlignment(Pos.CENTER_LEFT);

        Label legal = new Label(AppInfo.LICENSE); // license only here; the author/copyright lives in About
        legal.getStyleClass().add("welcome-footer");

        VBox box = new VBox(4, line1);
        // Build commit on its own line below the version/URL line — dev builds only ("" otherwise).
        if (!devCommit.isBlank()) {
            Label commit = new Label(tr("about.commit", devCommit));
            commit.getStyleClass().add("welcome-footer");
            box.getChildren().add(commit);
        }
        box.getChildren().add(legal);
        box.getStyleClass().add("welcome-footer-row");
        return box;
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
        HBox box = new HBox(16, logo, titles);
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
        view.setFitWidth(64);
        view.setFitHeight(64);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }

    private VBox startActions() {
        VBox box = new VBox();
        box.getStyleClass().add("welcome-actions");
        box.setFillWidth(true);
        box.setMinWidth(ACTIONS_WIDTH);
        box.setPrefWidth(ACTIONS_WIDTH);
        box.setMaxWidth(ACTIONS_WIDTH);
        box.getChildren().add(action(Icons.newFile(), tr("welcome.newFile"), "file.new"));
        // Opens a file dialog (file.open), but show the familiar "find file" keybinding (C-x C-f).
        box.getChildren().add(action(Icons.open(), tr("welcome.openFile"), "file.open", "file.find"));
        if (projectsEnabled.getAsBoolean()) {
            box.getChildren().add(action(Icons.openFolder(), tr("welcome.openFolder"), "project.open"));
        }
        if (gitEnabled.getAsBoolean()) {
            box.getChildren().add(action(Icons.git(), tr("welcome.clone"), "git.clone"));
        }
        // The Command Palette is always last (it's the gateway to everything else).
        box.getChildren().add(action(Icons.palette(), tr("command.palette.show"), "palette.show"));
        return box;
    }

    private VBox recentList() {
        VBox box = new VBox();
        box.getStyleClass().add("welcome-recent");
        if (recentFiles == null || recentFiles.getList().isEmpty()) {
            box.getChildren().add(caption(tr("welcome.noRecent")));
            return box;
        }
        for (Path p : recentFiles.getList()) {
            Hyperlink link = new Hyperlink(p.getFileName().toString());
            link.getStyleClass().add("welcome-link");
            Label dir = new Label(parentText(p));
            dir.getStyleClass().add("welcome-recent-dir");
            HBox row = new HBox(8, link, dir);
            row.setAlignment(Pos.BASELINE_LEFT);
            Tooltip.install(row, new Tooltip(p.toString()));
            link.setOnAction(e -> onOpenRecent.accept(p));
            box.getChildren().add(row);
        }
        return box;
    }

    private Node action(Node icon, String text, String commandId) {
        return action(icon, text, commandId, commandId);
    }

    /** {@code commandId} runs on click; the keybinding shown is {@code shortcutCommandId}'s (often the same). */
    private Node action(Node icon, String text, String commandId, String shortcutCommandId) {
        // Put the icon in a fixed-width column so every label starts at the same x, regardless of icon width.
        javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane(icon);
        holder.setMinWidth(26);
        holder.setPrefWidth(26);
        holder.setMaxWidth(26);
        holder.setAlignment(Pos.CENTER_LEFT);
        Hyperlink link = new Hyperlink(text);
        link.setGraphic(holder);
        link.getStyleClass().add("welcome-action");
        link.setOnAction(e -> registry.run(commandId));

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox row = new HBox(link, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("welcome-action-row");
        String shortcut = shortcutFor(shortcutCommandId); // the configured keybinding, if any
        if (shortcut != null) {
            Label key = new Label(shortcut);
            key.getStyleClass().add("welcome-key");
            row.getChildren().add(key);
        }
        // Clicking anywhere on the row triggers the action, not just the link text.
        row.setOnMouseClicked(e -> link.fire());
        return row;
    }

    /** The configured keybinding chord for {@code commandId} (shortest), or null if unbound. */
    private String shortcutFor(String commandId) {
        if (keymap == null) {
            return null;
        }
        String best = null;
        for (var e : keymap.bindings().entrySet()) {
            if (!commandId.equals(e.getValue())) {
                continue;
            }
            String chord = e.getKey();
            if (best == null || chord.length() < best.length()) {
                best = chord;
            }
        }
        return best;
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

    private static String parentText(Path p) {
        Path parent = p.getParent();
        return parent == null ? "" : parent.toString();
    }

    @Override
    protected void layoutChildren() {
        // The ScrollPane fills the pane; it centers + scrolls the content (see centerHost).
        scroll.resizeRelocate(0, 0, getWidth(), getHeight());
    }
}
