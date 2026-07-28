package com.editora.editor;

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
 * The quick-fix / refactoring list, anchored <b>at the caret</b> — IntelliJ's Alt+Enter intention popup and
 * Eclipse's Ctrl+1 (#767).
 *
 * <p>The protocol side of code actions already worked; this replaces the presentation. It was a
 * {@code QuickOpen} overlay: a card centred near the top of the window, the same surface used for "pick a
 * project". That is the wrong place for something that acts on the symbol under the cursor — the user is
 * looking at the caret, and the list appeared somewhere else entirely.
 *
 * <p>Structurally this is {@link CompletionPopup}: a <b>focus-less</b> {@link Popup}, so the editor keeps
 * focus and its key filter drives selection, acceptance and dismissal. It reuses the completion popup's style
 * classes rather than introducing parallel ones, so it tracks the theme with no new CSS and the two read as
 * the same kind of object — which they are.
 */
public final class CodeActionPopup {

    private static final int VISIBLE_ROWS = 9;
    private static final double ROW_HEIGHT = 24;
    private static final double MIN_WIDTH = 320;
    private static final double MAX_WIDTH = 720;
    private static final double CHAR_PX = 7.2;
    private static final double H_PADDING = 26;
    private static final double KIND_GAP = 24;

    private final Popup popup = new Popup();
    private final ListView<CodeAction> list = new ListView<>();
    private Consumer<CodeAction> onAccept = a -> {};

    public CodeActionPopup() {
        list.getStyleClass().addAll("completion-list", "code-action-list");
        list.setFocusTraversable(false);
        list.setFixedCellSize(ROW_HEIGHT);
        list.setCellFactory(v -> new ActionCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                CodeAction sel = selected();
                if (sel != null) {
                    onAccept.accept(sel);
                }
            }
        });
        VBox box = new VBox(list);
        box.getStyleClass().addAll("completion-popup", "code-action-popup");
        popup.getContent().add(box);
        popup.setAutoFix(true); // keep it on screen near a window edge
        popup.setAutoHide(false);
        // Escape must reach the editor's filter, not be swallowed by the Popup's own default handling: the
        // filter is what releases the key ownership taken when the list opened. With the default (true) the
        // popup hid itself first, the filter then saw a closed list, skipped its branch, and left the editor
        // owning chords it no longer needed. CompletionPopup sets this for the same reason.
        popup.setHideOnEscape(false);
    }

    /**
     * Notified whenever the popup hides, however it hid. A belt-and-braces release point: any path that
     * closes the popup without going through the editor's own hide leaves the key ownership stranded
     * otherwise, and not every such path is under this class's control.
     */
    public void setOnHidden(Runnable onHidden) {
        popup.setOnHidden(e -> onHidden.run());
    }

    public void setOnAccept(Consumer<CodeAction> onAccept) {
        this.onAccept = onAccept == null ? a -> {} : onAccept;
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    public CodeAction selected() {
        return list.getSelectionModel().getSelectedItem();
    }

    /** Fires the accept handler for the current selection, as a click does. */
    public void accept() {
        CodeAction sel = selected();
        if (sel != null) {
            onAccept.accept(sel);
        }
    }

    public void moveDown() {
        int n = list.getItems().size();
        if (n > 0) {
            list.getSelectionModel().select((list.getSelectionModel().getSelectedIndex() + 1) % n);
            list.scrollTo(list.getSelectionModel().getSelectedIndex());
        }
    }

    public void moveUp() {
        int n = list.getItems().size();
        if (n > 0) {
            int i = list.getSelectionModel().getSelectedIndex();
            list.getSelectionModel().select((i - 1 + n) % n);
            list.scrollTo(list.getSelectionModel().getSelectedIndex());
        }
    }

    /** Shows the list just below {@code caretScreen}, preselecting the server's preferred action if any. */
    public void show(Window owner, Bounds caretScreen, List<CodeAction> actions) {
        if (owner == null || caretScreen == null || actions == null || actions.isEmpty()) {
            hide();
            return;
        }
        list.getItems().setAll(actions);
        int preferred = 0;
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).preferred()) {
                preferred = i;
                break;
            }
        }
        list.getSelectionModel().select(preferred);
        list.scrollTo(preferred);

        int rows = Math.min(actions.size(), VISIBLE_ROWS);
        double h = rows * ROW_HEIGHT + 2; // +2 for the list's 1px top/bottom border
        list.setPrefHeight(h);
        list.setMinHeight(h);
        list.setMaxHeight(h);
        double w = width(actions);
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

    public void hide() {
        if (popup.isShowing()) {
            popup.hide();
        }
        list.getItems().clear();
    }

    /** Width that fits the widest title plus its kind column, clamped. */
    private static double width(List<CodeAction> actions) {
        int widest = 0;
        for (CodeAction a : actions) {
            int len = (a.title() == null ? 0 : a.title().length())
                    + (a.kind() == null || a.kind().isBlank() ? 0 : a.kind().length() + 3);
            widest = Math.max(widest, len);
        }
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, widest * CHAR_PX + H_PADDING + KIND_GAP));
    }

    /** Title on the left, the LSP kind muted on the right — the completion popup's detail-column idiom. */
    private static final class ActionCell extends ListCell<CodeAction> {
        @Override
        protected void updateItem(CodeAction item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label title = new Label(item.title());
            title.getStyleClass().add("completion-label");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(6, title, spacer);
            if (item.kind() != null && !item.kind().isBlank()) {
                Label kind = new Label(item.kind());
                kind.getStyleClass().add("completion-detail");
                row.getChildren().add(kind);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("completion-cell-row");
            setText(null);
            setGraphic(row);
        }
    }
}
