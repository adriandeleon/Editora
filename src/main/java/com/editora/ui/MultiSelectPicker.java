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

    private MultiSelectPicker() {}

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
        if (host == null || items == null || items.isEmpty()) {
            return;
        }
        List<CheckBox> boxes = new ArrayList<>(items.size());
        ListView<CheckBox> list = new ListView<>();
        for (Item<T> it : items) {
            CheckBox cb = new CheckBox(it.label());
            cb.setSelected(it.preselected());
            cb.setFocusTraversable(false); // the ListView owns focus; Space toggles the selected row
            boxes.add(cb);
            list.getItems().add(cb);
        }
        list.getSelectionModel().select(0);
        list.setPrefHeight(Math.min(360, 28.0 * items.size() + 16));

        Label heading = new Label(title);
        heading.getStyleClass().add("overlay-title");
        Hyperlink all = new Hyperlink(tr("picker.selectAll"));
        Hyperlink none = new Hyperlink(tr("picker.selectNone"));
        all.setOnAction(e -> boxes.forEach(b -> b.setSelected(true)));
        none.setOnAction(e -> boxes.forEach(b -> b.setSelected(false)));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, heading, spacer, all, none);
        Label hint = new Label(tr("picker.hint"));
        hint.getStyleClass().add("overlay-hint");

        VBox card = new VBox(8, header, list, hint);
        card.getStyleClass().add("multi-select-picker");
        card.setPadding(new Insets(12));
        card.setPrefWidth(520);
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
                host.hide();
                if (!chosen.isEmpty()) {
                    onAccept.accept(chosen);
                }
            } else if (e.isControlDown() && (e.getCode() == KeyCode.N || e.getCode() == KeyCode.P)) {
                int i = list.getSelectionModel().getSelectedIndex();
                int next = e.getCode() == KeyCode.N ? i + 1 : i - 1;
                if (next >= 0 && next < boxes.size()) {
                    list.getSelectionModel().select(next);
                    list.scrollTo(next);
                }
                e.consume();
            }
        });
        host.show(card, () -> list.requestFocus(), () -> {});
    }
}
