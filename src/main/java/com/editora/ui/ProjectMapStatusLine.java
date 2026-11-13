package com.editora.ui;

import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import com.editora.editor.ControlNames;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Project Map window's own status line, under the Map (Project Map review 3 A2). Everything the Map has to
 * say lands here — a folder it cannot read, the row limit, a search that did not reach the whole project — in the
 * window the user is looking at, not in the editor window's status bar behind it.
 *
 * <p>A search's notice belongs to its query: it stays until another message replaces it or the query changes
 * ({@link #clearSearch}). Any other message stays until the next one.
 */
final class ProjectMapStatusLine extends HBox {

    private final Label text = new Label();
    /** Whether the line shows a search's notice, which goes with its query. */
    private boolean fromSearch;

    ProjectMapStatusLine() {
        getStyleClass().add("project-map-status-line");
        text.getStyleClass().add("project-map-status-text");
        text.setTextOverrun(OverrunStyle.ELLIPSIS);
        text.setMinWidth(0);
        text.setMaxWidth(Double.MAX_VALUE);
        text.setAccessibleRole(AccessibleRole.TEXT);
        text.setAccessibleHelp(tr("project.map.statusLine"));
        HBox.setHgrow(text, Priority.ALWAYS);
        getChildren().add(text);
    }

    /** Shows {@code message} until the next one. */
    void show(@Nullable String message) {
        set(message, false);
    }

    /** Shows a search's notice: it goes when the query changes or is cleared. */
    void showSearch(@Nullable String message) {
        set(message, true);
    }

    /** The query changed or was cleared: its notice, if the line still shows it, goes too. */
    void clearSearch() {
        if (fromSearch) {
            set("", false);
        }
    }

    /** What the line shows now (tests). */
    String text() {
        return text.getText();
    }

    private void set(@Nullable String message, boolean search) {
        String value = message == null ? "" : message.strip();
        fromSearch = search && !value.isEmpty();
        if (value.equals(text.getText())) {
            return;
        }
        text.setText(value);
        // A long notice is cut to the window's width; the tooltip holds all of it.
        text.setTooltip(value.isEmpty() ? null : ControlNames.tooltip(value));
        text.getStyleClass().removeAll("project-map-status-warn", "project-map-status-error");
        switch (StatusSeverity.of(value)) {
            case ERROR -> text.getStyleClass().add("project-map-status-error");
            case WARN -> text.getStyleClass().add("project-map-status-warn");
            default -> {}
        }
        // Read out where a screen reader is: the change itself tells no one otherwise (B11).
        text.notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
    }
}
