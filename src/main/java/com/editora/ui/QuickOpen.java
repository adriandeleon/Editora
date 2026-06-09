package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

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

    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    private final Supplier<List<T>> itemsSupplier;
    private final Function<T, String> label;
    private final Function<T, String> detail;
    /** Text the typed query is matched against; defaults to {@link #label} (the displayed text). */
    private final Function<T, String> searchKey;
    private final Consumer<T> onChoose;
    /** Optional per-item CSS class applied to the row's title label (e.g. "dirty-name"); null = none. */
    private Function<T, String> itemStyleClass;

    private final TextField input = new TextField();
    private final ListView<T> list = new ListView<>();
    private final ObservableList<T> items = FXCollections.observableArrayList();
    private List<T> all = List.of();

    /** Shared in-scene overlay host (injected by MainController) + the card it shows. */
    private OverlayHost overlayHost;
    private VBox content;
    private boolean showing;

    public QuickOpen(String title, String prompt, Supplier<List<T>> itemsSupplier,
                     Function<T, String> label, Function<T, String> detail, Consumer<T> onChoose) {
        this(title, prompt, itemsSupplier, label, detail, label, onChoose);
    }

    /**
     * As above but with an explicit {@code searchKey} the typed query matches against (instead of the
     * displayed {@code label}) — e.g. to search a note's full body/tags/file while showing a short label.
     */
    public QuickOpen(String title, String prompt, Supplier<List<T>> itemsSupplier,
                     Function<T, String> label, Function<T, String> detail,
                     Function<T, String> searchKey, Consumer<T> onChoose) {
        this.itemsSupplier = itemsSupplier;
        this.label = label;
        this.detail = detail;
        this.searchKey = searchKey == null ? label : searchKey;
        this.onChoose = onChoose;
        build(title, prompt);
    }

    /** Sets a per-item CSS class applied to the row's title label (e.g. "dirty-name" for unsaved files). */
    public void setItemStyleClass(Function<T, String> itemStyleClass) {
        this.itemStyleClass = itemStyleClass;
    }

    /** Injects the shared overlay host used to show the picker card. */
    public void setOverlayHost(OverlayHost overlayHost) {
        this.overlayHost = overlayHost;
    }

    private void build(String title, String prompt) {
        input.setPromptText(prompt);
        list.setItems(items);
        list.setPrefHeight(280);
        list.setCellFactory(v -> new ItemCell());

        input.textProperty().addListener((obs, old, now) -> filter(now));
        input.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        // On macOS the opening chord can emit an Option-composed character; swallow chars typed with a
        // chord modifier held (plain typing passes through). The opening chord's trailing KEY_TYPED is
        // already swallowed by the global KeyDispatcher (the card lives in the main scene now).
        if (IS_MAC) {
            input.addEventFilter(KeyEvent.KEY_TYPED, e -> {
                if (e.isAltDown() || e.isMetaDown() || e.isControlDown() || e.isShortcutDown()) {
                    e.consume();
                }
            });
        }

        Label header = new Label(title);
        header.getStyleClass().add("palette-title");
        Label hint = new Label("↑↓ / C-n C-p move  ·  ↵ select  ·  esc / C-g cancel");
        hint.getStyleClass().add("palette-hint");
        content = new VBox(6, header, input, list, hint);
        content.getStyleClass().add("command-palette");
        content.setPrefWidth(620);
        content.setMaxSize(620, Region.USE_PREF_SIZE); // hug content; don't stretch to fill the overlay
        content.getProperties().put("editora.ownsKeys", Boolean.TRUE); // keep C-n/C-p/arrows for the picker
    }

    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                e.consume();
            }
            case ENTER -> {
                chooseSelected();
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
            default -> {
            }
        }
    }

    private void move(int delta) {
        int size = items.size();
        if (size == 0) {
            return;
        }
        int idx = Math.floorMod(list.getSelectionModel().getSelectedIndex() + delta, size);
        list.getSelectionModel().select(idx);
        list.scrollTo(idx);
    }

    private void chooseSelected() {
        T item = list.getSelectionModel().getSelectedItem();
        if (item != null) {
            hide();
            onChoose.accept(item);
        }
    }

    private void filter(String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        List<T> matches = new ArrayList<>();
        for (T item : all) {
            if (q.isEmpty() || CommandPalette.isSubsequence(q, searchKey.apply(item).toLowerCase(Locale.ROOT))) {
                matches.add(item);
            }
        }
        items.setAll(matches);
        if (!items.isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    /** Shows the picker as a centered in-scene overlay. {@code owner} is unused (kept for call-site
     *  compatibility); the shared {@link OverlayHost} positions it within the main scene. */
    public void show(Window owner) {
        if (overlayHost == null) {
            return;
        }
        all = itemsSupplier.get();
        input.clear();
        filter("");
        showing = true;
        overlayHost.show(content, input::requestFocus, () -> showing = false);
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
        private final Label title = new Label();
        private final Label sub = new Label();
        private final HBox box = new HBox(10, title, spacer(), sub);
        private String appliedClass;

        ItemCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            sub.getStyleClass().add("keybinding"); // reuse the palette's muted right-detail style
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty() && getItem() != null) {
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
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText(label.apply(item));
            if (appliedClass != null) {
                title.getStyleClass().remove(appliedClass);
            }
            appliedClass = itemStyleClass == null ? null : itemStyleClass.apply(item);
            if (appliedClass != null && !title.getStyleClass().contains(appliedClass)) {
                title.getStyleClass().add(appliedClass);
            }
            String d = detail == null ? null : detail.apply(item);
            sub.setText(d == null ? "" : d);
            setGraphic(box);
        }
    }
}
