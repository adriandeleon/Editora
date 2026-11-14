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
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A find bar for read-only text: a console {@link CodeArea} (the build, Run, External Tools and Debug
 * consoles) or a log shown in a plain text area (the Debug Log window). A query, match case, the count,
 * previous/next and close. The editor's {@link FindReplaceBar} works on an {@code EditorBuffer} and the
 * Terminal's on a scrollback, so neither fits; this is the {@link TerminalFindBar} shape over plain text. It is
 * a node of the window it searches, never a popup.
 *
 * <p>Stateless between steps: every search scans the console's current text (a console is capped at a few
 * hundred thousand characters, which a quoted-pattern scan covers in well under a frame), so output that
 * arrives while the bar is open is searched too and no stale match index is kept. Every match is painted
 * while the bar is open, the active one more strongly, over a console that was given the layer for it
 * ({@link #withMarks}); a plain text control draws one selection and nothing else. Enter goes to the next
 * match after the selection, Shift+Enter to the previous one, Escape closes the bar and gives the keyboard
 * back to the text. Typing refines from where the caret was when the bar opened, so narrowing the query does
 * not run away down the text. The chord the keymap binds to {@code find.show}, pressed again while the bar is
 * open, steps to the next match.
 */
final class ConsoleFindBar extends HBox {

    /** More matches than this are counted as "{@value}+". */
    static final int MAX_MATCHES = 10_000;

    private final TextField query = new TextField();
    private final CheckBox matchCase = new CheckBox("Aa");
    private final Label count = new Label();
    private final Supplier<@Nullable Target> target;
    private @Nullable Runnable onClose;
    /** Where the caret was when the bar opened: typing searches from here, not from the last match. */
    private int anchor;

    /** The text whose matches are painted now, so that they can be taken off it when the bar closes or moves. */
    private @Nullable Target painted;

    /** Stops listening to the text for output that arrives while the bar is open. */
    private Runnable unwatch = () -> {};

    private boolean repaintQueued;

    /** Where the match the find is on starts, or -1: output that arrives can take the selection off it. */
    private int activeStart = -1;

    /** The key under which a console keeps the layer that paints its matches ({@link #marks}). */
    private static final Object MARKS = new Object();

    /**
     * The layer that paints every match of {@code area}, for the caller to lay over the console's scroll pane
     * (a panel that already stacks things over its console adds it to that stack).
     */
    static com.editora.editor.search.SearchHighlightOverlay marks(CodeArea area) {
        com.editora.editor.search.SearchHighlightOverlay marks =
                new com.editora.editor.search.SearchHighlightOverlay(area);
        area.getProperties().put(MARKS, marks);
        return marks;
    }

    /** {@code scroll}, the console's scroll pane, under the layer that paints its matches: laid out in its place. */
    static javafx.scene.layout.StackPane withMarks(CodeArea area, Node scroll) {
        return new javafx.scene.layout.StackPane(scroll, marks(area));
    }

    /** The text a bar searches: what it reads, where its selection is, and how it selects and takes focus. */
    interface Target {
        String text();

        int selectionStart();

        void select(int start, int end);

        void focus();

        /** Paints every one of {@code matches}, {@code active} more strongly; none clears them. */
        default void paint(List<int[]> matches, int active) {}

        /** Calls {@code changed} whenever the text changes; the returned action stops that. */
        default Runnable watch(Runnable changed) {
            return () -> {};
        }

        static @Nullable Target of(@Nullable CodeArea area) {
            if (area == null) {
                return null;
            }
            return new Target() {
                @Override
                public String text() {
                    return area.getText();
                }

                @Override
                public int selectionStart() {
                    return area.getSelection().getStart();
                }

                @Override
                public void select(int start, int end) {
                    area.selectRange(start, end);
                    area.requestFollowCaret();
                }

                @Override
                public void focus() {
                    area.requestFocus();
                }

                @Override
                public void paint(List<int[]> matches, int active) {
                    if (area.getProperties().get(MARKS)
                            instanceof com.editora.editor.search.SearchHighlightOverlay marks) {
                        marks.setMatches(com.editora.text.SearchMatches.ofPairs(matches), active);
                    }
                }

                @Override
                public Runnable watch(Runnable changed) {
                    return area.multiPlainChanges().subscribe(change -> changed.run())::unsubscribe;
                }
            };
        }

        /** A plain text control, which draws one selection and nothing else: the active match is that selection. */
        static @Nullable Target of(@Nullable TextInputControl control) {
            if (control == null) {
                return null;
            }
            return new Target() {
                @Override
                public String text() {
                    return control.getText() == null ? "" : control.getText();
                }

                @Override
                public int selectionStart() {
                    return control.getSelection().getStart();
                }

                @Override
                public void select(int start, int end) {
                    control.selectRange(start, end); // the caret ends at the match's end; the control scrolls to it
                }

                @Override
                public void focus() {
                    control.requestFocus();
                }
            };
        }
    }

    /** @param target the console searched — a supplier, so a panel showing another buffer retargets the bar */
    ConsoleFindBar(Supplier<@Nullable CodeArea> target) {
        this(() -> Target.of(target.get()), true);
    }

    /** A bar over a log shown in a plain text control (the Debug Log window's text area). */
    static ConsoleFindBar overText(Supplier<? extends @Nullable TextInputControl> text) {
        return new ConsoleFindBar(() -> Target.of(text.get()), true);
    }

    /**
     * A bar over {@code area}, wired to the keyboard: the chord the keymap binds to {@code find.show}, pressed
     * in the console, opens it seeded with a one-line selection, and steps to the next match once it is open.
     * The caller adds the returned bar to its layout, above the console.
     */
    static ConsoleFindBar install(CodeArea area) {
        ConsoleFindBar bar = new ConsoleFindBar(() -> area);
        ConsoleNav.onCommand(area, "find.show", () -> bar.openOrNext(area.getSelectedText()));
        return bar;
    }

    private ConsoleFindBar(Supplier<@Nullable Target> target, boolean ignored) {
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
            } else if (isFindChord(e)) {
                step(true); // Find again, with the bar already open: the next match
                e.consume();
            }
        });
        // The keymap's caret and editing chords (C-a, C-e, C-k, C-y; Ctrl+V/X/C/Z) act on the field.
        com.editora.command.TextInputKeymap.installShared(query);

        getChildren().addAll(query, OptionToggle.viewOf(matchCase, tr("search.caseTip")), count, previous, next, close);
        setVisible(false);
        setManaged(false);
    }

    /** Whether {@code e} is the chord the active keymap binds to {@code find.show}. */
    static boolean isFindChord(KeyEvent e) {
        com.editora.command.KeymapManager keymap = com.editora.command.TextInputKeymap.sharedKeymap();
        String chord = com.editora.command.KeyDispatcher.chord(e);
        return keymap != null && chord != null && "find.show".equals(keymap.commandFor(chord));
    }

    /** Where Escape and the close button go, in place of the default (the text the bar searches). */
    void setOnClose(@Nullable Runnable onClose) {
        this.onClose = onClose;
    }

    /** Shows the bar, seeding the query with {@code seed} (a one-line selection) when there is one. */
    void open(@Nullable String seed) {
        Target text = target.get();
        anchor = text == null ? 0 : text.selectionStart();
        if (seed != null && !seed.isBlank() && !seed.contains("\n")) {
            query.setText(seed);
        }
        setVisible(true);
        setManaged(true);
        watch(text);
        refine();
        query.requestFocus();
        query.selectAll();
    }

    /** Follows {@code text} for output that arrives while the bar is open: its matches are painted too. */
    private void watch(@Nullable Target text) {
        unwatch.run();
        unwatch = text == null ? () -> {} : text.watch(this::repaintSoon);
    }

    /** The text changed under an open bar: paint its matches again, once for a burst of output. */
    private void repaintSoon() {
        if (repaintQueued) {
            return;
        }
        repaintQueued = true;
        javafx.application.Platform.runLater(() -> {
            repaintQueued = false;
            Target text = target.get();
            if (text == null || !isShowing()) {
                return;
            }
            List<int[]> matches = matches(text.text());
            int at = -1;
            for (int i = 0; i < matches.size() && at < 0; i++) {
                at = matches.get(i)[0] == activeStart ? i : -1;
            }
            if (at >= 0 || matches.isEmpty()) {
                show(text, matches, at); // the count grows with the output, the find staying on its match
            } else {
                paint(text, matches, -1); // its match was trimmed away: the count stays until the next step
            }
        });
    }

    private void paint(@Nullable Target text, List<int[]> matches, int at) {
        Target before = painted;
        if (before != null && before != text) {
            before.paint(List.of(), -1);
        }
        painted = matches.isEmpty() ? null : text;
        if (text != null) {
            text.paint(matches, at);
        }
    }

    /** Find, asked for again: opens the bar, or with the bar already open steps to the next match. */
    void openOrNext(@Nullable String seed) {
        if (isShowing()) {
            step(true);
            query.requestFocus();
        } else {
            open(seed);
        }
    }

    boolean isShowing() {
        return isVisible();
    }

    /** Hides the bar and gives the keyboard back to the text it searched. Nothing is pending to cancel. */
    void close() {
        setVisible(false);
        setManaged(false);
        unwatch.run();
        unwatch = () -> {};
        paint(null, List.of(), -1);
        Runnable after = onClose;
        if (after != null) {
            after.run();
        } else {
            Target text = target.get();
            if (text != null) {
                text.focus();
            }
        }
    }

    /** The console shown changed (the previous run's buffer, say): search it instead. */
    void retarget() {
        if (isShowing()) {
            paint(null, List.of(), -1); // off the console that was showing
            watch(target.get());
            refine();
        }
    }

    TextField field() {
        return query;
    }

    String countText() {
        return count.getText();
    }

    /** The match at or after the anchor, else the first — what typing into the field keeps in view. */
    private void refine() {
        Target area = target.get();
        if (area == null || !isShowing()) {
            return;
        }
        List<int[]> matches = matches(area.text());
        if (matches.isEmpty()) {
            show(area, matches, -1);
            return;
        }
        int from = Math.min(anchor, area.text().length());
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
        Target area = target.get();
        if (area == null) {
            return;
        }
        List<int[]> matches = matches(area.text());
        if (matches.isEmpty()) {
            show(area, matches, -1);
            return;
        }
        int selectionStart = area.selectionStart();
        int at;
        if (forward) {
            at = 0;
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i)[0] > selectionStart) {
                    at = i;
                    break;
                }
            }
        } else {
            at = matches.size() - 1;
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i)[0] < selectionStart) {
                    at = i;
                    break;
                }
            }
        }
        select(area, matches, at);
    }

    private void select(Target area, List<int[]> matches, int at) {
        int[] match = matches.get(at);
        area.select(match[0], match[1]);
        activeStart = match[0];
        show(area, matches, at);
    }

    private void show(Target area, List<int[]> matches, int at) {
        if (at < 0) {
            activeStart = -1;
        }
        paint(area, matches, at);
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
        Icons.name(button, name);
        button.setFocusTraversable(false);
        button.getStyleClass().addAll("button-icon", "flat", "find-bar-icon");
        return button;
    }
}
