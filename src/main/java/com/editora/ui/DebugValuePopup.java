package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.event.EventHandler;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PopupControl;
import javafx.scene.control.Skin;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import com.editora.dap.DapModels;
import com.editora.editor.debug.DebugValuePresenter;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The hover / Show Value at Caret popup (follow-up 6): a plain value is one line, as before; an object, an array
 * or a collection unfolds as a lazy tree of its fields — fetched through the same DAP {@code variables} path, a page
 * at a time, as the Variables tree — with Copy Value and Add to Watches on its rows. Bounded (it scrolls), it closes
 * on Esc and when the mouse leaves it, and while it was opened from the keyboard the editor's arrow keys, Enter
 * (load more) and Ctrl+C work it — the popup itself never takes focus, so typing stays in the editor.
 */
final class DebugValuePopup implements DebugValuePresenter {

    /** The window's side: evaluating, loading children, adding a watch. */
    interface Source {
        /** Evaluates {@code expression} in the selected frame (context "hover"); failures deliver a failed result. */
        void evaluate(String expression, Consumer<DapModels.EvalResult> cb);

        /**
         * Children of {@code ref}: {@code filter} null for all of them, {@code "named"} or {@code "indexed"}, and
         * {@code count > 0} one page from {@code start} — the DAP {@code variables} the Variables tree sends.
         */
        void children(
                int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb);

        void addWatch(String expression);
    }

    static final double MAX_WIDTH = 560;
    static final double MAX_HEIGHT = 340;
    private static final double ROW = 22;

    private final Source source;
    private final PopupControl popup = new PopupControl();
    private final StackPane root = new StackPane();
    private final Label line = new Label();
    private final TreeView<DebugPanel.VarRow> tree = new TreeView<>();
    private final PauseTransition closing = new PauseTransition(Duration.millis(350));
    private final ContextMenu menu = new ContextMenu();

    /** The editor the popup was opened for, and the key filter it carries while the popup is keyboard-driven. */
    private @Nullable CodeArea owner;

    private final EventHandler<KeyEvent> keys = this::onKey;
    /** Answers to an older show than this are dropped (the mouse moved on). */
    private int shown;

    private @Nullable String expression;

    DebugValuePopup(Source source) {
        this.source = source;
        root.getStyleClass().addAll("debug-panel", "debug-value-popup");
        line.getStyleClass().add("debug-value-line");
        line.setWrapText(true);
        line.setMaxWidth(MAX_WIDTH);
        tree.getStyleClass().add("debug-vars");
        tree.setShowRoot(true);
        tree.setFocusTraversable(false);
        tree.setFixedCellSize(ROW);
        tree.setCellFactory(v -> new TreeCell<>() {
            @Override
            protected void updateItem(DebugPanel.VarRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                setGraphic(empty || row == null ? null : DebugPanel.renderRow(row));
            }
        });
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tree.getSelectionModel().getSelectedItem() instanceof More more) {
                more.load();
            }
        });
        MenuItem copy = new MenuItem(tr("debugpanel.copyValue"));
        copy.setOnAction(e -> copySelected());
        MenuItem watch = new MenuItem();
        watch.setOnAction(e -> addSelectedToWatches());
        menu.getItems().addAll(copy, watch);
        menu.setOnShowing(e -> {
            String expr = selectedExpression();
            watch.setText(tr("debugpanel.addToWatches", expr));
            watch.setDisable(expr.isEmpty());
        });
        tree.setContextMenu(menu);
        // As tall as its rows, up to the bound: a three-element list was a mostly empty box.
        tree.expandedItemCountProperty().addListener((o, was, now) -> fitHeight());
        root.getChildren().add(line);
        root.setMaxSize(MAX_WIDTH, MAX_HEIGHT);
        root.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> closing.stop());
        root.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (!menu.isShowing()) {
                mouseLeft();
            }
        });
        closing.setOnFinished(e -> {
            if (!root.isHover() && !menu.isShowing()) {
                hide();
            }
        });
        popup.setSkin(new Skin<>() {
            @Override
            public PopupControl getSkinnable() {
                return popup;
            }

            @Override
            public javafx.scene.Node getNode() {
                return root;
            }

            @Override
            public void dispose() {}
        });
        popup.setAutoHide(true);
        popup.setOnHidden(e -> release());
    }

    // --- DebugValuePresenter -------------------------------------------------------------------------

    @Override
    public void show(CodeArea area, String expr, double x, double y, boolean keyboard) {
        int asked = ++shown;
        closing.stop();
        source.evaluate(expr, r -> {
            if (asked != shown
                    || r == null
                    || r.failed()
                    || r.result() == null
                    || r.result().isBlank()) {
                return; // moved on, or nothing to show: an identifier that is not a variable is normal
            }
            present(area, expr, r, x, y, keyboard);
        });
    }

    @Override
    public void hide() {
        shown++;
        closing.stop();
        menu.hide();
        if (popup.isShowing()) {
            popup.hide();
        }
        release();
    }

    @Override
    public void mouseLeft() {
        if (popup.isShowing() && !keyboardDriven()) {
            closing.playFromStart();
        } else if (!popup.isShowing()) {
            shown++; // an evaluation still under way is for a word the mouse left
        }
    }

    @Override
    public boolean mouseInside() {
        return popup.isShowing() && root.isHover();
    }

    @Override
    public boolean isShowing() {
        return popup.isShowing();
    }

    // --- content -------------------------------------------------------------------------------------

    private void present(CodeArea area, String expr, DapModels.EvalResult r, double x, double y, boolean keyboard) {
        expression = expr;
        if (r.variablesReference() <= 0) {
            line.setText(expr + " = " + r.result());
            root.getChildren().setAll(line);
            root.setPrefSize(
                    javafx.scene.layout.Region.USE_COMPUTED_SIZE, javafx.scene.layout.Region.USE_COMPUTED_SIZE);
        } else {
            DebugPanel.VarRow top = new DebugPanel.VarRow(
                    expr,
                    r.result(),
                    r.type() == null ? "" : r.type(),
                    r.variablesReference(),
                    0,
                    DebugPanel.Kind.VARIABLE,
                    r.namedVariables(),
                    r.indexedVariables());
            TreeItem<DebugPanel.VarRow> item = lazy(top);
            tree.setRoot(item);
            item.setExpanded(true); // the object's fields at once: that is what hovering it is for
            tree.getSelectionModel().select(0);
            root.getChildren().setAll(tree);
            fitHeight();
        }
        setOwner(area, keyboard);
        if (popup.isShowing()) {
            popup.setAnchorX(x);
            popup.setAnchorY(y);
        } else {
            popup.show(area, x, y);
        }
    }

    /** A tree item whose children load on first expansion (a page at a time for a large array). */
    private TreeItem<DebugPanel.VarRow> lazy(DebugPanel.VarRow row) {
        TreeItem<DebugPanel.VarRow> item = new TreeItem<>(row) {
            @Override
            public boolean isLeaf() {
                return row.ref() <= 0;
            }
        };
        if (row.ref() > 0) {
            boolean[] loaded = {false};
            item.expandedProperty().addListener((o, was, now) -> {
                if (now && !loaded[0]) {
                    loaded[0] = true;
                    load(item, row);
                }
            });
        }
        return item;
    }

    private void load(TreeItem<DebugPanel.VarRow> item, DebugPanel.VarRow row) {
        if (!DebugValues.fetchedByPage(row.indexed())) {
            source.children(row.ref(), null, 0, 0, all -> page(item, row, all, 0));
            return;
        }
        if (row.named() > 0) {
            source.children(row.ref(), "named", 0, 0, named -> {
                item.getChildren().addAll(rows(row, named));
                requestPage(item, row, 0);
            });
        } else {
            requestPage(item, row, 0);
        }
    }

    private void requestPage(TreeItem<DebugPanel.VarRow> item, DebugPanel.VarRow row, int start) {
        int count = DebugValues.nextPage(start, row.indexed());
        source.children(row.ref(), "indexed", start, count, got -> {
            dropMore(item);
            if (got.size() > count) {
                page(item, row, got, 0); // the adapter ignored start/count
                return;
            }
            item.getChildren().addAll(rows(row, got));
            int done = start + got.size();
            int next = DebugValues.nextPage(done, row.indexed());
            if (next > 0 && !got.isEmpty()) {
                item.getChildren().add(new More(next, row.indexed() - done, () -> requestPage(item, row, done)));
            }
        });
    }

    private void page(
            TreeItem<DebugPanel.VarRow> item, DebugPanel.VarRow row, List<DapModels.VariableInfo> all, int start) {
        dropMore(item);
        int end = start + DebugValues.nextPage(start, all.size());
        item.getChildren().addAll(rows(row, all.subList(start, end)));
        int next = DebugValues.nextPage(end, all.size());
        if (next > 0) {
            item.getChildren().add(new More(next, all.size() - end, () -> page(item, row, all, end)));
        }
    }

    private List<TreeItem<DebugPanel.VarRow>> rows(DebugPanel.VarRow parent, List<DapModels.VariableInfo> vars) {
        List<TreeItem<DebugPanel.VarRow>> out = new ArrayList<>(vars.size());
        for (DapModels.VariableInfo v : vars) {
            out.add(lazy(new DebugPanel.VarRow(
                    v.name(),
                    v.value(),
                    v.type() == null ? "" : v.type(),
                    v.variablesReference(),
                    parent.ref(),
                    DebugPanel.Kind.VARIABLE,
                    v.namedVariables(),
                    v.indexedVariables())));
        }
        return out;
    }

    private static void dropMore(TreeItem<DebugPanel.VarRow> item) {
        var children = item.getChildren();
        if (!children.isEmpty() && children.getLast() instanceof More) {
            children.removeLast();
        }
    }

    /** "Show N more…": Enter or a double-click loads the next page. */
    private static final class More extends TreeItem<DebugPanel.VarRow> {
        private final Runnable next;
        private boolean requested;

        More(int next, int remaining, Runnable load) {
            super(new DebugPanel.VarRow(
                    tr("debugpanel.showMore", next, remaining), "", "", 0, 0, DebugPanel.Kind.MORE));
            this.next = load;
        }

        void load() {
            if (!requested) {
                requested = true;
                next.run();
            }
        }
    }

    // --- actions -------------------------------------------------------------------------------------

    /** The selected row's expression: the hovered expression, then {@code .field} / {@code [i]} down to it. */
    String selectedExpression() {
        TreeItem<DebugPanel.VarRow> sel = tree.getSelectionModel().getSelectedItem();
        if (sel == null || sel instanceof More || expression == null) {
            return "";
        }
        if (sel == tree.getRoot()) {
            return expression;
        }
        String below = DebugPanel.pathExpression(sel); // up to (and including) the root's own name
        return below.isEmpty() ? expression : below;
    }

    private void copySelected() {
        TreeItem<DebugPanel.VarRow> sel = tree.getSelectionModel().getSelectedItem();
        if (sel != null && !(sel instanceof More)) {
            DebugPanel.copyToClipboard(DebugPanelText.copyValue(sel.getValue().value()));
        } else if (root.getChildren().contains(line)) {
            DebugPanel.copyToClipboard(line.getText());
        }
    }

    private void addSelectedToWatches() {
        String expr = selectedExpression();
        if (!expr.isEmpty()) {
            hide();
            source.addWatch(expr);
        }
    }

    // --- keyboard (only while opened from the keyboard; focus stays in the editor) -------------------

    /** Opened from the keyboard: the arrows, Enter and Ctrl+C work the popup (from the hover only Esc does). */
    private boolean keyboardMode;

    private boolean keyboardDriven() {
        return owner != null && keyboardMode;
    }

    private void setOwner(CodeArea area, boolean keyboard) {
        release();
        owner = area;
        keyboardMode = keyboard;
        area.addEventFilter(KeyEvent.KEY_PRESSED, keys);
    }

    private void release() {
        if (owner != null) {
            owner.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
            owner = null;
        }
        keyboardMode = false;
    }

    private void onKey(KeyEvent e) {
        if (!popup.isShowing()) {
            release();
            return;
        }
        if (!keyboardMode && e.getCode() != KeyCode.ESCAPE) {
            if (!e.getCode().isModifierKey()) {
                hide(); // opened by the hover: a key typed in the editor closes it and does what it does
            }
            return;
        }
        boolean isTree = root.getChildren().contains(tree);
        TreeItem<DebugPanel.VarRow> sel = tree.getSelectionModel().getSelectedItem();
        switch (e.getCode()) {
            case ESCAPE -> hide();
            case UP -> {
                if (isTree) {
                    tree.getSelectionModel().selectPrevious();
                    tree.scrollTo(Math.max(0, tree.getSelectionModel().getSelectedIndex() - 2));
                }
            }
            case DOWN -> {
                if (isTree) {
                    tree.getSelectionModel().selectNext();
                    tree.scrollTo(Math.max(0, tree.getSelectionModel().getSelectedIndex() - 2));
                }
            }
            case RIGHT -> {
                if (isTree && sel != null && !sel.isLeaf()) {
                    sel.setExpanded(true);
                }
            }
            case LEFT -> {
                if (isTree && sel != null) {
                    if (sel.isExpanded()) {
                        sel.setExpanded(false);
                    } else if (sel.getParent() != null) {
                        tree.getSelectionModel().select(sel.getParent());
                    }
                }
            }
            case ENTER -> {
                if (e.isShiftDown()) {
                    addSelectedToWatches(); // Shift+Enter: the row's expression to Watches
                } else if (sel instanceof More more) {
                    more.load();
                } else if (sel != null && !sel.isLeaf()) {
                    sel.setExpanded(!sel.isExpanded());
                }
            }
            case C -> {
                if (!e.isShortcutDown()) {
                    hide(); // typing goes on in the editor
                    return;
                }
                copySelected();
            }
            default -> {
                if (e.getCode().isModifierKey()) {
                    return;
                }
                hide(); // any other key: the popup goes, the key does what it does in the editor
                return;
            }
        }
        e.consume();
    }

    private void fitHeight() {
        double h = Math.min(MAX_HEIGHT, tree.getExpandedItemCount() * ROW + 4);
        tree.setPrefHeight(h);
        root.setPrefSize(MAX_WIDTH, h + 8);
    }

    /** For tests. */
    TreeView<DebugPanel.VarRow> tree() {
        return tree;
    }

    Label line() {
        return line;
    }

    PopupControl popup() {
        return popup;
    }
}
