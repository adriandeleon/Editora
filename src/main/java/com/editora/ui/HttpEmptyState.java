package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What the response view shows before any response (and after Clear): which request Run will send — the one at the
 * caret, by its title, name or method and URL — the Run chord from the live keymap, that the gutter's ▶ runs a
 * request, and the file's requests as rows. A click on a row, or Enter on it, runs that request; Up and Down move
 * between the rows. Filled by {@link HttpEmptyStateFeed} while it is on show.
 */
final class HttpEmptyState extends VBox {

    /** One request of the file: the line of its request line, its method, its name and its URL as written. */
    record Row(int line, String method, String label, String url) {}

    private static final PseudoClass AT_CARET = PseudoClass.getPseudoClass("caret");
    /** Stands in for the argument a pattern's bold or keycap part goes in. */
    private static final String ARG = "\u0001";

    private final IntConsumer onRun;
    private final TextFlow title = new TextFlow();
    private final HBox chordHint = new HBox(6);
    private final Label chord = new Label();
    private final Label gutterHint = new Label(tr("httppanel.empty.gutter"));
    private final Label listTitle = new Label(tr("httppanel.empty.requests"));
    private final VBox rows = new VBox(1);
    private final ScrollPane rowsScroll = new ScrollPane(rows);
    private List<Row> shown = List.of();
    private int caretRow = -1;
    private boolean blocked;

    /** {@code onRun} is given the line of the request a row stands for. */
    HttpEmptyState(IntConsumer onRun) {
        this.onRun = onRun;
        getStyleClass().add("http-empty");
        setSpacing(8);
        setPadding(new Insets(14, 12, 8, 12));
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
        rows.getStyleClass().add("http-empty-rows");
        rowsScroll.setFitToWidth(true);
        rowsScroll.getStyleClass().add("http-empty-scroll");
        rowsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(rowsScroll, Priority.ALWAYS);
        VBox.setMargin(listTitle, new Insets(8, 0, 0, 0));
        getChildren().addAll(title, chordHint, gutterHint, listTitle, rowsScroll);
        setChord(null);
        setRequests(List.of(), -1);
    }

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
        rows.getChildren().forEach(n -> n.setDisable(blocked));
    }

    /**
     * Shows the file's requests, {@code caret} (an index into {@code requests}, -1 for none) being the one Run sends.
     * Rows are rebuilt only when the requests changed: moving the caret only moves the mark.
     */
    void setRequests(List<Row> requests, int caret) {
        List<Row> now = requests == null ? List.of() : List.copyOf(requests);
        if (!now.equals(shown)) {
            shown = now;
            List<Node> built = new ArrayList<>(now.size());
            for (Row r : now) {
                built.add(row(r));
            }
            rows.getChildren().setAll(built);
        }
        caretRow = caret >= 0 && caret < now.size() ? caret : -1;
        for (int i = 0; i < rows.getChildren().size(); i++) {
            rows.getChildren().get(i).pseudoClassStateChanged(AT_CARET, i == caretRow);
        }
        showTitle(caretRow < 0 ? null : now.get(caretRow).label());
        boolean any = !now.isEmpty();
        listTitle.setText(any ? tr("httppanel.empty.requests") : tr("httppanel.empty.none"));
        rowsScroll.setVisible(any);
        rowsScroll.setManaged(any);
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

    private Button row(Row r) {
        Label method = new Label(r.method() == null ? "" : r.method());
        method.getStyleClass().add("http-empty-method");
        method.setMinWidth(Region.USE_PREF_SIZE);
        Label name = new Label(r.label());
        name.getStyleClass().add("http-empty-name-cell");
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox box = new HBox(10, method, name);
        box.setAlignment(Pos.CENTER_LEFT);
        String url = r.url() == null ? "" : r.url();
        if (!url.isEmpty() && !r.label().endsWith(url)) {
            Label where = new Label(url);
            where.getStyleClass().add("http-empty-url");
            where.setMinWidth(0);
            where.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
            HBox.setHgrow(where, Priority.ALWAYS);
            box.getChildren().add(where);
        }
        Button b = new Button();
        b.setGraphic(box);
        b.getStyleClass().addAll("flat", "http-empty-row");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setMnemonicParsing(false);
        b.setAccessibleText(tr("httppanel.empty.runRow", r.label()));
        b.setDisable(blocked);
        b.setOnAction(e -> onRun.accept(r.line()));
        b.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShortcutDown() && !e.isAltDown()) {
                b.fire();
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
                int i = rows.getChildren().indexOf(b) + (e.getCode() == KeyCode.DOWN ? 1 : -1);
                if (i >= 0 && i < rows.getChildren().size()) {
                    rows.getChildren().get(i).requestFocus();
                }
                e.consume();
            }
        });
        return b;
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

    /** The rows' buttons, in file order. Test accessor. */
    List<Button> rowsForTest() {
        return rows.getChildren().stream().map(Button.class::cast).toList();
    }

    /** The chord shown, or null when the line is hidden. Test accessor. */
    @Nullable
    String chordForTest() {
        return chordHint.isVisible() ? chord.getText() : null;
    }
}
