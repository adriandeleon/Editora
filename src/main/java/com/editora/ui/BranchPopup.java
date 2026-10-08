package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.editora.search.FuzzyMatch;

import static com.editora.i18n.Messages.tr;

/**
 * IntelliJ-style branch dropdown: a search field over a sectioned list — the <em>actions</em> (New Branch,
 * Pull, Fetch, Push, Stash, Commit…), the <em>Local</em> branches (the current one first), then the
 * <em>Remote</em> branches. The list is tall enough that the actions do not push the current branch below
 * the fold, and it opens with the current branch selected, so Enter straight away changes nothing. Typing filters
 * branches <em>and</em> actions together; ↑/↓ navigate (wrapping), Enter activates, Esc closes. A row is
 * activated by a click on that row only. Anchored just above the status-bar branch segment.
 *
 * <p>Each section collapses to its header: a click on the header, Enter on it, or ←/→ while it is
 * selected. The headers are therefore stops for ↑/↓ — but only while nothing is typed: a search looks
 * through every section, collapsed or not, and its headers are plain labels again. The owner is told the
 * collapsed set ({@link #setOnCollapsedSectionsChanged}) so it can outlive the popup.
 *
 * <p>Pure view: the owner supplies the branch lists and the action/checkout callbacks via
 * {@link #show}. Modeled on {@link QuickOpen} (popup + filtered {@link ListView}).
 */
public final class BranchPopup {

    /**
     * One of the popup's command rows.
     *
     * <p>{@code commandId} is carried so the row can take its glyph from {@link MenuBarIcons} — the same
     * table the VCS menu draws from — rather than naming a glyph here. The two surfaces offer the same
     * actions, so picking icons independently would let them drift apart for no reason.
     */
    public record MenuAction(String label, String commandId, Runnable run) {}

    /** The branch a row stands for, as handed to the owner when it is asked for that row's actions. */
    public record BranchRef(String name, boolean remote, boolean current, String upstream, boolean gone) {}

    /**
     * One entry of a branch row's secondary menu (rename, merge, delete…). {@code danger} marks an action
     * that destroys something; {@link #SEPARATOR} draws a line between groups.
     */
    public record RowAction(String label, boolean danger, Runnable run) {
        public static final RowAction SEPARATOR = new RowAction("", false, () -> {});
    }

    private sealed interface Row permits Header, ActionRow, BranchRow {}

    /**
     * A section label. {@code key} names the section in the collapsed set and {@code count} is how many
     * rows it holds, shown while they are folded away.
     */
    private record Header(String title, String key, int count) implements Row {}

    private record ActionRow(String label, String accel, String commandId, Runnable run) implements Row {}
    /** A branch row: {@code upstream}/{@code ahead}/{@code behind} are the tracking info (locals only). */
    private record BranchRow(
            String name,
            boolean remote,
            boolean current,
            String upstream,
            int ahead,
            int behind,
            boolean gone,
            Runnable run)
            implements Row {}

    private final Label titleLabel = new Label(tr("branchpopup.title"));
    /** Remote URL shown in the header (origin's), ellipsized; empty when there's no remote. */
    private final Label remoteUrlLabel = new Label();

    private final TextField search = new TextField();
    private final ListView<Row> list = new ListView<>();
    /** The key legend under the list; rebuilt from the live keymap each time the popup is shown. */
    private final Label hint = new Label();

    private final ObservableList<Row> items = FXCollections.observableArrayList();
    private List<Row> all = List.of();

    /** Wide enough for a long branch name beside its upstream without a horizontal scroll bar. */
    private static final double WIDTH = 640;

    /** Eighteen menu-height rows: the actions, and a screenful of branches under them. */
    private static final double LIST_HEIGHT = 520;

    /** Section keys: the collapsed set is made of these ({@code remote:<name>} with several remotes). */
    static final String SECTION_LOCAL = "local";

    static final String SECTION_REMOTE = "remote";
    static final String SECTION_ACTIONS = "actions";

    /** The sections shown as their header only. Kept across shows; the owner may seed and persist it. */
    private final java.util.Set<String> collapsed = new java.util.LinkedHashSet<>();

    private Consumer<java.util.Set<String>> onCollapsedChanged = keys -> {};

    /** The search text the list is filtered by (lower-cased, trimmed); empty when nothing is typed. */
    private String query = "";

    /** Shared in-scene overlay host (injected by MainController) + the card it shows. */
    private OverlayHost overlayHost;

    private VBox content;
    private boolean showing;

    /** Supplies a branch row's secondary actions; the rows carry no menu while this answers nothing. */
    private java.util.function.Function<BranchRef, List<RowAction>> rowActions = branch -> List.of();

    /** The secondary menu that is up, so a second request (or hiding the dropdown) can take it down. */
    private javafx.scene.control.ContextMenu rowMenu;

    public BranchPopup() {
        search.setPromptText(tr("branchpopup.searchPrompt"));
        list.setItems(items);
        list.setPrefHeight(LIST_HEIGHT);
        list.setFocusTraversable(false);
        // Activation by mouse belongs to the cell (see RowCell): a handler on the list fired for a click
        // anywhere inside it — a section header, the empty space under the rows — and ran whichever row
        // happened to be selected, i.e. checked out a branch nobody clicked.
        list.setCellFactory(v -> new RowCell());

        search.textProperty().addListener((o, a, b) -> filter(b));
        search.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // Emacs caret movement + basic editing (list navigation stays with onKey, registered first).
        com.editora.command.TextInputKeymap.installShared(search);

        titleLabel.getStyleClass().add("palette-title");
        remoteUrlLabel.getStyleClass().add("branch-remote-url");
        remoteUrlLabel.setMaxWidth(Double.MAX_VALUE);
        remoteUrlLabel.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(remoteUrlLabel, Priority.ALWAYS);
        HBox header = new HBox(8, titleLabel, remoteUrlLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        hint.getStyleClass().add("palette-hint");
        content = new VBox(6, header, search, list, hint);
        content.getStyleClass().addAll("command-palette", "branch-popup");
        content.setPrefWidth(WIDTH);
        content.setMaxSize(WIDTH, Region.USE_PREF_SIZE); // hug content; don't stretch to fill the overlay
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE); // keep C-n/C-p for the picker
    }

    /** Injects the shared overlay host used to show the branch dropdown. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    /** Replaces the collapsed sections (keys as handed to {@link #setOnCollapsedSectionsChanged}). */
    public void setCollapsedSections(java.util.Collection<String> keys) {
        collapsed.clear();
        if (keys != null) {
            collapsed.addAll(keys);
        }
    }

    /** Called with the whole collapsed set each time the user folds or unfolds a section. */
    public void setOnCollapsedSectionsChanged(Consumer<java.util.Set<String>> listener) {
        onCollapsedChanged = listener == null ? keys -> {} : listener;
    }

    /** When the popup last hid — lets the status-bar click that auto-hid it act as a clean toggle. */
    private long lastHiddenAt;

    /** True if the popup auto-hid within the last 250 ms (i.e. from the same click that's reopening it). */
    public boolean justHidden() {
        return System.currentTimeMillis() - lastHiddenAt < 250;
    }

    /**
     * Populates and shows the popup anchored above {@code anchor}.
     *
     * @param current          current branch name (shown first under Local, marked, and not re-checked out)
     * @param onCheckoutLocal  invoked with a local branch name to switch to it
     * @param onCheckoutRemote invoked with a remote branch short-name (e.g. {@code origin/foo}) to check it out
     */
    public void show(
            Window owner,
            Node anchor,
            String current,
            List<com.editora.git.GitService.BranchInfo> local,
            List<String> remote,
            String remoteUrl,
            List<MenuAction> actions,
            Consumer<String> onCheckoutLocal,
            Consumer<String> onCheckoutRemote) {
        show(
                owner,
                anchor,
                current,
                local,
                remote,
                List.of(),
                remoteUrl,
                actions,
                onCheckoutLocal,
                onCheckoutRemote,
                branch -> List.of());
    }

    /**
     * As above, with the remotes' names — more than one groups the remote branches under a header per
     * remote — and the supplier of each branch row's secondary actions (shown from the row's "more" button,
     * a right-click, or the Menu key / Shift+F10 on the selected row).
     */
    public void show(
            Window owner,
            Node anchor,
            String current,
            List<com.editora.git.GitService.BranchInfo> local,
            List<String> remote,
            List<String> remoteNames,
            String remoteUrl,
            List<MenuAction> actions,
            Consumer<String> onCheckoutLocal,
            Consumer<String> onCheckoutRemote,
            java.util.function.Function<BranchRef, List<RowAction>> rowActions) {
        this.rowActions = rowActions == null ? branch -> List.of() : rowActions;
        List<Row> rows = new ArrayList<>();
        List<Row> section = new ArrayList<>();
        if (!actions.isEmpty()) {
            // The chord beside an action comes from the live keymap, never from the caller: a hardcoded
            // "C-x g" was only true of the Emacs keymap.
            var keymap = com.editora.command.TextInputKeymap.sharedKeymap();
            for (MenuAction a : actions) {
                String chord = keymap == null ? null : keymap.displayChord(a.commandId());
                section.add(new ActionRow(a.label(), chord == null ? "" : chord, a.commandId(), a.run()));
            }
            addSection(rows, tr("branchpopup.actions"), SECTION_ACTIONS, section);
        }
        List<com.editora.git.GitService.BranchInfo> locals = new ArrayList<>(local);
        locals.sort((x, y) -> {
            if (x.name().equals(current)) {
                return -1;
            }
            if (y.name().equals(current)) {
                return 1;
            }
            return x.name().compareToIgnoreCase(y.name());
        });
        for (var b : locals) {
            boolean cur = b.name().equals(current);
            section.add(new BranchRow(
                    b.name(),
                    false,
                    cur,
                    b.upstream(),
                    b.ahead(),
                    b.behind(),
                    b.gone(),
                    cur ? this::hide : () -> onCheckoutLocal.accept(b.name())));
        }
        addSection(rows, tr("branchpopup.local"), SECTION_LOCAL, section);
        for (var group : remoteGroups(remote, remoteNames).entrySet()) {
            String name = group.getKey();
            for (String b : group.getValue()) {
                section.add(new BranchRow(b, true, false, "", 0, 0, false, () -> onCheckoutRemote.accept(b)));
            }
            addSection(
                    rows,
                    name.isEmpty() ? tr("branchpopup.remote") : tr("branchpopup.remoteOf", name),
                    name.isEmpty() ? SECTION_REMOTE : SECTION_REMOTE + ":" + name,
                    section);
        }
        titleLabel.setText(tr("branchpopup.title"));
        // Never the raw URL: one stored as https://user:token@host would put the token on screen.
        String shownUrl = com.editora.git.GitFormat.displayRemoteUrl(remoteUrl);
        remoteUrlLabel.setText(shownUrl);
        remoteUrlLabel.setTooltip(shownUrl.isBlank() ? null : new Tooltip(shownUrl));
        all = rows;
        present(owner, anchor);
    }

    /** Appends a header and its rows to {@code rows}, then empties {@code section} for the next one. */
    private static void addSection(List<Row> rows, String title, String key, List<Row> section) {
        rows.add(new Header(title, key, section.size()));
        rows.addAll(section);
        section.clear();
    }

    /**
     * The remote branches as the sections they are listed in: one section (keyed {@code ""}, the plain
     * "Remote" header) while they all live on one remote, else one per remote in name order, each sorted.
     * Pure.
     */
    static java.util.Map<String, List<String>> remoteGroups(List<String> remote, List<String> remoteNames) {
        java.util.Map<String, List<String>> byRemote = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String branch : remote) {
            var split = com.editora.git.GitRemotes.split(branch, remoteNames);
            byRemote.computeIfAbsent(split == null ? "" : split.remote(), r -> new ArrayList<>())
                    .add(branch);
        }
        java.util.Map<String, List<String>> groups = new java.util.LinkedHashMap<>();
        if (byRemote.size() <= 1) {
            List<String> all = new ArrayList<>(remote);
            all.sort(String.CASE_INSENSITIVE_ORDER);
            if (!all.isEmpty()) {
                groups.put("", all);
            }
            return groups;
        }
        byRemote.forEach((name, branches) -> {
            branches.sort(String.CASE_INSENSITIVE_ORDER);
            groups.put(name, branches);
        });
        return groups;
    }

    /**
     * "No VCS" mode: the active file isn't under version control, so the dropdown offers only
     * "Clone Git repository…". Opened from the always-visible status-bar segment.
     */
    public void showNoVcs(Window owner, Node anchor, Runnable onClone) {
        titleLabel.setText(tr("branchpopup.noVcs"));
        remoteUrlLabel.setText("");
        remoteUrlLabel.setTooltip(null);
        all = List.of(new ActionRow(tr("branchpopup.clone"), "", "git.clone", onClone));
        present(owner, anchor);
    }

    /** Filters to the current items and shows the dropdown anchored just above {@code anchor}. */
    private void present(Window owner, Node anchor) {
        if (overlayHost == null) {
            return;
        }
        hint.setText(PickerKeys.legend(PickerKeys.hint("select", "↵"), tr("branchpopup.moreHint")));
        search.clear();
        filter("");
        showing = true;
        overlayHost.show(content, anchor, search::requestFocus, () -> {
            showing = false;
            lastHiddenAt = System.currentTimeMillis();
        });
    }

    public void hide() {
        hideRowMenu();
        if (overlayHost != null) {
            overlayHost.hide();
        }
    }

    public boolean isShown() {
        return showing;
    }

    // --- filtering + navigation ---

    private static String labelOf(Row r) {
        if (r instanceof ActionRow a) {
            return a.label();
        }
        if (r instanceof BranchRow b) {
            return b.name();
        }
        return "";
    }

    private void filter(String text) {
        query = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        rebuild();
        selectFirstSelectable();
    }

    /** Fills the list from {@link #all}: the rows matching the search, minus those of a folded section. */
    private void rebuild() {
        List<Row> out = new ArrayList<>();
        Header pending = null;
        boolean folded = false;
        for (Row r : all) {
            if (r instanceof Header h) {
                folded = isFolded(h);
                pending = folded ? null : h; // only emitted if a following row in its section matches
                if (folded) {
                    out.add(h);
                }
                continue;
            }
            if (!folded && (query.isEmpty() || FuzzyMatch.of(labelOf(r), query) != null)) {
                if (pending != null) {
                    out.add(pending);
                    pending = null;
                }
                out.add(r);
            }
        }
        items.setAll(out);
    }

    /**
     * Whether {@code header} folds and unfolds right now. Not during a search: its results come from every
     * section, so a fold would hide the branch that was typed for.
     */
    private boolean toggleable(Header header) {
        return query.isEmpty();
    }

    private boolean isFolded(Header header) {
        return toggleable(header) && collapsed.contains(header.key());
    }

    /** Folds or unfolds {@code header}'s section and leaves the selection on the header. */
    private void setFolded(Header header, boolean fold) {
        if (!toggleable(header) || fold == collapsed.contains(header.key())) {
            return;
        }
        if (fold) {
            collapsed.add(header.key());
        } else {
            collapsed.remove(header.key());
        }
        onCollapsedChanged.accept(new java.util.LinkedHashSet<>(collapsed));
        rebuild();
        list.getSelectionModel().select(items.indexOf(header));
    }

    /** Whether the cursor stops on {@code row}: every action and branch, and a header that folds. */
    private boolean selectable(Row row) {
        return !(row instanceof Header header) || toggleable(header);
    }

    private void selectFirstSelectable() {
        // With nothing typed, the current branch: the actions above it are a keystroke away, and Enter on a
        // just-opened dropdown must not run one. Else the first branch or action; a header is only the
        // first stop when everything is folded.
        for (int pass = query.isEmpty() ? 0 : 1; pass < 3; pass++) {
            for (int i = 0; i < items.size(); i++) {
                Row row = items.get(i);
                boolean wanted =
                        switch (pass) {
                            case 0 -> row instanceof BranchRow branch && branch.current();
                            case 1 -> !(row instanceof Header);
                            default -> selectable(row);
                        };
                if (wanted) {
                    list.getSelectionModel().select(i);
                    list.scrollTo(0); // from the top: the section header above the first row stays in view
                    return;
                }
            }
        }
        list.getSelectionModel().clearSelection();
    }

    private void onKey(KeyEvent e) {
        // The Menu key (and Shift+F10, its keyboard twin) opens the selected branch's secondary actions.
        if (e.getCode() == javafx.scene.input.KeyCode.CONTEXT_MENU
                || (e.getCode() == javafx.scene.input.KeyCode.F10 && e.isShiftDown())) {
            showRowMenuForSelection();
            e.consume();
            return;
        }
        // ←/→ fold and unfold the selected section, as in a tree. A header is only ever selected while the
        // search field is empty, so the caret has nowhere to go and loses nothing.
        boolean left = e.getCode() == javafx.scene.input.KeyCode.LEFT;
        if ((left || e.getCode() == javafx.scene.input.KeyCode.RIGHT)
                && !e.isShiftDown()
                && !e.isShortcutDown()
                && !e.isAltDown()
                && list.getSelectionModel().getSelectedItem() instanceof Header header) {
            setFolded(header, left);
            e.consume();
            return;
        }
        PickerKeys.Action action = PickerKeys.action(e);
        switch (action) {
            case CANCEL -> hide();
            case ACCEPT -> activate(list.getSelectionModel().getSelectedItem());
            default -> {
                if (!PickerKeys.navigate(list, action, this::selectable)) {
                    return;
                }
            }
        }
        e.consume();
    }

    private void activate(Row row) {
        if (row instanceof ActionRow a) {
            hide();
            a.run().run();
        } else if (row instanceof BranchRow b) {
            hide();
            b.run().run();
        } else if (row instanceof Header header) {
            setFolded(header, !collapsed.contains(header.key()));
        }
    }

    private static BranchRef refOf(BranchRow row) {
        return new BranchRef(row.name(), row.remote(), row.current(), row.upstream(), row.gone());
    }

    /** The secondary actions of the branch named {@code name} in the shown list; empty when it has none. */
    List<RowAction> rowActionsFor(String name) {
        for (Row row : all) {
            if (row instanceof BranchRow branch && branch.name().equals(name)) {
                return rowActions.apply(refOf(branch));
            }
        }
        return List.of();
    }

    /** Runs a secondary action the way a row is activated: the dropdown closes first. */
    void runRowAction(RowAction action) {
        hide();
        action.run().run();
    }

    private void hideRowMenu() {
        if (rowMenu != null) {
            rowMenu.hide();
            rowMenu = null;
        }
    }

    /** Opens the secondary menu of the selected row, under that row's cell. */
    private void showRowMenuForSelection() {
        int index = list.getSelectionModel().getSelectedIndex();
        if (index < 0 || !(items.get(index) instanceof BranchRow row)) {
            return;
        }
        Node anchor = list;
        for (Node node : list.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty() && cell.getIndex() == index) {
                anchor = cell;
                break;
            }
        }
        showRowMenu(row, anchor, javafx.geometry.Side.BOTTOM, 24, 0);
    }

    /** Builds and shows {@code row}'s secondary menu beside {@code anchor}; nothing when it has no actions. */
    private void showRowMenu(BranchRow row, Node anchor, javafx.geometry.Side side, double dx, double dy) {
        hideRowMenu();
        List<RowAction> actions = rowActions.apply(refOf(row));
        if (actions.isEmpty()) {
            return;
        }
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        for (RowAction action : actions) {
            if (action == RowAction.SEPARATOR) {
                menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
                continue;
            }
            javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(action.label());
            if (action.danger()) {
                item.getStyleClass().add("danger");
            }
            item.setOnAction(e -> runRowAction(action));
            menu.getItems().add(item);
        }
        rowMenu = menu;
        menu.show(anchor, side, dx, dy);
    }

    /** Width of the leading icon column — the menu bar's {@code ICON_COLUMN}, so both read alike. */
    private static final double ICON_COLUMN = 22;

    /** Side of the square a header's fold chevron is centred in. */
    private static final double CHEVRON_BOX = 12;

    private final class RowCell extends ListCell<Row> {
        RowCell() {
            // Only a primary click on this cell, and only while it shows a row that does something: an
            // action, a branch, or a header that folds.
            setOnMouseClicked(e -> {
                Row row = getItem();
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && row != null && selectable(row)) {
                    activate(row);
                    e.consume();
                }
            });
            // A right-click (or the platform's context-menu gesture) on a branch opens its secondary menu.
            setOnContextMenuRequested(e -> {
                if (!isEmpty() && getItem() instanceof BranchRow row) {
                    list.getSelectionModel().select(getIndex());
                    showRowMenu(row, this, javafx.geometry.Side.BOTTOM, e.getX(), e.getY() - getHeight());
                    e.consume();
                }
            });
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("branch-popup-header");
            // Cells are recycled: a cell that last showed a branch must not keep describing it while it
            // shows an action, a header or nothing.
            setTooltip(null);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setDisable(false);
                return;
            }
            if (item instanceof Header h) {
                getStyleClass().add("branch-popup-header");
                setText(null);
                setGraphic(header(h));
                setDisable(!toggleable(h)); // during a search: a plain, non-selectable section label
                if (toggleable(h)) {
                    setTooltip(new Tooltip(tr(isFolded(h) ? "branchpopup.expand" : "branchpopup.collapse", h.title())));
                }
            } else if (item instanceof ActionRow a) {
                setDisable(false);
                Label label = new Label(a.label());
                label.getStyleClass().add("menu-item-title");
                Label accel = new Label(a.accel() == null ? "" : a.accel());
                accel.getStyleClass().add("menu-item-chord");
                setText(null);
                setGraphic(row(iconColumn(a.commandId()), label, accel));
            } else if (item instanceof BranchRow br) {
                setDisable(false);
                setText(null);
                setGraphic(branchRow(br));
            }
        }

        /** A section header: the fold chevron, the title and — while folded — how many rows it hides. */
        private HBox header(Header h) {
            boolean folded = isFolded(h);
            Node chevron = Icons.chevronRight();
            chevron.setRotate(folded ? 0 : 90);
            javafx.scene.layout.StackPane box = new javafx.scene.layout.StackPane(chevron);
            box.setMinSize(CHEVRON_BOX, CHEVRON_BOX);
            box.setPrefSize(CHEVRON_BOX, CHEVRON_BOX);
            box.setMaxSize(CHEVRON_BOX, CHEVRON_BOX);
            box.getStyleClass().add("branch-popup-chevron");
            HBox row = new HBox(4, box, new Label(h.title()));
            if (folded) {
                Label count = new Label(Integer.toString(h.count()));
                count.getStyleClass().add("branch-popup-count");
                row.getChildren().add(count);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        }

        private HBox row(Node icon, Label left, Label right) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox box = new HBox(0, icon, left, spacer, right);
            // The same two gaps the menu uses: a small one after the glyph, a wide one before the chord.
            HBox.setMargin(left, new javafx.geometry.Insets(0, 0, 0, 8));
            HBox.setMargin(right, new javafx.geometry.Insets(0, 0, 0, 18));
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        }

        /**
         * The fixed-width leading column, mirroring {@code MainMenuBar.iconColumn}.
         *
         * <p>Fixed width whether or not there is a glyph, and present on <em>every</em> row including the
         * branches: JavaFX reserves no icon gutter of its own, so a column only some rows carry would start
         * each label at a different x and read as two ragged lists.
         */
        private javafx.scene.layout.StackPane iconColumn(Node glyph) {
            javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane();
            holder.setMinWidth(ICON_COLUMN);
            holder.setPrefWidth(ICON_COLUMN);
            holder.setMaxWidth(ICON_COLUMN);
            holder.setAlignment(Pos.CENTER);
            if (glyph != null) {
                holder.getChildren().add(glyph);
            }
            return holder;
        }

        private javafx.scene.layout.StackPane iconColumn(String commandId) {
            return iconColumn(MenuBarIcons.forCommand(commandId));
        }

        /** A branch row: name (+ incoming/outgoing badge) on the left, the upstream on the right. */
        private HBox branchRow(BranchRow br) {
            Label name = new Label(br.name());
            name.getStyleClass().add("menu-item-title");
            if (br.current()) {
                name.getStyleClass().add("branch-current");
            }
            HBox left = new HBox(6, name);
            left.setAlignment(Pos.CENTER_LEFT);
            String badge = trackBadge(br);
            if (!badge.isEmpty()) {
                Label b = new Label(badge);
                b.getStyleClass().add("branch-track");
                left.getChildren().add(b);
            }

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            // Right detail: the upstream (e.g. "origin/main"), or "remote" for a remote row.
            String detail = br.remote()
                    ? tr("branchpopup.remoteLabel")
                    : (br.gone() ? tr("branchpopup.gone", br.upstream()) : br.upstream());
            Label up = new Label(detail);
            up.getStyleClass().add("branch-upstream");
            if (br.gone()) {
                up.getStyleClass().add("branch-gone"); // the upstream was deleted: this branch is a leftover
            }
            // The visible way into the secondary actions; right-click and the Menu key reach the same menu.
            javafx.scene.control.Button more = new javafx.scene.control.Button("\u22ef");
            more.getStyleClass().addAll("flat", "branch-more");
            more.setFocusTraversable(false);
            more.setAccessibleText(tr("branchpopup.more", br.name()));
            more.setTooltip(new Tooltip(tr("branchpopup.more", br.name())));
            more.setOnAction(e -> {
                list.getSelectionModel().select(getIndex());
                showRowMenu(br, more, javafx.geometry.Side.BOTTOM, 0, 0);
            });
            // A click on the button is not a click on the row: it must not check the branch out.
            more.setOnMouseClicked(javafx.event.Event::consume);

            // The check moves into the shared leading column: as a "✓ " text prefix it shifted the current
            // branch's name out of line with every other row's.
            Node mark = br.current() ? Icons.check() : null;
            HBox box = new HBox(0, iconColumn(mark), left, spacer, up, more);
            HBox.setMargin(left, new javafx.geometry.Insets(0, 0, 0, 8));
            HBox.setMargin(up, new javafx.geometry.Insets(0, 0, 0, 18));
            HBox.setMargin(more, new javafx.geometry.Insets(0, 0, 0, 6));
            box.setAlignment(Pos.CENTER_LEFT);
            setTooltip(new Tooltip(branchTooltip(br)));
            return box;
        }

        private String trackBadge(BranchRow br) {
            return BranchPopup.trackBadge(br.ahead(), br.behind());
        }

        private String branchTooltip(BranchRow br) {
            if (br.remote()) {
                return tr("branchpopup.tip.remote", br.name());
            }
            StringBuilder sb = new StringBuilder(tr("branchpopup.tip.header", br.name()));
            if (br.upstream().isEmpty()) {
                sb.append("\n").append(tr("branchpopup.tip.noUpstream"));
            } else {
                sb.append("\n").append(tr("branchpopup.tip.tracks", br.upstream()));
                if (br.gone()) {
                    sb.append(tr("branchpopup.tip.goneSuffix"));
                    sb.append("\n").append(tr("branchpopup.tip.goneHint"));
                }
                if (br.ahead() > 0) {
                    sb.append("\n")
                            .append(tr(
                                    br.ahead() == 1 ? "branchpopup.tip.outgoing.one" : "branchpopup.tip.outgoing.many",
                                    br.ahead()));
                }
                if (br.behind() > 0) {
                    sb.append("\n")
                            .append(tr(
                                    br.behind() == 1 ? "branchpopup.tip.incoming.one" : "branchpopup.tip.incoming.many",
                                    br.behind()));
                }
                if (br.ahead() == 0 && br.behind() == 0 && !br.gone()) {
                    sb.append("\n").append(tr("branchpopup.tip.upToDate"));
                }
            }
            return sb.toString();
        }
    }

    /**
     * "↑M ↓N" outgoing/incoming badge (each capped at 99+), or empty when up to date / no upstream. Ahead
     * first, then behind — the order the status bar and the Commit window use, so one glance reads the same
     * everywhere.
     */
    static String trackBadge(int ahead, int behind) {
        StringBuilder sb = new StringBuilder();
        if (ahead > 0) {
            sb.append("↑").append(cap(ahead));
        }
        if (behind > 0) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("↓").append(cap(behind));
        }
        return sb.toString();
    }

    private static String cap(int n) {
        return n > 99 ? "99+" : Integer.toString(n);
    }
}
