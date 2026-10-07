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
 * IntelliJ-style branch dropdown: a search field over a sectioned list — the <em>Local</em> branches (the
 * current one first), the <em>Remote</em> branches, then the <em>actions</em> (New Branch, Pull, Fetch,
 * Push, Stash, Commit…). Branches come first because they are what the dropdown is opened for: with the
 * seven action rows on top, the current branch sat below the fold at the default height. Typing filters
 * branches <em>and</em> actions together; ↑/↓ navigate (skipping section headers, and wrapping — so ↑ from
 * the current branch lands on the last action), Enter activates, Esc closes. A row is activated by a click
 * on that row only. Anchored just above the status-bar branch segment.
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

    private sealed interface Row permits Header, ActionRow, BranchRow {}

    private record Header(String title) implements Row {}

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

    /** Shared in-scene overlay host (injected by MainController) + the card it shows. */
    private OverlayHost overlayHost;

    private VBox content;
    private boolean showing;

    public BranchPopup() {
        search.setPromptText(tr("branchpopup.searchPrompt"));
        list.setItems(items);
        list.setPrefHeight(360);
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
        content.setPrefWidth(480);
        content.setMaxSize(480, Region.USE_PREF_SIZE); // hug content; don't stretch to fill the overlay
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE); // keep C-n/C-p for the picker
    }

    /** Injects the shared overlay host used to show the branch dropdown. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
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
        List<Row> rows = new ArrayList<>();
        rows.add(new Header(tr("branchpopup.local")));
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
            rows.add(new BranchRow(
                    b.name(),
                    false,
                    cur,
                    b.upstream(),
                    b.ahead(),
                    b.behind(),
                    b.gone(),
                    cur ? this::hide : () -> onCheckoutLocal.accept(b.name())));
        }
        if (!remote.isEmpty()) {
            rows.add(new Header(tr("branchpopup.remote")));
            List<String> rem = new ArrayList<>(remote);
            rem.sort(String.CASE_INSENSITIVE_ORDER);
            for (String b : rem) {
                rows.add(new BranchRow(b, true, false, "", 0, 0, false, () -> onCheckoutRemote.accept(b)));
            }
        }
        if (!actions.isEmpty()) {
            rows.add(new Header(tr("branchpopup.actions")));
            // The chord beside an action comes from the live keymap, never from the caller: a hardcoded
            // "C-x g" was only true of the Emacs keymap.
            var keymap = com.editora.command.TextInputKeymap.sharedKeymap();
            for (MenuAction a : actions) {
                String chord = keymap == null ? null : keymap.displayChord(a.commandId());
                rows.add(new ActionRow(a.label(), chord == null ? "" : chord, a.commandId(), a.run()));
            }
        }
        titleLabel.setText(tr("branchpopup.title"));
        // Never the raw URL: one stored as https://user:token@host would put the token on screen.
        String shownUrl = com.editora.git.GitFormat.displayRemoteUrl(remoteUrl);
        remoteUrlLabel.setText(shownUrl);
        remoteUrlLabel.setTooltip(shownUrl.isBlank() ? null : new Tooltip(shownUrl));
        all = rows;
        present(owner, anchor);
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
        hint.setText(PickerKeys.legend(PickerKeys.hint("select", "↵")));
        search.clear();
        filter("");
        showing = true;
        overlayHost.show(content, anchor, search::requestFocus, () -> {
            showing = false;
            lastHiddenAt = System.currentTimeMillis();
        });
    }

    public void hide() {
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

    private void filter(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Row> out = new ArrayList<>();
        Header pending = null;
        for (Row r : all) {
            if (r instanceof Header h) {
                pending = h; // only emitted if a following row in its section matches
                continue;
            }
            if (q.isEmpty() || FuzzyMatch.of(labelOf(r), q) != null) {
                if (pending != null) {
                    out.add(pending);
                    pending = null;
                }
                out.add(r);
            }
        }
        items.setAll(out);
        selectFirstSelectable();
    }

    private void selectFirstSelectable() {
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Header)) {
                list.getSelectionModel().select(i);
                list.scrollTo(0); // from the top: the section header above the first row stays in view
                return;
            }
        }
        list.getSelectionModel().clearSelection();
    }

    private void onKey(KeyEvent e) {
        PickerKeys.Action action = PickerKeys.action(e);
        switch (action) {
            case CANCEL -> hide();
            case ACCEPT -> activate(list.getSelectionModel().getSelectedItem());
            default -> {
                // Section headers are stepped over.
                if (!PickerKeys.navigate(list, action, row -> !(row instanceof Header))) {
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
        }
    }

    /** Width of the leading icon column — the menu bar's {@code ICON_COLUMN}, so both read alike. */
    private static final double ICON_COLUMN = 22;

    /** Whether a click or Enter on {@code row} does something: a real action or branch, never a header. */
    private static boolean activatable(Row row) {
        return row instanceof ActionRow || row instanceof BranchRow;
    }

    private final class RowCell extends ListCell<Row> {
        RowCell() {
            // Only a primary click on this cell, and only while it shows an action or a branch.
            setOnMouseClicked(e -> {
                Row row = getItem();
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && activatable(row)) {
                    activate(row);
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
                setGraphic(null);
                setText(h.title());
                getStyleClass().add("branch-popup-header");
                setDisable(true); // visually a non-selectable section label
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

            // The check moves into the shared leading column: as a "✓ " text prefix it shifted the current
            // branch's name out of line with every other row's.
            Node mark = br.current() ? Icons.check() : null;
            HBox box = new HBox(0, iconColumn(mark), left, spacer, up);
            HBox.setMargin(left, new javafx.geometry.Insets(0, 0, 0, 8));
            HBox.setMargin(up, new javafx.geometry.Insets(0, 0, 0, 18));
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
