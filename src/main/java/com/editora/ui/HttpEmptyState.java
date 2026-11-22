package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the response view shows before any response (and after Clear): which request Run will send — the one at the
 * caret, by its title, name or method and URL — the Run chord from the live keymap, that the gutter's ▶ runs a
 * request, and the file's requests as rows. A click on a row, or Enter on it, runs that request; Up and Down move
 * between the rows. Filled by {@link HttpEmptyStateFeed} while it is on show.
 *
 * <p>The rows are a virtualized list (PERF-08, Pauta 6.2.0): an API collection of hundreds of requests builds the
 * rows on screen, and a request added near the top of the file rebuilds no row.
 */
final class HttpEmptyState extends VBox {

    /** One request of the file: the line of its request line, its method, its name and its URL as written. */
    record Row(int line, String method, String label, String url) {}

    private static final PseudoClass AT_CARET = PseudoClass.getPseudoClass("caret");
    /** Stands in for the argument a pattern's bold or keycap part goes in. */
    private static final String ARG = "\u0001";

    private final IntConsumer onRun;
    private final TextFlow title = new TextFlow();
    private final HBox chordHint = UiZoom.spacing(new HBox(), 6);
    private final Label chord = new Label();
    private final Label gutterHint = new Label(tr("httppanel.empty.gutter"));
    private final Label listTitle = new Label(tr("httppanel.empty.requests"));
    private final ListView<Row> rows = new ListView<>();
    private List<Row> shown = List.of();
    private int caretRow = -1;
    private boolean blocked;

    /** {@code onRun} is given the line of the request a row stands for. */
    HttpEmptyState(IntConsumer onRun) {
        this.onRun = onRun;
        getStyleClass().add("http-empty");
        UiZoom.spacing(this, 8);
        UiZoom.padding(this, 14, 12, 8, 12);
        setFillWidth(true);
        title.getStyleClass().add("http-empty-title");
        chord.getStyleClass().addAll("chord-chip", "http-run-chord");
        chord.setMinWidth(Region.USE_PREF_SIZE);
        chordHint.setAlignment(Pos.CENTER_LEFT);
        chordHint.getStyleClass().add("http-empty-hint");
        String[] around = tr("httppanel.empty.chord", ARG).split(ARG, -1);
        if (!around[0].isBlank()) {
            chordHint.getChildren().add(hint(around[0].strip()));
        }
        chordHint.getChildren().add(chord);
        if (around.length > 1 && !around[1].isBlank()) {
            chordHint.getChildren().add(hint(around[1].strip()));
        }
        gutterHint.getStyleClass().add("http-empty-hint");
        gutterHint.setWrapText(true);
        listTitle.getStyleClass().add("http-empty-heading");
        rows.getStyleClass().addAll("http-empty-rows", "http-empty-scroll");
        rows.setAccessibleText(tr("httppanel.empty.requests"));
        rows.setCellFactory(view -> new RowCell());
        UiZoom.follow(rows, () -> rows.setFixedCellSize(UiZoom.px(ROW_HEIGHT)));
        // Enter runs the row the keyboard is on; Up and Down are the list's own.
        rows.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShortcutDown() && !e.isAltDown()) {
                run(rows.getSelectionModel().getSelectedItem());
                e.consume();
            }
        });
        VBox.setVgrow(rows, Priority.ALWAYS);
        UiZoom.follow(listTitle, () -> VBox.setMargin(listTitle, UiZoom.insets(8, 0, 0, 0)));
        getChildren().addAll(title, chordHint, gutterHint, listTitle, rows);
        setChord(null);
        setRequests(List.of(), -1);
    }

    /** A row's height at 100 % zoom: the method chip and the name on one line, padded as the buttons were. */
    static final double ROW_HEIGHT = 28;

    private static Label hint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("http-empty-hint");
        return l;
    }

    /** Shows the run chord ({@code null}: the command is unbound — the line goes). */
    void setChord(@Nullable String text) {
        boolean on = text != null && !text.isEmpty();
        chord.setText(on ? text : "");
        chordHint.setVisible(on);
        chordHint.setManaged(on);
    }

    /** A remote file's requests do not run from here: the rows are shown but cannot be run. */
    void setRunBlocked(boolean blocked) {
        this.blocked = blocked;
        rows.refresh();
    }

    /** Runs the request {@code r} stands for, unless the file's requests do not run from here. */
    private void run(@Nullable Row r) {
        if (r != null && !blocked) {
            onRun.accept(r.line());
        }
    }

    /**
     * Shows the file's requests, {@code caret} (an index into {@code requests}, -1 for none) being the one Run sends.
     * Rows are rebuilt only when the requests changed: moving the caret only moves the mark.
     */
    void setRequests(List<Row> requests, int caret) {
        List<Row> now = requests == null ? List.of() : List.copyOf(requests);
        int was = caretRow;
        caretRow = caret >= 0 && caret < now.size() ? caret : -1;
        if (!now.equals(shown)) {
            shown = now;
            int selected = rows.getSelectionModel().getSelectedIndex();
            rows.getItems().setAll(now);
            if (selected >= 0 && selected < now.size()) {
                rows.getSelectionModel().select(selected);
            }
        } else if (was != caretRow) {
            rows.refresh(); // the mark moves: only the rows on screen are drawn again
        }
        showTitle(caretRow < 0 ? null : now.get(caretRow).label());
        boolean any = !now.isEmpty();
        listTitle.setText(any ? tr("httppanel.empty.requests") : tr("httppanel.empty.none"));
        rows.setVisible(any);
        rows.setManaged(any);
    }

    private void showTitle(@Nullable String request) {
        if (request == null) {
            Text t = new Text(tr("httppanel.empty.noRequest"));
            t.getStyleClass().add("http-empty-lead");
            title.getChildren().setAll(t);
            return;
        }
        String[] around = tr("httppanel.empty.willSend", ARG).split(ARG, -1);
        List<Node> parts = new ArrayList<>(3);
        if (!around[0].isEmpty()) {
            parts.add(styled(new Text(around[0]), "http-empty-lead"));
        }
        parts.add(styled(new Text(request), "http-empty-name"));
        if (around.length > 1 && !around[1].isEmpty()) {
            parts.add(styled(new Text(around[1]), "http-empty-lead"));
        }
        title.getChildren().setAll(parts);
    }

    private static Text styled(Text t, String styleClass) {
        t.getStyleClass().add(styleClass);
        return t;
    }

    /** A request's row: its method, its name and, when the name does not end with it, its URL. */
    private final class RowCell extends ListCell<Row> {
        private final Label method = new Label();
        private final Label name = new Label();
        private final Label where = new Label();
        private final HBox box = UiZoom.spacing(new HBox(method, name, where), 10);

        RowCell() {
            getStyleClass().add("http-empty-row");
            method.getStyleClass().add("http-empty-method");
            method.setMinWidth(Region.USE_PREF_SIZE);
            name.getStyleClass().add("http-empty-name-cell");
            name.setMinWidth(0);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            where.getStyleClass().add("http-empty-url");
            where.setMinWidth(0);
            where.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
            where.managedProperty().bind(where.visibleProperty());
            HBox.setHgrow(where, Priority.ALWAYS);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setMinWidth(0);
            setPrefWidth(0); // never wider than the list: a long URL is cut short, the list does not scroll sideways
            addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getButton() == MouseButton.PRIMARY && !isEmpty()) {
                    run(getItem());
                }
            });
        }

        @Override
        protected void updateItem(Row r, boolean empty) {
            super.updateItem(r, empty);
            setText(null);
            if (empty || r == null) {
                setGraphic(null);
                setAccessibleText(null);
                pseudoClassStateChanged(AT_CARET, false);
                setDisable(false);
                return;
            }
            method.setText(r.method() == null ? "" : r.method());
            name.setText(r.label());
            String url = r.url() == null ? "" : r.url();
            where.setText(url);
            where.setVisible(!url.isEmpty() && !r.label().endsWith(url));
            setAccessibleText(runRowText(r));
            pseudoClassStateChanged(AT_CARET, getIndex() == caretRow);
            setDisable(blocked);
            setGraphic(box);
        }
    }

    /** What a screen reader says for a row: "Run {name}". */
    private static String runRowText(Row r) {
        return tr("httppanel.empty.runRow", r.label());
    }

    // --- test accessors ---

    /** "Run will send Get one user" or "Put the caret in a request". Test accessor. */
    String titleForTest() {
        StringBuilder sb = new StringBuilder();
        for (Node n : title.getChildren()) {
            sb.append(((Text) n).getText());
        }
        return sb.toString();
    }

    /** The rows, in file order. Test accessor. */
    List<Row> rowsForTest() {
        return List.copyOf(rows.getItems());
    }

    /** What a screen reader says for the row at {@code index}. Test accessor. */
    String rowAccessibleTextForTest(int index) {
        return runRowText(rows.getItems().get(index));
    }

    /** The list of rows. Test accessor. */
    ListView<Row> rowListForTest() {
        return rows;
    }

    /** The chord shown, or null when the line is hidden. Test accessor. */
    @Nullable
    String chordForTest() {
        return chordHint.isVisible() ? chord.getText() : null;
    }
}
