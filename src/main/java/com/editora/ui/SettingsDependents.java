package com.editora.ui;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.Node;
import javafx.scene.control.Label;

import static com.editora.i18n.Messages.tr;

/**
 * The one Settings rule for rows that only matter while another switch is on: the rows grey out while it is
 * off, and a one-line note names the switch that turns them back on. Every page uses this instead of deciding
 * on its own whether (and how) a feature's sub-settings follow its master switch.
 */
final class SettingsDependents {

    private SettingsDependents() {}

    /** Greys out {@code rows} while {@code on} is false. A row bound here must not also set its own disable. */
    static void bind(ObservableBooleanValue on, Node... rows) {
        for (Node row : rows) {
            row.disableProperty().bind(Bindings.not(on));
        }
    }

    /**
     * A note shown only while {@code on} is false — "Turn on “{@code switchTitle}” to change these settings." —
     * that takes no space while hidden. Place it directly above or below the rows it explains.
     */
    static Label note(ObservableBooleanValue on, String switchTitle) {
        Label note = new Label(tr("settings.dependsOn", switchTitle));
        note.getStyleClass().addAll("settings-row-desc", "settings-depends-note");
        note.setWrapText(true);
        note.visibleProperty().bind(Bindings.not(on));
        note.managedProperty().bind(note.visibleProperty());
        return note;
    }
}
