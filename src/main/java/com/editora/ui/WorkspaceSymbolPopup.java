package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

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
import javafx.util.Duration;

import com.editora.lsp.LspManager.WorkspaceSymbolMatch;
import com.editora.search.FuzzyMatch;

import static com.editora.i18n.Messages.tr;

/**
 * An in-scene "Go to Symbol in Workspace" popup: a query field over a live-updating list of project-wide
 * symbols. Typing re-runs {@code workspace/symbol} on the active file's language server (debounced), and
 * Enter jumps to the selected symbol's definition — the IDE "Go to Symbol" gesture (VS Code {@code Ctrl-T} /
 * IntelliJ {@code Ctrl-N}). Modeled on {@link SearchInFilesPopup}: shown through the shared {@link OverlayHost},
 * {@code editora.ownsKeys} so its nav chords aren't hijacked, {@code Esc}/{@code C-g} dismiss (host-handled).
 */
final class WorkspaceSymbolPopup {

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** Window hooks: run the async server query, and open a symbol's location. */
    interface Ops {
        /** Runs {@code workspace/symbol} for {@code text}; {@code cb} is delivered on the FX thread. */
        void query(String text, Consumer<List<WorkspaceSymbolMatch>> cb);

        /** Opens {@code file} and jumps to the 0-based {@code line}/{@code character}, focusing the editor. */
        void open(Path file, int line, int character);
    }

    private static final double CELL_HEIGHT = 26;
    private static final int MAX_VISIBLE = 14;
    private static final double CARD_WIDTH = 620;

    private final OverlayHost overlayHost;
    private final Ops ops;

    private final TextField query = new TextField();
    private final ListView<WorkspaceSymbolMatch> list = new ListView<>();
    private final ObservableList<WorkspaceSymbolMatch> rows = FXCollections.observableArrayList();
    private final Label status = new Label();
    private final VBox content;

    private final PauseTransition debounce = new PauseTransition(Duration.millis(150));
    private long queryGen; // drops stale async replies (fast typing)
    private boolean showing;

    WorkspaceSymbolPopup(OverlayHost overlayHost, Ops ops) {
        this.overlayHost = overlayHost;
        this.ops = ops;
        this.content = build();
        debounce.setOnFinished(e -> runQuery());
    }

    private VBox build() {
        query.setPromptText(tr("lsp.workspaceSymbols.prompt"));
        query.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        query.addEventFilter(KeyEvent.KEY_PRESSED, this::onQueryKey);
        // Emacs caret movement + basic editing (registered after onQueryKey so its list navigation wins).
        com.editora.command.TextInputKeymap.installShared(query);
        if (IS_MAC) {
            query.addEventFilter(KeyEvent.KEY_TYPED, e -> {
                if (e.isAltDown() || e.isMetaDown() || e.isControlDown() || e.isShortcutDown()) {
                    e.consume();
                }
            });
        }

        list.setItems(rows);
        list.setFixedCellSize(CELL_HEIGHT);
        list.setCellFactory(v -> new SymbolCell());
        rows.addListener((javafx.collections.ListChangeListener<WorkspaceSymbolMatch>) c -> resizeList());

        status.getStyleClass().add("fif-status");
        Label title = new Label(tr("lsp.workspaceSymbols.title"));
        title.getStyleClass().add("palette-title");
        Label hint = new Label(tr("lsp.workspaceSymbols.hint"));
        hint.getStyleClass().add("palette-hint");

        VBox card = new VBox(6, title, query, list, status, hint);
        card.getStyleClass().addAll("command-palette", "fif-popup");
        card.setPrefWidth(CARD_WIDTH);
        card.setMaxSize(CARD_WIDTH, Region.USE_PREF_SIZE);
        card.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        resizeList();
        return card;
    }

    private void resizeList() {
        int n = Math.max(1, Math.min(rows.size(), MAX_VISIBLE));
        double h = n * CELL_HEIGHT + 2;
        list.setMinHeight(h);
        list.setPrefHeight(h);
        list.setMaxHeight(h);
    }

    /** Shows the popup, seeding the query with {@code seed} (e.g. the selected text) when non-blank. */
    void show(String seed) {
        if (seed != null && !seed.isBlank()) {
            query.setText(seed);
        }
        showing = true;
        overlayHost.show(
                content,
                () -> {
                    query.requestFocus();
                    query.selectAll();
                    runQuery();
                },
                () -> showing = false);
    }

    private void onQueryKey(KeyEvent e) {
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
                openSelected();
                e.consume();
            }
            default -> {}
            // ESCAPE / C-g are handled by the OverlayHost.
        }
    }

    private void move(int delta) {
        int size = rows.size();
        if (size == 0) {
            return;
        }
        int idx = Math.floorMod(list.getSelectionModel().getSelectedIndex() + delta, size);
        list.getSelectionModel().select(idx);
        list.scrollTo(idx);
    }

    private void openSelected() {
        WorkspaceSymbolMatch m = list.getSelectionModel().getSelectedItem();
        if (m == null) {
            return;
        }
        overlayHost.hide();
        ops.open(m.file(), m.line(), m.character());
    }

    /** The query the in-flight request was issued for, so its results can be ranked when they land. */
    private String lastQuery = "";

    private void runQuery() {
        String q = query.getText() == null ? "" : query.getText().trim();
        lastQuery = q;
        if (q.isEmpty()) {
            rows.clear();
            status.setText("");
            return;
        }
        status.setText(tr("lsp.workspaceSymbols.searching"));
        long gen = ++queryGen;
        ops.query(q, matches -> {
            if (gen != queryGen) {
                return; // a newer query superseded this one
            }
            populate(matches);
        });
    }

    /**
     * Orders the server's results by how well each symbol <em>name</em> matches the query.
     *
     * <p>{@code workspace/symbol} decides what matches, and servers differ wildly in what order they hand
     * it back — jdtls in particular returns something close to index order, so an exact name can sit below
     * a dozen incidental hits. This does not re-filter (a symbol the server chose to return is kept even
     * if the name alone doesn't match — the server may have matched on the container), it only sorts;
     * anything unmatched keeps its server order at the end.
     */
    static List<WorkspaceSymbolMatch> rankByName(List<WorkspaceSymbolMatch> matches, String query) {
        if (query == null || query.isBlank() || matches.size() < 2) {
            return matches;
        }
        record Scored(WorkspaceSymbolMatch match, int score, int index) {}
        List<Scored> scored = new java.util.ArrayList<>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            FuzzyMatch.Match m = FuzzyMatch.of(matches.get(i).name(), query);
            scored.add(new Scored(matches.get(i), m == null ? Integer.MIN_VALUE : m.score(), i));
        }
        scored.sort(java.util.Comparator.comparingInt((Scored s) -> s.score())
                .reversed()
                .thenComparingInt(Scored::index));
        return scored.stream().map(Scored::match).toList();
    }

    private void populate(List<WorkspaceSymbolMatch> matches) {
        rows.setAll(rankByName(matches, lastQuery));
        if (!rows.isEmpty()) {
            list.getSelectionModel().select(0);
        }
        status.setText(
                matches.isEmpty() ? tr("lsp.workspaceSymbols.none") : tr("lsp.workspaceSymbols.count", matches.size()));
    }

    private final class SymbolCell extends ListCell<WorkspaceSymbolMatch> {
        private final Label name = new Label();
        private final Label detail = new Label();
        private final Label location = new Label();
        private final Region spacer = new Region();
        private final HBox row = new HBox(8, name, detail, spacer, location);

        SymbolCell() {
            row.setAlignment(Pos.CENTER_LEFT);
            name.getStyleClass().add("ws-symbol-name");
            detail.getStyleClass().add("ws-symbol-detail");
            location.getStyleClass().add("ws-symbol-detail");
            HBox.setHgrow(spacer, Priority.ALWAYS);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
                    getListView().getSelectionModel().select(getItem());
                    openSelected();
                }
            });
        }

        @Override
        protected void updateItem(WorkspaceSymbolMatch item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            name.setText(item.name());
            name.setGraphic(StructureIcons.forKind(item.kind()));
            String container = item.container() == null ? "" : item.container();
            detail.setText(container.isBlank() ? "" : container);
            location.setText(String.valueOf(item.file().getFileName()));
            setGraphic(row);
        }
    }
}
