package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.animation.PauseTransition;
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
    private final VBox card;

    /** Typing is cheap, but a query can trigger the first project walk; debounce so it happens once. */
    private final PauseTransition debounce = new PauseTransition(Duration.millis(120));

    private String currentQuery = "";
    private boolean showing;

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
        Label hint = new Label(tr("searchEverywhere.hint"));
        hint.getStyleClass().add("palette-hint");

        VBox box = new VBox(6, input, list, status, hint);
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
                },
                () -> showing = false);
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
            default -> {}
            // Escape and C-g belong to the host, as with every other in-scene overlay.
        }
    }

    /** Moves to the next selectable row, stepping over group headers rather than landing on them. */
    private void move(int delta) {
        int size = rows.size();
        if (size == 0) {
            return;
        }
        int cur = list.getSelectionModel().getSelectedIndex();
        for (int step = 1; step <= size; step++) {
            int idx = Math.floorMod((cur < 0 ? 0 : cur) + delta * step, size);
            if (rows.get(idx) instanceof ItemRow) {
                list.getSelectionModel().select(idx);
                list.scrollTo(idx);
                return;
            }
        }
    }

    private void chooseSelected() {
        if (list.getSelectionModel().getSelectedItem() instanceof ItemRow row) {
            overlayHost.hide();
            ops.choose(row.item());
        }
    }

    /** Re-queries every in-scope source and rebuilds the list. */
    private void refresh() {
        Scope scope = SearchEverywhere.scopeOf(input.getText());
        currentQuery = scope.query();
        if (currentQuery.isEmpty()) {
            rows.clear();
            status.setText(scope.kind() == null ? "" : tr("searchEverywhere.scoped", labelFor(scope.kind())));
            return;
        }
        // Files and symbols both need the project corpus; a command-scoped query must not trigger a walk.
        if (scope.kind() == Kind.COMMAND) {
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
        List<Item> all = new ArrayList<>();
        if (scope.kind() == null || scope.kind() == Kind.COMMAND) {
            all.addAll(ops.commands(scope.query()));
        }
        if (scope.kind() == null || scope.kind() == Kind.FILE) {
            all.addAll(ops.files(scope.query()));
        }
        if (scope.kind() == null || scope.kind() == Kind.SYMBOL) {
            all.addAll(ops.symbols(scope.query()));
        }
        List<Group> groups = SearchEverywhere.merge(all);

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
            if (rows.get(i) instanceof ItemRow) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                return;
            }
        }
        list.getSelectionModel().clearSelection();
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
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && getItem() instanceof ItemRow) {
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
            setGraphic(box);
        }
    }
}
