package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import com.editora.search.SearchEverywhere;
import com.editora.search.SearchEverywhere.Group;
import com.editora.search.SearchEverywhere.Item;
import com.editora.search.SearchEverywhere.Kind;
import com.editora.search.SearchEverywhere.Scope;

import static com.editora.i18n.Messages.tr;

/**
 * One entry point for everything you can look for: a command, a file in the project, or a declaration.
 *
 * <p>Editora had five pickers behind five keystrokes, which asks the user to know in advance which kind of
 * thing they want before they can start typing its name. This asks for the name.
 *
 * <p>Results stay grouped by source rather than interleaved, because the sources differ in size by orders
 * of magnitude and a flat ranking hands the whole list to whichever is biggest — see
 * {@link SearchEverywhere}. A leading {@code >}, {@code #} or {@code @} restricts to one source when the
 * user does already know.
 */
final class SearchEverywherePopup {

    /** Rows are either a group header or a result; headers are skipped by the selection. */
    private sealed interface Row permits HeaderRow, ItemRow {}

    private record HeaderRow(Kind kind) implements Row {}

    private record ItemRow(Item item) implements Row {}

    /** Supplies results. Each is called on the FX thread with the sigil already stripped off the query. */
    interface Ops {
        List<Item> commands(String query);

        List<Item> files(String query);

        List<Item> symbols(String query);

        /** Ensures the project corpus exists, then runs the callback — may be asynchronous. */
        void ensureIndex(Runnable then);

        /** Acts on the chosen result: run the command, open the file, jump to the symbol. */
        void choose(Item item);

        /**
         * Why {@code item} is listed but cannot be run, or null. Called only for a <em>visible</em>
         * disabled row, so deriving it can be as expensive as the palette's equivalent.
         */
        String disabledReason(Item item);

        /** Opens {@code item}'s online documentation, if it has any. */
        void openDocs(Item item);
    }

    private static final double CELL_HEIGHT = 26;
    private static final int MAX_VISIBLE = 16;
    private static final double CARD_WIDTH = 680;

    private final OverlayHost overlayHost;
    private final Ops ops;

    private final TextField input = new TextField();
    private final ListView<Row> list = new ListView<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final Label status = new Label();
    /** The highlighted row's longer explanation — one fixed line, so the card cannot jitter. */
    private final Label desc = new Label();

    private final VBox card;

    /** Typing is cheap, but a query can trigger the first project walk; debounce so it happens once. */
    private final PauseTransition debounce = new PauseTransition(Duration.millis(120));

    private String currentQuery = "";
    private boolean showing;
    /** The row {@link #selectFirstItem} last put the cursor on; see {@link #reassertCursor}. */
    private int cursorAtOpen = -1;

    SearchEverywherePopup(OverlayHost overlayHost, Ops ops) {
        this.overlayHost = overlayHost;
        this.ops = ops;
        this.card = build();
        debounce.setOnFinished(e -> refresh());
    }

    private VBox build() {
        input.setPromptText(tr("searchEverywhere.prompt"));
        input.getStyleClass().add("palette-input");
        input.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        com.editora.command.TextInputKeymap.installShared(input);

        list.setItems(rows);
        list.setFixedCellSize(CELL_HEIGHT);
        list.setCellFactory(v -> new RowCell());
        rows.addListener((javafx.collections.ListChangeListener<Row>) c -> resizeList());

        status.getStyleClass().add("fif-status");
        desc.getStyleClass().add("palette-desc");
        desc.setMaxWidth(Double.MAX_VALUE);
        desc.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> updateDescription(now));
        Label hint = new Label(tr("searchEverywhere.hint"));
        hint.getStyleClass().add("palette-hint");

        VBox box = new VBox(6, input, list, status, desc, hint);
        box.getStyleClass().addAll("command-palette", "search-everywhere");
        box.setPrefWidth(CARD_WIDTH);
        box.setMaxSize(CARD_WIDTH, Region.USE_PREF_SIZE);
        box.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        resizeList();
        return box;
    }

    private void resizeList() {
        int n = Math.max(1, Math.min(rows.size(), MAX_VISIBLE));
        double h = n * CELL_HEIGHT + 2;
        list.setMinHeight(h);
        list.setPrefHeight(h);
        list.setMaxHeight(h);
    }

    void show(String seed) {
        input.setText(seed == null ? "" : seed);
        showing = true;
        overlayHost.show(
                card,
                () -> {
                    input.requestFocus();
                    input.selectAll();
                    refresh();
                    // Re-assert the cursor once the pulse that laid the card out has run. The first
                    // populate happens before the ListView has a skin, so it has no cells: the selection
                    // cannot paint and scrollTo() silently no-ops. Every *later* open was accidentally
                    // getting this for free — setText() only fires the debounce when the text actually
                    // changes, so re-opening with a stale query queued a second populate 120 ms later,
                    // by which time the list was laid out. That is why the first open of a session looked
                    // different from every one after it.
                    Platform.runLater(this::reassertCursor);
                },
                () -> showing = false);
    }

    /**
     * Puts the cursor back on the first runnable row after layout — but only if nothing has moved it
     * since, so a keystroke landing in the intervening pulse is never overridden.
     */
    private void reassertCursor() {
        if (showing && list.getSelectionModel().getSelectedIndex() == cursorAtOpen) {
            selectFirstItem();
        }
    }

    boolean isShown() {
        return showing;
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
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
            case ENTER -> {
                chooseSelected();
                e.consume();
            }
            case H -> {
                if (e.isControlDown()) {
                    openDocs();
                    e.consume();
                }
            }
            default -> {}
            // Escape and C-g belong to the host, as with every other in-scene overlay.
        }
    }

    /**
     * Moves to the next <em>selectable</em> row, stepping over group headers and over rows that are
     * listed but cannot be run, so the cursor never rests somewhere Enter would do nothing.
     */
    private void move(int delta) {
        int size = rows.size();
        if (size == 0) {
            return;
        }
        int cur = list.getSelectionModel().getSelectedIndex();
        for (int step = 1; step <= size; step++) {
            int idx = Math.floorMod((cur < 0 ? 0 : cur) + delta * step, size);
            if (isSelectable(rows.get(idx))) {
                list.getSelectionModel().select(idx);
                list.scrollTo(idx);
                return;
            }
        }
    }

    private static boolean isSelectable(Row row) {
        return row instanceof ItemRow r && r.item().enabled();
    }

    private void chooseSelected() {
        if (list.getSelectionModel().getSelectedItem() instanceof ItemRow row
                && row.item().enabled()) {
            overlayHost.hide();
            ops.choose(row.item());
        }
    }

    /** C-h: the highlighted row's online documentation, mirroring the command palette. */
    private void openDocs() {
        if (list.getSelectionModel().getSelectedItem() instanceof ItemRow row) {
            overlayHost.hide();
            ops.openDocs(row.item());
        }
    }

    private void updateDescription(Row row) {
        String d = row instanceof ItemRow r ? r.item().description() : "";
        desc.setText(d == null || d.isEmpty() ? " " : d); // one line tall always, so the card never jitters
    }

    /** Re-queries every in-scope source and rebuilds the list. */
    private void refresh() {
        Scope scope = SearchEverywhere.scopeOf(input.getText());
        currentQuery = scope.query();
        if (currentQuery.isEmpty() && scope.kind() != null && scope.kind() != Kind.COMMAND) {
            // A file or symbol sigil with nothing typed after it yet: name what it scopes to rather than
            // walking the project on behalf of an empty query.
            rows.clear();
            status.setText(tr("searchEverywhere.scoped", labelFor(scope.kind())));
            updateDescription(null);
            return;
        }
        // An empty query lists every command and touches no corpus. That is what lets this stand in for
        // the command palette: opening it shows the same browsable list rather than a blank box.
        // Otherwise: files and symbols need the corpus, so a command-scoped query must not trigger a walk.
        if (currentQuery.isEmpty() || scope.kind() == Kind.COMMAND) {
            populate(scope);
        } else {
            ops.ensureIndex(() -> {
                if (showing) {
                    populate(scope);
                }
            });
        }
    }

    private void populate(Scope scope) {
        // The corpus sources answer nothing useful for an empty query, and asking would build the index.
        boolean corpus = !scope.query().isEmpty();
        List<Item> all = new ArrayList<>();
        if (scope.kind() == null || scope.kind() == Kind.COMMAND) {
            all.addAll(ops.commands(scope.query()));
        }
        if (corpus && (scope.kind() == null || scope.kind() == Kind.FILE)) {
            all.addAll(ops.files(scope.query()));
        }
        if (corpus && (scope.kind() == null || scope.kind() == Kind.SYMBOL)) {
            all.addAll(ops.symbols(scope.query()));
        }
        // With one source in play there is nothing to drown, so nothing is trimmed: a `>` search must not
        // return fewer commands than the palette would, and the empty query must list them all.
        boolean single = scope.kind() != null || !corpus;
        List<Group> groups = single
                ? SearchEverywhere.merge(all, SearchEverywhere.UNCAPPED, SearchEverywhere.UNCAPPED)
                : SearchEverywhere.merge(all);

        List<Row> built = new ArrayList<>();
        for (Group g : groups) {
            built.add(new HeaderRow(g.kind()));
            for (Item item : g.items()) {
                built.add(new ItemRow(item));
            }
        }
        rows.setAll(built);
        status.setText(built.isEmpty() ? tr("searchEverywhere.none") : "");
        selectFirstItem();
    }

    private void selectFirstItem() {
        for (int i = 0; i < rows.size(); i++) {
            if (isSelectable(rows.get(i))) {
                list.getSelectionModel().select(i);
                // Scroll to the top rather than to the row: the first runnable row sits directly under
                // its group header, and scrolling *to* it puts it flush at the top with the header pushed
                // out of sight — so the list opened without the label that says what it is listing.
                list.scrollTo(0);
                cursorAtOpen = i;
                return;
            }
        }
        list.getSelectionModel().clearSelection(); // nothing here can be run
        cursorAtOpen = -1;
        updateDescription(null);
    }

    private static String labelFor(Kind kind) {
        return tr("searchEverywhere.group." + kind.name().toLowerCase(Locale.ROOT));
    }

    private final class RowCell extends ListCell<Row> {
        private final TextFlow label = new TextFlow();
        private final Label detail = new Label();
        private final HBox box = new HBox(10, label, spacer(), detail);

        RowCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            detail.getStyleClass().add("keybinding");
            // A grayed row is inert to the mouse too, exactly as in the command palette.
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && isSelectable(getItem())) {
                    getListView().getSelectionModel().select(getItem());
                    chooseSelected();
                }
            });
        }

        private Region spacer() {
            Region r = new Region();
            HBox.setHgrow(r, Priority.ALWAYS);
            return r;
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("se-header");
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            if (item instanceof HeaderRow h) {
                // A header is not a result: it must not be selectable, and it must not look like one.
                setGraphic(null);
                setText(labelFor(h.kind()));
                getStyleClass().add("se-header");
                setDisable(true);
                return;
            }
            setDisable(false);
            setText(null);
            Item it = ((ItemRow) item).item();
            label.getChildren().setAll(MatchText.runs(it.label(), currentQuery));
            detail.setText(it.detail());
            box.getStyleClass().remove("palette-disabled");
            setTooltip(null); // cells are recycled — never leave a previous row's explanation behind
            if (!it.enabled()) {
                box.getStyleClass().add("palette-disabled");
                // Say why, and which command would fix it: a gray row with no explanation reads as a bug
                // rather than a state, and the explanation is the whole reason it is listed at all.
                String why = ops.disabledReason(it);
                if (why != null && !why.isBlank()) {
                    javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(why);
                    tip.setWrapText(true);
                    tip.setMaxWidth(380);
                    tip.getStyleClass().add("palette-disabled-tooltip");
                    setTooltip(tip);
                }
            }
            setGraphic(box);
        }
    }
}
