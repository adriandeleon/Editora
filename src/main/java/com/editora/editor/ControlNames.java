package com.editora.editor;

import javafx.scene.control.Control;
import javafx.scene.control.Tooltip;

/**
 * The one place a control gets its name: its tooltip and its accessible text are set together, from one
 * string, so a screen reader says what a sighted user reads. An icon-only button is named here and nowhere
 * else ({@code com.editora.ui.Icons.name} is this, for the panels).
 */
public final class ControlNames {

    private ControlNames() {}

    /** Sets {@code control}'s tooltip and accessible text to {@code name}. Call it again when the name changes. */
    public static <C extends Control> C name(C control, String name) {
        control.setAccessibleText(name);
        Tooltip tooltip = new Tooltip(name);
        // A name that is a sentence (the diff bar's, a toggle that explains itself) wraps instead of running
        // the width of the screen; a short one is unaffected.
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(360);
        control.setTooltip(tooltip);
        return control;
    }

    /**
     * {@code title} followed by the chord the active keymap binds to {@code commandId}, in parentheses, or alone
     * when the command is unbound. The chord is the one the right-click menus show ({@link MenuChords}).
     */
    public static String withChord(String title, String commandId) {
        String chord = MenuChords.chordOf(commandId);
        return chord == null || chord.isBlank() ? title : title + " (" + chord + ")";
    }
}
