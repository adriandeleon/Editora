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

import static com.editora.i18n.Messages.tr;

/**
 * IntelliJ-style branch dropdown: a search field over a sectioned list of <em>actions</em> (New Branch,
 * Fetch, Pull, Push, Commit…) plus <em>Local</em> and <em>Remote</em> branches. Typing filters branches
 * <em>and</em> actions together; ↑/↓ navigate (skipping section headers), Enter activates, Esc closes.
 * Anchored just above the status-bar branch segment.
 *
 * <p>Pure view: the owner supplies the branch lists and the action/checkout callbacks via
 * {@link #show}. Modeled on {@link QuickOpen} (popup + filtered {@link ListView}).
 */
public final class BranchPopup {

    /** A non-branch action shown at the top (label + optional shortcut hint + handler). */
    public record MenuAction(String label, String accel, Runnable run) {}

    private sealed interface Row permits Header, ActionRow, BranchRow {}

    private record Header(String title) implements Row {}

    private record ActionRow(String label, String accel, Runnable run) implements Row {}
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
        list.setCellFactory(v -> new RowCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                activate(list.getSelectionModel().getSelectedItem());
            }
        });

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
        Label hint = new Label("↑↓ / C-n C-p move  ·  ↵ select  ·  esc / C-g cancel");
        hint.getStyleClass().add("palette-hint");
        content = new VBox(6, header, search, list, hint);
        content.getStyleClass().add("command-palette");
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
        for (MenuAction a : actions) {
            rows.add(new ActionRow(a.label(), a.accel(), a.run()));
        }
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
        titleLabel.setText(tr("branchpopup.title"));
        remoteUrlLabel.setText(remoteUrl == null ? "" : remoteUrl);
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            remoteUrlLabel.setTooltip(new Tooltip(remoteUrl));
        }
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
        all = List.of(new ActionRow(tr("branchpopup.clone"), "", onClone));
        present(owner, anchor);
    }

    /** Filters to the current items and shows the dropdown anchored just above {@code anchor}. */
    private void present(Window owner, Node anchor) {
        if (overlayHost == null) {
            return;
        }
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
            if (q.isEmpty() || CommandPalette.isSubsequence(q, labelOf(r).toLowerCase(Locale.ROOT))) {
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
                list.scrollTo(i);
                return;
            }
        }
        list.getSelectionModel().clearSelection();
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                e.consume();
            }
            case ENTER -> {
                activate(list.getSelectionModel().getSelectedItem());
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            case N -> {
                if (e.isControlDown()) {
                    move(1);
                    e.consume();
                }
            }
            case P -> {
                if (e.isControlDown()) {
                    move(-1);
                    e.consume();
                }
            }
            case G -> {
                if (e.isControlDown()) {
                    hide();
                    e.consume();
                }
            }
            default -> {}
        }
    }

    /** Moves the selection by {@code dir}, skipping section headers, wrapping at the ends. */
    private void move(int dir) {
        int n = items.size();
        if (n == 0) {
            return;
        }
        int idx = list.getSelectionModel().getSelectedIndex();
        for (int step = 0; step < n; step++) {
            idx = Math.floorMod(idx + dir, n);
            if (!(items.get(idx) instanceof Header)) {
                list.getSelectionModel().select(idx);
                list.scrollTo(idx);
                return;
            }
        }
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

    private final class RowCell extends ListCell<Row> {
        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("branch-popup-header");
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
                Label accel = new Label(a.accel() == null ? "" : a.accel());
                accel.getStyleClass().add("keybinding");
                setText(null);
                setGraphic(row(label, accel));
            } else if (item instanceof BranchRow br) {
                setDisable(false);
                setText(null);
                setGraphic(branchRow(br));
            }
        }

        private HBox row(Label left, Label right) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox box = new HBox(10, left, spacer, right);
            box.setAlignment(Pos.CENTER_LEFT);
            return box;
        }

        /** A branch row: name (+ incoming/outgoing badge) on the left, the upstream on the right. */
        private HBox branchRow(BranchRow br) {
            Label name = new Label((br.current() ? "✓ " : "") + br.name());
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

            HBox box = new HBox(10, left, spacer, up);
            box.setAlignment(Pos.CENTER_LEFT);
            setTooltip(new Tooltip(branchTooltip(br)));
            return box;
        }

        /** "↓N ↑M" incoming/outgoing badge (capped at 99+), or empty when up to date / no upstream. */
        private String trackBadge(BranchRow br) {
            StringBuilder sb = new StringBuilder();
            if (br.behind() > 0) {
                sb.append("↓").append(cap(br.behind()));
            }
            if (br.ahead() > 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append("↑").append(cap(br.ahead()));
            }
            return sb.toString();
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
                if (br.behind() > 0) {
                    sb.append("\n")
                            .append(tr(
                                    br.behind() == 1 ? "branchpopup.tip.incoming.one" : "branchpopup.tip.incoming.many",
                                    br.behind()));
                }
                if (br.ahead() > 0) {
                    sb.append("\n")
                            .append(tr(
                                    br.ahead() == 1 ? "branchpopup.tip.outgoing.one" : "branchpopup.tip.outgoing.many",
                                    br.ahead()));
                }
                if (br.ahead() == 0 && br.behind() == 0 && !br.gone()) {
                    sb.append("\n").append(tr("branchpopup.tip.upToDate"));
                }
            }
            return sb.toString();
        }

        private String cap(int n) {
            return n > 99 ? "99+" : Integer.toString(n);
        }
    }
}
