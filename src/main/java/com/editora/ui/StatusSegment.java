package com.editora.ui;

import javafx.scene.AccessibleAttribute;
import javafx.scene.input.KeyCode;

import com.editora.editor.Pressable;
import com.editora.editor.PressableLabel;

/**
 * A status-bar segment that does something when pressed: a {@link PressableLabel} that reads and looks like its
 * neighbours, but is a button to everyone. ACC-02 (Pauta 6.0.1): the {@code BUTTON} role, a screen reader's
 * {@code FIRE}, Enter or Space once Tab has reached it (KBD-15), and a click all run its action.
 *
 * <p>The echo line is one too (it opens the Message Log); {@link #announce} tells a screen reader that its text
 * is new, so a status message is read out where it lands.
 */
final class StatusSegment extends PressableLabel {

    /** How many times {@link #announce} told assistive technology the text changed (tests). */
    int announcements;

    StatusSegment(Runnable action) {
        super(action);
        getStyleClass().add("status-segment-clickable");
    }

    /** Whether a key press presses the segment: an unmodified Enter or Space. Pure. */
    static boolean isActivationKey(KeyCode code, boolean modified) {
        return Pressable.isActivationKey(code, modified);
    }

    /**
     * Tells a screen reader the segment's text is new, even when it equals the last one (the same message said
     * twice is still news). A {@code Label} notifies only when its text changes.
     */
    void announce() {
        announcements++;
        notifyAccessibleAttributeChanged(AccessibleAttribute.TEXT);
    }
}
