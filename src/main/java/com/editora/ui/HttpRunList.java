package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import static com.editora.i18n.Messages.tr;

/**
 * The response view's Run All results, between the header row and the tab strip: a title line ("Run All · 3 of 10
 * failed · 4.1 s", the count as a coloured pill), an "Only failures" filter and a collapse chevron, then one row per
 * request in run order ({@link HttpRunRows}). Selecting a row shows that response in the tabs below; Enter or a
 * double-click goes to the request in the editor; a row's menu runs it again or copies it as cURL. The list scrolls
 * on its own and the panel caps its height, so the tabs keep their room. Collapsed and the filter are remembered for
 * the panel's life; the rows stay until the next run or Clear ({@link #hide}).
 */
final class HttpRunList extends VBox {

    private static final List<String> PILL_STYLES = List.of("http-run-ok", "http-run-partial", "http-run-failed");
    private static final List<String> TEST_STYLES = List.of("http-test-passed", "http-test-failed");

    private final Label title = new Label(tr("httppanel.runList.title"));
    private final Label pill = new Label();
    /** "tests failed in 1 request", red; hidden when none failed. */
    private final Label testsNote = new Label();
    /** The run's time ("4.1 s"), muted; hidden when unknown. */
    private final Label took = new Label();

    private final CheckBox onlyFailures = new CheckBox(tr("httppanel.runList.onlyFailures"));
    private final Button chevron;
    private final ListView<HttpRunRows.Row> list = new ListView<>();
    private final Label placeholder = new Label(tr("httppanel.runList.noFailures"));

    /** The widths of the status, time and tests columns: the widest of their texts, so the rows line up. */
    private final DoubleProperty statusWidth = new SimpleDoubleProperty();

    private final DoubleProperty timeWidth = new SimpleDoubleProperty();
    private final DoubleProperty testsWidth = new SimpleDoubleProperty();
    /** Measures a cell's texts. */
    private final Text probe = new Text();

    private List<HttpRunRows.Row> rows = List.of();
    /** The entry whose row was last selected (kept while "Only failures" hides it), or null. */
    private HttpHistoryEntry selected;

    private boolean collapsed;
    private boolean syncing;

    private final Consumer<HttpHistoryEntry> onSelected;
    private final Consumer<HttpHistoryEntry> onJump;
    private final Consumer<HttpHistoryEntry> onRunAgain;
    private final Consumer<HttpHistoryEntry> onCopyAsCurl;

    /**
     * @param onSelected   told when the user selects a row (show its response)
     * @param onJump       Enter or a double-click on a row: go to its request in the editor
     * @param onRunAgain   a row's "Run again"
     * @param onCopyAsCurl a row's "Copy as cURL" (offered only for a request that was sent)
     */
    HttpRunList(
            Consumer<HttpHistoryEntry> onSelected,
            Consumer<HttpHistoryEntry> onJump,
            Consumer<HttpHistoryEntry> onRunAgain,
            Consumer<HttpHistoryEntry> onCopyAsCurl) {
        this.onSelected = onSelected;
        this.onJump = onJump;
        this.onRunAgain = onRunAgain;
        this.onCopyAsCurl = onCopyAsCurl;
        getStyleClass().add("http-run-list");
        setSpacing(4);

        title.getStyleClass().add("http-run-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        pill.getStyleClass().addAll("state-pill", "http-run-pill");
        pill.setMinWidth(Region.USE_PREF_SIZE);
        testsNote.getStyleClass().add("http-run-tests-note");
        testsNote.setMinWidth(Region.USE_PREF_SIZE);
        took.getStyleClass().add("http-run-detail");
        took.setMinWidth(Region.USE_PREF_SIZE);
        onlyFailures.getStyleClass().add("http-run-only-failures");
        onlyFailures.setMinWidth(Region.USE_PREF_SIZE);
        onlyFailures.selectedProperty().addListener((o, a, b) -> refill());
        chevron = Icons.button(Icons.findPrevious(), tr("httppanel.runList.collapse"), this::toggleCollapsed);
        chevron.getStyleClass().addAll("flat", "http-action", "http-run-chevron");
        chevron.setMinWidth(Region.USE_PREF_SIZE);
        HBox controls = new HBox(6, onlyFailures, chevron);
        controls.setAlignment(Pos.CENTER_RIGHT);
        controls.setMinWidth(Region.USE_PREF_SIZE);
        HBox heading = new HBox(8, title, pill);
        heading.setAlignment(Pos.CENTER_LEFT);
        heading.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        // Words stay whole: in a narrow Split the notes, then the filter and the chevron, move to a line below.
        WrapRow top = new WrapRow(8, 2, heading, testsNote, took, WrapRow.setGrow(spacer), controls);
        top.getStyleClass().add("http-run-top");

        list.getStyleClass().add("http-run-rows");
        list.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        list.setCellFactory(v -> new RowCell());
        list.setPlaceholder(placeholder);
        list.setAccessibleText(tr("httppanel.runList.name"));
        list.setMinHeight(0);
        // As tall as its rows (at least one, for the "No failures" line); the panel caps the whole section.
        list.prefHeightProperty()
                .bind(Bindings.createDoubleBinding(
                        () -> Math.max(1, list.getItems().size()) * cellSize() + 4,
                        list.getItems(),
                        list.fixedCellSizeProperty()));
        list.getSelectionModel().selectedItemProperty().addListener((o, a, row) -> {
            if (!syncing && row != null) {
                selected = row.entry();
                this.onSelected.accept(row.entry());
            }
        });
        list.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            HttpRunRows.Row row = list.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && !e.isShortcutDown() && !e.isAltDown() && row != null) {
                this.onJump.accept(row.entry());
                e.consume();
            }
        });
        list.setContextMenu(buildMenu());
        VBox.setVgrow(list, Priority.ALWAYS);
        // Preview ↔ Split, a resize or a new run changes its height: the selected row stays in view.
        list.heightProperty().addListener((o, a, b) -> Platform.runLater(() -> reveal(false)));

        getChildren().addAll(top, list);
        hide();
    }

    private double cellSize() {
        double fixed = list.getFixedCellSize();
        return fixed > 0 ? fixed : 26;
    }

    private ContextMenu buildMenu() {
        MenuItem runAgain = new MenuItem(tr("httppanel.runList.runAgain"), Icons.run());
        runAgain.setOnAction(e -> withRow(onRunAgain));
        MenuItem copy = new MenuItem(tr("httppanel.copyAsCurl"), Icons.copy());
        copy.setOnAction(e -> withRow(onCopyAsCurl));
        MenuItem jump = new MenuItem(tr("httppanel.runList.goTo"));
        jump.setOnAction(e -> withRow(onJump));
        ContextMenu menu = new ContextMenu(runAgain, copy, jump);
        menu.setOnShowing(e -> {
            HttpRunRows.Row row = list.getSelectionModel().getSelectedItem();
            runAgain.setDisable(row == null);
            jump.setDisable(row == null);
            // A request that was never sent has no command to copy: its URL and headers may still be templates.
            copy.setDisable(row == null || HttpStatusText.notSent(row.entry().result()));
        });
        return menu;
    }

    private void withRow(Consumer<HttpHistoryEntry> action) {
        HttpRunRows.Row row = list.getSelectionModel().getSelectedItem();
        if (row != null && action != null) {
            action.accept(row.entry());
        }
    }

    /**
     * Shows the results of a Run All over {@code entries} (run order), {@code cancelledOf} requests long when it was
     * cancelled (0: it finished), which took {@code tookMs} (-1 unknown), with {@code select}'s row selected (it is
     * shown already, so nobody is told).
     */
    void show(List<HttpHistoryEntry> entries, int cancelledOf, long tookMs, HttpHistoryEntry select) {
        rows = HttpRunRows.rows(entries);
        HttpRunRows.Title t = HttpRunRows.title(entries, cancelledOf, tookMs);
        pill.setText(t.pill());
        pill.getStyleClass().removeAll(PILL_STYLES);
        pill.getStyleClass()
                .add(
                        switch (t.outcome()) {
                            case OK -> "http-run-ok";
                            case PARTIAL -> "http-run-partial";
                            case FAILED -> "http-run-failed";
                        });
        showNote(testsNote, t.testsFailed());
        showNote(took, t.time());
        setAccessibleText(t.spoken());
        title.setAccessibleText(t.spoken());
        statusWidth.set(0);
        timeWidth.set(0);
        testsWidth.set(0);
        selected = select;
        setVisible(true);
        setManaged(true);
        refill();
        Platform.runLater(() -> reveal(true)); // once laid out at its new height
    }

    /**
     * Scrolls the selected row fully into view, moving as little as possible; {@code withContext} first puts the row
     * before it at the top, so the first failure is seen with what ran before it.
     */
    private void reveal(boolean withContext) {
        int at = list.getSelectionModel().getSelectedIndex();
        if (at < 0 || collapsed || !isShown() || !(list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow)) {
            return;
        }
        if (withContext) {
            flow.scrollToTop(Math.max(0, at - 1));
        }
        flow.scrollTo(at);
    }

    private static void showNote(Label label, String text) {
        label.setText(text);
        label.setVisible(!text.isEmpty());
        label.setManaged(!text.isEmpty());
    }

    /** No Run All results (a new run started, Clear, or a single request ran). */
    void hide() {
        rows = List.of();
        selected = null;
        syncing = true;
        list.getItems().clear();
        syncing = false;
        setVisible(false);
        setManaged(false);
    }

    /** Whether the results show. */
    boolean isShown() {
        return isVisible();
    }

    /**
     * {@code entry} (a History pick, or null) is now shown: its row is selected when it has one, otherwise no row is.
     * Nobody is told (it is shown already).
     */
    void select(HttpHistoryEntry entry) {
        if (!isShown()) {
            return;
        }
        selected = rowOf(rows, entry) == null ? null : rowOf(rows, entry).entry();
        syncSelection();
    }

    /** The rows the filter leaves, the remembered selection kept when its row is among them. */
    private void refill() {
        List<HttpRunRows.Row> shown = HttpRunRows.filter(rows, onlyFailures.isSelected());
        syncing = true;
        list.getItems().setAll(shown);
        syncing = false;
        syncSelection();
        Platform.runLater(() -> reveal(false));
    }

    private void syncSelection() {
        HttpRunRows.Row row = rowOf(list.getItems(), selected);
        syncing = true;
        if (row == null) {
            list.getSelectionModel().clearSelection();
        } else {
            list.getSelectionModel().select(row);
            list.getFocusModel().focus(list.getItems().indexOf(row));
        }
        syncing = false;
    }

    private static HttpRunRows.Row rowOf(List<HttpRunRows.Row> rows, HttpHistoryEntry entry) {
        if (entry == null) {
            return null;
        }
        for (HttpRunRows.Row r : rows) {
            if (HttpRunRows.sameRun(r.entry(), entry)) {
                return r;
            }
        }
        return null;
    }

    private void toggleCollapsed() {
        setCollapsed(!collapsed);
    }

    private void setCollapsed(boolean on) {
        collapsed = on;
        list.setVisible(!on);
        list.setManaged(!on);
        chevron.setGraphic(on ? Icons.findNext() : Icons.findPrevious());
        Icons.name(chevron, tr(on ? "httppanel.runList.expand" : "httppanel.runList.collapse"));
        if (!on) {
            Platform.runLater(() -> reveal(true));
        }
    }

    /** Widens {@code column} to fit {@code label}'s text in its font (bold when {@code bold}), plus {@code pad}. */
    private void fit(Label label, DoubleProperty column, boolean bold, double pad) {
        Font f = label.getFont() == null ? Font.getDefault() : label.getFont();
        probe.setFont(bold ? Font.font(f.getFamily(), FontWeight.BOLD, f.getSize()) : f);
        probe.setText(label.getText());
        double w = Math.ceil(probe.getLayoutBounds().getWidth()) + pad;
        if (w > column.get()) {
            column.set(w);
        }
    }

    /** One request's row: status pill, name with the method and path muted beside it, time, tests. */
    private final class RowCell extends ListCell<HttpRunRows.Row> {
        private final Label status = new Label();
        private final Label name = new Label();
        private final Label path = new Label();
        private final Label time = new Label();
        private final Label tests = new Label();
        private final HBox box;

        RowCell() {
            getStyleClass().add("http-run-row");
            status.getStyleClass().addAll("state-pill", "http-run-status");
            status.setAlignment(Pos.CENTER);
            status.minWidthProperty().bind(statusWidth);
            status.prefWidthProperty().bind(statusWidth);
            name.getStyleClass().add("http-run-name");
            name.setMinWidth(0);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            path.getStyleClass().add("http-run-path");
            path.setMinWidth(0);
            path.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS); // a long path keeps its start and its end
            HBox.setHgrow(path, Priority.ALWAYS);
            path.setMaxWidth(Double.MAX_VALUE);
            time.getStyleClass().add("http-run-time");
            time.setAlignment(Pos.CENTER_RIGHT);
            time.minWidthProperty().bind(timeWidth);
            time.prefWidthProperty().bind(timeWidth);
            tests.getStyleClass().add("http-run-tests");
            tests.setAlignment(Pos.CENTER_RIGHT);
            tests.minWidthProperty().bind(testsWidth);
            tests.prefWidthProperty().bind(testsWidth);
            box = new HBox(8, status, name, path, time, tests);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setMinWidth(0);
            box.setPrefWidth(0); // the list's width, not the texts', decides: a long path is cut, not scrolled to
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null) {
                    onJump.accept(getItem().entry());
                }
            });
            setOnContextMenuRequested(e -> {
                if (!isEmpty()) {
                    list.getSelectionModel().select(getIndex()); // the menu acts on the row it was opened on
                }
            });
        }

        @Override
        protected void updateItem(HttpRunRows.Row row, boolean empty) {
            super.updateItem(row, empty);
            setText(null);
            if (empty || row == null) {
                setGraphic(null);
                setAccessibleText(null);
                setTooltip(null);
                return;
            }
            status.setText(row.status());
            status.getStyleClass().removeAll(HttpStatusText.ALL);
            status.getStyleClass().remove(HttpRunRows.CANCELLED);
            status.getStyleClass().add(row.statusStyle());
            name.setText(row.name());
            path.setText(row.detail() == null ? "" : row.detail());
            time.setText(row.time());
            tests.setText(row.tests());
            tests.getStyleClass().removeAll(TEST_STYLES);
            if (row.testsStyle() == HttpRunRows.Tests.FAILED) {
                tests.getStyleClass().add("http-test-failed");
            }
            fit(status, statusWidth, true, 20);
            fit(time, timeWidth, false, 4);
            fit(tests, testsWidth, true, 4);
            setGraphic(box);
            String spoken = row.spoken();
            setAccessibleText(spoken);
            if (getTooltip() == null) {
                setTooltip(new Tooltip());
            }
            getTooltip().setText(spoken);
        }
    }

    // --- test accessors ---

    /** The title line as shown: "Run All", the pill, then the notes that show, joined by " · ". Test accessor. */
    String titleForTest() {
        StringBuilder sb = new StringBuilder(title.getText()).append(" · ").append(pill.getText());
        for (Label note : List.of(testsNote, took)) {
            if (note.isVisible()) {
                sb.append(" · ").append(note.getText());
            }
        }
        return sb.toString();
    }

    /** The pill's colour class. Test accessor. */
    String pillStyleForTest() {
        return pill.getStyleClass().stream()
                .filter(PILL_STYLES::contains)
                .findFirst()
                .orElse(null);
    }

    ListView<HttpRunRows.Row> listForTest() {
        return list;
    }

    CheckBox onlyFailuresForTest() {
        return onlyFailures;
    }

    Button chevronForTest() {
        return chevron;
    }

    boolean collapsedForTest() {
        return collapsed;
    }
}
