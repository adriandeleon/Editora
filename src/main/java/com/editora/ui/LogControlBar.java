package com.editora.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
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

import com.editora.editor.ControlNames;
import com.editora.logviewer.LogFilter;
import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogQuery;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The log viewer's control bar, docked above a log's text: a Follow ({@code tail -f}) toggle, a minimum-level
 * combo, a field of records to show and one of records to hide, a Match Case toggle for both, and a note of
 * what the view is showing ("23 of 186 lines"). It only renders the
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
    private final TextField hide = new TextField();
    private final CheckBox matchCase = new CheckBox("Aa");
    private final Label state = new Label();
    private final Tooltip patternTip = ControlNames.tooltip(tr("tooltip.logFilter"));
    private final Tooltip hideTip = ControlNames.tooltip(tr("tooltip.logHide"));
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));

    private static final PseudoClass LITERAL = PseudoClass.getPseudoClass("literal");

    private final BiConsumer<LogLevel, LogQuery> onFilter;
    /** Set while the widgets are being made to show the buffer's state: nothing is reported back. */
    private boolean showing;

    public LogControlBar(Consumer<Boolean> onFollow, BiConsumer<LogLevel, LogQuery> onFilter, Runnable onLeave) {
        this.onFilter = onFilter;

        getStyleClass().add("log-controls");
        setAlignment(Pos.CENTER_LEFT);

        follow.setGraphic(Icons.arrowDown());
        follow.getStyleClass().addAll("log-follow", "flat");
        follow.setAccessibleHelp(tr("tooltip.logFollow"));
        ControlNames.tip(follow, tr("tooltip.logFollow"));
        follow.setOnAction(e -> onFollow.accept(follow.isSelected()));

        level.getItems().addAll(levelLabels());
        level.getSelectionModel().selectFirst();
        level.getStyleClass().add("log-level-combo");
        ControlNames.tip(level, tr("tooltip.logLevel"));
        level.setAccessibleText(tr("tooltip.logLevel"));
        level.setOnAction(e -> {
            if (!showing) {
                fire();
            }
        });

        pattern.setPromptText(tr("log.filter.prompt"));
        pattern.setTooltip(patternTip);
        pattern.setAccessibleText(tr("log.filter.label"));
        patternField(pattern, onLeave);
        hide.setPromptText(tr("log.hide.prompt"));
        hide.setTooltip(hideTip);
        hide.setAccessibleText(tr("log.hide.label"));
        hide.getStyleClass().add("log-hide-field");
        patternField(hide, onLeave);
        // Live filtering: re-apply (debounced) as the user types, so a pattern filters without needing Enter.
        debounce.setOnFinished(e -> fire());

        matchCase.selectedProperty().addListener((obs, was, now) -> {
            if (!showing) {
                fire();
            }
        });
        ToggleButton caseToggle = OptionToggle.viewOf(matchCase, tr("search.caseTip"));
        caseToggle.getStyleClass().add("log-case-toggle");
        ControlNames.tip(caseToggle, tr("search.caseTip"));
        // Find's Match Case chord flips this one while the keyboard is in the bar.
        FocusCommands.bindIf(this, "find.toggleCase", PanelCommands.toggle(matchCase));

        state.getStyleClass().add("log-state");
        state.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        getChildren().addAll(follow, level, pattern, hide, caseToggle, gap, state);
    }

    /** The shared wiring of the two pattern fields: keymap, Enter, Escape, live filtering, validity. */
    private void patternField(TextField field, Runnable onLeave) {
        // The configured keymap's caret/editing chords act on this field (the KeyDispatcher leaves them to it).
        com.editora.command.TextInputKeymap.installShared(field);
        field.getStyleClass().add("log-filter-field");
        HBox.setHgrow(field, Priority.SOMETIMES);
        field.setOnAction(e -> fire()); // Enter applies now; the pending debounce must not apply it again
        field.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                onLeave.run();
                e.consume();
            }
        });
        field.textProperty().addListener((obs, old, now) -> {
            markValidity(field, now);
            if (!showing) {
                debounce.playFromStart();
            }
        });
    }

    private void fire() {
        debounce.stop();
        onFilter.accept(selectedLevel(), query());
    }

    /**
     * A pattern that is not a valid regular expression still filters — as plain text — and the field says so,
     * rather than leaving the user to work out why {@code (a|b} matches nothing they expected.
     */
    private void markValidity(TextField field, String text) {
        boolean literal = !LogFilter.isValidRegex(text);
        field.pseudoClassStateChanged(LITERAL, literal);
        Tooltip tip = field == hide ? hideTip : patternTip;
        String normal = field == hide ? "tooltip.logHide" : "tooltip.logFilter";
        tip.setText(tr(literal ? "tooltip.logFilterLiteral" : normal));
    }

    /** Reflects the buffer's follow state in the toggle (without reporting it back). */
    public void setFollowing(boolean following) {
        follow.setSelected(following);
    }

    /** The active filter's time range, which the bar has no field for: kept so typing a pattern does not drop it. */
    private @Nullable Long from;

    private @Nullable Long to;

    /** Shows {@code min}/{@code text} as the active filter (without reporting it back). */
    public void showFilter(@Nullable LogLevel min, LogQuery text) {
        from = text.from();
        to = text.to();
        showing = true;
        try {
            level.getSelectionModel().select(Math.max(0, LEVELS.indexOf(min)));
            show(pattern, text.include());
            show(hide, text.exclude());
            // With no pattern there is no filter to read the case from: the toggle keeps what the user set.
            if (!text.isEmpty()) {
                matchCase.setSelected(text.matchCase());
            }
        } finally {
            showing = false;
        }
    }

    private static void show(TextField field, @Nullable String text) {
        String wanted = text == null ? "" : text;
        // Not while the user is typing in it: the buffer's filter trails the field by the debounce.
        if (!field.isFocused() && !wanted.equals(field.getText())) {
            field.setText(wanted);
        }
    }

    /**
     * Shows what the view holds: how many of the log's lines a filter shows, whether its top was dropped, and
     * whether a pattern was too slow on some lines (which were then taken as not matching it).
     */
    public void showState(boolean filtered, int visible, int total, boolean trimmed, boolean slow) {
        java.util.List<String> parts = new java.util.ArrayList<>(4);
        String range = rangeText(from, to);
        if (filtered && range != null) {
            parts.add(range);
        }
        if (filtered) {
            parts.add(tr("log.state.filtered", visible, total));
        }
        if (slow) {
            parts.add(tr("log.state.slow"));
        }
        if (trimmed) {
            parts.add(tr("log.state.trimmed"));
        }
        state.setText(String.join(" · ", parts));
        state.pseudoClassStateChanged(SLOW, slow);
    }

    private static final PseudoClass SLOW = PseudoClass.getPseudoClass("warning");

    /** Puts the keyboard in the pattern field, with its text selected. */
    public void focusPattern() {
        pattern.requestFocus();
        pattern.selectAll();
    }

    /** The minimum level currently selected, or {@code null} for "All". */
    public LogLevel selectedLevel() {
        return LEVELS.get(Math.max(0, level.getSelectionModel().getSelectedIndex()));
    }

    /** The text filter as typed, with the active time range. Not trimmed: a pattern may be about its spaces. */
    public LogQuery query() {
        return new LogQuery(pattern.getText(), hide.getText(), matchCase.isSelected(), from, to);
    }

    /** A time range as the state note says it, or null for none. */
    static @Nullable String rangeText(@Nullable Long from, @Nullable Long to) {
        com.editora.logviewer.LogTimes.Context context = com.editora.logviewer.LogTimes.Context.local();
        if (from != null && to != null) {
            return tr(
                    "log.state.between",
                    com.editora.logviewer.LogTimes.format(from, context),
                    com.editora.logviewer.LogTimes.format(to, context));
        }
        if (from != null) {
            return tr("log.state.from", com.editora.logviewer.LogTimes.format(from, context));
        }
        if (to != null) {
            return tr("log.state.to", com.editora.logviewer.LogTimes.format(to, context));
        }
        return null;
    }

    /** What the state note reads (for tests). */
    String stateText() {
        return state.getText();
    }
}
