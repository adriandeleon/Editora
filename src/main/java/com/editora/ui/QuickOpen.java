package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import javafx.stage.Window;

import com.editora.editor.UiZoom;
import com.editora.search.FuzzyMatch;
import org.jspecify.annotations.Nullable;

/**
 * A generic fuzzy-filtered picker shown as a popup overlay — the keyboard-first counterpart to a list
 * UI (e.g. recent files, the structure outline). Modeled on {@link CommandPalette}: type to fuzzily
 * filter by each item's label, navigate with ↑/↓ or {@code C-n}/{@code C-p}, Enter chooses,
 * {@code Esc}/{@code C-g} cancels. The item list is supplied fresh on each {@link #show(Window)} and
 * snapshotted so typing filters a stable set.
 *
 * @param <T> the element type (e.g. {@code Path}, a structure entry)
 */
public class QuickOpen<T> {

    private final Supplier<List<T>> itemsSupplier;
    private final Function<T, String> label;
    private final Function<T, String> detail;
    /** Text the typed query is matched against; defaults to {@link #label} (the displayed text). */
    private final Function<T, String> searchKey;

    private final Consumer<T> onChoose;
    /** Optional per-item CSS class applied to the row's title label (e.g. "dirty-name"); null = none. */
    private @Nullable Function<T, @Nullable String> itemStyleClass;

    private @Nullable Function<T, Node> itemIcon;

    /** Fixed list-cell height; lets the result list hug its row count. */
    private static final double CELL_HEIGHT = 26;
    /** Max rows shown before the list scrolls. */
    private static final int MAX_VISIBLE = 12;
    /** Default card width (px); overridable per picker via {@link #setCardWidth}. */
    private static final double DEFAULT_WIDTH = 620;

    /** Card width + the minimum number of result rows reserved (so a sparse list doesn't collapse). */
    private double cardWidth = DEFAULT_WIDTH;

    private int minRows = 1;

    /** A fixed list height (px) with natural row heights, instead of hugging the rows; 0 = hug (the default). */
    private double fixedListHeight;

    // The card's nodes are made by build(), on the first show(): a picker nobody has opened holds none.
    private TextField input;
    private ListView<T> list;
    /** The key legend under the list; rebuilt from the live keymap each time the picker is shown. */
    private Label hint;

    /** Supplies the value currently in force, pre-selected on open (see {@link #setCurrentItem}). */
    private java.util.function.@Nullable Supplier<T> currentItem;

    private final ObservableList<T> items = FXCollections.observableArrayList();

    /** The picker's own sentence for an empty source ({@link #setPlaceholder}); null: the general one. */
    private @Nullable String emptyText;

    /** Shown in place of the rows while there are none. */
    private Label emptyLabel;

    private List<T> all = List.of();

    /** The live query, so a recycled cell can embolden the characters responsible for its row's rank. */
    private String currentQuery = "";

    /** Optional live preview of the highlighted row, plus the undo for it when the picker is cancelled. */
    private @Nullable Consumer<T> onPreview;

    private @Nullable Runnable onPreviewCancelled;

    private Label header;

    /** The title over the list and the query field's prompt, kept for {@link #build}. */
    private String title;

    private final String prompt;

    /** Whether {@link #build} has run: the setters that reach a node check it and otherwise only keep the value. */
    private boolean built;

    /** Whether this showing ended in a choice — so cancelling restores and choosing does not. */
    private boolean chosen;

    /** Shared in-scene overlay host (injected by MainController) + the card it shows. */
    private @Nullable OverlayHost overlayHost;

    private VBox content;
    private boolean showing;

    public QuickOpen(
            String title,
            String prompt,
            Supplier<List<T>> itemsSupplier,
            Function<T, String> label,
            Function<T, String> detail,
            Consumer<T> onChoose) {
        this(title, prompt, itemsSupplier, label, detail, label, onChoose);
    }

    /**
     * As above but with an explicit {@code searchKey} the typed query matches against (instead of the
     * displayed {@code label}) — e.g. to search a note's full body/tags/file while showing a short label.
     */
    public QuickOpen(
            String title,
            String prompt,
            Supplier<List<T>> itemsSupplier,
            Function<T, String> label,
            Function<T, String> detail,
            Function<T, String> searchKey,
            Consumer<T> onChoose) {
        this.itemsSupplier = itemsSupplier;
        this.label = label;
        this.detail = detail;
        this.searchKey = searchKey == null ? label : searchKey;
        this.onChoose = onChoose;
        this.title = title;
        this.prompt = prompt;
    }

    /** A key that acts on the highlighted row and keeps the picker open: the list is read again after it. */
    private record RowAction<U>(javafx.scene.input.KeyCombination key, String legend, Consumer<U> action) {}

    private final List<RowAction<T>> rowActions = new ArrayList<>();

    /**
     * Adds a key that acts on the highlighted row without closing the picker (the breakpoints list's enable /
     * remove keys): {@code legend} is the already-translated entry for the key legend under the list.
     */
    public void addRowAction(javafx.scene.input.KeyCombination key, String legend, Consumer<T> action) {
        rowActions.add(new RowAction<>(key, legend, action));
    }

    /** Sets a per-item CSS class applied to the row's title label (e.g. "dirty-name" for unsaved files). */
    public void setItemStyleClass(Function<T, @Nullable String> itemStyleClass) {
        this.itemStyleClass = itemStyleClass;
    }

    /** Sets a per-item leading icon shown on the row's title label (e.g. a file-type glyph). */
    public void setItemIcon(Function<T, Node> itemIcon) {
        this.itemIcon = itemIcon;
    }

    /**
     * What the list says while its source has no rows at all, in this picker's own words ("No bookmarks in this
     * project"). A picker that sets none says a general sentence: the card is never blank.
     */
    public void setPlaceholder(String text) {
        emptyText = text;
        if (built) {
            emptyLabel.setText(placeholderText());
        }
    }

    /** The sentence for a list with no rows: nothing to choose from at all, or nothing that matches what was typed. */
    private String placeholderText() {
        if (!all.isEmpty()) {
            return com.editora.i18n.Messages.tr("search.none");
        }
        return emptyText != null ? emptyText : com.editora.i18n.Messages.tr("picker.empty");
    }

    /** The title over the list: one picker serves two commands (Insert Snippet, Surround With — ET2). */
    public void setTitle(String title) {
        this.title = title;
        if (built) {
            header.setText(title);
        }
    }

    /** Runs {@code onCancelled} when the picker is dismissed without choosing (the caret put back — ET2). */
    public void setOnCancelled(Runnable onCancelled) {
        this.onPreviewCancelled = onCancelled;
    }

    /**
     * Makes the picker preview the highlighted row: {@code onPreview} runs as the selection moves, and
     * {@code onCancelled} runs if the picker is dismissed without choosing.
     *
     * <p>Preview is what turns a picker from "commit and hope" into something you can browse — but only
     * if it is reversible, hence the second half. Without the undo, arrowing through a list and pressing
     * Esc leaves you wherever the cursor happened to stop, which is worse than not previewing at all.
     *
     * <p>Nothing is previewed for the selection the picker makes when it opens — only for a selection the
     * user moved to. Firing on open would yank the editor somewhere the instant the picker appears, before
     * any intent has been expressed.
     */
    public void setPreview(Consumer<T> onPreview, Runnable onCancelled) {
        this.onPreview = onPreview;
        this.onPreviewCancelled = onCancelled;
    }

    /**
     * Marks one item as the current value, so opening the picker lands on it rather than on the first
     * row. Restores what a {@code ChoiceDialog} gave for free when these were native dialogs: for a
     * "change this setting" picker, the value in force is the useful starting point — the user is
     * usually moving one step from it, and it says what the current value IS.
     *
     * <p>Only honoured while the query is empty; once the user types, the best match leads as before.
     */
    public void setCurrentItem(java.util.function.Supplier<T> currentItem) {
        this.currentItem = currentItem;
    }

    /** Injects the shared overlay host used to show the picker card. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    /**
     * Overrides the card width + the minimum reserved result rows for this picker (e.g. so a registry
     * browser with a single entry still shows a roomy box instead of a one-row sliver). {@code minRows} is
     * clamped to {@link #MAX_VISIBLE}.
     */
    public void setPreferredSize(double width, int minVisibleRows) {
        this.cardWidth = width;
        this.minRows = Math.max(1, Math.min(minVisibleRows, MAX_VISIBLE));
        if (built) {
            UiZoom.prefWidth(content, width);
            UiZoom.maxSize(content, width, Region.USE_PREF_SIZE);
            resizeList();
        }
    }

    /**
     * Gives the list a fixed {@code height} and lets each row take its natural height, instead of hugging the
     * row count with {@link #CELL_HEIGHT} rows — so a picker can match the file finder's card ({@code C-x b}
     * beside {@code C-x C-f}).
     */
    public void setFixedListHeight(double height) {
        this.fixedListHeight = height;
        if (built) {
            followCellSize();
            resizeList();
        }
    }

    /** Rows of {@link #CELL_HEIGHT}, or their natural height under {@link #setFixedListHeight}. */
    private void followCellSize() {
        UiZoom.follow(
                list,
                "cellSize",
                () -> list.setFixedCellSize(fixedListHeight > 0 ? Region.USE_COMPUTED_SIZE : UiZoom.px(CELL_HEIGHT)));
    }

    /** Builds the card now if nothing has yet: the first show does, and a test that reads the card before one. */
    void ensureBuilt() {
        if (!built) {
            build();
            built = true;
        }
    }

    @Initializer
    private void build() {
        input = new TextField();
        list = new ListView<>();
        hint = new Label();
        emptyLabel = new Label();
        header = new Label();
        input.setPromptText(prompt);
        input.setAccessibleText(prompt);
        list.setItems(items);
        list.setAccessibleText(prompt);
        emptyLabel.getStyleClass().add("palette-hint");
        emptyLabel.setWrapText(true);
        if (emptyText != null) {
            emptyLabel.setText(placeholderText());
        }
        list.setPlaceholder(emptyLabel);
        // Hug the result count: a fixed cell size lets the list size to its rows, capped at MAX_VISIBLE
        // (then it scrolls), so the popup isn't a fixed-tall box with empty space for a few matches.
        followCellSize();
        items.addListener((javafx.collections.ListChangeListener<T>) c -> resizeList());
        list.setCellFactory(v -> new ItemCell());
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (showing && onPreview != null && now != null) {
                onPreview.accept(now);
            }
        });

        input.textProperty().addListener((obs, old, now) -> {
            chooseWhenLanded = false; // typed on after Enter: the Enter was for the query before
            filter(now);
        });
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // Emacs caret movement + basic editing in the query field. Registered after onKey so the picker's own
        // list navigation (C-n/C-p/C-g) consumes those chords first and the keymap yields to it (isConsumed).
        com.editora.command.TextInputKeymap.installShared(input);
        header.setText(title);
        header.getStyleClass().add("palette-title");
        hint.getStyleClass().add("palette-hint");
        content = UiZoom.spacing(new VBox(header, input, list, hint), 6);
        content.getStyleClass().add("command-palette");
        UiZoom.prefWidth(content, cardWidth);
        UiZoom.maxSize(content, cardWidth, Region.USE_PREF_SIZE); // hug content; don't stretch to fill the overlay
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE); // keep C-n/C-p/arrows for the picker
        UiZoom.follow(list, "listHeight", this::resizeList);
    }

    /** Sizes the result list to its (filtered) item count, capped at {@link #MAX_VISIBLE} rows, so the
     *  popup hugs the matches instead of reserving a fixed-tall area. */
    private void resizeList() {
        if (fixedListHeight > 0) {
            list.setMinHeight(Region.USE_COMPUTED_SIZE);
            list.setPrefHeight(UiZoom.px(fixedListHeight));
            list.setMaxHeight(Region.USE_COMPUTED_SIZE);
            return;
        }
        int rows = Math.max(minRows, Math.min(Math.max(items.size(), minRows), MAX_VISIBLE));
        double h = UiZoom.px(rows * CELL_HEIGHT + 2); // +2 for the list's border/insets
        list.setMinHeight(h);
        list.setPrefHeight(h);
        list.setMaxHeight(h);
    }

    private void onKey(KeyEvent e) {
        for (RowAction<T> row : rowActions) {
            if (row.key().match(e)) {
                int at = list.getSelectionModel().getSelectedIndex();
                T item = list.getSelectionModel().getSelectedItem();
                if (item != null) {
                    row.action().accept(item);
                    if (showing) {
                        all = itemsSupplier.get();
                        keys = null;
                        filter(input.getText(), at); // the cursor stays at the same place once the rows land
                    }
                }
                e.consume();
                return;
            }
        }
        PickerKeys.Action action = PickerKeys.action(e);
        switch (action) {
            case CANCEL -> hide();
            case ACCEPT -> chooseSelected();
            default -> {
                if (!PickerKeys.navigate(list, action)) {
                    return;
                }
            }
        }
        e.consume();
    }

    private void chooseSelected() {
        if (search.pending()) {
            chooseWhenLanded = true; // the rows shown answer an older query: choose from the latest once it lands
            return;
        }
        chooseRow();
    }

    /** Chooses the highlighted row (a click chooses the row clicked, which is shown and so known). */
    private void chooseRow() {
        T item = list.getSelectionModel().getSelectedItem();
        if (item != null) {
            chosen = true; // must be set before hide(), which is what runs the cancel hook
            hide();
            onChoose.accept(item);
        }
    }

    /** Where a typed query is matched against the rows: a picker thread, never the FX thread. */
    static final String FILTER_SITE = "QuickOpen.filter";

    /** The filter in flight: a newer keystroke supersedes it, and only the latest answer is shown. */
    private final LatestOnly search = PickerSearch.latest();

    /** Enter was pressed while the rows for the query were still coming: choose once they land. */
    private boolean chooseWhenLanded;

    /** {@link #searchKey} of each of {@link #all}, taken once per list on the FX thread; null until a query needs it. */
    private @Nullable List<String> keys;

    /** Whether the rows shown are not yet those for the query typed (a test waits on it). */
    boolean filtering() {
        return search.pending();
    }

    private void filter(String query) {
        filter(query, -1);
    }

    /**
     * Shows the rows matching {@code query}: at once for an empty one (the source's own order), else matched off the FX
     * thread (PERF-01, Pauta 6.0.1) — the list may be thousands long (Loaded Sources, Modules) — on a copy of the rows
     * and their search keys; the rows shown stay until the latest query's answer lands. {@code keepAt} is the row the
     * cursor goes back to (a row action), or -1 for the usual first row.
     */
    private void filter(String query, int keepAt) {
        String q = query == null ? "" : query.trim();
        List<T> rows = all;
        if (q.isEmpty()) {
            // With nothing typed the supplier's own order is the meaningful one — recency for recent
            // files, document order for symbols — so it is passed through exactly as given.
            search.cancel();
            landed(q, new ArrayList<>(rows), keepAt);
            return;
        }
        List<String> matchKeys = keys;
        if (matchKeys == null || matchKeys.size() != rows.size()) {
            List<String> taken = new ArrayList<>(rows.size());
            for (T item : rows) {
                String key = searchKey.apply(item);
                taken.add(key == null ? "" : key);
            }
            matchKeys = List.copyOf(taken);
            keys = matchKeys;
        }
        List<String> matched = matchKeys;
        search.submit(
                () -> {
                    com.editora.perf.BlockingSites.enter(FILTER_SITE);
                    record Scored(int index, int score) {}
                    List<Scored> scored = new ArrayList<>();
                    for (int i = 0; i < matched.size(); i++) {
                        if (Thread.currentThread().isInterrupted()) {
                            return List.<T>of(); // superseded: the answer is dropped anyway
                        }
                        FuzzyMatch.Match m = FuzzyMatch.of(matched.get(i), q);
                        if (m != null) {
                            scored.add(new Scored(i, m.score()));
                        }
                    }
                    // The supplier's index is the final tiebreak, so equal-scoring rows keep their original
                    // relative order instead of shuffling under the cursor between keystrokes.
                    scored.sort(
                            Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::index));
                    List<T> matches = new ArrayList<>(scored.size());
                    for (Scored s : scored) {
                        matches.add(rows.get(s.index()));
                    }
                    return matches;
                },
                matches -> landed(q, matches, keepAt));
    }

    /** The rows for {@code q}: shown, the cursor placed, and chosen when Enter was waiting for them. */
    private void landed(String q, List<T> matches, int keepAt) {
        currentQuery = q;
        items.setAll(matches);
        if (items.isEmpty()) {
            emptyLabel.setText(placeholderText());
            chooseWhenLanded = false;
            return;
        }
        // With no query, start on the value in force when one was supplied; otherwise the first row.
        int start = 0;
        if (keepAt >= 0) {
            start = Math.min(keepAt, items.size() - 1);
        } else if (q.isEmpty() && currentItem != null) {
            T current = currentItem.get();
            int idx = current == null ? -1 : items.indexOf(current);
            if (idx >= 0) {
                start = idx;
            }
        }
        list.getSelectionModel().select(start);
        list.scrollTo(start);
        PickerKeys.announce(list); // typing changed the row in front
        if (chooseWhenLanded) {
            chooseWhenLanded = false;
            chooseRow();
        }
    }

    /** Shows the picker as a centered in-scene overlay. {@code owner} is unused (kept for call-site
     *  compatibility); the shared {@link OverlayHost} positions it within the main scene. */
    public void show(Window owner) {
        if (overlayHost == null) {
            return;
        }
        ensureBuilt();
        all = itemsSupplier.get();
        keys = null;
        List<String> legend = new ArrayList<>();
        legend.add(PickerKeys.hint("select", "↵"));
        rowActions.forEach(r -> legend.add(r.legend()));
        hint.setText(PickerKeys.legend(legend.toArray(String[]::new))); // per show: follows a keymap switch
        input.clear();
        filter("");
        chosen = false;
        showing = true;
        overlayHost.show(content, input::requestFocus, () -> {
            showing = false;
            search.cancel(); // a filter landing after the card closed shows nothing and chooses nothing
            chooseWhenLanded = false;
            if (!chosen && onPreviewCancelled != null) {
                onPreviewCancelled.run();
            }
        });
    }

    /**
     * Re-reads the items while the picker is open, keeping what was typed — for a source whose content
     * arrived after {@link #show} (an outline the server had not answered yet).
     */
    public void reload() {
        if (showing) {
            all = itemsSupplier.get();
            keys = null;
            filter(input.getText());
        }
    }

    public void hide() {
        if (overlayHost != null) {
            overlayHost.hide();
        }
    }

    public boolean isShown() {
        return showing;
    }

    private final class ItemCell extends ListCell<T> {
        // A TextFlow rather than a Label, so the characters that matched can be emboldened. The icon
        // therefore needs its own slot: a TextFlow has no graphic property to hang it off.
        private final javafx.scene.text.TextFlow title = new javafx.scene.text.TextFlow();
        private final HBox titleBox = UiZoom.spacing(new HBox(title), 6);
        private final Label sub = new Label();
        private final HBox box = UiZoom.spacing(new HBox(titleBox, spacer(), sub), 10);
        private @Nullable String appliedClass;

        ItemCell() {
            // Never wider than the list. A row whose detail ran past it (Surround With's snippet bodies) gave
            // the list a horizontal scroll bar on one pulse and took it away on the next: the picker
            // flickered. The detail gives way with an ellipsis; the title keeps its width and never wraps.
            setPrefWidth(0);
            title.setMinWidth(Region.USE_PREF_SIZE);
            titleBox.setMinWidth(Region.USE_PREF_SIZE);
            titleBox.setAlignment(Pos.CENTER_LEFT);
            box.setAlignment(Pos.CENTER_LEFT);
            sub.getStyleClass().add("keybinding"); // reuse the palette's muted right-detail style
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
                    getListView().getSelectionModel().select(getItem());
                    chooseRow();
                }
            });
        }

        private Region spacer() {
            Region r = new Region();
            HBox.setHgrow(r, Priority.ALWAYS);
            return r;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            // A per-row treatment (an unsaved buffer's amber italic) replaces the plain run style rather
            // than layering on top of it, so the two can't fight over the text colour; the matched runs
            // keep their own accent either way.
            appliedClass = itemStyleClass == null ? null : itemStyleClass.apply(item);
            String plainClass = appliedClass == null ? MatchText.PLAIN : appliedClass;
            title.getChildren().setAll(MatchText.runs(label.apply(item), currentQuery, plainClass));
            javafx.scene.Node icon = itemIcon == null ? null : itemIcon.apply(item);
            if (icon == null) {
                titleBox.getChildren().setAll(title);
            } else {
                titleBox.getChildren().setAll(icon, title);
            }
            String d = detail == null ? null : detail.apply(item);
            sub.setText(d == null ? "" : d);
            setGraphic(CellNames.read(box));
        }
    }
}
