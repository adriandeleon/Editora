package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * A find bar for a read-only console {@link CodeArea} (the Output tabs — Maven review B22): a query, match
 * case, the count, previous/next and close. The editor's {@link FindReplaceBar} works on an
 * {@code EditorBuffer} and the Terminal's on a scrollback, so neither fits a console; this is the
 * {@link TerminalFindBar} shape over plain text.
 *
 * <p>Stateless between steps: every search scans the console's current text (a console is capped at a few
 * hundred thousand characters, which a quoted-pattern scan covers in well under a frame), so output that
 * arrives while the bar is open is searched too and no stale match index is kept. Enter goes to the next
 * match after the selection, Shift+Enter to the previous one, Escape closes the bar and gives the keyboard
 * back to the console. Typing refines in place: the match at or after the selection is kept selected.
 */
final class ConsoleFindBar extends HBox {

    /** More matches than this are counted as "{@value}+". */
    static final int MAX_MATCHES = 10_000;

    private final TextField query = new TextField();
    private final CheckBox matchCase = new CheckBox("Aa");
    private final Label count = new Label();
    private final Supplier<CodeArea> target;
    private Runnable onClose = () -> {};

    /** @param target the console searched — a supplier, so a panel showing another buffer retargets the bar */
    ConsoleFindBar(Supplier<CodeArea> target) {
        this.target = target;
        getStyleClass().addAll("find-bar", "console-find-bar");
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Region.USE_PREF_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        query.setPromptText(tr("find.prompt"));
        query.setPrefColumnCount(18);
        count.getStyleClass().add("find-count");
        count.setMinWidth(Region.USE_PREF_SIZE);

        Button previous = iconButton(Icons.findPrevious(), tr("find.prev"));
        Button next = iconButton(Icons.findNext(), tr("find.next"));
        Button close = iconButton(Icons.closeTab(), tr("find.close"));
        previous.setOnAction(e -> step(false));
        next.setOnAction(e -> step(true));
        close.setOnAction(e -> close());

        query.textProperty().addListener((o, was, now) -> refine());
        matchCase.selectedProperty().addListener((o, was, now) -> refine());
        query.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                step(!e.isShiftDown());
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            }
        });

        getChildren().addAll(query, OptionToggle.viewOf(matchCase, tr("search.caseTip")), count, previous, next, close);
        setVisible(false);
        setManaged(false);
    }

    /** Where Escape and the close button go: back to the console. */
    void setOnClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> {} : onClose;
    }

    /** Shows the bar, seeding the query with {@code seed} (a one-line selection) when there is one. */
    void open(String seed) {
        if (seed != null && !seed.isBlank() && !seed.contains("\n")) {
            query.setText(seed);
        }
        setVisible(true);
        setManaged(true);
        refine();
        query.requestFocus();
        query.selectAll();
    }

    boolean isShowing() {
        return isVisible();
    }

    void close() {
        setVisible(false);
        setManaged(false);
        onClose.run();
    }

    /** The console shown changed (the previous run's buffer, say): search it instead. */
    void retarget() {
        if (isShowing()) {
            refine();
        }
    }

    TextField field() {
        return query;
    }

    String countText() {
        return count.getText();
    }

    /** The match at or after the selection, else the first — what typing into the field keeps in view. */
    private void refine() {
        CodeArea area = target.get();
        if (area == null || !isShowing()) {
            return;
        }
        List<int[]> matches = matches(area.getText());
        if (matches.isEmpty()) {
            show(matches, -1);
            return;
        }
        int from = area.getSelection().getStart();
        int at = 0;
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i)[0] >= from) {
                at = i;
                break;
            }
        }
        select(area, matches, at);
    }

    /** The next match after the selection ({@code forward}) or the last one before it, wrapping around. */
    private void step(boolean forward) {
        CodeArea area = target.get();
        if (area == null) {
            return;
        }
        List<int[]> matches = matches(area.getText());
        if (matches.isEmpty()) {
            show(matches, -1);
            return;
        }
        IndexRange selection = area.getSelection();
        int at;
        if (forward) {
            at = 0;
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i)[0] > selection.getStart()) {
                    at = i;
                    break;
                }
            }
        } else {
            at = matches.size() - 1;
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i)[0] < selection.getStart()) {
                    at = i;
                    break;
                }
            }
        }
        select(area, matches, at);
    }

    private void select(CodeArea area, List<int[]> matches, int at) {
        int[] match = matches.get(at);
        area.selectRange(match[0], match[1]);
        area.requestFollowCaret();
        show(matches, at);
    }

    private void show(List<int[]> matches, int at) {
        if (query.getText().isEmpty()) {
            count.setText("");
        } else if (matches.isEmpty()) {
            count.setText(tr("search.none"));
        } else {
            String total = matches.size() >= MAX_MATCHES ? MAX_MATCHES + "+" : Integer.toString(matches.size());
            count.setText(tr("find.count", at + 1, total));
        }
    }

    /** Every {@code [start, end)} of the query in {@code text}, at most {@link #MAX_MATCHES} of them. */
    List<int[]> matches(String text) {
        String needle = query.getText();
        List<int[]> out = new ArrayList<>();
        if (needle.isEmpty() || text == null || text.isEmpty()) {
            return out;
        }
        // A quoted pattern rather than a lower-cased indexOf: case folding can change a string's length.
        int flags = matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Matcher m = Pattern.compile(Pattern.quote(needle), flags).matcher(text);
        while (m.find() && out.size() < MAX_MATCHES) {
            out.add(new int[] {m.start(), m.end()});
        }
        return out;
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
