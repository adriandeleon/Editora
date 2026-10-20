package com.editora.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import com.editora.terminal.TerminalSearch;
import com.editora.terminal.TerminalView;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Terminal's find bar: a query, match case, the count, previous/next and close. It searches the selected
 * tab's scrollback ({@link TerminalView#search}); Enter goes to the next hit up (older output, as a terminal is
 * read bottom-up), Shift+Enter down, Escape closes it and gives the keyboard back to the shell.
 *
 * <p>Typing searches once the query has held still for {@link #TYPING_SETTLE}, and the scan runs off the FX thread
 * ({@link TerminalView#searchAsync}): a scrollback of 100,000 lines takes a tenth of a second or more to scan, which
 * on every keystroke made typing in the field stutter.
 */
final class TerminalFindBar extends HBox {

    private final TextField query = new TextField();
    private final CheckBox matchCase = new CheckBox("Aa");
    private final Label count = new Label();
    private @Nullable TerminalView view;
    private Runnable onClose = () -> {};

    /** How long the query must hold still before it is searched. */
    static final javafx.util.Duration TYPING_SETTLE = javafx.util.Duration.millis(150);

    private final javafx.animation.PauseTransition typing = new javafx.animation.PauseTransition(TYPING_SETTLE);

    TerminalFindBar() {
        getStyleClass().addAll("find-bar", "terminal-find-bar");
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Region.USE_PREF_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        query.setPromptText(tr("find.prompt"));
        query.setPrefColumnCount(18);
        count.getStyleClass().add("find-count");
        count.setMinWidth(Region.USE_PREF_SIZE);

        Button older = iconButton(Icons.findPrevious(), tr("find.prev"));
        Button newer = iconButton(Icons.findNext(), tr("find.next"));
        Button close = iconButton(Icons.closeTab(), tr("find.close"));
        older.setOnAction(e -> step(true));
        newer.setOnAction(e -> step(false));
        close.setOnAction(e -> close());

        typing.setOnFinished(e -> run());
        query.textProperty().addListener((o, was, now) -> typing.playFromStart());
        matchCase.selectedProperty().addListener((o, was, now) -> run());
        query.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                step(!e.isShiftDown());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            }
        });

        getChildren().addAll(query, OptionToggle.viewOf(matchCase, tr("search.caseTip")), count, older, newer, close);
        setVisible(false);
        setManaged(false);
    }

    /** Where Escape and ✕ go: back to the terminal. */
    void setOnClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> {} : onClose;
    }

    /** Shows the bar over {@code target}'s tab, searching it, with the field focused and selected. */
    void open(TerminalView target, String seed) {
        bindTo(target);
        if (seed != null && !seed.isBlank() && !seed.contains("\n")) {
            query.setText(seed);
        }
        setVisible(true);
        setManaged(true);
        run();
        query.requestFocus();
        query.selectAll();
    }

    /** Searches {@code target} instead (another tab was selected); keeps the query. */
    void bindTo(TerminalView target) {
        if (view != null && view != target) {
            view.clearSearch();
        }
        view = target;
        if (isShowing()) {
            run();
        }
    }

    boolean isShowing() {
        return isVisible();
    }

    void close() {
        typing.stop();
        setVisible(false);
        setManaged(false);
        if (view != null) {
            view.clearSearch();
        }
        onClose.run();
    }

    TextField field() {
        return query;
    }

    String countText() {
        return count.getText();
    }

    private void run() {
        typing.stop();
        if (view == null || !isVisible()) {
            return;
        }
        String text = query.getText();
        view.searchAsync(text, matchCase.isSelected(), result -> {
            if (text.equals(query.getText())) show(result);
        });
    }

    private void step(boolean older) {
        if (view == null) {
            return;
        }
        if (typing.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            run(); // Enter before the query settled: search what was typed, then step from there
            return;
        }
        view.findNextAsync(older, this::show);
    }

    private void show(TerminalView.SearchResult result) {
        if (query.getText().isEmpty()) {
            count.setText("");
        } else if (result.count() == 0) {
            count.setText(tr("search.none"));
        } else {
            String total = result.count() >= TerminalSearch.MAX_MATCHES
                    ? TerminalSearch.MAX_MATCHES + "+"
                    : Integer.toString(result.count());
            count.setText(tr("find.count", result.index(), total));
        }
    }

    private static Button iconButton(Node graphic, String name) {
        Button button = new Button();
        button.setGraphic(graphic);
        button.setTooltip(new Tooltip(name));
        button.setAccessibleText(name);
        button.setFocusTraversable(false);
        button.getStyleClass().addAll("button-icon", "flat", "find-bar-icon");
        return button;
    }
}
