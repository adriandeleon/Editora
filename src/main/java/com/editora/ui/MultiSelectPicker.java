package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import static com.editora.i18n.Messages.tr;

/**
 * A checkbox multi-select shown as an in-scene card in the shared {@link OverlayHost} — the "pick which
 * members" step of jdtls's source generators (#741), where {@link QuickOpen} can't be reused because it
 * picks exactly one item.
 *
 * <p>Keyboard-first, matching the rest of the overlay family: {@code ↑}/{@code ↓} (and {@code C-n}/{@code C-p})
 * move, {@code Space} toggles the focused row, {@code Enter} accepts, {@code Esc}/{@code C-g} cancel (the
 * host handles those). {@code onAccept} runs after the card hides, so focus is already back in the editor.
 *
 * <p>Accepting with nothing ticked is treated as a cancel rather than as "generate an empty member list" —
 * the latter silently produces a {@code toString()} with no fields, which reads as a bug.
 */
public final class MultiSelectPicker {

    private static final double ROW_HEIGHT = 30;
    private static final int VISIBLE_ROWS = 12;

    private static final double MIN_WIDTH = 520;
    private static final double MAX_WIDTH = 1000;

    private MultiSelectPicker() {}

    /**
     * Wide enough for the longest row, within {@link #MIN_WIDTH}–{@link #MAX_WIDTH}: a rename preview's paths
     * were cut off at a fixed 520 px (Java review B10).
     */
    static <T> double cardWidth(List<Item<T>> items) {
        double widest = 0;
        for (Item<T> it : items) {
            widest = Math.max(
                    widest,
                    new javafx.scene.text.Text(it.label()).getLayoutBounds().getWidth());
        }
        // checkbox, list padding and scrollbar, card padding
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, widest + 90));
    }

    /** One row: its display label and whether it starts ticked (jdtls pre-selects sensible defaults). */
    public record Item<T>(String label, boolean preselected, T value) {}

    /**
     * Shows the picker.
     *
     * @param host     the shared overlay host
     * @param title    card title (e.g. "Generate toString()")
     * @param items    the rows, in the server's order
     * @param onAccept receives the chosen values, in the listed order; never called with an empty list
     */
    public static <T> void show(OverlayHost host, String title, List<Item<T>> items, Consumer<List<T>> onAccept) {
        show(host, title, items, tr("picker.hint"), onAccept);
    }

    /** As above with the hint line's own words: a refactoring preview applies, it does not generate (B10). */
    public static <T> void show(
            OverlayHost host, String title, List<Item<T>> items, String hintText, Consumer<List<T>> onAccept) {
        if (host == null || items == null || items.isEmpty()) {
            return;
        }
        List<CheckBox> boxes = new ArrayList<>(items.size());
        ListView<CheckBox> list = new ListView<>();
        for (Item<T> it : items) {
            CheckBox cb = new CheckBox(it.label());
            cb.setTooltip(new javafx.scene.control.Tooltip(it.label())); // a row still too long says it whole
            cb.setSelected(it.preselected());
            cb.setFocusTraversable(false); // the ListView owns focus; Space toggles the selected row
            boxes.add(cb);
            list.getItems().add(cb);
        }
        list.getSelectionModel().select(0);
        // A fixed row height, and the card no taller than its rows: it filled the window yet showed about two
        // rows, hiding the rename preview's "moves to …" row (Java review B10).
        list.setFixedCellSize(ROW_HEIGHT);
        list.setPrefHeight(Math.min(VISIBLE_ROWS, items.size()) * ROW_HEIGHT + 4);

        Label heading = new Label(title);
        heading.getStyleClass().add("overlay-title");
        heading.setWrapText(true); // "Rename to "Figure" — 3 changes in 3 fi…" (B10)
        heading.setMinWidth(0);
        HBox.setHgrow(heading, Priority.ALWAYS);
        Hyperlink all = new Hyperlink(tr("picker.selectAll"));
        Hyperlink none = new Hyperlink(tr("picker.selectNone"));
        all.setOnAction(e -> boxes.forEach(b -> b.setSelected(true)));
        none.setOnAction(e -> boxes.forEach(b -> b.setSelected(false)));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, heading, spacer, all, none);
        Label hint = new Label(hintText);
        hint.getStyleClass().add("overlay-hint");

        VBox card = new VBox(8, header, list, hint);
        card.getStyleClass().add("multi-select-picker");
        card.setPadding(new Insets(12));
        card.setPrefWidth(cardWidth(items));
        card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        // The card owns its keys: without this the global KeyDispatcher would take C-n/C-p as caret motion.
        card.getProperties().put("editora.ownsKeys", true);

        card.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE) {
                CheckBox sel = list.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    sel.setSelected(!sel.isSelected());
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                List<T> chosen = new ArrayList<>();
                for (int i = 0; i < boxes.size(); i++) {
                    if (boxes.get(i).isSelected()) {
                        chosen.add(items.get(i).value());
                    }
                }
                e.consume();
                if (chosen.isEmpty()) {
                    hint.setText(tr("picker.nothingSelected")); // closing silently read as "done" (B9)
                    return;
                }
                host.hide();
                onAccept.accept(chosen);
            } else if (PickerKeys.navigate(
                    list, PickerKeys.action(e, com.editora.command.TextInputKeymap.sharedKeymap(), false))) {
                e.consume(); // arrows, PageUp/PageDown, Home/End and the keymap's line-up/down chords
            }
        });
        host.show(card, () -> list.requestFocus(), () -> {});
    }
}
