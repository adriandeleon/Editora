package com.editora.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import com.editora.logviewer.LogFilter;
import com.editora.logviewer.LogLevel;

import static com.editora.i18n.Messages.tr;

/**
 * The log viewer's control bar, docked above a log's text: a Follow ({@code tail -f}) toggle, a minimum-level
 * combo, a pattern field, and a note of what the view is showing ("23 of 186 lines"). It only renders the
 * widgets and reports what the user did through the injected callbacks; the state it shows is the buffer's,
 * pushed in by {@link LogViewerCoordinator} — so the bar and the palette commands cannot disagree about which
 * filter is on.
 */
public class LogControlBar extends HBox {

    /** The levels a floor can be set to; index 0 = no floor. One table for the bar and the palette picker. */
    static final List<LogLevel> LEVELS = java.util.Arrays.asList(
            null, LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL);

    /** The labels of {@link #LEVELS}, in the same order. */
    static List<String> levelLabels() {
        return List.of(
                tr("log.level.all"),
                tr("log.level.trace"),
                tr("log.level.debug"),
                tr("log.level.info"),
                tr("log.level.warn"),
                tr("log.level.error"),
                tr("log.level.fatal"));
    }

    private final ToggleButton follow = new ToggleButton(tr("log.follow"));
    private final ComboBox<String> level = new ComboBox<>();
    private final TextField pattern = new TextField();
    private final Label state = new Label();
    private final Tooltip patternTip = new Tooltip(tr("tooltip.logFilter"));
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));

    private final BiConsumer<LogLevel, String> onFilter;
    /** Set while the widgets are being made to show the buffer's state: nothing is reported back. */
    private boolean showing;

    public LogControlBar(Consumer<Boolean> onFollow, BiConsumer<LogLevel, String> onFilter, Runnable onLeave) {
        this.onFilter = onFilter;

        getStyleClass().add("log-controls");
        setAlignment(Pos.CENTER_LEFT);

        follow.setGraphic(Icons.arrowDown());
        follow.getStyleClass().addAll("log-follow", "flat");
        follow.setTooltip(new Tooltip(tr("tooltip.logFollow")));
        follow.setAccessibleHelp(tr("tooltip.logFollow"));
        follow.setOnAction(e -> onFollow.accept(follow.isSelected()));

        level.getItems().addAll(levelLabels());
        level.getSelectionModel().selectFirst();
        level.getStyleClass().add("log-level-combo");
        level.setTooltip(new Tooltip(tr("tooltip.logLevel")));
        level.setAccessibleText(tr("tooltip.logLevel"));
        level.setOnAction(e -> {
            if (!showing) {
                fire();
            }
        });

        pattern.setPromptText(tr("log.filter.prompt"));
        // The configured keymap's caret/editing chords act on this field (the KeyDispatcher leaves them to it).
        com.editora.command.TextInputKeymap.installShared(pattern);
        pattern.getStyleClass().add("log-filter-field");
        pattern.setTooltip(patternTip);
        pattern.setAccessibleText(tr("log.filter.label"));
        HBox.setHgrow(pattern, Priority.SOMETIMES);
        pattern.setOnAction(e -> fire()); // Enter applies now; the pending debounce must not apply it again
        pattern.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                onLeave.run();
                e.consume();
            }
        });
        // Live filtering: re-apply (debounced) as the user types, so a pattern filters without needing Enter.
        debounce.setOnFinished(e -> fire());
        pattern.textProperty().addListener((obs, old, now) -> {
            markValidity(now);
            if (!showing) {
                debounce.playFromStart();
            }
        });

        state.getStyleClass().add("log-state");
        state.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        getChildren().addAll(follow, level, pattern, gap, state);
    }

    private void fire() {
        debounce.stop();
        onFilter.accept(selectedLevel(), patternText());
    }

    /**
     * A pattern that is not a valid regular expression still filters — as plain text — and the field says so,
     * rather than leaving the user to work out why {@code (a|b} matches nothing they expected.
     */
    private void markValidity(String text) {
        boolean literal = !LogFilter.isValidRegex(text);
        pattern.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("literal"), literal);
        patternTip.setText(tr(literal ? "tooltip.logFilterLiteral" : "tooltip.logFilter"));
    }

    /** Reflects the buffer's follow state in the toggle (without reporting it back). */
    public void setFollowing(boolean following) {
        follow.setSelected(following);
    }

    /** Shows {@code min}/{@code text} as the active filter (without reporting it back). */
    public void showFilter(LogLevel min, String text) {
        showing = true;
        try {
            level.getSelectionModel().select(Math.max(0, LEVELS.indexOf(min)));
            String wanted = text == null ? "" : text;
            // Not while the user is typing in it: the buffer's filter trails the field by the debounce.
            if (!pattern.isFocused() && !wanted.equals(pattern.getText())) {
                pattern.setText(wanted);
            }
        } finally {
            showing = false;
        }
    }

    /** Shows what the view holds: how many of the log's lines a filter shows, and whether its top was dropped. */
    public void showState(boolean filtered, int visible, int total, boolean trimmed) {
        String count = filtered ? tr("log.state.filtered", visible, total) : "";
        String dropped = trimmed ? tr("log.state.trimmed") : "";
        state.setText(count.isEmpty() || dropped.isEmpty() ? count + dropped : count + " · " + dropped);
    }

    /** Puts the keyboard in the pattern field, with its text selected. */
    public void focusPattern() {
        pattern.requestFocus();
        pattern.selectAll();
    }

    /** The minimum level currently selected, or {@code null} for "All". */
    public LogLevel selectedLevel() {
        return LEVELS.get(Math.max(0, level.getSelectionModel().getSelectedIndex()));
    }

    /** The pattern as typed, or {@code null} when empty. Not trimmed: a pattern may be about its spaces. */
    public String patternText() {
        String text = pattern.getText();
        return text == null || text.isEmpty() ? null : text;
    }

    /** What the state note reads (for tests). */
    String stateText() {
        return state.getText();
    }
}
