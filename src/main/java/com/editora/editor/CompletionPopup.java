package com.editora.editor;

import com.editora.completion.Completion;
import java.util.List;
import java.util.function.Consumer;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

/**
 * The autocomplete dropdown: a <b>focus-less</b> {@link Popup} holding a {@link ListView} of
 * {@link Completion}s, anchored just below the caret. Unlike the modal palette/finder popups, this one
 * keeps focus in the editor — the editor's key filter drives selection (↑/↓), acceptance (Enter/Tab) and
 * dismissal (Esc); a mouse click also accepts. Styled via {@code .completion-popup}/{@code .completion-cell}
 * in {@code app.css} (AtlantaFX semantic colors, so it tracks the theme).
 */
public final class CompletionPopup {

    private static final int VISIBLE_ROWS = 9;
    private static final double ROW_HEIGHT = 24;
    private static final double WIDTH = 380;

    private final Popup popup = new Popup();
    private final ListView<Completion> list = new ListView<>();
    private Consumer<Completion> onAccept = c -> {};
    /** Index of the top visible row; we manage scrolling so the list only scrolls at the window edges. */
    private int firstVisible;

    public CompletionPopup() {
        list.getStyleClass().add("completion-list");
        list.setFocusTraversable(false);
        list.setFixedCellSize(ROW_HEIGHT);
        list.setPrefWidth(WIDTH);
        list.setCellFactory(v -> new CompletionCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                Completion sel = selected();
                if (sel != null) {
                    onAccept.accept(sel);
                }
            }
        });
        VBox box = new VBox(list);
        box.getStyleClass().add("completion-popup");
        popup.getContent().add(box);
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        popup.setHideOnEscape(false); // the editor's filter handles Escape so it can also stop typing
    }

    /** Sets the callback invoked when a row is clicked (keyboard accept is handled by the editor). */
    public void setOnAccept(Consumer<Completion> onAccept) {
        this.onAccept = onAccept == null ? c -> {} : onAccept;
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    public Completion selected() {
        return list.getSelectionModel().getSelectedItem();
    }

    public void moveUp() {
        move(-1);
    }

    public void moveDown() {
        move(1);
    }

    private void move(int delta) {
        int n = list.getItems().size();
        if (n == 0) {
            return;
        }
        int i = list.getSelectionModel().getSelectedIndex();
        int next = Math.floorMod((i < 0 ? 0 : i) + delta, n);
        list.getSelectionModel().select(next);
        scrollIntoView(next, n);
    }

    /**
     * Scrolls only when {@code index} falls outside the currently visible window — so arrowing within the
     * visible rows never scrolls; it scrolls one row at a time at the top/bottom edge (and jumps on a
     * wrap-around). When everything fits ({@code n <= VISIBLE_ROWS}) it never scrolls.
     */
    private void scrollIntoView(int index, int n) {
        if (n <= VISIBLE_ROWS) {
            firstVisible = 0;
            return;
        }
        if (index < firstVisible) {
            firstVisible = index; // moved above the window → it becomes the top row
        } else if (index > firstVisible + VISIBLE_ROWS - 1) {
            firstVisible = index - VISIBLE_ROWS + 1; // moved below → it becomes the bottom row
        } else {
            return; // already visible → no scroll
        }
        firstVisible = Math.max(0, Math.min(firstVisible, n - VISIBLE_ROWS));
        list.scrollTo(firstVisible);
    }

    /**
     * Shows/updates the popup with {@code items} anchored below the caret ({@code caretScreen} in screen
     * coordinates). Empty items hide it. The first row is selected.
     */
    public void show(Window owner, Bounds caretScreen, List<Completion> items) {
        if (owner == null || caretScreen == null || items == null || items.isEmpty()) {
            hide();
            return;
        }
        list.getItems().setAll(items);
        list.getSelectionModel().select(0);
        firstVisible = 0; // reset the scroll window to the top for the new list
        if (items.size() > VISIBLE_ROWS) {
            list.scrollTo(0);
        }
        int rows = Math.min(items.size(), VISIBLE_ROWS);
        // Exact fit so no vertical scrollbar shows unless the list actually overflows (> VISIBLE_ROWS).
        double h = rows * ROW_HEIGHT + 2; // +2 for the list's 1px top/bottom border
        list.setPrefHeight(h);
        list.setMinHeight(h);
        list.setMaxHeight(h);
        double x = caretScreen.getMinX();
        double y = caretScreen.getMaxY() + 2;
        if (popup.isShowing()) {
            popup.setAnchorX(x);
            popup.setAnchorY(y);
        } else {
            popup.show(owner, x, y);
        }
    }

    public void hide() {
        if (popup.isShowing()) {
            popup.hide();
        }
        list.getItems().clear();
    }

    private static final class CompletionCell extends ListCell<Completion> {
        private final Label label = new Label();
        private final Label detail = new Label();
        private final HBox row;

        CompletionCell() {
            getStyleClass().add("completion-cell");
            label.getStyleClass().add("completion-label");
            detail.getStyleClass().add("completion-detail");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row = new HBox(8, label, spacer, detail);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Completion item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            label.setText(item.label());
            detail.setText(item.detail() == null ? "" : item.detail());
            getStyleClass().removeAll("completion-kind-snippet", "completion-kind-word");
            getStyleClass()
                    .add(item.kind() == Completion.Kind.SNIPPET ? "completion-kind-snippet" : "completion-kind-word");
            setGraphic(row);
        }
    }
}
