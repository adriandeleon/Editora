package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Evaluate window (IntelliJ's Evaluate Expression dialog, Alt+F8): a window of its own beside the editor, not
 * modal, so the code can be read and the program stepped while it is open, and several can be open at once. It
 * holds:
 *
 * <ul>
 *   <li>the <b>Expression</b> — a {@link DebugExpressionField}: highlighted in the debugged program's language, the
 *       configured keymap's keys, completion, history (Up / Down, {@code M-p} / {@code M-n}, and the History list),
 *       and Shift+Enter (or Expand) for a code fragment of several lines, evaluated with Ctrl+Enter;
 *   <li>the <b>Result</b> — a tree: an object's fields open under it, a page at a time for a large array, with Copy
 *       Value, Copy Name and Add to Watches on its rows. A failure shows the adapter's reason in its place;
 *   <li>the frame it evaluates in — the one selected in the Debug window when Evaluate is pressed.
 * </ul>
 *
 * Opened with the editor's selection, or the expression at the caret, as its expression.
 */
final class DebugEvaluateWindow {

    /** The window's side of the debugger. */
    interface Source extends DebugValueTree.Children {
        /** Whether the program is paused (there is a frame to evaluate in). */
        boolean paused();

        /** The frame evaluations run in, as the Debug window names it ({@code main:12, Main.java}); "" when none. */
        String frameName();

        /** Evaluates {@code expression} as code in the selected frame; failures deliver a failed result. */
        void evaluate(String expression, Consumer<DapModels.EvalResult> cb);

        /** The adapter's completions of {@code text} at {@code caret}, in the selected frame. */
        void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb);

        void addWatch(String expression);

        /** Something was evaluated (the history changed; values may have changed). */
        void evaluated(boolean succeeded);
    }

    static final double WIDTH = 640;
    static final double HEIGHT = 460;

    private final Source source;
    private final Stage stage = new Stage();
    private final DebugExpressionField field;
    private final TreeView<DebugPanel.VarRow> tree = new TreeView<>();
    private final Label context = new Label();
    private final Label resultHeader = new Label(tr("debugeval.result"));
    private final DebugValueTree values;
    private final ToggleButton expand = new ToggleButton();
    private final MenuButton historyButton = new MenuButton();
    private final Button evaluate = new Button(tr("debugeval.evaluate"));
    private final Button toWatches = new Button(tr("debugeval.addToWatches"));
    /** The expression of the result shown (Add to Watches with the field emptied, a row's path). */
    private @Nullable String shownExpression;
    /** Answers to an older Evaluate than this are dropped. */
    private int asked;

    DebugEvaluateWindow(Source source, DebugPanelText.History history) {
        this.source = source;
        this.values = new DebugValueTree(source);
        this.field = new DebugExpressionField(
                new DebugExpressionField.Owner() {
                    @Override
                    public boolean paused() {
                        return source.paused();
                    }

                    @Override
                    public void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb) {
                        source.complete(text, caret, cb);
                    }

                    @Override
                    public void submit(String expression) {
                        evaluate(expression);
                    }
                },
                new DebugPanelText.History(history));
        build();
    }

    private void build() {
        stage.setTitle(tr("debugeval.title"));
        stage.initModality(Modality.NONE);

        Label expressionHeader = new Label(tr("debugeval.expression"));
        expressionHeader.getStyleClass().add("debug-section");
        expressionHeader.setLabelFor(field.area());
        field.setPromptText(tr("debugeval.prompt"));
        field.area().setAccessibleText(tr("debugeval.expression"));
        field.setOnTab(back -> (back ? evaluate : tree).requestFocus());

        expand.setGraphic(Icons.findNext());
        expand.getStyleClass().addAll("flat", "debug-toolbar-button", "debug-eval-expand");
        expand.setTooltip(new Tooltip(tr("debugpanel.evaluateExpand")));
        expand.setAccessibleText(tr("debugpanel.evaluateExpand"));
        expand.setFocusTraversable(false);
        expand.setOnAction(e -> {
            field.toggleMultiLine();
            expand.setSelected(field.isMultiLine());
        });
        field.multiLineProperty().addListener((o, was, now) -> expand.setSelected(now));

        historyButton.setGraphic(Icons.history());
        historyButton.getStyleClass().addAll("flat", "debug-toolbar-button", "debug-eval-history");
        historyButton.setTooltip(new Tooltip(tr("debugeval.history")));
        historyButton.setAccessibleText(tr("debugeval.history"));
        historyButton.setFocusTraversable(false);
        historyButton.setOnShowing(e -> fillHistory());
        historyButton.getItems().add(new MenuItem()); // a MenuButton with no items does not open

        HBox expressionRow = new HBox(2, field.node(), expand, historyButton);
        expressionRow.setAlignment(Pos.TOP_LEFT);
        HBox.setHgrow(field.node(), Priority.ALWAYS);
        Label keys = new Label(tr("debugeval.keys"));
        keys.getStyleClass().add("debug-eval-keys");

        resultHeader.getStyleClass().add("debug-section");
        tree.getStyleClass().addAll("debug-vars", "debug-eval-result");
        tree.setShowRoot(true);
        // No result yet: a muted hint where it will be (a TreeView has no placeholder).
        tree.setRoot(new TreeItem<>(
                new DebugPanel.VarRow(tr("debugeval.noResult"), "", "", 0, 0, DebugPanel.Kind.ADD_WATCH)));
        tree.setCellFactory(v -> new TreeCell<>() {
            @Override
            protected void updateItem(DebugPanel.VarRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                setGraphic(empty || row == null ? null : DebugPanel.renderRow(row));
            }
        });
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && tree.getSelectionModel().getSelectedItem() instanceof DebugValueTree.More m) {
                m.load();
            }
        });
        tree.setOnKeyPressed(e -> {
            TreeItem<DebugPanel.VarRow> sel = tree.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && sel instanceof DebugValueTree.More m) {
                m.load();
                e.consume();
            } else if (e.getCode() == KeyCode.C && e.isShortcutDown() && sel != null) {
                DebugPanel.copyToClipboard(
                        DebugPanelText.copyValue(sel.getValue().value()));
                e.consume();
            }
        });
        ContextMenu rowMenu = rowMenu();
        tree.setContextMenu(rowMenu);
        RowContextMenu.anchor(tree, rowMenu); // the Menu key / Shift+F10: at the selected row
        VBox.setVgrow(tree, Priority.ALWAYS);

        context.getStyleClass().add("debug-eval-context");
        context.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(context, Priority.ALWAYS);
        evaluate.setDefaultButton(false); // Enter belongs to the field (a new line in a code fragment)
        evaluate.getStyleClass().add("accent");
        evaluate.setOnAction(e -> {
            String text = field.getText();
            if (!text.isBlank()) {
                evaluate(text.strip());
            }
        });
        toWatches.setOnAction(e -> {
            String text = field.getText().isBlank()
                    ? shownExpression
                    : field.getText().strip();
            if (text != null && !text.isBlank()) {
                source.addWatch(text);
            }
        });
        Button close = new Button(tr("debugeval.close"));
        close.setCancelButton(true);
        close.setOnAction(e -> stage.hide());
        HBox buttons = new HBox(8, context, toWatches, evaluate, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(6, expressionHeader, expressionRow, keys, resultHeader, tree, buttons);
        root.setPadding(new Insets(10));
        root.getStyleClass().addAll("debug-panel", "debug-evaluate-window");
        root.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        // Esc closes the window, unless the completion list is open (its own filter, on the scene, closes that).
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !e.isConsumed()) {
                stage.hide();
                e.consume();
            }
        });
        stage.setScene(scene);
        stage.setMinWidth(420);
        stage.setMinHeight(300);
        refreshContext();
    }

    private ContextMenu rowMenu() {
        MenuItem copyValue = new MenuItem(tr("debugpanel.copyValue"));
        copyValue.setOnAction(e -> {
            TreeItem<DebugPanel.VarRow> sel = tree.getSelectionModel().getSelectedItem();
            if (sel != null && !(sel instanceof DebugValueTree.More)) {
                DebugPanel.copyToClipboard(
                        DebugPanelText.copyValue(sel.getValue().value()));
            }
        });
        MenuItem copyName = new MenuItem(tr("debugpanel.copyName"));
        copyName.setOnAction(e -> {
            TreeItem<DebugPanel.VarRow> sel = tree.getSelectionModel().getSelectedItem();
            if (sel != null && !(sel instanceof DebugValueTree.More)) {
                DebugPanel.copyToClipboard(sel.getValue().name());
            }
        });
        MenuItem watch = new MenuItem();
        watch.setGraphic(Icons.newFile());
        watch.setOnAction(e -> {
            String expr = selectedExpression();
            if (!expr.isEmpty()) {
                source.addWatch(expr);
            }
        });
        ContextMenu menu = new ContextMenu(copyValue, copyName, watch);
        menu.setOnShowing(e -> {
            String expr = selectedExpression();
            watch.setText(tr("debugpanel.addToWatches", DebugPanelText.oneLine(expr, 60)));
            boolean row = tree.getSelectionModel().getSelectedItem() != null
                    && !(tree.getSelectionModel().getSelectedItem() instanceof DebugValueTree.More);
            watch.setDisable(expr.isEmpty());
            copyValue.setDisable(!row);
            copyName.setDisable(!row);
        });
        return menu;
    }

    /** The selected row's expression: the evaluated expression, then {@code .field} / {@code [i]} down to it. */
    String selectedExpression() {
        TreeItem<DebugPanel.VarRow> sel = tree.getSelectionModel().getSelectedItem();
        if (sel == null || sel instanceof DebugValueTree.More || shownExpression == null) {
            return "";
        }
        if (sel == tree.getRoot()) {
            return shownExpression;
        }
        return DebugPanel.expression(sel);
    }

    private void fillHistory() {
        List<String> entries = field.historyEntries();
        historyButton.getItems().clear();
        if (entries.isEmpty()) {
            MenuItem none = new MenuItem(tr("debugeval.historyEmpty"));
            none.setDisable(true);
            historyButton.getItems().add(none);
            return;
        }
        for (int i = entries.size() - 1; i >= 0 && historyButton.getItems().size() < 30; i--) {
            String expression = entries.get(i);
            MenuItem item = new MenuItem(DebugPanelText.oneLine(expression, 80));
            item.setMnemonicParsing(false);
            item.setOnAction(e -> {
                field.setText(expression);
                field.requestFocus();
            });
            historyButton.getItems().add(item);
        }
    }

    /** Shows the window over {@code owner}, its expression {@code initial} (selected, to be typed over) when given. */
    void show(Window owner, @Nullable String initial, String languageFile, double offset) {
        if (stage.getOwner() == null && owner != null) {
            stage.initOwner(owner);
            mirrorStylesheets(owner);
        }
        field.setLanguageFile(languageFile);
        if (initial != null && !initial.isBlank()) {
            field.setText(initial);
        }
        refreshContext();
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            WindowPlacement.centerOnOwner(stage, owner, WIDTH, HEIGHT);
            stage.setX(stage.getX() + offset);
            stage.setY(stage.getY() + offset);
            stage.show();
        }
        stage.requestFocus();
        field.requestFocus();
        field.selectAll();
    }

    /** A fresh Scene has no stylesheets: the owner's are mirrored, so a theme switch reaches the window too. */
    private void mirrorStylesheets(Window owner) {
        if (owner.getScene() == null) {
            return;
        }
        javafx.collections.ObservableList<String> sheets = owner.getScene().getStylesheets();
        stage.getScene().getStylesheets().setAll(sheets);
        // For the window's life: a closed window is reused by the next Evaluate (see DebugCoordinator), never dropped.
        sheets.addListener((javafx.collections.ListChangeListener<String>)
                change -> stage.getScene().getStylesheets().setAll(sheets));
    }

    /** Evaluates {@code expression} in the selected frame and shows its result. */
    void evaluate(String expression) {
        int request = ++asked;
        shownExpression = expression;
        field.completion().hide();
        if (!source.paused()) {
            refreshContext();
            showResult(expression, DapModels.EvalResult.failure(tr("debugpanel.evaluateNeedsPause")));
            source.evaluated(false);
            return;
        }
        refreshContext();
        tree.setRoot(new TreeItem<>(new DebugPanel.VarRow(expression, "…", "", 0, 0, DebugPanel.Kind.RESULT)));
        source.evaluate(expression, r -> {
            if (request != asked) {
                return;
            }
            DapModels.EvalResult shown = r == null ? DapModels.EvalResult.failure(tr("debugpanel.noValue")) : r;
            showResult(expression, shown);
            source.evaluated(!shown.failed());
        });
    }

    private void showResult(String expression, DapModels.EvalResult r) {
        DebugPanel.VarRow row = new DebugPanel.VarRow(
                expression,
                r.result() == null ? "" : r.result(),
                r.type() == null ? "" : r.type(),
                r.failed() ? 0 : r.variablesReference(),
                0,
                DebugPanel.Kind.RESULT,
                r.namedVariables(),
                r.indexedVariables(),
                r.failed());
        TreeItem<DebugPanel.VarRow> item = values.lazy(row);
        tree.setRoot(item);
        tree.getSelectionModel().select(item);
        if (!item.isLeaf()) {
            item.setExpanded(true);
        }
    }

    /** The program moved on (or paused): the result's references are gone, and the frame shown changed. */
    void sessionChanged() {
        asked++;
        TreeItem<DebugPanel.VarRow> root = tree.getRoot();
        if (root != null && root.getValue() != null && !root.getValue().failed()) {
            DebugPanel.VarRow row = root.getValue();
            // Kept for reading, but no longer expandable: its fields belonged to the stop that ended.
            tree.setRoot(new TreeItem<>(
                    new DebugPanel.VarRow(row.name(), row.value(), row.type(), 0, 0, DebugPanel.Kind.RESULT)));
        }
        refreshContext();
    }

    private void refreshContext() {
        boolean paused = source.paused();
        String frame = paused ? source.frameName() : "";
        context.setText(
                !paused
                        ? tr("debugeval.notPaused")
                        : frame.isEmpty() ? tr("debugeval.inSelectedFrame") : tr("debugeval.inFrame", frame));
        evaluate.setDisable(!source.paused());
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    void close() {
        stage.hide();
    }

    // --- for tests -------------------------------------------------------------------------------------

    Stage stage() {
        return stage;
    }

    DebugExpressionField field() {
        return field;
    }

    TreeView<DebugPanel.VarRow> resultTree() {
        return tree;
    }

    String contextText() {
        return context.getText();
    }

    /** The value colours legible on the window's theme (the Debug window's setting, review C111). */
    void setPlainValueColours(boolean plain) {
        DebugPanel.setPlainValueColours(stage.getScene().getRoot(), plain);
    }
}
