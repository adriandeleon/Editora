package com.editora.ui;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import com.editora.logviewer.LogLevel;

import static com.editora.i18n.Messages.tr;

/**
 * The floating log-viewer control overlaid at the top-right of a {@code .log} editor (mirroring
 * {@link MarkdownViewToggle}/{@link HtmlPreviewToggle}): a Follow ({@code tail -f}) toggle, a minimum-level
 * combo, and a regex filter field. The behaviour (following, filtering) lives in {@code MainController}; this
 * control only renders the widgets and reports changes through the injected callbacks.
 */
public class LogControlBar extends HBox {

    /** The level-combo entries; index 0 = "All" (no floor), the rest map to {@link LogLevel} values. */
    private static final LogLevel[] LEVELS = {
        null, LogLevel.TRACE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR
    };

    private final ToggleButton follow = new ToggleButton();
    private final ComboBox<String> level = new ComboBox<>();
    private final TextField regex = new TextField();

    private final Consumer<Boolean> onFollow;
    private final BiConsumer<LogLevel, String> onFilter;

    public LogControlBar(Consumer<Boolean> onFollow, BiConsumer<LogLevel, String> onFilter) {
        this.onFollow = onFollow;
        this.onFilter = onFilter;

        getStyleClass().add("log-controls");
        setAlignment(Pos.CENTER_RIGHT);
        setSpacing(4);
        setPadding(new Insets(2, 4, 2, 4));
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        setPickOnBounds(false);

        follow.setGraphic(Icons.arrowDown());
        follow.getStyleClass().addAll("log-follow", "flat");
        follow.setFocusTraversable(false);
        follow.setTooltip(new Tooltip(tr("tooltip.logFollow")));
        follow.setOnAction(e -> onFollow.accept(follow.isSelected()));

        level.getItems()
                .addAll(
                        tr("log.level.all"),
                        tr("log.level.trace"),
                        tr("log.level.debug"),
                        tr("log.level.info"),
                        tr("log.level.warn"),
                        tr("log.level.error"));
        level.getSelectionModel().selectFirst();
        level.setFocusTraversable(false);
        level.getStyleClass().add("log-level-combo");
        level.setTooltip(new Tooltip(tr("tooltip.logLevel")));
        level.setOnAction(e -> fireFilter());

        regex.setPromptText(tr("log.filter.prompt"));
        regex.getStyleClass().add("log-filter-field");
        regex.setPrefColumnCount(12);
        regex.setFocusTraversable(false);
        regex.setTooltip(new Tooltip(tr("tooltip.logFilter")));
        regex.setOnAction(e -> fireFilter()); // apply immediately on Enter
        // Live filtering: re-apply (debounced) as the user types, so a regex filters without needing Enter —
        // the query is treated as a regex (literal fallback for an invalid/partial one, see LogFilter).
        PauseTransition debounce = new PauseTransition(Duration.millis(300));
        debounce.setOnFinished(e -> fireFilter());
        regex.textProperty().addListener((obs, old, now) -> debounce.playFromStart());

        getChildren().addAll(follow, level, regex);
    }

    private void fireFilter() {
        LogLevel min = LEVELS[Math.max(0, level.getSelectionModel().getSelectedIndex())];
        String pattern = regex.getText() == null ? "" : regex.getText().strip();
        onFilter.accept(min, pattern.isEmpty() ? null : pattern);
    }

    /** Reflects the buffer's current follow state in the toggle (without firing the callback). */
    public void setFollowing(boolean following) {
        follow.setSelected(following);
    }

    /** Resets the level + regex widgets to "no filter" (without firing the callback). */
    public void clearFilterControls() {
        level.setOnAction(null);
        regex.setOnAction(null);
        level.getSelectionModel().selectFirst();
        regex.clear();
        level.setOnAction(e -> fireFilter());
        regex.setOnAction(e -> fireFilter());
    }

    /** The minimum level currently selected, or {@code null} for "All". */
    public LogLevel selectedLevel() {
        return LEVELS[Math.max(0, level.getSelectionModel().getSelectedIndex())];
    }

    /** The current regex text, or {@code null} when empty. */
    public String regexText() {
        String t = regex.getText() == null ? "" : regex.getText().strip();
        return t.isEmpty() ? null : t;
    }
}
