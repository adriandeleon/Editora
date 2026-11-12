package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.event.EventHandler;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PopupControl;
import javafx.scene.control.Skin;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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

        /** Evaluates {@code expression} as code (context "repl"): Quick Evaluate. The hover's evaluation by default. */
        default void evaluateCode(String expression, Consumer<DapModels.EvalResult> cb) {
            evaluate(expression, cb);
        }

        /** Pins {@code expression} to line {@code line} (0-based) of {@code area}'s file, or unpins it there. */
        default void pin(CodeArea area, int line, String expression) {}
    }

    /** The Mark Object label of the object a value shows, or null (see {@link ObjectMarks}). */
    private java.util.function.Function<String, @Nullable String> marks = value -> null;

    /** Where the popup's rows find the labels of marked objects. */
    void setMarks(java.util.function.Function<String, @Nullable String> marks) {
        this.marks = marks == null ? value -> null : marks;
    }

    /** The line the popup was opened for (where Pin to Line pins), or -1. */
    private int anchorLine = -1;

    /** The value line shows an evaluation's failure (Quick Evaluate): drawn as an error, not as a value. */
    private static final javafx.css.PseudoClass FAILED = javafx.css.PseudoClass.getPseudoClass("failed");

    static final double MAX_WIDTH = 560;
    static final double MAX_HEIGHT = 340;
    private static final double ROW = 22;

    private final Source source;
    /** Loads the rows of an object's fields, a page at a time. */
    private final DebugValueTree values;

    private final PopupControl popup = new PopupControl();
    private final StackPane root = new StackPane();
    private final Label line = new Label();
    private final TreeView<DebugPanel.VarRow> tree = new TreeView<>();
    private final PauseTransition closing = new PauseTransition(Duration.millis(350));
    private final ContextMenu menu = new ContextMenu();
    /** A String's whole text (review 2, C105): its header line, the text itself, and Copy. */
    private final Label textHeader = new Label();

    private final TextArea text = new TextArea();
    private final javafx.scene.control.Button copyText = new javafx.scene.control.Button();
    private final VBox textBox = new VBox(4, textHeader, text, copyText);

    /** The editor the popup was opened for, and the key filter it carries while the popup is keyboard-driven. */
    private @Nullable CodeArea owner;

    private final EventHandler<KeyEvent> keys = this::onKey;
    /** Answers to an older show than this are dropped (the mouse moved on). */
    private int shown;

    private @Nullable String expression;

    DebugValuePopup(Source source) {
        this.source = source;
        this.values = new DebugValueTree(source::children);
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
                setGraphic(empty || row == null ? null : DebugPanel.renderRow(row, false, marks.apply(row.value())));
            }
        });
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2
                    && tree.getSelectionModel().getSelectedItem() instanceof DebugValueTree.More more) {
                more.load();
            }
        });
        MenuItem copy = new MenuItem(tr("debugpanel.copyValue"));
        copy.setOnAction(e -> copySelected());
        MenuItem watch = new MenuItem();
        watch.setOnAction(e -> addSelectedToWatches());
        MenuItem pin = new MenuItem();
        pin.setOnAction(e -> pinSelected());
        menu.getItems().addAll(copy, watch, pin);
        menu.setOnShowing(e -> {
            String expr = selectedExpression();
            watch.setText(tr("debugpanel.addToWatches", expr));
            watch.setDisable(expr.isEmpty());
            pin.setText(tr("debugpanel.pinToLine", expr, anchorLine + 1));
            pin.setDisable(expr.isEmpty() || anchorLine < 0 || owner == null);
        });
        tree.setContextMenu(menu);
        // A plain value is one line: Copy Value and Pin to Line on it too.
        MenuItem copyLine = new MenuItem(tr("debugpanel.copyValue"));
        copyLine.setOnAction(e -> DebugPanel.copyToClipboard(line.getText()));
        MenuItem pinLine = new MenuItem();
        pinLine.setOnAction(e -> pin(expression));
        ContextMenu lineMenu = new ContextMenu(copyLine, pinLine);
        lineMenu.setOnShowing(e -> {
            String expr = expression == null ? "" : expression;
            pinLine.setText(tr("debugpanel.pinToLine", expr, anchorLine + 1));
            pinLine.setDisable(expr.isEmpty() || anchorLine < 0 || owner == null);
        });
        line.setContextMenu(lineMenu);
        textHeader.getStyleClass().add("debug-value-line");
        text.getStyleClass().add("debug-value-text");
        text.setEditable(false);
        text.setWrapText(true);
        text.setFocusTraversable(false);
        copyText.setText(tr("debugpanel.copyValue"));
        copyText.setFocusTraversable(false);
        copyText.setOnAction(e -> DebugPanel.copyToClipboard(text.getText()));
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
        anchorLine = keyboard ? area.getCurrentParagraph() : lineAbove(area, x, y);
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

    /**
     * Quick Evaluate (Alt+click, IntelliJ's): {@code expr} evaluated as code — a method call included, which the hover
     * never runs — and shown even when it fails, with the adapter's reason: it was asked for, so silence would read as
     * the click being missed. Keyboard-driven, as Show Value at Caret.
     */
    @Override
    public void showEvaluated(CodeArea area, String expr, double x, double y) {
        int asked = ++shown;
        anchorLine = lineAbove(area, x, y);
        closing.stop();
        source.evaluateCode(expr, r -> {
            if (asked != shown || r == null) {
                return;
            }
            if (r.failed() || r.result() == null || r.result().isBlank()) {
                String reason = r.failed() && r.result() != null && !r.result().isBlank()
                        ? r.result()
                        : tr("debugpanel.noValue");
                present(area, expr, DapModels.EvalResult.failure(reason), x, y, true);
                return;
            }
            present(area, expr, r, x, y, true);
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

    /**
     * Pure: whether {@code r} is a Java {@code String}, shown as its whole text rather than a tree of the String
     * class's internals ({@code CASE_INSENSITIVE_ORDER}, {@code coder}, {@code value = byte[318]}…) — review 2, C105.
     */
    static boolean isText(DapModels.@Nullable EvalResult r) {
        if (r == null || r.failed() || r.result() == null || !r.result().startsWith("\"")) {
            return false;
        }
        String type = r.type() == null ? "" : r.type();
        return type.equals("String") || type.equals("java.lang.String");
    }

    /** How tall the text box is for {@code value}: its lines (wrapped ones counted roughly), within the bound. */
    static double textHeight(String value) {
        int lines = 0;
        for (String l : value.split("\\R", -1)) {
            lines += 1 + l.length() / 70;
        }
        return Math.min(MAX_HEIGHT - 70, Math.max(2, lines) * 18 + 14);
    }

    private void present(CodeArea area, String expr, DapModels.EvalResult r, double x, double y, boolean keyboard) {
        expression = expr;
        if (isText(r)) {
            String value = DebugPanelText.copyValue(r.result());
            // Characters as the user counts them: an emoji is one, not two UTF-16 units (review 3, B note).
            textHeader.setText(tr("debugpanel.stringChars", expr, value.codePointCount(0, value.length())));
            text.setText(value);
            text.setPrefHeight(textHeight(value));
            text.setPrefWidth(MAX_WIDTH - 16);
            root.getChildren().setAll(textBox);
            root.setPrefSize(
                    javafx.scene.layout.Region.USE_COMPUTED_SIZE, javafx.scene.layout.Region.USE_COMPUTED_SIZE);
        } else if (r.variablesReference() <= 0) {
            line.setText(r.failed() ? tr("debugpanel.evalFailedLine", expr, r.result()) : expr + " = " + r.result());
            line.pseudoClassStateChanged(FAILED, r.failed());
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
            TreeItem<DebugPanel.VarRow> item = values.lazy(top);
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
            // Owned by the window, not the editor: a popup owned by a node takes that node as its CSS parent, so the
            // editor's own rules (`.editor-area .text { -fx-fill: … }`, the editor theme's text colour) reached the
            // popup's labels and button. A light editor theme in a dark window made the String header and Copy
            // Value dark on the dark popup (review 2, C105 follow-up). The window's scene root gives the popup the
            // window theme's colours, as every other popup has.
            javafx.stage.Window window =
                    area.getScene() == null ? null : area.getScene().getWindow();
            if (window != null) {
                popup.show(window, x, y);
            } else {
                popup.show(area, x, y);
            }
        }
    }

    // --- actions -------------------------------------------------------------------------------------

    /** The selected row's expression: the hovered expression, then {@code .field} / {@code [i]} down to it. */
    String selectedExpression() {
        TreeItem<DebugPanel.VarRow> sel = tree.getSelectionModel().getSelectedItem();
        if (sel == null || sel instanceof DebugValueTree.More || expression == null) {
            return "";
        }
        if (sel == tree.getRoot()) {
            return expression;
        }
        String below = DebugPanel.expression(sel); // the adapter's own, or the path up to (and including) the root
        return below.isEmpty() ? expression : below;
    }

    private void copySelected() {
        TreeItem<DebugPanel.VarRow> sel = tree.getSelectionModel().getSelectedItem();
        if (root.getChildren().contains(textBox)) {
            DebugPanel.copyToClipboard(text.getText());
        } else if (sel != null && !(sel instanceof DebugValueTree.More)) {
            DebugPanel.copyToClipboard(DebugPanelText.copyValue(sel.getValue().value()));
        } else if (root.getChildren().contains(line)) {
            DebugPanel.copyToClipboard(line.getText());
        }
    }

    /** Pins the selected row's expression to the line the popup was opened for (Visual Studio's pin on a DataTip). */
    private void pinSelected() {
        pin(selectedExpression());
    }

    private void pin(@Nullable String expr) {
        CodeArea area = owner;
        int line = anchorLine;
        if (expr != null && !expr.isEmpty() && area != null && line >= 0) {
            hide();
            source.pin(area, line, expr);
        }
    }

    /**
     * The line the popup at {@code (x, y)} (screen) was opened for: the hover places it just below the word's line,
     * Show Value at Caret below the caret's. -1 when it cannot be told.
     */
    static int lineAbove(CodeArea area, double x, double y) {
        try {
            javafx.geometry.Point2D local = area.screenToLocal(x, y - 6);
            if (local == null) {
                return -1;
            }
            int offset = area.hit(local.getX(), local.getY()).getInsertionIndex();
            return area.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                    .getMajor();
        } catch (RuntimeException e) {
            return -1;
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
                } else if (sel instanceof DebugValueTree.More more) {
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

    TextArea text() {
        return text;
    }

    PopupControl popup() {
        return popup;
    }
}
