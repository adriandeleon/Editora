package com.editora.editor;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import javafx.stage.Window;

import com.editora.completion.Completion;
import com.editora.completion.MatchHighlighter;

/**
 * The autocomplete dropdown: a <b>focus-less</b> {@link Popup} holding a {@link ListView} of
 * {@link Completion}s, anchored just below the caret. Unlike the modal palette/finder popups, this one
 * keeps focus in the editor — the editor's key filter drives selection (↑/↓), acceptance (Enter/Tab) and
 * dismissal (Esc); a mouse click also accepts. Styled via {@code .completion-popup}/{@code .completion-cell}
 * in {@code app.css} (AtlantaFX semantic colors, so it tracks the theme).
 *
 * <p>IntelliJ-style each row shows a per-kind icon, the label with the typed characters bolded
 * ({@link MatchHighlighter}), and a muted right-aligned type/detail; deprecated entries are struck through.
 */
public final class CompletionPopup {

    private static final int VISIBLE_ROWS = 9;
    private static final double ROW_HEIGHT = 24;
    private static final double MIN_WIDTH = 360;
    private static final double MAX_WIDTH = 760;
    private static final double DETAIL_MAX_WIDTH = 380;
    // Cell geometry used to size the popup to its widest row (icon + gaps + paddings + label↔detail gap).
    private static final double ICON_WIDTH = 18;
    private static final double ROW_GAP = 6;
    private static final double LABEL_DETAIL_GAP = 28;
    private static final double H_PADDING = 18;
    private static final double SCROLLBAR_ALLOWANCE = 14;

    private final Popup popup = new Popup();
    private final ListView<Completion> list = new ListView<>();
    private Consumer<Completion> onAccept = c -> {};
    private Consumer<Completion> onSelect = c -> {};
    private String query = "";
    /** Index of the top visible row; we manage scrolling so the list only scrolls at the window edges. */
    private int firstVisible;

    public CompletionPopup() {
        list.getStyleClass().add("completion-list");
        list.setFocusTraversable(false);
        list.setFixedCellSize(ROW_HEIGHT);
        list.setPrefWidth(MIN_WIDTH);
        list.setCellFactory(v -> new CompletionCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                Completion sel = selected();
                if (sel != null) {
                    onAccept.accept(sel);
                }
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> onSelect.accept(now));
        VBox box = new VBox(list);
        box.getStyleClass().add("completion-popup");
        // A Popup has its own scene that does NOT inherit the editor scene's stylesheets, so without this
        // app.css (and its `.completion-*` rules + AtlantaFX `-color-*` vars) never apply and the list
        // falls back to bare default colors. Attach it to the content subtree (the note-tooltip idiom).
        java.net.URL css = CompletionPopup.class.getResource("/com/editora/styles/app.css");
        if (css != null) {
            box.getStylesheets().add(css.toExternalForm());
        }
        popup.getContent().add(box);
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        popup.setHideOnEscape(false); // the editor's filter handles Escape so it can also stop typing
    }

    /** Sets the callback invoked when a row is clicked (keyboard accept is handled by the editor). */
    public void setOnAccept(Consumer<Completion> onAccept) {
        this.onAccept = onAccept == null ? c -> {} : onAccept;
    }

    /** Sets the callback invoked when the selected row changes (drives the documentation side-popup). */
    public void setOnSelect(Consumer<Completion> onSelect) {
        this.onSelect = onSelect == null ? c -> {} : onSelect;
    }

    /** Runs when the popup hides — including auto-hide — so the editor can tear down the doc side-popup. */
    public void setOnHidden(Runnable onHidden) {
        popup.setOnHidden(e -> onHidden.run());
    }

    /** The typed prefix used to bold matched characters in each label. */
    public void setQuery(String query) {
        this.query = query == null ? "" : query;
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

    public void pageUp() {
        move(-VISIBLE_ROWS);
    }

    public void pageDown() {
        move(VISIBLE_ROWS);
    }

    private void move(int delta) {
        int n = list.getItems().size();
        if (n == 0) {
            return;
        }
        int i = list.getSelectionModel().getSelectedIndex();
        int base = i < 0 ? 0 : i;
        int next;
        if (Math.abs(delta) == 1) {
            next = Math.floorMod(base + delta, n); // single-step wraps around
        } else {
            next = Math.max(0, Math.min(n - 1, base + delta)); // paging clamps at the ends
        }
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
     * coordinates). Empty items hide it. {@code selectIndex} is the initially-selected row (clamped).
     */
    public void show(Window owner, Bounds caretScreen, List<Completion> items, int selectIndex) {
        if (owner == null || caretScreen == null || items == null || items.isEmpty()) {
            hide();
            return;
        }
        list.getItems().setAll(items);
        int sel = Math.max(0, Math.min(selectIndex, items.size() - 1));
        list.getSelectionModel().select(sel);
        firstVisible = 0; // reset the scroll window to the top for the new list
        if (items.size() > VISIBLE_ROWS) {
            list.scrollTo(0);
        }
        scrollIntoView(sel, items.size());
        int rows = Math.min(items.size(), VISIBLE_ROWS);
        // Exact fit so no vertical scrollbar shows unless the list actually overflows (> VISIBLE_ROWS).
        double h = rows * ROW_HEIGHT + 2; // +2 for the list's 1px top/bottom border
        list.setPrefHeight(h);
        list.setMinHeight(h);
        list.setMaxHeight(h);
        double w = contentWidth(items); // size to the widest row (IntelliJ-style), capped
        list.setPrefWidth(w);
        list.setMinWidth(w);
        list.setMaxWidth(w);
        double x = caretScreen.getMinX();
        double y = caretScreen.getMaxY() + 2;
        if (popup.isShowing()) {
            popup.setAnchorX(x);
            popup.setAnchorY(y);
        } else {
            popup.show(owner, x, y);
        }
    }

    /** The popup's on-screen bounds (for positioning the doc popup beside it), or null when hidden. */
    public Bounds screenBounds() {
        if (!popup.isShowing()) {
            return null;
        }
        VBox box = (VBox) popup.getContent().get(0);
        return box.localToScreen(box.getBoundsInLocal());
    }

    public void hide() {
        if (popup.isShowing()) {
            popup.hide();
        }
        list.getItems().clear();
    }

    /**
     * The popup width that fits the widest row — icon + label + the right-aligned type/detail — so the
     * type column isn't clipped (IntelliJ-style), clamped to {@code [MIN_WIDTH, MAX_WIDTH]}. Text is
     * measured with the popup's font sizes (label 13px, detail 11px) plus geometry slack; beyond the cap
     * the detail Label ellipsizes.
     */
    private static double contentWidth(List<Completion> items) {
        Font labelFont = Font.font(13);
        Font detailFont = Font.font(11);
        Text measure = new Text();
        double widest = 0;
        boolean overflow = items.size() > VISIBLE_ROWS;
        for (Completion c : items) {
            measure.setFont(labelFont);
            measure.setText(c.label() == null ? "" : c.label());
            double labelW = measure.getLayoutBounds().getWidth();
            double detailW = 0;
            String d = c.detail();
            if (d != null && !d.isEmpty()) {
                measure.setFont(detailFont);
                measure.setText(d);
                detailW = Math.min(measure.getLayoutBounds().getWidth(), DETAIL_MAX_WIDTH);
            }
            double row = ICON_WIDTH
                    + ROW_GAP
                    + labelW
                    + (detailW > 0 ? LABEL_DETAIL_GAP + detailW : 0)
                    + H_PADDING
                    + (overflow ? SCROLLBAR_ALLOWANCE : 0);
            widest = Math.max(widest, row);
        }
        return Math.max(MIN_WIDTH, Math.min(widest, MAX_WIDTH));
    }

    private final class CompletionCell extends ListCell<Completion> {
        private final TextFlow label = new TextFlow();
        private final Label detail = new Label();
        private final HBox row;
        private Node iconHolder;

        CompletionCell() {
            getStyleClass().add("completion-cell");
            label.getStyleClass().add("completion-label");
            detail.getStyleClass().add("completion-detail");
            detail.setMaxWidth(DETAIL_MAX_WIDTH);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row = new HBox(6, label, spacer, detail);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Completion item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            // Icon (rebuilt per item — a JavaFX node can only have one parent / the cell is recycled).
            Node icon = CompletionIcons.forKind(item.iconKind());
            if (iconHolder == null) {
                row.getChildren().add(0, icon);
            } else {
                row.getChildren().set(0, icon);
            }
            iconHolder = icon;
            // Label: bold the matched characters.
            label.getChildren().setAll(buildLabel(item));
            detail.setText(item.detail() == null ? "" : item.detail());
            getStyleClass().removeAll("completion-kind-snippet", "completion-kind-word", "completion-deprecated");
            getStyleClass()
                    .add(item.kind() == Completion.Kind.SNIPPET ? "completion-kind-snippet" : "completion-kind-word");
            if (item.deprecated()) {
                getStyleClass().add("completion-deprecated");
            }
            setGraphic(row);
        }

        /** Splits the label into matched (bold) and plain {@link Text} runs for the current query. */
        private List<Text> buildLabel(Completion item) {
            String text = item.label();
            int[][] ranges = MatchHighlighter.matchRanges(text, query);
            List<Text> parts = new java.util.ArrayList<>();
            int pos = 0;
            for (int[] r : ranges) {
                if (r[0] > pos) {
                    parts.add(textRun(text.substring(pos, r[0]), false, item.deprecated()));
                }
                parts.add(textRun(text.substring(r[0], r[1]), true, item.deprecated()));
                pos = r[1];
            }
            if (pos < text.length()) {
                parts.add(textRun(text.substring(pos), false, item.deprecated()));
            }
            return parts;
        }

        private Text textRun(String s, boolean matched, boolean deprecated) {
            Text t = new Text(s);
            t.getStyleClass().add(matched ? "completion-match" : "completion-label-text");
            t.setStrikethrough(deprecated);
            return t;
        }
    }
}
