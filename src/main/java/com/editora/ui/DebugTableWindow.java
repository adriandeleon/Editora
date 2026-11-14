package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
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
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * View as Table: a collection of the debugged program — a list, an array, a slice, a set, a map — as a table, one row
 * per element and one column per field, in a window of its own beside the editor. Columns sort (numbers by value) and
 * a filter keeps the rows with the text in a cell. Copy puts the selected rows (or all) on the clipboard as
 * tab-separated text, Open as CSV in a CSV tab, and Refresh evaluates the expression again at the current stop.
 *
 * <p>The table is the stop's: its values stay after the program goes on, and Refresh is the way to see new ones. What
 * becomes rows and columns is {@link ValueTable}'s.
 */
final class DebugTableWindow {

    /** The window's side of the debugger. */
    interface Actions {
        /**
         * The children of {@code ref}: all of them ({@code filter} null), or one page of the indexed or named ones
         * (DAP variable paging).
         */
        void children(
                int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb);

        /** Opens {@code text} in a read-only editor tab named {@code title}, in {@code language}. */
        void openText(String title, String text, String language);

        /** Evaluates {@code expression} again in the frame shown; {@code cb} gets null when it cannot. */
        void evaluate(String expression, Consumer<DapModels.@Nullable EvalResult> cb);

        /** Whether the program is paused, so the expression can be evaluated again. */
        boolean canRefresh();
    }

    static final double WIDTH = 820;
    static final double HEIGHT = 480;

    private final Actions actions;
    private final Stage stage = new Stage();
    private final Label heading = new Label();
    private final Label note = new Label();
    private final TextField filter = new TextField();
    private final TableView<ValueTable.Row> table = new TableView<>();
    private final ObservableList<ValueTable.Row> rows = FXCollections.observableArrayList();
    private final FilteredList<ValueTable.Row> filtered = new FilteredList<>(rows);
    private final Button refresh = new Button(tr("valuetable.refresh"));
    private ValueTable model = ValueTable.of(List.of());
    private String name = "";
    private String expression = "";

    /** Bumped by each load: a page or a row's fields that arrive for an older one are dropped. */
    private long generation;

    /** Rows whose fields are still being asked for, of the current load. */
    private int pending;

    /** How many elements the collection has, as its debugger said; -1 when it did not. */
    private int total = -1;

    DebugTableWindow(Actions actions) {
        this.actions = actions;
        build();
    }

    private void build() {
        stage.initModality(Modality.NONE);
        heading.getStyleClass().add("debug-section");
        note.getStyleClass().add("thread-dump-note");
        note.setWrapText(true);

        filter.setPromptText(tr("valuetable.filter"));
        filter.setAccessibleText(tr("valuetable.filter"));
        filter.textProperty().addListener((o, was, now) -> filtered.setPredicate(r -> model.matches(r, now)));
        filter.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                table.requestFocus();
                if (table.getSelectionModel().isEmpty() && !table.getItems().isEmpty()) {
                    table.getSelectionModel().selectFirst();
                }
                e.consume();
            }
        });

        SortedList<ValueTable.Row> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getStyleClass().add("debug-value-table");
        table.setAccessibleText(tr("valuetable.table"));
        table.setPlaceholder(new Label(tr("valuetable.empty")));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.C && e.isShortcutDown()) {
                copy();
                e.consume();
            }
        });
        VBox.setVgrow(table, Priority.ALWAYS);

        Button copy = new Button(tr("valuetable.copy"));
        copy.setGraphic(Icons.copy());
        copy.setOnAction(e -> copy());
        // The table's right-click menu: Copy, as the button and Shortcut+C do (MENU-05).
        RowMenus.install(
                table,
                () -> !table.getItems().isEmpty(),
                ListRowCommands.buttonRow(table, ListRowCommands.COPY, copy, Icons.copy()));
        Button asCsv = new Button(tr("valuetable.openAsCsv"));
        asCsv.setOnAction(e -> actions.openText(tr("valuetable.csvTitle", name), model.csv(shownRows()), "csv"));
        refresh.setOnAction(e -> refresh());
        Button close = new Button(tr("threaddump.close"));
        close.setCancelButton(true);
        close.setOnAction(e -> stage.hide());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = UiZoom.spacing(new HBox(copy, asCsv, refresh, spacer, close), 8);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox root = UiZoom.spacing(new VBox(heading, note, filter, table, buttons), 6);
        UiZoom.padding(root, 10);
        root.getStyleClass().addAll("debug-panel", "value-table-window");
        Scene scene = new Scene(root, UiZoom.px(WIDTH), UiZoom.px(HEIGHT));
        scene.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE && !e.isConsumed()) {
                if (!filter.getText().isEmpty() && filter.isFocused()) {
                    filter.clear();
                } else {
                    stage.hide();
                }
                e.consume();
            }
        });
        stage.setScene(scene);
        UiZoom.follow(stage, () -> {
            stage.setMinWidth(UiZoom.px(420));
            stage.setMinHeight(UiZoom.px(260));
        });
    }

    /**
     * Shows the collection {@code ref} — named {@code name}, given by {@code expression} in the stopped frame, with
     * {@code named} and {@code indexed} children as its debugger counted them (0 when it did not) — over {@code owner}.
     */
    void show(Window owner, String name, String expression, int ref, int named, int indexed) {
        if (stage.getOwner() == null && owner != null) {
            stage.initOwner(owner);
            mirrorStylesheets(owner);
        }
        this.name = name;
        this.expression = expression;
        stage.setTitle(tr("valuetable.title", name));
        heading.setText(expression.isBlank() ? name : expression);
        refresh.setDisable(!actions.canRefresh() || expression.isBlank());
        load(ref, named, indexed);
        if (stage.isShowing()) {
            stage.toFront();
        } else {
            WindowPlacement.centerOnOwner(stage, owner, UiZoom.px(WIDTH), UiZoom.px(HEIGHT));
            stage.show();
        }
        stage.requestFocus();
        table.requestFocus();
    }

    /** The session moved on (or ended): Refresh needs a paused program. */
    void sessionChanged() {
        refresh.setDisable(!actions.canRefresh() || expression.isBlank());
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    void hide() {
        stage.hide();
    }

    /** Asks for the elements of {@code ref}, then each one's fields; the table fills in as they come. */
    private void load(int ref, int named, int indexed) {
        long load = ++generation;
        rows.clear();
        pending = 0;
        total = -1;
        model = ValueTable.of(List.of());
        showNote();
        if (ref <= 0) {
            return;
        }
        if (indexed > 0) {
            total = indexed;
            int count = Math.min(indexed, ValueTable.MAX_ROWS);
            if (named > 0) {
                actions.children(ref, "named", 0, 0, namedOnes -> {
                    if (load == generation) {
                        actions.children(ref, "indexed", 0, count, page -> {
                            List<DapModels.VariableInfo> all = new ArrayList<>(namedOnes);
                            all.addAll(page);
                            elementsLoaded(load, all, indexed);
                        });
                    }
                });
            } else {
                actions.children(ref, "indexed", 0, count, page -> elementsLoaded(load, page, indexed));
            }
        } else {
            actions.children(ref, null, 0, 0, all -> elementsLoaded(load, all, -1));
        }
    }

    private void elementsLoaded(long load, List<DapModels.VariableInfo> children, int indexed) {
        if (load != generation) {
            return;
        }
        List<DapModels.VariableInfo> elements = ValueTable.elements(children);
        // The size: a page's count, else what came back (an adapter that pages by itself has said it with indexed).
        total = indexed > 0 ? Math.max(indexed, elements.size()) : elements.size();
        List<DapModels.VariableInfo> shown = elements.subList(0, Math.min(elements.size(), ValueTable.MAX_ROWS));
        List<ValueTable.Row> start = new ArrayList<>();
        for (DapModels.VariableInfo e : shown) {
            start.add(new ValueTable.Row(e.name(), e.value(), e.variablesReference() > 0 ? null : Map.of()));
        }
        rows.setAll(start);
        rebuild();
        for (int i = 0; i < shown.size(); i++) {
            DapModels.VariableInfo e = shown.get(i);
            if (e.variablesReference() > 0) {
                pending++;
                int at = i;
                fieldsOf(load, e.variablesReference(), true, fields -> fieldsLoaded(load, at, fields));
            }
        }
        showNote();
    }

    /** The fields of the element {@code ref}; a pointer's are those of what it points to (once). */
    private void fieldsOf(long load, int ref, boolean followPointer, Consumer<List<DapModels.VariableInfo>> cb) {
        actions.children(ref, null, 0, 0, children -> {
            if (load != generation) {
                return;
            }
            List<DapModels.VariableInfo> fields = ValueTable.fields(children);
            int pointee = followPointer ? ValueTable.pointee(fields) : 0;
            if (pointee > 0) {
                fieldsOf(load, pointee, false, cb);
            } else {
                cb.accept(fields);
            }
        });
    }

    private void fieldsLoaded(long load, int at, List<DapModels.VariableInfo> fields) {
        if (load != generation || at >= rows.size()) {
            return;
        }
        Map<String, String> byName = new LinkedHashMap<>();
        for (DapModels.VariableInfo f : fields) {
            byName.putIfAbsent(f.name(), f.value());
        }
        ValueTable.Row was = rows.get(at);
        rows.set(at, new ValueTable.Row(was.key(), was.value(), byName));
        pending--;
        // Columns change as fields arrive: re-laid out every 50 rows and at the end, not at each one.
        if (pending == 0 || pending % 50 == 0) {
            rebuild();
        }
        showNote();
    }

    /** Makes the columns those of the rows there are, keeping the sort when its columns are still there. */
    private void rebuild() {
        ValueTable next = ValueTable.of(new ArrayList<>(rows));
        boolean same = next.columns()
                .equals(table.getColumns().stream()
                        .map(c -> (String) c.getUserData())
                        .toList());
        model = next;
        filtered.setPredicate(r -> model.matches(r, filter.getText()));
        if (same) {
            return;
        }
        List<String> sortedBy =
                table.getSortOrder().stream().map(c -> (String) c.getUserData()).toList();
        List<TableColumn<ValueTable.Row, String>> columns = new ArrayList<>();
        for (String c : next.columns()) {
            TableColumn<ValueTable.Row, String> col =
                    new TableColumn<>(ValueTable.VALUE_COLUMN.equals(c) ? tr("valuetable.value") : c);
            col.setUserData(c);
            col.setCellValueFactory(
                    cell -> new ReadOnlyStringWrapper(cell.getValue().cell(c)));
            col.setComparator(ValueTable::compare);
            col.setPrefWidth(UiZoom.px(ValueTable.KEY_COLUMN.equals(c) ? 70 : 140));
            columns.add(col);
        }
        table.getColumns().setAll(columns);
        for (String c : sortedBy) {
            columns.stream()
                    .filter(col -> c.equals(col.getUserData()))
                    .findFirst()
                    .ifPresent(col -> table.getSortOrder().add(col));
        }
    }

    private void showNote() {
        String text;
        if (total < 0) {
            text = tr("valuetable.loading");
        } else if (pending > 0) {
            text = tr("valuetable.loadingFields", rows.size() - pending, rows.size());
        } else if (total > rows.size()) {
            text = tr("valuetable.firstOf", rows.size(), total);
        } else {
            text = tr("valuetable.count", rows.size());
        }
        note.setText(text);
    }

    /** The rows the filter keeps, in the order the table shows them. */
    private List<ValueTable.Row> shownRows() {
        return List.copyOf(table.getItems());
    }

    /** Copies the selected rows — every row shown, when none is — as tab-separated text with the columns' names. */
    private void copy() {
        List<ValueTable.Row> selected = List.copyOf(table.getSelectionModel().getSelectedItems());
        DebugPanel.copyToClipboard(model.tsv(selected.isEmpty() ? shownRows() : selected));
    }

    /** Evaluates the expression again at the current stop and shows what it is now. */
    private void refresh() {
        if (expression.isBlank() || !actions.canRefresh()) {
            return;
        }
        actions.evaluate(expression, ev -> {
            if (ev == null || ev.failed() || ev.variablesReference() <= 0) {
                note.setText(tr("valuetable.refreshFailed", expression));
                return;
            }
            load(ev.variablesReference(), ev.namedVariables(), ev.indexedVariables());
        });
    }

    /** A fresh Scene has no stylesheets: the owner's are mirrored, so a theme switch reaches the window too. */
    private void mirrorStylesheets(Window owner) {
        if (owner.getScene() == null) {
            return;
        }
        ObservableList<String> sheets = owner.getScene().getStylesheets();
        stage.getScene().getStylesheets().setAll(sheets);
        sheets.addListener((ListChangeListener<String>)
                change -> stage.getScene().getStylesheets().setAll(sheets));
    }

    // --- test reads ----------------------------------------------------------------------------------------

    TableView<ValueTable.Row> table() {
        return table;
    }

    TextField filter() {
        return filter;
    }

    Label note() {
        return note;
    }

    Button refreshButton() {
        return refresh;
    }

    ValueTable model() {
        return model;
    }
}
