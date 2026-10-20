package com.editora.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The state a Settings row shows beside its setting: whether its value differs from the default (a marker at
 * the row's leading edge, "Modified" for a screen reader), a scope note ("Project", "Overridden by this
 * project"), and a "Reset to default" action — a link under the row's text while the row is modified, and the
 * row's context menu.
 *
 * <p>One state per row node, made on first use: {@code SettingRowState.of(row).setModified(true)}. The note and
 * the link sit under the title and description of a {@link SettingRowPane} row (the rows {@code settingRow}
 * builds); on another kind of row they are tracked but not drawn.
 */
final class SettingRowState {

    /** The row's pseudo-class while modified (app.css draws the marker). */
    static final PseudoClass MODIFIED = PseudoClass.getPseudoClass("modified");

    private static final Object KEY = new Object();
    private static final Object HELP_KEY = new Object();

    private final Node row;
    private final BooleanProperty modified = new SimpleBooleanProperty(false);
    private final Label scopeNote = new Label();
    private final Hyperlink reset = new Hyperlink(tr("settings.row.reset"));
    private final HBox line = new HBox(8, scopeNote, reset);
    private @Nullable Runnable resetAction;

    private SettingRowState(Node row) {
        this.row = row;
        scopeNote.getStyleClass().add("settings-scope-note");
        reset.getStyleClass().add("settings-row-reset");
        reset.setOnAction(e -> resetNow());
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("settings-row-state");
        SettingsSearch.setShown(scopeNote, false);
        SettingsSearch.setShown(reset, false);
        SettingsSearch.setShown(line, false);
        if (row instanceof SettingRowPane pane && pane.text() instanceof VBox text) {
            text.getChildren().add(line);
        }
        modified.addListener((o, was, now) -> refresh());
        row.setOnContextMenuRequested(e -> {
            if (resetAction != null && modified.get()) {
                MenuItem item = new MenuItem(tr("settings.row.reset"));
                item.setOnAction(a -> resetNow());
                new ContextMenu(item).show(row, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });
    }

    /** The state of {@code row}, made on first use. */
    static SettingRowState of(Node row) {
        return (SettingRowState) row.getProperties().computeIfAbsent(KEY, k -> new SettingRowState(row));
    }

    /** The state of {@code row} if it has one, else null (it was never marked). */
    static @Nullable SettingRowState peek(Node row) {
        return row.getProperties().get(KEY) instanceof SettingRowState s ? s : null;
    }

    /** Whether the row's value differs from the default: the marker, and "Modified" for assistive technology. */
    void setModified(boolean on) {
        modified.set(on);
    }

    boolean isModified() {
        return modified.get();
    }

    BooleanProperty modifiedProperty() {
        return modified;
    }

    /** A short note under the row's text, such as "Project"; null or blank for none. */
    void setScopeNote(@Nullable String note) {
        boolean shown = note != null && !note.isBlank();
        scopeNote.setText(shown ? note : "");
        SettingsSearch.setShown(scopeNote, shown);
        refresh();
    }

    String scopeNote() {
        return scopeNote.getText();
    }

    /** The reset link's text: "Reset to default", or {@code label} (Project scope: "Use my setting"); null = default. */
    void setResetLabel(@Nullable String label) {
        String text = label == null ? tr("settings.row.reset") : label;
        reset.setText(text);
    }

    /** What "Reset to default" does for this row (null: the row cannot be reset); shown while it is modified. */
    void setResetAction(@Nullable Runnable action) {
        this.resetAction = action;
        refresh();
    }

    /** Runs the reset action, if the row has one. */
    void resetNow() {
        if (resetAction != null) {
            resetAction.run();
        }
    }

    /** The "Reset to default" link (tests and keyboard focus). */
    Hyperlink resetLink() {
        return reset;
    }

    private void refresh() {
        boolean on = modified.get();
        row.pseudoClassStateChanged(MODIFIED, on);
        SettingsSearch.setShown(reset, on && resetAction != null);
        SettingsSearch.setShown(line, reset.isVisible() || scopeNote.isVisible());
        announce(row, on);
    }

    /**
     * Puts "Modified" in the accessible help of the row's controls (what a screen reader reads after a control's
     * name), keeping whatever help a control had of its own.
     */
    private void announce(Node node, boolean on) {
        if (node == reset || node == scopeNote) {
            return;
        }
        if (node instanceof Control c) {
            if (!c.getProperties().containsKey(HELP_KEY)) {
                c.getProperties().put(HELP_KEY, c.getAccessibleHelp() == null ? "" : c.getAccessibleHelp());
            }
            String own = (String) c.getProperties().get(HELP_KEY);
            String mark = tr("settings.row.modified");
            c.setAccessibleHelp(on ? (own.isBlank() ? mark : mark + ". " + own) : (own.isBlank() ? null : own));
        }
        if (node instanceof Parent parent && !(node instanceof Control)) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                announce(child, on);
            }
        }
    }
}
